package com.yaniv.dailyspendoverlay.overlay

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/**
 * שורה שגולשת לשורה הבאה כשאין מקום (כמו מילים בפסקה) – לשטרות ולמטבעות שבבועה.
 * בלי AndroidX/Flexbox. מכבדת ימין-לשמאל, ומיישרת כל פריט לאמצע הגובה של השורה שלו.
 */
class FlowRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    private val gap = (8 * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowHeight = 0
        var widest = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val w = child.measuredWidth
            if (x > 0 && x + w > maxWidth) {
                y += rowHeight + gap
                x = 0
                rowHeight = 0
            }
            x += w + gap
            rowHeight = max(rowHeight, child.measuredHeight)
            widest = max(widest, x - gap)
        }
        val height = y + rowHeight + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(widest + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(height, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val maxWidth = width - paddingLeft - paddingRight
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        // שורות: אילו ילדים בכל שורה, וגובה כל שורה
        val rows = mutableListOf<MutableList<View>>()
        var current = mutableListOf<View>()
        var x = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val w = child.measuredWidth
            if (x > 0 && x + w > maxWidth) {
                rows += current
                current = mutableListOf()
                x = 0
            }
            current += child
            x += w + gap
        }
        if (current.isNotEmpty()) rows += current

        var top = paddingTop
        for (row in rows) {
            val rowHeight = row.maxOf { it.measuredHeight }
            var offset = 0
            for (child in row) {
                val w = child.measuredWidth
                val h = child.measuredHeight
                val left = if (rtl) width - paddingRight - offset - w else paddingLeft + offset
                val childTop = top + (rowHeight - h) / 2
                child.layout(left, childTop, left + w, childTop + h)
                offset += w + gap
            }
            top += rowHeight + gap
        }
    }
}
