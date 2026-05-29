/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.model;

/**
 * OTP 账号实体。secret 字段在数据库中以加密 + Base64 形式存储。
 */
public class OtpAccount {

    public static final String DEFAULT_ALGO = "SHA1";
    public static final int DEFAULT_DIGITS = 6;
    public static final int DEFAULT_PERIOD = 30;

    public long id;
    public String issuer;       // 服务名（如 Google）
    public String account;      // 账号（如 user@gmail.com）
    public String secret;       // Base32 明文密钥（仅在内存中持有）
    public String algorithm;    // SHA1 / SHA256 / SHA512
    public int digits;
    public int period;
    public long createdAt;
    public int sortOrder;

    public OtpAccount() {
        this.algorithm = DEFAULT_ALGO;
        this.digits = DEFAULT_DIGITS;
        this.period = DEFAULT_PERIOD;
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
