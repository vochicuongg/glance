import 'dart:async';
import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../../core/localization/app_strings.dart';
import '../../../core/localization/locale_provider.dart';
import '../../../core/theme/app_colors.dart';
import '../../../core/services/glance_channel_service.dart';
import '../widgets/shield_status_card.dart';
import '../widgets/sensitivity_slider_card.dart';
import '../widgets/calibrate_card.dart';
import '../widgets/tolerance_slider_card.dart';
import '../widgets/overlay_mode_card.dart';
import '../../permissions/screens/permission_screen.dart';
import '../../privacy_display/privacy_display_screen.dart';
import 'settings_screen.dart';
import 'targeted_area_editor.dart';

/// ─────────────────────────────────────────────────────────────────────────────
/// Dashboard Screen
/// ─────────────────────────────────────────────────────────────────────────────
/// The main screen of the Glance app. Composes all dashboard widgets into a
/// clean, breathable layout with ample dark-space.
///
/// State management: Uses plain StatefulWidget for simplicity in Step 1.
/// Can be migrated to Riverpod/Bloc in later steps if needed.
///
/// Layout (top to bottom):
///   1. AppBar with brand identity
///   2. ShieldStatusCard — hero protection toggle (tap to switch mode)
///   3. Real-time sensor bars (Beta / Gamma)
///   4. SensitivitySliderCard + ToleranceSliderCard
///   5. AutoPostureToggle — "Tối ưu góc nhìn" (posture adaptation)
///   6. OverlayModeCard — fullscreen / targeted toggle
///   7. Privacy Display features hub card
///   8. CalibrateCard — pinned bottom bar
///
/// Settings (gear icon) holds only app-level preferences:
/// theme, language, about.
/// ─────────────────────────────────────────────────────────────────────────────
class DashboardScreen extends StatefulWidget {
  const DashboardScreen({super.key});

  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen>
    with WidgetsBindingObserver {
  // ── State ───────────────────────────────────────────────────────────────
  bool _isServiceActive = false;
  bool _isCalibrated = false;
  double _sensitivity = 0.5; // Default: Medium
  double _tolerance = 5.0; // Default: 5° hysteresis dead zone
  bool _isTargetedMode = false; // false = fullscreen, true = targeted
  String _protectionMode = 'maximum';

  // ── Service ─────────────────────────────────────────────────────────────
  final _channelService = GlanceChannelService();

  // ── Lifecycle ───────────────────────────────────────────────────────────

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    GlanceChannelService.channel.setMethodCallHandler(_handleNativeMethodCall);
    _syncServiceState();
    _loadSavedSettings(); // Restore sliders from Native SharedPreferences
  }

  Future<dynamic> _handleNativeMethodCall(MethodCall call) async {
    if (call.method == 'showModeSelectionMenu' && mounted) {
      await _showModeSelectionMenu();
    }
  }

  /// Reads the user's last-saved tolerance & sensitivity from Native
  /// SharedPreferences and applies them to the UI slider state.
  ///
  /// This prevents the "lost memory" bug where killing the app caused
  /// sliders to reset to hardcoded defaults.
  Future<void> _loadSavedSettings() async {
    final settings = await GlanceChannelService.getSettingsFromNative();
    final prefs = await SharedPreferences.getInstance();
    if (mounted) {
      setState(() {
        _tolerance = settings['tolerance'] ?? 5.0;
        _sensitivity = settings['sensitivity'] ?? 0.5;
        _protectionMode = prefs.getString('protection_mode') ?? 'maximum';
        // Only sync isCalibrated from Native if Service IS RUNNING
        if (_isServiceActive) {
          _isCalibrated = settings['isCalibrated'] ?? false;
        }
      });
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _syncServiceState();
    }
  }

  /// Queries the native side to check if GlanceOverlayService is already
  /// running (e.g. started via Quick Settings Tile before the app was opened).
  ///
  /// If the service IS running, we:
  ///   1. Set [_isServiceActive] = true so the UI shows the active state.
  ///   2. Force the sensor stream cache to refresh so [StreamBuilder] in
  ///      [_buildSensorBars] immediately starts receiving beta/gamma data.
  ///   3. Sync `isActive` to SharedPreferences so the Quick Settings Tile
  ///      always reads the correct state (fixes Dart↔Kotlin phase mismatch).
  ///
  /// The method is fire-and-forget from [initState] but uses [mounted]
  /// guard to avoid calling [setState] after the widget is disposed.
  Future<void> _syncServiceState() async {
    try {
      final isRunning = await GlanceChannelService.isServiceRunning();
      if (!mounted) return;
      if (isRunning != _isServiceActive) {
        setState(() {
          _isServiceActive = isRunning;
          if (!isRunning) _isCalibrated = false;
        });
      }

      // ── Sync isActive to SharedPreferences for Quick Settings Tile ──
      // The Tile reads `flutter.isActive` to determine toggle state.
      // Flutter must write this key so the Tile stays in phase.
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('isActive', isRunning);

      if (isRunning) {
        // Force the sensor stream to re-establish so the Beta/Gamma
        // bars update immediately. Clearing the cache causes the next
        // access to sensorStream to create a fresh broadcast stream
        // which re-attaches the native EventChannel listener.
        GlanceChannelService.resetSensorStreamCache();
      }
    } catch (_) {
      // Silently ignore — if the native side isn't ready yet, the UI
      // stays in the default inactive state. No crash risk.
    }
  }

  // ── Handlers ────────────────────────────────────────────────────────────

  /// Toggle the overlay service on/off.
  ///
  /// Uses optimistic UI update for snappy feel, then reverts if the
  /// native call fails. Special handling for PERMISSION_DENIED:
  /// shows a premium Dark/Gold dialog explaining the permission need.
  ///
  /// **Pre-flight check (v1.1):**
  /// Before attempting to start the service, we proactively verify that
  /// both Accessibility Service and Overlay (SYSTEM_ALERT_WINDOW) permissions
  /// are still granted. If either is missing, the toggle is immediately
  /// reverted and the user is redirected to PermissionScreen to re-grant.
  /// This prevents the UX deadlock where lifecycle events fail to trigger
  /// after the user revokes permissions from system Settings.
  Future<void> _handleToggleService(bool value) async {
    // ── Pre-flight permission check (only when turning ON) ────────────
    if (value) {
      final prefs = await SharedPreferences.getInstance();
      final protectionMode = prefs.getString('protection_mode') ?? 'maximum';

      final accessibility = await GlanceChannelService.isAccessibilityEnabled();
      final overlay = await GlanceChannelService.isOverlayPermissionGranted();
      final battery = await Permission.ignoreBatteryOptimizations.isGranted;

      // Standard mode: overlay + battery; Maximum mode: accessibility + overlay + battery
      final bool permissionsMissing;
      if (protectionMode == 'standard') {
        permissionsMissing = !overlay || !battery;
      } else {
        permissionsMissing = !accessibility || !overlay || !battery;
      }

      if (permissionsMissing) {
        // Ensure toggle stays OFF
        setState(() => _isServiceActive = false);

        if (!mounted) return;

        // Show feedback SnackBar
        final strings = LocaleProvider.stringsOf(context);
        _showSnackBar(strings.permGrantRequired);

        // Redirect to PermissionScreen to re-grant missing permissions
        Navigator.of(context).pushReplacement(
          MaterialPageRoute(builder: (_) => const PermissionScreen()),
        );
        return;
      }
    }

    // Optimistic UI update for snappy feel
    setState(() => _isServiceActive = value);

    try {
      if (value) {
        // Pass localized notification strings so the foreground
        // notification displays in the user's current language.
        if (!mounted) return;
        final strings = LocaleProvider.stringsOf(context);
        await _channelService.startService(
          notificationTitle: strings.notificationTitle,
          notificationText: strings.notificationText,
        );
      } else {
        await _channelService.stopService();
        // Reset calibration status when service stops
        setState(() => _isCalibrated = false);
      }

      // ── Sync isActive to SharedPreferences for Quick Settings Tile ──
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('isActive', value);
    } on GlanceServiceException catch (e) {
      // Revert the toggle on failure
      setState(() => _isServiceActive = false);

      if (!mounted) return;

      // Check if it's a permission error (from Kotlin PERMISSION_DENIED)
      if (e.message.contains('PERMISSION_DENIED') ||
          e.message.contains('permission')) {
        _showPermissionDeniedDialog();
      } else {
        _showSnackBar(e.message);
      }
    }
  }

  /// Calibrate the baseline viewing angle.
  Future<void> _handleCalibrate() async {
    try {
      await _channelService.calibrate();
      setState(() => _isCalibrated = true);
      if (mounted) {
        _showSnackBar(
          LocaleProvider.stringsOf(context).calibrateSuccess,
          isError: false,
        );
      }
    } on GlanceServiceException catch (e) {
      if (mounted) {
        _showSnackBar(e.message);
      }
    }
  }

  /// Update sensitivity slider value (UI only — continuous drag).
  void _handleSensitivityChanged(double value) {
    setState(() => _sensitivity = value);
  }

  /// Send final sensitivity value to native service (on drag end).
  Future<void> _handleSensitivityChangeEnd(double value) async {
    try {
      await _channelService.setSensitivity(value);
      GlanceChannelService.saveSettingsToNative(0.8, _tolerance, value);
    } on GlanceServiceException catch (e) {
      if (mounted) {
        _showSnackBar(e.message);
      }
    }
  }

  /// Update tolerance slider value (UI only — continuous drag).
  void _handleToleranceChanged(double value) {
    setState(() => _tolerance = value);
  }

  /// Send final tolerance value to native service (on drag end).
  /// Also persists to native SharedPreferences for Quick Settings Tile.
  Future<void> _handleToleranceChangeEnd(double value) async {
    try {
      await _channelService.setTolerance(value);
      // Persist to native SharedPreferences so Quick Settings Tile
      // can read the user's configured tolerance when launching the service.
      GlanceChannelService.saveSettingsToNative(0.8, value, _sensitivity);
    } on GlanceServiceException catch (e) {
      if (mounted) {
        _showSnackBar(e.message);
      }
    }
  }

  /// Switches overlay mode between fullscreen and targeted.
  ///
  /// Sends the mode change to the native service via MethodChannel.
  /// On failure, reverts the toggle and shows an error.
  Future<void> _handleOverlayModeChanged(bool isTargeted) async {
    final previousMode = _isTargetedMode;
    setState(() => _isTargetedMode = isTargeted);

    try {
      await _channelService.setOverlayMode(
        isTargeted ? 'targeted' : 'fullscreen',
      );
    } on GlanceServiceException catch (e) {
      // Revert on failure
      setState(() => _isTargetedMode = previousMode);
      if (mounted) {
        _showSnackBar(e.message);
      }
    }
  }

  /// Opens the TargetedAreaEditor screen where the user can drag/resize
  /// the protected area rectangle.
  void _handleDefineArea() {
    Navigator.of(context).push(
      PageRouteBuilder(
        opaque:
            false, // Allow previous route to show through (transparent background)
        pageBuilder: (context, animation, secondaryAnimation) {
          return const TargetedAreaEditor();
        },
        transitionsBuilder: (context, animation, secondaryAnimation, child) {
          // Smooth fade + slide-up transition matching premium feel
          return FadeTransition(
            opacity: CurvedAnimation(
              parent: animation,
              curve: Curves.easeOutCubic,
            ),
            child: SlideTransition(
              position:
                  Tween<Offset>(
                    begin: const Offset(0, 0.05),
                    end: Offset.zero,
                  ).animate(
                    CurvedAnimation(
                      parent: animation,
                      curve: Curves.easeOutCubic,
                    ),
                  ),
              child: child,
            ),
          );
        },
        transitionDuration: const Duration(milliseconds: 350),
        reverseTransitionDuration: const Duration(milliseconds: 250),
      ),
    );
  }

  Future<void> _switchProtectionMode(String mode) async {
    final prefs = await SharedPreferences.getInstance();
    final oldMode = _protectionMode;
    if (mode == oldMode) return;

    if (mode == 'standard') {
      await GlanceChannelService.revokeAccessibility();
      await prefs.setString('protection_mode', mode);
      if (!mounted) return;
      setState(() => _protectionMode = mode);
      _showSnackBar(
        LocaleProvider.stringsOf(context).switchedToMode.replaceFirst(
          '%s',
          LocaleProvider.stringsOf(context).standardMode,
        ),
        isError: false,
      );
      return;
    }

    await prefs.setString('protection_mode', mode);
    if (!mounted) return;
    setState(() => _protectionMode = mode);

    final hasAccessibility =
        await GlanceChannelService.isAccessibilityEnabled();
    final hasOverlay = await GlanceChannelService.isOverlayPermissionGranted();
    if (!hasAccessibility || !hasOverlay) {
      if (!mounted) return;
      await Navigator.of(context).push(
        MaterialPageRoute(
          builder: (_) => const PermissionScreen(fromSettings: true),
        ),
      );
      if (!mounted) return;
      final recheckAccessibility =
          await GlanceChannelService.isAccessibilityEnabled();
      final recheckOverlay =
          await GlanceChannelService.isOverlayPermissionGranted();
      if (!recheckAccessibility || !recheckOverlay) {
        await prefs.setString('protection_mode', oldMode);
        if (!mounted) return;
        setState(() => _protectionMode = oldMode);
        final strings = LocaleProvider.stringsOf(context);
        _showSnackBar(
          strings.insufficientPermissionsKeepMode.replaceFirst(
            '%s',
            oldMode == 'standard' ? strings.standardMode : strings.maximumMode,
          ),
        );
        return;
      }
    }

    if (!mounted) return;
    _showSnackBar(
      LocaleProvider.stringsOf(context).switchedToMode.replaceFirst(
        '%s',
        LocaleProvider.stringsOf(context).maximumMode,
      ),
      isError: false,
    );
  }

  Future<void> _showModeSelectionMenu() async {
    final strings = LocaleProvider.stringsOf(context);
    await showModalBottomSheet<void>(
      context: context,
      backgroundColor: Colors.transparent,
      builder: (context) => Container(
        margin: const EdgeInsets.all(16),
        padding: const EdgeInsets.all(20),
        decoration: BoxDecoration(
          color: AppColors.cardSurface(context),
          borderRadius: BorderRadius.circular(24),
          border: Border.all(color: AppColors.border(context), width: 0.5),
        ),
        child: SafeArea(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                strings.chooseProtectionMode,
                style: Theme.of(context).textTheme.titleMedium?.copyWith(
                  color: AppColors.textPrimaryC(context),
                  fontWeight: FontWeight.w700,
                ),
              ),
              const SizedBox(height: 16),
              _buildModeSheetTile(
                title: strings.standardMode,
                subtitle: strings.standardModeDesc,
                icon: Icons.verified_user_rounded,
                selected: _protectionMode == 'standard',
                onTap: () {
                  Navigator.of(context).pop();
                  _switchProtectionMode('standard');
                },
              ),
              const SizedBox(height: 10),
              _buildModeSheetTile(
                title: strings.maximumMode,
                subtitle: strings.maximumModeDesc,
                icon: Icons.security_rounded,
                selected: _protectionMode == 'maximum',
                onTap: () {
                  Navigator.of(context).pop();
                  _switchProtectionMode('maximum');
                },
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildModeSheetTile({
    required String title,
    required String subtitle,
    required IconData icon,
    required bool selected,
    required VoidCallback onTap,
  }) {
    return ListTile(
      onTap: onTap,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      tileColor: selected
          ? AppColors.gold.withValues(alpha: 0.12)
          : AppColors.surface(context),
      leading: Icon(
        icon,
        color: selected ? AppColors.gold : AppColors.textTertiaryC(context),
      ),
      title: Text(
        title,
        style: TextStyle(
          color: AppColors.textPrimaryC(context),
          fontWeight: FontWeight.w600,
        ),
      ),
      subtitle: Text(
        subtitle,
        style: TextStyle(color: AppColors.textTertiaryC(context), fontSize: 12),
      ),
      trailing: selected
          ? const Icon(Icons.check_circle_rounded, color: AppColors.gold)
          : null,
    );
  }

  /// Shows a themed SnackBar for feedback.
  void _showSnackBar(String message, {bool isError = true}) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          message,
          style: const TextStyle(color: AppColors.textPrimary, fontSize: 13),
        ),
        backgroundColor: isError
            ? AppColors.statusInactive
            : AppColors.darkCharcoal,
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        margin: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
        duration: const Duration(seconds: 3),
      ),
    );
  }

  /// Shows a premium Dark/Gold dialog when overlay permission is denied.
  ///
  /// This provides a polite, non-technical explanation of why the
  /// "Display over other apps" permission is required, matching the
  /// app's luxury visual language.
  void _showPermissionDeniedDialog() {
    final strings = LocaleProvider.stringsOf(context);
    showDialog(
      context: context,
      barrierColor: Colors.black87,
      builder: (context) => AlertDialog(
        backgroundColor: AppColors.darkCharcoal,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(20),
          side: BorderSide(
            color: AppColors.gold.withValues(alpha: 0.3),
            width: 1,
          ),
        ),
        // ── Icon ──────────────────────────────────────────────────────
        icon: Image.asset(
          'assets/glance-favicon.png',
          width: 56,
          height: 56,
          fit: BoxFit.contain,
        ),
        // ── Title ─────────────────────────────────────────────────────
        title: Text(
          strings.permissionRequired,
          textAlign: TextAlign.center,
          style: const TextStyle(
            color: AppColors.textPrimary,
            fontSize: 18,
            fontWeight: FontWeight.w600,
            letterSpacing: 0.3,
          ),
        ),
        // ── Body ──────────────────────────────────────────────────────
        content: Text(
          strings.permissionDescription,
          textAlign: TextAlign.center,
          style: TextStyle(
            color: AppColors.textSecondary.withValues(alpha: 0.85),
            fontSize: 14,
            height: 1.5,
          ),
        ),
        // ── Actions ───────────────────────────────────────────────────
        actionsAlignment: MainAxisAlignment.center,
        actionsPadding: const EdgeInsets.only(bottom: 20, left: 20, right: 20),
        actions: [
          SizedBox(
            width: double.infinity,
            height: 48,
            child: ElevatedButton(
              onPressed: () {
                Navigator.of(context).pop();
                // Re-attempt starting the service which will re-trigger
                // the permission flow on the Kotlin side.
                _handleToggleService(true);
              },
              style: ElevatedButton.styleFrom(
                backgroundColor: AppColors.gold,
                foregroundColor: AppColors.oledBlack,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(14),
                ),
                elevation: 0,
              ),
              child: Text(
                strings.openSettings,
                style: const TextStyle(
                  fontWeight: FontWeight.w600,
                  fontSize: 15,
                  letterSpacing: 0.5,
                ),
              ),
            ),
          ),
          const SizedBox(height: 10),
          SizedBox(
            width: double.infinity,
            height: 44,
            child: TextButton(
              onPressed: () => Navigator.of(context).pop(),
              style: TextButton.styleFrom(
                foregroundColor: AppColors.textTertiary,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(14),
                ),
              ),
              child: Text(strings.notNow, style: const TextStyle(fontSize: 14)),
            ),
          ),
        ],
      ),
    );
  }

  // ── Build ───────────────────────────────────────────────────────────────

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.background(context),
      body: SafeArea(
        child: CustomScrollView(
          physics: const BouncingScrollPhysics(),
          slivers: [
            // ── App Bar ─────────────────────────────────────────────────────
            SliverAppBar(
              pinned: true,
              automaticallyImplyLeading: false,
              backgroundColor: AppColors.background(context),
              surfaceTintColor: Colors.transparent,
              expandedHeight: 64,
              title: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  // Brand icon
                  Container(
                    width: 28,
                    height: 28,
                    decoration: BoxDecoration(
                      color: AppColors.gold.withValues(alpha: 0.15),
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: Image.asset(
                      'assets/glance-favicon.png',
                      width: 32,
                      height: 32,
                    ),
                  ),
                  const SizedBox(width: 12),
                  Text(
                    'GLANCE',
                    style: Theme.of(context).textTheme.titleLarge?.copyWith(
                      letterSpacing: 3,
                      fontWeight: FontWeight.w700,
                      fontSize: 18,
                    ),
                  ),
                ],
              ),
              actions: [
                // Settings button — navigates to SettingsScreen
                IconButton(
                  onPressed: () async {
                    await Navigator.of(context).push(
                      MaterialPageRoute(builder: (_) => const SettingsScreen()),
                    );
                    _loadSavedSettings();
                  },
                  icon: Icon(
                    Icons.settings_rounded,
                    color: AppColors.textTertiaryC(context),
                    size: 22,
                  ),
                ),
                const SizedBox(width: 4),
              ],
            ),

            // ── Content ─────────────────────────────────────────────────────
            SliverPadding(
              padding: const EdgeInsets.symmetric(horizontal: 20),
              sliver: SliverList(
                delegate: SliverChildListDelegate([
                  const SizedBox(height: 8),

                  // 1. Shield Status Card (hero)
                  ShieldStatusCard(
                    isActive: _isServiceActive,
                    isCalibrated: _isCalibrated,
                    protectionMode: _protectionMode,
                    onToggle: _handleToggleService,
                    onModeTap: _showModeSelectionMenu,
                  ),

                  const SizedBox(height: 16),

                  // 2. Real-time Sensor Angles (Beta / Gamma)
                  _buildSensorBars(context),

                  const SizedBox(height: 16),

                  // 3. Sensitivity Slider Card
                  SensitivitySliderCard(
                    value: _sensitivity,
                    isServiceActive: _isServiceActive,
                    onChanged: _handleSensitivityChanged,
                    onChangeEnd: _handleSensitivityChangeEnd,
                  ),

                  const SizedBox(height: 16),

                  // 4. Tolerance Slider Card (Flicker Guard / Vùng chấp nhận lệch)
                  ToleranceSliderCard(
                    value: _tolerance,
                    isServiceActive: _isServiceActive,
                    onChanged: _handleToleranceChanged,
                    onChangeEnd: _handleToleranceChangeEnd,
                  ),

                  const SizedBox(height: 16),

                  // 4b. Auto Posture Adaptation Toggle (below Tolerance, above Calibrate)
                  AutoPostureToggle(isServiceActive: _isServiceActive),

                  const SizedBox(height: 12),

                  // ── UX Warning: Clickjacking Protection notice ──────────
                  // Android's OS-level Clickjacking Protection drops touch
                  // events on windows obscured by overlays. This is a system
                  // security feature that CANNOT be bypassed. Users must
                  // toggle the overlay off via Quick Settings when interacting
                  // with sensitive apps like Google Play or banking apps.
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 4),
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Padding(
                          padding: const EdgeInsets.only(top: 1),
                          child: Icon(
                            Icons.info_outline_rounded,
                            size: 14,
                            color: AppColors.textTertiaryC(
                              context,
                            ).withValues(alpha: 0.6),
                          ),
                        ),
                        const SizedBox(width: 6),
                        Expanded(
                          child: Text(
                            LocaleProvider.stringsOf(
                              context,
                            ).overlayTouchWarning,
                            style: TextStyle(
                              color: AppColors.textTertiaryC(
                                context,
                              ).withValues(alpha: 0.6),
                              fontSize: 11,
                              fontStyle: FontStyle.italic,
                              height: 1.4,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),

                  const SizedBox(height: 16),

                  // 5. Coverage Mode Card (Fullscreen / Targeted) — ABOVE calibration
                  OverlayModeCard(
                    isServiceActive: _isServiceActive,
                    isTargetedMode: _isTargetedMode,
                    onModeChanged: _handleOverlayModeChanged,
                    onDefineArea: _handleDefineArea,
                  ),

                  const SizedBox(height: 16),

                  // 6. Privacy Display features hub — per-app triggers,
                  // notification zone, credential guard, pixel pattern.
                  // Features live here on the main screen; Settings keeps
                  // only app-level preferences (theme / language / about).
                  _buildPrivacyDisplayCard(context),

                  const SizedBox(height: 16),
                ]),
              ),
            ),
          ],
        ),
      ),
      bottomNavigationBar: Container(
        decoration: BoxDecoration(
          color: Colors.transparent,
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: 0.15),
              blurRadius: 12,
              offset: const Offset(0, -4),
            ),
          ],
        ),
        child: ClipRRect(
          child: BackdropFilter(
            filter: ImageFilter.blur(sigmaX: 12, sigmaY: 12),
            child: SafeArea(
              top: false,
              child: CalibrateCard(
                isServiceActive: _isServiceActive,
                isCalibrated: _isCalibrated,
                onCalibrate: _handleCalibrate,
              ),
            ),
          ),
        ),
      ),
    );
  }

  /// Entry card to the Privacy Display feature set (Glance 2.0).
  /// Moved here from Settings: it is a feature hub, not a preference.
  Widget _buildPrivacyDisplayCard(BuildContext context) {
    final isVi = LocaleProvider.of(context).locale == AppLocale.vi;
    return Container(
      width: double.infinity,
      decoration: BoxDecoration(
        color: AppColors.cardSurface(context),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: AppColors.border(context), width: 0.5),
      ),
      child: ListTile(
        contentPadding: const EdgeInsets.symmetric(horizontal: 20, vertical: 4),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        leading: Container(
          width: 36,
          height: 36,
          decoration: BoxDecoration(
            color: AppColors.gold.withValues(alpha: 0.12),
            borderRadius: BorderRadius.circular(10),
          ),
          child: Icon(
            Icons.privacy_tip_rounded,
            size: 18,
            color: AppColors.gold,
          ),
        ),
        title: Text(
          'Privacy Display',
          style: TextStyle(
            color: AppColors.textPrimaryC(context),
            fontSize: 15,
            fontWeight: FontWeight.w600,
            letterSpacing: 0.3,
          ),
        ),
        subtitle: Text(
          isVi
              ? 'Theo ứng dụng · Vùng thông báo · PIN/mật khẩu · Họa tiết'
              : 'Per-app · Notification zone · PIN/password · Pattern',
          style: TextStyle(
            color: AppColors.textSecondaryC(context),
            fontSize: 12,
          ),
        ),
        trailing: Icon(
          Icons.chevron_right_rounded,
          color: AppColors.textSecondaryC(context),
          size: 20,
        ),
        onTap: () {
          Navigator.of(context).push(
            MaterialPageRoute(
              builder: (context) => const PrivacyDisplayScreen(),
            ),
          );
        },
      ),
    );
  }

  /// ── Real-time Sensor Bars ───────────────────────────────────────────────
  /// Uses [StreamBuilder] to listen to [GlanceChannelService.sensorStream].
  /// Displays two premium Gold progress bars for Beta (pitch) and Gamma (roll).
  ///
  /// The stream is a broadcast stream cached in the service layer, so no
  /// duplicate native listeners are created. StreamBuilder automatically
  /// cancels the subscription when this widget is disposed — no manual
  /// cleanup needed, zero memory leak risk.
  ///
  /// Angle normalization: Native sends degrees. We normalize to 0..1 range
  /// using ±180° for beta and ±90° for gamma as the max range.
  Widget _buildSensorBars(BuildContext context) {
    final strings = LocaleProvider.stringsOf(context);

    // When service is inactive, show static zeroed bars
    if (!_isServiceActive) {
      return _buildSensorContainer(
        strings: strings,
        beta: 0.0,
        gamma: 0.0,
        betaDeg: 0.0,
        gammaDeg: 0.0,
        isActive: false,
      );
    }

    return StreamBuilder<Map<String, double>>(
      stream: GlanceChannelService.sensorStream,
      builder: (context, snapshot) {
        final beta = snapshot.data?['beta'] ?? 0.0;
        final gamma = snapshot.data?['gamma'] ?? 0.0;

        // Normalize to 0..1 for progress bars
        // Beta (pitch): typically -180..+180 → map to 0..1 centered at 0.5
        final normalizedBeta = ((beta / 180.0) + 1.0) / 2.0;
        // Gamma (roll): typically -90..+90 → map to 0..1 centered at 0.5
        final normalizedGamma = ((gamma / 90.0) + 1.0) / 2.0;

        return _buildSensorContainer(
          strings: strings,
          beta: normalizedBeta.clamp(0.0, 1.0),
          gamma: normalizedGamma.clamp(0.0, 1.0),
          betaDeg: beta,
          gammaDeg: gamma,
          isActive: true,
        );
      },
    );
  }

  /// Builds the visual container for the two sensor angle bars.
  Widget _buildSensorContainer({
    required LocalizedStrings strings,
    required double beta,
    required double gamma,
    required double betaDeg,
    required double gammaDeg,
    required bool isActive,
  }) {
    // Mask data to zero when disconnected
    final displayBeta = isActive ? beta : 0.0;
    final displayGamma = isActive ? gamma : 0.0;
    final displayBetaDeg = isActive ? betaDeg : 0.0;
    final displayGammaDeg = isActive ? gammaDeg : 0.0;

    return AnimatedOpacity(
      duration: const Duration(milliseconds: 300),
      opacity: isActive ? 1.0 : 0.4,
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: AppColors.cardSurface(context),
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: AppColors.border(context), width: 0.5),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Beta bar (Front / Back)
            _buildSingleBar(
              label: strings.sensorFrontBack,
              value: displayBeta,
              degrees: displayBetaDeg,
            ),
            const SizedBox(height: 12),
            // Gamma bar (Left / Right)
            _buildSingleBar(
              label: strings.sensorLeftRight,
              value: displayGamma,
              degrees: displayGammaDeg,
            ),
          ],
        ),
      ),
    );
  }

  /// Builds a single labeled Gold progress bar with degree readout.
  Widget _buildSingleBar({
    required String label,
    required double value,
    required double degrees,
  }) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(
              label.toUpperCase(),
              style: TextStyle(
                color: AppColors.textTertiaryC(context),
                fontSize: 10,
                fontWeight: FontWeight.w700,
                letterSpacing: 0.8,
              ),
            ),
            TweenAnimationBuilder<double>(
              tween: Tween<double>(end: degrees),
              duration: const Duration(milliseconds: 600),
              curve: Curves.easeOutCubic,
              builder: (context, animDegrees, _) => Text(
                '${animDegrees > 0 ? '+' : ''}${animDegrees.toStringAsFixed(1)}°',
                style: TextStyle(
                  color: AppColors.gold.withValues(alpha: 0.9),
                  fontSize: 11,
                  fontWeight: FontWeight.w700,
                  fontFamily: 'monospace',
                  fontFeatures: const [FontFeature.tabularFigures()],
                ),
              ),
            ),
          ],
        ),
        const SizedBox(height: 8),
        ClipRRect(
          borderRadius: BorderRadius.circular(2),
          child: Container(
            height: 3,
            width: double.infinity,
            color: AppColors.surface(context),
            child: LayoutBuilder(
              builder: (context, constraints) {
                final totalWidth = constraints.maxWidth;

                return TweenAnimationBuilder<double>(
                  tween: Tween<double>(end: value),
                  duration: const Duration(milliseconds: 600),
                  curve: Curves.easeOutCubic,
                  builder: (context, animValue, _) {
                    final leftFraction = animValue < 0.5 ? animValue : 0.5;
                    final widthFraction = (animValue - 0.5).abs();

                    final leftPos = leftFraction * totalWidth;
                    final barWidth = widthFraction * totalWidth;

                    return Stack(
                      children: [
                        // Subtle center divider
                        Positioned(
                          left: totalWidth / 2 - 0.5,
                          top: 0,
                          bottom: 0,
                          width: 1,
                          child: Container(
                            color: AppColors.border(context).withValues(alpha: 0.5),
                          ),
                        ),
                        // Animated Gold Bar — smooth wake-up transition
                        Positioned(
                          left: leftPos,
                          top: 0,
                          bottom: 0,
                          width: barWidth,
                          child: Container(
                            decoration: BoxDecoration(
                              gradient: LinearGradient(
                                colors: [
                                  AppColors.gold,
                                  AppColors.gold.withValues(alpha: 0.7),
                                ],
                              ),
                              borderRadius: BorderRadius.circular(2),
                            ),
                          ),
                        ),
                      ],
                    );
                  },
                );
              },
            ),
          ),
        ),
      ],
    );
  }
}
