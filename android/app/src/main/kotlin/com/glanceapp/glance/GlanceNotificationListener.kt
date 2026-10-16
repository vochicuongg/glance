package com.glanceapp.glance

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * GlanceNotificationListener — powers "Notification zone" privacy
 * (Samsung's Partial Screen Privacy analogue).
 *
 * When the user enables notification protection, this listener tells the
 * overlay engines (via an in-app broadcast) that a notification is being
 * displayed, and the engines cover the top strip of the screen — where
 * heads-up notifications appear — for a few seconds.
 *
 * Privacy posture: this listener NEVER reads notification titles, text or
 * extras. It only observes that a notification from some package exists.
 * Notifications posted by Glance itself and group summaries are ignored.
 */
class GlanceNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "GlanceNotifListener"
    }

    private val activeKeys = mutableSetOf<String>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName == packageName) return
        if (sbn.isGroup && sbn.notification.flags and
            android.app.Notification.FLAG_GROUP_SUMMARY != 0
        ) return

        if (!PrivacyDisplayPrefs.read(this).protectNotifications) return

        val wasEmpty = activeKeys.isEmpty()
        activeKeys.add(sbn.key)
        if (wasEmpty) {
            sendShieldBroadcast(active = true)
            Log.d(TAG, "Notification zone ON (package=${sbn.packageName})")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (activeKeys.remove(sbn.key) && activeKeys.isEmpty()) {
            sendShieldBroadcast(active = false)
            Log.d(TAG, "Notification zone OFF")
        }
    }

    private fun sendShieldBroadcast(active: Boolean) {
        sendBroadcast(Intent(ShieldConfig.ACTION_NOTIFICATION_SHIELD).apply {
            setPackage(packageName)
            putExtra(ShieldConfig.EXTRA_NOTIFICATION_ACTIVE, active)
        })
    }
}
