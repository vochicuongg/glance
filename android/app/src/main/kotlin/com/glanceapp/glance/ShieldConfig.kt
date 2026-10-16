package com.glanceapp.glance

/**
 * ShieldConfig — SINGLE SOURCE OF TRUTH for shield strength values.
 *
 * Glance 2.0 (Privacy Display release). Before this file existed, the repo
 * carried at least four different alpha numbers across README, Manifest and
 * the two services (204 / 212 / 242 / 250 / 255) and the two modes had
 * effectively drifted apart. Every alpha value in the app must now come
 * from here, and docs must quote these numbers.
 *
 * Why Standard is capped at 204:
 *   Standard mode draws with TYPE_APPLICATION_OVERLAY — an "untrusted"
 *   window. Since Android 12, touches that pass through a combination of
 *   untrusted windows whose combined opacity exceeds ~0.8 are blocked by
 *   the system (untrusted touch blocking). Keeping the single Standard
 *   layer at 204/255 = 0.80 keeps FLAG_NOT_TOUCHABLE pass-through safe.
 *
 * Why Maximum is 197 per layer:
 *   Maximum mode draws TWO layers (Dual Shield) of
 *   TYPE_ACCESSIBILITY_OVERLAY — a "trusted" window, exempt from the
 *   untrusted-touch cap. Two layers of alpha `a` compound to an effective
 *   opacity of 1 - (1 - a/255)^2. For a = 197 that is ~0.949, i.e. an
 *   effective alpha of ~242/255 (95%) — the documented Maximum strength.
 */
object ShieldConfig {

    /** Standard mode: single untrusted layer, capped for touch pass-through. */
    const val ALPHA_STANDARD: Int = 204

    /** Maximum mode: alpha of EACH of the two trusted layers. */
    const val ALPHA_MAX_PER_LAYER: Int = 197

    /** Documented effective Maximum opacity after both layers compound. */
    const val ALPHA_MAX_EFFECTIVE: Int = 242

    /**
     * While a protected app is in the foreground, or a password/PIN field
     * is focused, the tilt tolerance is tightened to at most this many
     * degrees so the shield reacts to much smaller deviations — the
     * software analogue of Samsung Privacy Display auto-arming on
     * sensitive content.
     */
    const val TRIGGER_TOLERANCE_DEG: Float = 6.0f

    /** Notification-zone (Partial Screen Privacy) geometry and timing. */
    const val NOTIFICATION_ZONE_FRACTION: Float = 0.16f
    const val NOTIFICATION_ZONE_ALPHA: Int = 235
    const val NOTIFICATION_ZONE_DURATION_MS: Long = 6000L

    /** Broadcast action: a notification arrived / was removed. */
    const val ACTION_NOTIFICATION_SHIELD: String =
        "com.glanceapp.glance.NOTIFICATION_SHIELD"
    const val EXTRA_NOTIFICATION_ACTIVE: String = "notification_active"
}
