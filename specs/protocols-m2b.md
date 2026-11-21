# M2b 业务协议规范：图书馆座位只读查询（`libseat` & 超星）

本文档定义 M2b 阶段中国石油大学（华东）两套图书馆/空间座位系统的**严格只读**接入协议、签名规范、认证链路、防错边界与双端一致性测试向量。

> **范围与安全底线**：M2b 阶段严格保持**只读查询**——禁止引入任何预约提交（如 `libseat` `/rest/v2/freeBook` 或超星 `/data/apps/*/submit`）、取消预约（`/rest/v2/cancel`）或签到写接口；界面明确展示只读声明并提供跳转到学校官方 HTTPS 页面的外部浏览器入口。

---

## 1. 系统总览与官方入口

| Tab / 标识 | 适用区域 | 官方页面入口 | 认证协议 |
| --- | --- | --- | --- |
| 唐岛湾 `libseat` | 唐岛湾校区图书馆 | `https://libspace.upc.edu.cn/libseat/` | UPC CAS -> `libspace` hash/query token -> `/rest/ssoAuth` |
| 古镇口东区（超星 `seat`） | 古镇口东区学习空间 | `https://office.chaoxing.com/front/third/apps/seat/index?fidEnc=e32f8fbde51f9dba&mappId=4197453` | UPC CAS -> `tyrzfw.chaoxing.com` -> `passport2-api` -> `office.chaoxing.com` |
| 求是（超星 `seatengine`） | 求是空间 | `https://office.chaoxing.com/front/third/apps/seatengine/index?fidEnc=e32f8fbde51f9dba&mappId=20853788&seatId=4258` | 复用同套 `office.chaoxing.com` CAS 会话 |

默认选中 Tab 可结合当前用户偏好校区：选择「古镇口」时默认打开「古镇口东区」，其余默认「唐岛湾 `libseat`」，用户可随时通过分段控件切换三个系统。

---

## 2. 系统一：唐岛湾 `libseat`（`libspace.upc.edu.cn`）

### 2.1 `UpcLibrarySigning` 请求签名规范

每个 `/rest/` 接口请求（除浏览器跳转入口外）必须附带以下请求头：

- `X-request-id`：随机 UUID v4 字符串
- `X-request-date`：UTC 毫秒时间戳字符串（十进制整数）
- `X-hmac-request-key`：小写十六进制 HMAC-SHA256 摘要，明文为：
  ```text
  seat::<X-request-id>::<X-request-date>::<METHOD>
  ```
  其中 `<METHOD>` 仅允许大写 `"GET"` 或 `"POST"`。
- `loginType`：固定 `"PC"`
- `Accept`：固定 `"application/json"`
- `Authorization`：登录拿到 token 后附加该请求头（未登录前省略）

HMAC 密钥 `configKey`（9 字节，来源自学校公开前端脚本配置，非用户凭据）按以下确定性步骤解码：
- Base64 密文：`"cDf+jadFUWncEn536MXItw=="`
- 算法：`AES-128-CBC` + `PKCS#5 / PKCS#7` 填充
- Key：UTF-8 `"server_date_time"`（16 字节）
- IV：UTF-8 `"client_date_time"`（16 字节）

#### 双端一致测试向量

- 输入：
  - `uuid = "00000000-0000-4000-8000-000000000000"`
  - `timestamp = 1700000000000`
- 预期输出：
  - 解码密钥长度：`9` 字节
  - `GET` 签名：`"d96340212c47c2f7c2c843bbf99e0a53f90d41fa8e0505c4ffc16c407f968897"`
  - `POST` 签名：`"c62c4b092c8d71425cc29e479bba3fb4120a89d3fbeb35ebf97f7aa5bfb1a8e1"`

### 2.2 CAS 认证与本人一致性校验

1. `GET https://libspace.upc.edu.cn/remote/static/sso/login?redirectUrl=https%3A%2F%2Flibspace.upc.edu.cn%2Flibseat%2F%23%2Flogin`（禁用自动重定向），记录 `JSESSIONID` 等受限主机 Cookie；仅接受 `301/302/303/307/308` 跳转至精确 CAS 登录 URL（`https://cas.upc.edu.cn/cas/login` 且 `service=https://libspace.upc.edu.cn/remote/static/casAuth/getServiceByVerifyTicket/casLogin`）。
2. 调用共享 `UpcCasLogin` 单次提交账密获取回调（如遇验证码或二次验证则返回 `InteractionRequired`）。
3. 在同一个会话 CookieJar 下跟随精确 `https://libspace.upc.edu.cn` 重定向链路（`/remote/static/casAuth/getServiceByVerifyTicket/casLogin` 或 `/libseat/...`，最多 8 跳），从 URL query 参数 `token` 或 Vue hash fragment（`#/login?token=...`）提取 `appToken`（长度 `1..4096`）。
4. `POST https://libspace.upc.edu.cn/rest/ssoAuth?token=<appToken>`（附带 `UpcLibrarySigning` 头与表单 `token=<appToken>`）兑换 `seatToken`。
5. `GET https://libspace.upc.edu.cn/rest/v2/user?token=<seatToken>` 解析本人信息 `LibraryUser(id, username, name)`，强制校验 `username.trim() == credential.loginName.trim()`；不匹配时必须返回 `IDENTITY_MISMATCH` 且不得发布会话。

### 2.3 只读数据接口与严格解析规则

| 能力 | 请求 | 响应契约与边界规则 |
| --- | --- | --- |
| 筛选日期与馆区 | `GET /rest/v2/free/filters?token=...` | 解析 `data.dates`（非空字符串数组）与 `data.buildings`（支持 `[id, name]` 元组数组或 `{id, name}` 对象）。缺一报错。 |
| 楼层列表 | `POST /rest/v2/room/floors/{buildingId}?token=...` | 解析 `data` 数组内 `{floorNum, floorName}`。 |
| 房间与余座统计 | `GET /rest/v2/room/stats2/{buildingId}/{date}?token=...` | 解析 `roomId`/`id`、`room`/`roomName`/`name`、`floor`，以及**可空**统计字段 `totalSeats`、`free`、`reserved`、`inUse`、`away`、`disableSeat`、`maxHour`。缺失计数字段保持 `null`（UI 展示“待查询”），严禁默认猜测为 `0`。按 `roomId` 去重。 |
| 房间座位布局 | `GET /rest/v2/room/layoutByDate/{roomId}/{date}?token=...` | 校验 `1 <= rows, cols <= 200`；`data.layout` key 支持 `row*1000+col` 整数、`row_col` 字符串或节点内显式 `row`/`col`。空座位列表报错。 |
| 当前预约（只读） | `GET /rest/v2/user/reservations?token=...` | 学校在无当前预约时明确返回 `data: null`（此时返回空列表）；若 `data` 为对象/字符串/数字等非数组非空类型，判为协议异常报错。每条记录需含非空 `id`、`date`/`onDate`、`begin`、`end`、`location`/`loc`。 |
| 历史预约（只读） | `GET /rest/v2/history/{page}/{size}?token=...&page=...&pageSize=...` | `data` 必须为对象且包含 `reservations` 数组。 |

### 2.4 业务错误码语义

- `code == 12`（数字或字符串 `"12"`）或 HTTP `401/403`：`LibrarySessionExpired`（清空内存 `seatToken` 后最多触发 **1 次**自动静默重认证重试；重认证仍需交互则切入受控 WebView）。
- `code == 20`：学校返回时间/签名异常；保留原文并附本地提示“这是请求签名或手机时间不匹配，请先校准设备时间后重试”。
- `status == false` / `"fail"` / `"error"` 或 `code` 非 `0/200`：直接抛出业务失败消息，绝不把报错当成空数据列表。

---

## 3. 系统二：超星座位（`office.chaoxing.com`：`seat` / `seatengine`）

### 3.1 固定业务参数与主机白名单

- `FID_ENC = "e32f8fbde51f9dba"`
- `SEAT_MAPP_ID = "4197453"`（古镇口东区 `seat`）
- `SEATENGINE_MAPP_ID = "20853788"`、`SEATENGINE_SEAT_ID = "4258"`（求是 `seatengine`）

### 3.2 多跳 CAS -> 超星 SSO 认证链

全程使用内存 CookieJar，限定允许主机：`cas.upc.edu.cn`、`tyrzfw.chaoxing.com`、`passport2-api.chaoxing.com`、`i.chaoxing.com`、`office.chaoxing.com`、`chaoxing.com`（其中允许子域父域匹配：仅当 Cookie 域 `chaoxing.com` 与请求子域同在 `allowedHosts` 内时生效）：

1. `GET https://office.chaoxing.com/front/third/apps/seat/index?fidEnc=e32f8fbde51f9dba&mappId=4197453` -> 3xx 重定向至 `https://tyrzfw.chaoxing.com/auth3/upc/cas/init`（严格校验 `refer` 指向 `office.chaoxing.com` 座位入口，`mappId` 缺失或等于 `4197453`）。
2. 请求 `init` 页面 HTML，提取 JS `window.location` 跳转到 `https://cas.upc.edu.cn/cas/login?service=https://tyrzfw.chaoxing.com/auth3/upc/cas/index`。
3. 通过 `UpcCasLogin` 完成 CAS 认证并拿到 `tyrzfw.chaoxing.com/auth3/upc/cas/index?ticket=ST-...` 回调。
4. 请求回调页（允许最多 1 次无参 `/auth3/upc/cas/index` 重定向），提取 JS `window.location` 至 `https://passport2-api.chaoxing.com/api/v2/casreceive?...`（严格校验允许查询键及 `setmain=true`）。
5. 请求 `casreceive`（允许 3xx 跳转至 `i.chaoxing.com` 根或 `/base` 路径），从页面 `<input id="refer" value="...">`（解码 HTML 实体）提取 `office.chaoxing.com` 座位入口并跟随最多 8 跳至 HTTP 200 页面，确认包含 `userLoginInfo`。
6. 解析 HTML 内 `userLoginInfo.userInfo.sno`，**绑定学校账号保存模式或身份核验**：当本地已配置有效学号登录名时校验 `sno.trim() == loginName.trim()`；不匹配报错拒绝。导出 `office.chaoxing.com` 可用 Cookie Header 仅存于内存。

### 3.3 只读数据接口与严格解析规则

全部只读请求仅允许访问 `https://office.chaoxing.com` 以下精确路径（`User-Agent`、禁用自动重定向、最大响应体 2 MB）：

1. **房间列表**：
   `GET /data/apps/{seat|seatengine}/room/list?time=&cpage=1&pageSize=100&firstLevelName=&secondLevelName=&thirdLevelName=&day={yyyy-MM-dd}&deptIdEnc=e32f8fbde51f9dba[&seatId=4258]`
   - 解析 `data.seatRoomList`、`totalRow`、`totalPage`；按 `Asia/Shanghai` 星期读取 `seatSpecialTime` / `seatEngineSpecialTime` 的 `{mon|tues|wed|thur|fri|sat|sun}StartTime/EndTime`。
2. **房间详情与座位底图**：
   - `POST /data/apps/{seat|seatengine}/room/info`（表单 `id={roomId}&fidEnc=e32f8fbde51f9dba`）
     - 校验 `seatRoom.id == expectedRoomId`、`timeUnit > 0`、`isReserve` 仅允许 `0/1`（`0` 记入不可约座号集合），解析 `minReserveDuration`、`reserveDuration`（小时转分钟）、`closed = (isOpen == 1)`。
   - `GET /data/apps/{seat|seatengine}/getdrawseat?id={roomId}`
     - 解析浮点坐标 `xleft`、`ytop`、`width > 0`、`height > 0`，`rotate` 归一化到 `[0, 360)`，且数量须与 `data` 一致并拒绝重复 `seatNum`。
3. **指定时段已占用座位**：
   `GET /data/apps/{seat|seatengine}/getusedseatnums?roomId={roomId}&startTime={HH:mm}&endTime={HH:mm}&day={yyyy-MM-dd}&(seatId=4258|fidEnc=...)`
   - 解析 `data.seatReserves[].seatNum` 集合。
4. **单座位当日已占用时段**：
   `GET /data/apps/{seat|seatengine}/getusedtimes?roomId={roomId}&seatNum={seatNum}&day={yyyy-MM-dd}&(seatId=4258|fidEnc=...)`
   - 解析 `[startEpoch, endEpoch]`（自动区分秒/毫秒时间戳，转 `Asia/Shanghai` 当天分钟；若 `endMinute <= startMinute` 跨日加 1440 分钟）。

错误语义：
- JSON 顶层要求 `success == true` 且含 `data`。
- HTTP `302/303/401/403` 判为会话过期（清空内存超星 Cookie，触发至多 1 次自动重认证或受控 WebView）；HTTP `429` 明确报错限流。

---

## 4. 受控 WebView 回退与内存会话边界

- 无论 `libseat` 还是超星，登录凭据（`seatToken`、`cookieHeader`）**严禁落盘、严禁写入日志/异常/诊断**，日志与 `toString()`/`description` 一律脱敏。
- 当自动 CAS 认证返回 `InteractionRequired` 时，打开**唯一共享受控 WebView**（`ControlledSchoolWebViewDialog` / `ControlledSchoolWebViewSheet`），绑定启动时 `accountId + generation`：
  - `libseat`：`scopeHosts = {"cas.upc.edu.cn", "libspace.upc.edu.cn"}`，监听完成地址 query/fragment 提取 `token`，交由仓库兑换 `seatToken` 并校验本人身份后才发布到 UI。
  - 超星：`scopeHosts = {"cas.upc.edu.cn", "tyrzfw.chaoxing.com", "passport2-api.chaoxing.com", "i.chaoxing.com", "office.chaoxing.com"}`，完成页为 `office.chaoxing.com` 座位入口路径时采集目标域 Cookie 并调用 `identity(...)` 验证后发布。
- 发生账号修改、删除、切换或登出时，立即递增 `businessEpoch`，清空 `libseat` 内存 token、超星内存 Cookie、WebView Cookie 及当前图书馆 UI 状态，防止迟到网络结果污染新账号会话。
