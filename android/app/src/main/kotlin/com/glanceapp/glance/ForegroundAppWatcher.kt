package com.glanceapp.glance

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * ForegroundAppWatcher — per-app trigger for Standard mode.
 *
 * Maximum mode learns the foreground app from Accessibility window
 * events. Standard mode has no Accessibility service, so it polls
 * UsageStats instead (requires the user to grant "Usage access" for
 * Glance in system settings; without it the watcher stays idle).
 *
 * Polling runs at 1 Hz and only while per-app protection is enabled and
 * the protected list is non-empty. Each tick it looks at the last
 * MOVE_TO_FOREGROUND event and feeds the package to
 * [PrivacyDisplayTriggers.updateForegroundApp].
 */
class ForegroundAppWatcher(private val context: Context) {

    companion object {
        private const val TAG = "ForegroundAppWatcher"
        private const val POLL_MS = 1000L
        private const val LOOKBACK_MS = 15000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                poll()
            } catch (e: Exception) {
                Log.d(TAG, "poll failed: ${e.message}")
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(tick)
        Log.d(TAG, "Watcher started")
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    fun hasUsageAccess(): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    private fun poll() {
        val config = PrivacyDisplayPrefs.read(context)
        if (!config.perAppEnabled || config.protectedPackages.isEmpty()) {
            PrivacyDisplayState.protectedAppInForeground = false
            return
        }
        if (!hasUsageAccess()) return

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - LOOKBACK_MS, now)
        val event = UsageEvents.Event()
        var lastForeground: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            ) {
                lastForeground = event.packageName
            }
        }
        PrivacyDisplayTriggers.updateForegroundApp(context, lastForeground)
    }
}
