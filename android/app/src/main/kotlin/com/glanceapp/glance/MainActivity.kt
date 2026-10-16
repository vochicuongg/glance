package com.glanceapp.glance

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.NonNull
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.android.FlutterActivityLaunchConfigs.BackgroundMode
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

/// ─────────────────────────────────────────────────────────────────────────────
/// MainActivity — Dual-Engine Architecture
/// ─────────────────────────────────────────────────────────────────────────────
/// Flutter host Activity that bridges Flutter UI commands to the native
/// overlay services via MethodChannel + Broadcasts.
///
/// DUAL-ENGINE ROUTING:
///   • Standard mode → StandardOverlayService (regular Service, TYPE_APPLICATION_OVERLAY)
///     Only needs Overlay permission. Banking apps work normally.
///   • Maximum mode  → MaxOverlayService (AccessibilityService, TYPE_ACCESSIBILITY_OVERLAY)
///     Needs both Accessibility + Overlay. Darker but banking apps blocked.
///
/// The mode is read from Flutter SharedPreferences ("flutter.protection_mode").
/// ─────────────────────────────────────────────────────────────────────────────
class MainActivity : FlutterActivity() {

    companion object {
        private const val TAG = "GlanceMainActivity"

        /// Channel name — must match the Dart-side GlanceChannelService.
        private const val CHANNEL = "com.glanceapp.glance/overlay"

        /// EventChannel name for real-time sensor data streaming to Flutter.
        private const val SENSOR_STREAM_CHANNEL = "com.glanceapp.glance/sensor_stream"

    }

    override fun getBackgroundMode(): BackgroundMode {
        return BackgroundMode.transparent
    }

    private var methodChannel: MethodChannel? = null
    private var sensorEventChannel: EventChannel? = null

    private var pendingShowModeSelectionMenu = false

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        handleQuickSettingsPreferencesIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleQuickSettingsPreferencesIntent(intent)
    }

    // ── Helper: Read protection mode from Flutter SharedPreferences ────────
    private fun getProtectionMode(): String {
        val flutterPrefs = getSharedPreferences(
            "FlutterSharedPreferences", Context.MODE_PRIVATE
        )
        return flutterPrefs.getString(
            "flutter.protection_mode", "maximum"
        ) ?: "maximum"
    }

    // ── Helper: Check if EITHER service is currently running ───────────────
    private fun isAnyServiceRunning(): Boolean {
        return MaxOverlayService.isRunning || StandardOverlayService.isRunning
    }

    // ── Helper: Get the active sensor event sink from whichever service ────
    private fun getActiveSensorEventSink(): io.flutter.plugin.common.EventChannel.EventSink? {
        return MaxOverlayService.sensorEventSink ?: StandardOverlayService.sensorEventSink
    }

    private fun setActiveSensorEventSink(sink: io.flutter.plugin.common.EventChannel.EventSink?) {
        val mode = getProtectionMode()
        if (mode == "standard") {
            StandardOverlayService.sensorEventSink = sink
        } else {
            MaxOverlayService.sensorEventSink = sink
        }
    }

    // ── Helper: Get isCalibrated from whichever service is relevant ────────
    private fun getIsCalibrated(): Boolean {
        val mode = getProtectionMode()
        return if (mode == "standard") {
            StandardOverlayService.isCalibrated
        } else {
            MaxOverlayService.isCalibrated
        }
    }

    // ── Flutter Engine Configuration ──────────────────────────────────────

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        methodChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL
        )

        methodChannel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "startService"    -> {
                    val notifTitle = call.argument<String>("notificationTitle")
                    val notifText  = call.argument<String>("notificationText")
                    handleStartService(result, notifTitle, notifText)
                }
                "stopService"     -> handleStopService(result)
                "calibrate"       -> handleCalibrate(result)
                "setSensitivity"  -> {
                    val value = call.argument<Double>("value") ?: 0.5
                    handleSetSensitivity(value, result)
                }
                "setOverlayMode"  -> {
                    val mode = call.argument<String>("mode") ?: "fullscreen"
                    handleSetOverlayMode(mode, result)
                }
                "setTargetedArea" -> {
                    val x      = call.argument<Int>("x") ?: 0
                    val y      = call.argument<Int>("y") ?: 0
                    val width  = call.argument<Int>("width") ?: 0
                    val height = call.argument<Int>("height") ?: 0
                    handleSetTargetedArea(x, y, width, height, result)
                }
                "setIntensity" -> {
                    val intensity = call.argument<Double>("intensity") ?: 0.8
                    handleSetIntensity(intensity, result)
                }
                "setTolerance" -> {
                    val tolerance = call.argument<Double>("tolerance") ?: 5.0
                    handleSetTolerance(tolerance, result)
                }
                "isServiceRunning" -> {
                    result.success(isAnyServiceRunning())
                }
                "isAccessibilityEnabled" -> {
                    val isEnabled = isAccessibilityServiceEnabled(this, MaxOverlayService::class.java)
                    result.success(isEnabled)
                }
                "openAccessibilitySettings" -> {
                    openAccessibilitySettings()
                    result.success(true)
                }
                "openAppDetails" -> {
                    openAppDetails()
                    result.success(true)
                }
                "revokeAccessibility" -> {
                    // ── Self-destruct: send REVOKE broadcast to MaxOverlayService ──
                    sendBroadcast(Intent(MaxOverlayService.ACTION_REVOKE_ACCESSIBILITY).apply {
                        setPackage(packageName)
                    })
                    Log.d(TAG, "revokeAccessibility — broadcast sent to MaxOverlayService")
                    result.success(true)
                }
                "isOverlayPermissionGranted" -> {
                    result.success(Settings.canDrawOverlays(this))
                }
                "openOverlaySettings" -> {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${packageName}")
                ).apply {
                    // Prevent Settings from remaining in back stack
                    addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                }
                startActivity(intent)
                    result.success(true)
                }
                "getSettingsFromNative" -> {
                    val prefs = getSharedPreferences("GlanceNativePrefs", Context.MODE_PRIVATE)
                    val opacity = prefs.getFloat("opacity", 0.8f).toDouble()
                    val tolerance = prefs.getFloat("tolerance", 5.0f).toDouble()
                    val sensitivity = prefs.getFloat("sensitivity", 0.5f).toDouble()
                    val isCalibrated = getIsCalibrated()

                    result.success(mapOf(
                        "opacity" to opacity,
                        "tolerance" to tolerance,
                        "sensitivity" to sensitivity,
                        "isCalibrated" to isCalibrated
                    ))
                    Log.d(TAG, "Settings read from native: opacity=$opacity, tolerance=$tolerance, sensitivity=$sensitivity, isCalibrated=$isCalibrated")
                }
                "getLaunchableApps" -> {
                    result.success(getLaunchableApps())
                }
                "hasUsageAccess" -> {
                    result.success(hasUsageAccess())
                }
                "openUsageAccessSettings" -> {
                    try {
                        startActivity(
                            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                        result.success(true)
                    } catch (e: Exception) {
                        result.success(false)
                    }
                }
                "isNotificationAccessEnabled" -> {
                    val enabled = Settings.Secure.getString(
                        contentResolver, "enabled_notification_listeners"
                    ) ?: ""
                    result.success(enabled.contains(packageName))
                }
                "openNotificationAccessSettings" -> {
                    try {
                        startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                        result.success(true)
                    } catch (e: Exception) {
                        result.success(false)
                    }
                }
                "saveSettingsToNative" -> {
                    val opacity = call.argument<Double>("opacity") ?: 1.0
                    val tolerance = call.argument<Double>("tolerance") ?: 5.0
                    val sensitivity = call.argument<Double>("sensitivity") ?: 0.5

                    val prefs = getSharedPreferences("GlanceNativePrefs", Context.MODE_PRIVATE)
                    prefs.edit().apply {
                        putFloat("opacity", opacity.toFloat())
                        putFloat("tolerance", tolerance.toFloat())
                        putFloat("sensitivity", sensitivity.toFloat())
                        apply()
                    }
                    Log.d(TAG, "Settings saved to native: opacity=$opacity, tolerance=$tolerance, sensitivity=$sensitivity")

                    // Signal running Service to reload settings via broadcast
                    if (isAnyServiceRunning()) {
                        // Send to BOTH services — only the running one will process it
                        sendBroadcast(Intent(MaxOverlayService.ACTION_UPDATE_CONFIG).apply {
                            setPackage(packageName)
                            putExtra(MaxOverlayService.EXTRA_SENSITIVITY, sensitivity.toFloat())
                            putExtra(MaxOverlayService.EXTRA_TOLERANCE, tolerance.toFloat())
                        })
                        sendBroadcast(Intent(StandardOverlayService.ACTION_UPDATE_CONFIG).apply {
                            setPackage(packageName)
                            putExtra(StandardOverlayService.EXTRA_SENSITIVITY, sensitivity.toFloat())
                            putExtra(StandardOverlayService.EXTRA_TOLERANCE, tolerance.toFloat())
                        })
                        Log.d(TAG, "Config broadcast sent to running Service(s)")
                    }

                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }

        Log.d(TAG, "MethodChannel '$CHANNEL' configured")
        flushPendingQuickSettingsAction()

        // ── EventChannel for real-time sensor data streaming ──────────────
        sensorEventChannel = EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            SENSOR_STREAM_CHANNEL
        )
        sensorEventChannel?.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                // Attach to both services — only the active one matters
                MaxOverlayService.sensorEventSink = events
                StandardOverlayService.sensorEventSink = events
                Log.d(TAG, "Sensor stream: Flutter listener attached")
            }

            override fun onCancel(arguments: Any?) {
                MaxOverlayService.sensorEventSink = null
                StandardOverlayService.sensorEventSink = null
                Log.d(TAG, "Sensor stream: Flutter listener detached")
            }
        })
        Log.d(TAG, "EventChannel '$SENSOR_STREAM_CHANNEL' configured")
    }

    override fun cleanUpFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        methodChannel?.setMethodCallHandler(null)
        methodChannel = null

        MaxOverlayService.sensorEventSink = null
        StandardOverlayService.sensorEventSink = null
        sensorEventChannel?.setStreamHandler(null)
        sensorEventChannel = null

        super.cleanUpFlutterEngine(flutterEngine)
    }

    // ── Command Handlers ──────────────────────────────────────────────────

    /**
     * Handles the "startService" command from Flutter (Connected switch).
     *
     * DUAL-ENGINE ROUTING:
     *   • Standard mode: Only checks Overlay permission, starts StandardOverlayService
     *     as a foreground service. No Accessibility needed.
     *   • Maximum mode: Requires Accessibility enabled. If not, guides user to settings.
     *     Uses MaxOverlayService (AccessibilityService).
     */
    private fun handleStartService(
        result: MethodChannel.Result,
        notifTitle: String? = null,
        notifText: String? = null
    ) {
        val protectionMode = getProtectionMode()
        Log.d(TAG, "handleStartService — protection_mode=$protectionMode")

        if (protectionMode == "standard") {
            // ── Standard mode: Only overlay permission needed ──────────────
            if (!Settings.canDrawOverlays(this)) {
                Log.d(TAG, "Standard mode — overlay permission missing, opening settings...")
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${packageName}")
                ).apply {
                    // Prevent Settings activity from remaining in back stack
                    addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                }
                startActivity(intent)
                result.error(
                    "OVERLAY_NOT_GRANTED",
                    "Please grant 'Display over other apps' permission.",
                    null
                )
                return
            }

            // ── Standard mode: explicitly start StandardOverlayService ─────
            Log.d(TAG, "Standard mode — overlay permission OK, starting StandardOverlayService")

            val serviceIntent = Intent(this, StandardOverlayService::class.java).apply {
                action = StandardOverlayService.ACTION_START_STANDARD_MODE
                putExtra(StandardOverlayService.EXTRA_NOTIFICATION_TITLE,
                    notifTitle ?: "Glance đang bảo vệ")
                putExtra(StandardOverlayService.EXTRA_NOTIFICATION_TEXT,
                    notifText ?: "Chế độ tiêu chuẩn đang hoạt động")
            }
            ContextCompat.startForegroundService(this, serviceIntent)

            result.success(true)
            return
        }

        // ── Maximum mode: Accessibility required ──────────────────────────
        if (MaxOverlayService.isAccessibilityEnabled(this)) {
            Log.d(TAG, "Max mode — Accessibility enabled, service bound (no auto-resume)")

            // Start sensor streaming for Beta/Gamma bars
            sendBroadcast(Intent(MaxOverlayService.ACTION_START_SENSOR_ONLY).apply {
                setPackage(packageName)
            })

            result.success(true)
            return
        }

        // Not enabled — guide user to Accessibility Settings
        Log.d(TAG, "Max mode — Accessibility not enabled, opening settings...")
        openAccessibilitySettings()
        result.success(true)
    }

    /**
     * Handles the "stopService" command from Flutter.
     *
     * Sends stop/hibernate broadcast to BOTH services to ensure clean shutdown
     * regardless of which mode is active.
     */
    private fun handleStopService(result: MethodChannel.Result) {
        // Send stop broadcast to BOTH services — only the active one will respond
        sendBroadcast(Intent(MaxOverlayService.ACTION_STOP_SERVICE).apply {
            setPackage(packageName)
        })
        sendBroadcast(Intent(StandardOverlayService.ACTION_STOP_SERVICE).apply {
            setPackage(packageName)
        })

        // For Standard mode, also explicitly stop the foreground service
        if (StandardOverlayService.isRunning) {
            stopService(Intent(this, StandardOverlayService::class.java))
        }

        Log.d(TAG, "Stop broadcast sent to both services")
        result.success(true)
    }

    /**
     * Sends a calibration command via broadcast to the running service.
     */
    private fun handleCalibrate(result: MethodChannel.Result) {
        // Send to both — only the active one processes it
        sendBroadcast(Intent(MaxOverlayService.ACTION_CALIBRATE).apply {
            setPackage(packageName)
        })
        sendBroadcast(Intent(StandardOverlayService.ACTION_CALIBRATE).apply {
            setPackage(packageName)
        })
        Log.d(TAG, "Calibrate broadcast sent to service(s)")
        result.success(true)
    }

    /**
     * Sends the updated sensitivity value via broadcast to the running service.
     */
    private fun handleSetSensitivity(value: Double, result: MethodChannel.Result) {
        sendBroadcast(Intent(MaxOverlayService.ACTION_SET_SENSITIVITY).apply {
            setPackage(packageName)
            putExtra(MaxOverlayService.EXTRA_SENSITIVITY, value.toFloat())
        })
        sendBroadcast(Intent(StandardOverlayService.ACTION_SET_SENSITIVITY).apply {
            setPackage(packageName)
            putExtra(StandardOverlayService.EXTRA_SENSITIVITY, value.toFloat())
        })
        Log.d(TAG, "Sensitivity broadcast: $value")
        result.success(true)
    }

    /**
     * Sends the overlay mode via broadcast to the running service.
     */
    private fun handleSetOverlayMode(mode: String, result: MethodChannel.Result) {
        val nativePrefs = getSharedPreferences("GlanceNativePrefs", Context.MODE_PRIVATE)
        nativePrefs.edit()
            .putString("overlay_mode", mode)
            .apply()
        sendBroadcast(Intent(MaxOverlayService.ACTION_SET_OVERLAY_MODE).apply {
            setPackage(packageName)
            putExtra(MaxOverlayService.EXTRA_MODE, mode)
        })
        sendBroadcast(Intent(StandardOverlayService.ACTION_SET_OVERLAY_MODE).apply {
            setPackage(packageName)
            putExtra(StandardOverlayService.EXTRA_MODE, mode)
        })
        Log.d(TAG, "Overlay mode broadcast: $mode")
        result.success(true)
    }

    /**
     * Sends the targeted area coordinates via broadcast to the running service.
     */
    private fun handleSetTargetedArea(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        result: MethodChannel.Result
    ) {
        // Persist the targeted area coordinates to shared preferences BEFORE broadcasting.
        // The overlay services load these values from the same prefs in loadSavedConfig().
        val nativePrefs = getSharedPreferences("GlanceNativePrefs", Context.MODE_PRIVATE)
        nativePrefs.edit()
            .putInt("area_x", x)
            .putInt("area_y", y)
            .putInt("area_width", width)
            .putInt("area_height", height)
            .apply()

        sendBroadcast(Intent(MaxOverlayService.ACTION_SET_TARGETED_AREA).apply {
            setPackage(packageName)
            putExtra(MaxOverlayService.EXTRA_AREA_X, x)
            putExtra(MaxOverlayService.EXTRA_AREA_Y, y)
            putExtra(MaxOverlayService.EXTRA_AREA_WIDTH, width)
            putExtra(MaxOverlayService.EXTRA_AREA_HEIGHT, height)
        })
        sendBroadcast(Intent(StandardOverlayService.ACTION_SET_TARGETED_AREA).apply {
            setPackage(packageName)
            putExtra(StandardOverlayService.EXTRA_AREA_X, x)
            putExtra(StandardOverlayService.EXTRA_AREA_Y, y)
            putExtra(StandardOverlayService.EXTRA_AREA_WIDTH, width)
            putExtra(StandardOverlayService.EXTRA_AREA_HEIGHT, height)
        })
        Log.d(TAG, "Targeted area broadcast: x=$x, y=$y, w=$width, h=$height")
        result.success(true)
    }

    /**
     * Sends the overlay intensity via broadcast to the running service.
     */
    private fun handleSetIntensity(intensity: Double, result: MethodChannel.Result) {
        sendBroadcast(Intent(MaxOverlayService.ACTION_SET_INTENSITY).apply {
            setPackage(packageName)
            putExtra(MaxOverlayService.EXTRA_INTENSITY, intensity.toFloat())
        })
        sendBroadcast(Intent(StandardOverlayService.ACTION_SET_INTENSITY).apply {
            setPackage(packageName)
            putExtra(StandardOverlayService.EXTRA_INTENSITY, intensity.toFloat())
        })
        Log.d(TAG, "Intensity broadcast: $intensity")
        result.success(true)
    }

    /**
     * Sends the tolerance value via broadcast to the running service.
     */
    private fun handleSetTolerance(tolerance: Double, result: MethodChannel.Result) {
        sendBroadcast(Intent(MaxOverlayService.ACTION_SET_TOLERANCE).apply {
            setPackage(packageName)
            putExtra(MaxOverlayService.EXTRA_TOLERANCE, tolerance.toFloat())
        })
        sendBroadcast(Intent(StandardOverlayService.ACTION_SET_TOLERANCE).apply {
            setPackage(packageName)
            putExtra(StandardOverlayService.EXTRA_TOLERANCE, tolerance.toFloat())
        })
        Log.d(TAG, "Tolerance broadcast: $tolerance°")
        result.success(true)
    }

    // ── Accessibility Helper ────────────────────────────────────────────────

    /**
     * Robust helper to check if a specific AccessibilityService is enabled.
     */
    private fun isAccessibilityServiceEnabled(
        context: Context,
        accessibilityService: Class<*>
    ): Boolean {
        val expectedComponentName = android.content.ComponentName(context, accessibilityService)
        val enabledServicesSetting = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = android.content.ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService == expectedComponentName) {
                return true
            }
        }
        return false
    }

    // ── Accessibility Settings ─────────────────────────────────────────────

    /**
     * Opens the Android Accessibility Settings screen so the user can
     * enable/disable the MaxOverlayService.
     *
     * Uses NO_HISTORY flag to prevent Settings from creating synthetic back stack.
     */
    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                
                // Deep-link nhảy thẳng vào trang cấu hình Trợ năng của Glance
                val componentName = android.content.ComponentName(packageName, MaxOverlayService::class.java.name).flattenToString()
                putExtra(":settings:fragment_args_key", componentName)
                putExtra(":settings:show_fragment_args", android.os.Bundle().apply {
                    putString(":settings:fragment_args_key", componentName)
                })
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Deep-link Accessibility failed, falling back to default.", e)
            val fallbackIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(fallbackIntent)
        }
    }

    private fun openAppDetails() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        ).apply {
            // Prevent the details screen from lingering in the back stack
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
        }
        startActivity(intent)
    }

    // ── Privacy Display helpers (Glance 2.0) ───────────────────────────

    /**
     * Lists user-facing launchable apps for the protected-apps picker.
     * Uses a launcher-intent query, so no QUERY_ALL_PACKAGES permission
     * is needed; the matching <queries> block lives in the Manifest.
     */
    private fun getLaunchableApps(): List<Map<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val pm = packageManager
        val resolved = pm.queryIntentActivities(intent, 0)
        val seen = mutableSetOf<String>()
        val apps = mutableListOf<Map<String, String>>()
        for (info in resolved) {
            val pkg = info.activityInfo.packageName
            if (pkg == packageName) continue
            if (!seen.add(pkg)) continue
            val label = try {
                info.loadLabel(pm).toString()
            } catch (_: Exception) {
                pkg
            }
            apps.add(mapOf("package" to pkg, "label" to label))
        }
        return apps.sortedBy { it["label"]?.lowercase() ?: "" }
    }

    /** True when the user has granted Glance "Usage access". */
    private fun hasUsageAccess(): Boolean {
        return try {
            val appOps = getSystemService(
                android.content.Context.APP_OPS_SERVICE
            ) as android.app.AppOpsManager
            appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            ) == android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    private fun handleQuickSettingsPreferencesIntent(intent: Intent?) {
        if (intent?.action != "android.service.quicksettings.action.QS_TILE_PREFERENCES") {
            return
        }
        pendingShowModeSelectionMenu = true
        flushPendingQuickSettingsAction()
    }

    private fun flushPendingQuickSettingsAction() {
        val channel = methodChannel ?: return
        if (!pendingShowModeSelectionMenu) return
        pendingShowModeSelectionMenu = false
        channel.invokeMethod("showModeSelectionMenu", null)
        Log.d(TAG, "QS_TILE_PREFERENCES — requested Flutter mode menu")
    }
}
