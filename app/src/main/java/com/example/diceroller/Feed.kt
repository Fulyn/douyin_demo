package com.example.diceroller

import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET

// ============================ 数据模型 ============================
// 直接作为 Gson 的反序列化目标：字段名与 videos.json 完全对应。
// 这一版资源全部网络化，VideoItem 不再持有 res id，只有 url。

data class VideoFeed(
    val channels: List<Channel> = emptyList()
)

data class Channel(
    val title: String,
    val videoItems: List<VideoItem> = emptyList(),
    val isLiveChannel: Boolean = false
)

data class VideoItem(
    val title: String,
    val author: String,
    val stats: String,
    val videoUrl: String,
    val coverUrl: String
)

// ============================ Retrofit 接口 ============================
// 第一阶段只有一个接口：拉取频道列表（videos.json）。

interface FeedApi {
    @GET("videos.json")
    fun getFeed(): Call<VideoFeed>
}

// ============================ 数据入口 ============================

/**
 * 第一阶段的数据入口：用 Retrofit 拉 videos.json（频道 feed），结果缓存在内存里。
 *
 * 这一版刻意不引入 ViewModel / 本地存储 / 协程：Retrofit 的 enqueue 在 Android 上
 * 默认就把回调切回主线程，所以调用方拿到 onSuccess 时可直接操作 UI。
 *
 * 缓存的意义：MainActivity 拉一次后，LiveRoomActivity 能直接复用同一份频道，
 * 不必再发一次请求，也省去把 Channel 做成 Parcelable 跨 Activity 传递。
 */
object FeedRepository {

    // adb reverse tcp:8080 tcp:8080 后，手机的 127.0.0.1:8080 即电脑的本地静态服务器。
    private const val BASE_URL = "http://127.0.0.1:8080/"

    private val api: FeedApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(FeedApi::class.java)

    private var cachedChannels: List<Channel> = emptyList()

    fun loadChannels(
        onSuccess: (List<Channel>) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        api.getFeed().enqueue(object : Callback<VideoFeed> {
            override fun onResponse(call: Call<VideoFeed>, response: Response<VideoFeed>) {
                val channels = response.body()?.channels
                if (response.isSuccessful && channels != null) {
                    cachedChannels = channels
                    onSuccess(channels)
                } else {
                    onError(IllegalStateException("加载频道失败：HTTP ${response.code()}"))
                }
            }

            override fun onFailure(call: Call<VideoFeed>, t: Throwable) {
                onError(t)
            }
        })
    }

    // 供 LiveRoomActivity 读取已缓存的直播频道；未加载或无直播频道时返回 null。
    fun liveChannel(): Channel? = cachedChannels.firstOrNull { it.isLiveChannel }
}
