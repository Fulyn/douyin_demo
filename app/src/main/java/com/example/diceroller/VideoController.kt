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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

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

    // 视图层自清理：清掉封面位图引用并复位播放控件。
    fun recycleUi() {
        clearCover()
        resetControlUi()
    }
}

@UnstableApi
class VideoController private constructor(
    context: Context
) {

    private val appContext = context.applicationContext
    private val positionStore = PlaybackPositionStore()
    private val coverStore = VideoCoverStore(appContext.resources)
    private val progressTicker = RepeatingTicker(PROGRESS_UPDATE_INTERVAL_MS) { updateCurrentProgressUi() }

    private var sharedPlayer: ExoPlayer? = null
    private var currentVideo: VideoInterface? = null
    private var isManuallyPaused = false

    // 当前视频自然播放结束时回调（由活跃 pager 设置为"滑到下一条"），见 createSharedPlayer。
    var onVideoCompleted: (() -> Unit)? = null

    // ============================ 对外 API ============================
    // 由 pager / fragment 调用：播放、交互、生命周期、查询。

    // 由活跃 pager 在自己的 post 里把当前页视频传进来；为空（如 holder 尚未绑定）则忽略。
    fun play(targetVideo: VideoInterface?) {
        targetVideo ?: return
        val player = sharedPlayer ?: createSharedPlayer()

        // 切换 == 目标页不是当前挂着播放器的那页。非切换（同页恢复，如 onStart 重新起播）
        // 播放器仍挂着，无需搬运。
        if (currentVideo !== targetVideo) {
            switchToVideo(player, targetVideo)
        }

        // 一律续播：即使用户之前手动暂停过，回到前台/重新起播也自动恢复（对齐抖音行为），
        // startPlayback 会把 isManuallyPaused 复位。
        startPlayback(player, targetVideo)
    }

    fun toggleVideo(video: VideoInterface) {
        if (video !== currentVideo) return

        val player = sharedPlayer ?: return
        val isPausedNow = isManuallyPaused || !player.playWhenReady
        if (isPausedNow) {
            startPlayback(player, video)
        } else {
            saveCurrentVideoPositionAndCover()
            isManuallyPaused = true
            pauseSharedPlayer()
            updateCurrentProgressUi()
            video.setPausedUiVisible(true)
        }
    }

    fun seekCurrentVideoTo(progress: Float) {
        val player = sharedPlayer ?: return
        val duration = player.duration
        if (duration <= 0) return

        val targetPosition = (duration * progress.coerceIn(0f, 1f)).toLong()
        player.seekTo(targetPosition)
        currentVideo?.updateProgressUi(progress)
    }

    fun pauseCurrentVideo() {
        saveCurrentVideoPositionAndCover()
        pauseSharedPlayer()
    }

    // 只在播放器确实挂在这一页时才解绑。守卫保证了作用域：
    // 分页器拆页/回收时逐页调用，唯有正在播放的那一页会真正摘下播放器，
    // 不会误伤当前正在别的频道播放的视频。只处理播放器状态，视图 UI 由调用方 recycleUi 负责。
    fun detachFrom(video: VideoInterface) {
        if (currentVideo !== video) return

        saveCurrentVideoPositionAndCover()
        pauseSharedPlayer()
        video.playerView.player = null
        currentVideo = null
    }

    // 仅在 Activity 销毁时调用，整个 controller 随之被丢弃。
    // 因此只做 GC 管不了的两件事：释放播放器的原生资源；停掉进度轮询，
    // 否则 release 之后残留的 tick 会去访问已释放的播放器导致崩溃。
    // 其余视图/字段都随 Activity 一起销毁，交给 GC 即可。清空单例以便下次重建。
    fun destroy() {
        progressTicker.stop()
        sharedPlayer?.release()
        instance = null
    }

    fun coverFor(videoItem: VideoItem): Bitmap? {
        return coverStore.coverFor(videoItem.videoResId, videoItem.coverResId)
    }

    fun currentVideoProgress(): Float {
        val player = sharedPlayer ?: return 0f
        return playerProgress(player)
    }

    fun isCurrentVideoPaused(): Boolean {
        return isManuallyPaused
    }

    // ===================== 播放器创建与播放控制 =====================

    private fun createSharedPlayer(): ExoPlayer {
        return ExoPlayer.Builder(appContext).build().also { player ->
            // 不循环单条：播放到结尾后让其自然结束，由 onPlaybackStateChanged 触发自动滑到下一条。
            player.repeatMode = Player.REPEAT_MODE_OFF
            player.addListener(object : Player.Listener {
                // 首帧渲染到 Surface 后撤掉切换时盖的占位封面。只动 currentVideo 的封面，
                // 且 hideCover 幂等：seek 等会重复触发本回调，再调一次也只是无害空操作。
                override fun onRenderedFirstFrame() {
                    currentVideo?.let { hideCoverAfterFramePainted(it) }
                }

                // 进度轮询跟随播放器的真实播放状态自动起停，无需在各处手动开关。
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) progressTicker.start() else progressTicker.stop()
                }

                // 视频自然播放完毕：先把播放头退回开头并暂停（这样无论之后何时存档，
                // 记录的都是 0，回看从头开始；归零直接挂在结束事件上，不依赖后续切换），
                // 再交给活跃 pager 决定如何滑到下一条。
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        pauseSharedPlayer()
                        player.seekTo(0)
                        onVideoCompleted?.invoke()
                    }
                }
            })
            sharedPlayer = player
        }
    }

    // 开始/恢复播放的统一动作，play() 末尾和 toggleVideo 的恢复分支共用。
    private fun startPlayback(player: ExoPlayer, video: VideoInterface) {
        isManuallyPaused = false
        player.volume = 1f
        player.playWhenReady = true
        player.play()
        video.setPausedUiVisible(false)
        updateCurrentProgressUi()
    }

    private fun pauseSharedPlayer() {
        sharedPlayer?.apply {
            volume = 0f
            playWhenReady = false
            pause()
        }
    }

    // ===================== 切换：搬运播放器 + 换源 =====================

    // 把共享播放器从旧页搬到新页并换上新视频，一次切换的全部副作用都收在这里。
    private fun switchToVideo(player: ExoPlayer, targetVideo: VideoInterface) {
        val targetVideoItem = targetVideo.videoItem ?: return
        val targetVideoResId = targetVideoItem.videoResId

        // 先存档旧页（含截下它当前帧存为封面），再取新页封面：若新旧恰是同一条视频，
        // 此时取到的就是刚截下的最新帧，占位封面与即将续播的首帧一致，切换最无缝。
        saveCurrentVideoPositionAndCover()
        val targetCover = coverFor(targetVideoItem)
        currentVideo?.resetControlUi()
        targetVideo.resetControlUi()
        isManuallyPaused = false

        // 1) 离开旧页：摘下播放器，立刻盖回静帧封面填补空窗。
        //    否则旧页 shutter 透明又不留末帧，会露出深色（左右滑当场黑、上下滑回滑偶发闪黑）。
        currentVideo?.let { oldVideo ->
            oldVideo.playerView.player = null
            coverDetachedPage(oldVideo)
        }
        currentVideo = targetVideo

        // 2) 把新视频装进共享播放器：停掉旧内容，定位到上次进度，预备。
        pauseSharedPlayer()
        player.setMediaItem(mediaItemFor(targetVideoResId), positionStore.positionFor(targetVideoResId))
        player.prepare()

        // 3) 先盖占位封面再挂播放器（封面在上，等首帧真正上屏再撤，见 onRenderedFirstFrame）。
        targetVideo.setCover(bitmap = targetCover, showCover = targetCover != null)
        targetVideo.playerView.player = player
    }

    private fun mediaItemFor(videoResId: Int): MediaItem {
        val uri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .path(videoResId.toString())
            .build()
        return MediaItem.fromUri(uri)
    }

    // ============================ 封面占位 ============================

    // 给一页盖上静帧占位封面，用于它刚被摘掉播放器、画面会变空的时刻。
    private fun coverDetachedPage(video: VideoInterface) {
        val videoItem = video.videoItem ?: return
        val cover = coverFor(videoItem)

        video.setCover(bitmap = cover, showCover = cover != null)
    }

    // onRenderedFirstFrame 只表示帧已渲染进 SurfaceTexture，TextureView 还要等下一帧
    // VSYNC 才真正合成上屏。冷启动首个视频这个间隙最明显，此刻立刻撤封面会先露出深色
    // 背景（看起来像短暂黑屏）。推迟一帧再撤，确保画面已经上屏；并校验仍是当前视频，
    // 避免极短时间内被切走后误撤到别的页的封面。
    private fun hideCoverAfterFramePainted(video: VideoInterface) {
        video.playerView.postOnAnimation {
            if (currentVideo === video) {
                video.hideCover()
            }
        }
    }

    // ====================== 存档：进度 + 截帧封面 ======================

    private fun saveCurrentVideoPositionAndCover() {
        val video = currentVideo ?: return
        val videoResId = video.videoResId ?: return
        val player = sharedPlayer ?: return
        positionStore.savePosition(videoResId, player.currentPosition)

        val renderedCover = captureCurrentFrame(video.playerView)
        if (renderedCover != null) {
            coverStore.saveRenderedCover(videoResId, renderedCover)
        }
    }

    // PlayerView.videoSurfaceView 直接给出承载画面的那块 View（这里 surface_type=texture_view
    // 时即 TextureView），用它的 getBitmap() 截当前帧。try/catch 只兜底极个别机型可能抛的异常。
    private fun captureCurrentFrame(playerView: PlayerView): Bitmap? {
        val textureView = playerView.videoSurfaceView as? TextureView ?: return null

        return try {
            textureView.bitmap
        } catch (_: Exception) {
            null
        }
    }

    // ============================ 进度 UI ============================

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

    companion object {
        private const val PROGRESS_UPDATE_INTERVAL_MS = 250L

        // 全场唯一：MainActivity 创建时调一次 init()，之后各处直接用 shared，无需层层传递。
        private var instance: VideoController? = null

        fun init(context: Context): VideoController {
            return instance ?: VideoController(context).also { instance = it }
        }

        val shared: VideoController
            get() = instance ?: error("VideoController 未初始化，请先在 MainActivity.onCreate 调用 init()")
    }
}

private class VideoCoverStore(
    private val resources: Resources
) {

    private val renderedCovers = mutableMapOf<Int, Bitmap>()
    private val packagedCovers = mutableMapOf<Int, Bitmap>()

    // 优先用实时截帧封面（看过的视频显示上次画面），否则回退到预置封面（占位图）。
    fun coverFor(videoResId: Int, coverResId: Int): Bitmap? {
        renderedCoverFor(videoResId)?.let { return it }

        return packagedCoverFor(coverResId)
    }

    fun saveRenderedCover(videoResId: Int, renderedCover: Bitmap) {
        // 不手动 recycle 旧封面：它可能仍被某个停靠页的 coverImageView 引用，
        // 回收后再绘制会 "trying to use a recycled bitmap" 崩溃。交给 GC 即可。
        renderedCovers[videoResId] = renderedCover
    }

    private fun renderedCoverFor(videoResId: Int): Bitmap? {
        return renderedCovers[videoResId]
    }

    private fun packagedCoverFor(coverResId: Int): Bitmap? {
        packagedCovers[coverResId]?.let { return it }

        val cover = BitmapFactory.decodeResource(resources, coverResId) ?: return null
        packagedCovers[coverResId] = cover

        return cover
    }
}

// 按 videoResId 记住每条视频的播放进度，切换/恢复时复位到上次位置。
private class PlaybackPositionStore {

    private val positionsByVideoResId = mutableMapOf<Int, Long>()

    fun positionFor(videoResId: Int): Long {
        return positionsByVideoResId[videoResId] ?: 0L
    }

    fun savePosition(videoResId: Int, positionMs: Long) {
        positionsByVideoResId[videoResId] = positionMs
    }
}

// 固定间隔在主线程重复回调的定时器，只管"机制"，回调内容由调用方提供。
private class RepeatingTicker(
    private val intervalMs: Long,
    private val onTick: () -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private val runnable = object : Runnable {
        override fun run() {
            if (!isRunning) return

            onTick()
            handler.postDelayed(this, intervalMs)
        }
    }

    fun start() {
        if (isRunning) return

        isRunning = true
        handler.post(runnable)
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacks(runnable)
    }
}
