# UPC Pocket

面向中国石油大学（华东）的非官方、同仓完全独立双端纯原生校园工具。

> 本应用为非学校官方客户端，校园查询直接访问学校对应服务端，不经过自建账号或数据中转服务器。

## 仓库结构

```text
upc-pocket/
├── specs/       # 双端共享架构、数据契约、视觉规范、安全基线与 UPC 官方入口目录
├── android/     # Android 纯原生客户端（Kotlin + Jetpack Compose + Material 3 / M3 Expressive）
├── ios/         # iOS 纯原生客户端（Swift 6 + SwiftUI，iOS 26+ Liquid Glass + iOS 18 回退）
├── LICENSE
└── THIRD_PARTY_NOTICES.md
```

## 技术选型与原则

- **同仓独立双端纯原生**：不使用 Flutter、KMP 或跨端桥接层，两端各自遵循平台最地道的设计规范与系统特性。
- **Android 端**：Kotlin 2.3.10 + Jetpack Compose + Material 3 (`1.4.0` / BOM `2026.06.01`)，支持 Android 12+ 动态取色、标准 `NavigationBar`、折叠顶栏（`LargeTopAppBar`）、M3 分组卡片（24dp/6dp 分段圆角）、Edge-to-Edge 与预测性返回；单 `:app` 模块按功能清晰分层。
- **iOS 端**：Swift 6（严格并发检查）+ SwiftUI，采用 XcodeGen（`project.yml`）管理；以 iOS 26+ 原生 Liquid Glass（原生悬浮 `TabView`、玻璃胶囊 toolbar、`.glassEffect()`）为基准，同时向 iOS 18 优雅回退标准 `.ultraThinMaterial` 与 `.insetGrouped` 规范。

## 当前阶段：M0 骨架

- **4 个顶级 Tab**：`首页 / 课表 / 服务 / 专注`
- **设置与账号骨架**：首页右上角进入「设置」，包含「学校账号（只保存本机加密副本，状态严格显示未保存 / 已保存未验证）」「常用校区」「外观」「安全与隐私」「关于」。
- **交互原型**：内置「专注」Tab 纯内存 25 分钟番茄钟计时交互原型，以及「服务」Tab 校区筛选与校园官方 HTTPS 网页外链。
- **后续演进**：先做课表、空闲教室、电量、校园卡余额及图书馆座位等基础/只读能力；校园卡充值等高风险写操作明确后置。

## 核心安全基线

1. **禁止系统云备份与跨机迁移**：Android 设置 `allowBackup="false"` 并显式排除全部数据域、凭据与敏感文件落盘至 `noBackupFilesDir`；iOS 对所有私有存储目录设置 `isExcludedFromBackup = true`，Keychain 使用 `ThisDeviceOnly`。
2. **最小化明文例外**：全量默认强制 HTTPS 且不绕过证书校验；仅对已核实无 HTTPS 服务的 `jhzf.upc.edu.cn` 与 `dfcz.upc.edu.cn` 精确放行明文（不含子域名）。
3. **凭据加密与内存边界**：学校账号密码保存或离页即清零，永不回显、不写日志、不称「已登录」；Android 使用 Keystore AES-256-GCM 加密，iOS 使用 Keychain 存储；换号/删号/改密递增 `generation`。
4. **全异步本地 I/O**：所有磁盘、DataStore、Keystore/Keychain 操作均在异步线程/actor 内执行。
5. **唯一受控 WebView 契约**：后续仅维护 1 个受控官方登录/验证码回退容器，限定精确 HTTPS 校方域名，不导入外部浏览器 Cookie。

## 构建与测试

### Android

需配置 JDK 17+（推荐 OpenJDK 21）与 Android SDK 36（`android/local.properties` 写入 `sdk.dir=...`）：

```bash
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

### iOS

需 macOS + Xcode（iOS 18+ 部署目标，推荐 iOS 26+ SDK）与 XcodeGen：

```bash
cd ios
xcodegen generate
xcodebuild -project UPCPocket.xcodeproj -scheme UPCPocket \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  CODE_SIGNING_ALLOWED=NO build test
```

## 开源许可与致谢

- 本仓库代码采用 [MIT License](LICENSE)。
- 校园服务入口与只读协议语义参考自 [UPCPocket](https://github.com/dlam12138/UPCPocket) 与 [UPC-MCP](https://github.com/dlam12138/UPC-MCP)（MIT License），详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
