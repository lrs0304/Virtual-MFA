/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.data;

import android.util.Base64;
import com.google.gson.Gson;
import com.risonliang.mfa.crypto.CryptoManager;
import com.risonliang.mfa.model.OtpAccount;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 备份文件读写器。
 *
 * 文件结构（JSON）：
 *  {
 *    "v": 1,
 *    "salt": "<base64>",
 *    "data": "<base64( aes-gcm( json_payload ) )>"
 *  }
 *
 *  payload 的 json 形如：
 *  { "items": [{issuer, account, secret, algorithm, digits, period}] }
 */
public final class BackupCodec {

    private static final int VERSION = 1;
    private static final int SALT_LEN = 16;
    private static final Gson GSON = new Gson();

    private BackupCodec() {}

    /** 将账号列表加密导出。 */
    public static void export(List<OtpAccount> accounts, char[] password,
                              OutputStream out) throws Exception {
        Payload payload = new Payload();
        for (OtpAccount a : accounts) {
            payload.items.add(Item.from(a));
        }
        String json = GSON.toJson(payload);
        byte[] salt = CryptoManager.randomSalt(SALT_LEN);
        byte[] enc = CryptoManager.encryptWithPassword(
                json.getBytes(StandardCharsets.UTF_8), password, salt);

        BackupFile bf = new BackupFile();
        bf.v = VERSION;
        bf.salt = Base64.encodeToString(salt, Base64.NO_WRAP);
        bf.data = Base64.encodeToString(enc, Base64.NO_WRAP);
        out.write(GSON.toJson(bf).getBytes(StandardCharsets.UTF_8));
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
        BackupFile bf = GSON.fromJson(
                new String(buf.toByteArray(), StandardCharsets.UTF_8),
                BackupFile.class);
        if (bf == null || bf.v != VERSION || bf.salt == null
                || bf.data == null) {
            throw new IllegalStateException("invalid backup file");
        }
        byte[] salt = Base64.decode(bf.salt, Base64.NO_WRAP);
        byte[] cipher = Base64.decode(bf.data, Base64.NO_WRAP);
        byte[] plain = CryptoManager.decryptWithPassword(
                cipher, password, salt);
        Payload payload = GSON.fromJson(
                new String(plain, StandardCharsets.UTF_8), Payload.class);
        List<OtpAccount> list = new ArrayList<>();
        if (payload != null && payload.items != null) {
            for (Item it : payload.items) {
                list.add(it.toAccount());
            }
        }
        return list;
    }

    private static final class BackupFile {
        int v;
        String salt;
        String data;
    }

    private static final class Payload {
        List<Item> items = new ArrayList<>();
    }

    private static final class Item {
        String issuer;
        String account;
        String secret;
        String algorithm;
        int digits;
        int period;

        static Item from(OtpAccount a) {
            Item it = new Item();
            it.issuer = a.issuer;
            it.account = a.account;
            it.secret = a.secret;
            it.algorithm = a.algorithm;
            it.digits = a.digits;
            it.period = a.period;
            return it;
        }

        OtpAccount toAccount() {
            OtpAccount a = new OtpAccount();
            a.issuer = issuer;
            a.account = account;
            a.secret = secret;
            a.algorithm = algorithm == null ? OtpAccount.DEFAULT_ALGO
                    : algorithm;
            a.digits = digits == 0 ? OtpAccount.DEFAULT_DIGITS : digits;
            a.period = period == 0 ? OtpAccount.DEFAULT_PERIOD : period;
            return a;
        }
    }
}
