package com.glanceapp.glance

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * NotificationZone — the Partial Screen Privacy strip.
 *
 * A black strip covering the top [ShieldConfig.NOTIFICATION_ZONE_FRACTION]
 * of the screen, where heads-up notification banners appear. Shown by an
 * overlay engine when [GlanceNotificationListener] reports a notification
 * and hidden again after [ShieldConfig.NOTIFICATION_ZONE_DURATION_MS] or
 * when the notification is removed, whichever the engine's broadcasts say.
 *
 * The window type is supplied by the owning engine
 * (TYPE_APPLICATION_OVERLAY for Standard, TYPE_ACCESSIBILITY_OVERLAY for
 * Maximum) so the strip inherits the same trust level as the main shield.
 */
class NotificationZone(
    private val context: Context,
    private val windowType: Int,
) {

    companion object {
        private const val TAG = "NotificationZone"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var zoneView: View? = null

    private val hideRunnable = Runnable { hide() }

    fun show() {
        handler.post {
            if (zoneView != null) {
                scheduleHide()
                return@post
            }
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val heightPx = (context.resources.displayMetrics.heightPixels *
                ShieldConfig.NOTIFICATION_ZONE_FRACTION).toInt()
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                heightPx,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }
            val view = View(context).apply {
                setBackgroundColor(
                    android.graphics.Color.argb(ShieldConfig.NOTIFICATION_ZONE_ALPHA, 0, 0, 0)
                )
            }
            try {
                wm.addView(view, params)
                zoneView = view
                scheduleHide()
                Log.d(TAG, "Zone shown (height=${heightPx}px)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show zone: ${e.message}")
            }
        }
    }

    fun hide() {
        handler.post {
            handler.removeCallbacks(hideRunnable)
            val view = zoneView ?: return@post
            zoneView = null
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(view)
            } catch (_: Exception) {
            }
            Log.d(TAG, "Zone hidden")
        }
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, ShieldConfig.NOTIFICATION_ZONE_DURATION_MS)
    }
}
