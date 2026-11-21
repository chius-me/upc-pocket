# 视觉与交互设计规范（Design System）

遵循“**各自平台最原生、最地道的设计语言**”原则：不强行在 Android 画 iOS 玻璃，也不在 iOS 套用 Material 卡片。

## 1. 品牌与配色方案（Android 4 Seed Schemes / iOS Accent）

Android V2 默认采用低饱和清透画布（浅色背景 `#EEF3F8`，纯白 `#FFFFFF` `surfaceContainerLowest` Bento 卡片）与高对比大字号指标排版，并在设置页内置 4 套精选色彩种子，同时保留 Android 12+ 动态取色开关：

| 配色种子 (`ColorSeed`) | 主色 (`primary`) | 用途与气质 |
| --- | --- | --- |
| **Pixel 晴海蓝 (`OCEAN_BLUE`, 默认)** | `#0B57D0`（深色 `#A8C7FA`） | 对标 Google Pixel Weather / Calendar 的明快现代蓝 |
| **青翠校园绿 (`JADE_GREEN`)** | `#146C2E`（深色 `#8CD7B2`） | 石大校园青绿与高对比清爽阅读体验 |
| **琥珀暖金 (`SUNSET_AMBER`)** | `#B06000`（深色 `#FFB963`） | 暖色调活跃卡片视觉 |
| **鸢尾紫 (`IRIS_PURPLE`)** | `#65558F`（深色 `#CFBCFF`） | Android Expressive 经典柔紫色调 |

---

## 2. Android：Google Pixel Bento & Material 3 Expressive 规范

对标 Google 原生软件（Pixel Weather / Google Wallet / Google Calendar）的高对比度与信息密度：

- **Canvas-First 层次分离**：主页面背景采用 `background`（浅色 `#EEF3F8`），核心指标卡与 Hero 卡采用纯白 `surfaceContainerLowest` 搭配 `28.dp` / `32.dp` 大圆角（Squircle）与清晰字重对比，避免“灰底叠灰卡”的沉闷感。
- **单行顶栏 (`GoogleTopBar`)**：
  - 顶级页面使用单行 `GoogleTopBar`，左侧显示页面大标题与上下文状态胶囊（如 `第 X 周`），右侧固定圆形账号/设置头像入口，释放首屏宝贵纵向空间。
  - 课表页采用紧凑周切换顶栏（`第 X 周 ▾` BottomSheet、`回本周`、网格/日程流切换、校历、刷新）。
- **标准 3-Tab `NavigationBar`**：
  - 仅在 `今日 / 课表 / 校园` 3 个顶级页面显示底部 `NavigationBar`。
  - 进入成绩、校园卡钱包、图书馆选座、空教室、电费、石光、校历、设置与学校账号页时平滑隐藏底栏，专注内容全屏呈现，并支持 Android 14+ 原生预测性返回与 Edge-to-Edge。
- **正文零工程样板文**：
  - 主界面与各业务卡片禁止堆砌 `allowBackup`、明文例外、免责声明等技术解释文案；隐私与加密细节统一收口至「关于」页面，账号页诚实维持「已保存（未验证）」语义。
- **设置页分层与配色选择器**：保留分段分组圆角卡（`24.dp`/`6.dp`）与 4 枚交互式配色圆点按钮。

---

## 3. iOS：iOS 26+ Liquid Glass & iOS 18 HIG 规范

- **部署目标与基线**：`iOS 18.0` 最小部署目标，面向 `iOS 26+` SDK 编译。
- **原生 Tab 导航 & Toolbar**：
  - 使用 iOS 18+ `TabView` + `Tab(...)` 声明式 API，在 `iOS 26+` 自动获得原生悬浮 Liquid Glass TabBar（`if #available(iOS 26.0, *)` + `.tabBarMinimizeBehavior(.onScrollDown)`）与玻璃胶囊 toolbar，并向 iOS 18 优雅回退标准材质。
- **避免“玻璃叠玻璃”**：正文表单与数据卡片坚持系统原生高对比容器与 HIG 排版。
