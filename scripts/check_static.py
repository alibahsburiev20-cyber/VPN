#!/usr/bin/env python3
import json
import re
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
errors = []

# XML validity
for p in (ROOT / "app/src/main/res").rglob("*.xml"):
    try:
        ET.parse(p)
    except Exception as e:
        errors.append(f"XML invalid: {p}: {e}")

# Android resource extensions: only xml in values/drawable/layout here.
for p in (ROOT / "app/src/main/res").rglob("*"):
    if p.is_file() and p.suffix not in {".xml", ".png", ".webp", ".jpg", ".jpeg", ".9.png"}:
        errors.append(f"Unexpected resource file: {p}")

manifest = ROOT / "app/src/main/AndroidManifest.xml"
text = manifest.read_text()
for required in (
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
    "android.permission.BIND_VPN_SERVICE",
):
    if required not in text:
        errors.append(f"Manifest missing {required}")

# Critical API guards: don't regress into the old auto-redirect/two-platform mistakes.
service = (ROOT / "app/src/main/java/com/alivpn/app/VPNService.kt").read_text()
platform = (ROOT / "app/src/main/java/com/alivpn/app/PlatformInterfaceWrapper.kt").read_text()
for forbidden in ("CreateAutoRedirect", "UsePlatformAutoRedirect", ".toIpPrefix()", "asList()"):
    if forbidden in service or forbidden in platform:
        errors.append(f"Forbidden legacy API reference: {forbidden}")

# Ensure full-tunnel invariants exist.
sing = (ROOT / "app/src/main/java/com/alivpn/app/SingBoxConfig.kt").read_text()
for token in ('"type", "tun"', '"auto_route", true', '"auto_detect_interface", true', '"final", "proxy"'):
    if token not in sing:
        errors.append(f"Missing tunnel invariant: {token}")

# Ensure GitHub build uses a real Gradle distribution and output artifact.
wf = (ROOT / ".github/workflows/build.yml").read_text()
if "assembleDebug" not in wf or "app-debug.apk" not in wf:
    errors.append("Build workflow does not build/verify APK")

# Basic parser fixtures sanity using URI grammar checks.
fixtures = ROOT / "tests/fixtures.txt"
for i, line in enumerate(fixtures.read_text().splitlines(), 1):
    line = line.strip()
    if not line or line.startswith("#"):
        continue
    if not re.match(r"^(vless|vmess|trojan|ss|socks5|http)://", line):
        errors.append(f"Fixture {i} unsupported URI scheme")

# Kotlin/API structure guards for the exact libbox 1.14.0 integration.
if "override fun localDNSTransport()" not in platform:
    errors.append("PlatformInterface localDNSTransport() is not implemented")
if "override fun usePlatformAutoDetectInterfaceControl(): Boolean = true" not in platform:
    errors.append("PlatformInterface auto-detect control is not enabled")
if "vpn.protect(fd)" not in platform:
    errors.append("VPN socket protection is missing")
if "server.startOrReloadService" not in service or "Libbox.checkConfig" not in service:
    errors.append("VPN service missing libbox config validation/start path")
if "fetchExternalIp()" not in service or 'if (ip.isNotBlank())' not in service:
    errors.append("VPN service missing post-connect traffic validation")
if "org.jetbrains.kotlinx:kotlinx-coroutines-android" not in (ROOT / "app/build.gradle.kts").read_text():
    errors.append("Missing coroutine Android dependency")

if errors:
    for e in errors:
        print("ERROR:", e)
    raise SystemExit(1)

print("STATIC CHECK OK")
print("Files checked:", sum(1 for _ in ROOT.rglob("*")))
