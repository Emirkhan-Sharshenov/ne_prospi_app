package com.nesprosi.app.main

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.nesprosi.app.R

/**
 * Полоса поездки из макета: градиент синий → индиго → янтарный, круглый бегунок с автобусом
 * и янтарная точка — зона будильника в конце.
 */
class TripProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val trackHeight = 10 * density
    private val knobRadius = 18 * density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.surface_high) }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(6 * density, 0f, 2 * density, 0x40000000)
    }
    private val endPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.amber) }
    private val bus = ContextCompat.getDrawable(context, R.drawable.ic_bus)!!.mutate().apply {
        setTint(ContextCompat.getColor(context, R.color.accent))
    }
    private val rect = RectF()
    private var shown = 0f
    private var animator: ValueAnimator? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // для тени бегунка
    }

    /** 0…1 — доля пути до будильника. */
    fun setProgress(value: Float, animate: Boolean = true) {
        val target = value.coerceIn(0f, 1f)
        animator?.cancel()
        if (!animate) {
            shown = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(shown, target).apply {
            duration = 600
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), (knobRadius * 2 + 8 * density).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fillPaint.shader = LinearGradient(
            knobRadius, 0f, w - knobRadius, 0f,
            intArrayOf(
                ContextCompat.getColor(context, R.color.accent),
                ContextCompat.getColor(context, R.color.secondary),
                ContextCompat.getColor(context, R.color.amber),
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val left = knobRadius
        val right = width - knobRadius
        rect.set(left - trackHeight / 2, cy - trackHeight / 2, right + trackHeight / 2, cy + trackHeight / 2)
        canvas.drawRoundRect(rect, trackHeight, trackHeight, trackPaint)

        val x = left + (right - left) * shown
        rect.set(left - trackHeight / 2, cy - trackHeight / 2, x, cy + trackHeight / 2)
        canvas.drawRoundRect(rect, trackHeight, trackHeight, fillPaint)

        // точка зоны будильника в конце
        canvas.drawCircle(right, cy, 6 * density, endPaint)

        // бегунок с автобусом
        canvas.drawCircle(x, cy, knobRadius, knobPaint)
        val s = (11 * density).toInt()
        bus.setBounds((x - s).toInt(), (cy - s).toInt(), (x + s).toInt(), (cy + s).toInt())
        bus.draw(canvas)
    }
}
