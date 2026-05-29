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
 *  4. 维护"已解锁会话"窗口，超过 30 秒进入后台自动失效，要求重新解锁。
 *
 * 仅本地、不联网；与 CryptoManager 互不依赖，避免循环。
 */
public final class AppLockManager {

    /** 默认锁屏宽限期 30 秒。 */
    public static final long kIdleLockTimeoutMs = 30_000L;

    private static final String kPrefsName = "mfa_lock_prefs";
    private static final String kKeyPinHash = "pin_hash";
    private static final String kKeyPinSalt = "pin_salt";
    private static final String kKeyBiometricEnabled = "bio_enabled";
    private static final int kPbkdfIterations = 150_000;
    private static final int kPbkdfKeyLength = 256;
    private static final int kSaltLength = 16;

    private static volatile AppLockManager sInstance;

    private final SharedPreferences prefs_;

    /** 当前会话是否已解锁；进程内态。 */
    private volatile boolean unlocked_ = false;
    /** 上一次进入后台的时间戳，用于宽限期判定。 */
    private volatile long backgroundAt_ = 0L;

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

    /** 设置/重置 PIN。 */
    public void setPin(char[] pin) throws Exception {
        byte[] salt = new byte[kSaltLength];
        new SecureRandom().nextBytes(salt);
        byte[] hash = pbkdf2(pin, salt);
        prefs_.edit()
                .putString(kKeyPinSalt,
                        Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(kKeyPinHash,
                        Base64.encodeToString(hash, Base64.NO_WRAP))
                .apply();
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
     * 进入后台时调用。仅记录时间戳，由 shouldRequireUnlock 在回前台时判断。
     */
    public void onAppBackgrounded() {
        backgroundAt_ = System.currentTimeMillis();
    }

    /**
     * 回到前台时调用，决定是否需要重新解锁。
     * 超过宽限期或从未解锁，则需要解锁。
     */
    public boolean shouldRequireUnlock() {
        if (!unlocked_) {
            return true;
        }
        if (backgroundAt_ <= 0L) {
            return false;
        }
        long elapsed = System.currentTimeMillis() - backgroundAt_;
        if (elapsed >= kIdleLockTimeoutMs) {
            unlocked_ = false;
            return true;
        }
        return false;
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
