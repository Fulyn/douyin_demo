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

import android.os.Bundle
import android.view.ViewGroup
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.media3.common.util.UnstableApi
import androidx.viewpager2.widget.ViewPager2

/**
 * Hosts app-level fragments.
 *
 * MainActivity deliberately stays small so HomeFragment can own the home screen
 * lifecycle, and later LiveRoomFragment can be added without mixing screens.
 */
@UnstableApi
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VideoController.init(this)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, HomeFragment())
                .commit()
        }
    }

    fun openLiveRoom(startPosition: Int) {
        VideoController.shared.pauseCurrentVideo()
        supportFragmentManager.beginTransaction()
            .add(R.id.fragmentContainer, LiveRoomFragment.newInstance(startPosition))
            .addToBackStack(LiveRoomFragment.BACK_STACK_NAME)
            .commit()
    }

    fun restoreHomeVideoSource() {
        supportFragmentManager.fragments
            .filterIsInstance<HomeFragment>()
            .firstOrNull()
            ?.playCurrentVideo()
    }

    override fun onDestroy() {
        VideoController.shared.destroy()
        super.onDestroy()
    }
}

@UnstableApi
class HomeFragment : Fragment(R.layout.fragment_home) {

    private lateinit var channelPager: ChannelPager
    private lateinit var channelBar: ChannelBar

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val channels = DemoVideoData.createChannels(requireContext())
        val initialChannelIndex = channels.lastIndex

        val channelPagerView = view.findViewById<ViewPager2>(R.id.channelPager)
        val hostActivity = requireActivity() as MainActivity
        channelBar = ChannelBar(
            rootView = view,
            channels = channels,
            selectedIndex = initialChannelIndex,
            channelPagerView = channelPagerView
        )

        channelPager = ChannelPager(
            pager = channelPagerView,
            channels = channels,
            selectedIndex = initialChannelIndex,
            channelBar = channelBar,
            onEnterLiveRoom = { _, startPosition ->
                hostActivity.openLiveRoom(startPosition)
            }
        )
        // 起播统一放在 onStart：它在首次创建时也必然紧跟 onViewCreated 触发，
        // 还能覆盖从后台返回的恢复。这里不再额外调一次，避免对同一条视频连播两遍 play。
    }

    override fun onStart() {
        super.onStart()
        playCurrentVideo()
    }

    override fun onStop() {
        VideoController.shared.pauseCurrentVideo()
        super.onStop()
    }

    override fun onDestroyView() {
        channelPager.release()

        super.onDestroyView()
    }

    fun playCurrentVideo() {
        channelPager.playCurrentVideo()
    }
}

@UnstableApi
class LiveRoomFragment : Fragment() {

    private lateinit var livePager: LivePager

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ViewPager2(requireContext()).apply {
            id = View.generateViewId()
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundResource(R.color.video_background)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val liveChannel = DemoVideoData.createLiveChannel(requireContext())
        livePager = LivePager(
            pager = view as ViewPager2,
            videoItems = liveChannel.videoItems,
            showEnterButton = false,
            selectedItemIndex = requireArguments().getInt(ARG_START_POSITION)
        )
        // 同 HomeFragment：起播交给 onStart，onViewCreated 不再重复调。
    }

    override fun onStart() {
        super.onStart()
        playCurrentVideo()
    }

    override fun onStop() {
        VideoController.shared.pauseCurrentVideo()
        super.onStop()
    }

    override fun onDestroyView() {
        livePager.release()
        (requireActivity() as MainActivity).restoreHomeVideoSource()
        super.onDestroyView()
    }

    private fun playCurrentVideo() {
        livePager.playCurrentVideo()
    }

    companion object {
        const val BACK_STACK_NAME = "live_room"
        private const val ARG_START_POSITION = "start_position"

        fun newInstance(startPosition: Int): LiveRoomFragment {
            return LiveRoomFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_START_POSITION, startPosition)
                }
            }
        }
    }
}
