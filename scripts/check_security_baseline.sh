#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "==> Checking security baseline in $ROOT_DIR"

python3 - <<'PY'
import json
import pathlib
import plistlib
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

root = pathlib.Path(".").resolve()
errors: list[str] = []

# 1. AndroidManifest.xml
manifest_path = root / "android/app/src/main/AndroidManifest.xml"
manifest_text = manifest_path.read_text(encoding="utf-8")
ns_android = "{http://schemas.android.com/apk/res/android}"
manifest_root = ET.fromstring(manifest_text)
app_el = manifest_root.find("application")
if app_el is None:
    errors.append("AndroidManifest.xml missing <application> element")
else:
    if app_el.attrib.get(f"{ns_android}allowBackup") != "false":
        errors.append("AndroidManifest.xml must set android:allowBackup=\"false\"")
    if app_el.attrib.get(f"{ns_android}usesCleartextTraffic") != "false":
        errors.append("AndroidManifest.xml must set android:usesCleartextTraffic=\"false\"")
    if app_el.attrib.get(f"{ns_android}networkSecurityConfig") != "@xml/network_security_config":
        errors.append("AndroidManifest.xml must reference @xml/network_security_config")

for perm in manifest_root.findall("uses-permission"):
    name = perm.attrib.get(f"{ns_android}name", "")
    if name in {
        "android.permission.USE_EXACT_ALARM",
        "android.permission.SCHEDULE_EXACT_ALARM",
    }:
        errors.append(f"AndroidManifest.xml must not declare forbidden permission: {name}")

# 2. network_security_config.xml
nsc_path = root / "android/app/src/main/res/xml/network_security_config.xml"
nsc_root = ET.fromstring(nsc_path.read_text(encoding="utf-8"))
base_cfg = nsc_root.find("base-config")
if base_cfg is None or base_cfg.attrib.get("cleartextTrafficPermitted") != "false":
    errors.append("network_security_config.xml must set <base-config cleartextTrafficPermitted=\"false\">")

allowed_cleartext_hosts = {"jhzf.upc.edu.cn", "dfcz.upc.edu.cn"}
seen_cleartext_hosts: set[str] = set()
for dc in nsc_root.findall("domain-config"):
    if dc.attrib.get("cleartextTrafficPermitted") == "true":
        for dom in dc.findall("domain"):
            host = (dom.text or "").strip()
            seen_cleartext_hosts.add(host)
            if dom.attrib.get("includeSubdomains") != "false":
                errors.append(f"network_security_config.xml domain {host} must set includeSubdomains=\"false\"")

if seen_cleartext_hosts != allowed_cleartext_hosts:
    errors.append(
        f"network_security_config.xml cleartext hosts mismatch: got {sorted(seen_cleartext_hosts)}, expected {sorted(allowed_cleartext_hosts)}"
    )

# 3. data_extraction_rules.xml
der_path = root / "android/app/src/main/res/xml/data_extraction_rules.xml"
der_root = ET.fromstring(der_path.read_text(encoding="utf-8"))
for section in ("cloud-backup", "device-transfer"):
    sec_el = der_root.find(section)
    if sec_el is None or len(sec_el.findall("exclude")) == 0:
        errors.append(f"data_extraction_rules.xml must contain non-empty <exclude> entries under <{section}>")

# 4. iOS Info.plist ATS checks
plist_path = root / "ios/UPCPocket/Resources/Info.plist"
with plist_path.open("rb") as fh:
    plist_data = plistlib.load(fh)

ats = plist_data.get("NSAppTransportSecurity")
if not isinstance(ats, dict):
    errors.append("Info.plist missing NSAppTransportSecurity dictionary")
else:
    if ats.get("NSAllowsArbitraryLoads") is not False:
        errors.append("Info.plist NSAllowsArbitraryLoads must be false")
    if ats.get("NSAllowsArbitraryLoadsInWebContent") is True:
        errors.append("Info.plist must not enable NSAllowsArbitraryLoadsInWebContent")
    exc = ats.get("NSExceptionDomains", {})
    if not isinstance(exc, dict) or set(exc.keys()) != allowed_cleartext_hosts:
        errors.append(f"Info.plist NSExceptionDomains mismatch: got {sorted(exc.keys()) if isinstance(exc, dict) else exc}")
    else:
        for host, cfg in exc.items():
            if not isinstance(cfg, dict):
                errors.append(f"Info.plist domain {host} entry must be dict")
                continue
            if cfg.get("NSIncludesSubdomains") is not False:
                errors.append(f"Info.plist domain {host} must set NSIncludesSubdomains=false")
            if cfg.get("NSExceptionAllowsInsecureHTTPLoads") is not True:
                errors.append(f"Info.plist domain {host} missing NSExceptionAllowsInsecureHTTPLoads=true")

# 5. specs/upc-entrypoints.json
entrypoints_path = root / "specs/upc-entrypoints.json"
try:
    ep_data = json.loads(entrypoints_path.read_text(encoding="utf-8"))
    if not isinstance(ep_data.get("entries"), list) or len(ep_data["entries"]) == 0:
        errors.append("specs/upc-entrypoints.json must contain non-empty entries list")
except Exception as exc:
    errors.append(f"specs/upc-entrypoints.json invalid JSON: {exc}")

# 6. Check git tracked files for secrets / forbidden build artifacts
if (root / ".git").exists():
    tracked = subprocess.check_output(["git", "ls-files"], text=True).splitlines()
    forbidden_suffixes = (".jks", ".keystore", ".p12", ".mobileprovision")
    for rel in tracked:
        lower = rel.lower()
        name = pathlib.Path(rel).name
        if lower.endswith(forbidden_suffixes):
            errors.append(f"Tracked secret/signing artifact forbidden: {rel}")
        if name in {"keystore.properties", "local.properties", "官方入口.txt", ".env"} or name.startswith(".env."):
            errors.append(f"Tracked sensitive/local file forbidden: {rel}")
        if "UPCPocket.xcodeproj" in rel:
            errors.append(f"Generated Xcode project must not be committed: {rel}")

# 7. Check production source files for forbidden TLS bypass patterns
forbidden_patterns = [
    (re.compile(r"\bX509TrustManager\b"), "custom X509TrustManager"),
    (re.compile(r"\bALLOW_ALL_HOSTNAME_VERIFIER\b"), "ALLOW_ALL_HOSTNAME_VERIFIER"),
    (re.compile(r"\bAllowAllHostnameVerifier\b"), "AllowAllHostnameVerifier"),
    (re.compile(r"SslErrorHandler.*\bproceed\s*\("), "SslErrorHandler.proceed()"),
    (re.compile(r"\ballowsAnyHTTPSCertificate"), "allowsAnyHTTPSCertificate"),
]
source_roots = [
    root / "android/app/src/main",
    root / "ios/UPCPocket",
]
for src_root in source_roots:
    for path in src_root.rglob("*"):
        if not path.is_file() or path.suffix not in {".kt", ".java", ".swift"}:
            continue
        content = path.read_text(encoding="utf-8", errors="ignore")
        for line_no, line in enumerate(content.splitlines(), start=1):
            stripped = line.strip()
            if stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*"):
                continue
            for pat, desc in forbidden_patterns:
                if pat.search(line):
                    errors.append(f"Forbidden TLS bypass pattern ({desc}) in {path.relative_to(root)}:{line_no}")

if errors:
    for err in errors:
        print(f"ERROR: {err}", file=sys.stderr)
    sys.exit(1)

print("Security baseline check passed: Android manifest/NSC/backup, iOS ATS, specs JSON, tracked files, and TLS checks clean.")
PY
