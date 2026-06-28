# 安全自审 Checklist · feat/ux-enhancement

> Sam（安全工程师）针对 `feat/ux-enhancement` 分支 7 个子任务的逐项核对。
> 与 [CLAUDE.md §4 安全红线](../CLAUDE.md) 11 条逐条比对。

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
- [ ] PIN 设置 / 修改 / 关闭 / 解锁 / 生物识别快捷解锁全部正常
- [ ] 加密备份 .2fa 导出 + 导入
- [ ] 明文 CSV 导出（含警告对话框）+ 导入
- [ ] 后台脱敏遮罩生效，FLAG_SECURE 在 release 包阻止截屏

## 4. 包体守门

> 本次未引入新依赖；仅新增 4 个 Java 类、3 张 layout/drawable 修改、若干 string。
> 预估 release APK 体积变化：**+5 ~ +10 KB**（新增字符串与 dex），仍在 7.3 MB 基线内。

— Sam · 2026
