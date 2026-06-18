package com.example.diceroller

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
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

class ChannelPagerAdapter(
    private val channels: List<Channel>,
    private val playbackHost: VideoPlaybackHost,
    private val onVideoPageSelected: (channelPosition: Int) -> Unit
) : RecyclerView.Adapter<ChannelViewHolder>() {

    private val boundHolders = mutableMapOf<Int, ChannelViewHolder>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val videoPager = ViewPager2(parent.context).apply {
            id = View.generateViewId()
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            orientation = ViewPager2.ORIENTATION_VERTICAL
            offscreenPageLimit = 1
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        return ChannelViewHolder(videoPager, playbackHost, onVideoPageSelected)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        boundHolders.entries.removeAll { it.value == holder }
        boundHolders[position] = holder
        holder.bindChannel(channels[position], position)
    }

    override fun getItemCount(): Int {
        return channels.size
    }

    override fun onViewRecycled(holder: ChannelViewHolder) {
        val position = boundHolders.entries.firstOrNull { it.value == holder }?.key
        if (position != null) {
            boundHolders.remove(position)
        }
        holder.releaseChannelPage()
        super.onViewRecycled(holder)
    }

    fun videoPageAt(channelPosition: Int): VideoPage? {
        return boundHolders[channelPosition]?.currentVideoPage()
    }

    fun boundVideoPages(): List<VideoPage> {
        return boundHolders.values.flatMap { it.boundVideoPages() }
    }

    fun releaseBoundPages() {
        boundHolders.values.forEach { it.releaseChannelPage() }
        boundHolders.clear()
    }
}

class ChannelViewHolder(
    private val videoPager: ViewPager2,
    private val playbackHost: VideoPlaybackHost,
    private val onVideoPageSelected: (channelPosition: Int) -> Unit
) : RecyclerView.ViewHolder(videoPager) {

    private var channelPosition = RecyclerView.NO_POSITION
    private var videoAdapter: VideoPagerAdapter? = null
    private var videoPageChangeCallback: ViewPager2.OnPageChangeCallback? = null

    fun bindChannel(channel: Channel, position: Int) {
        videoPageChangeCallback?.let { videoPager.unregisterOnPageChangeCallback(it) }
        videoAdapter?.releaseBoundPages()

        channelPosition = position
        videoAdapter = VideoPagerAdapter(channel, playbackHost)
        videoPager.adapter = videoAdapter
        videoPager.setCurrentItem(loopStartPosition(channel.videos.size), false)

        videoPageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                onVideoPageSelected(channelPosition)
            }
        }.also {
            videoPager.registerOnPageChangeCallback(it)
        }
    }

    fun currentVideoPage(): VideoPage? {
        val adapter = videoAdapter ?: return null

        return adapter.videoPageAt(videoPager.currentItem)
    }

    fun boundVideoPages(): List<VideoPage> {
        return videoAdapter?.boundPages().orEmpty()
    }

    fun releaseChannelPage() {
        videoPageChangeCallback?.let { videoPager.unregisterOnPageChangeCallback(it) }
        videoPageChangeCallback = null
        videoAdapter?.releaseBoundPages()
        videoAdapter = null
        videoPager.adapter = null
    }
}

@UnstableApi
class VideoPagerAdapter(
    private val channel: Channel,
    private val playbackHost: VideoPlaybackHost
) : RecyclerView.Adapter<VideoViewHolder>() {

    private val boundHolders = mutableMapOf<Int, VideoViewHolder>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val rootView = FrameLayout(parent.context).apply {
            setBackgroundResource(R.color.video_background)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val playerView = (View.inflate(parent.context, R.layout.view_feed_player, null) as PlayerView).apply {
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

        return VideoViewHolder(
            rootView = rootView,
            playerView = playerView,
            coverImageView = coverImageView,
            titleText = titleText,
            metaText = metaText,
            playbackHost = playbackHost
        )
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        boundHolders.entries.removeAll { it.value == holder }
        boundHolders[position] = holder
        holder.bindVideo(channel, realVideoIndex(position))
    }

    override fun getItemCount(): Int {
        return if (channel.videos.size <= 1) {
            channel.videos.size
        } else {
            VIDEO_LOOP_ITEM_COUNT
        }
    }

    override fun onViewRecycled(holder: VideoViewHolder) {
        val position = boundHolders.entries.firstOrNull { it.value == holder }?.key
        if (position != null) {
            boundHolders.remove(position)
            playbackHost.onVideoPageReleased(holder)
        }
        super.onViewRecycled(holder)
    }

    fun videoPageAt(position: Int): VideoPage? {
        return boundHolders[position]
    }

    fun boundPages(): List<VideoPage> {
        return boundHolders.values.toList()
    }

    fun releaseBoundPages() {
        boundHolders.values.forEach { playbackHost.onVideoPageReleased(it) }
        boundHolders.clear()
    }

    private fun realVideoIndex(position: Int): Int {
        return position % channel.videos.size
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
class VideoViewHolder(
    private val rootView: FrameLayout,
    override val playerView: PlayerView,
    private val coverImageView: ImageView,
    private val titleText: TextView,
    private val metaText: TextView,
    private val playbackHost: VideoPlaybackHost
) : RecyclerView.ViewHolder(rootView), VideoPage {

    override var video: VideoItem? = null
        private set

    init {
        rootView.setOnClickListener {
            playbackHost.onVideoPageClicked(this)
        }
    }

    fun bindVideo(channel: Channel, position: Int) {
        val currentVideo = channel.videos[position]
        val cover = playbackHost.coverFor(currentVideo.rawResId)

        video = currentVideo
        rootView.setBackgroundResource(R.color.video_background)

        if (!playbackHost.isPlayingPage(this)) {
            playerView.player = null
        }

        setCover(cover, visible = cover != null && !playbackHost.isPlayingPage(this))
        if (cover == null) {
            playbackHost.requestCover(currentVideo.rawResId)
        }

        playerView.visibility = View.VISIBLE
        titleText.text = currentVideo.title
        metaText.text = itemView.context.getString(
            R.string.video_meta,
            currentVideo.author,
            currentVideo.stats
        )
    }

    override fun setCover(bitmap: Bitmap?, visible: Boolean) {
        coverImageView.setImageBitmap(bitmap)
        coverImageView.visibility = if (visible) View.VISIBLE else View.GONE
    }

    override fun hideCover() {
        coverImageView.visibility = View.GONE
    }

    override fun clearCover() {
        coverImageView.setImageBitmap(null)
        coverImageView.visibility = View.GONE
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
