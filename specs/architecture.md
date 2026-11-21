# 架构规范（Architecture）

## 1. 总体路线：同仓完全独立双端纯原生

- 同一仓库内并行维护 `specs/`、`android/` 与 `ios/`。
- **零跨语言工具链摩擦**：不引入 Flutter、Kotlin Multiplatform (KMP)、React Native 或 Web 壳，避免多引擎桥接、平台 UI 妥协与构建链路互相绑架。
- `specs/` 作为双端契约的单一事实来源（Single Source of Truth）：状态枚举、学号正则/校验规则、加密代数语义、网络放行名单、视觉主色与页面结构在 `specs/` 对齐后，分别用 Kotlin 和 Swift 原生实现。

## 2. 信息架构（4 Tab + 统一设置入口）

两端遵循完全一致的顶层信息架构：

1. **首页 (`Home`)**：
   - 顶栏标题 `UPC Pocket`，**右上角提供唯一的「设置」入口**（不重建平行的「更多」菜单）。
   - 展示常用校区指示胶囊、学校账号状态卡（未保存 / 已保存未验证 / 需要重新保存）、M1 业务概览卡（今日课程、校园卡余额、宿舍电量占位）与快捷服务跳转。
2. **课表 (`Timetable`)**：
   - 顶部预留「今日日程待办」卡片（延续 2.1.0 规划，待办与当日课程同一视图可见）。
   - 下方为周日–周六连续周视图及官方 1–12 节作息时间骨架。
3. **服务 (`Services`)**：
   - 顶部支持按常用校区筛选（`全部 / 唐岛湾 / 古镇口`），与设置页「常用校区」双向同步。
   - 分组展示：
     - **校园查询**：空闲教室、唐岛湾电量 / 古镇口电费外链、校园卡余额、图书馆座位。
     - **校园官方网页**：校历（教务处）、教务系统、数字石大、教务处官网、学院官网（通过系统浏览器打开官方 HTTPS 页面）。
     - **后续只读规划**：石光（第二课堂）、e站通（标明后置阶段接入，只读不写）。
4. **专注 (`Focus`)**：
   - 本地 25 分钟番茄钟计时器与专注模式切换，零校园网络依赖；M0 阶段即可完整交互。

### 二级页面（不占用底部导航栏）

- **设置 (`Settings`)**：学校账号入口、常用校区选择、主题外观配置（系统 / 浅色 / 深色，Android 含动态取色开关）、安全与隐私基线说明、关于入口。
- **学校账号 (`SchoolAccount`)**：学号/账号输入（实时校验本科及研究生学号格式）、密码输入（加密后立即清零，不回显）、状态徽章、删除学校账号确认、隐私边界说明。
- **关于 (`About`)**：应用版本、非学校官方工具声明、MIT 开源协议与致谢。

## 3. Android 端分层（单应用清晰包分层）

放弃旧版 18 子模块的多 Gradle 子工程开销，采用单 `:app` 模块内清晰包分层：

```text
android/app/src/main/kotlin/me/chius/upcpocket/
├── UpcPocketApplication.kt        # 手动轻量 AppContainer 装配，无 Hilt 编译期魔法
├── MainActivity.kt                # enableEdgeToEdge() + 宿主主题 + NavHost
├── core/
│   ├── model/                     # 纯领域模型与学号校验器（无 Android 框架依赖）
│   ├── data/                      # DataStore 偏好、Keystore AES-GCM 密文、账号仓库
│   ├── webview/                   # 唯一受控 WebView 验证码回退契约
│   └── designsystem/              # MD3 Expressive 主题、分段卡片形状与基础组件
├── navigation/                    # 4 Tab 路由与子页面导航宿主
└── feature/
    ├── home/                      # 首页
    ├── timetable/                 # 课表 + 顶部日程待办占位
    ├── services/                  # 校园服务目录 + 校区筛选
    ├── focus/                     # 番茄钟交互原型
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
│   ├── Home/
│   ├── Timetable/
│   ├── Services/
│   ├── Focus/
│   ├── Settings/
│   └── Account/
└── Resources/
    ├── Info.plist                 # 最小化 ATS 例外配置
    └── Assets.xcassets/
```
