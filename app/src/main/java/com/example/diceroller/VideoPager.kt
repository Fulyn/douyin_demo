package com.example.diceroller

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.roundToInt

@UnstableApi
class VideoPager(
    internal val pager: ViewPager2,
    channel: Channel,
    private val channelPosition: Int,
    videoController: VideoController,
    private val onVideoSelected: (channelPosition: Int) -> Unit
) {

    internal val videoAdapter = VideoPagerAdapter(channel, videoController)
    internal val videoChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            onVideoSelected(channelPosition)
        }
    }

    init {
        pager.adapter = videoAdapter
        pager.setCurrentItem(loopStartPosition(channel.videoItems.size), false)
        pager.registerOnPageChangeCallback(videoChangeCallback)
    }

}

@UnstableApi
internal class VideoPagerAdapter(
    private val channel: Channel,
    private val videoController: VideoController
) : RecyclerView.Adapter<VideoViewHolder>() {

    internal val boundHolders = mutableMapOf<Int, VideoViewHolder>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val rootView = FrameLayout(parent.context).apply {
            setBackgroundResource(R.color.video_background)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val playerView = (View.inflate(parent.context, R.layout.view_video_player, null) as PlayerView).apply {
            setShutterBackgroundColor(Color.TRANSPARENT)
            setKeepContentOnPlayerReset(false)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootView.addView(playerView)

        val coverImageView = ImageView(parent.context).apply {
            setBackgroundResource(R.color.video_background)
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootView.addView(coverImageView)

        val pauseIconView = ImageView(parent.context).apply {
            alpha = 0.72f
            setImageResource(R.drawable.pause_icon)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(parent.dp(72), parent.dp(72), Gravity.CENTER)
        }
        rootView.addView(pauseIconView)

        val bottomLayout = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(parent.dp(20), 0, parent.dp(96), parent.dp(34))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }

        val titleText = createTextView(parent, 21f, "#FFFFFFFF", isBold = true).apply {
            gravity = Gravity.START
        }
        val metaText = createTextView(parent, 14f, "#D9FFFFFF").apply {
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = parent.dp(8)
            }
        }

        bottomLayout.addView(titleText)
        bottomLayout.addView(metaText)
        rootView.addView(bottomLayout)

        val progressTrack = FrameLayout(parent.context).apply {
            setBackgroundColor(Color.parseColor("#5C5C5C"))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                parent.dp(2),
                Gravity.BOTTOM
            ).apply {
                bottomMargin = parent.dp(PROGRESS_BOTTOM_MARGIN_DP)
            }
        }
        val progressFill = View(parent.context).apply {
            setBackgroundColor(Color.WHITE)
            pivotX = 0f
            scaleX = 0f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        progressTrack.addView(progressFill)
        rootView.addView(progressTrack)

        val progressTouchArea = View(parent.context).apply {
            visibility = View.VISIBLE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                parent.dp(34),
                Gravity.BOTTOM
            ).apply {
                bottomMargin = parent.dp(PROGRESS_BOTTOM_MARGIN_DP)
            }
        }
        rootView.addView(progressTouchArea)

        return VideoViewHolder(
            rootView = rootView,
            playerView = playerView,
            coverImageView = coverImageView,
            pauseIconView = pauseIconView,
            progressTrack = progressTrack,
            progressFill = progressFill,
            progressTouchArea = progressTouchArea,
            titleText = titleText,
            metaText = metaText,
            videoController = videoController
        )
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        boundHolders.entries.removeAll { it.value == holder }
        boundHolders[position] = holder
        holder.bind(channel, realVideoItemIndex(position))
    }

    override fun getItemCount(): Int {
        return if (channel.videoItems.size <= 1) {
            channel.videoItems.size
        } else {
            VIDEO_LOOP_ITEM_COUNT
        }
    }

    override fun onViewRecycled(holder: VideoViewHolder) {
        val position = boundHolders.entries.find { it.value == holder }?.key
        if (position != null) {
            boundHolders.remove(position)
            videoController.detachFrom(holder)
            holder.clearCover()
            holder.resetControlUi()
        }
        super.onViewRecycled(holder)
    }

    private fun realVideoItemIndex(position: Int): Int {
        return position % channel.videoItems.size
    }

    private fun createTextView(
        parent: ViewGroup,
        sizeSp: Float,
        color: String,
        isBold: Boolean = false
    ): TextView {
        return TextView(parent.context).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(color))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            if (isBold) {
                typeface = Typeface.DEFAULT_BOLD
            }
        }
    }
}

@UnstableApi
internal class VideoViewHolder(
    private val rootView: FrameLayout,
    override val playerView: PlayerView,
    private val coverImageView: ImageView,
    private val pauseIconView: ImageView,
    private val progressTrack: FrameLayout,
    private val progressFill: View,
    private val progressTouchArea: View,
    private val titleText: TextView,
    private val metaText: TextView,
    private val videoController: VideoController
) : RecyclerView.ViewHolder(rootView), VideoInterface {

    override var videoItem: VideoItem? = null
        private set
    override var isDraggingProgress: Boolean = false
        private set

    private var isPausedUiVisible = false
    private var progressDragStartX = 0f
    private var progressDragStartValue = 0f
    private val hideProgressRunnable = Runnable {
        if (!isPausedUiVisible && !isDraggingProgress) {
            setProgressUiVisible(false)
        }
    }

    init {
        rootView.setOnClickListener {
            videoController.onVideoClicked(this)
        }
        progressTouchArea.setOnTouchListener { view, event ->
            handleProgressTouch(view, event)
        }
    }

    fun bind(channel: Channel, position: Int) {
        val videoItem = channel.videoItems[position]
        val cover = videoController.coverFor(videoItem)
        val isCurrentVideo = videoController.isCurrentVideo(this)

        this.videoItem = videoItem
        rootView.setBackgroundResource(R.color.video_background)
        resetControlUi()
        progressTouchArea.visibility = if (videoItem.isLiveCard) View.GONE else View.VISIBLE

        if (!isCurrentVideo) {
            playerView.player = null
        }

        setCover(cover, showCover = cover != null && !isCurrentVideo)

        playerView.visibility = View.VISIBLE
        titleText.text = videoItem.title
        metaText.text = itemView.context.getString(
            R.string.video_meta,
            videoItem.author,
            videoItem.stats
        )
    }

    override fun setCover(bitmap: Bitmap?, showCover: Boolean) {
        coverImageView.setImageBitmap(bitmap)
        coverImageView.visibility = if (showCover) View.VISIBLE else View.GONE
    }

    override fun hideCover() {
        coverImageView.visibility = View.GONE
    }

    override fun clearCover() {
        coverImageView.setImageBitmap(null)
        coverImageView.visibility = View.GONE
    }

    override fun setPausedUiVisible(visible: Boolean) {
        if (videoItem?.isLiveCard == true) {
            resetControlUi()
            return
        }

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

    private fun handleProgressTouch(view: View, event: MotionEvent): Boolean {
        if (videoItem?.isLiveCard == true) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                progressTrack.removeCallbacks(hideProgressRunnable)
                isDraggingProgress = true
                progressDragStartX = event.x
                progressDragStartValue = videoController.currentVideoProgress()
                setProgressUiVisible(true)
                updateProgressUi(progressDragStartValue)
                view.parent.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val width = view.width
                if (width > 0) {
                    val distanceX = event.x - progressDragStartX
                    val progressDelta = distanceX / width
                    val newProgress = (progressDragStartValue + progressDelta).coerceIn(0f, 1f)

                    updateProgressUi(newProgress)
                    videoController.seekCurrentVideoTo(newProgress)
                }
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                isDraggingProgress = false
                view.parent.requestDisallowInterceptTouchEvent(false)
                if (!videoController.isCurrentVideoPaused()) {
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

private fun loopStartPosition(realItemCount: Int): Int {
    if (realItemCount <= 1) return 0

    val middle = VIDEO_LOOP_ITEM_COUNT / 2
    return middle - middle % realItemCount
}

private fun View.dp(value: Int): Int {
    return (value * resources.displayMetrics.density).roundToInt()
}

private const val VIDEO_LOOP_ITEM_COUNT = 10_000
private const val PROGRESS_HIDE_DELAY_MS = 1_500L
private const val PROGRESS_BOTTOM_MARGIN_DP = 10
