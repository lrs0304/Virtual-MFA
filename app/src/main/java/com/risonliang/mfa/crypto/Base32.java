/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.crypto;

/**
 * Base32 编解码工具（RFC 4648），用于 TOTP secret 的解析。
 * 不依赖第三方库，避免引入额外体积。
 */
public final class Base32 {

    private static final char[] ALPHABET = {
            'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H',
            'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P',
            'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X',
            'Y', 'Z', '2', '3', '4', '5', '6', '7'
    };

    private static final int[] DECODE_TABLE = new int[128];

    static {
        for (int i = 0; i < DECODE_TABLE.length; i++) {
            DECODE_TABLE[i] = -1;
        }
        for (int i = 0; i < ALPHABET.length; i++) {
            DECODE_TABLE[ALPHABET[i]] = i;
        }
    }

    private Base32() {}

    /** 将 Base32 字符串解码为 byte 数组。允许小写、空格、'-'、'='。 */
    public static byte[] decode(String src) {
        if (src == null) {
            throw new IllegalArgumentException("base32 input null");
        }
        String s = src.replace(" ", "").replace("-", "").replace("=", "")
                .toUpperCase();
        int len = s.length();
        if (len == 0) {
            return new byte[0];
        }
        int outLen = len * 5 / 8;
        byte[] out = new byte[outLen];
        int buffer = 0;
        int bitsLeft = 0;
        int idx = 0;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c >= DECODE_TABLE.length || DECODE_TABLE[c] < 0) {
                throw new IllegalArgumentException("invalid base32 char: " + c);
            }
            buffer = (buffer << 5) | DECODE_TABLE[c];
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out[idx++] = (byte) (buffer >> (bitsLeft - 8));
                bitsLeft -= 8;
            }
        }
        return out;
    }

    /** 校验是否为合法的 Base32 字符串。 */
    public static boolean isValid(String src) {
        if (src == null || src.isEmpty()) {
            return false;
        }
        try {
            decode(src);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 将原始字节数组编码为 Base32 字符串（不含 padding）。 */
    public static String encode(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                int index = (buffer >> (bitsLeft - 5)) & 0x1F;
                sb.append(ALPHABET[index]);
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            int index = (buffer << (5 - bitsLeft)) & 0x1F;
            sb.append(ALPHABET[index]);
        }
        return sb.toString();
    }
}
