import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../../core/localization/app_strings.dart';
import '../../../core/localization/locale_provider.dart';
import '../../../core/services/glance_channel_service.dart';
import '../../../core/theme/app_colors.dart';
import '../../../core/theme/theme_provider.dart';
import '../../permissions/screens/permission_screen.dart';
import '../widgets/language_selector_card.dart';
import '../widgets/about_app_sheet.dart';
import '../../privacy_display/privacy_display_screen.dart';

/// ─────────────────────────────────────────────────────────────────────────────
/// Settings Screen
/// ─────────────────────────────────────────────────────────────────────────────
/// A dedicated settings page pushed from the Dashboard gear icon.
///
/// Contains:
///   • Protection mode switcher (Standard / Maximum)
///   • Theme toggle (Light / Dark mode)
///   • Language selector
///
/// Design:
///   • Uses adaptive colors from [AppColors] to match the current theme.
///   • Maintains the premium aesthetic in both light and dark modes.
/// ─────────────────────────────────────────────────────────────────────────────
class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  String _protectionMode = 'maximum';
  bool _isLoading = true;

  String _modeLabel(LocalizedStrings strings, String mode) =>
      mode == 'standard' ? strings.standardMode : strings.maximumMode;

  @override
  void initState() {
    super.initState();
    _loadProtectionMode();
  }

  Future<void> _loadProtectionMode() async {
    final prefs = await SharedPreferences.getInstance();
    if (!mounted) return;
    setState(() {
      _protectionMode = prefs.getString('protection_mode') ?? 'maximum';
      _isLoading = false;
    });
  }

  Future<void> _switchMode(String newMode) async {
    if (newMode == _protectionMode) return;

    final prefs = await SharedPreferences.getInstance();
    final oldMode = _protectionMode;

    if (newMode == 'standard') {
      // ══════════════════════════════════════════════════════════════════
      //  MAX → STANDARD: Auto-Revoke Accessibility immediately
      // ══════════════════════════════════════════════════════════════════
      // 1. Revoke Accessibility permission FIRST (disableSelf on native)
      //    This kills the MaxOverlayService engine immediately.
      await GlanceChannelService.revokeAccessibility();

      // 2. Save the new mode to SharedPreferences AFTER revoking
      await prefs.setString('protection_mode', newMode);
      if (!mounted) return;
      setState(() => _protectionMode = newMode);

      // 3. Check overlay permission (Standard mode still needs it)
      final hasOverlay =
          await GlanceChannelService.isOverlayPermissionGranted();

      if (!hasOverlay) {
        // Missing overlay → navigate to PermissionScreen to request it
        if (!mounted) return;
        final navigator = Navigator.of(context);
        await navigator.push(
          MaterialPageRoute(
            builder: (_) => const PermissionScreen(fromSettings: true),
          ),
        );

        // After returning, re-check overlay
        if (!mounted) return;
        final recheckOverlay =
            await GlanceChannelService.isOverlayPermissionGranted();

        if (!recheckOverlay) {
          // User didn't grant overlay → revert to old mode
          await prefs.setString('protection_mode', oldMode);
          if (!mounted) return;
          setState(() => _protectionMode = oldMode);
          final strings = LocaleProvider.stringsOf(context);
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text(
                strings.insufficientPermissionsKeepMode.replaceFirst(
                  '%s',
                  _modeLabel(strings, oldMode),
                ),
              ),
              behavior: SnackBarBehavior.floating,
            ),
          );
          return;
        }
      }

      if (!mounted) return;
      final strings = LocaleProvider.stringsOf(context);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            strings.switchedToMode.replaceFirst(
              '%s',
              _modeLabel(strings, newMode),
            ),
          ),
          behavior: SnackBarBehavior.floating,
        ),
      );
    } else {
      // ══════════════════════════════════════════════════════════════════
      //  STANDARD → MAX: Save mode, then go to PermissionScreen
      // ══════════════════════════════════════════════════════════════════
      // 1. Save mode first so PermissionScreen's Gatekeeper can read it
      await prefs.setString('protection_mode', newMode);
      if (!mounted) return;
      setState(() => _protectionMode = newMode);

      // 2. Check if all permissions are already granted
      final hasAccessibility =
          await GlanceChannelService.isAccessibilityEnabled();
      final hasOverlay =
          await GlanceChannelService.isOverlayPermissionGranted();

      final batteryGranted = await Permission.ignoreBatteryOptimizations.isGranted;

      if (!hasAccessibility || !hasOverlay || !batteryGranted) {
        // 3. Missing permissions → navigate to PermissionScreen
        if (!mounted) return;
        final navigator = Navigator.of(context);
        await navigator.push(
          MaterialPageRoute(
            builder: (_) => const PermissionScreen(fromSettings: true),
          ),
        );

        // After returning, re-check if user actually granted permissions
        if (!mounted) return;
        final recheckAccessibility =
            await GlanceChannelService.isAccessibilityEnabled();
        final recheckOverlay =
            await GlanceChannelService.isOverlayPermissionGranted();
        final recheckBattery =
            await Permission.ignoreBatteryOptimizations.isGranted;

        if (!recheckAccessibility || !recheckOverlay || !recheckBattery) {
          // User didn't grant → revert to old mode
          await prefs.setString('protection_mode', oldMode);
          if (!mounted) return;
          setState(() => _protectionMode = oldMode);
          final strings = LocaleProvider.stringsOf(context);
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text(
                strings.insufficientPermissionsKeepMode.replaceFirst(
                  '%s',
                  _modeLabel(strings, oldMode),
                ),
              ),
              behavior: SnackBarBehavior.floating,
            ),
          );
          return;
        }
      }

      if (!mounted) return;
      final strings = LocaleProvider.stringsOf(context);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            strings.switchedToMode.replaceFirst(
              '%s',
              _modeLabel(strings, newMode),
            ),
          ),
          behavior: SnackBarBehavior.floating,
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final strings = LocaleProvider.stringsOf(context);
    final themeProvider = ThemeProvider.of(context);
    final currentMode = themeProvider.themeMode;

    return Scaffold(
      backgroundColor: AppColors.background(context),
      appBar: AppBar(
        backgroundColor: AppColors.background(context),
        surfaceTintColor: Colors.transparent,
        leading: IconButton(
          onPressed: () => Navigator.of(context).pop(),
          icon: Icon(
            Icons.arrow_back_ios_new_rounded,
            color: AppColors.textSecondaryC(context),
            size: 20,
          ),
        ),
        title: Text(
          strings.settings,
          style: TextStyle(
            color: AppColors.textPrimaryC(context),
            fontSize: 18,
            fontWeight: FontWeight.w600,
            letterSpacing: 0.5,
          ),
        ),
        centerTitle: true,
      ),
      body: SafeArea(
        child: ListView(
          physics: const BouncingScrollPhysics(),
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
          children: [
            // ── Protection Mode Switcher ─────────────────────────────────
            if (!_isLoading) _buildProtectionModeCard(context),

            const SizedBox(height: 16),

            // ── Privacy Display (Glance 2.0) ────────────────────────────
            _buildPrivacyDisplayCard(context),

            const SizedBox(height: 16),

            // ── Theme Toggle ──────────────────────────────────────────────
            _buildThemeCard(context, strings, currentMode, themeProvider),

            const SizedBox(height: 16),

            // ── Language Selector ──────────────────────────────────────────
            const LanguageSelectorCard(),

            const SizedBox(height: 16),

            // ── About App Card ─────────────────────────────────────────────
            _buildAboutAppCard(context, strings),

            const SizedBox(height: 32),
          ],
        ),
      ),
    );
  }

  /// Entry card to the Privacy Display settings (Glance 2.0).
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

  /// Builds a card with a ListTile to show the About App bottom sheet.
  Widget _buildAboutAppCard(BuildContext context, LocalizedStrings strings) {
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
            Icons.info_outline_rounded,
            size: 18,
            color: AppColors.gold,
          ),
        ),
        title: Text(
          strings.aboutApp,
          style: TextStyle(
            color: AppColors.textPrimaryC(context),
            fontSize: 15,
            fontWeight: FontWeight.w600,
            letterSpacing: 0.3,
          ),
        ),
        trailing: Icon(
          Icons.chevron_right_rounded,
          color: AppColors.textSecondaryC(context),
          size: 20,
        ),
        onTap: () {
          showModalBottomSheet(
            context: context,
            isScrollControlled: true,
            backgroundColor: Colors.transparent,
            builder: (context) => const AboutAppSheet(),
          );
        },
      ),
    );
  }

  /// Builds the protection mode switcher card.
  Widget _buildProtectionModeCard(BuildContext context) {
    final strings = LocaleProvider.stringsOf(context);
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: AppColors.cardSurface(context),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: AppColors.border(context), width: 0.5),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // ── Header row ────────────────────────────────────────────────
          Row(
            children: [
              Container(
                width: 36,
                height: 36,
                decoration: BoxDecoration(
                  color: AppColors.gold.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Icon(
                  Icons.shield_rounded,
                  size: 18,
                  color: AppColors.gold,
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      strings.protectionMode,
                      style: TextStyle(
                        color: AppColors.textPrimaryC(context),
                        fontSize: 15,
                        fontWeight: FontWeight.w600,
                        letterSpacing: 0.3,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      strings.usingMode.replaceFirst(
                        '%s',
                        _modeLabel(strings, _protectionMode),
                      ),
                      style: TextStyle(
                        color: AppColors.textTertiaryC(context),
                        fontSize: 12,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),

          const SizedBox(height: 16),

          // ── 2-way Segmented toggle (Standard / Maximum) ───────────────
          Container(
            width: double.infinity,
            height: 44,
            decoration: BoxDecoration(
              color: AppColors.surface(context),
              borderRadius: BorderRadius.circular(12),
            ),
            child: Row(
              children: [
                // Standard mode button
                Expanded(
                  child: _buildModeSegmentButton(
                    context: context,
                    label: strings.standardModeShort,
                    icon: Icons.verified_user_rounded,
                    isSelected: _protectionMode == 'standard',
                    onTap: () => _switchMode('standard'),
                  ),
                ),
                // Maximum mode button
                Expanded(
                  child: _buildModeSegmentButton(
                    context: context,
                    label: strings.maximumModeShort,
                    icon: Icons.security_rounded,
                    isSelected: _protectionMode == 'maximum',
                    onTap: () => _switchMode('maximum'),
                  ),
                ),
              ],
            ),
          ),

          const SizedBox(height: 12),

          // ── Description text ──────────────────────────────────────────
          Text(
            _protectionMode == 'standard'
                ? strings.standardModeDesc
                : strings.maximumModeDesc,
            style: TextStyle(
              color: AppColors.textTertiaryC(context),
              fontSize: 12,
              fontStyle: FontStyle.italic,
            ),
          ),
        ],
      ),
    );
  }

  /// Builds a single segment button for the protection mode toggle.
  /// Style is identical to the theme toggle's [_buildSegmentButton].
  Widget _buildModeSegmentButton({
    required BuildContext context,
    required String label,
    required IconData icon,
    required bool isSelected,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 200),
        curve: Curves.easeOutCubic,
        margin: const EdgeInsets.all(3),
        decoration: BoxDecoration(
          color: isSelected
              ? AppColors.gold.withValues(alpha: 0.15)
              : Colors.transparent,
          borderRadius: BorderRadius.circular(10),
          border: isSelected
              ? Border.all(
                  color: AppColors.gold.withValues(alpha: 0.4),
                  width: 1,
                )
              : null,
        ),
        child: Center(
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                icon,
                size: 16,
                color: isSelected
                    ? AppColors.gold
                    : AppColors.textTertiaryC(context),
              ),
              const SizedBox(width: 6),
              Text(
                label,
                style: TextStyle(
                  color: isSelected
                      ? AppColors.gold
                      : AppColors.textTertiaryC(context),
                  fontSize: 13,
                  fontWeight: isSelected ? FontWeight.w600 : FontWeight.w400,
                  letterSpacing: 0.3,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// Builds a premium theme toggle card with 3-way segmented button.
  Widget _buildThemeCard(
    BuildContext context,
    dynamic strings,
    ThemeMode currentMode,
    ThemeProvider themeProvider,
  ) {
    // Resolve the icon based on effective brightness
    final isDark = Theme.of(context).brightness == Brightness.dark;

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: AppColors.cardSurface(context),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: AppColors.border(context), width: 0.5),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // ── Header row ──────────────────────────────────────────────────
          Row(
            children: [
              // Icon
              Container(
                width: 36,
                height: 36,
                decoration: BoxDecoration(
                  color: AppColors.gold.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Icon(
                  isDark ? Icons.dark_mode_rounded : Icons.light_mode_rounded,
                  size: 18,
                  color: AppColors.gold,
                ),
              ),
              const SizedBox(width: 12),
              // Title + subtitle
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      strings.theme,
                      style: TextStyle(
                        color: AppColors.textPrimaryC(context),
                        fontSize: 15,
                        fontWeight: FontWeight.w600,
                        letterSpacing: 0.3,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      strings.themeSubtitle,
                      style: TextStyle(
                        color: AppColors.textTertiaryC(context),
                        fontSize: 12,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),

          const SizedBox(height: 16),

          // ── 3-way Segmented toggle (System / Light / Dark) ─────────────
          Container(
            width: double.infinity,
            height: 44,
            decoration: BoxDecoration(
              color: AppColors.surface(context),
              borderRadius: BorderRadius.circular(12),
            ),
            child: Row(
              children: [
                // System mode button
                Expanded(
                  child: _buildSegmentButton(
                    context: context,
                    label: strings.systemMode,
                    icon: Icons.settings_brightness_rounded,
                    isSelected: currentMode == ThemeMode.system,
                    onTap: () => themeProvider.setThemeMode(ThemeMode.system),
                  ),
                ),
                // Light mode button
                Expanded(
                  child: _buildSegmentButton(
                    context: context,
                    label: strings.lightMode,
                    icon: Icons.light_mode_rounded,
                    isSelected: currentMode == ThemeMode.light,
                    onTap: () => themeProvider.setThemeMode(ThemeMode.light),
                  ),
                ),
                // Dark mode button
                Expanded(
                  child: _buildSegmentButton(
                    context: context,
                    label: strings.darkMode,
                    icon: Icons.dark_mode_rounded,
                    isSelected: currentMode == ThemeMode.dark,
                    onTap: () => themeProvider.setThemeMode(ThemeMode.dark),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  /// Builds a single segment button for the theme toggle.
  Widget _buildSegmentButton({
    required BuildContext context,
    required String label,
    required IconData icon,
    required bool isSelected,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 200),
        curve: Curves.easeOutCubic,
        margin: const EdgeInsets.all(3),
        decoration: BoxDecoration(
          color: isSelected
              ? AppColors.gold.withValues(alpha: 0.15)
              : Colors.transparent,
          borderRadius: BorderRadius.circular(10),
          border: isSelected
              ? Border.all(
                  color: AppColors.gold.withValues(alpha: 0.4),
                  width: 1,
                )
              : null,
        ),
        child: Center(
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                icon,
                size: 16,
                color: isSelected
                    ? AppColors.gold
                    : AppColors.textTertiaryC(context),
              ),
              const SizedBox(width: 6),
              Text(
                label,
                style: TextStyle(
                  color: isSelected
                      ? AppColors.gold
                      : AppColors.textTertiaryC(context),
                  fontSize: 13,
                  fontWeight: isSelected ? FontWeight.w600 : FontWeight.w400,
                  letterSpacing: 0.3,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
