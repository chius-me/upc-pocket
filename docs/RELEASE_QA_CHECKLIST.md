# v0.2.0-rc.1 真机验收与签名发版清单

本文档用于发版前的签名配置与真实校园账号端到端验收。所有密钥、证书与真实学号密码均不得提交至 Git 仓库或写入日志/测试样本。

---

## 1. Android 正式签名配置

### 1.1 生成并备份长期 Release Keystore（仓库外目录）

```bash
mkdir -p "$HOME/.secrets/upc-pocket"
keytool -genkeypair -v \
  -keystore "$HOME/.secrets/upc-pocket/upc-pocket-release.jks" \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias upcpocket
```

> 请妥善离线备份 `.jks` 文件与口令；一旦丢失，后续无法以同一签名覆盖安装或更新商店应用。

### 1.2 本地试打正式签名 Release APK（可选）

创建被 `.gitignore` 忽略的 `android/keystore.properties`：

```properties
storeFile=/absolute/path/to/upc-pocket-release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=upcpocket
keyPassword=YOUR_KEY_PASSWORD
```

本地验证 R8 + Resource Shrink + 正式签名：

```bash
cd android
./gradlew :app:assembleRelease -PrequireReleaseSigning=true --no-daemon
```

### 1.3 配置 GitHub Repository Secrets

```bash
base64 -i "$HOME/.secrets/upc-pocket/upc-pocket-release.jks" | tr -d '\n' | gh secret set ANDROID_KEYSTORE_BASE64
gh secret set ANDROID_KEYSTORE_PASSWORD
gh secret set ANDROID_KEY_ALIAS -b "upcpocket"
gh secret set ANDROID_KEY_PASSWORD
```

---

## 2. iOS 真机安装与正式签名配置

### 2.1 个人自用直连安装（Xcode 本地真机）

1. 生成工程：`cd ios && xcodegen generate`
2. 用 Xcode 打开 `ios/UPCPocket.xcodeproj`
3. 在 `UPCPocket` Target -> `Signing & Capabilities` 选择个人或团队 Apple ID，连接 iPhone 即可真机运行。

### 2.2 团队分发 / TestFlight CI 自动签名（可选）

如需通过 GitHub Actions 输出已签名 `.ipa` 并上传 TestFlight，需在 Apple Developer 后台为 Bundle ID `me.chius.upcpocket` 签发分发证书（`.p12`）与描述文件（`.mobileprovision`），再写入 Secrets：

```bash
base64 -i dist.p12 | tr -d '\n' | gh secret set IOS_CERTIFICATE_BASE64
gh secret set IOS_CERTIFICATE_PASSWORD
base64 -i profile.mobileprovision | tr -d '\n' | gh secret set IOS_PROVISIONING_PROFILE_BASE64
gh secret set IOS_TEAM_ID -b "YOUR_TEAM_ID"
```

如需工作流自动上传 TestFlight，额外配置 App Store Connect API Key：

```bash
gh secret set APP_STORE_CONNECT_KEY_ID
gh secret set APP_STORE_CONNECT_ISSUER_ID
base64 -i AuthKey_XXXX.p8 | tr -d '\n' | gh secret set APP_STORE_CONNECT_API_KEY_BASE64
```

> 首次向 TestFlight / App Store 提交构建时，需在 App Store Connect 后台完成**出口合规声明**（仅使用标准 HTTPS 与系统 Keychain 加密）。

---

## 3. 真实石大账号真机端到端验收表

验收须使用已授权的本人真实学号与密码，并在校内网与校外网条件下分别抽查。

| 验收项 | 检查要点 | Android | iOS |
| --- | --- | --- | --- |
| **统一学校账号** | 保存后显示「已保存（未验证）」且不可称「已登录」；保存或退出页面密码框立即清零、永不回显；换号/删号后课表与余额缓存彻底失效 | [ ] | [ ] |
| **CAS 认证与 WebView 回退** | 正常账密直接通过表单认证；触发验证码时仅弹出唯一受控 HTTPS WebView，完成验证后会话正确回传并继续查询 | [ ] | [ ] |
| **只读周课表** | 周日–周六周次切换、单双周与 1–12 节官方作息准确；断网时展示最近一次账号绑定缓存；无编辑/导出按钮 | [ ] | [ ] |
| **校园卡余额** | 仅发送本人有效学号，返回真实余额与卡状态；断网读缓存；不触发任何充值/扣款操作 | [ ] | [ ] |
| **宿舍电量** | 唐岛湾精准校验房间参数与电量（失败不误报空/正常）；古镇口跳转官方页面而非绕过 TLS | [ ] | [ ] |
| **空闲教室** | 节次交集（`d.js` 语义）、教学楼与「卓越 A/B」筛选正确；切换校区与设置页同步 | [ ] | [ ] |
| **图书馆座位（只读）** | 唐岛湾 `libseat` 楼层/房间/余座及当前/历史预约正确显示；古镇口东区 `seat` 与求是 `seatengine` 房间/时段占用可读；确认无抢座/提交写操作 | [ ] | [ ] |
| **本机待办与专注** | 独立截止与一次性系统通知按时触发（无精确闹钟权限报错）；课表「今日日程待办」同步；专注计时在退后台/杀进程重启后墙钟读秒仍正确 | [ ] | [ ] |
| **视觉与桌面图标** | 浅色/深色模式文字与卡片对比度正常；Android 自适应图标与 Material You 单色图标无裁切；iOS 26 Liquid Glass 与 iOS 18 回退正常，默认/深色/色调图标无黑边白底异常 | [ ] | [ ] |

---

## 4. 首版内测发版指令

以上真机验收全部勾选通过、线上联调问题修复并合入 `main` 后，打 Tag 触发 [`.github/workflows/release.yml`](../.github/workflows/release.yml)：

```bash
git checkout main && git pull --ff-only origin main
git tag v0.2.0-rc.1
git push origin main v0.2.0-rc.1
```

带 `-rc.1` 后缀的 Tag 会自动以 GitHub Prerelease 形态发布。内测稳定后，再推送 `v1.0.0` 正式版 Tag。
