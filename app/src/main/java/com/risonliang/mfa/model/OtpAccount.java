/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.model;

/**
 * OTP 账号实体。secret 字段在数据库中以加密 + Base64 形式存储。
 * 支持 TOTP（基于时间）和 HOTP（基于计数器）两种类型。
 */
public class OtpAccount {

    public static final String TYPE_TOTP = "totp";
    public static final String TYPE_HOTP = "hotp";

    public static final String DEFAULT_ALGO = "SHA1";
    public static final int DEFAULT_DIGITS = 6;
    public static final int DEFAULT_PERIOD = 30;

    public long id;
    public String issuer;       // 服务名（如 Google）
    public String account;      // 账号（如 user@gmail.com）
    public String secret;       // Base32 明文密钥（仅在内存中持有）
    public String type;         // totp 或 hotp
    public String algorithm;    // SHA1 / SHA256 / SHA512
    public int digits;
    public int period;
    public long counter;        // HOTP 计数器（仅 HOTP 使用）
    public long createdAt;
    public int sortOrder;

    public OtpAccount() {
        this.type = TYPE_TOTP;
        this.algorithm = DEFAULT_ALGO;
        this.digits = DEFAULT_DIGITS;
        this.period = DEFAULT_PERIOD;
    }

    public boolean isHotp() {
        return TYPE_HOTP.equalsIgnoreCase(type);
    }

    /**
     * 校验账号数据是否合法。
     * @return true 表示数据完整且合法，可安全用于 OTP 生成。
     */
    public boolean isValid() {
        if (secret == null || secret.isEmpty()) {
            return false;
        }
        if (digits < 4 || digits > 8) {
            return false;
        }
        if (!isHotp() && (period < 10 || period > 300)) {
            return false;
        }
        if (isHotp() && counter < 0) {
            return false;
        }
        String algo = (algorithm == null) ? "" : algorithm.toUpperCase();
        if (!algo.equals("SHA1") && !algo.equals("SHA256")
                && !algo.equals("SHA512")) {
            return false;
        }
        return true;
    }

    public String displayLabel() {
        if (issuer == null || issuer.isEmpty()) {
            return account == null ? "" : account;
        }
        if (account == null || account.isEmpty()) {
            return issuer;
        }
        return issuer + " (" + account + ")";
    }
}
