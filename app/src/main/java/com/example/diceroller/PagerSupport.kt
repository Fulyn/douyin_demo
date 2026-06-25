package com.example.diceroller

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import kotlin.math.roundToInt

// 频道/视频/直播三个分页器共用的小工具：循环页机制、dp 换算、统一样式的文本控件。

// 用一大批虚拟页营造"无限循环滑动"的效果；realItemCount <= 1 时无需循环。
const val LOOP_ITEM_COUNT = 10_000

fun loopStartPosition(realItemCount: Int, selectedItemIndex: Int = 0): Int {
    if (realItemCount <= 1) return 0

    val middle = LOOP_ITEM_COUNT / 2
    val loopStart = middle - middle % realItemCount
    return loopStart + selectedItemIndex.coerceIn(0, realItemCount - 1)
}

fun View.dp(value: Int): Int {
    return (value * resources.displayMetrics.density).roundToInt()
}

fun createTextView(
    context: Context,
    sizeSp: Float,
    color: String,
    isBold: Boolean = false
): TextView {
    return TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor(color))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (isBold) {
            typeface = Typeface.DEFAULT_BOLD
        }
    }
}
