# M1a 协议与业务契约规范

本文档沉淀 M1a 阶段首批 3 个核心只读校园业务在 Android 与 iOS 两端必须严格对齐的协议细节、边界保护与持久化语义（只读语义参考 `UPCPocket` MIT 源码）。

---

## 1. 校园卡余额（`card_balance`）

### 请求契约
- **端点**：`POST http://jhzf.upc.edu.cn:20081/wechat/callinterface/getCardInfo.html`
- **网络例外**：仅针对精确域名 `jhzf.upc.edu.cn` 放行明文（不含子域名），**禁止跟随重定向**，响应大小上限 `256 KiB`。
- **必需请求头**：
  - `Content-Type: application/x-www-form-urlencoded; charset=UTF-8`
  - `Accept: */*`（不可发送 `application/json`，校方网关会直接返回 HTTP 406）
  - `X-Requested-With: XMLHttpRequest`
  - `Cache-Control: no-cache, no-store`
  - 不附带 `Cookie`
- **表单体**：`sno=<studentId>&xxbh=synjones&idtype=sno&id=<studentId>`（URL 百分号编码）。
- **查询目标约束**：只能读取当前已保存统一账号中通过 `StudentIdValidator` 整串校验的本人学号；**绝不发送密码**，**绝不接受**路由参数、页面输入框或旧草稿中的任意学号。

### 响应解析与金额计算
- 必须严格校验以下全部条件，任意缺失或异常均报错，不输出服务端原文或学生姓名：
  1. JSON 根对象 `retcode == "0"`
  2. `card` 为数组且长度 **精确等于 1**
  3. `card[0].sno == expectedStudentId`
  4. `card[0].db_balance` 与 `card[0].unsettle_amount` 均为匹配 `^-?[0-9]+$` 的整数（单位：分）
- **展示计算**：总额 `totalCents = balanceCents + transitionCents`，使用十进制整数分或 `BigDecimal` / `Decimal` 按 2 位小数精确格式化，禁止浮点数加法。

### 账号绑定与隔离缓存
- **无备份存储**：
  - Android：`noBackupFilesDir/card-balance/snapshot-v1.json`
  - iOS：`Application Support/UPCPocketNoBackup/card-balance-v1.json`（`isExcludedFromBackup = true`）
- 持久化快照记录 `accountId`、`studentId`、`credentialGeneration`、`balanceCents`、`transitionCents`、`fetchedAtEpochMillis`。
- **并发与迟到响应防写回**：发起请求前捕获 `(accountId, studentId, generation, businessEpoch)`；收到响应并解析成功后再次核对当前账号绑定与 `businessEpoch`，不一致立即丢弃结果，绝不写回内存或磁盘。
- **清理区分**：
  - 「仅清除余额缓存」只删除余额缓存文件与内存快照，不动学校账号与加密凭据。
  - 账号切换、删除或进入恢复状态时自动调用 `clearForAccountChange()`，递增 `businessEpoch` 并清除余额缓存。

---

## 2. 唐岛湾剩余电量（`power_tangdaowan`）与古镇口电费

### 唐岛湾请求与表单契约（ASP.NET WebForms）
- **步骤 1（读取表单）**：`GET http://dfcz.upc.edu.cn/ShiYou/Default.aspx?code=0`
  - 必须携带 `code=0`（服务端不校验该参数，但缺省会 302 跳转微信 OAuth）。
  - 禁止跟随重定向；响应上限 `1 MiB`。
  - 校验不含未登录跳转并存在 `id="form1"`，提取隐藏字段 `__VIEWSTATE`、`__VIEWSTATEGENERATOR`、`__EVENTVALIDATION` 与 `<option value="...">` 楼号列表。
- **步骤 2（提交查询）**：`POST http://dfcz.upc.edu.cn/ShiYou/Default.aspx`
  - 表单体：`__VIEWSTATE=...&__VIEWSTATEGENERATOR=...&__EVENTVALIDATION=...&lou=<楼号>&yonghu=<房间号>&chaxun=查询`。
- **网络例外**：仅针对精确域名 `dfcz.upc.edu.cn` 放行明文，查询参数为公开的楼号与房间号，不携带账号凭据，单次查询后丢弃会话状态。

### 模糊匹配防误报规则
- 学校页面会按模糊子串匹配房间（例如输入 `101` 时返回包含 `1101` 的候选表 `yonghuGridView` 及第一条的电量表 `yongdianGrid`）。
- **必须用请求房间号做精确比对**：
  - 若 `yonghuGridView` 候选表非空：仅当筛选 `room.trim() == requestedRoom` 恰好命中 1 条，且 `yongdianGrid`（若含房间列）不矛盾时返回 `Success`；其余情况一律返回 `Ambiguous(candidates)` 交用户点选，绝不代猜。
  - 若无候选表但 `yongdianGrid` 读到的房间号非空且与 `requestedRoom` 不一致，同样返回 `Ambiguous`。
- 电量文本必须匹配 `^([0-9]+(?:\.[0-9]+)?)\s*度$`，界面明确标注「剩余电量 XX.XX 度」（不可改写为金额余额）。

### 古镇口电费边界
- 入口：`https://gzkdf.upc.edu.cn/acmswx/index.aspx`
- 严禁绕过 TLS 证书校验；仅作为外部官方 HTTPS 页面在系统浏览器打开并明确提示传输安全边界。

---

## 3. 空闲教室（`free_classroom`）

### 请求契约
- **端点**：`POST https://app.upc.edu.cn/freeclass/wap/default/search-all`
- 公开查询：强制 HTTPS、不发送 Cookie 或学校账号、禁止跟随重定向、响应上限 `2 MiB`。
- **表单体**：`xq=<唐岛湾校区|古镇口校区>&date=<YYYY-MM-DD>&lh=<全部|楼栋>&jc[]=1&jc[]=2...`（节次允许 `1..13`）。

### 严格响应语义
- 校验 `e == 0`，读取 `d.all`（全部教室按楼栋分组字典）、`d.js`（所选全部节次**交集均空闲**的教室字典；PHP 空关联数组可能序列化为 `[]`，仅允许空数组代表空集合）、`d.listLh`（楼栋选项）。
- **只有明确出现在 `d.js` 内的教室编码才标记为 `available = true`**；未命中、空集合或响应异常绝不能推断为空闲。
- 节次快捷分组（与学校公开页面一致）：
  - 上午：`1–5` 节
  - 下午：`6–9` 节
  - 晚上：`10–13` 节
- 古镇口校区支持本地「全部 / 卓越 A / 卓越 B」筛选（按房间名匹配 `^卓越\s*[AB]\s*\d`），仅过滤本地展示，不修改请求参数。
