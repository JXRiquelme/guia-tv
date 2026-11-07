package cl.jxr.aeriolite

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View

/** Vertical glass pill slider (brightness / volume), filled from the bottom. Value 0..1. */
class VerticalSlider(context: Context, private val icon: Drawable) : View(context) {
    var value = 0.5f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var onChange: ((Float) -> Unit)? = null
    var onTouchState: ((Boolean) -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFFFFF.toInt() }
    private val rect = RectF()
    private var startY = 0f
    private var startValue = 0f

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = w / 2f
        val top = h - h * value
        canvas.save()
        rect.set(0f, 0f, w, h)
        val clip = android.graphics.Path().apply { addRoundRect(rect, r, r, android.graphics.Path.Direction.CW) }
        canvas.clipPath(clip)
        rect.set(0f, top, w, h)
        canvas.drawRect(rect, fill)
        canvas.restore()
        // icon near the bottom: dark over the white fill, white otherwise
        val s = (w * 0.46f).toInt()
        val l = ((w - s) / 2).toInt()
        val t = (h - w / 2 - s / 2).toInt()
        icon.setBounds(l, t, l + s, t + s)
        icon.setTint(if (top < t + s / 2) 0xFF1C1E24.toInt() else Color.WHITE)
        icon.draw(canvas)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                startY = e.y
                startValue = value
                onTouchState?.invoke(true)
            }
            MotionEvent.ACTION_MOVE -> {
                // relative drag (like iOS / ColorOS): no jump where the finger lands
                value = startValue + (startY - e.y) / height
                onChange?.invoke(value)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onTouchState?.invoke(false)
        }
        return true
    }
}
