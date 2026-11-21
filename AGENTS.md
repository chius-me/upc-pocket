# AGENTS.md — UPC Pocket 双端仓库协作指南

本文件适用于 `/Users/chius/repo/github/upc/upc-pocket`。改动任意模块前，请先阅读对应 `specs/` 文档。

## 1. 仓库定位与架构边界

- **同仓独立双端纯原生**：`specs/` 为唯一跨端行为与安全规范来源；`android/`（Kotlin + Jetpack Compose）与 `ios/`（Swift 6 + SwiftUI + XcodeGen）完全独立构建，禁止引入 Flutter、KMP 或共享运行时。
- **参考仓库只读原则**：`/Users/chius/repo/github/upc/UPCPocket` 仅作协议、入口和历史边界的只读参考，禁止在本仓库任务中修改旧仓文件。
- **目录职责**：
  - `specs/`：架构、数据契约、安全基线、视觉规范、官方入口清单。
  - `android/`：单 `:app` 模块清晰分层（`core/model`, `core/data`, `core/webview`, `core/designsystem`, `navigation`, `feature/*`）。不恢复旧版 18 子模块结构。
  - `ios/`：Swift 6 严格并发（`SWIFT_STRICT_CONCURRENCY=complete`），工程由 `ios/project.yml` 生成，不将 `.xcodeproj` 提交入库。

## 2. 必须遵守的安全与产品底线

1. **云备份关闭**：
   - Android 保持 `allowBackup="false"`、`data_extraction_rules.xml` 与 `backup_rules.xml` 全域排除，所有私有偏好与加密凭据写入 `noBackupFilesDir`。
   - iOS 保持所有应用私有存储目录 `isExcludedFromBackup = true`，Keychain 属性强制 `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`。
2. **网络安全基线**：
   - 默认全局禁止明文 HTTP，禁止绕过或信任自签/过期证书（含 `gzkdf.upc.edu.cn`）。
   - 仅放行已核实无 HTTPS 的精确域名 `jhzf.upc.edu.cn` 与 `dfcz.upc.edu.cn`（禁止 `includeSubdomains=true`）。
3. **账号与隐私诚实表达**：
   - 本地保存学校账号后的状态固定为 `SAVED_UNVERIFIED`（界面展示「已保存（未验证）」），禁止显示「已登录」或「认证通过」。
   - 密码输入保存或离页后立即清零，永不回显、永不写入日志/异常/诊断/测试快照。
   - 仓库内严禁提交真实学号、密码、Cookie、Token、Keystore 文件或未脱敏学生业务数据。
4. **全异步本地 I/O**：所有文件、DataStore、Keystore/Keychain、SQLite 读写必须在后台调度器或 Swift `actor` 中完成。
5. **高风险写操作后置**：现阶段聚焦只读与本机工具能力，校园卡微信充值、自动抢座、签到、代提交等写操作未获明确计划前禁止引入。
6. **唯一受控 WebView**：后续验证码/二次验证回退全端只维护 1 个通用受控组件，严格校验目标 HTTPS 域名与当前 `accountId` + `generation`。

## 3. 构建与验证规范

- 修改涉及双端共享模型（如 `StudentIdValidator`、`CampusPreference`、WebView 域名白名单）时，必须同步更新 `specs/` 并同时运行 Android 与 iOS 单测保证语义一致。
- **Android 版本锁**：当前 AGP `8.13.2` + `compileSdk 36`，禁止擅自升级 `material3` 至 `1.5.0+`（其要求 `minCompileSdk=37` 与 `AGP 9.1+`）。使用 `compose-bom:2026.06.01`（`material3:1.4.0`）。
- **iOS 兼容性**：部署目标为 `iOS 18.0`，所有 iOS 26+ Liquid Glass API（`.glassEffect`、`GlassEffectContainer`、`.tabBarMinimizeBehavior`）必须包裹在 `if #available(iOS 26.0, *)` 内并提供 iOS 18 标准材质回退。
- **验证命令**：
  - Android：`cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
  - iOS：`cd ios && xcodegen generate && xcodebuild -project UPCPocket.xcodeproj -scheme UPCPocket -destination 'platform=iOS Simulator,name=iPhone 17' CODE_SIGNING_ALLOWED=NO build test`
- 交付报告只陈述本轮实际运行观察到的文件、构建结果与测试输出，不虚构未验证项。
