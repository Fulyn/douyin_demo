package com.example.diceroller

import android.content.Context

data class Channel(
    val title: String,
    val videoItems: List<VideoItem>,
    val isLiveChannel: Boolean = false
)

data class VideoItem(
    val title: String,
    val author: String,
    val stats: String,
    val videoResId: Int,
    val coverResId: Int
)

object DemoVideoData {

    fun createChannels(context: Context): List<Channel> {
        return listOf(
            Channel(
                context.getString(R.string.channel_featured),
                listOf(
                    VideoItem("我们是同胞", "@daily_watch", "1.8w likes  ·  428 comments", R.raw.compatriot, R.drawable.compatriot_cover),
                    VideoItem("猕猴桃", "@fruit_note", "6,204 likes  ·  88 comments", R.raw.kiwi, R.drawable.kiwi_cover),
                    VideoItem("巨构", "@city_scale", "9,821 likes  ·  156 comments", R.raw.megastructure, R.drawable.megastructure_cover),
                    VideoItem("麦浪过来咿呀咿呀", "@field_song", "2.4w likes  ·  391 comments", R.raw.wheat_wave, R.drawable.wheat_wave_cover)
                )
            ),
            Channel(
                context.getString(R.string.channel_experience),
                listOf(
                    VideoItem("我们手牵手云海下约好", "@cloud_walk", "8,176 likes  ·  221 comments", R.raw.cloud_hands, R.drawable.cloud_hands_cover),
                    VideoItem("R&B 便利贴", "@music_memo", "1.1w likes  ·  312 comments", R.raw.rnb_note, R.drawable.rnb_note_cover),
                    VideoItem("我就这样看讨厌的人", "@small_mood", "7,604 likes  ·  108 comments", R.raw.disliked_people, R.drawable.disliked_people_cover)
                )
            ),
            Channel(
                context.getString(R.string.channel_hot),
                listOf(
                    VideoItem("我枣糕世界！", "@hot_today", "12.4w likes  ·  2,901 comments", R.raw.date_cake_world, R.drawable.date_cake_world_cover),
                    VideoItem("人心呐", "@trend_watch", "7.6w likes  ·  1,406 comments", R.raw.human_heart, R.drawable.human_heart_cover),
                    VideoItem("一天一天贴近你的心", "@quick_news", "5.2w likes  ·  984 comments", R.raw.closer_to_your_heart, R.drawable.closer_to_your_heart_cover),
                    VideoItem("范玮琪开口低音", "@music_clip", "9.3w likes  ·  1,702 comments", R.raw.fan_weiqi_low_note, R.drawable.fan_weiqi_low_note_cover)
                )
            ),
            Channel(
                context.getString(R.string.channel_live),
                listOf(
                    VideoItem("World War 3", "@timeline_notes", "4,321 likes  ·  73 comments", R.raw.world_war_3, R.drawable.world_war_3_cover),
                    VideoItem("安详", "@live_room", "2.1w likes  ·  1,032 comments", R.raw.live_peaceful, R.drawable.live_peaceful_cover),
                    VideoItem("iPhone 手机", "@maker_live", "9,405 likes  ·  642 comments", R.raw.live_iphone, R.drawable.live_iphone_cover),
                    VideoItem("成语接龙", "@game_live", "4.8w likes  ·  3,215 comments", R.raw.live_idiom_game, R.drawable.live_idiom_game_cover)
                ),
                isLiveChannel = true
            ),
            Channel(
                context.getString(R.string.channel_following),
                listOf(
                    VideoItem("优雅步伐", "@friend_video", "3,502 likes  ·  66 comments", R.raw.elegant_steps, R.drawable.elegant_steps_cover),
                    VideoItem("烈日永照", "@study_daily", "2,908 likes  ·  45 comments", R.raw.endless_sun, R.drawable.endless_sun_cover),
                    VideoItem("草原烂麦", "@code_diary", "5,601 likes  ·  118 comments", R.raw.grassland_wheat, R.drawable.grassland_wheat_cover)
                )
            ),
            Channel(
                context.getString(R.string.channel_local),
                listOf(
                    VideoItem("黄龙江一带全部带蓝牙", "@city_walk", "1.2w likes  ·  278 comments", R.raw.yellow_dragon_bluetooth, R.drawable.yellow_dragon_bluetooth_cover),
                    VideoItem("怎样你们才能放过我", "@local_food", "2.7w likes  ·  604 comments", R.raw.let_me_go, R.drawable.let_me_go_cover),
                    VideoItem("旋转几轮变成我们深刻的指纹", "@nearby_view", "7,889 likes  ·  139 comments", R.raw.fingerprint_spin, R.drawable.fingerprint_spin_cover)
                )
            ),
            Channel(
                context.getString(R.string.channel_recommended),
                listOf(
                    VideoItem("今日穿搭灵感", "@style_daily", "3.2w likes  ·  910 comments", R.raw.outfit, R.drawable.outfit_cover),
                    VideoItem("白鲸吐泡泡", "@ocean_diary", "6.8w likes  ·  1,508 comments", R.raw.beluga_bubbles, R.drawable.beluga_bubbles_cover),
                    VideoItem("水母慢慢游", "@jellyfish_view", "9.9w likes  ·  2,345 comments", R.raw.jellyfish, R.drawable.jellyfish_cover)
                )
            )
        )
    }

    fun createLiveChannel(context: Context): Channel {
        return createChannels(context).first { it.isLiveChannel }
    }
}
