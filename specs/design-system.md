# 视觉与交互设计规范（Design System）

遵循“**各自平台最原生、最地道的设计语言**”原则：不强行在 Android 画 iOS 玻璃，也不在 iOS 套用 Material 卡片。

## 1. 品牌色板（Brand Seed Palette）

灵感取自中国石油大学（华东）青岛校区海洋深蓝与校园生态绿：

| 角色 | Hex | 用途 |
| --- | --- | --- |
| **UPC Deep Blue** (`primary`) | `#0E5A8A`（深色模式 `#7CCBFF`） | 品牌种子主色、主要 CTA、Active Tab 高亮、iOS `AccentColor` |
| **Ocean Container** (`primaryContainer`) | `#D3E9FF`（深色 `#004B74`） | 强调卡片底色、状态胶囊底色 |
| **Campus Jade** (`secondary`) | `#1B6B4A`（深色 `#8CD7B2`） | 校园成功/正常/专注完成等语义辅助色 |
| **Amber Notice** (`tertiary`) | `#8A5A10`（深色 `#F0BF73`） | 未验证提示、注意事项标记 |

---

## 2. Android：Material 3 / M3 Expressive 规范

对标 Google 原生软件（Settings / Clock / Pixel 体验）与 Immich 的 MD3 质感：

- **动态取色优先（Dynamic Color）**：Android 12+（API 31+）默认读取系统壁纸动态取色，设置页提供开关可切换回品牌石大蓝静态色板。
- **Edge-to-Edge 与系统栏**：Activity 启用 `enableEdgeToEdge()`，清单开启 `android:enableOnBackInvokedCallback="true"` 支持原生预测性返回。
- **标准底部导航 (`NavigationBar`)**：
  - 仅 4 个顶级 Tab 显示底部 `NavigationBar`（`首页 / 课表 / 服务 / 专注`），使用 `AlwaysShow` 标签与 M3 Active Indicator 胶囊。
  - 进入「设置 / 学校账号 / 关于」等二级页面时平滑收起底栏，让内容全屏延伸。
- **折叠顶栏 (`LargeTopAppBar`)**：
  - 4 个顶级 Tab 使用 `LargeTopAppBar` + `TopAppBarDefaults.exitUntilCollapsedScrollBehavior()`，搭配 `Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)` 实现丝滑折叠。
  - 二级页面使用 `MediumTopAppBar` + 返回导航按钮。
- **Immich / Google Settings 风格分段分组卡片**：
  - 分组列表项不再使用单一大白块内塞分割线，而是让每项成为独立 `Surface`（底色 `surfaceContainerHigh` 或 `surfaceContainerLow`），项间保持 `2.dp` 间距，通过外侧 `24.dp`、内侧 `6.dp` 的不对称圆角形成视觉组：
    - `SINGLE`: 四角 `24.dp`
    - `TOP`: 上 `24.dp` / 下 `6.dp`
    - `MIDDLE`: 四角 `6.dp`
    - `BOTTOM`: 上 `6.dp` / 下 `24.dp`
  - 分组间垂直间距 `20.dp`，页面水平内边距 `16.dp`。

---

## 3. iOS：iOS 26+ Liquid Glass & iOS 18 HIG 规范

- **部署目标与基线**：`iOS 18.0` 最小部署目标，面向 `iOS 26+` SDK 编译。
- **原生 Tab 导航**：
  - 使用 iOS 18+ `TabView` + `Tab(...)` 声明式 API。
  - 在 `iOS 26+` 系统上自动渲染原生悬浮 Liquid Glass TabBar，并配合 `.tabBarMinimizeBehavior(.onScrollDown)` 在滚动时自动收拢底栏。
  - 在 `iOS 18`–`25` 自动保持系统标准半透明底栏，零自定义 hack。
- **Toolbar 与胶囊控件**：
  - 使用原生 `NavigationStack` + `.navigationTitle(...)` 大标题与 `.toolbar { ToolbarItem(...) }`；iOS 26+ 系统自动为导航栏按钮赋予原生玻璃胶囊效果。
  - 浮动状态指示器、专注计时控制胶囊按钮等悬浮控件通过 `LiquidGlassModifiers` 实现：
    - `iOS 26+`：`.glassEffect(.regular.interactive(), in: .capsule)` 与 `GlassEffectContainer`。
    - `iOS 18–25`：回退 `.background(.ultraThinMaterial, in: Capsule())` + `0.5pt` 半透明边框描边。
- **避免“玻璃叠玻璃”**：
  - 根据 Apple HIG，Liquid Glass 仅用于导航层、浮动栏、胶囊控制与局部轻浮层。
  - 表单与服务列表正文区域（`ServicesView`、`SettingsView`、`SchoolAccountView`）坚持使用系统原生 `List` / `Form`（`.insetGrouped` 风格），保证阅读对比度与滚屏性能。
