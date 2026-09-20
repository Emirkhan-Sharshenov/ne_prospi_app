package com.nesprosi.app.main

import android.content.Context
import android.util.AttributeSet
import androidx.core.widget.NestedScrollView

/**
 * Прокрутка с ограничением по высоте.
 * Нужна нижней панели: без неё содержимое выше экрана просто обрезалось и кнопки нельзя было нажать.
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : NestedScrollView(context, attrs) {

    var maxHeightPx = 0
        set(value) {
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = if (maxHeightPx > 0) {
            MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
        } else {
            heightMeasureSpec
        }
        super.onMeasure(widthMeasureSpec, spec)
    }
}
