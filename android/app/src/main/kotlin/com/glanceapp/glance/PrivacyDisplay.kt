package com.glanceapp.glance

import android.content.Context

/**
 * PrivacyDisplay — shared state + settings for the Glance 2.0
 * "Privacy Display" feature set, modelled on Samsung's Privacy Display
 * (Galaxy S26 Ultra, Flex Magic Pixel hardware):
 *
 *   Samsung (hardware)                Glance 2.0 (software approximation)
 *   ──────────────────────────────    ────────────────────────────────────
 *   Whole-screen privacy              Tilt shield (existing engine)
 *   Per-app activation                Foreground-app trigger: tightens
 *                                     tilt tolerance inside chosen apps
 *   PIN / password auto-activation    Credential guard: Accessibility
 *                                     checks ONLY the isPassword flag of
 *                                     the focused field (never content)
 *   Notification pop-up privacy       Notification zone: a shield strip
 *                                     over the top of the screen while a
 *                                     notification is being displayed
 *   Maximum Privacy Protection        Dual-layer trusted overlay at
 *                                     effective alpha 242 + pattern mode
 *   Narrow/wide pixel switching       Pixel Pattern overlay: fine stripe
 *                                     pattern degrading off-axis legibility
 *
 * HONEST LIMIT: Samsung's feature steers light at the pixel level, so the
 * owner looking straight on keeps a fully clear screen while side viewers
 * see darkness. No Android API lets a third-party app steer light. Glance
 * approximates the *behaviour* (when/where privacy engages) with overlays;
 * it cannot reproduce the optical effect itself.
 *
 * Settings are written by the Flutter UI into FlutterSharedPreferences
 * (the same pattern the codebase already uses for protection_mode and
 * auto_calibrate) and read here on the native side.
 *
 * NOTE on the package list format: Flutter's shared_preferences does not
 * store string lists as a native Android StringSet, so the Dart side
 * mirrors the protected-package list as one comma-separated string under
 * [KEY_PROTECTED_PACKAGES_CSV]. That CSV is the native source of truth.
 */
object PrivacyDisplayState {

    /** A user-selected protected app is currently in the foreground. */
    @Volatile
    var protectedAppInForeground: Boolean = false

    /** A password/PIN field is currently focused (Maximum/accessibility). */
    @Volatile
    var credentialFieldFocused: Boolean = false

    /** True while any trigger wants tightened tolerance. */
    val triggerActive: Boolean
        get() = protectedAppInForeground || credentialFieldFocused
}

data class PrivacyDisplayConfig(
    val perAppEnabled: Boolean,
    val protectedPackages: Set<String>,
    val protectNotifications: Boolean,
    val credentialGuard: Boolean,
    val renderPattern: Boolean,
)

object PrivacyDisplayPrefs {

    private const val FLUTTER_PREFS = "FlutterSharedPreferences"
    const val KEY_PER_APP_ENABLED = "flutter.per_app_enabled"
    const val KEY_PROTECTED_PACKAGES_CSV = "flutter.protected_packages_csv"
    const val KEY_PROTECT_NOTIFICATIONS = "flutter.protect_notifications"
    const val KEY_CREDENTIAL_GUARD = "flutter.credential_guard"
    const val KEY_RENDER_PATTERN = "flutter.render_pattern"

    fun read(context: Context): PrivacyDisplayConfig {
        val prefs = context.getSharedPreferences(FLUTTER_PREFS, Context.MODE_PRIVATE)
        val csv = prefs.getString(KEY_PROTECTED_PACKAGES_CSV, "") ?: ""
        val packages = csv.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        return PrivacyDisplayConfig(
            perAppEnabled = prefs.getBoolean(KEY_PER_APP_ENABLED, false),
            protectedPackages = packages,
            protectNotifications = prefs.getBoolean(KEY_PROTECT_NOTIFICATIONS, false),
            credentialGuard = prefs.getBoolean(KEY_CREDENTIAL_GUARD, false),
            renderPattern = prefs.getBoolean(KEY_RENDER_PATTERN, false),
        )
    }
}

object PrivacyDisplayTriggers {

    /**
     * Updates [PrivacyDisplayState.protectedAppInForeground] from a
     * foreground-package observation (Accessibility window events in
     * Maximum mode, UsageStats polling in Standard mode).
     */
    fun updateForegroundApp(context: Context, packageName: String?) {
        if (packageName == null) return
        if (packageName == context.packageName) {
            // Glance's own UI never counts as a protected app.
            PrivacyDisplayState.protectedAppInForeground = false
            return
        }
        val config = PrivacyDisplayPrefs.read(context)
        PrivacyDisplayState.protectedAppInForeground =
            config.perAppEnabled && config.protectedPackages.contains(packageName)
    }
}
