# UPC Pocket 共享规范（Specs）

`specs/` 定义 Android 与 iOS 两条纯原生工程共同遵守的架构边界、数据契约、安全基线、视觉风格与校园入口目录。两端分别以各自平台原生范式实现，共享语义而不共享运行时代码。

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [architecture.md](architecture.md) | 同仓独立双端架构、Android V2 3-Tab Pixel Bento 架构与包分层 |
| [security-baseline.md](security-baseline.md) | 6 条硬性安全基线（禁云备份、明文例外、凭据加密、异步 I/O、受控 WebView、范围控制） |
| [account-contract.md](account-contract.md) | 账号状态机、`StudentIdValidator` 规则与双端一致测试向量、`CampusPreference` / `ThemePreference` |
| [protocols-m1a.md](protocols-m1a.md) | M1a 三项核心只读业务协议契约（校园卡余额、唐岛湾电量、空闲教室） |
| [protocols-m1b.md](protocols-m1b.md) | M1b CAS 表单认证、受控 WebView 验证码回退、只读周课表与官方作息契约 |
| [productivity-m2a.md](productivity-m2a.md) | M2a 历史规格归档（Android V2 起已聚焦纯粹校园核心能力） |
| [protocols-m2b.md](protocols-m2b.md) | M2b 图书馆座位协议（`libseat` 签名/预约/取消、超星 `seat`/`seatengine` 预约契约） |
| [design-system.md](design-system.md) | Google Pixel Bento 规范、4 套主题配色与 iOS 26+ Liquid Glass 回退规范 |
| [upc-entrypoints.json](upc-entrypoints.json) | 中国石油大学（华东）官方服务入口与协议目录（开发参考资料，非运行配置） |

## 里程碑路线

| 阶段 | 目标范围 | 状态 |
| --- | --- | --- |
| **M0–M2b** | 安全基线、双端纯原生骨架、课表/余额/电量/空教室/图书馆座位基础 | 已完成 |
| **V2 (Android v0.3.0)** | Pixel Bento 3-Tab UI 全面革新 + 成绩 GPA 推演 + 校园卡流水与微信充值 + 图书馆选座/取消 + 石光第二课堂 + 高清校历 + 应用更新检查 | 已完成 |
| **V2 (iOS 对齐)** | 将 iOS 端同步升级至 3-Tab 信息架构与完整 V2 业务能力 | 待执行 |
