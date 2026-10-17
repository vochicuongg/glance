import 'package:flutter/material.dart';

import '../../../core/localization/app_strings.dart';
import '../../../core/localization/locale_provider.dart';
import '../../../core/theme/app_colors.dart';
import '../../../core/theme/theme_provider.dart';
import '../widgets/language_selector_card.dart';
import '../widgets/about_app_sheet.dart';

/// ─────────────────────────────────────────────────────────────────────────────
/// Settings Screen
/// ─────────────────────────────────────────────────────────────────────────────
/// App-level preferences only, pushed from the Dashboard gear icon.
///
/// Contains:
///   • Theme toggle (System / Light / Dark)
///   • Language selector
///   • About app
///
/// All protection FEATURES live on the Dashboard (main screen):
/// protection mode (ShieldStatusCard sheet), sensitivity, tolerance,
/// auto posture adaptation, coverage mode and the Privacy Display hub.
/// This screen intentionally holds nothing that changes how the shield
/// behaves — "mục nào ra mục đó".
///
/// Design:
///   • Uses adaptive colors from [AppColors] to match the current theme.
///   • Maintains the premium aesthetic in both light and dark modes.
/// ─────────────────────────────────────────────────────────────────────────────
class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

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
