package com.glanceapp.glance

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.service.quicksettings.TileService
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * StandardOverlayService — Regular Foreground Service Privacy Shield (Standard Mode)
 *
 * Extends plain [Service] (NOT AccessibilityService) so banking apps
 * are NOT blocked. Uses TYPE_APPLICATION_OVERLAY which requires only the
 * SYSTEM_ALERT_WINDOW (Overlay) permission.
 *
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  DUAL-ENGINE ARCHITECTURE — STANDARD ENGINE                         │
 * │                                                                    │
 * │  • Uses TYPE_APPLICATION_OVERLAY (untrusted window)                 │
 * │  • Alpha capped at 212 (~83%) — below Maximum mode intensity        │
 * │  • Requires ONLY Overlay permission (no Accessibility)              │
 * │  • Banking apps work normally                                       │
 * │  • Runs as foreground service with notification                     │
 * │  • FLAG_NOT_TOUCHABLE for touch pass-through (≤80% opacity safe)    │
 * └──────────────────────────────────────────────────────────────────────┘
 */
class StandardOverlayService : Service(), SensorEventListener {

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
        private const val TAG = "StandardOverlayService"

        @Volatile
        @JvmStatic
        var isRunning: Boolean = false
            private set

        @JvmStatic
        var isCalibrated: Boolean = false

        @JvmStatic
        var sensorEventSink: io.flutter.plugin.common.EventChannel.EventSink? = null

        // ── Action constants (shared with MaxOverlayService) ──────────────
        const val ACTION_UPDATE_CONFIG = "com.glanceapp.glance.UPDATE_CONFIG"
        const val ACTION_STOP_SERVICE = "com.glanceapp.glance.STOP_SERVICE"
        const val ACTION_RESUME_SERVICE = "com.glanceapp.glance.RESUME_SERVICE"
        const val ACTION_CALIBRATE = "com.glanceapp.glance.CALIBRATE"
        const val ACTION_SET_SENSITIVITY = "com.glanceapp.glance.SET_SENSITIVITY"
        const val ACTION_SET_OVERLAY_MODE = "com.glanceapp.glance.SET_OVERLAY_MODE"
        const val ACTION_SET_TARGETED_AREA = "com.glanceapp.glance.SET_TARGETED_AREA"
        const val ACTION_SET_TOLERANCE = "com.glanceapp.glance.SET_TOLERANCE"
        const val ACTION_START_SENSOR_ONLY = "com.glanceapp.glance.START_SENSOR_ONLY"
        const val ACTION_START_STANDARD_MODE = "com.glanceapp.glance.START_STANDARD_MODE"

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

        // ── Notification constants ────────────────────────────────────────
        private const val NOTIF_CHANNEL_ID = "glance_standard_channel"
        private const val NOTIF_ID = 9001

        // ── Max Alpha for Standard mode ───────────────────────────────────
        // 250 ~= 98% opacity (+15% from previous 83%), substantially
        // stronger shield while staying below full opaque.
        private const val MAX_ALPHA = 255
    }

    // ── System services ───────────────────────────────────────────────────
    private var windowManager: WindowManager? = null
    private var sensorManager: SensorManager? = null
    private var rotationSensor: Sensor? = null
    private var powerManager: PowerManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // ── Overlay views ─────────────────────────────────────────────────────
    private val overlayViews: MutableList<View> = mutableListOf()

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
    // Gravity sensor for pitch detection (auto-calibration only)
    private var gravitySensor: Sensor? = null
    private var autoCalibrationEnabled: Boolean = false

    // LPF-smoothed pitch/roll for auto-calibration (separate from main sensor)
    private var acSmoothedPitch: Float = 0f
    private var acSmoothedRoll: Float = 0f
    private val AC_LPF_ALPHA: Float = 0.1f  // Heavy smoothing to suppress hand tremor

    // Current overlay baseline angle (the angle the overlay is "locked" to)
    private var acCurrentBaselinePitch: Float = 0f
    private var acCurrentBaselineRoll: Float = 0f
    private var acBaselineInitialized: Boolean = false

    // Stability timer: fires after 5 seconds of stable new angle
    private val acHandler: Handler = Handler(Looper.getMainLooper())
    private var acStabilityRunnable: Runnable? = null
    private var acStableTargetPitch: Float = 0f
    private var acStableTargetRoll: Float = 0f
    private var acStableOpacityBucket: Int = -1
    // *** New timestamp for auto-calibration stability ***
    // Marked @Volatile for thread-safe access from both sensor and VSYNC threads
    @Volatile
    private var firstDeviationTime: Long = 0L
    
    @Volatile
    private var acTargetDuration: Long = 0L  // Computed by sensor thread, read by VSYNC

    // Thresholds
    private val AC_ANGLE_CHANGE_THRESHOLD: Float = 40.0f   // Must deviate > 40° to start timer (only large posture changes trigger auto-calibration)
    private val AC_STABILITY_TOLERANCE: Float = 8.0f       // Must stay within 8° during 5s (relaxed for extreme angles)
    private val AC_STABILITY_DURATION: Long = 3000L        // 5 seconds
    private val AC_PITCH_MIN: Float = 10f                  // Dead zone: widened to 10°–170° for lying-down use
    private val AC_PITCH_MAX: Float = 170f
    private val AC_MIN_OPACITY_RATIO: Float = 0.10f
    private val AC_FAST_OPACITY_RATIO: Float = 0.40f
    private val AC_FAST_STABILITY_DURATION: Long = 500L
    private val AC_SNAPSHOT_ANGLE_TOLERANCE: Float = 5.0f
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

            // ── BƯỚC 2.2: FIX Timer Check — Move from sensor to VSYNC for accuracy ──
            // PROBLEM: Sensor callback doesn't fire when device is still → timer misses deadline
            // SOLUTION: VSYNC runs at 60fps continuously, always catches timer expiration
            if (autoCalibrationEnabled && firstDeviationTime > 0L && acTargetDuration > 0L) {
                val elapsed = System.currentTimeMillis() - firstDeviationTime
                if (elapsed >= acTargetDuration) {
                    // Timer expired - trigger baseline transition
                    Log.d(TAG, "VSYNC detected AC timer expiration — elapsed=${elapsed}ms, target=${acTargetDuration}ms")
                    performSmoothBaselineTransition(acStableTargetPitch, acStableTargetRoll)
                    firstDeviationTime = 0L
                    acTargetDuration = 0L
                    // FIX: Return immediately after triggering transition.
                    // performSmoothBaselineTransition sets isAnimating=true and starts
                    // a ValueAnimator. If we continue to the lerp section below, both
                    // the lerp and the ValueAnimator fight over currentDisplayedAlpha,
                    // causing visual glitches and state corruption.
                    isAnimationRunning = false
                    return
                }
            }

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

                if (autoCalibrationEnabled && firstDeviationTime > 0L && acTargetDuration > 0L) {
                    isAnimationRunning = true
                    overlayViews[0].postOnAnimation(this)
                } else if (currentDisplayedAlpha < 0.5f && alphaZeroSince > 0L) {
                    // Keep VSYNC alive for auto-remove countdown
                    isAnimationRunning = true
                    overlayViews[0].postOnAnimation(this)
                } else {
                    isAnimationRunning = false
                }
            }
        }
    }

    // ── Flag: receiver registered ─────────────────────────────────────────
    private var receiverRegistered = false

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
                                this@StandardOverlayService, it,
                                SensorManager.SENSOR_DELAY_GAME
                            )
                        }
                        wakeLock?.let { if (it.isHeld) it.release() }
                        wakeLock = powerManager?.newWakeLock(
                            PowerManager.PARTIAL_WAKE_LOCK,
                            "Glance::StandardShieldWakeLock"
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
                    // Hibernate: hide overlay, pause sensor, but keep service alive
                    isOverlayShowing = false
                    isRunning = false
                    isCalibrated = false
                    removeOverlayView()
                    sensorManager?.unregisterListener(this@StandardOverlayService)
                    wakeLock?.let { if (it.isHeld) it.release() }
                    Log.d(TAG, "Shield HIBERNATED — overlay removed, sensor paused, wake lock released")
                    notifyTileStateChanged()

                    // Standard service: actually stop the foreground service
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
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
                                this@StandardOverlayService, it,
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
                                this@StandardOverlayService, it,
                                SensorManager.SENSOR_DELAY_GAME
                            )
                        }
                        wakeLock?.let { if (it.isHeld) it.release() }
                        wakeLock = powerManager?.newWakeLock(
                            PowerManager.PARTIAL_WAKE_LOCK,
                            "Glance::StandardShieldWakeLock"
                        )?.apply {
                            acquire(10 * 60 * 1000L)
                        }
                        notifyTileStateChanged()
                    }
                    Log.d(TAG, "Shield RESUMED — autoCalibrate=$autoCalibrate, overlay hidden, isRunning=$isRunning")
                }
                ACTION_SET_OVERLAY_MODE, ACTION_SET_TARGETED_AREA -> {
                    loadSavedConfig()
                    if (isOverlayShowing) {
                        removeOverlayView()
                        createOverlayView()
                    }
                    Log.d(TAG, "Targeted Area / Mode updated dynamically")
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Service Lifecycle — Foreground Service
    // ══════════════════════════════════════════════════════════════════════

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand — action=$action")

        // ── STEP 1: ALWAYS promote to foreground IMMEDIATELY ──────────────
        // This MUST happen within 5 seconds of startForegroundService() call,
        // regardless of which action triggered the start. Failing to do so
        // causes ForegroundServiceDidNotStartInTimeException (fatal crash).
        promoteToForeground(intent)

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

        // ── STEP 2: Initialize system services (idempotent) ───────────────
        initSystemServices()

        // ── STEP 3: Register BroadcastReceiver (idempotent) ───────────────
        registerConfigReceiver()

        // ── STEP 4: Route based on action ─────────────────────────────────
        when (action) {
            ACTION_START_STANDARD_MODE, ACTION_RESUME_SERVICE -> {
                Log.d(TAG, "Starting/Resuming standard shield")
                loadSavedConfig()
                isRunning = true
                val autoCalibrate = intent?.getBooleanExtra(EXTRA_AUTO_CALIBRATE, false) ?: false
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

                rotationSensor?.let {
                    sensorManager?.registerListener(
                        this, it, SensorManager.SENSOR_DELAY_GAME
                    )
                }

                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Glance::StandardWakeLock"
                )?.apply { acquire(10 * 60 * 1000L) }

                notifyTileStateChanged()
                Log.d(TAG, "Standard mode initialized — autoCalibrate=$autoCalibrate, sensor streaming")
            }
            ACTION_STOP_SERVICE -> {
                // If stop was delivered via startForegroundService intent,
                // we already promoted above (safe), now just tear down.
                Log.d(TAG, "STOP action received via onStartCommand — stopping self")
                isOverlayShowing = false
                isRunning = false
                isCalibrated = false
                removeOverlayView()
                sensorManager?.unregisterListener(this)
                wakeLock?.let { if (it.isHeld) it.release() }
                notifyTileStateChanged()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                Log.d(TAG, "Unknown or null action — service promoted to foreground, awaiting commands")
            }
        }
        return START_STICKY
    }

    /**
     * Immediately promotes this service to foreground with a valid Notification.
     * MUST be called within 5 seconds of startForegroundService() to avoid
     * ForegroundServiceDidNotStartInTimeException.
     */
    private fun promoteToForeground(intent: Intent?) {
        createNotificationChannel()
        val notifTitle = intent?.getStringExtra(EXTRA_NOTIFICATION_TITLE)
            ?: "Glance đang hoạt động"
        val notifText = intent?.getStringExtra(EXTRA_NOTIFICATION_TEXT)
            ?: "Chế độ Tiêu chuẩn đang bảo vệ màn hình"
        val notification = buildForegroundNotification(notifTitle, notifText)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
        Log.d(TAG, "promoteToForeground — notification posted")
    }

    /**
     * Builds a minimal foreground notification for the Standard service.
     */
    private fun buildForegroundNotification(title: String, text: String): Notification {
        return Notification.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_favicon)
            .setOngoing(true)
            .build()
    }

    /**
     * Initializes WindowManager, SensorManager, PowerManager, and rotation sensor.
     * Safe to call multiple times — each field is only set if still null.
     */
    private fun initSystemServices() {
        if (windowManager == null) {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }
        if (sensorManager == null) {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        }
        if (powerManager == null) {
            powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        }
        if (rotationSensor == null) {
            rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        }

        // Initialize Auto-Calibration engine (idempotent — gravitySensor null-check inside)
        if (gravitySensor == null) {
            initAutoCalibration()
        }
    }

    /**
     * Registers the BroadcastReceiver for runtime config changes.
     * Safe to call multiple times — only registers once.
     */
    private fun registerConfigReceiver() {
        if (receiverRegistered) return
        try {
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
            }
            registerReceiver(configReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        } catch (_: Exception) {
            // Already registered — ignore
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy — cleaning up")
        isRunning = false
        destroyAutoCalibration()
        if (receiverRegistered) {
            try { unregisterReceiver(configReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        sensorManager?.unregisterListener(this)
        removeOverlayView()
        wakeLock?.let { if (it.isHeld) it.release() }
        notifyTileStateChanged()
        super.onDestroy()
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Notification
    // ══════════════════════════════════════════════════════════════════════

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "Glance Protection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hiển thị khi Glance đang bảo vệ màn hình"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
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
        val outerThreshold = sensorTolerance + hysteresisBand / 2f
        val innerThreshold = (sensorTolerance - hysteresisBand / 2f).coerceAtLeast(1f)

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
    //  Overlay management — TYPE_APPLICATION_OVERLAY (Standard)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Applies alpha value to all overlay views by setting background color.
     */
    /**
     * Applies alpha value to all overlay views by setting background color.
     */
    private fun applyAlphaToOverlay(alpha: Int) {
        if (overlayViews.isEmpty()) return
        val safeAlpha = alpha.coerceIn(0, MAX_ALPHA)
        val shieldColor = android.graphics.Color.argb(safeAlpha, 0, 0, 0)
        overlayViews.forEach { view ->
            view.setBackgroundColor(shieldColor)
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
     * Creates the overlay using TYPE_APPLICATION_OVERLAY.
     * Standard View with background color controlled by applyAlphaToOverlay.
     */
    private fun createOverlayView() {
        if (overlayViews.isNotEmpty()) return
        val wm = windowManager ?: return

        // FIX: Reset alpha về 0 trước khi tạo View, đảm bảo lớp phủ luôn
        // bắt đầu từ trạng thái tàng hình tuyệt đối, triệt tiêu flash.
        currentDisplayedAlpha = 0f

        try {
            loadSavedConfig()

            val isTargeted = overlayMode == "targeted"

            val pxW = if (isTargeted && areaWidth > 0) areaWidth else WindowManager.LayoutParams.MATCH_PARENT
            val pxH = if (isTargeted && areaHeight > 0) areaHeight else WindowManager.LayoutParams.MATCH_PARENT

            val params = WindowManager.LayoutParams(
                pxW,
                pxH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (isTargeted) {
                    x = areaX
                    y = areaY
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    fitInsetsTypes = 0
                }
            }

            val view = View(this).apply {
                setBackgroundColor(android.graphics.Color.argb(0, 0, 0, 0))
                alpha = 1f
            }
            wm.addView(view, params)
            overlayViews.add(view)
            if (!isAnimationRunning && overlayViews.isNotEmpty()) {
                overlayViews[0].postOnAnimation(vsyncRunnable)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create Standard Shield overlay: ${e.message}")
            removeOverlayView()
        }
    }

    private fun removeOverlayView() {
        isAnimationRunning = false
        val wm = windowManager ?: return
        overlayViews.forEach { view ->
            view.removeCallbacks(vsyncRunnable)
            try {
                wm.removeView(view)
            } catch (_: Exception) {}
        }
        overlayViews.clear()
    }

    // ======================================================================
    //  Sensor -> Flutter streaming
    // ======================================================================

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

    /**
     * Initializes auto-calibration: registers gravity sensor & prefs listener.
     * Called from initSystemServices(). Reads kill switch from Flutter SharedPrefs.
     */
    private fun initAutoCalibration() {
        // Read initial state from Flutter SharedPreferences
        val flutterPrefs = getSharedPreferences(FLUTTER_PREFS_NAME, Context.MODE_PRIVATE)
        autoCalibrationEnabled = flutterPrefs.getBoolean(KEY_AUTO_CALIBRATE, false)

        // Listen for kill switch changes from Flutter
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

        // Initialize gravity sensor
        gravitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (autoCalibrationEnabled) {
            startAutoCalibrationSensor()
        }
        Log.d(TAG, "AutoCalibration initialized — enabled=$autoCalibrationEnabled")
    }

    /**
     * Registers the gravity sensor for auto-calibration at SENSOR_DELAY_NORMAL
     * (battery-efficient ~200ms interval).
     */
    private fun startAutoCalibrationSensor() {
        gravitySensor?.let {
            sensorManager?.registerListener(
                autoCalibrationSensorListener, it,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }
        Log.d(TAG, "AutoCalibration sensor STARTED")
    }

    /**
     * Unregisters the gravity sensor and cancels any pending stability timer.
     */
    private fun stopAutoCalibrationSensor() {
        sensorManager?.unregisterListener(autoCalibrationSensorListener)
        cancelAutoCalibrationTimer()
        Log.d(TAG, "AutoCalibration sensor STOPPED — angle frozen")
    }

    /**
     * Cancels the 5-second stability timer if running.
     */
    private fun cancelAutoCalibrationTimer() {
        acStabilityRunnable?.let { acHandler.removeCallbacks(it) }
        acStabilityRunnable = null
        firstDeviationTime = 0L
        acTargetDuration = 0L
        acStableOpacityBucket = -1
    }

    /**
     * Cleans up auto-calibration resources.
     */
    private fun destroyAutoCalibration() {
        stopAutoCalibrationSensor()
        val flutterPrefs = getSharedPreferences(FLUTTER_PREFS_NAME, Context.MODE_PRIVATE)
        acPrefsListener?.let { flutterPrefs.unregisterOnSharedPreferenceChangeListener(it) }
        acPrefsListener = null
    }

    /**
     * Separate SensorEventListener for auto-calibration gravity sensor.
     * Keeps auto-calibration logic isolated from the main rotation sensor.
     */
    private val autoCalibrationSensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null) return
            if (!autoCalibrationEnabled || !isRunning) return
            if (isAnimating) return

            val gx = event.values[0]
            val gy = event.values[1]
            val gz = event.values[2]

            // Compute pitch from gravity vector: atan2(gy, gz) → degrees
            val rawPitch = Math.toDegrees(
                Math.atan2(gy.toDouble(), gz.toDouble())
            ).toFloat()

            // Compute roll from gravity vector: atan2(gx, gz) → degrees
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
                    Log.d(TAG, "AC baseline initialized while disarmed — pitch=${rawPitch}°, roll=${rawRoll}°")
                }
                return
            }

            // Apply low-pass filter (heavy smoothing, alpha=0.1)
            if (!acBaselineInitialized) {
                acSmoothedPitch = rawPitch
                acSmoothedRoll = rawRoll
                acCurrentBaselinePitch = rawPitch
                acCurrentBaselineRoll = rawRoll
                acBaselineInitialized = true
                Log.d(TAG, "AC baseline initialized — pitch=${rawPitch}°, roll=${rawRoll}°")
                return
            }

            acSmoothedPitch += AC_LPF_ALPHA * (rawPitch - acSmoothedPitch)
            acSmoothedRoll += AC_LPF_ALPHA * (rawRoll - acSmoothedRoll)

            // Dead Zone: Only process if pitch is within 10°–170° (normal holding range)
            val absPitch = Math.abs(acSmoothedPitch)
            if (absPitch < AC_PITCH_MIN || absPitch > AC_PITCH_MAX) {
                cancelAutoCalibrationTimer()
                return
            }

            // ── BƯỚC 2.1: FIX Timer Measurement — Use targetAlpha for INSTANT classification ──
            // PROBLEM: currentDisplayedAlpha is delayed by lerp, causing wrong timer duration.
            // SOLUTION: Use targetAlpha which reflects INSTANT sensor state (no lerp delay).
            // Alpha scale: 0-255. Classification logic:
            //   • targetAlpha <= 0 (transparent, no overlay) → reset timer and skip
            //   • targetAlpha 1-102 (thin overlay, up to 40%) → 1.5s needed
            //   • targetAlpha > 102 (thick overlay, over 40%) → 5s needed
            val alphaSnapshot = targetAlpha
            val opacityRatio = (alphaSnapshot / MAX_ALPHA).coerceIn(0f, 1f)
            val opacityBucket = when {
                opacityRatio < AC_MIN_OPACITY_RATIO -> 0
                opacityRatio < AC_FAST_OPACITY_RATIO -> 1
                else -> 2
            }
            if (opacityBucket == 0) {
                // Transparent state (no overlay) — reset timer and skip
                firstDeviationTime = 0L
                acTargetDuration = 0L
                acStableOpacityBucket = -1
                return
            }
            val targetDuration: Long = if (opacityBucket == 1) {
                AC_FAST_STABILITY_DURATION
            } else {
                AC_STABILITY_DURATION
            }

            // ── ANGLE DEVIATION CHECK (only runs if opacity > ~2%) ──
            val pitchDelta = Math.abs(normalizeAngle(acSmoothedPitch - acCurrentBaselinePitch))
            val rollDelta = Math.abs(normalizeAngle(acSmoothedRoll - acCurrentBaselineRoll))
            val totalDelta = Math.hypot(pitchDelta.toDouble(), rollDelta.toDouble()).toFloat()
            // Only proceed if deviation exceeds threshold (40° for major posture changes)
            if (alphaSnapshot > 0f) {
                // Begin timestamp detection (VSYNC will check for completion)
                val pitchSnapshotDrift = Math.abs(normalizeAngle(acSmoothedPitch - acStableTargetPitch))
                val rollSnapshotDrift = Math.abs(normalizeAngle(acSmoothedRoll - acStableTargetRoll))
                val bucketChanged = acStableOpacityBucket != opacityBucket || acTargetDuration != targetDuration
                val angleDrifted = pitchSnapshotDrift > AC_SNAPSHOT_ANGLE_TOLERANCE || rollSnapshotDrift > AC_SNAPSHOT_ANGLE_TOLERANCE
                if (firstDeviationTime == 0L || bucketChanged || angleDrifted) {
                    firstDeviationTime = System.currentTimeMillis()
                    acTargetDuration = targetDuration
                    acStableTargetPitch = acSmoothedPitch
                    acStableTargetRoll = acSmoothedRoll
                    acStableOpacityBucket = opacityBucket
                    ensureVsyncRunning()
                    Log.d(TAG, "AC timer STARTED — opacity=${alphaSnapshot.toInt()}, targetDuration=${targetDuration}ms, deviation=${totalDelta}°")
                }
                // ── BƯỚC 2.2: Timer check REMOVED from sensor callback ──
                // The VSYNC loop now handles checking if timer expired (60fps accuracy).
                // Sensor callback only STARTS the timer when angle deviates.
            } else {
                // Reset timer when angle returns within tolerance (< 40° deviation)
                if (firstDeviationTime != 0L) {
                    Log.d(TAG, "AC timer RESET — angle returned to tolerance (delta=${totalDelta}°)")
                }
                firstDeviationTime = 0L
                acTargetDuration = 0L
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

        // Step 2: Reset deviation timer
        firstDeviationTime = 0L

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
                    currentDisplayedAlpha = 0f
                    targetAlpha = 0f
                    isAnimating = false
                    needsBaselineReset = true
                    firstDeviationTime = 0L
                    acTargetDuration = 0L
                    // ── CRITICAL: Re-sync AC Gravity baseline to CURRENT smoothed angle ──
                    // During the 1.5s fade animation, the device may have moved further.
                    // Without this, the Gravity sensor sees stale baseline → computes wrong
                    // delta → immediately starts a new timer → AC appears "stuck/one-shot".
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
