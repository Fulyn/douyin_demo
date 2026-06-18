package com.example.diceroller

import android.graphics.Bitmap
import androidx.media3.ui.PlayerView

interface VideoPage {
    val video: VideoItem?
    val playerView: PlayerView

    fun setCover(bitmap: Bitmap?, visible: Boolean)
    fun hideCover()
    fun clearCover()
}

interface VideoPlaybackHost {
    fun coverFor(rawResId: Int): Bitmap?
    fun requestCover(rawResId: Int)
    fun isPlayingPage(page: VideoPage): Boolean
    fun onVideoPageClicked(page: VideoPage)
    fun onVideoPageReleased(page: VideoPage)
}
