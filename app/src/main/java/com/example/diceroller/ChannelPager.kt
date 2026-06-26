package com.example.diceroller

import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

@UnstableApi
class ChannelPager(
    private val pager: ViewPager2,
    private val channels: List<Channel>,
    selectedIndex: Int,
    private val channelBar: ChannelBar,
    private val onEnterLiveRoom: (startPosition: Int) -> Unit
) {

    private val channelAdapter = ChannelPagerAdapter()
    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
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

    // 横向频道父级只负责把"播放当前频道的视频"委托下去，自身从不自动横滑切频道。
    fun playCurrentVideo() {
        pager.post {
            channelAdapter.boundHolders[pager.currentItem]?.playCurrentVideo()
        }
    }

    // 当前可见频道正在播放的那条视频；供 Activity.onStop 做"按归属暂停"。
    fun currentVideo(): PlayableVideo? = channelAdapter.boundHolders[pager.currentItem]?.currentVideo()

    fun release() {
        channelAdapter.boundHolders.values.forEach { it.releasePage() }
        // adapter（inner 类，持有外层 ChannelPager 引用）随 adapter = null 失去引用，
        // 它和 boundHolders 一并交给 GC，无需手动 clear。
        pager.adapter = null
    }

    // adapter / holder 设为 inner：频道列表、进直播间回调、横向 pager 等都直接读外层，
    // 不必再当成构造参数层层传递。
    private inner class ChannelPagerAdapter : RecyclerView.Adapter<ChannelViewHolder>() {

        val boundHolders = mutableMapOf<Int, ChannelViewHolder>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
            return ChannelViewHolder(parent)
        }

        override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
            boundHolders.entries.removeAll { it.value == holder }
            boundHolders[position] = holder
            holder.bind(channels[position], position)
        }

        override fun getItemCount(): Int = channels.size

        override fun onViewRecycled(holder: ChannelViewHolder) {
            val position = boundHolders.entries.find { it.value == holder }?.key
            if (position != null) {
                boundHolders.remove(position)
            }
            holder.releasePage()
        }
    }

    private inner class ChannelViewHolder(
        parent: ViewGroup
    ) : RecyclerView.ViewHolder(
        ViewPager2(parent.context).apply {
            id = View.generateViewId()
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            orientation = ViewPager2.ORIENTATION_VERTICAL
            offscreenPageLimit = 1
            overScrollMode = View.OVER_SCROLL_NEVER
        }
    ) {

        private val videoPagerView = itemView as ViewPager2
        private var videoPager: VideoPager? = null

        // 把"播放当前视频"委托给本频道的竖向 pager；自动续播下一条由它自己负责。
        fun playCurrentVideo() {
            videoPager?.playCurrentVideo()
        }

        fun currentVideo(): PlayableVideo? = videoPager?.currentVideo()

        fun bind(channel: Channel, position: Int) {
            releasePage()

            videoPager = VideoPager(
                pager = videoPagerView,
                channel = channel,
                // 是否正是当前可见频道：竖向换页时 VideoPager 自己据此决定要不要起播，
                // 不必再绕回 ChannelPager 兜一圈又委托回同一个 VideoPager。
                isActiveChannel = { position == pager.currentItem },
                // 仅直播频道（预览页）需要进入按钮回调；普通频道传 null。
                onEnterLiveRoom = if (channel.isLiveChannel) onEnterLiveRoom else null
            )
        }

        fun releasePage() {
            videoPager?.release()
            videoPager = null
        }
    }
}
