package com.example.diceroller

import android.view.View
import android.view.ViewGroup
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

@UnstableApi
class ChannelPager(
    private val pager: ViewPager2,
    channels: List<Channel>,
    selectedIndex: Int,
    private val videoController: VideoController,
    private val channelBar: ChannelBar
) {

    private val channelAdapter = ChannelPagerAdapter(
        channels = channels,
        videoController = videoController,
        onVideoSelected = { channelPosition ->
            if (channelPosition == pager.currentItem) {
                playCurrentVideo()
            }
        }
    )
    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageScrolled(
            position: Int,
            positionOffset: Float,
            positionOffsetPixels: Int
        ) {
            channelBar.update(position, positionOffset)
        }

        override fun onPageSelected(position: Int) {
            channelBar.update(position, 0f)
            playCurrentVideo()
        }
    }

    init {
        pager.orientation = ViewPager2.ORIENTATION_HORIZONTAL
        pager.overScrollMode = View.OVER_SCROLL_NEVER
        pager.adapter = channelAdapter
        pager.setCurrentItem(selectedIndex, false)
        pager.registerOnPageChangeCallback(pageChangeCallback)
    }

    fun currentVideo(): VideoInterface? {
        val channelHolder = channelAdapter.boundHolders[pager.currentItem] ?: return null
        val videoPager = channelHolder.videoPager ?: return null

        return videoPager.videoAdapter.boundHolders[videoPager.pager.currentItem]
    }

    fun boundVideos(): List<VideoInterface> {
        return channelAdapter.boundHolders.values.flatMap { channelHolder ->
            channelHolder.videoPager?.videoAdapter?.boundHolders?.values.orEmpty()
        }
    }

    fun playCurrentVideo() {
        pager.post {
            videoController.playCurrentVideo()
        }
    }

    fun release() {
        channelAdapter.boundHolders.values.forEach { channelHolder ->
            val videoPager = channelHolder.videoPager ?: return@forEach

            videoPager.videoAdapter.boundHolders.values.forEach { videoHolder ->
                videoController.detachFrom(videoHolder)
                videoHolder.clearCover()
                videoHolder.resetControlUi()
            }
            videoPager.videoAdapter.boundHolders.clear()
            videoPager.pager.adapter = null
        }
        channelAdapter.boundHolders.clear()
        pager.adapter = null
    }
}

@UnstableApi
private class ChannelPagerAdapter(
    private val channels: List<Channel>,
    private val videoController: VideoController,
    private val onVideoSelected: (channelPosition: Int) -> Unit
) : RecyclerView.Adapter<ChannelViewHolder>() {

    val boundHolders = mutableMapOf<Int, ChannelViewHolder>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val videoPagerView = ViewPager2(parent.context).apply {
            id = View.generateViewId()
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            orientation = ViewPager2.ORIENTATION_VERTICAL
            offscreenPageLimit = 1
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        return ChannelViewHolder(videoPagerView, videoController, onVideoSelected)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        boundHolders.entries.removeAll { it.value == holder }
        boundHolders[position] = holder
        holder.bind(channels[position], position)
    }

    override fun getItemCount(): Int {
        return channels.size
    }

    override fun onViewRecycled(holder: ChannelViewHolder) {
        val position = boundHolders.entries.find { it.value == holder }?.key
        if (position != null) {
            boundHolders.remove(position)
        }
        val videoPager = holder.videoPager
        if (videoPager != null) {
            videoPager.pager.unregisterOnPageChangeCallback(videoPager.videoChangeCallback)
            videoPager.videoAdapter.boundHolders.values.forEach { videoHolder ->
                videoController.detachFrom(videoHolder)
                videoHolder.clearCover()
                videoHolder.resetControlUi()
            }
            videoPager.videoAdapter.boundHolders.clear()
            videoPager.pager.adapter = null
        }
        holder.videoPager = null
        super.onViewRecycled(holder)
    }
}

@UnstableApi
private class ChannelViewHolder(
    private val videoPagerView: ViewPager2,
    private val videoController: VideoController,
    private val onVideoSelected: (channelPosition: Int) -> Unit
) : RecyclerView.ViewHolder(videoPagerView) {

    var videoPager: VideoPager? = null

    fun bind(channel: Channel, position: Int) {
        val oldVideoPager = videoPager
        if (oldVideoPager != null) {
            oldVideoPager.pager.unregisterOnPageChangeCallback(oldVideoPager.videoChangeCallback)
            oldVideoPager.videoAdapter.boundHolders.values.forEach { videoHolder ->
                videoController.detachFrom(videoHolder)
                videoHolder.clearCover()
                videoHolder.resetControlUi()
            }
            oldVideoPager.videoAdapter.boundHolders.clear()
            oldVideoPager.pager.adapter = null
        }
        videoPager = VideoPager(
            pager = videoPagerView,
            channel = channel,
            channelPosition = position,
            videoController = videoController,
            onVideoSelected = onVideoSelected
        )
    }
}
