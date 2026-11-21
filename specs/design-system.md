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

## 2. Android：Google M3 Expressive（对标 Gmail / Chrome / Google Phone）

参考 Gmail、Chrome 与 Google Phone 在 Material 3 Expressive 下的层级节奏，收敛视觉噪音并保持高对比清晰度：

- **Canvas-First 层次分离**：主页面背景采用低饱和浅色画布 `background`（`#EEF3F8`），前景所有分组列表卡片（`ExpressiveGroupedCard` / `ExpressiveListRow`）与概览条均统一采用纯白 `surfaceContainerLowest`（`#FFFFFF`），彻底消除同色灰底叠灰卡与区块感杂乱。
- **M3 Expressive 分段圆角列表规范**：组内首尾外圆角 `24.dp`、相邻内圆角 `4.dp`、行间距 `2.dp`、组间距 `14.dp`，搭配 `40.dp` 软色调圆形图标容器与紧凑徽标，取代过去满屏铺排的大型方块宫格。
- **Google 悬浮搜索胶囊顶栏 (`GoogleSearchPillBar`)**：
  - `今日` 与 `校园` 顶级页采用 `52.dp` 高胶囊顶栏（`surfaceContainerHigh`），集成搜索图标、上下文状态胶囊（如 `第 X 周`）与**唯一**右侧账号/设置圆形头像入口，消除设置齿轮与头像双重重复按钮；点按搜索栏可即时模糊检索全端服务与本周课程。
  - 课表页采用紧凑单行顶栏（`第 X 周 ▾` BottomSheet、`回本周`、网格/日程流切换、刷新）。
- **标准 3-Tab `NavigationBar`**：
  - 仅在 `今日 / 课表 / 校园` 3 个顶级页面显示底部 `NavigationBar`（采用 `surfaceContainer` 区分于画布层）。
  - 进入成绩、校园卡钱包、图书馆选座、空教室、电费、石光、校历、设置与学校账号页时平滑隐藏底栏，专注内容全屏呈现，并支持 Android 14+ 原生预测性返回与 Edge-to-Edge。
- **正文零工程样板文**：
  - 主界面与各业务卡片禁止堆砌 `allowBackup`、明文例外、免责声明等技术解释文案；隐私与加密细节统一收口至「关于」页面，账号页诚实维持「已保存（未验证）」语义。
- **设置页分层与配色选择器**：沿用统一分段分组圆角卡（`24.dp`/`4.dp`）与 4 枚交互式配色圆点按钮。

---

## 3. iOS：iOS 26+ Liquid Glass & iOS 18 HIG 规范

- **部署目标与基线**：`iOS 18.0` 最小部署目标，面向 `iOS 26+` SDK 编译。
- **原生 Tab 导航 & Toolbar**：
  - 使用 iOS 18+ `TabView` + `Tab(...)` 声明式 API，在 `iOS 26+` 自动获得原生悬浮 Liquid Glass TabBar（`if #available(iOS 26.0, *)` + `.tabBarMinimizeBehavior(.onScrollDown)`）与玻璃胶囊 toolbar，并向 iOS 18 优雅回退标准材质。
- **避免“玻璃叠玻璃”**：正文表单与数据卡片坚持系统原生高对比容器与 HIG 排版。
