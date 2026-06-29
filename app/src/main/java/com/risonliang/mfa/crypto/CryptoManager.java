/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.crypto;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 加密管理器：基于 Android Keystore 持久化 AES-256 主密钥，对外提供两类能力：
 *
 * <ol>
 *   <li>{@link #encrypt(byte[])} / {@link #decrypt(byte[])} —— 使用 Keystore
 *       中的设备绑定主密钥加密本地敏感字段（如 OTP secret）。</li>
 *   <li>{@link #encryptWithPassword} / {@link #decryptWithPassword} —— 使用
 *       用户口令派生临时密钥（PBKDF2-HMAC-SHA256），用于离线加密备份文件。</li>
 * </ol>
 *
 * <p>统一密文输出格式：{@code [12B IV][密文 || 16B GCM Tag]}；是否再 Base64
 * 编码由调用方按场景决定（数据库 / JSON 文件均使用 Base64）。
 *
 * <p>线程安全：本类所有 public 方法都是无状态静态方法，内部对 Keystore 主密钥
 * 的获取通过类锁串行化，避免首次启动多线程并发时重复生成主密钥。
 */
public final class CryptoManager {

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "mfa_master_key_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BIT = 128;
    private static final int AES_KEY_BITS = 256;

    /**
     * PBKDF2 轮数。OWASP 2023 建议 ≥ 600000，这里为兼容已分发的备份文件
     * 仍保持 100000；下一次提升轮数时必须把实际轮数写入备份文件并保留旧值
     * 解析路径，避免老备份失效。
     *
     * <p>TODO(risonliang): 在 BackupCodec v3 中将 iter 写入文件头并升级到
     * 600000 轮。
     */
    private static final int PBKDF2_ITERATIONS = 100000;

    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";

    /** SecureRandom 构造较重，复用一个全局实例即可（线程安全）。 */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** 主密钥获取锁，避免并发首次启动重复 generate。 */
    private static final Object MASTER_KEY_LOCK = new Object();

    private CryptoManager() {}

    // ---------------------------------------------------------------------
    // Keystore 主密钥加解密
    // ---------------------------------------------------------------------

    /**
     * 使用 Keystore 中的设备绑定主密钥加密给定明文。
     *
     * @param plain 待加密的明文字节，不能为 {@code null}
     * @return {@code [12B IV][密文 || 16B Tag]} 的拼接结果
     * @throws GeneralSecurityException 当 Keystore 不可用、Cipher 初始化失败
     *         或加密过程中发生密码学异常时抛出
     */
    public static byte[] encrypt(byte[] plain) throws GeneralSecurityException {
        if (plain == null) {
            throw new IllegalArgumentException("plain == null");
        }
        SecretKey key = obtainMasterKey();
        // Keystore Provider 在 init(ENCRYPT_MODE, key) 时会自动生成随机 IV，
        // 此处显式取回该 IV 一并落盘，与口令派生路径的输出格式保持一致。
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        byte[] cipherText = cipher.doFinal(plain);
        return concatIvAndCipher(iv, cipherText);
    }

    /**
     * 使用 Keystore 中的设备绑定主密钥解密 {@link #encrypt} 的输出。
     *
     * @param payload 形如 {@code [12B IV][密文 || 16B Tag]} 的字节流
     * @return 解密后的原始明文
     * @throws GeneralSecurityException Tag 校验失败或 Keystore 不可用时抛出
     * @throws IllegalArgumentException payload 非法（null 或长度不足）
     */
    public static byte[] decrypt(byte[] payload)
            throws GeneralSecurityException {
        ensureValidPayload(payload);
        SecretKey key = obtainMasterKey();
        return aesGcmDecryptInternal(payload, key);
    }

    // ---------------------------------------------------------------------
    // 口令派生临时密钥的加解密（离线备份场景）
    // ---------------------------------------------------------------------

    /**
     * 使用用户口令派生 AES-256 密钥后加密。派生算法为 PBKDF2-HMAC-SHA256，
     * 轮数见 {@link #PBKDF2_ITERATIONS}。
     *
     * @param plain    待加密明文
     * @param password 用户输入的口令字符数组（调用方自行清理）
     * @param salt     盐值，建议每个备份文件独立随机生成
     * @return 形如 {@code [12B IV][密文 || 16B Tag]} 的字节流
     * @throws GeneralSecurityException 派生或加密失败时抛出
     */
    public static byte[] encryptWithPassword(byte[] plain, char[] password, byte[] salt)
            throws GeneralSecurityException {
        if (plain == null) {
            throw new IllegalArgumentException("plain == null");
        }
        SecretKey key = deriveKey(password, salt);
        return aesGcmEncryptInternal(plain, key);
    }

    /**
     * 使用用户口令派生 AES-256 密钥后解密 {@link #encryptWithPassword} 的输出。
     *
     * @param payload  形如 {@code [12B IV][密文 || 16B Tag]} 的字节流
     * @param password 用户输入的口令字符数组（调用方自行清理）
     * @param salt     与加密时一致的盐值
     * @return 解密后的原始明文
     * @throws GeneralSecurityException 派生失败、Tag 校验失败时抛出
     * @throws IllegalArgumentException payload 非法（null 或长度不足）
     */
    public static byte[] decryptWithPassword(byte[] payload, char[] password, byte[] salt)
            throws GeneralSecurityException {
        ensureValidPayload(payload);
        SecretKey key = deriveKey(password, salt);
        return aesGcmDecryptInternal(payload, key);
    }

    /**
     * 使用全局共享 {@link SecureRandom} 生成指定长度的盐值。
     *
     * @param len 期望的盐字节数，必须为正
     * @return 长度为 {@code len} 的随机字节数组
     */
    public static byte[] randomSalt(int len) {
        if (len <= 0) {
            throw new IllegalArgumentException("len must be positive");
        }
        byte[] salt = new byte[len];
        SECURE_RANDOM.nextBytes(salt);
        return salt;
    }

    // ---------------------------------------------------------------------
    // 内部实现
    // ---------------------------------------------------------------------

    private static SecretKey deriveKey(char[] password, byte[] salt)
            throws GeneralSecurityException {
        if (password == null || salt == null) {
            throw new IllegalArgumentException("password / salt == null");
        }
        PBEKeySpec spec = new PBEKeySpec(
                password, salt, PBKDF2_ITERATIONS, AES_KEY_BITS);
        try {
            SecretKeyFactory factory =
                    SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            try {
                return new SecretKeySpec(keyBytes, "AES");
            } finally {
                // 派生密钥字节用完即清零，降低内存中残留的窗口期。
                Arrays.fill(keyBytes, (byte) 0);
            }
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] aesGcmEncryptInternal(byte[] plain, SecretKey key)
            throws GeneralSecurityException {
        byte[] iv = new byte[IV_LENGTH];
        SECURE_RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key,
                new GCMParameterSpec(TAG_LENGTH_BIT, iv));
        byte[] cipherText = cipher.doFinal(plain);
        return concatIvAndCipher(iv, cipherText);
    }

    private static byte[] aesGcmDecryptInternal(byte[] payload, SecretKey key)
            throws GeneralSecurityException {
        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(payload, 0, iv, 0, IV_LENGTH);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(TAG_LENGTH_BIT, iv));
        return cipher.doFinal(payload, IV_LENGTH, payload.length - IV_LENGTH);
    }

    private static byte[] concatIvAndCipher(byte[] iv, byte[] cipherText) {
        byte[] out = new byte[iv.length + cipherText.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(cipherText, 0, out, iv.length, cipherText.length);
        return out;
    }

    private static void ensureValidPayload(byte[] payload) {
        if (payload == null || payload.length <= IV_LENGTH) {
            throw new IllegalArgumentException("invalid ciphertext");
        }
    }

    /**
     * 获取 Keystore 中的主密钥，若不存在则首次创建。通过类锁串行化以避免
     * 多线程并发调用时重复生成密钥（TOCTOU），那会导致已入库的密文全部
     * 因为主密钥被覆盖而无法解密。
     */
    private static SecretKey obtainMasterKey()
            throws GeneralSecurityException {
        synchronized (MASTER_KEY_LOCK) {
            try {
                KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
                ks.load(null);
                if (ks.containsAlias(KEY_ALIAS)) {
                    KeyStore.Entry entry = ks.getEntry(KEY_ALIAS, null);
                    if (entry instanceof KeyStore.SecretKeyEntry) {
                        return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
                    }
                    // 别名已存在但类型异常（如设备升级 / Keystore 损坏）。
                    // 不静默覆盖，交由上层决策（提示用户重置数据等），
                    // 否则会导致已加密入库的 secret 全部失效。
                    throw new KeyStoreException(
                            "unexpected keystore entry type for alias "
                                    + KEY_ALIAS);
                }
                return generateMasterKey();
            } catch (KeyStoreException e) {
                throw e;
            } catch (GeneralSecurityException e) {
                throw e;
            } catch (Exception e) {
                // KeyStore.load 声明 throws IOException 等非
                // GeneralSecurityException 的检查异常，此处统一包装。
                throw new KeyStoreException("load AndroidKeyStore failed", e);
            }
        }
    }

    private static SecretKey generateMasterKey()
            throws GeneralSecurityException {
        KeyGenerator kg = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .build();
        kg.init(spec);
        return kg.generateKey();
    }
}
