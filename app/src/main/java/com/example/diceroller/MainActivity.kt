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

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import androidx.viewpager2.widget.ViewPager2
import com.facebook.drawee.backends.pipeline.Fresco

/**
 * 抖音风首页：横向频道 + 每个频道的竖向视频流。直接由 Activity 承载，不再套 Fragment。
 * 进入直播间用启动 LiveRoomActivity 实现（见 [openLiveRoom]）。
 *
 * 频道数据这一版改为网络拉取：onCreate 里向 [FeedRepository] 请求 videos.json，
 * 拿到结果（主线程回调）后才搭建 pager，所以 channelPager 在加载完成前可能为 null。
 */
@UnstableApi
class MainActivity : AppCompatActivity() {

    private var channelPager: ChannelPager? = null
    private var channelBar: ChannelBar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 进程里第一个 Activity，且早于任何 SimpleDraweeView 被 inflate，在此初始化 Fresco 一次即可。
        Fresco.initialize(applicationContext)
        VideoController.init(this)
        setContentView(R.layout.activity_main)

        FeedRepository.loadChannels(
            onSuccess = { channels -> setupChannels(channels) },
            onError = { error ->
                Toast.makeText(this, "加载失败：${error.message}", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun setupChannels(channels: List<Channel>) {
        if (channels.isEmpty()) return

        val initialChannelIndex = channels.lastIndex
        val homeRoot = findViewById<View>(R.id.home_root)
        val channelPagerView = findViewById<ViewPager2>(R.id.channelPager)
        channelBar = ChannelBar(
            rootView = homeRoot,
            channels = channels,
            selectedIndex = initialChannelIndex,
            channelPagerView = channelPagerView
        )
        channelPager = ChannelPager(
            pager = channelPagerView,
            channels = channels,
            selectedIndex = initialChannelIndex,
            channelBar = channelBar!!,
            onEnterLiveRoom = { startPosition -> openLiveRoom(startPosition) }
        )
        // 数据可能在 onStart 之后才到达，这里补一次起播（onStart 当时还没有 pager 可播）。
        channelPager?.playCurrentVideo()
    }

    override fun onStart() {
        super.onStart()
        channelPager?.playCurrentVideo()
    }

    override fun onStop() {
        // 只暂停"本页正在播的那条"。若此刻直播间已接管播放器，这次请求会自动失效，
        // 不会掐断直播间刚开始的播放。
        VideoController.shared.pauseVideo(channelPager?.currentVideo())
        super.onStop()
    }

    override fun onDestroy() {
        channelPager?.release()
        // 仅在真正结束（退出 App）时释放共享播放器；配置变更/被系统回收时保活单例，
        // 以免把播放器从前台的 LiveRoomActivity 脚下抽走。
        if (isFinishing) VideoController.shared.destroy()
        super.onDestroy()
    }

    private fun openLiveRoom(startPosition: Int) {
        startActivity(
            Intent(this, LiveRoomActivity::class.java)
                .putExtra(EXTRA_START_POSITION, startPosition)
        )
    }

    companion object {
        const val EXTRA_START_POSITION = "start_position"
    }
}

/**
 * 直播间：竖向直播流，从用户点击预览的那条打开，可上下滑切换。复用 VideoPager，
 * 仅以 isInsideLiveRoom=true 切到"间内"UI（纯视频，无预览浮层）。
 */
@UnstableApi
class LiveRoomActivity : AppCompatActivity() {

    private lateinit var videoPager: VideoPager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pagerView = ViewPager2(this).apply {
            id = View.generateViewId()
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundResource(R.color.video_background)
        }
        setContentView(pagerView)

        // 直播频道复用 MainActivity 拉取并缓存的那份数据；理论上进入按钮只在加载完成后才可见，
        // 故缓存必然已就绪。万一为空（异常情况）则直接结束，避免空指针。
        val liveChannel = FeedRepository.liveChannel()
        if (liveChannel == null) {
            finish()
            return
        }
        videoPager = VideoPager(
            pager = pagerView,
            channel = liveChannel,
            // 直播间是整屏唯一内容，永远处于"可见频道"。
            isActiveChannel = { true },
            selectedItemIndex = intent.getIntExtra(MainActivity.EXTRA_START_POSITION, 0),
            isInsideLiveRoom = true
        )
    }

    override fun onStart() {
        super.onStart()
        if (::videoPager.isInitialized) videoPager.playCurrentVideo()
    }

    override fun onStop() {
        // 同理：只暂停直播间自己这条；返回首页时若首页已接管，这次请求自动失效。
        VideoController.shared.pauseVideo(if (::videoPager.isInitialized) videoPager.currentVideo() else null)
        super.onStop()
    }

    override fun onDestroy() {
        if (::videoPager.isInitialized) videoPager.release()
        super.onDestroy()
    }
}
