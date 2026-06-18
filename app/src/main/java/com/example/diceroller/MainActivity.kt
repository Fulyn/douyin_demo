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
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, HomeFragment())
                .commit()
        }
    }
}

@UnstableApi
class HomeFragment : Fragment(R.layout.fragment_home) {

    private lateinit var channelPager: ChannelPager
    private lateinit var channelBar: ChannelBar
    private lateinit var videoController: VideoController

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val channels = DemoVideoData.createChannels(requireContext())
        val initialChannelIndex = channels.lastIndex

        val channelPagerView = view.findViewById<ViewPager2>(R.id.channelPager)
        videoController = VideoController(
            context = requireContext(),
            resources = resources,
            currentVideoProvider = { channelPager.currentVideo() },
            boundVideosProvider = { channelPager.boundVideos() }
        )
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
            videoController = videoController,
            channelBar = channelBar
        )
    }

    override fun onStart() {
        super.onStart()
        channelPager.playCurrentVideo()
    }

    override fun onStop() {
        videoController.pauseCurrentVideo()
        super.onStop()
    }

    override fun onDestroyView() {
        channelPager.release()
        videoController.release()

        super.onDestroyView()
    }
}
