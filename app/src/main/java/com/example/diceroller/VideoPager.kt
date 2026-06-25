package com.example.diceroller

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

@UnstableApi
class VideoPager(
    internal val pager: ViewPager2,
    private val channel: Channel,
    private val isActiveChannel: () -> Boolean
) {

    private val videoAdapter = VideoPagerAdapter()
    private val videoChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        // 竖向换页后直接起播自己——但仅在本频道正处于可见状态时，避免离屏频道抢播放器。
        override fun onPageSelected(position: Int) {
            if (isActiveChannel()) playCurrentVideo()
        }
    }

    init {
        pager.adapter = videoAdapter
        pager.setCurrentItem(loopStartPosition(channel.videoItems.size), false)
        pager.registerOnPageChangeCallback(videoChangeCallback)
    }

    fun currentVideo(): VideoInterface? = videoAdapter.boundHolders[pager.currentItem]

    // 播放本竖向 pager 的当前视频，并把"播完平滑滑到下一条"绑定到自己身上
    // （循环页足够多，currentItem + 1 始终有效）。
    fun playCurrentVideo() {
        pager.post {
            VideoController.shared.onVideoCompleted = { pager.setCurrentItem(pager.currentItem + 1, true) }
            VideoController.shared.play(currentVideo())
        }
    }

    fun release() {
        pager.unregisterOnPageChangeCallback(videoChangeCallback)
        // 共享播放器至多挂在"当前播放页"这一页上，所以只解绑它即可（detachFrom 内部还会再校验一次）。
        // adapter（inner 类）随 adapter = null 失去引用，连同 boundHolders 一起交给 GC。
        currentVideo()?.let { VideoController.shared.detachFrom(it) }
        pager.adapter = null
    }

    // adapter 设为 inner：频道数据直接读外层 channel，不必当成构造参数传入。
    private inner class VideoPagerAdapter : RecyclerView.Adapter<VideoViewHolder>() {

        val boundHolders = mutableMapOf<Int, VideoViewHolder>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
            return VideoViewHolder(parent)
        }

        override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
            boundHolders.entries.removeAll { it.value == holder }
            boundHolders[position] = holder
            holder.bind(channel.videoItems[position % channel.videoItems.size])
        }

        override fun getItemCount(): Int {
            return if (channel.videoItems.size <= 1) channel.videoItems.size else LOOP_ITEM_COUNT
        }

        override fun onViewRecycled(holder: VideoViewHolder) {
            val position = boundHolders.entries.find { it.value == holder }?.key ?: return
            boundHolders.remove(position)
            VideoController.shared.detachFrom(holder)
            holder.recycleUi()
        }
    }
}

@UnstableApi
internal class VideoViewHolder(
    parent: ViewGroup
) : RecyclerView.ViewHolder(
    LayoutInflater.from(parent.context).inflate(R.layout.view_video_item, parent, false)
), VideoInterface {

    override val playerView: PlayerView = itemView.findViewById(R.id.video_player_view)
    private val coverImageView: ImageView = itemView.findViewById(R.id.video_cover)
    private val pauseIconView: ImageView = itemView.findViewById(R.id.video_pause_icon)
    private val progressFill: View = itemView.findViewById(R.id.video_progress_fill)
    private val progressTrack: FrameLayout = itemView.findViewById(R.id.video_progress_track)
    private val progressTouchArea: View = itemView.findViewById(R.id.video_progress_touch)
    private val titleText: TextView = itemView.findViewById(R.id.video_title)
    private val metaText: TextView = itemView.findViewById(R.id.video_meta)

    override var videoItem: VideoItem? = null
        private set
    override var isDraggingProgress: Boolean = false
        private set

    private var isPausedUiVisible = false
    private var progressDragStartX = 0f
    private var progressDragStartValue = 0f
    private val hideProgressRunnable = Runnable {
        if (!isPausedUiVisible && !isDraggingProgress) setProgressUiVisible(false)
    }

    init {
        itemView.setOnClickListener { VideoController.shared.toggleVideo(this) }
        progressTouchArea.setOnTouchListener { view, event -> handleProgressTouch(view, event) }
    }

    // ---------------- 数据绑定 ----------------

    fun bind(videoItem: VideoItem) {
        val cover = VideoController.shared.coverFor(videoItem)

        this.videoItem = videoItem
        resetControlUi()

        // 被绑定的页一定不是当前播放页（当前页不会被回收重绑），所以直接摘掉播放器、盖上封面占位。
        playerView.player = null
        setCover(cover, showCover = cover != null)

        titleText.text = videoItem.title
        metaText.text = itemView.context.getString(R.string.video_meta, videoItem.author, videoItem.stats)
    }

    // ---------------- 封面 ----------------

    override fun setCover(bitmap: Bitmap?, showCover: Boolean) {
        coverImageView.setImageBitmap(bitmap)
        coverImageView.visibility = if (showCover) View.VISIBLE else View.GONE
    }

    // 仅隐藏、保留位图：seek/重渲染会反复触发，之后可能还要原图复现。
    override fun hideCover() {
        coverImageView.visibility = View.GONE
    }

    // 回收时彻底清掉位图引用，等价于 setCover(null, 隐藏)。
    override fun clearCover() = setCover(bitmap = null, showCover = false)

    // ---------------- 暂停 / 进度控件 ----------------

    override fun setPausedUiVisible(visible: Boolean) {
        isPausedUiVisible = visible
        pauseIconView.visibility = if (visible) View.VISIBLE else View.GONE

        if (visible) {
            progressTrack.removeCallbacks(hideProgressRunnable)
            setProgressUiVisible(true)
        } else {
            hideProgressLater()
        }
    }

    override fun updateProgressUi(progress: Float) {
        progressFill.scaleX = progress.coerceIn(0f, 1f)
    }

    override fun resetControlUi() {
        isDraggingProgress = false
        isPausedUiVisible = false
        progressTrack.removeCallbacks(hideProgressRunnable)
        pauseIconView.visibility = View.GONE
        setProgressUiVisible(false)
    }

    // ---------------- 进度条拖动 ----------------

    private fun handleProgressTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                progressTrack.removeCallbacks(hideProgressRunnable)
                isDraggingProgress = true
                progressDragStartX = event.x
                progressDragStartValue = VideoController.shared.currentVideoProgress()
                setProgressUiVisible(true)
                updateProgressUi(progressDragStartValue)
                view.parent.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val width = view.width
                if (width > 0) {
                    val distanceX = event.x - progressDragStartX
                    val newProgress = (progressDragStartValue + distanceX / width).coerceIn(0f, 1f)
                    updateProgressUi(newProgress)
                    VideoController.shared.seekCurrentVideoTo(newProgress)
                }
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                isDraggingProgress = false
                view.parent.requestDisallowInterceptTouchEvent(false)
                if (!VideoController.shared.isCurrentVideoPaused()) {
                    hideProgressLater()
                }
                return true
            }
        }
        return false
    }

    private fun setProgressUiVisible(visible: Boolean) {
        progressTrack.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun hideProgressLater() {
        progressTrack.removeCallbacks(hideProgressRunnable)
        progressTrack.postDelayed(hideProgressRunnable, PROGRESS_HIDE_DELAY_MS)
    }
}

private const val PROGRESS_HIDE_DELAY_MS = 1_500L
