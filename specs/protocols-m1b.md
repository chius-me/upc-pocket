# M1b 协议与业务契约规范（CAS 认证、受控 WebView、只读课表）

本文档沉淀 M1b 阶段在 Android 与 iOS 两端必须严格对齐的统一身份认证、验证码受控 WebView 回退、只读周课表协议、官方作息表与隔离缓存规范（只读协议与边界语义参考 `UPCPocket` MIT 源码）。

---

## 1. 统一 CAS 表单认证（`UpcCasLogin` + `UpcTimetableAuthenticator`）

### 端点与 Service 边界
- `CAS_HOST = "cas.upc.edu.cn"`
- `APP_HOST = "app.upc.edu.cn"`
- `PAGE_URL = "https://app.upc.edu.cn/site/weekschedule/index"`
- `INDEX_URL = "https://app.upc.edu.cn/timetable/wap/default/get-index"`
- `WEEK_URL = "https://app.upc.edu.cn/timetable/wap/default/get-datatmp"`
- `SERVICE = "https://app.upc.edu.cn/a_upc/api/cas/login?redirect=https%3A%2F%2Fapp.upc.edu.cn%2Fsite%2Fweekschedule%2Findex"`
  - 注意：内层 `redirect` 只做单层 URL 编码，禁止二次编码。
- `SERVICE_DECODED = "https://app.upc.edu.cn/a_upc/api/cas/login?redirect=https://app.upc.edu.cn/site/weekschedule/index"`
- `LOGIN_URL = "https://cas.upc.edu.cn/cas/login?service=$SERVICE&from=wap&noAutoRedirect=1"`
- `APP_CALLBACK_PATH = "/a_upc/api/cas/login"`，允许的回调查询参数仅限 `ticket`、`redirect`、`from`（不可重复且不可包含其他参数），其中 `ticket` 必须以 `ST-` 开头且长度在 `8..4096` 之间。

### 严格登录流程与安全约束
1. **Scoped 内存 Cookie Jar**：认证会话仅保留在内存中，**只接纳精确主机 `cas.upc.edu.cn` 与 `app.upc.edu.cn`**；禁止自动跟随重定向；单响应读取上限 `2 MiB`。
2. **`GET LOGIN_URL`**：校验返回可提交的可见密码表单：
   - 先运行 `CasCaptchaChallengeDetector`（见第 2 节）。一旦确认存在激活的验证码或短信二次验证（`username_smstoken`），**立即返回 `INTERACTION_REQUIRED(interactionUrl)`**，**绝不提交密码、绝不自动重试**。
   - 若 `#errorDiv` 出现显式账密拒绝文案，返回 `INVALID_CREDENTIALS`。
   - 表单必须满足：`type == "username_password"`、`_eventId == "submit"`、`execution` 非空白且长度 `<= 65536` 且不含空白与控制字符，解析后的 `action` 必须精确匹配预期 CAS 登录 URL。
3. **单次 `POST action`**：
   - 请求头：`Content-Type: application/x-www-form-urlencoded`、`Origin: https://cas.upc.edu.cn`、`Referer: <casUrl>`。
   - 表单字段：`username`、`password`、`type`、`execution`、`_eventId`、`submit`（单次操作最多提交 1 次密码；`password` 使用后立即清零，`toString()` 永久脱敏）。
4. **响应分流**：
   - `302/3xx`：若跳向精确回调 URL（`https://app.upc.edu.cn/a_upc/api/cas/login?ticket=ST-...`），则仅在 `https://app.upc.edu.cn` 域名下逐跳跟随最多 8 次直至业务页完成设 Cookie，随后调用 `GET INDEX_URL` 验证 `e == 0`。若跳回 `cas.upc.edu.cn/cas/login` 则拉取返回页并按挑战/账密错误/未知拒绝分类。任何越权域名或非法路径均视为协议拒绝。
   - `200/401/403`：优先检查验证码/二次验证，再检查 `#errorDiv` 显式账密错误列表（`用户名或密码错误`、`账号或密码错误`、`用户名或密码不正确`、`账号或密码不正确`、`密码错误`、`密码不正确`、`Invalidusernameorpassword`、`Invalidcredentials`），其余返回 `INTERACTION_REQUIRED` 或上游错误。
   - `429` 或其他非预期状态码不重试。

---

## 2. 验证码与二次验证检测器（`CasCaptchaChallengeDetector`）

在向 `cas.upc.edu.cn` 提交任何密码前，必须先执行确定性 HTML/JS 检测（双端算法完全一致）：
1. 去除 HTML 注释 `<!-- ... -->`。
2. 提取所有 `<script>...</script>` 正文，屏蔽 JS 单行/多行注释与（当检查赋值表达式时）字符串/模板字面量：
   - 若存在对 `config.captcha` 的赋值（`=`, `||=`, `&&=`, `??=` 等）：只要右侧不是明确结束的字面量 `null`、`false` 或 `undefined`（例如 `{ ... }`、`true`、变量或组合表达式），均判定为 **激活挑战**。
3. 在移除 `script`/`style`/`textarea`/`template`/`title` 与注释后的可见 DOM 标记中，追踪祖先标签的 `hidden` / `display:none` / `visibility:hidden|collapse` 状态（注意 `aria-hidden="true"` 与相互矛盾的重复 `display` 声明仍视为可见风险）：
   - 可见 `<div id="captchaParent">`
   - 可见 `<input>` 且 `name="captcha"` 或 `id="captcha"` 且 `type != "hidden"`
   - 可见 `<img id="captcha|captchaimg|captchaimage">`
   - 可见表单内含 `type="username_smstoken"` 或非隐藏 `username_smstoken` 输入框

---

## 3. 唯一受控 WebView 验证码回退

当 CAS 返回 `INTERACTION_REQUIRED` 时，Android（`ControlledSchoolWebViewDialog`）与 iOS（`ControlledSchoolWebViewSheet`）各自只维护 **1 个通用受控 WebView 组件**，不得以任意浏览器替代或为课表另建第二套 WebView：
- **会话绑定**：进入前生成 `SessionContext(accountId, generation, entryUrl)`；导航期间及 Cookie 采纳前必须核对当前账号仍为同一 `accountId` 与 `generation`，不一致立刻关闭并清理 WebView Cookie。
- **主机与路径白名单**：课表场景作用域仅限 `cas.upc.edu.cn`（仅 `/cas/` 下 GET/POST，禁止 `..` 与 `\`）与 `app.upc.edu.cn`；入口 URL 必须拒绝 `about:blank`。
- **严格 TLS**：Android `onReceivedSslError` 强制 `handler.cancel()`；iOS `WKNavigationDelegate` 强制 `.performDefaultHandling`（且 `WKWebsiteDataStore.nonPersistent()`），绝不绕过证书校验。
- **CAS 移动端视口修复**：仅当在 `cas.upc.edu.cn` 的 `/cas/login` 页面时注入受限 JS 修复 `#loginIframe` 塌陷高度为 `0` 的已知问题，绝不读取用户输入的密码字段。
- **Cookie 回采与验证**：仅当 WebView 完成跳转到达 `https://app.upc.edu.cn/site/weekschedule/...` 时提取 `app.upc.edu.cn` 作用域 Cookie（Android 随后立即清空 WebView `CookieManager`；iOS 使用 `nonPersistent()` 并在结束后销毁），先经 `GET INDEX_URL` 验证 `e == 0` 后方才加密落盘。

---

## 4. 只读周课表协议（`TimetableProtocol`）

### 请求头与请求体
- 两接口统一携带：
  - `Accept: application/json, text/plain, */*`
  - `X-Requested-With: XMLHttpRequest`
  - `Referer: https://app.upc.edu.cn/site/weekschedule/index`
  - `Cookie: <app.upc.edu.cn scoped cookies>`
- `GET https://app.upc.edu.cn/timetable/wap/default/get-index`
- `POST https://app.upc.edu.cn/timetable/wap/default/get-datatmp`，表单体 `year=<year>&term=<term>&week=<week>&type=2`。
- 禁止自动跟随 302 重定向、禁止整学期自动遍历/轮询、单响应上限 `2 MiB`。

### 严格解析语义
- **业务码校验**：`e` 必须为严格 JSON 整数（**禁止布尔 `false`/`true` 被当作 `0`/`1` 或浮点非整数通过**）。`e == 10013`（或 302/401）统一识别为 `AUTH_REQUIRED`；非 `0` 报错。
- **`get-index` 解析**：
  - `d.termInfo` 不可为空；单条历史/暑期畸形学期（如缺少 `startday` 或 `countweek` 不在 `1..30`）允许跳过并记录兼容标记，但去重后必须至少存在 1 条有效学期；若 `d.params.year`/`term` 指向不存在于有效列表的当前学期则整体失败。
  - `term` 映射：`"1" -> 秋季`、`"2" -> 春季`、`"3" -> 夏季`。
  - `d.weekday`：`0` 归一化为周日 `7`，允许 `1..7`。
- **`get-datatmp` 解析**：
  - `d.classes` 长度必须为 `7`（周日至周六 7 天）；`d.weekdays` 必须为恰好 7 个连续自然日（支持 `YYYY-M-D` 与 `YYYY-MM-DD`），且 **`weekdays[0]` 必须精确等于 `term.startDay + (requestedWeek - 1) * 7` 天**，不一致立即失败，绝不将相邻周错当请求周缓存。
  - 每日对象内非 `c_` 前缀元数据键忽略；`c_N` 必须为 `null` 或 JSON 数组；节次取自非空整数数组 `lessArr`（允许如 `6.0` 等整值数值，拒绝小数与 `1..14` 越界值），非连续节次拆分为多条连续区间 `CourseMeeting` 同时保留完整 `sourcePeriods`。
  - `week` 字段（支持 `1-16`、`1~16`、逗号/顿号/分号/空格分隔、可带 `周` 后缀）解析后必须包含当前请求的 `week`，否则判定协议异常。
- **单次认证恢复上限**：`get-index`/`get-datatmp` 遇缺会话或 `AUTH_REQUIRED` 时最多自动执行 **1 次** 正常 CAS 登录恢复，绝不死循环提交密码。

---

## 5. 官方作息时间表（`TimetableSchedule`）

依据中国石油大学（华东）《关于调整作息时间的通知》（2020-05-25）：

| 节次 | 上课时段 | 分组参考 |
| --- | --- | --- |
| 第 1 节 | `08:00–08:45` | 上午 1–5 节：`08:00 – 12:20` |
| 第 2 节 | `08:50–09:35` | — |
| 第 3 节 | `09:55–10:40` | — |
| 第 4 节 | `10:45–11:30` | — |
| 第 5 节 | `11:35–12:20` | — |
| 第 6 节 | `14:00–14:45` | 下午 6–9 节：`14:00 – 17:30` |
| 第 7 节 | `14:50–15:35` | — |
| 第 8 节 | `15:55–16:40` | — |
| 第 9 节 | `16:45–17:30` | — |
| 第 10 节 | `19:00–19:45` | 晚间 10–12 节：`19:00 – 21:25` |
| 第 11 节 | `19:50–20:35` | — |
| 第 12 节 | `20:40–21:25` | — |
| 第 13–14 节 | `null`（界面显示「时间待确认」） | 禁止按等差公式推算 |

当课程自身携带可解析的 `course_time`（如 `08:00-09:35`）时优先展示 `course_time`，缺失时回退至上表。

---

## 6. 隔离存储与账号变更联动规则

- **会话 Cookie 存储（仅 `app.upc.edu.cn`，绝不持久化 `cas.upc.edu.cn` Cookie）**：
  - Android：`noBackupFilesDir/timetable/session.bin`，使用独立 Keystore AES-256-GCM 密钥别名 `upcpocket.timetable_session.v1`，绑定 `(accountId, generation)`。
  - iOS：Keychain `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` 独立条目标识 `timetable_session_v1`，绑定 `(accountId, generation)`。
- **周课表离线缓存**：
  - Android：`noBackupFilesDir/timetable/cache-v1.json`
  - iOS：`Application Support/UPCPocketNoBackup/timetable/cache-v1.json`（`isExcludedFromBackup = true`）
  - 仅写入完整校验通过的 `TimetableWeek`（最多保留 32 周）与最近一次 `TimetableIndex`，并绑定 `accountId`。
- **失效矩阵**：
  - **换号 / 删除账号 / 进入恢复态（`ACCOUNT_CHANGED_OR_CLEARED`）**：清空加密会话 + 清空离线周缓存 + 清空 WebView Cookie + 递增 `businessEpoch` 丢弃在途迟到响应。
  - **同账号修改密码（`CREDENTIALS_UPDATED`）**：清空加密会话 + 清空 WebView Cookie + 递增 `businessEpoch`，保留该 `accountId` 已缓存的离线周数据并在网络/认证失败时清晰标注为离线缓存。
