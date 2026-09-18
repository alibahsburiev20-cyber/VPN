# AliVPN verification report

## Current status

The project uses the official `libbox.aar` 1.14.0 release asset. GitHub Actions downloads the AAR before running `:app:assembleDebug`.

## Checks

- Static XML/resource checks: previously passed.
- Parser/config fixture checks: previously passed for 7 synthetic formats.
- Structural Kotlin checks: passed only against an isolated API stub; this does not prove compatibility with the real AAR.
- Real Android Gradle build: pending the GitHub Actions run for the workflow that downloads the real AAR.
- Device-level VPN/TUN test: not performed here and must be run on a real Android device.

## Runtime acceptance

`CONNECTED` is emitted only after libbox starts a candidate and external traffic returns a valid IP. Failed candidates are skipped. If all candidates fail, the app reports a recoverable error rather than claiming a connection.

Public nodes are third-party and can be unavailable, blocked, malicious, or unstable. No public-node list guarantees availability.
