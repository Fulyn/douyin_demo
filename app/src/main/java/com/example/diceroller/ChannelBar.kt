package com.example.diceroller

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class ChannelBar(
    rootView: View,
    channels: List<Channel>,
    selectedIndex: Int,
    private val channelPagerView: ViewPager2
) {

    private val scrollView: HorizontalScrollView = rootView.findViewById(R.id.channelScrollView)
    private val rowForText: LinearLayout = rootView.findViewById(R.id.rowForText)
    private val indicator: View = rootView.findViewById(R.id.channelIndicator)
    private val channelTextViews = mutableListOf<TextView>()

    init {
        bind(channels, selectedIndex)
    }

    private fun bind(channels: List<Channel>, selectedIndex: Int) {
        rowForText.removeAllViews()
        channelTextViews.clear()

        channels.forEachIndexed { index, channel ->
            val channelTextView = TextView(rowForText.context).apply {
                text = channel.title
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(if (index == selectedIndex) SELECTED_TEXT_COLOR else NORMAL_TEXT_COLOR)
                setPadding(dp(14), 0, dp(14), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setOnClickListener {
                    channelPagerView.setCurrentItem(index, false)
                }
            }

            channelTextViews.add(channelTextView)
            rowForText.addView(channelTextView)
        }

        rowForText.post {
            update(selectedIndex, 0f)
        }
    }

    fun update(position: Int, positionOffset: Float) {
        if (channelTextViews.isEmpty()) return

        val startIndex = position.coerceIn(0, channelTextViews.lastIndex)
        val endIndex = (startIndex + 1).coerceAtMost(channelTextViews.lastIndex)
        val nearestIndex = if (positionOffset >= 0.5f) endIndex else startIndex

        channelTextViews.forEachIndexed { index, textView ->
            textView.setTextColor(if (index == nearestIndex) SELECTED_TEXT_COLOR else NORMAL_TEXT_COLOR)
        }

        updateIndicatorPosition(startIndex, endIndex, positionOffset)
    }

    private fun updateIndicatorPosition(startIndex: Int, endIndex: Int, positionOffset: Float) {
        val startTextView = channelTextViews[startIndex]
        val endTextView = channelTextViews[endIndex]

        if (startTextView.width == 0 || indicator.height == 0) return

        val safeOffset = if (startIndex == endIndex) 0f else positionOffset
        val startCenterX = startTextView.left + startTextView.width / 2f
        val endCenterX = endTextView.left + endTextView.width / 2f
        val indicatorCenterX = lerp(startCenterX, endCenterX, safeOffset)
        val indicatorWidth = lerp(
            indicatorWidthFor(startTextView),
            indicatorWidthFor(endTextView),
            safeOffset
        )

        indicator.layoutParams = indicator.layoutParams.apply {
            width = indicatorWidth.roundToInt()
        }
        indicator.translationX = indicatorCenterX - indicatorWidth / 2f
        indicator.alpha = (abs(safeOffset - 0.5f) * 2f).coerceIn(0f, 1f)
        scrollView.scrollTo(scrollXForCenteredIndicator(indicatorCenterX), 0)
    }

    private fun scrollXForCenteredIndicator(indicatorCenterX: Float): Int {
        val viewportWidth = scrollView.width - scrollView.paddingStart - scrollView.paddingEnd
        val maxScrollX = max(0, rowForText.width - viewportWidth)
        val desiredScrollX = indicatorCenterX - viewportWidth / 2f

        return desiredScrollX.roundToInt().coerceIn(0, maxScrollX)
    }

    private fun indicatorWidthFor(textView: TextView): Float {
        return max(dp(18).toFloat(), textView.paint.measureText(textView.text.toString()) - dp(8))
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float {
        return start + (end - start) * fraction
    }

    private fun dp(value: Int): Int {
        return (value * rowForText.resources.displayMetrics.density).roundToInt()
    }

    private companion object {
        val SELECTED_TEXT_COLOR: Int = Color.WHITE
        val NORMAL_TEXT_COLOR: Int = Color.argb(153, 255, 255, 255)
    }
}
