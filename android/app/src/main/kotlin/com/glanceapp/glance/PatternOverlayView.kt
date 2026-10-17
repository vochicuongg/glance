package com.glanceapp.glance

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * PatternOverlayView — "Pixel Pattern" rendering for the privacy shield.
 *
 * Instead of ONLY a uniform black fill, this view paints two things:
 *
 *   1. BASE FILL — a uniform black layer at [baseAlpha], driven by the
 *      overlay engine exactly like the plain solid layer it accompanies.
 *   2. STRIPE GRILLE — a fine vertical stripe pattern on top of the base,
 *      whose density scales with the shield [intensity] from the sensor
 *      engine. A high-frequency pattern degrades off-axis legibility
 *      (moiré/aliasing against the display's own pixel grid) more than it
 *      degrades the straight-on view, which is the closest a software
 *      overlay can get to the spirit of Samsung's narrow/wide pixel
 *      switching.
 *
 * The pattern therefore ADDS to the solid shield instead of replacing
 * it. (Before this change, enabling Pixel Pattern swapped the whole
 * shield for a grille-only layer with transparent gaps, so "Maximum +
 * Pattern" was visibly weaker than Maximum alone — the pattern never
 * actually combined with the screen overlay.)
 *
 * Stripe geometry:
 *   pitch (stripe period) is fixed at 6 physical px.
 *   coverage (black fraction of each period) maps intensity 0..1 to
 *   0.15..0.65, so the pattern is a light mesh at low intensity and a
 *   dense grille near full shield.
 */
class PatternOverlayView(context: Context) : View(context) {

    private val basePaint = Paint().apply {
        color = android.graphics.Color.BLACK
        style = Paint.Style.FILL
    }

    private val stripePaint = Paint().apply {
        color = android.graphics.Color.BLACK
        style = Paint.Style.FILL
    }

    @Volatile
    private var intensity: Float = 0f

    @Volatile
    private var baseAlpha: Int = 0

    /** Called by the overlay engine with a 0..1 shield intensity. */
    fun setIntensity(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        if (clamped != intensity) {
            intensity = clamped
            postInvalidate()
        }
    }

    /**
     * Uniform black base fill (0..255), driven with the same alpha the
     * engine would give a plain solid layer. Combined with the stripes
     * this makes the pattern layer exactly as dark as a solid layer —
     * plus the grille on top.
     */
    fun setBaseAlpha(value: Int) {
        val clamped = value.coerceIn(0, 255)
        if (clamped != baseAlpha) {
            baseAlpha = clamped
            postInvalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // 1. Base fill — the solid shield component of this layer.
        if (baseAlpha > 0) {
            basePaint.alpha = baseAlpha
            canvas.drawRect(0f, 0f, w, h, basePaint)
        }

        // 2. Stripe grille — the pattern component.
        if (intensity <= 0f) return
        val pitch = 6f
        val coverage = 0.15f + intensity * 0.50f
        val stripeWidth = pitch * coverage
        stripePaint.alpha = (140 + intensity * 115).toInt().coerceIn(0, 255)

        var x = 0f
        while (x < w) {
            canvas.drawRect(x, 0f, x + stripeWidth, h, stripePaint)
            x += pitch
        }
    }
}
