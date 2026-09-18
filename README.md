# AliVPN 1.0.0

AliVPN is an Android VPN client built around Android `VpnService` and the official sing-box `libbox.aar` 1.14.0. It downloads public proxy configurations, validates supported formats, tries multiple candidates, creates a full-tunnel TUN, and reports `CONNECTED` only after traffic through the tunnel returns a valid external IP.

## Connection flow

`CONNECT → download sources → deduplicate → validate configs → Android VPN permission → foreground VPN service → libbox/sing-box → TUN → real traffic check → CONNECTED`

A syntactically valid configuration is not considered a working node. Each candidate must start libbox and pass the external traffic check before the UI reports `CONNECTED`.

## Build

The app uses the official `libbox.aar` release asset. GitHub Actions downloads it from:

`https://github.com/singbox-android/libbox/releases/download/1.14.0/libbox.aar`

Then it runs:

`gradle :app:assembleDebug`

and uploads `app-debug.apk`.

## Public nodes

Several public sources are tried, including mirrors and independent repositories. Public nodes are third-party infrastructure and may be offline, blocked, malicious, or unstable. Never use them for sensitive traffic. The app must skip candidates that fail configuration or real traffic validation.

## Verification status

A stub-based structural test is not equivalent to building against the real AAR. The authoritative check is the GitHub Actions build and, separately, installation and VPN testing on a real Android device.

## License

Application source: GPL-3.0-or-later. The libbox/sing-box component is GPL-licensed; retain its license and source-distribution obligations when redistributing the APK.
