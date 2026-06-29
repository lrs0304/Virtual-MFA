
# MFA二次验证 (Virtual MFA)

一个 **完全离线、零联网权限** 的 Android TOTP / HOTP 二次验证码 App。
定位是个人/团队自用的、可信、可审计的 Authenticator 替代品。

代码全部使用 **Java + 原生 Android View** 体系实现，不依赖 Kotlin、Compose、协程，**编译产物 ≈ 7.2 MB**（arm64-v8a，release，单 ABI）。

> 仓库：https://github.com/lrs0304/Virtual-MFA
> 包名：`com.risonliang.mfa`
> 应用名：**MFA二次验证**（debug 包后缀 `-dev`，可与 release 同机并存）
> 主分支：`main`

---

## ✨ 特性

### 验证码

- **TOTP**（RFC 6238）：默认 30s 周期，支持 6/7/8 位
- **HOTP**（RFC 4226）：计数器型，本机维护 counter，刷新后自增
- 算法支持：**SHA-1 / SHA-256 / SHA-512**
- 列表项实时倒计时进度条；剩余 ≤5s 自动切换为告警橙红
- `tick` 刷新仅更新 RecyclerView 可见范围内的条目，列表很长时也不卡

### 录入方式

- 📷 **实时扫码**（`ScanActivity`：CameraX `Preview` + `ImageAnalysis` → ML Kit Barcode）
- 🖼 **相册导入**（系统 PhotoPicker → `AlbumQrDecoder` → ML Kit bundled 模型）
- ⌨️ **手动添加**（`AddManualActivity`）
- 📦 **Google Authenticator 迁移二维码**（`otpauth-migration://offline?data=...`，手写 protobuf 解码，无第三方依赖）

### 识别失败兜底机制（v1.1 新增）

| 失败类型 | 行为 |
|---|---|
| ML Kit 没识别到二维码 | 跳转 `ImageEditActivity`，提供「缩放模式」+「调整模式」（对比度 / 阈值 / R/G/B 亮度 + 反色 + Otsu 自动二值化），「重新识别」按钮重试 |
| 识别到二维码但内容不是 `otpauth` / GA 迁移 | 跳转 `QrContentPreviewActivity`，红色提示条 + 等宽原文 + 一键复制 |

### 列表与管理

- **长按拖拽排序**（`SwipeAndDragCallback`），按 `sortOrder` 单事务持久化
- **左滑删除**（带 Snackbar 撤销）
- **长按菜单**（`AccountActions`）：编辑 / 收藏置顶 / 删除
- **搜索栏**：issuer / account 大小写不敏感子串匹配
- **首字母色块图标**（`IssuerIconDrawable`，纯 `Drawable`，零图片资源，稳定色相）

### 备份 / 迁移

- **加密备份 `.2fa`**：AES-256-GCM + PBKDF2-HMAC-SHA256（10w 轮）派生密钥
- **明文 CSV `.csv`**：兼容主流导入工具
- 导入自动识别格式；按 `issuer + account + secret` 去重

### 安全

- **应用密码（PIN）**：用户可主动开启 / 关闭 / 修改，可加生物识别快捷解锁
- **PIN 不明文存储**：PBKDF2-HMAC-SHA256（**150000 轮**） + 16B 随机 salt 后落盘
- **secret 静默加密**：所有账号 `secret` 字段使用 Android Keystore 中的 AES-256-GCM 主密钥加密后入库
- **后台脱敏**：进入后台/最近任务时自动覆盖遮罩；release 默认开启 `FLAG_SECURE`，禁止截屏与录屏（debug 包**故意**关掉，便于调试）
- **零网络权限**：`AndroidManifest.xml` 中显式 `tools:node="remove"` 掉 `INTERNET`、`ACCESS_NETWORK_STATE`，并裁剪掉 ML Kit / startup / datatransport 等会被合并进来的多余组件

### 体积优化（release，arm64-v8a）

- 仅保留 `arm64-v8a` 单 ABI（砍掉 ML Kit barhopper 的 armeabi-v7a `.so` ≈ -3MB）
- R8 开启（标准模式；`android.enableR8.fullMode` 当前为关，详见 `CLAUDE.md` 第 4 节）
- `shrinkResources true`，仅保留 `zh / en` 语言资源
- BackupCodec 用 `org.json` 替代 Gson（≈ -64 KB）
- 直接调用 Android Keystore，不引入 `androidx.security:security-crypto`（≈ -516 KB）
- 当前 release APK ≈ **7.2 MB**

---

## 🏗 项目结构

```
app/src/main/java/com/risonliang/mfa/
├── crypto/
│   ├── Base32.java              # RFC 4648 Base32 编解码
│   ├── CryptoManager.java       # Android Keystore 主密钥 + AES-256-GCM
│   └── OtpGenerator.java        # TOTP / HOTP 生成
├── data/
│   ├── BackupCodec.java         # .2fa 加密备份格式 (v2，向下兼容 v1)
│   ├── CsvBackup.java           # 明文 CSV 导入导出
│   ├── GaMigrationDecoder.java  # otpauth-migration:// protobuf 手写解码
│   ├── OtpRepository.java       # SQLite 持久化 + secret 字段加密
│   └── OtpUriParser.java        # otpauth:// URI 解析
├── model/
│   └── OtpAccount.java          # 数据模型（含 isValid()）
├── security/
│   ├── AppLockManager.java      # PIN/生物识别锁 (PBKDF2 150k)
│   └── ClipboardCleaner.java    # 复制验证码后定时清空剪贴板
└── ui/
    ├── BaseSecureActivity.java         # FLAG_SECURE + 后台脱敏遮罩基类
    ├── MainActivity.java               # 主列表 / 搜索 / 长按菜单 / 添加入口
    ├── OtpAdapter.java                 # RecyclerView 适配器（独立顶层类）
    ├── SwipeAndDragCallback.java       # 列表手势（拖拽排序 + 左滑删除）
    ├── AccountActions.java             # 列表项弹窗（编辑 / 置顶 / 删除）
    ├── IssuerIconDrawable.java         # 首字母色块头像，零图片资源
    ├── UiPreferences.java              # 「隐藏验证码」「下一码预览」等开关持久化
    ├── LockActivity.java               # 三模式状态机：SETUP / UNLOCK / CHANGE
    ├── ScanActivity.java               # 实时扫码（CameraX + ML Kit ImageAnalysis）
    ├── AlbumQrDecoder.java             # 相册解码统一入口（含 sampleSize 策略）
    ├── QrDecoder.java                  # ML Kit Barcode 同步封装（CountDownLatch）
    ├── QrContentPreviewActivity.java   # 解析失败时展示原文+复制
    ├── ImageEditActivity.java          # 识别失败时手动调整重试
    ├── ZoomableImageView.java          # 双指缩放 / 单指平移 / 双击切档
    ├── BitmapAdjuster.java             # LUT 三通道亮度+对比度+二值化+Otsu
    ├── AddManualActivity.java
    ├── ImportExportActivity.java
    ├── SettingsActivity.java           # 隐藏验证码 / 下一码预览 / 自动锁宽限期
    ├── AboutActivity.java              # 介绍 / GitHub / 支付宝赞赏
    └── InsetsUtils.java                # 刘海 / 曲面 / 手势条 insets 适配
```

测试位于 `app/src/test/`（单元测试）与 `app/src/androidTest/`（仪器测试），覆盖：

- `OtpGeneratorTest`：HOTP RFC 4226 官方测试向量
- `OtpUriParserTest`：otpauth URI 解析
- `SearchFilterTest`：列表搜索过滤大小写归一
- `NextCodeWindowTest`：剩余秒数窗口边界
- Base32 encode 测试

---

## 🚀 构建与运行

### 环境要求

- JDK 11+
- Android Studio（任意 Iguana 之后版本）或裸 Gradle
- Android SDK：`compileSdk 36`（minorApiLevel 1），`minSdk 26`（Android 8.0+）
- AGP `9.1.1`，Gradle wrapper 已纳管

### 编译

```bash
# Debug（applicationId 带 .debug 后缀，应用名加 -dev，可与 release 共存）
./gradlew :app:assembleDebug

# Release（需要签名配置，详见下方）
./gradlew :app:assembleRelease
```

产物位于 `app/build/outputs/apk/{debug,release}/`。

### Release 签名

`local.properties` 中加入（**不会被提交**，已在 `.gitignore` 里）：

```properties
MFA_STORE_FILE=/absolute/path/to/your.jks
MFA_STORE_PASSWORD=...
MFA_KEY_ALIAS=...
MFA_KEY_PASSWORD=...
```

未配置时，release 构建依然会跑（生成未签名 APK），不影响本地体积观察。

### versionCode / versionName

由 `git rev-list --count HEAD` 自动生成，CI 友好且单调递增。

- `versionCode` = 总提交数
- `versionName` = `1.0.<提交数>`，debug 包追加 `-debug` 后缀

---

## 🔒 安全模型简述

| 数据 | 存储方式 | 备注 |
|---|---|---|
| 账号 `secret` | AES-256-GCM 密文（IV‖密文‖Tag），Base64 后入 SQLite | 主密钥常驻 Android Keystore，永不出 TEE |
| 应用 PIN | PBKDF2-HMAC-SHA256（150000 轮）哈希 + 16B salt | 仅落盘哈希值，离线爆破代价 ≈ 派生本身 |
| 加密备份 `.2fa` | AES-256-GCM，密钥由用户口令 PBKDF2-HMAC-SHA256（100000 轮）派生 | salt 随机 16B，IV 随机 12B，Tag 128 bit |
| CSV 备份 | 明文 | 仅在用户主动选择时生成，UI 上有醒目警示 |
| 剪贴板 | 复制验证码后 30s 自动清空 | `ClipboardCleaner` |

应用层默认 `android:allowBackup="false"`、`usesCleartextTraffic="false"`，零网络权限。

---

## 🛠 实现细节与"踩过的坑"

下面这一节是项目演进过程中实际踩到的坑与最终决策，主要给后续维护者看。

### 1. 包体积优化（一路从 ~9MB 砍到 ~7.2MB）

| commit | 优化点 | 收益 |
|---|---|---|
| `598b23e` | 弃用 `androidx.security:security-crypto`，直接调 Keystore | ≈ -516 KB |
| `b939644` | `BackupCodec` 中 Gson 替换为 `org.json` | ≈ -64 KB |
| `1dba19a` | abiFilters 仅保留 `arm64-v8a`，砍 ML Kit barhopper 的 v7a 库 | ≈ -3 MB |
| `863a071` | `AndroidManifest.xml` 中 `tools:node="remove"` 掉所有第三方注入但本应用不需要的组件 | -58 KB |
| `2a2f045` | 实时扫码迁到 CameraX + ML Kit，**整库移除 zxing-android-embedded** | 见下一条 |

**关键经验**：`R8 + minifyEnabled` 之后，APK 中**仍会保留**第三方库通过 manifest merger 注入的 Provider/Receiver/Service，必须手动 `tools:node="remove"`，否则它们会拖出一整条依赖链，让 R8 不敢剪。

### 2. 扫码引擎：从 ZXing 到 CameraX + ML Kit 的整体迁移（v1.1）

历史：早期使用 `zxing-android-embedded` 的 `BarcodeView`（Camera1 后端）+ ML Kit 兜底。

问题：

- 小米 15 Pro / 华为 Mate 60 Pro 等新机型上 Camera1 取景帧分辨率 / 自动对焦表现极差，对着二维码毫无反应；
- R8 fullMode 下 `BarcodeView → DecoderThread → Handler` 反射链路被静默裁剪，release 包扫码失效但 logcat 无报错；
- 为了 keep 住 ZXing 反射类，proguard 不得不整库 keep `com.google.zxing.**` 与 `com.journeyapps.barcodescanner.**`。

决策（commit `2a2f045`）：**整体迁移到 CameraX `Preview` + `ImageAnalysis` + ML Kit Barcode bundled**。

- 实时扫码（`ScanActivity`）和相册解码（`AlbumQrDecoder` / `QrDecoder`）共用同一 ML Kit 管线，技术栈统一；
- ZXing 依赖、proguard 规则、`gradle.properties` 中 fullMode 的强制 false 一起作废（fullMode 当前仍保持 false，可考虑重开省 50-100 KB dex，但要做物理机回归——详见 `CLAUDE.md` 第 4 节）；
- 相机权限仍是 `CAMERA` 一项；网络权限继续 `tools:node="remove"`。

### 3. 相册识别失败的兜底路径（v1.1）

老版本相册识别失败只有 Toast 一句话。新版本拆出两条兜底：

- **未识别到二维码** → `ImageEditActivity`。提供「缩放模式」（pinch [1, 8]、双击 1x↔2.5x、单指平移）和「调整模式」（对比度 / 阈值 / R/G/B 亮度 + 反色 + Otsu 自动二值化）。点「重新识别」喂调整后的 bitmap 给 `QrDecoder`，命中后 `setResult` 回 `MainActivity` 走原有 `handleScanResult`。
- **识别成功但内容不是 MFA 规范** → `QrContentPreviewActivity`。红色提示条 + 等宽原文 + 一键复制（ClipData label 用 `qr_content` 避免泄漏到剪贴板历史 UI）。

实测中还有一个非直觉的小修：`MainActivity.decodeQrInBackground` 的 `inSampleSize` 阈值从 4096 改成 2048（commit `8d5dd6d`）。原因是 ML Kit QR finder pattern detector 的甜点是 module ≈ 4-8 px，华为高分屏截图（2400-3060 边长）原图上 module 12-20 px 且高频噪声未滤波，一次 1/2 sampleSize 做了 box-filter 低通，反而让识别更稳。

### 4. 应用锁三模式状态机

`LockActivity` 是 SETUP / UNLOCK / CHANGE 三态机：

- **SETUP**：首次启用 PIN，分两次输入并校验一致
- **UNLOCK**：正常解锁；可生物识别快捷解锁
- **CHANGE**：先校验旧 PIN → 两次输入新 PIN；按返回键 = 取消并回到调用者，不影响当前会话

之前踩到的坑：

- 密码对话框校验失败会把 dialog 一起关掉 → `setOnClickListener` 改写，校验失败时**不**调用 `dismiss()`（commit `326806c`）
- "应用密码"和"设备锁屏密码"概念容易混淆 → 文案统一改为"应用密码"（`d804384`）
- 应用从后台回前台时不弹解锁 → 修复 lifecycle 回调时序（`1caa595`）

### 5. 备份格式版本兼容

`BackupCodec` 由 v1 升级到 v2 后，仍需要兼容 v1 文件导入。`bf.optInt("v", 1)` + 路由逻辑保证不会让老用户的备份失效（`ba7d84a`）。

### 6. 边到边 + 各类异形屏

- 主题 `windowLayoutInDisplayCutoutMode = shortEdges`，根布局 `fitsSystemWindows`
- `InsetsUtils` 统一处理 systemBars / displayCutout / IME insets
- 子页面（AddManual / ImportExport / Settings / About）用自有 `MaterialToolbar`，状态栏背景统一为品牌蓝（`b0d8e07`）

### 7. Debug 构建放宽 FLAG_SECURE

Release 默认开启 `FLAG_SECURE` 防截屏，但调试期间需要录屏/截图。`BaseSecureActivity` 在 `BuildConfig.DEBUG = true` 时跳过 `setFlags(FLAG_SECURE)`（`c82f1d9`）。

### 8. 列表性能

定时器每秒触发 `notifyItemChanged` 时只刷新可见区间内的 ViewHolder，而不是 `notifyDataSetChanged`，否则账号一多就会卡顿（`e8071b4`）。

### 9. GaMigrationDecoder 数组越界

Google Authenticator 二维码里 `OtpType` / `Algorithm` 是 enum 整型，遇到未来版本扩展的新值会越界 → 加 bounds 校验后默认回退 SHA1/TOTP（`f6470af`）。

---

## 🧪 单元测试

```bash
./gradlew :app:test
./gradlew :app:connectedAndroidTest   # 需要连接设备
```

关键覆盖：

- `OtpGeneratorTest`：用 RFC 4226 附录 D 的官方测试向量验证 HOTP；TOTP 用确定性时间戳验证
- `OtpUriParserTest`：覆盖标准 / 缺省参数 / Issuer 转义 / HOTP counter 等
- `SearchFilterTest` / `NextCodeWindowTest`：UI 层纯逻辑单测

---

## 📜 License

本仓库当前未指定开源许可证。如需基于此项目派生或商用请先联系作者。

---

## 🙏 致谢

- [Google ML Kit Barcode Scanning](https://developers.google.com/ml-kit/vision/barcode-scanning)（bundled 离线模型）
- [Android CameraX](https://developer.android.com/training/camerax)
- 所有 RFC 4226 / 6238 / 4648 / 8018（PBKDF2）标准的作者
- 历史曾依赖：[ZXing](https://github.com/zxing/zxing) / [zxing-android-embedded](https://github.com/journeyapps/zxing-android-embedded)（v1.1 已整体替换为 CameraX + ML Kit）
