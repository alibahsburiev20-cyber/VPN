# AliVPN 1.0.0 — clean rebuild

AliVPN is a one-tap Android VPN client built around Android `VpnService` and `libbox 1.14.0` from sing-box. It downloads public proxy configurations, removes duplicates, tries multiple candidates, creates a full-tunnel TUN, verifies real Internet access through the tunnel, and reconnects when the tunnel becomes unusable.

## Connection flow

`CONNECT → download configs → deduplicate → last-known-good first → Android VPN permission → foreground VPN service → libbox/sing-box → TUN → external IP check → CONNECTED`

The app does not claim **CONNECTED** merely because Android granted VPN permission. The sing-box service must start successfully and the app must receive a valid external IP through the running tunnel.

## Supported public node formats

The parser/configurator supports `vless://`, `vmess://`, `trojan://`, `ss://`, `socks5://`, and `http://` nodes, including common TLS/Reality, WebSocket, gRPC, and HTTP transport parameters used by public URI lists.

## Public source

The primary source is:

`https://raw.githubusercontent.com/aviamastersgh/vpn-free-russia/main/verified_configs.txt`

A jsDelivr mirror is tried as a fallback. Public third-party nodes are inherently unreliable and can be unsafe. AliVPN does not operate those servers and cannot guarantee uptime, confidentiality, logging policy, or integrity.

## Tunnel design

The sing-box configuration uses a TUN inbound with `auto_route` and `strict_route`, IPv4 and IPv6 tunnel addresses, DNS hijacking, and `route.auto_detect_interface=true`. The Android platform layer protects libbox's physical-network sockets with `VpnService.protect(fd)` so the proxy's own upstream connection does not loop back into the VPN.

## Android integration

`VPNService` implements the full libbox 1.14.0 `PlatformInterface` surface used by the core, starts a `CommandServer`, mirrors libbox TUN route/address information into Android `VpnService.Builder`, and provides a real Android default-network monitor.

The UI has one main button that toggles between `CONNECT` and `DISCONNECT`, remembers the last successful node, and automatically retries up to 12 candidates. A background monitor rechecks external IP connectivity about every 90 seconds and triggers failover when the tunnel stops responding.

## Build

The repository includes GitHub Actions configuration. Run **Actions → Build AliVPN** after pushing the project to GitHub. The workflow uses Java 17, Gradle 8.10.2, and produces `app-debug.apk` as an artifact.

## Verification performed in this environment

The clean project passed: XML/resource/manifest/static checks; structural Kotlin compilation against a dedicated API stub for the libbox 1.14.0 surface; and 7/7 synthetic core fixtures covering VLESS, Reality, Trojan, Shadowsocks, SOCKS5, HTTP, and VMess configuration generation.

A real Android Gradle/APK build could not be executed inside this container because the Android SDK, Gradle distribution, and Maven/AAR dependency cache are not installed here, and outbound network access from the container is unavailable. The included GitHub Actions workflow is the actual Android build path.

## License

Application source: GPL-3.0-or-later. The libbox/sing-box component is GPL-licensed; retain its license and source-distribution obligations when redistributing the APK.
