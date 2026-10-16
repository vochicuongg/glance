import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';
import '../../../core/localization/locale_provider.dart';
import '../../../core/theme/app_colors.dart';

/// ─────────────────────────────────────────────────────────────────────────────
/// About App Bottom Sheet
/// ─────────────────────────────────────────────────────────────────────────────
/// A finance-centric, premium bottom sheet displaying app details,
/// developer biography and project links.
/// Glance 2.0: personal donation QR codes and bank/phone numbers were
/// removed from the public repo — a privacy app must not publish its
/// author's financial identifiers.
/// ─────────────────────────────────────────────────────────────────────────────
class AboutAppSheet extends StatelessWidget {
  const AboutAppSheet({super.key});

  @override
  Widget build(BuildContext context) {
    final strings = LocaleProvider.stringsOf(context);

    return Container(
      decoration: BoxDecoration(
        color: AppColors.background(context),
        borderRadius: const BorderRadius.vertical(top: Radius.circular(24)),
        border: Border(
          top: BorderSide(
            color: AppColors.accent(context).withValues(alpha: 0.2),
            width: 1.5,
          ),
        ),
      ),
      padding: EdgeInsets.only(
        bottom: MediaQuery.of(context).viewInsets.bottom,
      ),
      child: SafeArea(
        child: SingleChildScrollView(
          physics: const BouncingScrollPhysics(),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              // ── Grab Handle ────────────────────────────────────────────────
              const SizedBox(height: 12),
              Container(
                width: 48,
                height: 5,
                decoration: BoxDecoration(
                  color: AppColors.textTertiaryC(context).withValues(alpha: 0.5),
                  borderRadius: BorderRadius.circular(10),
                ),
              ),
              const SizedBox(height: 24),

              // ── Header (Glance + Version) ──────────────────────────────────
              Text(
                'Glance',
                style: TextStyle(
                  color: AppColors.accent(context),
                  fontSize: 32,
                  fontWeight: FontWeight.w800, // extrabold is w800
                  letterSpacing: 1.5,
                  shadows: [
                    Shadow(
                      color: AppColors.accentGlow(context),
                      offset: const Offset(0, 4),
                      blurRadius: 12,
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 4),
              Text(
                strings.appVersion,
                style: TextStyle(
                  color: AppColors.textTertiaryC(context),
                  fontSize: 13,
                  fontWeight: FontWeight.w500,
                  letterSpacing: 0.5,
                ),
              ),
              const SizedBox(height: 28),

              // ── Developer Section ──────────────────────────────────────────
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 20),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      strings.developer.toUpperCase(),
                      style: TextStyle(
                        color: AppColors.textSecondaryC(context),
                        fontSize: 11,
                        fontWeight: FontWeight.w700,
                        letterSpacing: 1.5,
                      ),
                    ),
                    const SizedBox(height: 10),
                    // Developer Info Glassmorphism Container
                    Container(
                      width: double.infinity,
                      padding: const EdgeInsets.all(16),
                      decoration: BoxDecoration(
                        color: AppColors.cardSurface(context),
                        borderRadius: BorderRadius.circular(16),
                        border: Border.all(
                          color: AppColors.border(context),
                          width: 0.5,
                        ),
                      ),
                      child: Column(
                        children: [
                          _buildInfoRow(
                            context,
                            icon: Icons.person_rounded,
                            label: 'Võ Chí Cường',
                            isHeader: true,
                          ),
                          const Divider(height: 24, thickness: 0.5),
                          _buildInfoRow(
                            context,
                            icon: Icons.language_rounded,
                            label: 'vochicuong.is-a.dev',
                            onTap: () async {
                              try {
                                final Uri url = Uri.parse('https://vochicuong.is-a.dev');
                                await launchUrl(url, mode: LaunchMode.externalApplication);
                              } catch (_) {}
                            },
                          ),
                          const SizedBox(height: 12),
                          _buildInfoRow(
                            context,
                            icon: Icons.code_rounded,
                            label: 'github.com/vochicuongg',
                            onTap: () async {
                              try {
                                final Uri url = Uri.parse('https://github.com/vochicuongg');
                                await launchUrl(url, mode: LaunchMode.externalApplication);
                              } catch (_) {}
                            },
                          ),
                          const SizedBox(height: 12),
                          _buildInfoRow(
                            context,
                            icon: Icons.email_outlined,
                            label: 'cuong.vochi0411@gmail.com',
                            onTap: () async {
                              try {
                                final Uri emailUri = Uri(
                                  scheme: 'mailto',
                                  path: 'cuong.vochi0411@gmail.com',
                                );
                                await launchUrl(emailUri);
                              } catch (_) {}
                            },
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 32),
            ],
          ),
        ),
      ),
    );
  }

}
