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

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.util.concurrent.Executors
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
    private lateinit var channelAdapter: ChannelPagerAdapter

    private val channelTextViews = mutableListOf<TextView>()
    private var currentChannelIndex = 0
    private var channelPageChangeCallback: ViewPager2.OnPageChangeCallback? = null
    private var feedPlayer: ExoPlayer? = null
    private var playingHolder: VideoViewHolder? = null
    private var playingRawResId: Int? = null
    private var waitingForFirstFrameRawResId: Int? = null
    private var manualPausedRawResId: Int? = null
    private val playbackPositionsByRawResId = mutableMapOf<Int, Long>()
    private val coverBitmapsByRawResId = mutableMapOf<Int, Bitmap>()
    private val coverPositionsByRawResId = mutableMapOf<Int, Long>()
    private val coverRequestsInFlight = mutableSetOf<Int>()
    private val coverFrameExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

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
        // Fragment 进入前台后，尝试播放当前可见视频。
        // 这里不直接操作某个播放器，而是走统一入口，让它自己判断当前频道/视频是否已绑定。
        requestVisibleVideoPlayback()
    }

    override fun onStop() {
        // Fragment 退到后台时，只暂停这一只全局播放器。
        // 回到前台时，再按当前页面恢复播放。
        saveCurrentPlaybackPosition()
        pauseFeedPlayer()
        super.onStop()
    }

    override fun onDestroyView() {
        // Fragment 的 View 被销毁时，播放器必须释放。
        // ExoPlayer 不是普通 View，不会因为布局销毁就自动释放解码器和音频资源。
        saveCurrentPlaybackPosition()
        playingHolder?.playerView?.player = null
        playingHolder = null
        feedPlayer?.release()
        feedPlayer = null
        playingRawResId = null
        waitingForFirstFrameRawResId = null
        manualPausedRawResId = null

        channelPageChangeCallback?.let { channelPager.unregisterOnPageChangeCallback(it) }
        channelPageChangeCallback = null

        if (::channelAdapter.isInitialized) {
            // 清掉已经绑定的频道页记录，避免 Fragment View 销毁后还留着旧 holder 引用。
            channelAdapter.boundHolders.values.forEach { it.releaseChannelPage() }
            channelAdapter.boundHolders.clear()
        }

        if (::channelPager.isInitialized) {
            // 清空 adapter，切断 ViewPager2 -> RecyclerView -> ViewHolder 的引用链。
            channelPager.adapter = null
        }

        channelTextViews.clear()
        coverRequestsInFlight.clear()
        coverBitmapsByRawResId.values.forEach { it.recycle() }
        coverBitmapsByRawResId.clear()
        coverPositionsByRawResId.clear()
        super.onDestroyView()
    }

    override fun onDestroy() {
        coverFrameExecutor.shutdownNow()
        super.onDestroy()
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
        channelAdapter = ChannelPagerAdapter(channels)
        channelPager.adapter = channelAdapter
        // 初始默认显示“推荐”。先设置当前页，再注册回调，避免初始化阶段触发一次多余的播放请求。
        channelPager.setCurrentItem(defaultChannelIndex, false)
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
                // 横向切到新频道后，等 ViewPager2 完成页面绑定，再播放新频道里的当前视频。
                postVisibleVideoPlaybackRequest()
            }
        }.also {
            channelPager.registerOnPageChangeCallback(it)
        }

        channelRow.post {
            if (view == null) return@post

            updateChannelBar(defaultChannelIndex, 0f)
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

    // 播放入口只保留这一处：不管是进入页面、横向切频道，还是竖向切视频，
    // 最终都回到这里，把同一只 feedPlayer 挂到当前可见视频页上。
    private fun requestVisibleVideoPlayback() {
        if (view == null || !::channelAdapter.isInitialized) return

        val videoHolder = currentVideoHolderOrNull()
        if (videoHolder == null) {
            // ViewPager2 可能已经选中页面，但 RecyclerView 还没把对应 holder 绑定好。
            postVisibleVideoPlaybackRequest()
            return
        }

        playVideoHolder(videoHolder)
    }

    private fun currentVideoHolderOrNull(): VideoViewHolder? {
        val channelHolder = channelAdapter.boundHolders[currentChannelIndex] ?: return null
        val videoAdapter = channelHolder.videoAdapter ?: return null

        return videoAdapter.boundHolders[channelHolder.videoPager.currentItem]
    }

    private fun playVideoHolder(videoHolder: VideoViewHolder) {
        val currentVideo = videoHolder.video ?: return
        val rawResId = currentVideo.rawResId
        val currentCover = coverBitmapsByRawResId[rawResId]
        val isSwitchingHolder = playingHolder != videoHolder
        val isSwitchingVideo = playingRawResId != rawResId

        if (currentCover == null && isSwitchingVideo) {
            // 第一遍没播放过的视频也必须先有封面。
            // 如果默认封面还没抽出来，先不挂播放器，避免 PlayerView 的空 surface 闪黑。
            manualPausedRawResId = null
            requestCoverFrame(rawResId, DEFAULT_COVER_POSITION_MS)
            if (isSwitchingHolder) {
                movePlaybackToHolder(videoHolder)
            } else {
                saveCurrentPlaybackPosition()
            }
            waitingForFirstFrameRawResId = null
            pauseFeedPlayer()
            videoHolder.playerView.player = null
            hideCover(videoHolder)
            return
        }

        val player = feedPlayer ?: createFeedPlayer()

        // 同一只 ExoPlayer 只能挂在一个 PlayerView 上。
        // 切换视频页时，先从旧 holder 拿下来，再挂到新的 holder。
        if (isSwitchingHolder) {
            movePlaybackToHolder(videoHolder)
        }

        val shouldWaitForFirstFrame = isSwitchingHolder || isSwitchingVideo
        videoHolder.coverImageView.setImageBitmap(currentCover)
        videoHolder.coverImageView.visibility =
            if (currentCover != null && shouldWaitForFirstFrame) View.VISIBLE else View.GONE
        waitingForFirstFrameRawResId =
            if (currentCover != null && shouldWaitForFirstFrame) rawResId else null

        if (isSwitchingVideo) {
            manualPausedRawResId = null
            if (!isSwitchingHolder) {
                saveCurrentPlaybackPosition()
            }
            replacePlayerMedia(player, videoHolder, rawResId)
        }

        videoHolder.playerView.player = player
        if (manualPausedRawResId == rawResId) {
            pauseFeedPlayer()
            return
        }

        player.volume = 1f
        player.playWhenReady = true
        player.play()
    }

    private fun createFeedPlayer(): ExoPlayer {
        return ExoPlayer.Builder(requireContext()).build().also { player ->
            // 短视频一般循环播放；demo 里每条视频播放完后自动从头继续。
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    val waitingRawResId = waitingForFirstFrameRawResId
                    val holder = playingHolder

                    if (
                        events.contains(Player.EVENT_RENDERED_FIRST_FRAME) &&
                        waitingRawResId != null &&
                        holder?.video?.rawResId == waitingRawResId
                    ) {
                        // 新视频第一帧真正画出来以后，再移开封面，避免 prepare 阶段露出黑底。
                        holder.coverImageView.visibility = View.GONE
                        waitingForFirstFrameRawResId = null
                    }
                }
            })
            feedPlayer = player
        }
    }

    private fun movePlaybackToHolder(newHolder: VideoViewHolder) {
        val oldHolder = playingHolder

        saveCurrentPlaybackPosition()
        oldHolder?.playerView?.player = null
        oldHolder?.let { showCachedCover(it) }
        playingHolder = newHolder
    }

    private fun replacePlayerMedia(
        player: ExoPlayer,
        videoHolder: VideoViewHolder,
        rawResId: Int
    ) {
        pauseFeedPlayer()
        videoHolder.playerView.player = null

        // res/raw 里的视频不能直接用文件路径播放，需要转成 Media3 能识别的 android.resource Uri。
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

    private fun toggleVideoPlayback(holder: VideoViewHolder) {
        val video = holder.video ?: return
        if (video.isLiveCard) return

        // 只允许点击当前正在播放的那张视频卡。
        // 预加载出来的上下相邻卡片即使被点到，也不应该控制播放器。
        if (holder != playingHolder || playingRawResId != video.rawResId) return

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

    private fun showCachedCover(holder: VideoViewHolder) {
        val rawResId = holder.video?.rawResId ?: return
        val cover = coverBitmapsByRawResId[rawResId]

        holder.coverImageView.setImageBitmap(cover)
        holder.coverImageView.visibility = if (cover == null) View.GONE else View.VISIBLE
    }

    private fun hideCover(holder: VideoViewHolder) {
        holder.coverImageView.setImageBitmap(null)
        holder.coverImageView.visibility = View.GONE
    }

    private fun postVisibleVideoPlaybackRequest() {
        if (!::channelPager.isInitialized) return

        channelPager.post {
            if (view != null) {
                requestVisibleVideoPlayback()
            }
        }
    }

    private fun saveCurrentPlaybackPosition() {
        val rawResId = playingRawResId ?: return
        val player = feedPlayer ?: return
        val positionMs = player.currentPosition

        // feedPlayer 只有一只，但进度是每条视频自己的状态。
        // 切视频、退后台、销毁 View 前都先保存，之后切回来再 seekTo 恢复。
        playbackPositionsByRawResId[rawResId] = positionMs
        requestCoverFrame(rawResId, positionMs)
    }

    private fun requestCoverFrame(rawResId: Int, positionMs: Long) {
        val safePositionMs = positionMs.coerceAtLeast(0L)

        val lastCoverPosition = coverPositionsByRawResId[rawResId]
        val coverIsFreshEnough = lastCoverPosition != null &&
            abs(lastCoverPosition - safePositionMs) < COVER_REFRESH_DISTANCE_MS

        if (coverIsFreshEnough || coverRequestsInFlight.contains(rawResId)) return

        coverRequestsInFlight.add(rawResId)
        val requestedPositionMs = safePositionMs
        val appResources = resources

        coverFrameExecutor.execute {
            var assetFileDescriptor: AssetFileDescriptor? = null
            var frameBitmap: Bitmap? = null
            val retriever = MediaMetadataRetriever()

            try {
                assetFileDescriptor = appResources.openRawResourceFd(rawResId)
                retriever.setDataSource(
                    assetFileDescriptor.fileDescriptor,
                    assetFileDescriptor.startOffset,
                    assetFileDescriptor.length
                )

                val rawFrame = retriever.getFrameAtTime(
                    requestedPositionMs * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
                frameBitmap = rawFrame?.let { bitmap ->
                    val maxSide = max(bitmap.width, bitmap.height)

                    if (maxSide <= COVER_MAX_SIZE_PX) {
                        bitmap
                    } else {
                        val scale = COVER_MAX_SIZE_PX.toFloat() / maxSide
                        Bitmap.createScaledBitmap(
                            bitmap,
                            (bitmap.width * scale).roundToInt(),
                            (bitmap.height * scale).roundToInt(),
                            true
                        ).also {
                            bitmap.recycle()
                        }
                    }
                }
            } catch (_: Exception) {
                frameBitmap?.recycle()
                frameBitmap = null
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {
                }
                try {
                    assetFileDescriptor?.close()
                } catch (_: Exception) {
                }
            }

            mainHandler.post {
                coverRequestsInFlight.remove(rawResId)

                val newCover = frameBitmap ?: return@post
                if (view == null) {
                    newCover.recycle()
                    return@post
                }

                val oldCover = coverBitmapsByRawResId.put(rawResId, newCover)
                coverPositionsByRawResId[rawResId] = requestedPositionMs

                if (::channelAdapter.isInitialized) {
                    channelAdapter.boundHolders.values.forEach { channelHolder ->
                        channelHolder.videoAdapter?.boundHolders?.values?.forEach { videoHolder ->
                            if (videoHolder.video?.rawResId == rawResId) {
                                videoHolder.coverImageView.setImageBitmap(newCover)
                                videoHolder.coverImageView.visibility =
                                    if (videoHolder == playingHolder && playingRawResId == rawResId) {
                                        View.GONE
                                    } else {
                                        View.VISIBLE
                                    }
                            }
                        }
                    }

                    val currentVideoHolder = currentVideoHolderOrNull()

                    if (currentVideoHolder?.video?.rawResId == rawResId && playingRawResId != rawResId) {
                        postVisibleVideoPlaybackRequest()
                    }
                }

                oldCover?.recycle()
            }
        }
    }

    private fun detachFeedPlayerFrom(holder: VideoViewHolder) {
        if (playingHolder != holder) return

        saveCurrentPlaybackPosition()
        pauseFeedPlayer()
        holder.playerView.player = null
        showCachedCover(holder)
        playingHolder = null
        waitingForFirstFrameRawResId = null
    }

    private inner class ChannelPagerAdapter(
        private val channels: List<Channel>
    ) : RecyclerView.Adapter<ChannelViewHolder>() {

        // 横向 adapter 记录自己绑定过的频道页；竖向 VideoPagerAdapter 也用同样方式记录视频页。
        // 这两个 map 的来源都是 RecyclerView 的 onBind/onViewRecycled 生命周期。
        val boundHolders = mutableMapOf<Int, ChannelViewHolder>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
            // 每个横向频道页里，都放一个竖向 ViewPager2，用来上下刷视频。
            val videoPager = ViewPager2(parent.context).apply {
                id = View.generateViewId()
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                orientation = ViewPager2.ORIENTATION_VERTICAL
                // 预加载相邻一页的 ViewHolder，让标题和 PlayerView 提前准备好。
                // 真正的视频解码只交给 feedPlayer，不在预加载页里提前创建播放器。
                offscreenPageLimit = 1
                overScrollMode = View.OVER_SCROLL_NEVER
            }

            return ChannelViewHolder(videoPager)
        }

        override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
            // 同一个 holder 可能被 RecyclerView 复用到新 position。
            // 先删除旧映射，再记录新 position -> holder，避免播放控制找到过期页面。
            boundHolders.entries.removeAll { it.value == holder }
            boundHolders[position] = holder
            holder.bindChannel(channels[position], position)
        }

        override fun getItemCount(): Int {
            return channels.size
        }

        override fun onViewRecycled(holder: ChannelViewHolder) {
            // 频道页被回收后，它里面的竖向 ViewPager 和 holder 映射都不能继续保留。
            val position = boundHolders.entries.firstOrNull { it.value == holder }?.key
            if (position != null) {
                boundHolders.remove(position)
            }
            holder.releaseChannelPage()
            super.onViewRecycled(holder)
        }
    }

    private inner class ChannelViewHolder(
        val videoPager: ViewPager2
    ) : RecyclerView.ViewHolder(videoPager) {

        private var channelPosition = RecyclerView.NO_POSITION
        var videoAdapter: VideoPagerAdapter? = null
            private set
        private var videoPageChangeCallback: ViewPager2.OnPageChangeCallback? = null

        fun bindChannel(channel: Channel, position: Int) {
            val adapter = VideoPagerAdapter(channel)

            // holder 复用时，旧频道留下的回调和视频页必须先清掉。
            videoPageChangeCallback?.let { videoPager.unregisterOnPageChangeCallback(it) }
            videoAdapter?.let { oldAdapter ->
                oldAdapter.boundHolders.values.forEach {
                    detachFeedPlayerFrom(it)
                    it.coverImageView.setImageBitmap(null)
                }
                oldAdapter.boundHolders.clear()
            }
            channelPosition = position
            videoAdapter = adapter
            videoPager.adapter = adapter
            // 竖向视频流是伪无限循环，所以第一次绑定时跳到中间位置，
            // 用户往上/往下都能继续刷，不容易马上碰到列表边界。
            videoPager.setCurrentItem(loopStartPosition(channel.videos.size), false)
            videoPageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    if (channelPosition == currentChannelIndex) {
                        // 竖向切到新视频时，不在 ChannelViewHolder 里直接播放。
                        // 回到 Fragment 的统一入口，让同一只 feedPlayer 换到新的 PlayerView。
                        postVisibleVideoPlaybackRequest()
                    }
                }
            }.also {
                videoPager.registerOnPageChangeCallback(it)
            }
        }

        // 频道页被回收时，注销自己的竖向 ViewPager 回调，并断开可能挂在里面的全局播放器。
        fun releaseChannelPage() {
            videoPageChangeCallback?.let { videoPager.unregisterOnPageChangeCallback(it) }
            videoPageChangeCallback = null
            videoAdapter?.let { adapter ->
                adapter.boundHolders.values.forEach {
                    detachFeedPlayerFrom(it)
                    it.coverImageView.setImageBitmap(null)
                }
                adapter.boundHolders.clear()
            }
            videoAdapter = null
            videoPager.adapter = null
        }
    }

    private inner class VideoPagerAdapter(
        private val channel: Channel
    ) : RecyclerView.Adapter<VideoViewHolder>() {

        // 记录已经绑定的视频页。播放入口需要从“当前竖向 position”找到对应 VideoViewHolder，
        // 才能把全局播放器挂到它的 PlayerView 上。
        val boundHolders = mutableMapOf<Int, VideoViewHolder>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
            val rootView = FrameLayout(parent.context).apply {
                setBackgroundResource(R.color.video_background)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            // PlayerView 是 Media3 提供的视频渲染 View，全局 ExoPlayer 会在当前页被选中时挂上来。
            // surface_type 只能在 XML 里指定；这里用 texture_view，让封面 ImageView 可以稳定盖住播放器。
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
                coverImageView,
                titleText,
                metaText
            )
        }

        override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
            // 伪无限列表中，position 会很大；holder 也会复用。
            // 先更新映射，再把真实视频数据绑定到这个 holder。
            boundHolders.entries.removeAll { it.value == holder }
            boundHolders[position] = holder
            holder.bindVideo(channel, realVideoIndex(position))
        }

        override fun getItemCount(): Int {
            // 多条视频时给一个很大的数量，形成“看起来可以一直刷”的效果。
            // 真实要展示哪条视频，由 realVideoIndex(position) 取模决定。
            return if (channel.videos.size <= 1) {
                channel.videos.size
            } else {
                VIDEO_LOOP_ITEM_COUNT
            }
        }

        private fun realVideoIndex(position: Int): Int {
            // 把伪无限 position 映射回真实 videos 列表下标。
            // 例如真实只有 3 条，position 5001 实际显示 5001 % 3 = 第 1 条。
            return position % channel.videos.size
        }

        override fun onViewRecycled(holder: VideoViewHolder) {
            // 视频页离开 RecyclerView 复用池时，如果全局播放器正挂在它身上，就先断开。
            val position = boundHolders.entries.firstOrNull { it.value == holder }?.key
            if (position != null) {
                boundHolders.remove(position)
            }
            detachFeedPlayerFrom(holder)
            holder.coverImageView.setImageBitmap(null)
            super.onViewRecycled(holder)
        }

    }

    private inner class VideoViewHolder(
        private val rootView: FrameLayout,
        val playerView: PlayerView,
        val coverImageView: ImageView,
        private val titleText: TextView,
        private val metaText: TextView
    ) : RecyclerView.ViewHolder(rootView) {

        init {
            rootView.setOnClickListener {
                toggleVideoPlayback(this)
            }
        }

        // 当前 holder 绑定的是哪条视频数据。
        // 真正播放时，Fragment 会读取这里的 rawResId，再把全局播放器挂到 playerView。
        var video: VideoItem? = null
            private set

        fun bindVideo(channel: Channel, position: Int) {
            val currentVideo = channel.videos[position]
            video = currentVideo

            rootView.setBackgroundResource(R.color.video_background)
            if (playingHolder != this) {
                playerView.player = null
            }
            val cover = coverBitmapsByRawResId[currentVideo.rawResId]
            coverImageView.setImageBitmap(cover)
            coverImageView.visibility = if (playingHolder == this || cover == null) View.GONE else View.VISIBLE
            if (cover == null) {
                requestCoverFrame(currentVideo.rawResId, DEFAULT_COVER_POSITION_MS)
            }
            playerView.visibility = View.VISIBLE
            titleText.text = currentVideo.title
            metaText.text = getString(R.string.video_meta, currentVideo.author, currentVideo.stats)
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
                    VideoItem("我们是同胞", "@daily_watch", "1.8w likes  ·  428 comments", R.raw.compatriot),
                    VideoItem("猕猴桃", "@fruit_note", "6,204 likes  ·  88 comments", R.raw.kiwi),
                    VideoItem("巨构", "@city_scale", "9,821 likes  ·  156 comments", R.raw.megastructure),
                    VideoItem("麦浪过来咿呀咿呀", "@field_song", "2.4w likes  ·  391 comments", R.raw.wheat_wave)
                )
            ),
            Channel(
                getString(R.string.channel_experience),
                listOf(
                    VideoItem("我们手牵手云海下约好", "@cloud_walk", "8,176 likes  ·  221 comments", R.raw.cloud_hands),
                    VideoItem("R&B 便利贴", "@music_memo", "1.1w likes  ·  312 comments", R.raw.rnb_note),
                    VideoItem("我就这样看讨厌的人", "@small_mood", "7,604 likes  ·  108 comments", R.raw.disliked_people)
                )
            ),
            Channel(
                getString(R.string.channel_hot),
                listOf(
                    VideoItem("我枣糕世界！", "@hot_today", "12.4w likes  ·  2,901 comments", R.raw.date_cake_world),
                    VideoItem("人心呐", "@trend_watch", "7.6w likes  ·  1,406 comments", R.raw.human_heart),
                    VideoItem("一天一天贴近你的心", "@quick_news", "5.2w likes  ·  984 comments", R.raw.closer_to_your_heart),
                    VideoItem("范玮琪开口低音", "@music_clip", "9.3w likes  ·  1,702 comments", R.raw.fan_weiqi_low_note)
                )
            ),
            Channel(
                getString(R.string.channel_live),
                listOf(
                    VideoItem("World War 3", "@timeline_notes", "4,321 likes  ·  73 comments", R.raw.world_war_3),
                    VideoItem("安详", "@live_room", "2.1w likes  ·  1,032 comments", R.raw.live_peaceful),
                    VideoItem("iPhone 手机", "@maker_live", "9,405 likes  ·  642 comments", R.raw.live_iphone),
                    VideoItem("成语接龙", "@game_live", "4.8w likes  ·  3,215 comments", R.raw.live_idiom_game)
                )
            ),
            Channel(
                getString(R.string.channel_following),
                listOf(
                    VideoItem("优雅步伐", "@friend_feed", "3,502 likes  ·  66 comments", R.raw.elegant_steps),
                    VideoItem("烈日永照", "@study_daily", "2,908 likes  ·  45 comments", R.raw.endless_sun),
                    VideoItem("草原烂麦", "@code_diary", "5,601 likes  ·  118 comments", R.raw.grassland_wheat)
                )
            ),
            Channel(
                getString(R.string.channel_local),
                listOf(
                    VideoItem("黄龙江一带全部带蓝牙", "@city_walk", "1.2w likes  ·  278 comments", R.raw.yellow_dragon_bluetooth),
                    VideoItem("怎样你们才能放过我", "@local_food", "2.7w likes  ·  604 comments", R.raw.let_me_go),
                    VideoItem("旋转几轮变成我们深刻的指纹", "@nearby_view", "7,889 likes  ·  139 comments", R.raw.fingerprint_spin)
                )
            ),
            Channel(
                getString(R.string.channel_recommended),
                listOf(
                    VideoItem("今日穿搭灵感", "@style_daily", "3.2w likes  ·  910 comments", R.raw.outfit),
                    VideoItem("白鲸吐泡泡", "@ocean_diary", "6.8w likes  ·  1,508 comments", R.raw.beluga_bubbles),
                    VideoItem("水母慢慢游", "@jellyfish_view", "9.9w likes  ·  2,345 comments", R.raw.jellyfish)
                )
            )
        )
    }

    private fun loopStartPosition(realItemCount: Int): Int {
        if (realItemCount <= 1) return 0

        // 伪无限视频流从大列表中间开始，而不是从 0 开始。
        // 这样用户刚进入频道时，向上或向下都还有很多可滑空间。
        val middle = VIDEO_LOOP_ITEM_COUNT / 2
        // 对真实视频数量取整，保证初始 position 正好落在第 0 条真实视频上。
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
        val rawResId: Int,
        val isLiveCard: Boolean = false
    )

    private companion object {
        const val VIDEO_LOOP_ITEM_COUNT = 10_000
        const val DEFAULT_COVER_POSITION_MS = 500L
        const val COVER_MAX_SIZE_PX = 720
        const val COVER_REFRESH_DISTANCE_MS = 1_000L
        val SELECTED_TEXT_COLOR: Int = Color.WHITE
        val NORMAL_TEXT_COLOR: Int = Color.argb(153, 255, 255, 255)
    }
}
