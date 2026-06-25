package com.example.diceroller

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

@UnstableApi
class LivePager(
    internal val pager: ViewPager2,
    private val videoItems: List<VideoItem>,
    private val showEnterButton: Boolean,
    private val selectedItemIndex: Int = 0,
    private val onEnterLiveRoom: (startPosition: Int) -> Unit = {}
) {

    private val adapter = LivePagerAdapter()
    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            playCurrentVideo()
        }
    }

    init {
        pager.orientation = ViewPager2.ORIENTATION_VERTICAL
        pager.offscreenPageLimit = 1
        pager.overScrollMode = View.OVER_SCROLL_NEVER
        pager.adapter = adapter
        pager.setCurrentItem(loopStartPosition(videoItems.size, selectedItemIndex), false)
        pager.registerOnPageChangeCallback(pageChangeCallback)
    }

    fun currentVideo(): VideoInterface? = adapter.boundHolders[pager.currentItem]

    // 播放当前视频，并把"播完平滑滑到下一条"绑定到自己身上
    // （循环页足够多，currentItem + 1 始终有效）。
    fun playCurrentVideo() {
        pager.post {
            VideoController.shared.onVideoCompleted = { pager.setCurrentItem(pager.currentItem + 1, true) }
            VideoController.shared.play(currentVideo())
        }
    }

    fun release() {
        pager.unregisterOnPageChangeCallback(pageChangeCallback)
        // 共享播放器至多挂在"当前播放页"这一页上，只解绑它即可；其余对象随 adapter = null 交给 GC。
        currentVideo()?.let { VideoController.shared.detachFrom(it) }
        pager.adapter = null
    }

    // adapter / holder 设为 inner：视频列表、是否显示进入按钮、进直播间回调都直接读外层，
    // 不必再当成构造参数层层传递。
    private inner class LivePagerAdapter : RecyclerView.Adapter<LiveViewHolder>() {

        val boundHolders = mutableMapOf<Int, LiveViewHolder>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LiveViewHolder {
            return LiveViewHolder(parent)
        }

        override fun onBindViewHolder(holder: LiveViewHolder, position: Int) {
            boundHolders.entries.removeAll { it.value == holder }
            boundHolders[position] = holder
            val realIndex = position % videoItems.size
            holder.bind(videoItems[realIndex], realIndex)
        }

        override fun getItemCount(): Int {
            return if (videoItems.size <= 1) videoItems.size else LOOP_ITEM_COUNT
        }

        override fun onViewRecycled(holder: LiveViewHolder) {
            val position = boundHolders.entries.find { it.value == holder }?.key ?: return
            boundHolders.remove(position)
            VideoController.shared.detachFrom(holder)
            holder.recycleUi()
        }
    }

    private inner class LiveViewHolder(
        parent: ViewGroup
    ) : RecyclerView.ViewHolder(FrameLayout(parent.context)), VideoInterface {

        private val rootView = itemView as FrameLayout

        override val playerView = (View.inflate(parent.context, R.layout.view_video_player, null) as PlayerView).apply {
            setShutterBackgroundColor(Color.TRANSPARENT)
            setKeepContentOnPlayerReset(false)
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
        private val coverImageView = ImageView(parent.context).apply {
            setBackgroundResource(R.color.video_background)
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
        private val titleText = createTextView(parent.context, 21f, "#FFFFFFFF", isBold = true).apply {
            gravity = Gravity.START
        }
        private val metaText = createTextView(parent.context, 14f, "#D9FFFFFF").apply {
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = parent.dp(8)
            }
        }
        private val enterButton = TextView(parent.context).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            text = "点击进入直播间"
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = parent.dp(22).toFloat()
                setColor(Color.parseColor("#1AFFFFFF"))
                setStroke(parent.dp(1), Color.WHITE)
            }
            setPadding(parent.dp(24), 0, parent.dp(24), 0)
            layoutParams = FrameLayout.LayoutParams(
                WRAP_CONTENT,
                parent.dp(44),
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = parent.dp(204)
            }
        }

        override var videoItem: VideoItem? = null
            private set
        override val isDraggingProgress: Boolean = false
        private var itemIndex = 0

        init {
            rootView.setBackgroundResource(R.color.video_background)
            rootView.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)

            val bottomLayout = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(parent.dp(20), 0, parent.dp(96), parent.dp(34))
                layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM)
                addView(titleText)
                addView(metaText)
            }

            rootView.addView(playerView)
            rootView.addView(coverImageView)
            rootView.addView(bottomLayout)
            rootView.addView(enterButton)

            enterButton.setOnClickListener { onEnterLiveRoom(itemIndex) }
        }

        // ---------------- 数据绑定 ----------------

        fun bind(videoItem: VideoItem, position: Int) {
            val cover = VideoController.shared.coverFor(videoItem)

            this.videoItem = videoItem
            itemIndex = position
            enterButton.visibility = if (showEnterButton) View.VISIBLE else View.GONE
            titleText.visibility = if (showEnterButton) View.VISIBLE else View.GONE
            metaText.visibility = if (showEnterButton) View.VISIBLE else View.GONE

            // 被绑定的页一定不是当前播放页（当前页不会被回收重绑），所以直接摘掉播放器、盖上封面占位。
            playerView.player = null
            setCover(cover, showCover = cover != null)

            titleText.text = videoItem.title
            metaText.text = itemView.context.getString(R.string.video_meta, videoItem.author, videoItem.stats)
        }

        // ---------------- 封面 ----------------

        override fun setCover(bitmap: Bitmap?, showCover: Boolean) {
            coverImageView.setImageBitmap(bitmap)
            coverImageView.visibility = if (showCover) View.VISIBLE else View.GONE
        }

        // 仅隐藏、保留位图：seek/重渲染会反复触发，之后可能还要原图复现。
        override fun hideCover() {
            coverImageView.visibility = View.GONE
        }

        // 回收时彻底清掉位图引用，等价于 setCover(null, 隐藏)。
        override fun clearCover() = setCover(bitmap = null, showCover = false)

        // ---------------- 直播页无播放控件，以下接口留空 ----------------

        override fun setPausedUiVisible(visible: Boolean) = Unit

        override fun updateProgressUi(progress: Float) = Unit

        override fun resetControlUi() = Unit
    }
}
