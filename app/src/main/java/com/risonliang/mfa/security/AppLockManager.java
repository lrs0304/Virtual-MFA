/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 应用锁管理器。
 *
 * 功能职责：
 *  1. 首次使用引导设置 PIN（最少 4 位，最多 12 位）。
 *  2. PIN 不明文存储；保存为 PBKDF2-HMAC-SHA256（150000 轮）+ 16B 随机 salt 的哈希。
 *  3. 偏好通过 EncryptedSharedPreferences 落盘，由 Android Keystore 主密钥保护。
 *  4. 维护"已解锁会话"窗口，由 SecureActivity 的 onStart/onStop 维护应用级前台计数；
 *     一旦真正切到桌面或其它 App，即立即清除解锁状态，下次回到前台必须重新验证。
 *
 * 仅本地、不联网；与 CryptoManager 互不依赖，避免循环。
 */
public final class AppLockManager {

    private static final String kPrefsName = "mfa_lock_prefs";
    private static final String kKeyPinHash = "pin_hash";
    private static final String kKeyPinSalt = "pin_salt";
    private static final String kKeyBiometricEnabled = "bio_enabled";
    private static final String kKeyLockEnabled = "lock_enabled";
    private static final int kPbkdfIterations = 150_000;
    private static final int kPbkdfKeyLength = 256;
    private static final int kSaltLength = 16;

    private static volatile AppLockManager sInstance;

    private final SharedPreferences prefs_;

    /** 当前会话是否已解锁；进程内态。 */
    private volatile boolean unlocked_ = false;
    /** 上一次进入后台的时间戳，用于宽限期判定。 */
    private volatile long backgroundAt_ = 0L;
    /**
     * 应用级前台 Activity 计数。仅在 0->1 与 1->0 翻转时分别触发
     * "回到前台""进入后台"语义，避免 App 内 Activity 跳转产生抖动。
     */
    private int foregroundCount_ = 0;

    public static AppLockManager get(Context ctx) {
        if (sInstance == null) {
            synchronized (AppLockManager.class) {
                if (sInstance == null) {
                    sInstance = new AppLockManager(ctx.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private AppLockManager(Context appCtx) {
        SharedPreferences sp;
        try {
            MasterKey masterKey = new MasterKey.Builder(appCtx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            sp = EncryptedSharedPreferences.create(
                    appCtx,
                    kPrefsName,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme
                            .AES256_GCM);
        } catch (Exception e) {
            // 极端兜底：Keystore 异常时降级为普通 SP，保持可用性；
            // PIN 仍为哈希存储，不会泄露明文。
            sp = appCtx.getSharedPreferences(kPrefsName, Context.MODE_PRIVATE);
        }
        prefs_ = sp;
    }

    /** 是否已设置 PIN（即是否完成首次初始化）。 */
    public boolean isPinConfigured() {
        return prefs_.contains(kKeyPinHash) && prefs_.contains(kKeyPinSalt);
    }

    /**
     * 应用密码功能是否开启。默认关闭：
     * 用户须显式在主菜单点击"启用应用密码"完成首次设置后才会标记为开启。
     */
    public boolean isLockEnabled() {
        return prefs_.getBoolean(kKeyLockEnabled, false)
                && isPinConfigured();
    }

    /** 设置启用开关。仅内部使用，外部应通过 setPin / disableLock 间接驱动。 */
    private void setLockEnabledInternal(boolean enabled) {
        prefs_.edit().putBoolean(kKeyLockEnabled, enabled).apply();
    }

    /** 设置/重置 PIN，并自动标记应用密码为启用。 */
    public void setPin(char[] pin) throws Exception {
        byte[] salt = new byte[kSaltLength];
        new SecureRandom().nextBytes(salt);
        byte[] hash = pbkdf2(pin, salt);
        prefs_.edit()
                .putString(kKeyPinSalt,
                        Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(kKeyPinHash,
                        Base64.encodeToString(hash, Base64.NO_WRAP))
                .putBoolean(kKeyLockEnabled, true)
                .apply();
    }

    /**
     * 关闭应用密码：清除 PIN、salt 与启用标记，并立即解除会话锁定。
     * 调用方必须先用 {@link #verifyPin(char[])} 校验通过当前 PIN，方可调用本方法。
     */
    public void disableLock() {
        clearAllSecrets();
    }

    /**
     * 彻底清除 PIN、salt 与启用标记（不做任何校验）。
     *
     * 用途：
     *  1. 用户通过 {@link #disableLock()} 关闭应用密码时复用本接口；
     *  2. SETUP 入口前置调用：当检测到旧版本残留的 "PIN 在但 lock_enabled=false" 不一致状态时，
     *     先彻底清掉再让用户重新设置，确保 LockActivity 一定走 SETUP 流程而非误判为 UNLOCK。
     *
     * 注意：本方法不要求当前会话已解锁，调用方必须自行确保安全语义。
     */
    public void clearAllSecrets() {
        prefs_.edit()
                .remove(kKeyPinHash)
                .remove(kKeyPinSalt)
                .putBoolean(kKeyLockEnabled, false)
                .apply();
        // 清除会话状态，避免脏数据。
        unlocked_ = false;
        backgroundAt_ = 0L;
    }

    /** 校验 PIN，常量时间比较。 */
    public boolean verifyPin(char[] pin) {
        String saltB64 = prefs_.getString(kKeyPinSalt, null);
        String hashB64 = prefs_.getString(kKeyPinHash, null);
        if (saltB64 == null || hashB64 == null) {
            return false;
        }
        try {
            byte[] salt = Base64.decode(saltB64, Base64.NO_WRAP);
            byte[] expected = Base64.decode(hashB64, Base64.NO_WRAP);
            byte[] actual = pbkdf2(pin, salt);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    /** 是否启用了生物识别快捷解锁（用户可在设置中关闭）。 */
    public boolean isBiometricEnabled() {
        return prefs_.getBoolean(kKeyBiometricEnabled, true);
    }

    public void setBiometricEnabled(boolean enabled) {
        prefs_.edit().putBoolean(kKeyBiometricEnabled, enabled).apply();
    }

    /** 标记会话已解锁。 */
    public void markUnlocked() {
        unlocked_ = true;
        backgroundAt_ = 0L;
    }

    /** 立即上锁（如用户从设置中点击"立即锁定"）。 */
    public void lockNow() {
        unlocked_ = false;
        backgroundAt_ = 0L;
    }

    /**
     * 任意 SecureActivity onStart 时调用。
     * 仅在前台计数从 0 翻转到 1 时视作"真正回到前台"，并返回是否需要解锁。
     * 否则视作 App 内的 Activity 切换，不强制重新解锁。
     */
    public synchronized boolean onActivityStarted() {
        foregroundCount_++;
        if (foregroundCount_ == 1) {
            return shouldRequireUnlock();
        }
        return false;
    }

    /**
     * 任意 SecureActivity onStop 时调用。
     * 仅在前台计数从 1 翻转到 0 时视作"真正进入后台"：立即记录时间戳并清除会话解锁标记，
     * 确保下次回到前台一定要求重新验证；App 内 Activity 跳转不会触发这一步。
     */
    public synchronized void onActivityStopped() {
        if (foregroundCount_ > 0) {
            foregroundCount_--;
        }
        if (foregroundCount_ == 0) {
            backgroundAt_ = System.currentTimeMillis();
            // 关键：真正进入后台（用户切桌面 / 切其它 App）即立刻清除解锁状态。
            // 不引入时间宽限期——前台计数已经吞掉应用内跳转噪声。
            unlocked_ = false;
        }
    }

    /**
     * 进入后台时调用。仅记录时间戳，由 shouldRequireUnlock 在回前台时判断。
     * 保留作为外部调用者的兼容入口。
     */
    public void onAppBackgrounded() {
        backgroundAt_ = System.currentTimeMillis();
    }

    /**
     * 回到前台时决定是否需要解锁。
     * 未启用应用密码 → 直接通过；
     * unlocked_=false（含真正进入后台被清除的情况）→ 必须解锁。
     */
    public boolean shouldRequireUnlock() {
        if (!isLockEnabled()) {
            return false;
        }
        return !unlocked_;
    }

    private static byte[] pbkdf2(char[] pin, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pin, salt,
                kPbkdfIterations, kPbkdfKeyLength);
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance(
                    "PBKDF2WithHmacSHA256");
            return f.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }
}
