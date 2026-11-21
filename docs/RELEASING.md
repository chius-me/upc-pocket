# CI/CD 与发版指南（GitHub Actions）

本仓库通过 `.github/workflows/` 两条工作流实现自动持续集成与跨平台发版。

---

## 1. 持续集成：[`.github/workflows/ci.yml`](../.github/workflows/ci.yml)

每次向 `main` 分支 `push`、发起 `pull_request` 或手动触发 `workflow_dispatch` 时，自动并行运行三项检查：

1. **`security-baseline`（`ubuntu-latest`）**：执行 [`scripts/check_security_baseline.sh`](../scripts/check_security_baseline.sh)，强制校验：
   - Android `AndroidManifest.xml` 关闭 `allowBackup` / 全局明文 HTTP，未声明 `USE_EXACT_ALARM` / `SCHEDULE_EXACT_ALARM`；
   - Android `network_security_config.xml` 与 iOS `Info.plist` 仅对 `jhzf.upc.edu.cn` 与 `dfcz.upc.edu.cn` 精确放行 HTTP（`includeSubdomains=false` / `NSIncludesSubdomains=false`）；
   - `data_extraction_rules.xml` 在云备份与跨机传输中均存在显式 `<exclude>` 规则；
   - `specs/upc-entrypoints.json` JSON 合法、Git 追踪列表不含 `.jks` / `.keystore` / `.p12` / `keystore.properties` / 生成态 `.xcodeproj`、源码不含绕过 TLS 校验的模式。
2. **`android-ci`（`ubuntu-latest`）**：使用 Temurin JDK 21 运行 `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`，校验 Debug/Release Merged Manifest，并上传测试报告与 APK 构建产物。
3. **`ios-ci`（`macos-26`）**：使用 `plutil -lint` 校验 `Info.plist`、`xcodegen generate` 生成工程、动态选取可用 iPhone Simulator（[`scripts/select_ios_simulator.py`](../scripts/select_ios_simulator.py)）执行全部 XCTest，最后运行 `-configuration Release -destination 'generic/platform=iOS'` 验证 Release 优化编译。

---

## 2. 发布流水线：[`.github/workflows/release.yml`](../.github/workflows/release.yml)

### 触发方式

1. **Tag 自动发版**：推送符合 SemVer 的 Tag（含 `-rc.1` 等后缀时自动标为 GitHub Prerelease）：
   ```bash
   git tag v0.2.0
   git push origin main v0.2.0
   ```
2. **手动触发**：在 GitHub Actions 页面选择 `Release` -> `Run workflow`，填写 `tag`（如 `v0.2.0`）、可选 `version_code`、`prerelease` 与 `publish_testflight`。

### 产物与签名行为

- **默认无 Secrets 场景**：
  - Android 在执行完单元测试后构建 R8 混淆 Release 包，输出明确标注的 `UPCPocket-android-v<version>-unsigned.apk`（**绝不使用 debug 签名冒充发布包**）；
  - iOS 在跑完 Simulator XCTest 后归档未签名产物 `UPCPocket-ios-v<version>-unsigned.ipa`；
  - `publish-release` 汇总产物与 `SHA256SUMS.txt` 并创建 GitHub Release。
- **配置完整 Secrets 后（正式签名发版）**：
  - Android 自动生成 signed APK（并经 `apksigner verify` 校验）+ Google Play AAB；
  - iOS 自动创建临时隔离 Keychain、导入证书与描述文件，导出已签名 `.ipa` 与 `dSYM.zip`；当手动勾选 `publish_testflight` 且配置 App Store Connect API Key 时自动上传至 TestFlight；
  - 全部签名凭据均在 `if: always()` 清理步骤中从 Runner 删除。

---

## 3. GitHub Repository Secrets 配置说明

在仓库 `Settings -> Secrets and variables -> Actions` 中按需配置：

### Android 正式签名

| Secret 名称 | 说明 | 本地生成参考 |
| --- | --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Release `.jks`/`.keystore` 的单行 Base64 | `base64 -i release.jks \| tr -d '\n'` |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore 密码 | — |
| `ANDROID_KEY_ALIAS` | Key Alias | — |
| `ANDROID_KEY_PASSWORD` | Key 密码 | — |

### iOS 正式签名与 TestFlight

| Secret 名称 | 说明 | 本地生成参考 |
| --- | --- | --- |
| `IOS_CERTIFICATE_BASE64` | 导出自钥匙串的 `.p12` 分发证书单行 Base64 | `base64 -i dist.p12 \| tr -d '\n'` |
| `IOS_CERTIFICATE_PASSWORD` | `.p12` 导入密码 | — |
| `IOS_PROVISIONING_PROFILE_BASE64` | 匹配 `me.chius.upcpocket` 的 `.mobileprovision` Base64 | `base64 -i profile.mobileprovision \| tr -d '\n'` |
| `IOS_TEAM_ID` | Apple Developer Team ID（10 位字母数字） | — |
| `IOS_EXPORT_METHOD` | 可选，默认 `app-store-connect`（也可设 `ad-hoc`、`release-testing` 等） | — |
| `IOS_CODE_SIGN_IDENTITY` | 可选，默认 `Apple Distribution` | — |
| `APP_STORE_CONNECT_KEY_ID` | 可选（TestFlight 上传需）API Key ID | — |
| `APP_STORE_CONNECT_ISSUER_ID` | 可选（TestFlight 上传需）Issuer UUID | — |
| `APP_STORE_CONNECT_API_KEY_BASE64` | 可选（TestFlight 上传需）`.p8` 私钥单行 Base64 | `base64 -i AuthKey_XXXX.p8 \| tr -d '\n'` |
