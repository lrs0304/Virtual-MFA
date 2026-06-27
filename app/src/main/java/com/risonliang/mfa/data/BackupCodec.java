/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.data;

import android.util.Base64;
import com.risonliang.mfa.crypto.CryptoManager;
import com.risonliang.mfa.model.OtpAccount;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 备份文件读写器。
 *
 * 文件结构（JSON）：
 *  {
 *    "v": 2,
 *    "salt": "<base64>",
 *    "data": "<base64( aes-gcm( json_payload ) )>"
 *  }
 *
 *  payload 的 json 形如：
 *  { "items": [{issuer, account, secret, type, algorithm, digits, period, counter}] }
 *
 * 实现说明：使用 Android 系统自带的 org.json 而非 Gson，避免引入 ~250KB
 * 第三方库；序列化字段名与旧版 Gson 版本完全一致，备份文件双向兼容。
 */
public final class BackupCodec {

    private static final int VERSION = 2;
    private static final int SALT_LEN = 16;

    private BackupCodec() {}

    /** 将账号列表加密导出。 */
    public static void export(List<OtpAccount> accounts, char[] password,
                              OutputStream out) throws Exception {
        JSONArray items = new JSONArray();
        for (OtpAccount a : accounts) {
            items.put(toItemJson(a));
        }
        JSONObject payload = new JSONObject();
        payload.put("items", items);
        String json = payload.toString();

        byte[] salt = CryptoManager.randomSalt(SALT_LEN);
        byte[] enc = CryptoManager.encryptWithPassword(
                json.getBytes(StandardCharsets.UTF_8), password, salt);

        JSONObject bf = new JSONObject();
        bf.put("v", VERSION);
        bf.put("salt", Base64.encodeToString(salt, Base64.NO_WRAP));
        bf.put("data", Base64.encodeToString(enc, Base64.NO_WRAP));
        out.write(bf.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** 解密导入备份文件。 */
    public static List<OtpAccount> importFrom(InputStream in, char[] password)
            throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = in.read(tmp)) > 0) {
            buf.write(tmp, 0, n);
        }
        JSONObject bf = new JSONObject(
                new String(buf.toByteArray(), StandardCharsets.UTF_8));
        String saltB64 = bf.optString("salt", null);
        String dataB64 = bf.optString("data", null);
        if (saltB64 == null || dataB64 == null) {
            throw new IllegalStateException("invalid backup file");
        }
        int v = bf.optInt("v", 0);
        // 兼容 v1 和 v2 格式
        if (v != 1 && v != 2) {
            throw new IllegalStateException("unsupported backup version: " + v);
        }
        byte[] salt = Base64.decode(saltB64, Base64.NO_WRAP);
        byte[] cipher = Base64.decode(dataB64, Base64.NO_WRAP);
        byte[] plain = CryptoManager.decryptWithPassword(
                cipher, password, salt);
        JSONObject payload = new JSONObject(
                new String(plain, StandardCharsets.UTF_8));
        JSONArray items = payload.optJSONArray("items");
        List<OtpAccount> list = new ArrayList<>();
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it != null) {
                    list.add(fromItemJson(it));
                }
            }
        }
        return list;
    }

    /** 将单个账号转为 JSON Item，字段命名与旧版 Gson 输出严格一致。 */
    private static JSONObject toItemJson(OtpAccount a) throws org.json.JSONException {
        JSONObject it = new JSONObject();
        if (a.issuer != null) {
            it.put("issuer", a.issuer);
        }
        if (a.account != null) {
            it.put("account", a.account);
        }
        if (a.secret != null) {
            it.put("secret", a.secret);
        }
        if (a.type != null) {
            it.put("type", a.type);
        }
        if (a.algorithm != null) {
            it.put("algorithm", a.algorithm);
        }
        it.put("digits", a.digits);
        it.put("period", a.period);
        it.put("counter", a.counter);
        return it;
    }

    /** 从 JSON Item 还原账号；缺省字段使用 OtpAccount 默认值。 */
    private static OtpAccount fromItemJson(JSONObject it) {
        OtpAccount a = new OtpAccount();
        a.issuer = it.optString("issuer", null);
        a.account = it.optString("account", null);
        a.secret = it.optString("secret", null);
        String type = it.optString("type", null);
        a.type = (type == null || type.isEmpty())
                ? OtpAccount.TYPE_TOTP : type;
        String algo = it.optString("algorithm", null);
        a.algorithm = (algo == null) ? OtpAccount.DEFAULT_ALGO : algo;
        int digits = it.optInt("digits", 0);
        a.digits = digits == 0 ? OtpAccount.DEFAULT_DIGITS : digits;
        int period = it.optInt("period", 0);
        a.period = period == 0 ? OtpAccount.DEFAULT_PERIOD : period;
        a.counter = it.optLong("counter", 0L);
        return a;
    }
}
