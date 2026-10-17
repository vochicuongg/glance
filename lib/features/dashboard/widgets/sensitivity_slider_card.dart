import 'package:flutter/material.dart';

import '../../../core/localization/app_strings.dart';
import '../../../core/localization/locale_provider.dart';
import '../../../core/theme/app_colors.dart';

/// ─────────────────────────────────────────────────────────────────────────────
/// Sensitivity Slider Card
/// ─────────────────────────────────────────────────────────────────────────────
/// Allows the user to adjust the "Sensitivity / Tolerance" of the tilt
/// detection. The value (0.0 – 1.0) maps to the `maxTolerance` parameter
/// in the native deviation formula:
///
///   opacity = (deviation / maxTolerance)²
///
/// Visual feedback is provided via:
///   • A gold-accented slider with smooth animation (0–100, step 1).
///   • A numeric badge showing the current sensitivity value (0–100).
///   • A subtle description explaining what the setting does.
/// ─────────────────────────────────────────────────────────────────────────────
class SensitivitySliderCard extends StatelessWidget {
  /// Current sensitivity value between 0.0 and 1.0.
  final double value;

  /// Whether the service is active (slider is only interactive when active).
  final bool isServiceActive;

  /// Called when the user changes the slider.
  final ValueChanged<double> onChanged;

  /// Called when the user finishes dragging (to send to native side).
  final ValueChanged<double> onChangeEnd;

  const SensitivitySliderCard({
    super.key,
    required this.value,
    required this.isServiceActive,
    required this.onChanged,
    required this.onChangeEnd,
  });

  /// Returns a description for the current sensitivity level.
  String _sensitivityDescription(LocalizedStrings s) {
    if (value <= 0.3) return s.sensitivityDescLow;
    if (value <= 0.7) return s.sensitivityDescMedium;
    return s.sensitivityDescHigh;
  }

  @override
  Widget build(BuildContext context) {
    final textTheme = Theme.of(context).textTheme;
    final strings = LocaleProvider.stringsOf(context);
    final isEnabled = isServiceActive;

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        color: AppColors.cardSurface(context),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: AppColors.border(context), width: 0.5),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // ── Header Row ────────────────────────────────────────────────────
          Row(
            children: [
              Container(
                width: 36,
                height: 36,
                decoration: BoxDecoration(
                  color: isEnabled
                      ? AppColors.accent(context).withValues(alpha: 0.12)
                      : AppColors.surface(context),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Icon(
                  Icons.tune_rounded,
                  size: 18,
                  color: isEnabled
                      ? AppColors.accent(context)
                      : AppColors.textTertiaryC(context),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      strings.sensitivity,
                      style: textTheme.titleMedium?.copyWith(
                        color: isEnabled
                            ? AppColors.textPrimaryC(context)
                            : AppColors.textTertiaryC(context),
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      strings.sensitivitySubtitle,
                      style: textTheme.bodySmall,
                    ),
                  ],
                ),
              ),
              // Current level badge
              AnimatedContainer(
                duration: const Duration(milliseconds: 300),
                padding: const EdgeInsets.symmetric(
                  horizontal: 10,
                  vertical: 4,
                ),
                decoration: BoxDecoration(
                  color: isEnabled
                      ? AppColors.accent(context).withValues(alpha: 0.12)
                      : AppColors.surface(context),
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(
                    color: isEnabled
                        ? AppColors.accent(context).withValues(alpha: 0.3)
                        : AppColors.border(context),
                    width: 0.5,
                  ),
                ),
                child: Text(
                  '${(value * 100).round()}',
                  style: textTheme.labelLarge?.copyWith(
                    color: isEnabled
                        ? AppColors.accent(context)
                        : AppColors.textTertiaryC(context),
                    fontSize: 12,
                  ),
                ),
              ),
            ],
          ),

          const SizedBox(height: 20),

          // ── Slider ────────────────────────────────────────────────────────
          SliderTheme(
            data: Theme.of(context).sliderTheme.copyWith(
                  activeTrackColor: isEnabled
                      ? AppColors.accent(context)
                      : AppColors.textTertiaryC(context),
                  thumbColor: isEnabled
                      ? AppColors.accent(context)
                      : AppColors.textTertiaryC(context),
                  inactiveTrackColor: AppColors.surface(context),
                ),
            child: Slider(
              value: value,
              min: 0.0,
              max: 1.0,
              divisions: 100,
              onChanged: isEnabled ? onChanged : null,
              onChangeEnd: isEnabled ? onChangeEnd : null,
            ),
          ),

          // ── Scale Labels ──────────────────────────────────────────────────
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 8),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  '0',
                  style: textTheme.bodySmall?.copyWith(
                    color: AppColors.textTertiaryC(context),
                    fontSize: 11,
                  ),
                ),
                Text(
                  '100',
                  style: textTheme.bodySmall?.copyWith(
                    color: AppColors.textTertiaryC(context),
                    fontSize: 11,
                  ),
                ),
              ],
            ),
          ),

          const SizedBox(height: 12),

          // ── Description ───────────────────────────────────────────────────
          AnimatedSwitcher(
            duration: const Duration(milliseconds: 250),
            child: Text(
              _sensitivityDescription(strings),
              key: ValueKey(_sensitivityDescription(strings)),
              style: textTheme.bodySmall?.copyWith(
                color: AppColors.textTertiaryC(context),
                height: 1.4,
              ),
            ),
          ),

          // ── Bottom Info Row (Instant Protection) ─────────────────────────
          const SizedBox(height: 16),
          Row(
            children: [
              Icon(
                Icons.flash_on_rounded,
                size: 14,
                color: isEnabled
                    ? AppColors.accent(context).withValues(alpha: 0.8)
                    : AppColors.textTertiaryC(context),
              ),
              const SizedBox(width: 6),
              Text(
                strings.instantProtectionLabel,
                style: textTheme.bodySmall?.copyWith(
                  color: isEnabled
                      ? AppColors.textSecondaryC(context)
                      : AppColors.textTertiaryC(context),
                  fontSize: 12,
                  fontWeight: FontWeight.w500,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
