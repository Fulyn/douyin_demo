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
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import androidx.viewpager2.widget.ViewPager2

/**
 * 抖音风首页：横向频道 + 每个频道的竖向视频流。直接由 Activity 承载，不再套 Fragment。
 * 进入直播间用启动 LiveRoomActivity 实现（见 [openLiveRoom]）。
 */
@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var channelPager: ChannelPager
    private lateinit var channelBar: ChannelBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VideoController.init(this)
        setContentView(R.layout.activity_main)

        val channels = DemoVideoData.createChannels(this)
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
            channelBar = channelBar,
            onEnterLiveRoom = { startPosition -> openLiveRoom(startPosition) }
        )
        // 起播统一放在 onStart：首次创建时它必然紧跟 onCreate 触发，还能覆盖从后台/直播间返回的恢复。
    }

    override fun onStart() {
        super.onStart()
        channelPager.playCurrentVideo()
    }

    override fun onStop() {
        VideoController.shared.pauseCurrentVideo()
        super.onStop()
    }

    override fun onDestroy() {
        channelPager.release()
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

        val liveChannel = DemoVideoData.createLiveChannel(this)
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
        videoPager.playCurrentVideo()
    }

    override fun onStop() {
        VideoController.shared.pauseCurrentVideo()
        super.onStop()
    }

    override fun onDestroy() {
        videoPager.release()
        super.onDestroy()
    }
}
