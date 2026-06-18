package com.example.diceroller

data class Channel(
    val title: String,
    val videos: List<VideoItem>
)

data class VideoItem(
    val title: String,
    val author: String,
    val stats: String,
    val rawResId: Int,
    val isLiveCard: Boolean = false
)
