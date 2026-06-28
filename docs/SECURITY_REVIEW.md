# 安全自审 Checklist · feat/ux-enhancement

> Sam（安全工程师）针对 `feat/ux-enhancement` 分支子任务的逐项核对。
> 与 [CLAUDE.md §4 安全红线](../CLAUDE.md) 11 条逐条比对。
> 覆盖范围：v0.1（T1–T7）+ v0.2（T8 / S1 / SearchFilterTest 修复）。

## 1. 红线核对

| # | 红线 | 本次是否触碰 | 说明 |
|---|---|---|---|
| 1 | 新增网络权限 / 上报 SDK | ❌ 未触碰 | 仅修改 UI / 偏好 / 剪贴板，未新增依赖 |
| 2 | secret 明文落盘 | ❌ 未触碰 | OtpRepository 加密路径未变 |
| 3 | 降低 PIN PBKDF2 轮数 | ❌ 未触碰 | AppLockManager 仅扩展宽限期，未动派生 |
| 4 | 关闭 allowBackup | ❌ 未触碰 | AndroidManifest 未改 |
| 5 | release 关闭 FLAG_SECURE | ❌ 未触碰 | BaseSecureActivity 未改 |
| 6 | 改回 androidx.security:security-crypto | ❌ 未触碰 |  |
| 7 | ML Kit 改 unbundled | ❌ 未触碰 |  |
| 8 | 引入 Gson / Jackson / kotlin-reflect | ❌ 未触碰 |  |
| 9 | 重新打开 R8 fullMode | ❌ 未触碰 | gradle.properties / build.gradle 未改 |

✅ 全部 9 条红线未被触碰。

## 2. 子任务安全分析

### T1 搜索过滤
- 仅前端基于内存中已解密的 issuer / account 字段做 `String.contains`
- 不写日志，不上报，不影响存储
- 风险：**极低**

### T2 复制 + 30s 自动清剪贴板（`ClipboardCleaner`）
- ✅ 写入剪贴板时 `PersistableBundle.putBoolean("android.content.extra.IS_SENSITIVE", true)`
  → Android 13+ 系统通知不回显验证码内容
- ✅ 30s 后清剪贴板前先校验 `kExtraOwner` 私有指纹，**只清自家写入的内容**，不会误删用户其它复制
- ⚠️ 已知约束：剪贴板上下游（IME、其他 App）可能在我方写入瞬间缓存明文，这是平台限制，不在本组件保护范围内
- 风险：**中** — 剪贴板的本质决定了"短暂明文"无法避免，已最大化降低残留

### T3 隐藏验证码（默认关闭）
- 减少肩窥风险，**改善**安全姿态
- 状态保存在普通 SharedPreferences，无可逆秘密
- 风险：**极低**（且为正向收益）

### T4 下一码预览
- "下一码"在过去 30 秒内本来就会变成"当前码"，曝光提前 ≤5 秒不增加任何攻击面
- 不改变 RFC 6238 算法
- 风险：**极低**

### T5 自动锁定宽限期
- ⚠️ 默认值 = **0**（立即锁定），与原版完全一致 → 老用户行为不变
- 仅在用户主动配置 N>0 后生效
- `AppLockManager.shouldRequireUnlock` 用 `backgroundAt_ + grace*1000ms` 严格判定
- **未污染**红线 #3（PIN 轮数）；不影响 PIN 校验路径
- 风险：**中** — 任何"不立即锁"的能力本质都降低安全强度，**已通过默认 0 + 显式配置**双重门禁

### T6 撤销删除
- snapshot 仅在内存中保留 10 秒（Snackbar 生命周期）
- 撤销时调用 OtpRepository.insert，secret 字段会重新走加密路径
- 撤销窗口期内 secret 仍在数据库（已加密），未提升攻击面
- 风险：**极低**

### T7 字母色块图标
- 纯 Drawable 代码绘制，零 PNG 资源
- 颜色函数为非加密哈希（FNV-1a），仅用于稳定色相，无安全属性
- 不联网获取真实品牌 LOGO
- 风险：**极低**

### T8 长按拖拽排序
- 仅调整 `sort_order` 列（int），绝不触叐 `secret_enc`、`algorithm`、`counter` 等敏感列
- 持久化走**单事务**批量 UPDATE，要么全部成功要么全部回滚，不会出现部分写入的中间态
- **搜索过滤态下禁用拖拽**：避免用户基于子集拖动后写回造成未可见账号的顺序被覆盖
- 持久化代码跳出 UI 线程（`bgExecutor_`），避免主线程 ANR
- 风险：**极低**

### S1 设置 Activity
- 继承 `BaseSecureActivity`，FLAG_SECURE 与解锁路由与主页一致；不存在“在设置页能截屏、主页不能”不一致
- 仅读写 `UiPreferences`、不暴露 PIN / 生物识别 / 备份入口（这些需要二次验证，独立流程维护）
- 设置项本身不含可逆秘密（都是布尔 / 枚举 int），走普通 SP 符合原有架构原则
- 风险：**极低**

### 修复 SearchFilterTest needle 大小写归一化
- 仅修改 `app/src/test/`，不改变产品代码语义
- MainActivity.applyFilter 依然在入口处 `currentQuery_.toLowerCase(Locale.ROOT)`，运行时行为与修复前一致
- 风险：**无**

## 3. 验收前回归矩阵（建议在物理机走一遍）

- [ ] 扫码添加账号
- [ ] 相册导入二维码
- [ ] 手动添加账号
- [ ] Google Authenticator 迁移二维码导入
- [ ] 列表显示 / 倒计时 / 颜色阈值
- [ ] **新**：搜索框输入过滤 / 清除恢复
- [ ] **新**：点按账号 → 验证码复制 + 30s 后剪贴板被清空
- [ ] **新**：开启隐藏验证码偏好后，列表项默认遮罩，点按显形 5s
- [ ] **新**：剩余 ≤5s 时下一码出现，过期后立即回到正常显示
- [ ] **新**：宽限期默认 0 → 后台返回需要解锁；改为 30s 后 20s 内返回不解锁、31s 后需要解锁
- [ ] **新**：滑动删除 → Snackbar 撤销，账号完整恢复
- [ ] **新**：列表项左侧首字母色块图标，相同 issuer 颜色稳定
- [ ] **新**：长按账号拖拽排序，松手后顺序持久化、杀进程重开仍保留
- [ ] **新**：搜索框非空时长按不触发拖拽
- [ ] **新**：「设置」页可调隐藏码 / 下一码 / 宽限期，返回主列表立即生效
- [ ] PIN 设置 / 修改 / 关闭 / 解锁 / 生物识别快捷解锁全部正常
- [ ] 加密备份 .2fa 导出 + 导入
- [ ] 明文 CSV 导出（含警告对话框）+ 导入
- [ ] 后台脱敏遮罩生效，FLAG_SECURE 在 release 包阻止截屏

## 4. 包体守门

> v0.1：未引入新依赖；仅新增 4 个 Java 类、3 张 layout/drawable 修改、若干 string。
> v0.2：只新增 1 个 Java 类（SettingsActivity）+ 1 张 layout + 11 条 string，未引入任何依赖。
> 预估 release APK 体积变化：**+15 ~ +25 KB**，仍在 7.3 MB 基线内。
> 实测数据请参考本轮 `feat: release APK size verification` 提交中的记录。

— Sam · 2026
