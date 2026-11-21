# UPC Pocket

面向中国石油大学（华东）的非官方、同仓完全独立双端纯原生校园工具。

> 本应用为非学校官方客户端，校园查询与业务直连学校对应服务端，不经过自建账号或数据中转服务器。

## 仓库结构

```text
upc-pocket/
├── .github/workflows/  # CI 自动测试/R8/XcodeGen 检查与 Tag/手动发布流水线
├── docs/               # 发布指引等工程文档
├── scripts/            # 安全基线检查与 iOS Simulator 动态选择脚本
├── specs/              # 双端共享架构、数据契约、视觉规范、安全基线与 UPC 官方入口目录
├── android/            # Android 纯原生客户端（Kotlin + Jetpack Compose + Material 3 / Pixel Bento UI）
├── ios/                # iOS 纯原生客户端（Swift 6 + SwiftUI，iOS 26+ Liquid Glass + iOS 18 回退）
├── LICENSE
└── THIRD_PARTY_NOTICES.md
```

## Android V2 体验革新（v0.3.0）

- **Google Pixel Bento 3-Tab 信息架构**：精简聚焦为 **`今日 / 课表 / 校园`** 三大主页，采用清透低饱和背景（`#EEF3F8`）与高对比纯白 Squircle 指标卡，提供 4 套主题配色与动态取色切换。
- **今日智能仪表盘**：进入超过 15 分钟自动静默同步课表、校园卡与绑定宿舍电量，并支持下拉刷新；顶部 Hero 卡实时倒计时当前/下节课程与图书馆预约状态。
- **滑动周课表**：顶栏紧凑切换 `第 X 周 ▾` 与 `回本周`，支持左右滑动 `HorizontalPager` 翻周、周网格与日程流双视图切换，并自动收窄空周末列。
- **完整校园服务矩阵**：
  - **成绩与 GPA 推演**：直连教务系统（`jwxt.upc.edu.cn`）解析成绩单与 5.0 绩点，支持筛选预设、勾选剔除、单科分数实时模拟推演。
  - **校园卡钱包**：快速余额、月度账单流水、近 7 日消费柱图与人工确认的微信 H5 充值闭环。
  - **图书馆选座与预约管理**：支持唐岛湾 `libseat`（选座提交 `freeBook` + `cancel` 取消预约）及古镇口/求是超星预约。
  - **石光第二课堂**：`dekt.upc.edu.cn` 活动广场搜索详情与五大模块学分/学时成绩单。
  - **宿舍电量常用绑定**：支持「设为常用宿舍」在首页卡片自动追踪；古镇口跳转官方 HTTPS 页。
  - **空闲教室 / 官方高清校历 / GitHub Releases 更新检查**：空教室默认定位当前作息节次，校历支持双指缩放高清查看与本地缓存。

> iOS 端当前保持 M2b 稳定基线并通过完整 CI 门禁，将在下一阶段同步对接 V2 功能与交互体系。

## 核心安全基线

1. **禁止系统云备份与跨机迁移**：Android 设置 `allowBackup="false"` 并显式排除全部数据域、凭据与敏感文件落盘至 `noBackupFilesDir`；iOS 对所有私有存储目录设置 `isExcludedFromBackup = true`，Keychain 使用 `ThisDeviceOnly`。
2. **最小化明文例外**：全量默认强制 HTTPS 且不绕过证书校验；仅对已核实无 HTTPS 服务的 `jhzf.upc.edu.cn` 与 `dfcz.upc.edu.cn` 精确放行明文（不含子域名）。
3. **凭据加密与内存边界**：学校账号密码保存或离页即清零，永不回显、不写日志、不称「已登录」；Android 使用 Keystore AES-256-GCM 加密，iOS 使用 Keychain 存储；换号/删号/改密递增 `generation`。
4. **全异步本地 I/O**：所有磁盘、DataStore、Keystore/Keychain 操作均在异步线程/`actor` 内执行。
5. **唯一受控 WebView 契约**：全端仅维护 1 个受控官方登录/验证码回退容器，限定精确 HTTPS 校方域名，不导入外部浏览器 Cookie。

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
- 校园服务入口与协议语义参考自 [UPCPocket](https://github.com/dlam12138/UPCPocket) 与 [UPC-MCP](https://github.com/dlam12138/UPC-MCP)（MIT License），详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
