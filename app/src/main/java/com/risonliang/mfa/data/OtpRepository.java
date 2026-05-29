/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Base64;
import com.risonliang.mfa.crypto.CryptoManager;
import com.risonliang.mfa.model.OtpAccount;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 SQLiteOpenHelper 的本地数据访问。
 * secret 字段强制加密存储（AES-256-GCM + Keystore），不允许明文。
 */
public final class OtpRepository {

    private static final String DB_NAME = "mfa.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "otp_account";

    private static final String COL_ID = "id";
    private static final String COL_ISSUER = "issuer";
    private static final String COL_ACCOUNT = "account";
    private static final String COL_SECRET_ENC = "secret_enc";
    private static final String COL_ALGO = "algorithm";
    private static final String COL_DIGITS = "digits";
    private static final String COL_PERIOD = "period";
    private static final String COL_CREATED = "created_at";
    private static final String COL_SORT = "sort_order";

    private static volatile OtpRepository sInstance;
    private final DbHelper helper_;

    private OtpRepository(Context appCtx) {
        helper_ = new DbHelper(appCtx);
    }

    public static OtpRepository get(Context ctx) {
        if (sInstance == null) {
            synchronized (OtpRepository.class) {
                if (sInstance == null) {
                    sInstance = new OtpRepository(ctx.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    /** 插入账号；返回新 id。 */
    public long insert(OtpAccount acc) throws Exception {
        ContentValues cv = toCv(acc);
        SQLiteDatabase db = helper_.getWritableDatabase();
        return db.insert(TABLE, null, cv);
    }

    /** 更新账号。 */
    public int update(OtpAccount acc) throws Exception {
        ContentValues cv = toCv(acc);
        SQLiteDatabase db = helper_.getWritableDatabase();
        return db.update(TABLE, cv, COL_ID + "=?",
                new String[]{String.valueOf(acc.id)});
    }

    /** 删除账号。 */
    public int delete(long id) {
        SQLiteDatabase db = helper_.getWritableDatabase();
        return db.delete(TABLE, COL_ID + "=?",
                new String[]{String.valueOf(id)});
    }

    /** 列出所有账号（已解密）。 */
    public List<OtpAccount> listAll() throws Exception {
        List<OtpAccount> list = new ArrayList<>();
        SQLiteDatabase db = helper_.getReadableDatabase();
        try (Cursor c = db.query(TABLE, null, null, null, null, null,
                COL_SORT + " ASC, " + COL_ID + " ASC")) {
            while (c.moveToNext()) {
                list.add(fromCursor(c));
            }
        }
        return list;
    }

    private ContentValues toCv(OtpAccount acc) throws Exception {
        ContentValues cv = new ContentValues();
        cv.put(COL_ISSUER, acc.issuer == null ? "" : acc.issuer);
        cv.put(COL_ACCOUNT, acc.account == null ? "" : acc.account);
        byte[] enc = CryptoManager.encrypt(
                acc.secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        cv.put(COL_SECRET_ENC, Base64.encodeToString(enc, Base64.NO_WRAP));
        cv.put(COL_ALGO, acc.algorithm);
        cv.put(COL_DIGITS, acc.digits);
        cv.put(COL_PERIOD, acc.period);
        cv.put(COL_CREATED,
                acc.createdAt == 0 ? System.currentTimeMillis() : acc.createdAt);
        cv.put(COL_SORT, acc.sortOrder);
        return cv;
    }

    private OtpAccount fromCursor(Cursor c) throws Exception {
        OtpAccount a = new OtpAccount();
        a.id = c.getLong(c.getColumnIndexOrThrow(COL_ID));
        a.issuer = c.getString(c.getColumnIndexOrThrow(COL_ISSUER));
        a.account = c.getString(c.getColumnIndexOrThrow(COL_ACCOUNT));
        String encStr = c.getString(c.getColumnIndexOrThrow(COL_SECRET_ENC));
        byte[] enc = Base64.decode(encStr, Base64.NO_WRAP);
        a.secret = new String(CryptoManager.decrypt(enc),
                java.nio.charset.StandardCharsets.UTF_8);
        a.algorithm = c.getString(c.getColumnIndexOrThrow(COL_ALGO));
        a.digits = c.getInt(c.getColumnIndexOrThrow(COL_DIGITS));
        a.period = c.getInt(c.getColumnIndexOrThrow(COL_PERIOD));
        a.createdAt = c.getLong(c.getColumnIndexOrThrow(COL_CREATED));
        a.sortOrder = c.getInt(c.getColumnIndexOrThrow(COL_SORT));
        return a;
    }

    private static final class DbHelper extends SQLiteOpenHelper {
        DbHelper(Context ctx) {
            super(ctx, DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE + " ("
                    + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + COL_ISSUER + " TEXT, "
                    + COL_ACCOUNT + " TEXT, "
                    + COL_SECRET_ENC + " TEXT NOT NULL, "
                    + COL_ALGO + " TEXT, "
                    + COL_DIGITS + " INTEGER, "
                    + COL_PERIOD + " INTEGER, "
                    + COL_CREATED + " INTEGER, "
                    + COL_SORT + " INTEGER DEFAULT 0)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // 预留升级位
        }
    }
}
