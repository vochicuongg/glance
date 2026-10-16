package com.glanceapp.glance

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * PatternOverlayView — "Pixel Pattern" rendering for the privacy shield.
 *
 * Instead of a uniform black fill, this view draws a fine vertical stripe
 * pattern whose density scales with the shield intensity computed by the
 * sensor engine. A high-frequency pattern degrades off-axis legibility
 * (moiré/aliasing against the display's own pixel grid) more than it
 * degrades the straight-on view, which is the closest a software overlay
 * can get to the spirit of Samsung's narrow/wide pixel switching.
 *
 * The engine drives this view through [setIntensity] from
 * applyAlphaToOverlay(); the view's background stays transparent.
 *
 * Stripe geometry:
 *   pitch (stripe period) is fixed at 6 physical px.
 *   coverage (black fraction of each period) maps intensity 0..1 to
 *   0.15..0.65, so the pattern is a light mesh at low intensity and a
 *   dense grille near full shield.
 */
class PatternOverlayView(context: Context) : View(context) {

    private val stripePaint = Paint().apply {
        color = android.graphics.Color.BLACK
        style = Paint.Style.FILL
    }

    @Volatile
    private var intensity: Float = 0f

    /** Called by the overlay engine with a 0..1 shield intensity. */
    fun setIntensity(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        if (clamped != intensity) {
            intensity = clamped
            postInvalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (intensity <= 0f) return

        val pitch = 6f
        val coverage = 0.15f + intensity * 0.50f
        val stripeWidth = pitch * coverage
        stripePaint.alpha = (140 + intensity * 115).toInt().coerceIn(0, 255)

        var x = 0f
        val height = height.toFloat()
        while (x < width) {
            canvas.drawRect(x, 0f, x + stripeWidth, height, stripePaint)
            x += pitch
        }
    }
}
