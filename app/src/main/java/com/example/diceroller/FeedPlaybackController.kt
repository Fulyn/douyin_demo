package com.example.diceroller

import android.content.ContentResolver
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

@UnstableApi
class FeedPlaybackController(
    context: Context,
    resources: Resources,
    private val currentVideoPageProvider: () -> VideoPage?,
    private val boundVideoPagesProvider: () -> List<VideoPage>,
    private val postPlaybackRequest: () -> Unit
) : VideoPlaybackHost {

    private val appContext = context.applicationContext
    private val playbackPositionsByRawResId = mutableMapOf<Int, Long>()
    private val coverStore = VideoCoverStore(resources, ::onCoverReady)

    private var feedPlayer: ExoPlayer? = null
    private var playingPage: VideoPage? = null
    private var playingRawResId: Int? = null
    private var waitingForFirstFrameRawResId: Int? = null
    private var manualPausedRawResId: Int? = null
    private var released = false

    override fun coverFor(rawResId: Int): Bitmap? {
        return coverStore.coverFor(rawResId)
    }

    override fun requestCover(rawResId: Int) {
        coverStore.requestDefaultCover(rawResId)
    }

    override fun isPlayingPage(page: VideoPage): Boolean {
        return playingPage === page
    }

    override fun onVideoPageClicked(page: VideoPage) {
        toggleVideoPlayback(page)
    }

    override fun onVideoPageReleased(page: VideoPage) {
        detachFrom(page)
        page.clearCover()
    }

    fun playCurrentVisiblePage() {
        if (released) return

        val videoPage = currentVideoPageProvider()
        if (videoPage == null) {
            postPlaybackRequest()
            return
        }

        play(videoPage)
    }

    fun pauseCurrent() {
        saveCurrentPlaybackPosition()
        pauseFeedPlayer()
    }

    fun release() {
        if (released) return

        released = true
        saveCurrentPlaybackPosition()
        playingPage?.playerView?.player = null
        playingPage = null
        feedPlayer?.release()
        feedPlayer = null
        playingRawResId = null
        waitingForFirstFrameRawResId = null
        manualPausedRawResId = null
        coverStore.release()
    }

    private fun play(videoPage: VideoPage) {
        val currentVideo = videoPage.video ?: return
        val rawResId = currentVideo.rawResId
        val currentCover = coverStore.coverFor(rawResId)
        val isSwitchingPage = playingPage !== videoPage
        val isSwitchingVideo = playingRawResId != rawResId

        if (currentCover == null && isSwitchingVideo) {
            manualPausedRawResId = null
            coverStore.requestDefaultCover(rawResId)
            if (isSwitchingPage) {
                movePlaybackToPage(videoPage)
            } else {
                saveCurrentPlaybackPosition()
            }
            waitingForFirstFrameRawResId = null
            pauseFeedPlayer()
            videoPage.playerView.player = null
            videoPage.hideCover()
            return
        }

        val player = feedPlayer ?: createFeedPlayer()

        if (isSwitchingPage) {
            movePlaybackToPage(videoPage)
        }

        val shouldWaitForFirstFrame = isSwitchingPage || isSwitchingVideo
        videoPage.setCover(
            bitmap = currentCover,
            visible = currentCover != null && shouldWaitForFirstFrame
        )
        waitingForFirstFrameRawResId =
            if (currentCover != null && shouldWaitForFirstFrame) rawResId else null

        if (isSwitchingVideo) {
            manualPausedRawResId = null
            if (!isSwitchingPage) {
                saveCurrentPlaybackPosition()
            }
            replacePlayerMedia(player, videoPage, rawResId)
        }

        videoPage.playerView.player = player
        if (manualPausedRawResId == rawResId) {
            pauseFeedPlayer()
            return
        }

        player.volume = 1f
        player.playWhenReady = true
        player.play()
    }

    private fun createFeedPlayer(): ExoPlayer {
        return ExoPlayer.Builder(appContext).build().also { player ->
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    val waitingRawResId = waitingForFirstFrameRawResId
                    val page = playingPage

                    if (
                        events.contains(Player.EVENT_RENDERED_FIRST_FRAME) &&
                        waitingRawResId != null &&
                        page?.video?.rawResId == waitingRawResId
                    ) {
                        page.hideCover()
                        waitingForFirstFrameRawResId = null
                    }
                }
            })
            feedPlayer = player
        }
    }

    private fun movePlaybackToPage(newPage: VideoPage) {
        val oldPage = playingPage

        saveCurrentPlaybackPosition()
        oldPage?.playerView?.player = null
        oldPage?.let { showCachedCover(it) }
        playingPage = newPage
    }

    private fun replacePlayerMedia(
        player: ExoPlayer,
        videoPage: VideoPage,
        rawResId: Int
    ) {
        pauseFeedPlayer()
        videoPage.playerView.player = null

        val videoUri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .path(rawResId.toString())
            .build()
        val savedPosition = playbackPositionsByRawResId[rawResId] ?: 0L

        playingRawResId = rawResId
        player.setMediaItem(MediaItem.fromUri(videoUri))
        player.prepare()
        if (savedPosition > 0L) {
            player.seekTo(savedPosition)
        }
    }

    private fun pauseFeedPlayer() {
        feedPlayer?.apply {
            volume = 0f
            playWhenReady = false
            pause()
        }
    }

    private fun toggleVideoPlayback(page: VideoPage) {
        val video = page.video ?: return
        if (video.isLiveCard || page !== playingPage || playingRawResId != video.rawResId) return

        val player = feedPlayer ?: return
        if (manualPausedRawResId == video.rawResId || !player.playWhenReady) {
            manualPausedRawResId = null
            player.volume = 1f
            player.playWhenReady = true
            player.play()
        } else {
            saveCurrentPlaybackPosition()
            manualPausedRawResId = video.rawResId
            pauseFeedPlayer()
        }
    }

    private fun showCachedCover(page: VideoPage) {
        val rawResId = page.video?.rawResId ?: return
        val cover = coverStore.coverFor(rawResId)

        page.setCover(bitmap = cover, visible = cover != null)
    }

    private fun saveCurrentPlaybackPosition() {
        val rawResId = playingRawResId ?: return
        val player = feedPlayer ?: return
        val positionMs = player.currentPosition

        playbackPositionsByRawResId[rawResId] = positionMs
        coverStore.requestCover(rawResId, positionMs)
    }

    private fun onCoverReady(rawResId: Int, cover: Bitmap) {
        boundVideoPagesProvider().forEach { page ->
            if (page.video?.rawResId == rawResId) {
                page.setCover(
                    bitmap = cover,
                    visible = !(page === playingPage && playingRawResId == rawResId)
                )
            }
        }

        val currentVideoPage = currentVideoPageProvider()
        if (currentVideoPage?.video?.rawResId == rawResId && playingRawResId != rawResId) {
            postPlaybackRequest()
        }
    }

    private fun detachFrom(page: VideoPage) {
        if (playingPage !== page) return

        saveCurrentPlaybackPosition()
        pauseFeedPlayer()
        page.playerView.player = null
        showCachedCover(page)
        playingPage = null
        waitingForFirstFrameRawResId = null
    }
}
