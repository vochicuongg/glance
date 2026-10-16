import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../core/localization/app_strings.dart';
import '../../core/localization/locale_provider.dart';
import '../../core/services/glance_channel_service.dart';
import '../../core/theme/app_colors.dart';

/// ─────────────────────────────────────────────────────────────────────────
/// Privacy Display Screen (Glance 2.0)
/// ─────────────────────────────────────────────────────────────────────────
/// Software approximation of Samsung's Privacy Display feature set:
///
///   • Per-app activation   — tighten the tilt shield inside chosen apps
///   • Notification zone    — cover the heads-up notification area while
///                            a notification is displayed
///   • Credential guard     — tighten the shield while a PIN/password
///                            field is focused (Maximum mode; the native
///                            side checks ONLY the field's isPassword
///                            flag, never its content)
///   • Pixel Pattern        — stripe-pattern shield rendering that
///                            degrades off-axis legibility
///
/// HONEST LIMIT (also stated in-app): Samsung steers light at the pixel
/// level in hardware. Glance reproduces the *behaviour* — when and where
/// privacy engages — with overlays; it cannot reproduce the optics.
///
/// Settings are stored in SharedPreferences. The native engines read the
/// same keys from FlutterSharedPreferences; the package list is mirrored
/// as a comma-separated string because shared_preferences does not store
/// Dart string lists as a native Android StringSet.
/// ─────────────────────────────────────────────────────────────────────────
class PrivacyDisplayScreen extends StatefulWidget {
  const PrivacyDisplayScreen({super.key});

  @override
  State<PrivacyDisplayScreen> createState() => _PrivacyDisplayScreenState();
}

class _PrivacyDisplayScreenState extends State<PrivacyDisplayScreen>
    with WidgetsBindingObserver {
  bool _loading = true;
  bool _perAppEnabled = false;
  bool _protectNotifications = false;
  bool _credentialGuard = false;
  bool _renderPattern = false;

  bool _usageAccess = false;
  bool _notificationAccess = false;

  List<Map<String, String>> _apps = [];
  Set<String> _protectedPackages = {};
  String _query = '';

  bool get _isVi =>
      LocaleProvider.of(context).locale == AppLocale.vi;

  String _t(String vi, String en) => _isVi ? vi : en;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _load();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _refreshAccess();
    }
  }

  Future<void> _load() async {
    final prefs = await SharedPreferences.getInstance();
    final apps = await GlanceChannelService.getLaunchableApps();
    if (!mounted) return;
    setState(() {
      _perAppEnabled = prefs.getBool('per_app_enabled') ?? false;
      _protectNotifications =
          prefs.getBool('protect_notifications') ?? false;
      _credentialGuard = prefs.getBool('credential_guard') ?? false;
      _renderPattern = prefs.getBool('render_pattern') ?? false;
      _protectedPackages =
          (prefs.getStringList('protected_packages') ?? []).toSet();
      _apps = apps;
      _loading = false;
    });
    await _refreshAccess();
  }

  Future<void> _refreshAccess() async {
    final usage = await GlanceChannelService.hasUsageAccess();
    final notif = await GlanceChannelService.isNotificationAccessEnabled();
    if (!mounted) return;
    setState(() {
      _usageAccess = usage;
      _notificationAccess = notif;
    });
  }

  Future<void> _saveBool(String key, bool value) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(key, value);
  }

  Future<void> _saveProtectedPackages() async {
    final prefs = await SharedPreferences.getInstance();
    final list = _protectedPackages.toList()..sort();
    await prefs.setStringList('protected_packages', list);
    // Native mirror — see file header for why the CSV exists.
    await prefs.setString('protected_packages_csv', list.join(','));
  }

  @override
  Widget build(BuildContext context) {
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
          'Privacy Display',
          style: TextStyle(
            color: AppColors.textPrimaryC(context),
            fontSize: 18,
            fontWeight: FontWeight.w600,
            letterSpacing: 0.5,
          ),
        ),
        centerTitle: true,
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              physics: const BouncingScrollPhysics(),
              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
              children: [
                _buildIntro(context),
                const SizedBox(height: 16),
                _buildPerAppCard(context),
                if (_perAppEnabled) ...[
                  const SizedBox(height: 16),
                  _buildAppPicker(context),
                ],
                const SizedBox(height: 16),
                _buildNotificationsCard(context),
                const SizedBox(height: 16),
                _buildCredentialCard(context),
                const SizedBox(height: 16),
                _buildPatternCard(context),
                const SizedBox(height: 32),
              ],
            ),
    );
  }

  Widget _buildIntro(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppColors.cardSurface(context),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: AppColors.border(context), width: 0.5),
      ),
      child: Text(
        _t(
          'Glance mô phỏng bộ tính năng Privacy Display của Samsung bằng phần mềm: tự kích hoạt theo ứng dụng, vùng thông báo, ô nhập PIN/mật khẩu và lớp phủ họa tiết. Khác biệt trung thực: Samsung điều hướng ánh sáng ở cấp độ pixel (phần cứng), Glance dùng lớp phủ — hiệu quả gần đúng về hành vi, không tương đương về quang học.',
          'Glance approximates Samsung\'s Privacy Display feature set in software: per-app activation, notification zone, PIN/password fields and a pattern overlay. Honest difference: Samsung steers light at the pixel level (hardware); Glance uses overlays — close in behaviour, not equivalent in optics.',
        ),
        style: TextStyle(
          color: AppColors.textSecondaryC(context),
          fontSize: 13,
          height: 1.45,
        ),
      ),
    );
  }

  Widget _switchTile({
    required BuildContext context,
    required IconData icon,
    required String title,
    required String subtitle,
    required bool value,
    required ValueChanged<bool> onChanged,
  }) {
    return SwitchListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      secondary: Icon(icon, color: AppColors.gold, size: 22),
      title: Text(
        title,
        style: TextStyle(
          color: AppColors.textPrimaryC(context),
          fontSize: 15,
          fontWeight: FontWeight.w600,
        ),
      ),
      subtitle: Text(
        subtitle,
        style: TextStyle(
          color: AppColors.textSecondaryC(context),
          fontSize: 12.5,
          height: 1.35,
        ),
      ),
      value: value,
      activeThumbColor: AppColors.gold,
      onChanged: onChanged,
    );
  }

  Widget _card(BuildContext context, List<Widget> children) {
    return Container(
      width: double.infinity,
      decoration: BoxDecoration(
        color: AppColors.cardSurface(context),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: AppColors.border(context), width: 0.5),
      ),
      child: Column(children: children),
    );
  }

  Widget _accessRow({
    required BuildContext context,
    required String label,
    required bool granted,
    required VoidCallback onGrant,
  }) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
      child: Row(
        children: [
          Icon(
            granted ? Icons.check_circle_rounded : Icons.error_rounded,
            size: 18,
            color: granted ? Colors.green : Colors.orange,
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              label,
              style: TextStyle(
                color: AppColors.textSecondaryC(context),
                fontSize: 12.5,
              ),
            ),
          ),
          if (!granted)
            TextButton(
              onPressed: onGrant,
              child: Text(_t('Cấp quyền', 'Grant')),
            ),
        ],
      ),
    );
  }

  Widget _buildPerAppCard(BuildContext context) {
    return _card(context, [
      _switchTile(
        context: context,
        icon: Icons.apps_rounded,
        title: _t(
          'Tự kích hoạt theo ứng dụng',
          'Per-app activation',
        ),
        subtitle: _t(
          'Khi mở ứng dụng được bảo vệ, lá chắn siết ngưỡng nghiêng xuống tối đa 6° để phản ứng sớm hơn nhiều.',
          'Inside a protected app the shield tolerance tightens to at most 6°, reacting far earlier.',
        ),
        value: _perAppEnabled,
        onChanged: (v) async {
          setState(() => _perAppEnabled = v);
          await _saveBool('per_app_enabled', v);
        },
      ),
      if (_perAppEnabled)
        _accessRow(
          context: context,
          label: _t(
            'Quyền Truy cập sử dụng (chế độ Tiêu chuẩn): ${_usageAccess ? "đã cấp" : "chưa cấp"}. Chế độ Tối đa dùng Trợ năng, không cần quyền này.',
            'Usage access (Standard mode): ${_usageAccess ? "granted" : "not granted"}. Maximum mode uses Accessibility instead.',
          ),
          granted: _usageAccess,
          onGrant: () => GlanceChannelService.openUsageAccessSettings(),
        ),
    ]);
  }

  Widget _buildAppPicker(BuildContext context) {
    final filtered = _apps.where((a) {
      final q = _query.toLowerCase();
      return q.isEmpty ||
          (a['label'] ?? '').toLowerCase().contains(q) ||
          (a['package'] ?? '').toLowerCase().contains(q);
    }).toList();

    return _card(context, [
      Padding(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
        child: TextField(
          onChanged: (v) => setState(() => _query = v),
          style: TextStyle(color: AppColors.textPrimaryC(context)),
          decoration: InputDecoration(
            hintText: _t('Tìm ứng dụng…', 'Search apps…'),
            prefixIcon: const Icon(Icons.search_rounded),
            border: OutlineInputBorder(
              borderRadius: BorderRadius.circular(12),
            ),
          ),
        ),
      ),
      Padding(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
        child: Align(
          alignment: Alignment.centerLeft,
          child: Text(
            _t(
              'Đã chọn ${_protectedPackages.length} ứng dụng',
              '${_protectedPackages.length} app(s) selected',
            ),
            style: TextStyle(
              color: AppColors.textSecondaryC(context),
              fontSize: 12.5,
            ),
          ),
        ),
      ),
      if (_apps.isEmpty)
        Padding(
          padding: const EdgeInsets.all(16),
          child: Text(
            _t(
              'Không đọc được danh sách ứng dụng.',
              'Could not load the app list.',
            ),
            style: TextStyle(color: AppColors.textSecondaryC(context)),
          ),
        )
      else
        ListView.builder(
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          itemCount: filtered.length,
          itemBuilder: (context, i) {
            final app = filtered[i];
            final pkg = app['package']!;
            final selected = _protectedPackages.contains(pkg);
            return CheckboxListTile(
              dense: true,
              title: Text(
                app['label'] ?? pkg,
                style: TextStyle(
                  color: AppColors.textPrimaryC(context),
                  fontSize: 14,
                ),
              ),
              subtitle: Text(
                pkg,
                style: TextStyle(
                  color: AppColors.textSecondaryC(context),
                  fontSize: 11.5,
                ),
              ),
              value: selected,
              activeColor: AppColors.gold,
              onChanged: (v) async {
                setState(() {
                  if (v == true) {
                    _protectedPackages.add(pkg);
                  } else {
                    _protectedPackages.remove(pkg);
                  }
                });
                await _saveProtectedPackages();
              },
            );
          },
        ),
    ]);
  }

  Widget _buildNotificationsCard(BuildContext context) {
    return _card(context, [
      _switchTile(
        context: context,
        icon: Icons.notifications_rounded,
        title: _t('Che vùng thông báo', 'Notification zone'),
        subtitle: _t(
          'Khi có thông báo đến, một dải che phủ vùng thông báo ở đầu màn hình trong vài giây (Privacy từng phần).',
          'When a notification arrives, a strip covers the notification area at the top of the screen for a few seconds (partial privacy).',
        ),
        value: _protectNotifications,
        onChanged: (v) async {
          setState(() => _protectNotifications = v);
          await _saveBool('protect_notifications', v);
        },
      ),
      if (_protectNotifications)
        _accessRow(
          context: context,
          label: _t(
            'Quyền Truy cập thông báo: ${_notificationAccess ? "đã cấp" : "chưa cấp"}. Glance chỉ biết có thông báo đến, không đọc nội dung.',
            'Notification access: ${_notificationAccess ? "granted" : "not granted"}. Glance only learns that a notification exists — never its content.',
          ),
          granted: _notificationAccess,
          onGrant: () =>
              GlanceChannelService.openNotificationAccessSettings(),
        ),
    ]);
  }

  Widget _buildCredentialCard(BuildContext context) {
    return _card(context, [
      _switchTile(
        context: context,
        icon: Icons.pin_rounded,
        title: _t('Bảo vệ ô PIN / mật khẩu', 'PIN / password guard'),
        subtitle: _t(
          'Chỉ ở chế độ Tối đa: khi ô nhập mật khẩu/PIN đang được chọn, lá chắn siết ngưỡng như ứng dụng được bảo vệ. Hệ thống chỉ kiểm tra loại trường nhập, không đọc nội dung bạn gõ.',
          'Maximum mode only: while a PIN/password field is focused, the shield tightens like in a protected app. The system checks only the field type — never what you type.',
        ),
        value: _credentialGuard,
        onChanged: (v) async {
          setState(() => _credentialGuard = v);
          await _saveBool('credential_guard', v);
        },
      ),
    ]);
  }

  Widget _buildPatternCard(BuildContext context) {
    return _card(context, [
      _switchTile(
        context: context,
        icon: Icons.grid_4x4_rounded,
        title: _t('Lớp phủ họa tiết Pixel Pattern', 'Pixel Pattern overlay'),
        subtitle: _t(
          'Thay màu đen đặc bằng họa tiết sọc mịn: mật độ tăng theo độ nghiêng, làm giảm khả năng đọc từ góc xiên trong khi nhìn thẳng vẫn dễ hơn.',
          'Replaces the solid fill with a fine stripe pattern whose density grows with tilt, degrading side-angle reading while staying lighter straight-on.',
        ),
        value: _renderPattern,
        onChanged: (v) async {
          setState(() => _renderPattern = v);
          await _saveBool('render_pattern', v);
        },
      ),
    ]);
  }
}
