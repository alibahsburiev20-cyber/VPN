# AliVPN 1.0.0

AliVPN is an Android VPN client built around Android `VpnService` and the official sing-box `libbox.aar` 1.14.0. It downloads public proxy configurations, validates supported formats, tries multiple candidates, creates a full-tunnel TUN, and reports `CONNECTED` only after traffic through the tunnel returns a valid external IP.

## Build

GitHub Actions downloads the official AAR from:

`https://github.com/singbox-android/libbox/releases/download/1.14.0/libbox.aar`

Then it runs `gradle :app:assembleDebug` and uploads `app-debug.apk`.

## Connection behavior

The app validates candidate configurations, starts candidates one at a time, and reports `CONNECTED` only after a real external traffic check succeeds. Failed candidates are skipped. If all candidates fail, the app shows a recoverable error instead of claiming a connection.

## Public nodes

Several independent public sources and mirrors are tried. Public nodes are third-party infrastructure and may be offline, blocked, malicious, or unstable. Never use them for sensitive traffic. No public-node list can guarantee availability.

## Verification limits

Structural checks and synthetic parser fixtures are not equivalent to a build against the real AAR or a device-level VPN test. The authoritative build check is GitHub Actions; stable runtime behavior must be verified on an Android device.

## License

Application source: GPL-3.0-or-later. The libbox/sing-box component is GPL-licensed; retain its license and source-distribution obligations when redistributing the APK.
