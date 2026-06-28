
# MFA二次验证 (Virtual MFA)

一个 **完全离线、零联网权限** 的 Android TOTP / HOTP 二次验证码 App。
定位是个人/团队自用的、可信、可审计的 Authenticator 替代品。

代码全部使用 **Java + 原生 Android View** 体系实现，不依赖 Kotlin、Compose、协程，**编译产物 < 8 MB**（arm64-v8a，release，单 ABI）。

> 仓库：https://github.com/lrs0304/Virtual-MFA
> 包名：`com.risonliang.mfa`
> 应用名：**MFA二次验证**

---

## ✨ 特性

### 验证码

- **TOTP**（RFC 6238）：默认 30s 周期，支持 6/7/8 位
- **HOTP**（RFC 4226）：计数器型，本机维护 counter，刷新后自增
- 算法支持：**SHA-1 / SHA-256 / SHA-512**
- 列表项实时倒计时进度条；剩余 ≤5s 自动切换为告警橙红
- `tick` 刷新仅更新 RecyclerView 可见范围内的条目，列表很长时也不卡

### 录入方式

- 📷 **相机扫码**（`ScanActivity`，基于 ZXing `BarcodeView`）
- 🖼 **相册导入**（系统 PhotoPicker → ML Kit + ZXing 双引擎兜底解码）
- ⌨️ **手动添加**（`AddManualActivity`）
- 📦 **Google Authenticator 迁移二维码**（`otpauth-migration://offline?data=...`，手写 protobuf 解码，无第三方依赖）

### 备份 / 迁移

- **加密备份 `.2fa`**：AES-256-GCM + PBKDF2-HMAC-SHA256（10w 轮）派生密钥
- **明文 CSV `.csv`**：兼容主流导入工具
- 导入自动识别格式；按 `issuer + account + secret` 去重

### 安全

- **应用密码（PIN）**：用户可主动开启 / 关闭 / 修改，可加生物识别快捷解锁
- **PIN 不明文存储**：PBKDF2-HMAC-SHA256（**150000 轮**） + 16B 随机 salt 后落盘
- **secret 静默加密**：所有账号 `secret` 字段使用 Android Keystore 中的 AES-256-GCM 主密钥加密后入库
- **后台脱敏**：进入后台/最近任务时自动覆盖遮罩；release 默认开启 `FLAG_SECURE`，禁止截屏与录屏
- **零网络权限**：`AndroidManifest.xml` 中显式 `tools:node="remove"` 掉 `INTERNET`、`ACCESS_NETWORK_STATE`，并裁剪掉 ML Kit / startup / datatransport 等会被合并进来的多余组件

### 体积优化（release，arm64-v8a）

- 仅保留 `arm64-v8a` 单 ABI（砍掉 ML Kit barhopper 的 armeabi-v7a `.so` ≈ -3MB）
- R8 **full mode** 开启
- `shrinkResources true`，仅保留 `zh / en` 语言资源
- ZXing 仅 keep `QRCodeReader` + `BarcodeView`，`MultiFormatReader` 内部反射加载的其它码制 Reader 全部被 R8 剪掉
- BackupCodec 用 `org.json` 替代 Gson（≈ -64KB）
- 直接调用 Android Keystore，不引入 `androidx.security:security-crypto`（≈ -516KB）
- 当前 release APK ≈ 7.3 MB

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
│   └── AppLockManager.java      # PIN/生物识别锁 (PBKDF2 150k)
└── ui/
    ├── BaseSecureActivity.java  # FLAG_SECURE + 后台脱敏遮罩基类
    ├── MainActivity.java        # 主列表 (滑动删除 / 长按编辑)
    ├── LockActivity.java        # 三模式状态机：SETUP / UNLOCK / CHANGE
    ├── ScanActivity.java        # 相机扫码（极简 BarcodeView，无 Decorator）
    ├── AddManualActivity.java
    ├── ImportExportActivity.java
    └── InsetsUtils.java         # 刘海 / 曲面 / 手势条 insets 适配
```

测试位于 `app/src/test/`（单元测试）与 `app/src/androidTest/`（仪器测试），覆盖：

- `OtpGeneratorTest`：HOTP RFC 4226 官方测试向量
- `OtpUriParserTest`：otpauth URI 解析
- Base32 encode 测试

---

## 🚀 构建与运行

### 环境要求

- JDK 11+
- Android Studio（任意 Iguana 之后版本）或裸 Gradle
- Android SDK：`compileSdk 36`，`minSdk 26`（Android 8.0+）
- AGP `9.1.1`，Gradle wrapper 已纳管

### 编译

```bash
# Debug（包名带 .debug 后缀，可与 release 共存）
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

应用层默认 `android:allowBackup="false"`、`usesCleartextTraffic="false"`，零网络权限。

---

## 🛠 实现细节与"踩过的坑"

下面这一节是项目演进过程中实际踩到的坑与最终决策，主要给后续维护者看。

### 1. 包体积优化（一路从 ~9MB 砍到 ~7.3MB）

| commit | 优化点 | 收益 |
|---|---|---|
| `598b23e` | 弃用 `androidx.security:security-crypto`，直接调 Keystore | ≈ -516 KB |
| `b939644` | `BackupCodec` 中 Gson 替换为 `org.json` | ≈ -64 KB |
| `4fb5175` | ZXing 仅 keep `QRCodeReader`，让 R8 剪掉其他 Reader | ≈ -180 KB |
| `09293be` | `DecoratedBarcodeView` 换为原始 `BarcodeView`，去除 ZXing 默认装饰 UI | 进一步精简 |
| `1dba19a` | abiFilters 仅保留 `arm64-v8a` + R8 fullMode | ≈ -3 MB（ML Kit 的 v7a 库） |
| `863a071` | `AndroidManifest.xml` 中 `tools:node="remove"` 掉所有第三方注入但本应用不需要的组件 | release 7448124 → 7389752 B |

**关键经验**：`R8 fullMode + minifyEnabled` 之后，APK 中**仍会保留**第三方库通过 manifest merger 注入的 Provider/Receiver/Service，必须手动 `tools:node="remove"`，否则它们会拖出一整条依赖链，让 R8 不敢剪。

### 2. ML Kit + ZXing 双引擎相册解码

最初纯 ZXing 解析相册截图大量失败（屏摄、水印、压缩噪点）。增加 ML Kit Barcode Scanning（bundled，离线）作为兜底：

- **ZXing 快速路径**：Hybrid → GlobalHistogram → Invert 三档二值化
- **ML Kit 兜底**：ZXing 全失败时调用 ML Kit，专治屏摄/水印/低对比度截图
- 删除了不必要的"灰度+中值滤波"和 `GenericMultipleBarcodeReader` 切块兜底（ML Kit 已经能 cover）
- 大图按宽度自适应降采样，且**移到后台线程**解码（修过 OOM）

### 3. 应用锁三模式状态机

`LockActivity` 是 SETUP / UNLOCK / CHANGE 三态机：

- **SETUP**：首次启用 PIN，分两次输入并校验一致
- **UNLOCK**：正常解锁；可生物识别快捷解锁
- **CHANGE**：先校验旧 PIN → 两次输入新 PIN；按返回键 = 取消并回到调用者，不影响当前会话

之前踩到的坑：

- 密码对话框校验失败会把 dialog 一起关掉 → `setOnClickListener` 改写，校验失败时**不**调用 `dismiss()`（commit `326806c`）
- "应用密码"和"设备锁屏密码"概念容易混淆 → 文案统一改为"应用密码"（`d804384`）
- 应用从后台回前台时不弹解锁 → 修复 lifecycle 回调时序（`1caa595`）

### 4. 备份格式版本兼容

`BackupCodec` 由 v1 升级到 v2 后，仍需要兼容 v1 文件导入。`bf.optInt("v", 1)` + 路由逻辑保证不会让老用户的备份失效（`ba7d84a`）。

### 5. 边到边 + 各类异形屏

- 主题 `windowLayoutInDisplayCutoutMode = shortEdges`，根布局 `fitsSystemWindows`
- `InsetsUtils` 统一处理 systemBars / displayCutout / IME insets
- 子页面（AddManual / ImportExport）改用自有 `MaterialToolbar` 替换默认 ActionBar，状态栏背景统一为品牌蓝（`b0d8e07`）

### 6. Debug 构建放宽 FLAG_SECURE

Release 默认开启 `FLAG_SECURE` 防截屏，但调试期间需要录屏/截图。`BaseSecureActivity` 在 `BuildConfig.DEBUG = true` 时跳过 `setFlags(FLAG_SECURE)`（`c82f1d9`）。

### 7. 列表性能

定时器每秒触发 `notifyItemChanged` 时只刷新可见区间内的 ViewHolder，而不是 `notifyDataSetChanged`，否则账号一多就会卡顿（`e8071b4`）。

### 8. GaMigrationDecoder 数组越界

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

---

## 📜 License

本仓库当前未指定开源许可证。如需基于此项目派生或商用请先联系作者。

---

## 🙏 致谢

- [ZXing](https://github.com/zxing/zxing) / [zxing-android-embedded](https://github.com/journeyapps/zxing-android-embedded)
- [ML Kit Barcode Scanning](https://developers.google.com/ml-kit/vision/barcode-scanning)
- 所有 RFC 4226 / 6238 / 4648 / 8018（PBKDF2）标准的作者
