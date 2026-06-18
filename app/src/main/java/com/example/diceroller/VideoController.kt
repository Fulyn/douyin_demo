package com.example.diceroller

import android.content.ContentResolver
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlin.math.max
import kotlin.math.roundToInt

interface VideoInterface {
    val videoItem: VideoItem?
    val videoResId: Int?
        get() = videoItem?.videoResId
    val isDraggingProgress: Boolean
    val playerView: PlayerView

    fun setCover(bitmap: Bitmap?, showCover: Boolean)
    fun hideCover()
    fun clearCover()
    fun setPausedUiVisible(visible: Boolean)
    fun updateProgressUi(progress: Float)
    fun resetControlUi()
}

@UnstableApi
class VideoController(
    context: Context,
    resources: Resources,
    private val currentVideoProvider: () -> VideoInterface?,
    private val boundVideosProvider: () -> List<VideoInterface>
) {

    private val appContext = context.applicationContext
    private val videoPositionsByVideoResId = mutableMapOf<Int, Long>()
    private val coverStore = VideoCoverStore(resources, ::onCoverReady)
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressUpdateRunnable = object : Runnable {
        override fun run() {
            if (!isUpdatingProgress) return

            updateCurrentProgressUi()
            progressHandler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS)
        }
    }

    private var sharedPlayer: ExoPlayer? = null
    private var currentVideo: VideoInterface? = null
    private var waitingForFirstFrameVideoResId: Int? = null
    private var isCurrentVideoManuallyPaused = false
    private var isUpdatingProgress = false

    fun coverFor(videoItem: VideoItem): Bitmap? {
        coverStore.renderedCoverFor(videoItem.videoResId)?.let { return it }
        if (videoPositionsByVideoResId.containsKey(videoItem.videoResId)) return null

        return coverStore.packagedCoverFor(videoItem.coverResId)
    }

    fun isCurrentVideo(video: VideoInterface): Boolean {
        return currentVideo === video
    }

    fun onVideoClicked(video: VideoInterface) {
        toggleVideo(video)
    }

    fun playCurrentVideo() {
        val video = currentVideoProvider() ?: return
        play(video)
    }

    fun pauseCurrentVideo() {
        saveCurrentVideoPositionAndCover()
        pauseSharedPlayer()
    }

    fun currentVideoProgress(): Float {
        val player = sharedPlayer ?: return 0f
        return playerProgress(player)
    }

    fun isCurrentVideoPaused(): Boolean {
        return isCurrentVideoManuallyPaused
    }

    fun seekCurrentVideoTo(progress: Float) {
        val player = sharedPlayer ?: return
        val duration = player.duration
        if (duration <= 0) return

        val targetPosition = (duration * progress.coerceIn(0f, 1f)).toLong()
        player.seekTo(targetPosition)
        currentVideo?.updateProgressUi(progress)
    }

    fun release() {
        stopProgressUpdates()
        saveCurrentVideoPositionAndCover()
        currentVideo?.playerView?.player = null
        currentVideo?.resetControlUi()
        currentVideo = null
        sharedPlayer?.release()
        sharedPlayer = null
        waitingForFirstFrameVideoResId = null
        isCurrentVideoManuallyPaused = false
        coverStore.release()
    }

    private fun play(video: VideoInterface) {
        val videoItem = video.videoItem ?: return
        val videoResId = video.videoResId ?: return
        val currentCover = coverFor(videoItem)
        val isSwitchingVideoView = currentVideo !== video
        val isSwitchingVideoItem = currentVideo?.videoResId != videoResId

        val player = sharedPlayer ?: createSharedPlayer()

        if (isSwitchingVideoView || isSwitchingVideoItem) {
            currentVideo?.resetControlUi()
            video.resetControlUi()
            isCurrentVideoManuallyPaused = false
        }

        if (isSwitchingVideoView) {
            movePlayerToVideo(video)
        }

        val shouldWaitForFirstFrame = isSwitchingVideoView || isSwitchingVideoItem
        video.setCover(
            bitmap = currentCover,
            showCover = currentCover != null && shouldWaitForFirstFrame
        )
        waitingForFirstFrameVideoResId =
            if (currentCover != null && shouldWaitForFirstFrame) videoResId else null

        if (isSwitchingVideoItem) {
            if (!isSwitchingVideoView) {
                saveCurrentVideoPositionAndCover()
            }
            replacePlayerVideo(player, video, videoResId)
        }

        video.playerView.player = player
        if (isCurrentVideoManuallyPaused) {
            pauseSharedPlayer()
            return
        }

        player.volume = 1f
        player.playWhenReady = true
        player.play()
        video.setPausedUiVisible(false)
        updateCurrentProgressUi()
        startProgressUpdates()
    }

    private fun createSharedPlayer(): ExoPlayer {
        return ExoPlayer.Builder(appContext).build().also { player ->
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    val waitingVideoResId = waitingForFirstFrameVideoResId
                    val video = currentVideo

                    if (
                        events.contains(Player.EVENT_RENDERED_FIRST_FRAME) &&
                        waitingVideoResId != null &&
                        video?.videoResId == waitingVideoResId
                    ) {
                        video.hideCover()
                        waitingForFirstFrameVideoResId = null
                    }
                }
            })
            sharedPlayer = player
        }
    }

    private fun movePlayerToVideo(newVideo: VideoInterface) {
        val oldVideo = currentVideo

        saveCurrentVideoPositionAndCover()
        oldVideo?.playerView?.player = null
        oldVideo?.let { showCachedCover(it) }
        currentVideo = newVideo
    }

    private fun replacePlayerVideo(
        player: ExoPlayer,
        video: VideoInterface,
        videoResId: Int
    ) {
        pauseSharedPlayer()
        video.playerView.player = null

        val videoUri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .path(videoResId.toString())
            .build()
        val savedPosition = videoPositionsByVideoResId[videoResId] ?: 0L

        player.setMediaItem(MediaItem.fromUri(videoUri), savedPosition)
        player.prepare()
    }

    private fun pauseSharedPlayer() {
        stopProgressUpdates()
        sharedPlayer?.apply {
            volume = 0f
            playWhenReady = false
            pause()
        }
    }

    private fun toggleVideo(video: VideoInterface) {
        val videoItem = video.videoItem ?: return

        if (videoItem.isLiveCard || video !== currentVideo) {
            return
        }

        val player = sharedPlayer ?: return
        if (isCurrentVideoManuallyPaused || !player.playWhenReady) {
            isCurrentVideoManuallyPaused = false
            video.setPausedUiVisible(false)
            player.volume = 1f
            player.playWhenReady = true
            player.play()
            startProgressUpdates()
        } else {
            saveCurrentVideoPositionAndCover()
            isCurrentVideoManuallyPaused = true
            pauseSharedPlayer()
            updateCurrentProgressUi()
            video.setPausedUiVisible(true)
        }
    }

    private fun showCachedCover(video: VideoInterface) {
        val videoItem = video.videoItem ?: return
        val cover = coverFor(videoItem)

        video.setCover(bitmap = cover, showCover = cover != null)
    }

    private fun saveCurrentVideoPositionAndCover() {
        val video = currentVideo ?: return
        val videoResId = video.videoResId ?: return
        val player = sharedPlayer ?: return
        val positionMs = player.currentPosition

        videoPositionsByVideoResId[videoResId] = positionMs
        val renderedCover = captureCurrentFrame(video)

        if (renderedCover != null) {
            coverStore.saveRenderedCover(videoResId, renderedCover)
        }
    }

    private fun updateCurrentProgressUi() {
        val player = sharedPlayer ?: return
        val video = currentVideo ?: return
        if (video.isDraggingProgress) return

        video.updateProgressUi(playerProgress(player))
    }

    private fun playerProgress(player: ExoPlayer): Float {
        val duration = player.duration
        if (duration <= 0) return 0f

        return (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
    }

    private fun startProgressUpdates() {
        if (isUpdatingProgress) return

        isUpdatingProgress = true
        progressHandler.post(progressUpdateRunnable)
    }

    private fun stopProgressUpdates() {
        isUpdatingProgress = false
        progressHandler.removeCallbacks(progressUpdateRunnable)
    }

    private fun captureCurrentFrame(video: VideoInterface): Bitmap? {
        val textureView = findTextureView(video.playerView) ?: return null

        if (!textureView.isAvailable || textureView.width <= 0 || textureView.height <= 0) {
            return null
        }

        return try {
            textureView.bitmap
        } catch (_: Exception) {
            null
        }
    }

    private fun findTextureView(view: View): TextureView? {
        if (view is TextureView) return view
        if (view !is ViewGroup) return null

        for (index in 0 until view.childCount) {
            findTextureView(view.getChildAt(index))?.let { return it }
        }

        return null
    }

    private fun onCoverReady(videoResId: Int, cover: Bitmap) {
        boundVideosProvider().forEach { video ->
            if (video.videoResId == videoResId) {
                video.setCover(
                    bitmap = cover,
                    showCover = video !== currentVideo
                )
            }
        }
    }

    fun detachFrom(video: VideoInterface) {
        if (currentVideo !== video) return

        saveCurrentVideoPositionAndCover()
        pauseSharedPlayer()
        video.playerView.player = null
        video.resetControlUi()
        currentVideo = null
        waitingForFirstFrameVideoResId = null
    }

    private companion object {
        const val PROGRESS_UPDATE_INTERVAL_MS = 250L
    }
}

private class VideoCoverStore(
    private val resources: Resources,
    private val onCoverReady: (videoResId: Int, cover: Bitmap) -> Unit
) {

    private val coverBitmapsByVideoResId = mutableMapOf<Int, Bitmap>()
    private val packagedCoverBitmapsByCoverResId = mutableMapOf<Int, Bitmap>()

    private var released = false

    fun renderedCoverFor(videoResId: Int): Bitmap? {
        return coverBitmapsByVideoResId[videoResId]
    }

    fun saveRenderedCover(videoResId: Int, renderedCover: Bitmap) {
        if (released) {
            renderedCover.recycle()
            return
        }

        saveCover(videoResId, scaleDownIfNeeded(renderedCover))
    }

    private fun saveCover(videoResId: Int, cover: Bitmap) {
        val oldCover = coverBitmapsByVideoResId.put(videoResId, cover)

        onCoverReady(videoResId, cover)
        oldCover?.recycle()
    }

    fun release() {
        if (released) return

        released = true
        coverBitmapsByVideoResId.values.forEach { it.recycle() }
        coverBitmapsByVideoResId.clear()
        packagedCoverBitmapsByCoverResId.values.forEach { it.recycle() }
        packagedCoverBitmapsByCoverResId.clear()
    }

    fun packagedCoverFor(coverResId: Int): Bitmap? {
        if (coverResId == 0) return null

        packagedCoverBitmapsByCoverResId[coverResId]?.let { return it }

        val cover = BitmapFactory.decodeResource(resources, coverResId) ?: return null
        packagedCoverBitmapsByCoverResId[coverResId] = cover

        return cover
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap): Bitmap {
        val maxSide = max(bitmap.width, bitmap.height)
        if (maxSide <= COVER_MAX_SIZE_PX) return bitmap

        val scale = COVER_MAX_SIZE_PX.toFloat() / maxSide
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt(),
            (bitmap.height * scale).roundToInt(),
            true
        ).also {
            bitmap.recycle()
        }
    }

    private companion object {
        const val COVER_MAX_SIZE_PX = 720
    }
}
