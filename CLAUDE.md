
# CLAUDE.md

This file gives AI coding assistants (Claude Code / CodeBuddy / Cursor / 等)
quick context for working in this repository. Keep it short, factual, and
opinionated. When in doubt, prefer the conventions documented here over
generic best practice.

> 项目：**MFA二次验证 (Virtual MFA)** — 一个完全离线、零联网权限的 Android TOTP/HOTP 验证器
> 主仓库：https://github.com/lrs0304/Virtual-MFA
> 包名：`com.risonliang.mfa`
> 主分支：`main`
> 用户阅读的项目说明请见 [README.md](./README.md)。本文是给 AI Agent 用的速查表。

---

## 1. 高层信息

- **语言**：Java（**不要**改写为 Kotlin / 不要引入 Compose）
- **UI**：原生 View + Material Components + ViewBinding
- **构建**：AGP `9.1.1` + Gradle wrapper（请用仓库内的 `./gradlew`，不要用全局 gradle）
- **JDK**：11（`compileOptions` 已锁定，不要随意提升）
- **minSdk = 26（Android 8.0）**，**targetSdk / compileSdk = 36**
- **ABI**：`arm64-v8a` 单架构。**不要**为了"兼容性"重新加回 `armeabi-v7a` / `x86_64`
- **网络**：`AndroidManifest.xml` 显式 `tools:node="remove"` 掉了 `INTERNET` 等权限。**永远不要**加任何网络权限或上报 SDK
- **R8**：release 默认开启 `minifyEnabled` + `shrinkResources` + **R8 full mode**

## 2. 目录速查

```
app/src/main/java/com/risonliang/mfa/
  crypto/    Base32, OtpGenerator, CryptoManager(Keystore + AES-GCM)
  data/      OtpRepository, BackupCodec, CsvBackup, GaMigrationDecoder, OtpUriParser
  model/     OtpAccount
  security/  AppLockManager (PIN + biometric)
  ui/        MainActivity, LockActivity, ScanActivity, AddManualActivity,
             ImportExportActivity, BaseSecureActivity, InsetsUtils
app/src/test/             JUnit 单元测试（无 Android 依赖）
app/src/androidTest/      Instrumented 测试
```

`MainActivity.java` 已经较大（~41KB / ~1k 行）；编辑时优先用 `replace_in_file` /
`multi_replace`，避免整文件重写。

## 3. 编码与提交约定

### 3.1 编码风格

- 缩进：4 空格（沿用 Android Studio 默认 Java 风格）
- 命名：常量 `UPPER_SNAKE_CASE` 或 `kCamelCase`（见 `AppLockManager` 的 `kKeyPinHash`）
- **所有新增文件**头部加版权声明：

  ```java
  /*
   * Copyright (c) 2026 risonliang. All rights reserved.
   */
  ```

- 注释：中文，描述 **意图** 而非翻译代码；多行用 Javadoc 风格
- 字符串：硬编码中文必须放 `res/values/strings.xml`（`90dba74` 已经做过一轮整改）

### 3.2 Commit message

仓库使用 **Conventional Commits（中文正文）** 风格，已有的好范例：

```
feat(security): 新增应用锁(PIN+生物识别)，全Activity开启FLAG_SECURE，后台脱敏遮罩
fix(scan): 相册解码移到后台线程+大图片降采样防OOM
perf(zxing): shrink APK by ~180KB via QRCodeReader and ProGuard slim
chore(size): 移除冗余 manifest 注入组件并精简 ProGuard keep
```

要求：

- type(scope): 一句话主语；正文可中文
- **包体相关 commit 在 message 里附上"X→Y B"或"≈ -KB"** 的实测数据，便于回查
- 提交前确认 `./gradlew :app:test` 通过

### 3.3 versionCode / versionName

由 `git rev-list --count HEAD` 自动生成，**不要**在 `build.gradle` 里写死。新增 commit 自动生效。

## 4. 安全红线（不要触碰）

下列任意一条都属于"会被立刻 revert"的修改：

1. 新增 **任何** 网络权限或上报 SDK；引入 Firebase / Crashlytics / Bugly / Sentry 等
2. 把 `secret` 字段改成明文落盘
3. 降低 PIN 哈希轮数（当前 PBKDF2-HMAC-SHA256，**150000 轮**），或改为简单哈希
4. 关闭 `android:allowBackup="false"`
5. release 包关闭 `FLAG_SECURE`（debug 包**故意**关闭，见 `BaseSecureActivity`，不要再"统一"）
6. 改回 `androidx.security:security-crypto` 这种重型库（Keystore 直调即可）
7. 把 ML Kit 改为 unbundled（远程下载模型）— 我们要离线
8. 引入 Gson / Jackson / kotlin-reflect 等会拖大包体的库

## 5. 包体守门（重要）

每次提交前如果改了 `app/build.gradle`、依赖、Manifest 或 ProGuard，请**手动跑一遍 release**：

```bash
./gradlew :app:assembleRelease
ls -la app/build/outputs/apk/release/*.apk
```

合理基线：**arm64-v8a release ≈ 7.3MB**，超过 8MB 就要分析 dex/原因。

实用工具：

```bash
# 列出 APK 中各文件占用
unzip -l app/build/outputs/apk/release/app-release.apk | sort -k1 -n -r | head -30
```

## 6. 扫码 / 解码

### 6.1 当前两层管线

1. **ZXing 三档**（快速）：`Hybrid` → `GlobalHistogram` → `Invert`
2. **ML Kit Barcode Scanning**（兜底，bundled，离线）：处理屏摄、水印、低对比度截图

不要再回到"灰度+中值滤波 / GenericMultipleBarcodeReader 切块"的旧兜底方案
（已经在 commit `d850298` 后被简化掉了）。

### 6.2 大图

相册导入大图必须：

- **后台线程** 解码（`8f1aa7d` 修过 OOM）
- 按需 `inSampleSize` 降采样

### 6.3 ProGuard

ZXing 通过 `Class.forName` 加载各码制 Reader。我们**只**需要 `QRCodeReader`，
所以在 `proguard-rules.pro` 里 keep 的范围严格收窄：

```proguard
-keep class com.google.zxing.qrcode.QRCodeReader { *; }
-keep class com.journeyapps.barcodescanner.BarcodeView { *; }
-keep class com.journeyapps.barcodescanner.ViewfinderView { *; }
-keep class com.journeyapps.barcodescanner.camera.** { *; }
```

新增码制（条形码 / DataMatrix）才需要扩展，否则**不要 keep 整个 zxing 包**。

## 7. UI / Insets

- 所有顶级 Activity 内容根布局必须 `fitsSystemWindows="true"` 或交给 `InsetsUtils` 处理
- 子页面用自有 `MaterialToolbar` + `AppBarLayout`，不要用默认 ActionBar
  （否则状态栏背景与品牌蓝不一致，详见 `b0d8e07`）
- 倒计时进度条 = 卡片底部 4dp 胶囊条；剩余 ≤5s 切换为告警橙红 `#EF4444`
- 列表 `tick` 刷新只更新 RecyclerView **可见区间**

## 8. 应用锁（LockActivity）

`LockActivity` 是三模式状态机：`SETUP` / `UNLOCK` / `CHANGE`，由 `EXTRA_MODE` 决定。
要点：

- 校验失败时 **不要 dismiss** 输入对话框（`326806c`）
- "应用密码" 与 "设备锁屏密码" 是两件事，文案不要混（`d804384`）
- `CHANGE` 模式按返回键 = 取消并回到调用者，**不**影响当前已解锁会话

## 9. 备份格式

`BackupCodec` 当前 `VERSION = 2`：

```json
{
  "v": 2,
  "salt": "<base64>",
  "data": "<base64(AES-GCM payload)>"
}
```

**必须保留 v1 兼容路径**（`bf.optInt("v", 1)` 的分支不能删）— 老用户的导出文件
还在野外。新版本号上线时同样的兼容策略。

## 10. 测试

- 单元测试：`./gradlew :app:test` — 不需要设备
- 仪器测试：`./gradlew :app:connectedAndroidTest` — 需要 USB / 模拟器
- `OtpGeneratorTest` 中已经植入 RFC 4226 附录 D 的官方测试向量，**不要随意改这些常量**

## 11. 常见任务速记

| 我想 ... | 先看 / 改 |
|---|---|
| 加新的 OTP 算法字段 | `OtpAccount` → `OtpUriParser` → `OtpGenerator` → `OtpRepository` 列定义 |
| 改备份文件格式 | `BackupCodec`，并务必加 v 字段 + 老版本兼容 |
| 加新的导入源 | `ImportExportActivity` + 必要时新写一个 decoder（参照 `GaMigrationDecoder`） |
| 调列表 UI | `item_otp.xml` + `MainActivity` 内的 ViewHolder |
| 改字符串文案 | `res/values/strings.xml`（先看是否已有 key） |
| 加新 Activity | 在 `AndroidManifest.xml` 中显式声明，并在主题上保持品牌蓝状态栏 |
| 调试期开/关 FLAG_SECURE | `BaseSecureActivity`（**不要**整体下掉，仅 debug 关闭） |

## 12. 千万别忘记

- 这是用户**主力使用**的 2FA App。任何会让"打不开 / 看不到验证码 / 解锁失败"的改动都属于 **P0**。
- 改完之后跑一遍：扫码 → 添加 → 列表显示 → 锁屏返回解锁 → 修改 PIN → 备份导出 → 备份导入 → 删除。
- 不确定时，**先 commit 当前可用版本再动手**，方便随时 `git reset --hard` 回滚。
