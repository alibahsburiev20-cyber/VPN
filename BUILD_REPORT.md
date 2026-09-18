# AliVPN 1.0.0 verification report

Date: 2026-09-18

## Checks passed

- `scripts/check_static.py`: PASS
- Kotlin structural compile using the project sources plus an isolated Android/libbox API stub: PASS
- Core fixture execution: PASS, 7/7
- XML parsing: PASS
- Manifest permission/service checks: PASS
- Legacy libbox auto-redirect API regression checks: PASS
- Full-tunnel invariants (`tun`, `auto_route`, `strict_route`, `auto_detect_interface`, `final=proxy`): PASS

## Core fixture coverage

VLESS, VLESS Reality, Trojan, Shadowsocks, SOCKS5, HTTP proxy, and VMess all parsed and generated a TUN-based sing-box configuration in the structural test harness.

## Environment limitation

A genuine Android Gradle build and device-level VPN/TUN test were not possible in this execution environment because no Android SDK/build-tools or Gradle dependency cache is installed and outbound network access is unavailable. Therefore this report does not claim that an APK was installed or that a live third-party public node was successfully connected from this container.

The GitHub Actions workflow in `.github/workflows/build.yml` is configured to perform the actual Gradle build with Java 17 and Gradle 8.10.2 and upload `app-debug.apk`.
