/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.crypto;

import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.KeyStore;
import javax.crypto.KeyGenerator;

/**
 * 加密管理器：基于 Android Keystore 持久化主密钥，使用 AES-256-GCM 加密敏感字段（如 secret）。
 *
 * 输出格式：[12B IV][密文 + 16B GCM Tag]
 * 输出再做 Base64 处理由调用方决定（数据库存储时使用 Base64）。
 */
public final class CryptoManager {

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "mfa_master_key_v1";
    private static final String TRANSFORMATION =
            "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BIT = 128;

    private CryptoManager() {}

    /** 加密明文（UTF-8 字符串）。 */
    public static byte[] encrypt(byte[] plain) throws Exception {
        SecretKey key = obtainMasterKey();
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        byte[] cipherText = cipher.doFinal(plain);
        byte[] out = new byte[iv.length + cipherText.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(cipherText, 0, out, iv.length, cipherText.length);
        return out;
    }

    /** 解密。 */
    public static byte[] decrypt(byte[] payload) throws Exception {
        if (payload == null || payload.length <= IV_LENGTH) {
            throw new IllegalArgumentException("invalid ciphertext");
        }
        SecretKey key = obtainMasterKey();
        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(payload, 0, iv, 0, IV_LENGTH);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(TAG_LENGTH_BIT, iv));
        return cipher.doFinal(payload, IV_LENGTH, payload.length - IV_LENGTH);
    }

    /**
     * 使用用户提供的密码派生临时密钥进行加密 / 解密（用于导入导出文件）。
     * 派生算法：PBKDF2-HMAC-SHA256, 100000 轮，AES-256。
     */
    public static byte[] encryptWithPassword(byte[] plain, char[] password,
                                             byte[] salt) throws Exception {
        SecretKey key = deriveKey(password, salt);
        return aesGcmEncrypt(plain, key);
    }

    public static byte[] decryptWithPassword(byte[] payload, char[] password,
                                             byte[] salt) throws Exception {
        SecretKey key = deriveKey(password, salt);
        return aesGcmDecrypt(payload, key);
    }

    public static byte[] randomSalt(int len) {
        byte[] salt = new byte[len];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    private static SecretKey deriveKey(char[] password, byte[] salt)
            throws Exception {
        javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                password, salt, 100000, 256);
        javax.crypto.SecretKeyFactory factory =
                javax.crypto.SecretKeyFactory.getInstance(
                        "PBKDF2WithHmacSHA256");
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        spec.clearPassword();
        return new SecretKeySpec(keyBytes, "AES");
    }

    private static byte[] aesGcmEncrypt(byte[] plain, SecretKey key)
            throws Exception {
        byte[] iv = new byte[IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key,
                new GCMParameterSpec(TAG_LENGTH_BIT, iv));
        byte[] ct = cipher.doFinal(plain);
        byte[] out = new byte[IV_LENGTH + ct.length];
        System.arraycopy(iv, 0, out, 0, IV_LENGTH);
        System.arraycopy(ct, 0, out, IV_LENGTH, ct.length);
        return out;
    }

    private static byte[] aesGcmDecrypt(byte[] payload, SecretKey key)
            throws Exception {
        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(payload, 0, iv, 0, IV_LENGTH);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(TAG_LENGTH_BIT, iv));
        return cipher.doFinal(payload, IV_LENGTH, payload.length - IV_LENGTH);
    }

    private static SecretKey obtainMasterKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        return generateMasterKey();
    }

    private static SecretKey generateMasterKey() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build();
        kg.init(spec);
        return kg.generateKey();
    }
}
