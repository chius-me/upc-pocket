# 安全与隐私基线（Security Baseline）

双端必须严格遵守以下 6 条基线，任何里程碑迭代不得降级。

## 1. 彻底关闭系统云备份与跨设备迁移

应用承诺“账号凭据与校园数据只保留在本机、不自动上传至第三方云备份”。

- **Android**：
  - `AndroidManifest.xml` 显式设置 `android:allowBackup="false"`。
  - 提供 `res/xml/data_extraction_rules.xml`（Android 12+），在 `<cloud-backup>` 与 `<device-transfer>` 中均显式 `<exclude>` `root`、`database`、`sharedpref`、`file`、`external` 及对应 `device_*` 域（注意：空的 `<device-transfer />` 在 Android 上等价于全部纳入，因此必须写全排除）。
  - 提供 `res/xml/backup_rules.xml`（兼容 `<full-backup-content>`）。
  - 账号密文、偏好 DataStore 全部写入 `context.noBackupFilesDir`。
- **iOS**：
  - 所有应用内私有持久化目录（`Application Support/UPCPocketNoBackup`、`Documents` 等）在应用启动及写入前通过 `URL.setResourceValues` 强制设置 `isExcludedFromBackup = true`。
  - Keychain 凭据条目强制使用 `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`，禁止同步到 iCloud Keychain 或包含进加密/非加密云备份。

## 2. 最小化明文 HTTP 放行，严禁绕过 TLS 校验

- 全局默认强制 HTTPS：
  - Android：`android:usesCleartextTraffic="false"` 配合 `network_security_config.xml` 中 `<base-config cleartextTrafficPermitted="false" />`。
  - iOS：`Info.plist` 中 `NSAppTransportSecurity` 设置 `NSAllowsArbitraryLoads = false`。
- **唯一允许的明文主机例外**（且 `includeSubdomains=false` / `NSIncludesSubdomains=false`）：
  1. `jhzf.upc.edu.cn` — 校园卡余额接口（仅提供 HTTP）。
  2. `dfcz.upc.edu.cn` — 唐岛湾宿舍剩余电量查询（仅提供 HTTP，公开楼号+房间号参数，无凭据）。
- 禁止为任何站点（包括证书曾过期的 `gzkdf.upc.edu.cn`）关闭证书校验、安装自定义全信任 `TrustManager` 或放行 `arbitraryLoadsInWebContent`。

## 3. 学校账号凭据与诚实状态机

- **默认不保存**：仅在用户主动填写并点击“加密保存到本机”时写入加密存储。
- **诚实状态表达**：本机成功落盘后状态固定为 `SAVED_UNVERIFIED`（界面展示「已保存（未验证）」），绝不显示「已登录」或「校方认证通过」。
- **零明文泄漏**：
  - 界面输入密码在保存提交或离开页面后立即清零（Android 清零 `CharArray` 与输入状态；iOS 清空 `@State` 并避免持久化明文）。
  - 严禁回显已保存密码；严禁在状态对象 `toString()`、日志、异常信息或测试报告中出现密码、Cookie、Token 或真实学号。
- **硬件/系统级加密**：
  - Android：Android Keystore `upcpocket.school_account.v1`（AES-256-GCM，随机 12 字节 IV，无降级明文）。
  - iOS：Security Framework Keychain（`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`）。
- **损坏隔离与代次清理**：
  - 密钥失效或密文损坏时进入 `RECOVERY_REQUIRED`（需要重新保存），不崩溃、不降级明文。
  - 每次保存、改密、换号、删除递增 `generation`；换号或删除变更或清空 `accountId`，触发旧业务会话与个人缓存失效。

## 4. 全异步本地 I/O

- 禁止在主线程执行磁盘读取、文件写入、JSON 解析落盘、DataStore 初始化、Keystore 加解密或 Keychain 查询。
- Android：使用 `Dispatchers.IO` + `Mutex` 封装仓储；UI 仅消费 `StateFlow`。
- iOS：使用独立 `actor`（`PreferencesStore`、`KeychainCredentialStore`、`SchoolAccountStore`、`LocalStorageExclusion`），在 Swift 6 `SWIFT_STRICT_CONCURRENCY = complete` 下由编译器保证数据竞争安全。

## 5. 唯一受控 WebView 验证码回退契约

后续校园认证遇到图形验证码或二次验证时，双端各自只维护 **1 个** 通用受控 WebView 容器（Android `WebView` / iOS `WKWebView`）：

- **严格 HTTPS 白名单**：仅允许精确主机且仅允许 `https`（443 默认端口），禁止 `http://`、userinfo（`user:pass@host`）、非标准端口与未知重定向域：
  - `cas.upc.edu.cn`（仅 `/cas/login` 及 `/cas/` 子资源）
  - `app.upc.edu.cn`
  - `libspace.upc.edu.cn`
  - `mb-xyk.upc.edu.cn`
  - `office.chaoxing.com`（仅受限选座与认证流）
- **绑定上下文**：每次打开 WebView 绑定启动时的 `accountId` 与 `generation`；操作期间账号变化或删除立即销毁页面并丢弃会话。
- **隔离浏览器环境**：不读取或导入系统浏览器、微信或第三方浏览器 Cookie；不读取或记录网页表单密码。

## 6. 功能范围与风险控制

- M0–M2 优先建设只读查询与纯本机工具（课表、空闲教室、电量、校园卡余额、图书馆座位只读、待办、专注）。
- 涉及资金或不可逆业务状态变更的写操作（如校园卡微信充值）明确后置，不在早期骨架引入支付 SDK 或订单状态机。
