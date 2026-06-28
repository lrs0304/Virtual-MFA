/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.data;

import android.net.Uri;
import android.util.Log;
import com.risonliang.mfa.crypto.Base32;
import com.risonliang.mfa.model.OtpAccount;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * otpauth URI 解析器。
 * 支持 TOTP 和 HOTP 两种类型。
 * 标准格式：otpauth://totp/Issuer:account?secret=XXX&issuer=Issuer&algorithm=SHA1&digits=6&period=30
 *          otpauth://hotp/Issuer:account?secret=XXX&issuer=Issuer&counter=0
 */
public final class OtpUriParser {

    private static final String kLogTag = "MFA-Scan";

    private OtpUriParser() {}

    public static OtpAccount parse(String uriStr) {
        if (uriStr == null) {
            Log.w(kLogTag, "OtpUriParser: input null");
            return null;
        }
        Uri uri = Uri.parse(uriStr.trim());
        if (uri.getScheme() == null
                || !"otpauth".equalsIgnoreCase(uri.getScheme())) {
            Log.w(kLogTag, "OtpUriParser: bad scheme=" + uri.getScheme());
            return null;
        }
        String type = uri.getHost();
        if (type == null) {
            Log.w(kLogTag, "OtpUriParser: missing host");
            return null;
        }
        boolean isTotp = "totp".equalsIgnoreCase(type);
        boolean isHotp = "hotp".equalsIgnoreCase(type);
        if (!isTotp && !isHotp) {
            Log.w(kLogTag, "OtpUriParser: unsupported host=" + type);
            return null;
        }

        OtpAccount acc = new OtpAccount();
        acc.type = isHotp ? OtpAccount.TYPE_HOTP : OtpAccount.TYPE_TOTP;
        // path 形如 /Issuer:account 或 /account
        String path = uri.getPath();
        if (path != null && path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path != null && !path.isEmpty()) {
            try {
                String decoded = URLDecoder.decode(path, "UTF-8");
                int idx = decoded.indexOf(':');
                if (idx > 0) {
                    acc.issuer = decoded.substring(0, idx).trim();
                    acc.account = decoded.substring(idx + 1).trim();
                } else {
                    acc.account = decoded;
                }
            } catch (Exception ignore) {
                acc.account = path;
            }
        }

        Map<String, String> params = parseQuery(uri);
        String secret = params.get("secret");
        if (secret == null || !Base32.isValid(secret)) {
            Log.w(kLogTag, "OtpUriParser: invalid secret, present="
                    + (secret != null) + ", len="
                    + (secret == null ? 0 : secret.length()));
            return null;
        }
        acc.secret = secret;
        if (params.containsKey("issuer")
                && (acc.issuer == null || acc.issuer.isEmpty())) {
            acc.issuer = params.get("issuer");
        }
        if (params.containsKey("algorithm")) {
            acc.algorithm = params.get("algorithm").toUpperCase();
        }
        if (params.containsKey("digits")) {
            try {
                acc.digits = Integer.parseInt(params.get("digits"));
            } catch (NumberFormatException ignore) {}
        }
        if (params.containsKey("period")) {
            try {
                acc.period = Integer.parseInt(params.get("period"));
            } catch (NumberFormatException ignore) {}
        }
        if (params.containsKey("counter")) {
            try {
                acc.counter = Long.parseLong(params.get("counter"));
            } catch (NumberFormatException ignore) {}
        }
        return acc;
    }

    private static Map<String, String> parseQuery(Uri uri) {
        Map<String, String> map = new HashMap<>();
        String q = uri.getEncodedQuery();
        if (q == null) {
            return map;
        }
        for (String kv : q.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0) {
                try {
                    String k = URLDecoder.decode(kv.substring(0, eq),
                            StandardCharsets.UTF_8.name());
                    String v = URLDecoder.decode(kv.substring(eq + 1),
                            StandardCharsets.UTF_8.name());
                    map.put(k, v);
                } catch (Exception ignore) {}
            }
        }
        return map;
    }
}
