package com.glanceapp.glance

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.service.quicksettings.TileService
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * MaxOverlayService — Accessibility-based Privacy Shield (Maximum Mode)
 *
 * Extends [AccessibilityService] to obtain TYPE_ACCESSIBILITY_OVERLAY
 * privilege. This bypasses Android 12+'s "Untrusted Touch Blocker",
 * allowing alpha up to 242 (95%) with full touch pass-through.
 *
 * ⚠️ WARNING: Banking apps may refuse to run while this service is enabled.
 *
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  DUAL-ENGINE ARCHITECTURE — MAX ENGINE                              │
 * │                                                                    │
 * │  • Uses TYPE_ACCESSIBILITY_OVERLAY (trusted window)                 │
 * │  • Alpha capped at 242 (95%) — pitch black, smooth                 │
 * │  • Requires Accessibility + Overlay permissions                     │
 * │  • FLAG_NOT_TOUCHABLE for full touch pass-through                   │
 * │  • No foreground notification required (system-managed lifecycle)   │
 * └──────────────────────────────────────────────────────────────────────┘
 */
class MaxOverlayService : AccessibilityService(), SensorEventListener {

    // ── Angle normalization utility ────────────────────────────────────────
    // Ensures any angle difference stays within [-180, 180] range,
    // preventing cumulative drift past ±360°.
    private fun normalizeAngle(angle: Float): Float {
        var a = angle % 360f
        if (a > 180f) a -= 360f
        if (a < -180f) a += 360f
        return a
    }

    // ── Samsung-Inspired Smoothstep (Hermite S-Curve) ─────────────────────
    // Creates a smooth sigmoid-like transition between edge0 and edge1.
    // Output: 0.0 at edge0, 1.0 at edge1, smooth acceleration/deceleration.
    // Formula: 3t² - 2t³ (cubic Hermite interpolation)
    // Superior to linear ramp: overlay appears gently then accelerates,
    // mimicking Samsung Privacy Display's natural opacity curve.
    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // ── Adaptive LPF Alpha computation ────────────────────────────────────
    // Computes optimal LPF alpha based on sensor change magnitude.
    // Large delta (deliberate tilt) → light filtering (responsive)
    // Small delta (tremor/vibration) → heavy filtering (smooth)
    private fun computeAdaptiveLpfAlpha(dx: Float, dy: Float, dz: Float): Float {
        val delta = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
        // Blend between HEAVY and LIGHT based on delta magnitude
        val blend = (delta / LPF_ADAPT_THRESHOLD).coerceIn(0f, 1f)
        return LPF_ALPHA_HEAVY + blend * (LPF_ALPHA_LIGHT - LPF_ALPHA_HEAVY)
    }

    // ── Sensitivity → Deviation Ramp Width mapping ────────────────────────
    // sensorSensitivity (0.0 = least sensitive → 1.0 = most sensitive)
    // Maps to deviation ramp width: wider ramp = more gradual opacity change
    // Low sensitivity (0.0) → 15° ramp (very gradual, tolerant)
    // High sensitivity (1.0) → 4° ramp (snappy, quick darkening)
    private fun computeDeviationRampWidth(): Float {
        return 15f - (sensorSensitivity * 11f)  // Range: 4° to 15°
    }

    // ── Sensitivity → Hysteresis Band Width mapping ───────────────────────
    // Wider band = more stable (less flicker), narrower = more responsive
    // Low sensitivity → 4° band, High sensitivity → 1.5° band
    private fun computeHysteresisBand(): Float {
        return 4.0f - (sensorSensitivity * 2.5f)  // Range: 1.5° to 4°
    }

    companion object {
        private const val TAG = "MaxOverlayService"

        @Volatile
        @JvmStatic
        var isRunning: Boolean = false
            private set

        @JvmStatic
        var isCalibrated: Boolean = false

        @JvmStatic
        var sensorEventSink: io.flutter.plugin.common.EventChannel.EventSink? = null

        // ── Action constants (shared across both engines) ─────────────────
        const val ACTION_UPDATE_CONFIG = "com.glanceapp.glance.UPDATE_CONFIG"
        const val ACTION_STOP_SERVICE = "com.glanceapp.glance.STOP_SERVICE"
        const val ACTION_RESUME_SERVICE = "com.glanceapp.glance.RESUME_SERVICE"
        const val ACTION_CALIBRATE = "com.glanceapp.glance.CALIBRATE"
        const val ACTION_SET_SENSITIVITY = "com.glanceapp.glance.SET_SENSITIVITY"
        const val ACTION_SET_OVERLAY_MODE = "com.glanceapp.glance.SET_OVERLAY_MODE"
        const val ACTION_SET_TARGETED_AREA = "com.glanceapp.glance.SET_TARGETED_AREA"
        const val ACTION_SET_TOLERANCE = "com.glanceapp.glance.SET_TOLERANCE"

        // Sensor-only mode: start sensor streaming without activating overlay
        const val ACTION_START_SENSOR_ONLY = "com.glanceapp.glance.START_SENSOR_ONLY"

        // Self-destruct: revoke accessibility permission by calling disableSelf()
        const val ACTION_REVOKE_ACCESSIBILITY = "com.glanceapp.glance.REVOKE_ACCESSIBILITY"

        // Dummy constants — kept so MainActivity compiles without changes
        const val ACTION_SET_INTENSITY = "com.glanceapp.glance.SET_INTENSITY"
        const val EXTRA_INTENSITY = "intensity"
        const val EXTRA_NOTIFICATION_TITLE = "notification_title"
        const val EXTRA_NOTIFICATION_TEXT = "notification_text"
        const val EXTRA_AUTO_CALIBRATE = "auto_calibrate"

        const val EXTRA_SENSITIVITY = "sensitivity"
        const val EXTRA_MODE = "mode"
        const val EXTRA_TOLERANCE = "tolerance"
        const val EXTRA_AREA_X = "area_x"
        const val EXTRA_AREA_Y = "area_y"
        const val EXTRA_AREA_WIDTH = "area_width"
        const val EXTRA_AREA_HEIGHT = "area_height"

        private const val PREFS_NAME = "GlanceNativePrefs"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_TOLERANCE = "tolerance"
        private const val KEY_BASELINE_PITCH = "baseline_pitch"
        private const val KEY_BASELINE_ROLL = "baseline_roll"

        // ── Targeted Area Config ──────────────────────────────────────────────
        private const val KEY_OVERLAY_MODE = "overlay_mode"
        private const val KEY_AREA_X = "area_x"
        private const val KEY_AREA_Y = "area_y"
        private const val KEY_AREA_WIDTH = "area_width"
        private const val KEY_AREA_HEIGHT = "area_height"

        // ── Max Alpha for Maximum mode ────────────────────────────────────
        // 203 ~= 80% opacity on AMOLED (-5% from previous 85%).
        // Slightly lighter to reduce visual heaviness while maintaining
        // strong privacy protection.
        private const val MAX_ALPHA = ShieldConfig.ALPHA_MAX_PER_LAYER

        /**
         * Checks whether this AccessibilityService is currently enabled
         * in the device's Accessibility Settings.
         */
        @JvmStatic
        fun isAccessibilityEnabled(context: Context): Boolean {
            val expectedComponentName = ComponentName(context, MaxOverlayService::class.java)
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)
            while (colonSplitter.hasNext()) {
                val componentNameString = colonSplitter.next()
                val enabledService = ComponentName.unflattenFromString(componentNameString)
                if (enabledService != null && enabledService == expectedComponentName) {
                    return true
                }
            }
            return false
        }
    }

    // ── System services ───────────────────────────────────────────────────
    private lateinit var windowManager: WindowManager
    private var sensorManager: SensorManager? = null
    private var rotationSensor: Sensor? = null
    private var powerManager: PowerManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // ── Overlay views ─────────────────────────────────────────────────────
    private val overlayViews: MutableList<View> = mutableListOf()

    // ── Notification zone (Partial Screen Privacy) ────────────────────
    private val notificationZone = NotificationZone(
        this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
    )

    // ── Sensor configuration ──────────────────────────────────────────────
    private var sensorSensitivity: Float = 0.5f
    private var sensorTolerance: Float = 5.0f

    // ── Targeted Area Config (instance vars) ──────────────────────────────
    private var overlayMode: String = "fullscreen"
    private var areaX: Int = 0
    private var areaY: Int = 0
    private var areaWidth: Int = WindowManager.LayoutParams.MATCH_PARENT
    private var areaHeight: Int = WindowManager.LayoutParams.MATCH_PARENT

    private var isOverlayShowing = false
    private var lastSensorStreamTime: Long = 0L

    // ── Baseline calibration state ────────────────────────────────────────
    private var needsBaselineReset: Boolean = true
    private var baselineRoll: Float = 0f
    private var baselinePitch: Float = 0f

    // ── Sensor LPF (Low-Pass Filter) for gravity vector noise suppression ─
    private var filteredGx: Float = 0f
    private var filteredGy: Float = 0f
    private var filteredGz: Float = 0f
    // ── Samsung-Inspired Adaptive LPF ──────────────────────────────────────
    // Instead of a fixed alpha, dynamically adjust between LIGHT (responsive
    // to intentional tilts) and HEAVY (suppresses hand tremor/vibration).
    // The blending factor is based on the magnitude of raw sensor change.
    private val LPF_ALPHA_LIGHT: Float = 0.30f   // Quick response for deliberate tilt
    private val LPF_ALPHA_HEAVY: Float = 0.06f    // Heavy smoothing for tremor/vibration
    private val LPF_ADAPT_THRESHOLD: Float = 0.04f // Sensor delta magnitude to blend between light/heavy
    private var prevRawGx: Float = 0f
    private var prevRawGy: Float = 0f
    private var prevRawGz: Float = 0f

    // ── Samsung-Inspired Hysteresis Dead Zone ──────────────────────────────
    // Prevents flicker at the boundary by using two separate thresholds:
    //   • outerThreshold (enter protection): tolerance + hysteresisBand/2
    //   • innerThreshold (leave protection): tolerance - hysteresisBand/2
    // The shield only activates when deviation crosses OUTER, and only
    // deactivates when it drops below INNER. Band width scales with sensitivity.
    private var isShieldEngaged: Boolean = false   // Hysteresis state flag

    // ── VSYNC-driven interpolation target ─────────────────────────────────
    private var targetAlpha: Float = 0f
    private var isAnimationRunning = false
    private var lastVsyncTime: Long = 0L // Biến Watchdog theo dõi nhịp đập VSYNC

    // ── EMA-smoothed alpha (directly controls overlay opacity) ────────────
    private var currentDisplayedAlpha: Float = 0f

    // ── Overlay Auto-Remove: auto-destroy View when transparent for 2s ────
    private var alphaZeroSince: Long = 0L
    private val OVERLAY_AUTO_REMOVE_DELAY: Long = 2000L

    // ── Fade-to-Clear animation lock ──────────────────────────────────────
    // When true, the ValueAnimator is driving alpha → 0. Main sensor and
    // VSYNC loop must NOT overwrite currentDisplayedAlpha while this is set.
    private var isAnimating: Boolean = false
    private var fadeAnimator: ValueAnimator? = null

    // ══════════════════════════════════════════════════════════════════════
    //  Auto-Calibration (Tự động nội suy góc cầm)
    // ══════════════════════════════════════════════════════════════════════
    private var gravitySensor: Sensor? = null
    private var autoCalibrationEnabled: Boolean = false

    // LPF-smoothed pitch/roll for auto-calibration (separate from main sensor)
    private var acSmoothedPitch: Float = 0f
    private var acSmoothedRoll: Float = 0f
    private val AC_LPF_ALPHA: Float = 0.1f

    // Current overlay baseline angle (the angle the overlay is "locked" to)
    private var acCurrentBaselinePitch: Float = 0f
    private var acCurrentBaselineRoll: Float = 0f
    private var acBaselineInitialized: Boolean = false

    // Stability timer
    private val acHandler: Handler = Handler(Looper.getMainLooper())
    private var acStabilityRunnable: Runnable? = null
    private var acStableTargetPitch: Float = 0f
    private var acStableTargetRoll: Float = 0f

    // -- Posture Adaptation v2 state ------------------------------------
    // Previous smoothed sample + its timestamp (angular-speed estimate),
    // and the settle window: when it opened (0 = closed) plus the
    // candidate posture's orientation at opening (drift guard).
    private var acPrevSmoothedPitch: Float = 0f
    private var acPrevSmoothedRoll: Float = 0f
    private var acLastSampleMs: Long = 0L
    private var acSettleStartMs: Long = 0L
    private var acCandidateStartPitch: Float = 0f
    private var acCandidateStartRoll: Float = 0f
    // Legacy opacity-bucket timer fields (pre-v2). Posture Adaptation v2
    // decides re-baselining inside the AC sensor listener and never
    // starts this timer; the fields remain because the calibrate /
    // stop / resume handlers still reset them.
    // Marked @Volatile for thread-safe access from both sensor and VSYNC threads
    @Volatile
    private var firstDeviationTime: Long = 0L
    
    @Volatile
    private var acTargetDuration: Long = 0L  // Computed by sensor thread, read by VSYNC

    // Thresholds - Posture Adaptation v2
    // The old algorithm gated adaptation on the shield's own opacity
    // (0.5s "fast" bucket under 40% alpha, a full 3s above it) and
    // restarted its timer whenever the held angle drifted >5 degrees
    // from a frozen snapshot - so a posture change first had to blacken
    // the screen, natural sway while lying down could postpone rescue
    // indefinitely, and the |pitch| 10-170 degree gate froze adaptation
    // in exactly the lying postures that need it. v2 asks one question
    // instead: has the device SETTLED (smoothed angular speed ~ 0) in a
    // new orientation, held long enough that it is a posture rather
    // than a gesture?
    private val AC_SETTLE_DURATION_MS: Long = 1000L    // Held-still time that confirms a new posture
    private val AC_SETTLE_SPEED_DEG_S: Float = 10f     // Smoothed angular speed under which the grip counts as settled
    private val AC_CANDIDATE_DRIFT_DEG: Float = 7f     // Max wander inside one settle window before it restarts
    private val AC_POSTURE_MARGIN_DEG: Float = 4f      // Posture threshold = sensorTolerance + margin...
    private val AC_POSTURE_MIN_DEG: Float = 9f         // ...but never below this
    private val AC_GRAVITY_MIN: Float = 6.5f           // m/s^2 - plausibility band for |gravity| (~9.8 when held)
    private val AC_GRAVITY_MAX: Float = 13.5f
    private val AC_ANIMATION_DURATION: Long = 600L         // Smooth transition duration (legacy, kept for reference)
    private val AC_BREATHE_FADEOUT_DURATION: Long = 300L    // Breathing: Fade-out (screen brightens)
    private val AC_BREATHE_FADEIN_DURATION: Long = 500L     // Breathing: Fade-in  (shield returns)

    // SharedPreferences listener for kill switch
    private var acPrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val FLUTTER_PREFS_NAME = "FlutterSharedPreferences"
    private val KEY_AUTO_CALIBRATE = "flutter.auto_calibrate"

    private val vsyncRunnable = object : Runnable {
        override fun run() {
            if (!isOverlayShowing || overlayViews.isEmpty()) {
                isAnimationRunning = false
                return
            }

            // ── Guard: Do NOT touch alpha while Fade-to-Clear animator is running ──
            if (isAnimating) {
                isAnimationRunning = false
                return
            }

            // ── SAFETY GATE: Block overlay until sensor has established a valid baseline ──
            // When the feature is just enabled, isCalibrated is false until the sensor
            // captures its first reading. During this window, stale SharedPreferences data
            // could cause a non-zero delta → visible overlay flash. Force transparent and exit.
            if (!isCalibrated) {
                currentDisplayedAlpha = 0f
                targetAlpha = 0f
                applyAlphaToOverlay(0)
                isAnimationRunning = false
                return
            }

            // Ghi nhận nhịp đập sinh tồn của VSYNC
            lastVsyncTime = System.currentTimeMillis()

            // Posture Adaptation v2 decides re-baselining inside the AC
            // sensor listener itself (settle detection on the gravity
            // stream), so VSYNC no longer polls an AC timer here.

            val diff = targetAlpha - currentDisplayedAlpha
            if (Math.abs(diff) > 0.5f) {
                // ── Samsung-Inspired ASYMMETRIC EMA ────────────────────────
                // DARKENING: Fast but slightly smoothed (0.7) to avoid jarring flash
                // FADING: Slow ease-out (0.10) with decay curve near zero
                //   → overlay melts away naturally instead of snapping off
                val emaCoefficient = if (targetAlpha > currentDisplayedAlpha) {
                    // DARKENING (Protection mode) → Fast but still smooth
                    0.70f
                } else {
                    // FADING (Returning to normal) → Slow ease-out with decay
                    // As alpha approaches 0, the coefficient decreases further,
                    // creating a natural deceleration ("landing") effect
                    val fadeBase = 0.10f
                    val decayFactor = (currentDisplayedAlpha / MAX_ALPHA).coerceIn(0.3f, 1.0f)
                    fadeBase * decayFactor
                }
                currentDisplayedAlpha += emaCoefficient * diff

                // ── Overlay Auto-Remove tracking ──────────────────────────
                if (currentDisplayedAlpha < 0.5f) {
                    currentDisplayedAlpha = 0f
                    if (alphaZeroSince == 0L) alphaZeroSince = System.currentTimeMillis()
                } else {
                    alphaZeroSince = 0L
                }

                applyAlphaToOverlay(currentDisplayedAlpha.toInt())
                isAnimationRunning = true
                overlayViews[0].postOnAnimation(this)
            } else {
                // Tắt mượt mà khi đã đạt target để tiết kiệm pin tối đa
                if (currentDisplayedAlpha != targetAlpha) {
                    currentDisplayedAlpha = targetAlpha
                    applyAlphaToOverlay(currentDisplayedAlpha.toInt())
                }

                // ── Overlay Auto-Remove: destroy View when transparent for 2s ──
                if (currentDisplayedAlpha < 0.5f) {
                    if (alphaZeroSince == 0L) alphaZeroSince = System.currentTimeMillis()
                    val transparentDuration = System.currentTimeMillis() - alphaZeroSince
                    if (transparentDuration >= OVERLAY_AUTO_REMOVE_DELAY && !autoCalibrationEnabled) {
                        // View has been fully transparent for 2s → destroy to save resources
                        isOverlayShowing = false
                        removeOverlayView()
                        isShieldEngaged = false
                        alphaZeroSince = 0L
                        Log.d(TAG, "Overlay auto-removed after ${transparentDuration}ms transparency")
                        isAnimationRunning = false
                        return
                    }
                } else {
                    alphaZeroSince = 0L
                }

                if (currentDisplayedAlpha < 0.5f && alphaZeroSince > 0L) {
                    // Keep VSYNC alive for auto-remove countdown
                    isAnimationRunning = true
                    overlayViews[0].postOnAnimation(this)
                } else {
                    isAnimationRunning = false
                }
            }
        }
    }

    // ── BroadcastReceiver for runtime config changes ──────────────────────
    private val configReceiver = object : BroadcastReceiver() {
        private fun getSafeFloat(intent: Intent, key: String, defaultVal: Float): Float {
            val value = intent.extras?.get(key)
            return when (value) {
                is Number -> value.toFloat()
                is String -> value.toFloatOrNull() ?: defaultVal
                else -> defaultVal
            }
        }

        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                ACTION_UPDATE_CONFIG -> {
                    sensorSensitivity = getSafeFloat(intent, EXTRA_SENSITIVITY, sensorSensitivity)
                    sensorTolerance = getSafeFloat(intent, EXTRA_TOLERANCE, sensorTolerance)
                }
                ACTION_SET_SENSITIVITY -> sensorSensitivity = getSafeFloat(intent, EXTRA_SENSITIVITY, sensorSensitivity)
                ACTION_SET_TOLERANCE -> sensorTolerance = getSafeFloat(intent, EXTRA_TOLERANCE, sensorTolerance)
                ACTION_CALIBRATE -> {
                    loadSavedConfig()
                    needsBaselineReset = true
                    isCalibrated = true
                    acBaselineInitialized = false
                    firstDeviationTime = 0L
                    acTargetDuration = 0L
                    removeOverlayView()
                    isOverlayShowing = false

                    if (!isRunning) {
                        isRunning = true
                        rotationSensor?.let {
                            sensorManager?.registerListener(
                                this@MaxOverlayService, it,
                                SensorManager.SENSOR_DELAY_GAME
                            )
                        }
                        wakeLock?.let { if (it.isHeld) it.release() }
                        wakeLock = powerManager?.newWakeLock(
                            PowerManager.PARTIAL_WAKE_LOCK,
                            "Glance::MaxShieldWakeLock"
                        )?.apply {
                            acquire(10 * 60 * 1000L)
                        }
                        notifyTileStateChanged()
                    }
                    
                    // ── BƯỚC 1: Gửi tín hiệu Delta=0 ngay lập tức về Flutter ──
                    // Khi người dùng bấm "Hiệu chỉnh", góc hiện tại sẽ trở thành
                    // baseline mới, nên độ lệch tức thời = 0. Gửi ngay event này
                    // về Flutter để thanh Gamma/Beta giật về 0 độ không cần đợi
                    // sensor reading tiếp theo (loại bỏ delay 16-100ms).
                    broadcastSensorToFlutter(0.0, 0.0)
                    
                    Log.d(TAG, "CALIBRATE — config reloaded, baseline reset, overlay hidden, delta=0 sent to Flutter, isRunning=$isRunning")
                }
                ACTION_STOP_SERVICE -> {
                    isOverlayShowing = false
                    isRunning = false
                    isCalibrated = false
                    removeOverlayView()
                    sensorManager?.unregisterListener(this@MaxOverlayService)
                    wakeLock?.let { if (it.isHeld) it.release() }
                    Log.d(TAG, "Shield HIBERNATED — overlay removed, sensor paused, wake lock released, service still alive")
                    notifyTileStateChanged()
                }
                ACTION_START_SENSOR_ONLY -> {
                    if (!isRunning) {
                        isRunning = true
                        isCalibrated = false
                        needsBaselineReset = false
                        targetAlpha = 0f
                        currentDisplayedAlpha = 0f
                        filteredGx = 0f
                        filteredGy = 0f
                        filteredGz = 0f
                        isShieldEngaged = false
                        alphaZeroSince = 0L
                        acBaselineInitialized = false
                        firstDeviationTime = 0L
                        acTargetDuration = 0L
                        rotationSensor?.let {
                            sensorManager?.registerListener(
                                this@MaxOverlayService, it,
                                SensorManager.SENSOR_DELAY_GAME
                            )
                        }
                        wakeLock?.let { if (it.isHeld) it.release() }
                        wakeLock = powerManager?.newWakeLock(
                            PowerManager.PARTIAL_WAKE_LOCK,
                            "Glance::SensorWakeLock"
                        )?.apply {
                            acquire(10 * 60 * 1000L)
                        }
                        Log.d(TAG, "START_SENSOR_ONLY — sensor registered, streaming Beta/Gamma, overlay NOT active")
                    } else {
                        Log.d(TAG, "START_SENSOR_ONLY — sensor already running, no-op")
                    }
                }
                ACTION_RESUME_SERVICE -> {
                    loadSavedConfig()
                    val autoCalibrate = intent.getBooleanExtra(EXTRA_AUTO_CALIBRATE, false)
                    isCalibrated = autoCalibrate
                    needsBaselineReset = autoCalibrate
                    targetAlpha = 0f
                    currentDisplayedAlpha = 0f
                    // Reset LPF ghost data so first sensor reading is captured cleanly
                    filteredGx = 0f
                    filteredGy = 0f
                    filteredGz = 0f
                    isShieldEngaged = false
                    alphaZeroSince = 0L
                    // Reset AC baseline so gravity sensor also re-bootstraps
                    acBaselineInitialized = false
                    firstDeviationTime = 0L
                    acTargetDuration = 0L
                    removeOverlayView()
                    isOverlayShowing = false

                    if (!isRunning) {
                        isRunning = true
                        rotationSensor?.let {
                            sensorManager?.registerListener(
                                this@MaxOverlayService, it,
                                SensorManager.SENSOR_DELAY_GAME
                            )
                        }
                        wakeLock?.let { if (it.isHeld) it.release() }
                        wakeLock = powerManager?.newWakeLock(
                            PowerManager.PARTIAL_WAKE_LOCK,
                            "Glance::MaxShieldWakeLock"
                        )?.apply {
                            acquire(10 * 60 * 1000L)
                        }
                        notifyTileStateChanged()
                    }
                    Log.d(TAG, "Shield RESUMED — autoCalibrate=$autoCalibrate, overlay hidden, isRunning=$isRunning")
                }
                ShieldConfig.ACTION_NOTIFICATION_SHIELD -> {
                    val active = intent.getBooleanExtra(
                        ShieldConfig.EXTRA_NOTIFICATION_ACTIVE, false
                    )
                    if (active) notificationZone.show() else notificationZone.hide()
                }
                ACTION_SET_OVERLAY_MODE, ACTION_SET_TARGETED_AREA -> {
                    loadSavedConfig()
                    if (isOverlayShowing) {
                        removeOverlayView()
                        createOverlayView()
                    }
                    Log.d(TAG, "Targeted Area / Mode updated dynamically")
                }
                ACTION_REVOKE_ACCESSIBILITY -> {
                    // ── Self-destruct sequence ─────────────────────────────
                    // 1. Stop overlay & sensor
                    isOverlayShowing = false
                    isRunning = false
                    removeOverlayView()
                    sensorManager?.unregisterListener(this@MaxOverlayService)
                    wakeLock?.let { if (it.isHeld) it.release() }
                    Log.d(TAG, "REVOKE_ACCESSIBILITY — overlay removed, sensor stopped")

                    // 2. Call disableSelf() to revoke Accessibility permission
                    //    This causes Android to remove the service from the
                    //    enabled accessibility services list immediately.
                    try {
                        disableSelf()
                        Log.d(TAG, "disableSelf() called — Accessibility permission revoked")
                    } catch (e: Exception) {
                        Log.e(TAG, "disableSelf() failed: ${e.message}")
                    }
                    notifyTileStateChanged()
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  AccessibilityService Lifecycle
    // ══════════════════════════════════════════════════════════════════════

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "onServiceConnected — Max Accessibility Service activated")

        // ── FIX: Deadlock Prevention — Reset animation state on startup ─────
        // When the service is restarted (e.g., after process death), isAnimating
        // may still be true from a previous run, permanently blocking the VSYNC
        // loop and causing the sensor to appear "paralyzed".
        fadeAnimator?.cancel()
        fadeAnimator = null
        isAnimating = false
        targetAlpha = 0f
        currentDisplayedAlpha = 0f
        firstDeviationTime = 0L
        Log.d(TAG, "Animation state RESET — deadlock prevention on startup")

        isRunning = false
        isOverlayShowing = false

        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK.inv().toInt()
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT
            notificationTimeout = 0
        } ?: serviceInfo

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        val filter = IntentFilter().apply {
            addAction(ACTION_UPDATE_CONFIG)
            addAction(ACTION_STOP_SERVICE)
            addAction(ACTION_RESUME_SERVICE)
            addAction(ACTION_CALIBRATE)
            addAction(ACTION_SET_SENSITIVITY)
            addAction(ACTION_SET_OVERLAY_MODE)
            addAction(ACTION_SET_TARGETED_AREA)
            addAction(ACTION_SET_TOLERANCE)
            addAction(ACTION_START_SENSOR_ONLY)
            addAction(ShieldConfig.ACTION_NOTIFICATION_SHIELD)
            addAction(ACTION_REVOKE_ACCESSIBILITY)
        }
        registerReceiver(configReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        loadSavedConfig()
        initAutoCalibration()
        notifyTileStateChanged()

        Log.d(TAG, "Initialization complete — HIBERNATED, waiting for RESUME broadcast")
    }

    /**
     * Handles explicit Intents sent via startService() from GlanceQuickTileService.
     *
     * This is a fallback path for when the BroadcastReceiver (configReceiver)
     * hasn't re-registered yet after a process restart. The tile sends both
     * a broadcast AND a startService intent — whichever arrives first wins.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            when (intent.action) {
                ACTION_RESUME_SERVICE -> {
                    Log.d(TAG, "onStartCommand — ACTION_RESUME_SERVICE received (direct Intent)")
                    loadSavedConfig()
                    val autoCalibrate = intent.getBooleanExtra(EXTRA_AUTO_CALIBRATE, false)
                    isCalibrated = autoCalibrate
                    needsBaselineReset = autoCalibrate
                    targetAlpha = 0f
                    currentDisplayedAlpha = 0f
                    filteredGx = 0f
                    filteredGy = 0f
                    filteredGz = 0f
                    isShieldEngaged = false
                    alphaZeroSince = 0L
                    acBaselineInitialized = false
                    firstDeviationTime = 0L
                    acTargetDuration = 0L
                    removeOverlayView()
                    isOverlayShowing = false

                    if (!isRunning) {
                        isRunning = true
                        rotationSensor?.let {
                            sensorManager?.registerListener(
                                this, it,
                                SensorManager.SENSOR_DELAY_GAME
                            )
                        }
                        wakeLock?.let { if (it.isHeld) it.release() }
                        wakeLock = powerManager?.newWakeLock(
                            PowerManager.PARTIAL_WAKE_LOCK,
                            "Glance::MaxShieldWakeLock"
                        )?.apply {
                            acquire(10 * 60 * 1000L)
                        }
                        notifyTileStateChanged()
                    }
                    Log.d(TAG, "onStartCommand — RESUME complete, autoCalibrate=$autoCalibrate, isRunning=$isRunning")
                }
                ACTION_STOP_SERVICE -> {
                    Log.d(TAG, "onStartCommand — ACTION_STOP_SERVICE received (direct Intent)")
                    isOverlayShowing = false
                    isRunning = false
                    isCalibrated = false
                    removeOverlayView()
                    sensorManager?.unregisterListener(this)
                    wakeLock?.let { if (it.isHeld) it.release() }
                    notifyTileStateChanged()
                }
            }
        }
        return START_STICKY
    }

    // ── Privacy Display triggers (Glance 2.0) ──────────────────────────
    // Window-state events feed the per-app trigger; focus/content events
    // feed the credential guard. The credential guard inspects ONLY the
    // isPassword boolean of the focused input node — notification/window
    // content and typed text are never read, logged or stored.
    private var lastCredentialCheckMs: Long = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    val pkg = event.packageName?.toString()
                    PrivacyDisplayTriggers.updateForegroundApp(this, pkg)
                    if (pkg != null && pkg != packageName) {
                        PrivacyDisplayState.credentialFieldFocused = false
                    }
                }
                AccessibilityEvent.TYPE_VIEW_FOCUSED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    if (!PrivacyDisplayPrefs.read(this).credentialGuard) {
                        PrivacyDisplayState.credentialFieldFocused = false
                        return
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastCredentialCheckMs < 300L) return
                    lastCredentialCheckMs = now
                    val root = rootInActiveWindow ?: return
                    val focused = root.findFocus(
                        android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT
                    )
                    PrivacyDisplayState.credentialFieldFocused =
                        focused?.isPassword == true
                    @Suppress("DEPRECATION")
                    focused?.recycle()
                    @Suppress("DEPRECATION")
                    root.recycle()
                }
            }
        } catch (_: Exception) {
            // Trigger detection must never crash the shield engine.
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "onInterrupt called")
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy — cleaning up")
        isRunning = false
        destroyAutoCalibration()
        try { unregisterReceiver(configReceiver) } catch (_: Exception) {}
        sensorManager?.unregisterListener(this)
        removeOverlayView()
        wakeLock?.let { if (it.isHeld) it.release() }
        notifyTileStateChanged()
        super.onDestroy()
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Sensor handling
    // ══════════════════════════════════════════════════════════════════════

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != rotationSensor?.type) return
        if (!isRunning) return

        // ── 1. Hybrid Sensor Engine ───────────────────────────────────────
        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        // Bóc tách vector trọng lực thô (cột thứ 3 của ma trận xoay)
        val rawGx = rotationMatrix[6]
        val rawGy = rotationMatrix[7]
        val rawGz = rotationMatrix[8]

        // ── Samsung-Inspired Adaptive LPF ─────────────────────────────────
        // Reset bộ lọc ngay lập tức khi cần lấy mốc (needsBaselineReset)
        // để triệt tiêu bóng ma dữ liệu cũ
        if ((filteredGx == 0f && filteredGy == 0f && filteredGz == 0f) || needsBaselineReset) {
            // Ép bộ lọc nhận ngay giá trị thực tế để baseline bắt mốc chuẩn xác 100%
            filteredGx = rawGx
            filteredGy = rawGy
            filteredGz = rawGz
            prevRawGx = rawGx
            prevRawGy = rawGy
            prevRawGz = rawGz
        } else {
            // Compute adaptive alpha based on raw sensor change magnitude
            val dx = rawGx - prevRawGx
            val dy = rawGy - prevRawGy
            val dz = rawGz - prevRawGz
            val adaptiveAlpha = computeAdaptiveLpfAlpha(dx, dy, dz)
            filteredGx += adaptiveAlpha * (rawGx - filteredGx)
            filteredGy += adaptiveAlpha * (rawGy - filteredGy)
            filteredGz += adaptiveAlpha * (rawGz - filteredGz)
            prevRawGx = rawGx
            prevRawGy = rawGy
            prevRawGz = rawGz
        }

        // Trục Gamma (Roll): Dùng asin(filteredGx) để lấy góc nghiêng ngang tuyệt đối, không bị nhiễu bởi độ chúi của máy (Pitch)
        val safeGx = filteredGx.toDouble().coerceIn(-1.0, 1.0)
        val rawRollDeg = Math.toDegrees(Math.asin(safeGx)).toFloat()

        // Trục Beta (Pitch): Dùng atan2(filteredGy, filteredGz) để đo độ chúi dọc ổn định
        val rawPitchDeg = Math.toDegrees(Math.atan2(filteredGy.toDouble(), filteredGz.toDouble())).toFloat()

        // ── 2. Stream sensor data to Flutter UI (always, even before calibration)
        // Normalize angle differences to [-180, 180] to prevent cumulative drift
        val displayRoll = if (isCalibrated) normalizeAngle(rawRollDeg - baselineRoll) else rawRollDeg
        val displayPitch = if (isCalibrated) normalizeAngle(rawPitchDeg - baselinePitch) else rawPitchDeg
        broadcastSensorToFlutter(displayPitch.toDouble(), displayRoll.toDouble())

        if (!isCalibrated) {
            this.targetAlpha = 0f
            currentDisplayedAlpha = 0f
            if (isOverlayShowing) {
                isOverlayShowing = false
                removeOverlayView()
            }
            return
        }

        // ── 4. Baseline capture on first sample after calibration ─────────
        if (needsBaselineReset) {
            baselineRoll = rawRollDeg
            baselinePitch = rawPitchDeg
            currentDisplayedAlpha = 0f
            isShieldEngaged = false
            alphaZeroSince = 0L
            needsBaselineReset = false

            // FIX: Lưu baseline mới vào SharedPreferences ngay lập tức,
            // đè lên dữ liệu rác cũ để Delta luôn bằng 0 sau reset.
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
                putFloat(KEY_BASELINE_PITCH, rawPitchDeg)
                putFloat(KEY_BASELINE_ROLL, rawRollDeg)
                apply()
            }

            Log.d(TAG, "Baseline captured — roll=$rawRollDeg°, pitch=$rawPitchDeg° (saved to prefs)")
        }

        // Normalize angle differences to prevent wrap-around artifacts at ±180° boundary
        val absRoll = Math.abs(normalizeAngle(rawRollDeg - baselineRoll))
        val absPitch = Math.abs(normalizeAngle(rawPitchDeg - baselinePitch))

        // ── 4. Samsung-Inspired Privacy Display Algorithm ─────────────────
        // Tổng hợp vector chéo bằng định lý Pytago để tạo vùng an toàn hình tròn
        val maxDeviation = Math.hypot(absRoll.toDouble(), absPitch.toDouble()).toFloat()

        // ── 4a. Hysteresis Dead Zone (Anti-Flicker) ───────────────────────
        // Uses two separate thresholds to prevent flicker at boundary:
        //   • OUTER threshold (activate shield): tolerance + band/2
        //   • INNER threshold (deactivate shield): tolerance - band/2
        val hysteresisBand = computeHysteresisBand()
        // Privacy Display triggers (per-app / credential guard): while a
        // protected app is in the foreground or a password field is
        // focused, tighten tolerance so the shield reacts to much smaller
        // tilts — Samsung-style auto-arming on sensitive content.
        val effectiveTolerance = if (PrivacyDisplayState.triggerActive) {
            minOf(sensorTolerance, ShieldConfig.TRIGGER_TOLERANCE_DEG)
        } else {
            sensorTolerance
        }
        val outerThreshold = effectiveTolerance + hysteresisBand / 2f
        val innerThreshold = (effectiveTolerance - hysteresisBand / 2f).coerceAtLeast(1f)

        // Update hysteresis state
        if (!isShieldEngaged && maxDeviation > outerThreshold) {
            isShieldEngaged = true
        } else if (isShieldEngaged && maxDeviation < innerThreshold) {
            isShieldEngaged = false
        }

        // ── 4b. S-Curve Alpha Ramp (Samsung Smoothstep) ───────────────────
        // When shield is engaged, compute target alpha using a smooth S-curve
        // instead of linear ramp. The ramp width adapts to sensitivity setting.
        this.targetAlpha = if (isShieldEngaged) {
            val rampWidth = computeDeviationRampWidth()
            val rampStart = innerThreshold
            val rampEnd = innerThreshold + rampWidth
            // Smoothstep: S-curve from 0 to MAX_ALPHA over the ramp range
            smoothstep(rampStart, rampEnd, maxDeviation) * MAX_ALPHA
        } else {
            0f
        }

        // Persistent View: Chỉ tạo View duy nhất 1 lần khi vượt ngưỡng
        if (!isOverlayShowing && this.targetAlpha > 0f) {
            isOverlayShowing = true
            alphaZeroSince = 0L
            createOverlayView()
        } else if (isOverlayShowing && Math.abs(this.targetAlpha - currentDisplayedAlpha) > 0.5f) {
            // Watchdog: Nếu hệ thống Android ngầm drop VSYNC (để tiết kiệm pin) khiến animation bị kẹt
            val now = System.currentTimeMillis()
            if (!isAnimationRunning || (now - lastVsyncTime > 100L)) {
                isAnimationRunning = true
                lastVsyncTime = now
                if (overlayViews.isNotEmpty()) {
                    overlayViews[0].removeCallbacks(vsyncRunnable)
                    overlayViews[0].postOnAnimation(vsyncRunnable)
                }
            }
        }
    } // End of onSensorChanged

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ══════════════════════════════════════════════════════════════════════
    //  Overlay management — TYPE_ACCESSIBILITY_OVERLAY
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Applies alpha value to all overlay views by setting background color.
     */
    private fun applyAlphaToOverlay(alpha: Int) {
        if (overlayViews.isEmpty()) return
        val safeAlpha = alpha.coerceIn(0, MAX_ALPHA)
        val shieldColor = android.graphics.Color.argb(safeAlpha, 0, 0, 0)
        overlayViews.forEach { view ->
            if (view is PatternOverlayView) {
                // Pixel Pattern COMBINES with the solid shield: the pattern
                // layer paints the same per-layer base alpha as a solid
                // layer, plus the stripe grille whose density follows the
                // shield intensity. (Previously it painted the grille only,
                // so Maximum + Pattern was weaker than Maximum alone.)
                view.setBaseAlpha(safeAlpha)
                view.setIntensity(safeAlpha.toFloat() / MAX_ALPHA)
            } else {
                view.setBackgroundColor(shieldColor)
            }
        }
    }

    private fun ensureVsyncRunning() {
        if (overlayViews.isEmpty()) return
        val view = overlayViews[0]
        view.removeCallbacks(vsyncRunnable)
        isAnimationRunning = true
        lastVsyncTime = System.currentTimeMillis()
        view.postOnAnimation(vsyncRunnable)
    }

    /**
     * Creates the overlay using TYPE_ACCESSIBILITY_OVERLAY (trusted window).
     * Uses 2 layers (Dual Shield) with standard View background color.
     */
    private fun createOverlayView() {

        if (overlayViews.isNotEmpty()) return

        // FIX: Reset alpha về 0 trước khi tạo View, đảm bảo lớp phủ luôn
        // bắt đầu từ trạng thái tàng hình tuyệt đối, triệt tiêu flash.
        currentDisplayedAlpha = 0f

        try {
            // Pixel Pattern rendering applies to fullscreen mode only.
            val usePatternOverlay = PrivacyDisplayPrefs.read(this).renderPattern &&
                overlayMode != "targeted"

            val isTargeted = overlayMode == "targeted"

            // CRITICAL FIX: Dữ liệu từ Flutter đã là Physical Pixels. TUYỆT ĐỐI KHÔNG nhân thêm density.
            val pxX = if (isTargeted) areaX else 0
            val pxY = if (isTargeted) areaY else 0
            val pxW = if (isTargeted && areaWidth > 0) areaWidth else WindowManager.LayoutParams.MATCH_PARENT
            val pxH = if (isTargeted && areaHeight > 0) areaHeight else WindowManager.LayoutParams.MATCH_PARENT

            // Dual Shield = ALWAYS 2 layers. With Pixel Pattern on, the
            // TOP layer is a PatternOverlayView painting the same
            // per-layer base fill plus the stripe grille, so pattern and
            // solid shield compound (effective ≈ ALPHA_MAX_EFFECTIVE).
            // There is only ever ONE grille, so no moiré between grilles.
            // (Previously pattern mode dropped to a single grille-only
            // layer with transparent gaps — Maximum + Pattern ended up
            // weaker than Maximum alone.)
            val layerCount = 2
            for (i in 0 until layerCount) {
                val params = WindowManager.LayoutParams(
                    pxW,
                    pxH,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    if (isTargeted) {
                        x = pxX
                        y = pxY
                    }

                    // CRITICAL FIX: Ép tràn viền qua Tai thỏ/Camera đục lỗ
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    }
                    // CRITICAL FIX: Bỏ qua System Insets (Status Bar & Nav Bar) trên Android 11+
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        fitInsetsTypes = 0
                    }

                }

                val view: View = if (usePatternOverlay && i == layerCount - 1) {
                    PatternOverlayView(this)
                } else {
                    View(this)
                }
                view.apply {
                    // FIX: Đảm bảo lớp phủ tàng hình tuyệt đối khi vừa sinh ra,
                    // triệt tiêu hiện tượng flash khi View được addView().
                    // Background alpha = 0 (hoàn toàn trong suốt), View alpha = 1
                    // để VSYNC loop điều khiển opacity qua setBackgroundColor().
                    setBackgroundColor(android.graphics.Color.argb(0, 0, 0, 0))
                    alpha = 1f
                }
                windowManager.addView(view, params)
                overlayViews.add(view)
            }
            if (!isAnimationRunning && overlayViews.isNotEmpty()) {
                overlayViews[0].postOnAnimation(vsyncRunnable)
            }
            Log.d(TAG, "Max Shield created — $layerCount layers (pattern=$usePatternOverlay), TYPE_ACCESSIBILITY_OVERLAY")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create Max Shield overlay: ${e.message}")
            removeOverlayView()
        }
    }

    private fun removeOverlayView() {
        isAnimationRunning = false
        overlayViews.forEach { view ->
            view.removeCallbacks(vsyncRunnable)
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {}
        }
        overlayViews.clear()
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Sensor → Flutter streaming
    // ══════════════════════════════════════════════════════════════════════

    private fun broadcastSensorToFlutter(pitch: Double, roll: Double) {
        val now = System.currentTimeMillis()
        if (now - lastSensorStreamTime < 100L) return
        lastSensorStreamTime = now

        val sink = sensorEventSink ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                // FIX: Hoán đổi mapping để khớp với comment và UI
                // Beta (Roll) = Góc nghiêng ngang (Left/Right)
                // Gamma (Pitch) = Góc chúi dọc (Front/Back)
                sink.success(mapOf("beta" to roll, "gamma" to pitch))
            } catch (_: Exception) {}
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Auto-Calibration Engine
    // ══════════════════════════════════════════════════════════════════════

    private fun initAutoCalibration() {
        val flutterPrefs = getSharedPreferences(FLUTTER_PREFS_NAME, Context.MODE_PRIVATE)
        autoCalibrationEnabled = flutterPrefs.getBoolean(KEY_AUTO_CALIBRATE, false)

        acPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == KEY_AUTO_CALIBRATE) {
                val enabled = prefs.getBoolean(KEY_AUTO_CALIBRATE, false)
                Log.d(TAG, "AutoCalibration kill switch changed: $enabled")
                if (enabled && !autoCalibrationEnabled) {
                    autoCalibrationEnabled = true
                    startAutoCalibrationSensor()
                } else if (!enabled && autoCalibrationEnabled) {
                    autoCalibrationEnabled = false
                    stopAutoCalibrationSensor()
                }
            }
        }
        flutterPrefs.registerOnSharedPreferenceChangeListener(acPrefsListener)

        gravitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (autoCalibrationEnabled) {
            startAutoCalibrationSensor()
        }
        Log.d(TAG, "AutoCalibration initialized — enabled=$autoCalibrationEnabled")
    }

    private fun startAutoCalibrationSensor() {
        gravitySensor?.let {
            sensorManager?.registerListener(
                autoCalibrationSensorListener, it,
                // GAME rate (~20ms): Posture Adaptation v2 measures
                // angular speed, which needs a denser stream than
                // SENSOR_DELAY_NORMAL (~200ms) can provide.
                SensorManager.SENSOR_DELAY_GAME
            )
        }
        Log.d(TAG, "AutoCalibration sensor STARTED")
    }

    private fun stopAutoCalibrationSensor() {
        sensorManager?.unregisterListener(autoCalibrationSensorListener)
        cancelAutoCalibrationTimer()
        Log.d(TAG, "AutoCalibration sensor STOPPED — angle frozen")
    }

    private fun cancelAutoCalibrationTimer() {
        acStabilityRunnable?.let { acHandler.removeCallbacks(it) }
        acStabilityRunnable = null
        firstDeviationTime = 0L
        acTargetDuration = 0L
        acSettleStartMs = 0L
    }

    private fun destroyAutoCalibration() {
        stopAutoCalibrationSensor()
        val flutterPrefs = getSharedPreferences(FLUTTER_PREFS_NAME, Context.MODE_PRIVATE)
        acPrefsListener?.let { flutterPrefs.unregisterOnSharedPreferenceChangeListener(it) }
        acPrefsListener = null
    }

    private val autoCalibrationSensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null) return
            if (!autoCalibrationEnabled || !isRunning) return
            if (isAnimating) return

            val gx = event.values[0]
            val gy = event.values[1]
            val gz = event.values[2]

            val rawPitch = Math.toDegrees(
                Math.atan2(gy.toDouble(), gz.toDouble())
            ).toFloat()
            val rawRoll = Math.toDegrees(
                Math.atan2(gx.toDouble(), gz.toDouble())
            ).toFloat()

            // ── BUG FIX: Bootstrap auto-calibration before manual calibration ──
            // If auto-calibration is enabled but user hasn't manually calibrated yet,
            // use the first sensor reading as the initial baseline so the 10° deviation
            // check has a reference point to work from immediately.
            if (!isCalibrated) {
                if (!acBaselineInitialized) {
                    acSmoothedPitch = rawPitch
                    acSmoothedRoll = rawRoll
                    acCurrentBaselinePitch = rawPitch
                    acCurrentBaselineRoll = rawRoll
                    acBaselineInitialized = true
                    acPrevSmoothedPitch = rawPitch
                    acPrevSmoothedRoll = rawRoll
                    acLastSampleMs = System.currentTimeMillis()
                    acSettleStartMs = 0L
                    Log.d(TAG, "AC baseline initialized while disarmed — pitch=${rawPitch}°, roll=${rawRoll}°")
                }
                return
            }

            if (!acBaselineInitialized) {
                acSmoothedPitch = rawPitch
                acSmoothedRoll = rawRoll
                acCurrentBaselinePitch = rawPitch
                acCurrentBaselineRoll = rawRoll
                acBaselineInitialized = true
                acPrevSmoothedPitch = rawPitch
                acPrevSmoothedRoll = rawRoll
                acLastSampleMs = System.currentTimeMillis()
                acSettleStartMs = 0L
                Log.d(TAG, "AC baseline initialized — pitch=${rawPitch}°, roll=${rawRoll}°")
                return
            }

            acSmoothedPitch += AC_LPF_ALPHA * (rawPitch - acSmoothedPitch)
            acSmoothedRoll += AC_LPF_ALPHA * (rawRoll - acSmoothedRoll)

            // ── Posture Adaptation v2 ──────────────────────────────────
            // (Design rationale lives with the AC_* constants above.)
            // Step 1: angular speed of the SMOOTHED orientation. The LPF
            // has already removed hand tremor, so a settled grip reads
            // only a few °/s while a genuine posture change reads 40°/s+.
            val nowMs = System.currentTimeMillis()
            val dtSec = if (acLastSampleMs > 0L) {
                ((nowMs - acLastSampleMs) / 1000f).coerceIn(0.01f, 1f)
            } else {
                0.05f
            }
            val stepDeg = Math.hypot(
                normalizeAngle(acSmoothedPitch - acPrevSmoothedPitch).toDouble(),
                normalizeAngle(acSmoothedRoll - acPrevSmoothedRoll).toDouble()
            ).toFloat()
            val angularSpeed = stepDeg / dtSec
            acPrevSmoothedPitch = acSmoothedPitch
            acPrevSmoothedRoll = acSmoothedRoll
            acLastSampleMs = nowMs

            // Step 2: physical-validity gate. This REPLACES the old
            // |pitch| 10°-170° dead zone, which froze adaptation in
            // exactly the lying-down postures that need it (|pitch|
            // leaves that band when the phone is held overhead). The
            // gravity vector of a held device has |g| ≈ 9.8 m/s² at ANY
            // posture; near-0 means free-fall or a sensor glitch.
            val gMag = Math.sqrt((gx * gx + gy * gy + gz * gz).toDouble()).toFloat()
            if (gMag < AC_GRAVITY_MIN || gMag > AC_GRAVITY_MAX) {
                acSettleStartMs = 0L
                return
            }

            // Step 3: deviation from the current AC baseline. Only
            // deviations the shield itself would act on (user tolerance
            // + margin) can become posture candidates.
            val deviation = Math.hypot(
                normalizeAngle(acSmoothedPitch - acCurrentBaselinePitch).toDouble(),
                normalizeAngle(acSmoothedRoll - acCurrentBaselineRoll).toDouble()
            ).toFloat()
            val postureThreshold = maxOf(sensorTolerance + AC_POSTURE_MARGIN_DEG, AC_POSTURE_MIN_DEG)
            if (deviation <= postureThreshold) {
                if (acSettleStartMs != 0L) {
                    Log.d(TAG, "AC posture candidate dropped - back within threshold (dev=${deviation})")
                    acSettleStartMs = 0L
                }
                return
            }

            // Step 4: still moving -> not a posture yet. The settle
            // window (re)opens at the arrival orientation; natural sway
            // AFTER arrival stays under the speed threshold, so - unlike
            // the old frozen-snapshot timer - it no longer restarts the
            // wait from zero.
            if (angularSpeed > AC_SETTLE_SPEED_DEG_S) {
                acSettleStartMs = 0L
                acCandidateStartPitch = acSmoothedPitch
                acCandidateStartRoll = acSmoothedRoll
                return
            }

            // Step 5: settled in a deviated orientation - open the settle
            // window, or keep the candidate glued to the live smoothed
            // angles while it runs.
            if (acSettleStartMs == 0L) {
                acSettleStartMs = nowMs
                acCandidateStartPitch = acSmoothedPitch
                acCandidateStartRoll = acSmoothedRoll
                Log.d(TAG, "AC posture settle STARTED - dev=${deviation}, speed=${angularSpeed}/s")
                return
            }
            acStableTargetPitch = acSmoothedPitch
            acStableTargetRoll = acSmoothedRoll

            // Guard: the candidate must not have wandered since the
            // window opened - a slow continuous drift is not a held
            // posture, so restart the window at the current orientation.
            val candidateDrift = Math.hypot(
                normalizeAngle(acSmoothedPitch - acCandidateStartPitch).toDouble(),
                normalizeAngle(acSmoothedRoll - acCandidateStartRoll).toDouble()
            ).toFloat()
            if (candidateDrift > AC_CANDIDATE_DRIFT_DEG) {
                acSettleStartMs = nowMs
                acCandidateStartPitch = acSmoothedPitch
                acCandidateStartRoll = acSmoothedRoll
                return
            }

            // Step 6: held still long enough -> this IS the user's new
            // posture (sitting -> lying, etc.). Adopt it as the baseline;
            // the transition fades the shield out and asks the main
            // engine to re-capture its own baseline on the next frame.
            if (nowMs - acSettleStartMs >= AC_SETTLE_DURATION_MS) {
                Log.d(TAG, "AC posture ADAPTED - new baseline pitch=${acStableTargetPitch}, roll=${acStableTargetRoll} (dev was ${deviation})")
                acSettleStartMs = 0L
                performSmoothBaselineTransition(acStableTargetPitch, acStableTargetRoll)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /**
     * Updates baseline by requesting the main rotation-sensor engine to
     * re-capture its own baseline on the very next frame.
     *
     * ⚠️ CRITICAL FIX — Cumulative Bug & Flash Bug:
     * The auto-calibration engine uses TYPE_GRAVITY (atan2) which produces
     * angles in a DIFFERENT coordinate system than the main engine's
     * TYPE_GAME_ROTATION_VECTOR (rotation matrix → asin/atan2).
     * Writing gravity-angles directly into baselinePitch/baselineRoll
     * caused the main engine's delta calculation to explode (cumulative bug)
     * and the forced alpha=0 caused a visible 1-frame flash.
     *
     * Solution:
     * 1. Update only AC's own tracking variables (acCurrentBaseline*).
     * 2. Set needsBaselineReset=true so the MAIN sensor captures its own
     *    baseline in its own coordinate system on the next frame.
     * 3. Do NOT force targetAlpha/currentDisplayedAlpha to 0 — let the
     *    VSYNC loop naturally converge to 0 once the new baseline matches
     *    the current sensor reading (delta ≈ 0 → alpha ≈ 0).
     */
    private fun performSmoothBaselineTransition(newPitch: Float, newRoll: Float) {
        // Step 1: Update AC's own baseline tracking (gravity-sensor coordinates)
        acCurrentBaselinePitch = newPitch
        acCurrentBaselineRoll = newRoll

        // Step 2: Reset deviation timer + settle window
        firstDeviationTime = 0L
        acSettleStartMs = 0L

        // Step 3: Cancel any previous fade animator to avoid conflicts
        fadeAnimator?.cancel()

        // Step 4: Smooth Fade-to-Clear (1.5s ValueAnimator)
        // Lock out main sensor & VSYNC loop from touching alpha during fade
        isAnimating = true
        targetAlpha = 0f

        val startAlpha = currentDisplayedAlpha
        fadeAnimator = ValueAnimator.ofFloat(startAlpha, 0f).apply {
            duration = AC_BREATHE_FADEOUT_DURATION
            addUpdateListener { animator ->
                val value = animator.animatedValue as Float
                currentDisplayedAlpha = value
                applyAlphaToOverlay(value.toInt())
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // ── BUG 2 FIX: Full State Liberation + AC Baseline Re-sync ──
                    // All state variables MUST be reset to allow the next
                    // auto-calibration cycle to work. Missing any of these
                    // causes a "one-shot" deadlock where AC only works once.
                    currentDisplayedAlpha = 0f
                    targetAlpha = 0f
                    isAnimating = false
                    needsBaselineReset = true
                    firstDeviationTime = 0L
                    acTargetDuration = 0L
                    // ── CRITICAL: Re-sync AC Gravity baseline to CURRENT smoothed angle ──
                    // During the 1.5s fade animation, the device may have moved further.
                    // If we keep the old acCurrentBaseline (set at animation START),
                    // the Gravity sensor sees stale baseline → computes wrong delta →
                    // immediately starts a new timer → AC appears "stuck" or "one-shot".
                    // Re-capture NOW so next deviation is measured from the TRUE current angle.
                    acCurrentBaselinePitch = newPitch
                    acCurrentBaselineRoll = newRoll
                    Log.d(TAG, "Fade-to-Clear complete — ALL state reset + AC baseline re-synced to pitch=${acSmoothedPitch}°, roll=${acSmoothedRoll}°, ready for next AC cycle")
                }
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    // ── BUG 2 FIX: Full State Liberation + AC Baseline Re-sync (on cancel) ──
                    currentDisplayedAlpha = 0f
                    targetAlpha = 0f
                    isAnimating = false
                    needsBaselineReset = true
                    firstDeviationTime = 0L
                    acTargetDuration = 0L
                    acCurrentBaselinePitch = newPitch
                    acCurrentBaselineRoll = newRoll
                    Log.d(TAG, "Fade-to-Clear CANCELLED — ALL state reset + AC baseline re-synced")
                }
            })
            start()
        }

        Log.d(TAG, "AC baseline transition — Fade-to-Clear started (${startAlpha.toInt()} → 0 over 1.5s), AC pitch=$newPitch°, roll=$newRoll°")
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Config helpers
    // ══════════════════════════════════════════════════════════════════════

    private fun loadSavedConfig() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sensorSensitivity = prefs.getFloat(KEY_SENSITIVITY, 0.5f)
        sensorTolerance = prefs.getFloat(KEY_TOLERANCE, 5.0f)

        overlayMode = prefs.getString(KEY_OVERLAY_MODE, "fullscreen") ?: "fullscreen"
        areaX = prefs.getInt(KEY_AREA_X, 0)
        areaY = prefs.getInt(KEY_AREA_Y, 0)
        areaWidth = prefs.getInt(KEY_AREA_WIDTH, WindowManager.LayoutParams.MATCH_PARENT)
        areaHeight = prefs.getInt(KEY_AREA_HEIGHT, WindowManager.LayoutParams.MATCH_PARENT)
        Log.d(TAG, "Config loaded — mode=$overlayMode, area=($areaX, $areaY, $areaWidth, $areaHeight)")
    }

    private fun notifyTileStateChanged() {
        try {
            TileService.requestListeningState(
                this,
                ComponentName(this, GlanceQuickTileService::class.java)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to notify tile: ${e.message}")
        }
    }
}
