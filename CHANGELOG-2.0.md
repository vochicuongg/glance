# Glance 2.0.0 — Privacy Display release

Basis: `c737194` (v1.0.0). Static-verified only — build & device-test in
Android Studio before publishing (see "Verification status").

## New: Privacy Display feature set (Samsung behaviour, software means)

- **Per-app activation** — pick protected apps in Settings → Privacy
  Display. While one is in the foreground, shield tolerance tightens to
  ≤ 6°. Detection: Accessibility window events (Maximum) /
  UsageStats polling `ForegroundAppWatcher` (Standard, needs Usage Access).
- **Notification zone (Partial Screen Privacy)** — new
  `GlanceNotificationListener` (observes only that a notification exists,
  never its content) → `NotificationZone` covers the top 16% of the
  screen at alpha 235 for 6 s or until dismissal.
- **PIN/password guard** — Maximum mode: `onAccessibilityEvent` checks
  only `isPassword` of the focused field (throttled 300 ms); focused
  password field tightens the shield like a protected app.
- **Pixel Pattern overlay** — `PatternOverlayView`: vertical stripe
  grille, coverage 15%→65% and paint alpha scale with shield intensity.
  Fullscreen only; Maximum uses a single patterned layer (no moiré).
- Shared modules: `ShieldConfig.kt`, `PrivacyDisplay.kt` (state + prefs),
  settings mirrored from Flutter (packages as CSV — see file header).

## P0 fixes folded in

- Alpha single source of truth: Standard 204 (Android 12 untrusted-touch
  ceiling), Maximum 197/layer × 2 layers ≈ 242 effective. README and
  Manifest now quote the same numbers.
- Removed personal donation QR images, bank account and phone numbers
  from the repo and the About sheet. NOTE: git history still contains
  them — deletion from HEAD is not a purge (see P0 checklist).
- Deleted `*.kt.backup`, build problem report, and leftover AI-prompt
  files (`CLAUDE.md`, `glance_architecture.md`); `.gitignore` hardened.
- Added the MIT `LICENSE` file the README badge always claimed.
- Release signing via git-ignored `android/key.properties` with debug
  fallback for local runs; Flutter template TODOs removed.
- README truth audit: removed the `google_fonts` and Kotlin Coroutines
  claims, corrected the sensor description (game rotation vector,
  SENSOR_DELAY_GAME, ~10 Hz only for the Flutter stream), removed the
  screenshot-protection claim, added an Accessibility transparency note.

## Deliberate scope cuts (do not mistake for oversights)

- The two overlay services were NOT merged in this release (that is R2
  of the refactor checklist); new logic lives in shared modules instead,
  so the merge later stays mechanical.
- Per-app/credential triggers tighten an *armed* shield; they do not
  start a stopped Standard foreground service on their own.
- No face/second-viewer detection, no location triggers, no side-key
  shortcut — not honestly implementable as a third-party app here.

## Verification status

- XML well-formed: Manifest, accessibility config, strings. ✔
- Brace/paren balance (comments stripped) = 0 for every touched
  Kotlin/Dart file, matching the originals where applicable. ✔
- Consistency greps: alpha only via `ShieldConfig`; no QR/personal
  identifiers; no leftover false README claims. ✔
- NOT compiled: the build machine has no JDK/Flutter SDK. First
  `flutter pub get` + build in Android Studio is the real gate, then
  the 8-scenario on-device matrix from the P0 checklist.
