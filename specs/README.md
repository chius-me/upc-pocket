# UPC Pocket 共享规范（Specs）

`specs/` 定义 Android 与 iOS 两条纯原生工程共同遵守的架构边界、数据契约、安全基线、视觉风格与校园入口目录。两端分别以各自平台原生范式实现，共享语义而不共享运行时代码。

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [architecture.md](architecture.md) | 同仓独立双端架构、4 Tab 信息架构、Android 与 iOS 各自工程分层 |
| [security-baseline.md](security-baseline.md) | 6 条硬性安全基线（禁云备份、明文例外、凭据加密、异步 I/O、受控 WebView、范围控制） |
| [account-contract.md](account-contract.md) | 账号状态机、`StudentIdValidator` 规则与双端一致测试向量、`CampusPreference` / `ThemePreference` |
| [protocols-m1a.md](protocols-m1a.md) | M1a 三项核心只读业务协议契约（校园卡余额、唐岛湾电量、空闲教室） |
| [protocols-m1b.md](protocols-m1b.md) | M1b CAS 表单认证、受控 WebView 验证码回退、只读周课表与官方作息契约 |
| [productivity-m2a.md](productivity-m2a.md) | M2a 本机待办与一次性提醒、课表日程联动、专注墙钟持久化与统计契约 |
| [protocols-m2b.md](protocols-m2b.md) | M2b 图书馆座位只读协议（`libseat` 签名与查询、超星 `seat`/`seatengine` SSO 与底图契约） |
| [design-system.md](design-system.md) | 品牌主色、Android MD3 Expressive 规范、iOS 26+ Liquid Glass 与 iOS 18 回退规范 |
| [upc-entrypoints.json](upc-entrypoints.json) | 中国石油大学（华东）官方服务入口与只读协议目录（开发参考资料，非运行配置） |

## 里程碑路线

| 阶段 | 目标范围 | 状态 |
| --- | --- | --- |
| **M0** | 规范文档 + Android/iOS 双端骨架（4 Tab、设置与学校账号页、番茄钟原型、视觉基线、安全清单与单测） | 已完成 |
| **M1a** | 校园卡余额（本人学号只读 + 账号绑定缓存）、唐岛湾剩余电量（防模糊误报）、空闲教室（`d.js` 交集语义 + 卓越 A/B 筛选） | 已完成 |
| **M1b** | 学校账号接入正常 CAS 认证、唯一受控 WebView 验证码回退、只读课表与离线缓存 | 已完成 |
| **M2a** | 本机待办与独立截止/一次性系统提醒、课表「今日日程待办」联动、专注计时墙钟持久化与统计 | 已完成 |
| **M2b** | 图书馆座位只读（`libseat` / 超星 `seat` & `seatengine`） | 已完成 |
| **M3** | 桌面小组件 / 锁屏组件、石光第二课堂与 e站通只读能力；高风险写操作（校园卡充值等）仅在单独审查后考虑 | 后置 / 待评估 |
