package com.nesprosi.app.main

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.nesprosi.app.R

/** Подписи-«таблетки» на карте («Вы · 1,2 км», «Зона будильника 500 м») — рисуются в картинку для маркера. */
object MapPills {
    fun make(context: Context, text: String, dark: Boolean, dotColor: Int? = null): Drawable {
        val view = View.inflate(context, R.layout.map_pill, null) as TextView
        view.text = text
        val bg = ContextCompat.getColor(context, if (dark) R.color.navy else R.color.card)
        val fg = if (dark) 0xFFFFFFFF.toInt() else ContextCompat.getColor(context, R.color.text)
        view.background.mutate().setTint(bg)
        view.setTextColor(fg)
        if (dotColor != null) {
            val dot = ContextCompat.getDrawable(context, R.drawable.dot)!!.mutate()
            dot.setTint(dotColor)
            view.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
        }
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(spec, spec)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val bitmap = Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return BitmapDrawable(context.resources, bitmap)
    }
}
