/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.data;

import android.util.Base64;
import android.util.Log;
import com.risonliang.mfa.crypto.Base32;
import com.risonliang.mfa.model.OtpAccount;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Google Authenticator 迁移二维码解码器。
 *
 * GA 导出的二维码格式为：
 *   otpauth-migration://offline?data=<base64_encoded_protobuf>
 *
 * Protobuf 结构（手写解码，无需引入 protobuf 库）：
 *   message MigrationPayload {
 *     repeated OtpParameters otp_parameters = 1;
 *   }
 *   message OtpParameters {
 *     bytes  secret    = 1;
 *     string name      = 2;
 *     string issuer    = 3;
 *     int32  algorithm = 4;  // 0=unspec, 1=SHA1, 2=SHA256, 3=SHA512, 4=MD5
 *     int32  digits    = 5;  // 0=unspec, 1=SIX, 2=EIGHT
 *     int32  type      = 6;  // 0=unspec, 1=HOTP, 2=TOTP
 *     int64  counter   = 7;
 *   }
 */
public final class GaMigrationDecoder {

    private static final String SCHEME = "otpauth-migration";
    private static final String PARAM_DATA = "data";
    private static final String kLogTag = "MFA-Scan";

    private GaMigrationDecoder() {}

    /**
     * 判断给定 URI 是否为 GA 迁移格式。
     */
    public static boolean isMigrationUri(String uri) {
        return uri != null && uri.trim().toLowerCase().startsWith(SCHEME + "://");
    }

    /**
     * 解析 GA 迁移 URI，返回账号列表。
     *
     * @param uri 完整的 otpauth-migration://offline?data=... 字符串
     * @return 解析出的账号列表，解析失败返回空列表
     */
    public static List<OtpAccount> decode(String uri) {
        List<OtpAccount> result = new ArrayList<>();
        if (uri == null) {
            Log.w(kLogTag, "GaMigrationDecoder: input null");
            return result;
        }
        try {
            android.net.Uri parsed = android.net.Uri.parse(uri.trim());
            String dataParam = parsed.getQueryParameter(PARAM_DATA);
            if (dataParam == null || dataParam.isEmpty()) {
                Log.w(kLogTag, "GaMigrationDecoder: missing data param");
                return result;
            }
            // GA 官方导出用标准 Base64（含 + / =），但部分实现可能用 URL-safe，
            // 这里两种都试一下，避免因 Base64 变种导致 protobuf 解析失败。
            byte[] payload;
            try {
                payload = Base64.decode(dataParam, Base64.DEFAULT);
            } catch (IllegalArgumentException badStd) {
                Log.d(kLogTag,
                        "GaMigrationDecoder: standard Base64 failed, retry URL_SAFE");
                payload = Base64.decode(dataParam,
                        Base64.URL_SAFE | Base64.NO_PADDING);
            }
            Log.d(kLogTag, "GaMigrationDecoder: dataLen=" + dataParam.length()
                    + ", payloadLen=" + (payload == null ? 0 : payload.length));
            List<OtpAccount> list = parseMigrationPayload(payload);
            Log.d(kLogTag, "GaMigrationDecoder: parsed accounts=" + list.size());
            return list;
        } catch (Exception e) {
            Log.e(kLogTag, "GaMigrationDecoder.decode failed", e);
            return result;
        }
    }

    /**
     * 解析 MigrationPayload protobuf 字节。
     */
    private static List<OtpAccount> parseMigrationPayload(byte[] data) {
        List<OtpAccount> result = new ArrayList<>();
        int pos = 0;
        while (pos < data.length) {
            int tag = readTag(data, pos);
            pos += varintSize(data, pos);
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x07;

            if (fieldNumber == 1 && wireType == 2) {
                // length-delimited: OtpParameters
                int len = readVarint32(data, pos);
                pos += varintSize(data, pos);
                if (len < 0 || pos + len > data.length) {
                    break; // 数据被篡改，长度越界，终止解析
                }
                byte[] sub = new byte[len];
                System.arraycopy(data, pos, sub, 0, len);
                pos += len;
                OtpAccount acc = parseOtpParameters(sub);
                if (acc != null) {
                    result.add(acc);
                }
            } else {
                // 跳过未知字段
                pos = skipField(data, pos, wireType);
            }
        }
        return result;
    }

    /**
     * 解析单个 OtpParameters message。
     */
    private static OtpAccount parseOtpParameters(byte[] data) {
        byte[] secret = null;
        String name = null;
        String issuer = null;
        int algorithm = 0;
        int digitEnum = 0;
        int typeEnum = 0;
        long counter = 0;

        int pos = 0;
        while (pos < data.length) {
            int tag = readTag(data, pos);
            pos += varintSize(data, pos);
            int fieldNumber = tag >>> 3;
            int wireType = tag & 0x07;

            switch (fieldNumber) {
                case 1: // bytes secret
                    if (wireType == 2) {
                        int len = readVarint32(data, pos);
                        pos += varintSize(data, pos);
                        if (len < 0 || pos + len > data.length) {
                            return null;
                        }
                        secret = new byte[len];
                        System.arraycopy(data, pos, secret, 0, len);
                        pos += len;
                    } else {
                        pos = skipField(data, pos, wireType);
                    }
                    break;
                case 2: // string name
                    if (wireType == 2) {
                        int len = readVarint32(data, pos);
                        pos += varintSize(data, pos);
                        if (len < 0 || pos + len > data.length) {
                            return null;
                        }
                        name = new String(data, pos, len, StandardCharsets.UTF_8);
                        pos += len;
                    } else {
                        pos = skipField(data, pos, wireType);
                    }
                    break;
                case 3: // string issuer
                    if (wireType == 2) {
                        int len = readVarint32(data, pos);
                        pos += varintSize(data, pos);
                        if (len < 0 || pos + len > data.length) {
                            return null;
                        }
                        issuer = new String(data, pos, len, StandardCharsets.UTF_8);
                        pos += len;
                    } else {
                        pos = skipField(data, pos, wireType);
                    }
                    break;
                case 4: // int32 algorithm
                    if (wireType == 0) {
                        algorithm = readVarint32(data, pos);
                        pos += varintSize(data, pos);
                    } else {
                        pos = skipField(data, pos, wireType);
                    }
                    break;
                case 5: // int32 digits
                    if (wireType == 0) {
                        digitEnum = readVarint32(data, pos);
                        pos += varintSize(data, pos);
                    } else {
                        pos = skipField(data, pos, wireType);
                    }
                    break;
                case 6: // int32 type
                    if (wireType == 0) {
                        typeEnum = readVarint32(data, pos);
                        pos += varintSize(data, pos);
                    } else {
                        pos = skipField(data, pos, wireType);
                    }
                    break;
                case 7: // int64 counter
                    if (wireType == 0) {
                        counter = readVarint64(data, pos);
                        pos += varintSize(data, pos);
                    } else {
                        pos = skipField(data, pos, wireType);
                    }
                    break;
                default:
                    pos = skipField(data, pos, wireType);
                    break;
            }
        }

        if (secret == null || secret.length == 0) {
            return null;
        }

        OtpAccount acc = new OtpAccount();
        // GA 存储的 secret 是原始字节，需要编码为 Base32
        acc.secret = Base32.encode(secret);

        // 解析 name 字段：格式可能是 "Issuer:account" 或纯 "account"
        if (name != null && !name.isEmpty()) {
            int colonIdx = name.indexOf(':');
            if (colonIdx > 0) {
                if (issuer == null || issuer.isEmpty()) {
                    issuer = name.substring(0, colonIdx).trim();
                }
                acc.account = name.substring(colonIdx + 1).trim();
            } else {
                acc.account = name.trim();
            }
        }
        acc.issuer = issuer;

        // 映射算法枚举
        switch (algorithm) {
            case 2:
                acc.algorithm = "SHA256";
                break;
            case 3:
                acc.algorithm = "SHA512";
                break;
            case 4:
                acc.algorithm = "MD5";
                break;
            default: // 0(unspec) 或 1(SHA1)
                acc.algorithm = "SHA1";
                break;
        }

        // 映射位数枚举
        switch (digitEnum) {
            case 2:
                acc.digits = 8;
                break;
            default: // 0(unspec) 或 1(SIX)
                acc.digits = 6;
                break;
        }

        // 映射类型枚举
        switch (typeEnum) {
            case 1:
                acc.type = OtpAccount.TYPE_HOTP;
                acc.counter = counter;
                break;
            default: // 0(unspec) 或 2(TOTP)
                acc.type = OtpAccount.TYPE_TOTP;
                acc.period = OtpAccount.DEFAULT_PERIOD;
                break;
        }

        return acc;
    }

    // ========== Protobuf Wire Format 基础解码 ==========

    private static int readTag(byte[] data, int pos) {
        return readVarint32(data, pos);
    }

    private static int readVarint32(byte[] data, int pos) {
        int result = 0;
        int shift = 0;
        while (pos < data.length) {
            byte b = data[pos];
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
            pos++;
        }
        return result;
    }

    private static long readVarint64(byte[] data, int pos) {
        long result = 0;
        int shift = 0;
        while (pos < data.length) {
            byte b = data[pos];
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
            pos++;
        }
        return result;
    }

    /** 计算从 pos 开始的 varint 占用字节数。 */
    private static int varintSize(byte[] data, int pos) {
        int size = 0;
        while (pos < data.length) {
            size++;
            if ((data[pos] & 0x80) == 0) {
                break;
            }
            pos++;
        }
        return size;
    }

    /** 跳过一个字段值，返回新的 pos。 */
    private static int skipField(byte[] data, int pos, int wireType) {
        switch (wireType) {
            case 0: // varint
                while (pos < data.length && (data[pos] & 0x80) != 0) {
                    pos++;
                }
                return Math.min(pos + 1, data.length);
            case 1: // 64-bit
                return Math.min(pos + 8, data.length);
            case 2: // length-delimited
                int len = readVarint32(data, pos);
                pos += varintSize(data, pos);
                if (len < 0 || pos + len > data.length) {
                    return data.length;
                }
                return pos + len;
            case 5: // 32-bit
                return Math.min(pos + 4, data.length);
            default:
                return data.length; // 无法识别，跳到末尾
        }
    }
}