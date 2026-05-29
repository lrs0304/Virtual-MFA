/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.crypto;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TOTP/HOTP 生成器（RFC 6238 / RFC 4226）。
 * 自实现以避免引入大体积第三方库（仅依赖 JDK javax.crypto）。
 */
public final class OtpGenerator {

    private static final int[] DIGITS_POWER = {
            1, 10, 100, 1000, 10000, 100000, 1000000, 10000000, 100000000
    };

    private OtpGenerator() {}

    /**
     * 生成 TOTP 验证码。
     *
     * @param secret    Base32 编码的密钥
     * @param algorithm SHA1 / SHA256 / SHA512
     * @param digits    验证码位数（通常 6）
     * @param period    周期秒（通常 30）
     * @param timeMs    当前毫秒时间戳
     * @return 验证码字符串（左侧补零）
     */
    public static String totp(String secret, String algorithm, int digits,
                              int period, long timeMs) {
        long counter = (timeMs / 1000L) / period;
        byte[] key = Base32.decode(secret);
        return hotp(key, counter, algorithm, digits);
    }

    /** 计算 TOTP 在当前周期内剩余秒数。 */
    public static int remainingSeconds(int period, long timeMs) {
        long sec = timeMs / 1000L;
        return period - (int) (sec % period);
    }

    /** 计算给定时间戳所属的 TOTP 周期序号。用于触发 UI 刷新。 */
    public static long periodIndex(int period, long timeMs) {
        return (timeMs / 1000L) / period;
    }

    private static String hotp(byte[] key, long counter, String algorithm,
                               int digits) {
        try {
            byte[] data = ByteBuffer.allocate(8).putLong(counter).array();
            String macAlgo = mapMacAlgo(algorithm);
            Mac mac = Mac.getInstance(macAlgo);
            mac.init(new SecretKeySpec(key, macAlgo));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            int otp = binary % DIGITS_POWER[Math.min(digits, 8)];
            StringBuilder sb = new StringBuilder(Integer.toString(otp));
            while (sb.length() < digits) {
                sb.insert(0, '0');
            }
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HOTP compute failed", e);
        }
    }

    private static String mapMacAlgo(String algorithm) {
        if (algorithm == null) {
            return "HmacSHA1";
        }
        switch (algorithm.toUpperCase()) {
            case "SHA256":
                return "HmacSHA256";
            case "SHA512":
                return "HmacSHA512";
            case "SHA1":
            default:
                return "HmacSHA1";
        }
    }
}
