# 架构规范（Architecture）

## 1. 总体路线：同仓完全独立双端纯原生

- 同一仓库内并行维护 `specs/`、`android/` 与 `ios/`。
- **零跨语言工具链摩擦**：不引入 Flutter、Kotlin Multiplatform (KMP)、React Native 或 Web 壳，避免多引擎桥接、平台 UI 妥协与构建链路互相绑架。
- `specs/` 作为双端契约的单一事实来源（Single Source of Truth）：状态枚举、学号正则/校验规则、加密代数语义、网络放行名单、视觉主色与页面结构在 `specs/` 对齐后，分别用 Kotlin 和 Swift 原生实现。

## 2. 信息架构（Android V2 · Google M3 Expressive 极简去重 / iOS M2b 待同步）

### Android V2（当前主线 · v0.3.1）

Android 端采用 **3 个职责单一、零入口重复的顶级 Tab + 全屏业务子页面**：

1. **今日 (`Today`)**：
   - 顶部为 Gmail / Google 风格悬浮圆角搜索胶囊（`GoogleSearchPillBar`）：集成当前周次状态与唯一设置头像入口，点击胶囊弹出即时本地全局快搜 BottomSheet（支持筛选全部服务与本周课程）。
   - 进入超 15 分钟且已配置账号时自动触发并行刷新（课表、校园卡余额、已绑定宿舍电量），支持下拉手动同步（含图书馆）。
   - 顶部 **1 条紧凑三列概览条**（校园卡、宿舍电量、GPA）+ **1 张聚焦 At-a-Glance Hero 卡**（正在上课/下节课程倒计时，并联动展示当前有效图书馆预约）+ M3 Expressive 分段圆角列表呈现今日日程。
2. **课表 (`Timetable`)**：
   - 紧凑单行顶栏：`第 X 周 ▾` BottomSheet（含学期切换与 1–N 周网格）、`回本周` 胶囊、周网格/日程流切换、同步刷新。
   - `HorizontalPager` 支持左右滑动翻周；无课周末列自动缩窄，最大化教学周网格可读性。
3. **校园 (`Campus`)**：
   - 与首页同一规格的 `GoogleSearchPillBar` 顶栏 + 内联 M3 `SingleChoiceSegmentedButtonRow` 校区筛选（`唐岛湾 / 古镇口 / 全部`）。
   - 对标 Gmail / Chrome 设置界面的 **3 组 M3 Expressive 分段圆角列表卡片**（`24.dp` 外角 / `4.dp` 内角 / `2.dp` 行间距）：
     - `学业与教务`：`成绩与 GPA`、`石光第二课堂`、`官方校历`
     - `学习空间`：`图书馆选座`、`空闲教室`
     - `校园生活`：`校园卡与充值`、`宿舍电量`

### 二级全屏页面（不占用底部导航栏）

- **成绩与 GPA 推演 (`Grades`)**：强智教务成绩单解析（`jwxt.upc.edu.cn`）、5.0 绩点、加权均分、筛选预设、剔除/模拟分实时推演、受控 WebView 回退。
- **校园卡钱包 (`CardWallet`)**：快速余额（`jhzf.upc.edu.cn`）与 `mb-xyk.upc.edu.cn` 月度流水/7 日消费柱图/收支统计，以及先本地留痕后单次下单拉起微信 H5 的校园卡充值。
- **图书馆选座 (`LibrarySeat`)**：唐岛湾 `libseat`（选座提交 `freeBook` + `cancel` 取消预约）与古镇口/求是超星 `seat` / `seatengine`（选座提交 `submit` + 风控检测回退）。
- **空闲教室 (`FreeClassroom`)**：按当前作息节次自动定位、时段/楼栋筛选与卓越 A/B 筛选。
- **宿舍电量 (`Power`)**：唐岛湾防模糊匹配电费查询与「设为常用宿舍」首页自动追踪，古镇口电费跳转官方 HTTPS 页面。
- **石光第二课堂 (`Shiguang`)**：`dekt.upc.edu.cn` 活动搜索、分页、详情纯文本展示与学分/学时五模块成绩单（强制学号一致性校验）。
- **官方校历 (`AcademicCalendar`)**：自动获取教务处最新学年校历图片，支持双指缩放/双击放大高清阅读与 `noBackupFilesDir` 离线缓存。
- **设置 / 学校账号 / 关于 (`Settings / SchoolAccount / About`)**：4 套主题配色种子、动态取色开关、常用校区、绑定宿舍摘要、GitHub Releases 应用更新检查。

### iOS 端现状

- `ios/` 当前维持 M2b 基线（4 Tab 与基础只读业务）并通过 CI XCTest 与 Release 编译门禁；待 Android V2 真机体验定版后同步对标升级 iOS V2。

## 3. Android 端分层（单应用清晰包分层）

放弃旧版 18 子模块的多 Gradle 子工程开销，采用单 `:app` 模块内清晰包分层：

```text
android/app/src/main/kotlin/me/chius/upcpocket/
├── UpcPocketApplication.kt        # 手动轻量 AppContainer 装配，统一账号失效回调与启动恢复
├── MainActivity.kt                # enableEdgeToEdge() + 宿主主题 + NavHost
├── core/
│   ├── model/                     # 纯领域模型、ColorSeed、Campus/Theme 偏好、学号校验器
│   ├── data/                      # DataStore 偏好、Keystore AES-GCM 密文、账号仓库
│   ├── auth/                      # CAS / Jwxt / Card / Library / Chaoxing / Shiguang 认证器
│   ├── webview/                   # 唯一受控 WebView 验证码回退契约
│   ├── update/                    # GitHub Releases SemVer 更新检查器
│   └── designsystem/              # Pixel Bento 组件库（GoogleTopBar / BentoMetricTile / HeroCard）与 4 套配色
├── navigation/                    # 3-Tab 导航宿主与二级全屏路由
└── feature/
    ├── home/                      # 今日仪表盘（Smart Auto-Sync）
    ├── timetable/                 # 滑屏周课表与日程流
    ├── services/                  # 校园 Bento 聚合页
    ├── grades/                    # 教务成绩与 GPA 模拟器
    ├── card/                      # 校园卡余额、流水、消费柱图与微信充值
    ├── library/                   # 唐岛湾 libseat & 超星馆区选座预约/取消
    ├── classroom/                 # 空闲教室
    ├── power/                     # 宿舍电量与常用宿舍绑定
    ├── shiguang/                  # 石光第二课堂
    ├── calendar/                  # 官方校历高清缓存查看
    ├── settings/                  # 设置与关于页
    └── account/                   # 学校账号配置页
```

## 4. iOS 端分层（Swift 6 + XcodeGen）

采用 XcodeGen（`ios/project.yml`）声明式管理 Target，开启 `SWIFT_STRICT_CONCURRENCY = complete`：

```text
ios/UPCPocket/
├── App/
│   ├── UPCPocketApp.swift         # @main 入口，异步初始化无备份排除与状态加载
│   ├── AppEnvironment.swift       # @Observable @MainActor 根状态容器
│   └── RootTabView.swift          # 原生 TabView + iOS 26 悬浮底栏最小化支持
├── Core/
│   ├── Models/                    # Sendable 模型、StudentIdValidator、官方入口目录
│   ├── Storage/                   # LocalStorageExclusion、PreferencesStore、Keychain、SchoolAccountStore
│   └── WebView/                   # ControlledWebViewPolicy
├── DesignSystem/
│   ├── UPCTheme.swift             # 品牌色彩与排版尺度
│   └── LiquidGlassModifiers.swift # iOS 26 .glassEffect / GlassEffectContainer 与 iOS 18 回退
├── Features/
│   └── (M2b baseline views)
└── Resources/
    ├── Info.plist                 # 最小化 ATS 例外配置
    └── Assets.xcassets/
```
