/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.diceroller

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A small Douyin-style home feed:
 * - swipe left and right to switch channels
 * - swipe up and down inside a channel to switch videos
 * - the top indicator follows the horizontal channel swipe progress
 */
@UnstableApi
class HomeFragment : Fragment(R.layout.fragment_home) {

    private lateinit var channelPager: ViewPager2
    private lateinit var channelScrollView: HorizontalScrollView
    private lateinit var channelRow: LinearLayout
    private lateinit var channelIndicator: View
    private lateinit var channels: List<Channel>
    private lateinit var channelPagerAdapter: ChannelPagerAdapter

    private val channelTextViews = mutableListOf<TextView>()
    private var currentChannelIndex = 0
    private var channelPageChangeCallback: ViewPager2.OnPageChangeCallback? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        channels = createChannels()
        currentChannelIndex = channels.lastIndex
        channelPager = view.findViewById(R.id.channelPager)
        channelScrollView = view.findViewById(R.id.channelScrollView)
        channelRow = view.findViewById(R.id.channelRow)
        channelIndicator = view.findViewById(R.id.channelIndicator)

        setupChannelBar()
        setupChannelPager()
    }

    override fun onStart() {
        super.onStart()
        playCurrentVideo()
    }

    override fun onStop() {
        pauseAllVideos()
        super.onStop()
    }

    override fun onDestroyView() {
        pauseAllVideos()
        channelPageChangeCallback?.let { channelPager.unregisterOnPageChangeCallback(it) }
        channelPageChangeCallback = null

        if (::channelPagerAdapter.isInitialized) {
            channelPagerAdapter.releaseAllVideos()
        }
        if (::channelPager.isInitialized) {
            channelPager.adapter = null
        }

        channelTextViews.clear()
        super.onDestroyView()
    }

    private fun setupChannelBar() {
        channelRow.removeAllViews()
        channelTextViews.clear()

        channels.forEachIndexed { index, channel ->
            val channelTextView = TextView(requireContext()).apply {
                text = channel.title
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(if (index == channels.lastIndex) SELECTED_TEXT_COLOR else NORMAL_TEXT_COLOR)
                setPadding(dp(14), 0, dp(14), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setOnClickListener {
                    channelPager.setCurrentItem(index, false)
                }
            }

            channelTextViews.add(channelTextView)
            channelRow.addView(channelTextView)
        }
    }

    private fun setupChannelPager() {
        val defaultChannelIndex = channels.lastIndex

        channelPager.orientation = ViewPager2.ORIENTATION_HORIZONTAL
        channelPager.overScrollMode = View.OVER_SCROLL_NEVER
        channelPagerAdapter = ChannelPagerAdapter(channels)
        channelPager.adapter = channelPagerAdapter
        channelPageChangeCallback = object : ViewPager2.OnPageChangeCallback() {

            override fun onPageScrolled(
                position: Int,
                positionOffset: Float,
                positionOffsetPixels: Int
            ) {
                updateChannelBar(position, positionOffset)
            }

            override fun onPageSelected(position: Int) {
                currentChannelIndex = position
                updateChannelBar(position, 0f)
                channelPager.post {
                    if (view != null) {
                        playCurrentVideo()
                    }
                }
            }
        }.also {
            channelPager.registerOnPageChangeCallback(it)
        }
        channelPager.setCurrentItem(defaultChannelIndex, false)

        channelRow.post {
            if (view == null) return@post

            updateChannelBar(defaultChannelIndex, 0f)
            playCurrentVideo()
        }
    }

    private fun updateChannelBar(position: Int, positionOffset: Float) {
        if (channelTextViews.isEmpty()) return

        val startIndex = position.coerceIn(0, channelTextViews.lastIndex)
        val endIndex = (startIndex + 1).coerceAtMost(channelTextViews.lastIndex)
        val nearestIndex = if (positionOffset >= 0.5f) endIndex else startIndex

        channelTextViews.forEachIndexed { index, textView ->
            textView.setTextColor(if (index == nearestIndex) SELECTED_TEXT_COLOR else NORMAL_TEXT_COLOR)
        }

        updateIndicatorPosition(startIndex, endIndex, positionOffset)
    }

    private fun updateIndicatorPosition(startIndex: Int, endIndex: Int, positionOffset: Float) {
        val startTextView = channelTextViews[startIndex]
        val endTextView = channelTextViews[endIndex]

        if (startTextView.width == 0 || channelIndicator.height == 0) return

        val safeOffset = if (startIndex == endIndex) 0f else positionOffset
        val startCenterX = startTextView.left + startTextView.width / 2f
        val endCenterX = endTextView.left + endTextView.width / 2f
        val indicatorCenterX = lerp(startCenterX, endCenterX, safeOffset)
        val indicatorWidth = lerp(
            indicatorWidthFor(startTextView),
            indicatorWidthFor(endTextView),
            safeOffset
        )

        channelIndicator.layoutParams = channelIndicator.layoutParams.apply {
            width = indicatorWidth.roundToInt()
        }
        channelIndicator.translationX = indicatorCenterX - indicatorWidth / 2f
        channelIndicator.alpha = (abs(safeOffset - 0.5f) * 2f).coerceIn(0f, 1f)
        channelScrollView.scrollTo(scrollXForCenteredIndicator(indicatorCenterX), 0)
    }

    private fun scrollXForCenteredIndicator(indicatorCenterX: Float): Int {
        val viewportWidth = channelScrollView.width -
            channelScrollView.paddingStart -
            channelScrollView.paddingEnd
        val maxScrollX = max(0, channelRow.width - viewportWidth)
        val desiredScrollX = indicatorCenterX - viewportWidth / 2f

        return desiredScrollX.roundToInt().coerceIn(0, maxScrollX)
    }

    private fun playCurrentVideo() {
        if (view == null || !::channelPagerAdapter.isInitialized) return

        val holder = channelPagerAdapter.holderFor(currentChannelIndex)

        if (holder == null) {
            channelPager.post {
                if (view != null) {
                    playCurrentVideo()
                }
            }
            return
        }

        pauseAllVideos()
        holder.playCurrentVideo()
    }

    private fun pauseAllVideos() {
        if (::channelPagerAdapter.isInitialized) {
            channelPagerAdapter.pauseAllVideos()
        }
    }

    private inner class ChannelPagerAdapter(
        private val channels: List<Channel>
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

            return ChannelViewHolder(videoPager)
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
            val position = boundHolders.entries.firstOrNull { it.value == holder }?.key
            if (position != null) {
                boundHolders.remove(position)
            }
            holder.release()
            super.onViewRecycled(holder)
        }

        fun holderFor(position: Int): ChannelViewHolder? {
            return boundHolders[position]
        }

        fun pauseAllVideos() {
            boundHolders.values.forEach { it.pauseAllVideos() }
        }

        fun releaseAllVideos() {
            boundHolders.values.forEach { it.release() }
            boundHolders.clear()
        }
    }

    private inner class ChannelViewHolder(
        private val videoPager: ViewPager2
    ) : RecyclerView.ViewHolder(videoPager) {

        private var channelPosition = RecyclerView.NO_POSITION
        private var videoAdapter: VideoPagerAdapter? = null
        private var pageChangeCallback: ViewPager2.OnPageChangeCallback? = null

        fun bind(channel: Channel, position: Int) {
            val adapter = VideoPagerAdapter(channel)

            pageChangeCallback?.let { videoPager.unregisterOnPageChangeCallback(it) }
            videoAdapter?.release()
            channelPosition = position
            videoAdapter = adapter
            videoPager.adapter = adapter
            videoPager.setCurrentItem(loopStartPosition(channel.videos.size), false)
            pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    if (channelPosition == currentChannelIndex) {
                        videoPager.post { playCurrentVideo() }
                    }
                }
            }.also {
                videoPager.registerOnPageChangeCallback(it)
            }
        }

        fun playCurrentVideo() {
            val adapter = videoAdapter ?: return
            val holder = adapter.holderFor(videoPager.currentItem)

            if (holder == null) {
                videoPager.post {
                    if (videoAdapter != null) {
                        playCurrentVideo()
                    }
                }
                return
            }

            holder.play()
        }

        fun pauseAllVideos() {
            videoAdapter?.pauseAll()
        }

        fun release() {
            pageChangeCallback?.let { videoPager.unregisterOnPageChangeCallback(it) }
            pageChangeCallback = null
            videoAdapter?.release()
            videoAdapter = null
            videoPager.adapter = null
        }
    }

    private inner class VideoPagerAdapter(
        private val channel: Channel
    ) : RecyclerView.Adapter<VideoViewHolder>() {

        private val boundHolders = mutableMapOf<Int, VideoViewHolder>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
            val rootView = FrameLayout(parent.context).apply {
                setBackgroundColor(Color.BLACK)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val playerView = PlayerView(parent.context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            rootView.addView(playerView)

            val centerLayout = LinearLayout(parent.context).apply {
                gravity = Gravity.CENTER
                orientation = LinearLayout.VERTICAL
                setPadding(dp(28), 0, dp(28), 0)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val channelNameText = createTextView(18f, "#D9FFFFFF", isBold = true)
            val videoNumberText = createTextView(44f, "#FFFFFFFF", isBold = true)
            val hintText = createTextView(16f, "#D6FFFFFF")
            hintText.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }

            centerLayout.addView(channelNameText)
            centerLayout.addView(videoNumberText)
            centerLayout.addView(hintText)
            rootView.addView(centerLayout)

            val bottomLayout = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), 0, dp(96), dp(34))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                )
            }

            val titleText = createTextView(21f, "#FFFFFFFF", isBold = true).apply {
                gravity = Gravity.START
            }
            val metaText = createTextView(14f, "#D9FFFFFF").apply {
                gravity = Gravity.START
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(8)
                }
            }

            bottomLayout.addView(titleText)
            bottomLayout.addView(metaText)
            rootView.addView(bottomLayout)

            return VideoViewHolder(
                rootView,
                playerView,
                channelNameText,
                videoNumberText,
                hintText,
                titleText,
                metaText
            )
        }

        override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
            boundHolders.entries.removeAll { it.value == holder }
            boundHolders[position] = holder
            holder.bind(channel, realVideoIndex(position))
        }

        override fun getItemCount(): Int {
            return if (channel.videos.size <= 1) {
                channel.videos.size
            } else {
                VIDEO_LOOP_ITEM_COUNT
            }
        }

        private fun realVideoIndex(position: Int): Int {
            return position % channel.videos.size
        }

        override fun onViewRecycled(holder: VideoViewHolder) {
            val position = boundHolders.entries.firstOrNull { it.value == holder }?.key
            if (position != null) {
                boundHolders.remove(position)
            }
            holder.releasePlayer()
            super.onViewRecycled(holder)
        }

        fun holderFor(position: Int): VideoViewHolder? {
            return boundHolders[position]
        }

        fun pauseAll() {
            boundHolders.values.forEach { it.pause() }
        }

        fun release() {
            boundHolders.values.forEach { it.releasePlayer() }
            boundHolders.clear()
        }
    }

    private inner class VideoViewHolder(
        private val rootView: FrameLayout,
        private val playerView: PlayerView,
        private val channelNameText: TextView,
        private val videoNumberText: TextView,
        private val hintText: TextView,
        private val titleText: TextView,
        private val metaText: TextView
    ) : RecyclerView.ViewHolder(rootView) {

        private var video: VideoItem? = null
        private var player: ExoPlayer? = null
        private var preparedRawResId: Int? = null

        fun bind(channel: Channel, position: Int) {
            val currentVideo = channel.videos[position]
            video = currentVideo
            val rawResId = currentVideo.rawResId

            val hasVideo = rawResId != null
            val placeholderVisibility = if (hasVideo) View.GONE else View.VISIBLE

            rootView.setBackgroundColor(Color.parseColor(currentVideo.backgroundColor))
            playerView.visibility = if (hasVideo) View.VISIBLE else View.GONE
            channelNameText.visibility = placeholderVisibility
            videoNumberText.visibility = placeholderVisibility
            hintText.visibility = placeholderVisibility
            channelNameText.text = getString(R.string.channel_page_title, channel.title)
            videoNumberText.text = getString(R.string.video_number, position + 1, channel.videos.size)
            hintText.text = getString(R.string.video_hint)
            titleText.text = currentVideo.title
            metaText.text = getString(R.string.video_meta, currentVideo.author, currentVideo.stats)

            if (rawResId == null) {
                releasePlayer()
            } else {
                preparePlayer(rawResId)
            }
        }

        fun play() {
            val rawResId = video?.rawResId ?: return

            preparePlayer(rawResId)
            player?.apply {
                volume = 1f
                playWhenReady = true
                play()
            }
        }

        fun pause() {
            player?.apply {
                volume = 0f
                playWhenReady = false
                pause()
            }
        }

        fun releasePlayer() {
            playerView.player = null
            player?.release()
            player = null
            preparedRawResId = null
        }

        private fun preparePlayer(rawResId: Int) {
            val videoUri = RawResourceDataSource.buildRawResourceUri(rawResId)
            val videoPlayer = player ?: ExoPlayer.Builder(rootView.context).build().also {
                it.repeatMode = Player.REPEAT_MODE_ONE
                it.playWhenReady = false
                playerView.player = it
                player = it
            }

            if (preparedRawResId != rawResId) {
                preparedRawResId = rawResId
                videoPlayer.setMediaItem(MediaItem.fromUri(videoUri))
                videoPlayer.prepare()
            }

            videoPlayer.playWhenReady = false
            videoPlayer.volume = 0f
            videoPlayer.pause()
        }
    }

    private fun createTextView(sizeSp: Float, color: String, isBold: Boolean = false): TextView {
        return TextView(requireContext()).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(color))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            if (isBold) {
                typeface = Typeface.DEFAULT_BOLD
            }
        }
    }

    private fun createChannels(): List<Channel> {
        return listOf(
            Channel(
                getString(R.string.channel_featured),
                listOf(
                    VideoItem("精选 · 城市夜跑 5km", "@daily_run", "1.8w likes  ·  428 comments", "#101820"),
                    VideoItem("精选 · 周末书店打卡", "@slow_weekend", "6,204 likes  ·  88 comments", "#3D405B"),
                    VideoItem("精选 · 一分钟 Kotlin 小知识", "@android_beginner", "9,821 likes  ·  156 comments", "#14213D")
                )
            ),
            Channel(
                getString(R.string.channel_experience),
                listOf(
                    VideoItem("经验 · 第一次做安卓页面", "@learn_android", "4,321 likes  ·  73 comments", "#264653"),
                    VideoItem("经验 · 新手调试小技巧", "@debug_notes", "8,176 likes  ·  221 comments", "#2A9D8F"),
                    VideoItem("经验 · XML 布局避坑", "@layout_lab", "1.1w likes  ·  312 comments", "#1D3557")
                )
            ),
            Channel(
                getString(R.string.channel_hot),
                listOf(
                    VideoItem("热点 · 今日话题速览", "@hot_today", "12.4w likes  ·  2,901 comments", "#780000"),
                    VideoItem("热点 · 全网都在讨论", "@trend_watch", "7.6w likes  ·  1,406 comments", "#9D0208"),
                    VideoItem("热点 · 三分钟看懂", "@quick_news", "5.2w likes  ·  984 comments", "#6A040F")
                )
            ),
            Channel(
                getString(R.string.channel_live),
                listOf(
                    VideoItem("直播 · 晚间聊天室预告", "@live_room", "2.1w likes  ·  1,032 comments", "#5F0F40"),
                    VideoItem("直播 · 手作摊位开播", "@maker_live", "9,405 likes  ·  642 comments", "#3C096C"),
                    VideoItem("直播 · 游戏排位中", "@game_live", "4.8w likes  ·  3,215 comments", "#240046")
                )
            ),
            Channel(
                getString(R.string.channel_following),
                listOf(
                    VideoItem("关注 · 你关注的人更新了", "@friend_feed", "3,502 likes  ·  66 comments", "#0B132B"),
                    VideoItem("关注 · 学习打卡第 12 天", "@study_daily", "2,908 likes  ·  45 comments", "#1C2541"),
                    VideoItem("关注 · 今天也写了一点代码", "@code_diary", "5,601 likes  ·  118 comments", "#3A506B")
                )
            ),
            Channel(
                getString(R.string.channel_local),
                listOf(
                    VideoItem("同城 · 附近新开的咖啡店", "@city_walk", "1.2w likes  ·  278 comments", "#3A5A40"),
                    VideoItem("同城 · 夜市第一口", "@local_food", "2.7w likes  ·  604 comments", "#344E41"),
                    VideoItem("同城 · 公园里的黄昏", "@nearby_view", "7,889 likes  ·  139 comments", "#588157")
                )
            ),
            Channel(
                getString(R.string.channel_recommended),
                listOf(
                    VideoItem("推荐 · 今日穿搭灵感", "@style_daily", "3.2w likes  ·  910 comments", "#1B4332", R.raw.outfit),
                    VideoItem("推荐 · 白鲸吐泡泡", "@ocean_diary", "6.8w likes  ·  1,508 comments", "#4A4E69", R.raw.beluga_bubbles),
                    VideoItem("推荐 · 水母慢慢游", "@jellyfish_view", "9.9w likes  ·  2,345 comments", "#22223B", R.raw.jellyfish)
                )
            )
        )
    }

    private fun loopStartPosition(realItemCount: Int): Int {
        if (realItemCount <= 1) return 0

        val middle = VIDEO_LOOP_ITEM_COUNT / 2
        return middle - middle % realItemCount
    }

    private fun indicatorWidthFor(textView: TextView): Float {
        return max(dp(18).toFloat(), textView.paint.measureText(textView.text.toString()) - dp(8))
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float {
        return start + (end - start) * fraction
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).roundToInt()
    }

    private data class Channel(
        val title: String,
        val videos: List<VideoItem>
    )

    private data class VideoItem(
        val title: String,
        val author: String,
        val stats: String,
        val backgroundColor: String,
        val rawResId: Int? = null
    )

    private companion object {
        const val VIDEO_LOOP_ITEM_COUNT = 10_000
        val SELECTED_TEXT_COLOR: Int = Color.WHITE
        val NORMAL_TEXT_COLOR: Int = Color.argb(153, 255, 255, 255)
    }
}
