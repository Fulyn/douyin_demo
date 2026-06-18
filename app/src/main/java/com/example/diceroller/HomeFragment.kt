package com.example.diceroller

import android.os.Bundle
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2

class HomeFragment : Fragment(R.layout.fragment_home) {

    private lateinit var channels: List<Channel>
    private lateinit var channelPager: ViewPager2
    private lateinit var channelAdapter: ChannelPagerAdapter
    private lateinit var channelBarController: ChannelBarController
    private lateinit var playbackController: FeedPlaybackController

    private var currentChannelIndex = 0
    private var channelPageChangeCallback: ViewPager2.OnPageChangeCallback? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        channels = DemoFeedData.createChannels(requireContext())
        currentChannelIndex = channels.lastIndex
        channelPager = view.findViewById(R.id.channelPager)

        setupPlaybackController()
        setupChannelBar(view)
        setupChannelPager()
    }

    override fun onStart() {
        super.onStart()
        if (::playbackController.isInitialized) {
            playbackController.playCurrentVisiblePage()
        }
    }

    override fun onStop() {
        if (::playbackController.isInitialized) {
            playbackController.pauseCurrent()
        }
        super.onStop()
    }

    override fun onDestroyView() {
        channelPageChangeCallback?.let { channelPager.unregisterOnPageChangeCallback(it) }
        channelPageChangeCallback = null

        if (::channelAdapter.isInitialized) {
            channelAdapter.releaseBoundPages()
        }
        if (::channelPager.isInitialized) {
            channelPager.adapter = null
        }
        if (::channelBarController.isInitialized) {
            channelBarController.clear()
        }
        if (::playbackController.isInitialized) {
            playbackController.release()
        }

        super.onDestroyView()
    }

    private fun setupPlaybackController() {
        playbackController = FeedPlaybackController(
            context = requireContext(),
            resources = resources,
            currentVideoPageProvider = { currentVideoPageOrNull() },
            boundVideoPagesProvider = {
                if (::channelAdapter.isInitialized) {
                    channelAdapter.boundVideoPages()
                } else {
                    emptyList()
                }
            },
            postPlaybackRequest = { postVisibleVideoPlaybackRequest() }
        )
    }

    private fun setupChannelBar(view: View) {
        channelBarController = ChannelBarController(
            scrollView = view.findViewById<HorizontalScrollView>(R.id.channelScrollView),
            row = view.findViewById<LinearLayout>(R.id.channelRow),
            indicator = view.findViewById(R.id.channelIndicator),
            onChannelClick = { index ->
                channelPager.setCurrentItem(index, false)
            }
        )
        channelBarController.bind(channels, currentChannelIndex)
    }

    private fun setupChannelPager() {
        channelPager.orientation = ViewPager2.ORIENTATION_HORIZONTAL
        channelPager.overScrollMode = View.OVER_SCROLL_NEVER
        channelAdapter = ChannelPagerAdapter(
            channels = channels,
            playbackHost = playbackController,
            onVideoPageSelected = { channelPosition ->
                if (channelPosition == currentChannelIndex) {
                    postVisibleVideoPlaybackRequest()
                }
            }
        )
        channelPager.adapter = channelAdapter
        channelPager.setCurrentItem(currentChannelIndex, false)

        channelPageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(
                position: Int,
                positionOffset: Float,
                positionOffsetPixels: Int
            ) {
                channelBarController.update(position, positionOffset)
            }

            override fun onPageSelected(position: Int) {
                currentChannelIndex = position
                channelBarController.update(position, 0f)
                postVisibleVideoPlaybackRequest()
            }
        }.also {
            channelPager.registerOnPageChangeCallback(it)
        }
    }

    private fun currentVideoPageOrNull(): VideoPage? {
        if (!::channelAdapter.isInitialized) return null

        return channelAdapter.videoPageAt(currentChannelIndex)
    }

    private fun postVisibleVideoPlaybackRequest() {
        if (!::channelPager.isInitialized) return

        channelPager.post {
            if (view != null && ::playbackController.isInitialized) {
                playbackController.playCurrentVisiblePage()
            }
        }
    }
}
