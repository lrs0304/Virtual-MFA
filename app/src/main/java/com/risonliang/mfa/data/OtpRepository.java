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
 *
 * 注意：本类为进程级单例，内部持有 applicationContext 引用。
 * applicationContext 的生命周期等于进程生命周期，不会导致 Activity 泄漏。
 */
public final class OtpRepository {

    private static final String DB_NAME = "mfa.db";
    private static final int DB_VERSION = 2;
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
    private static final String COL_TYPE = "type";
    private static final String COL_COUNTER = "counter";

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

    /**
     * 批量更新 sortOrder：按 ids 数组顺序写入 0,1,2,...，作为列表的展示次序。
     *
     * 实现要点：
     *  1. 走单事务，要么全部成功要么全部回滚，避免部分写入留下中间态；
     *  2. 仅更新 sort_order 一列，不触碰 secret_enc，避免无谓地走 Keystore；
     *  3. 调用方应保证 ids 来自同一份当前列表的快照，且 size ≤ 1000（极限情况下
     *     SQLiteStatement 复用即可，无需 IN 子句）。
     */
    public void updateSortOrder(long[] orderedIds) {
        if (orderedIds == null || orderedIds.length == 0) {
            return;
        }
        SQLiteDatabase db = helper_.getWritableDatabase();
        db.beginTransaction();
        try {
            try (android.database.sqlite.SQLiteStatement stmt =
                    db.compileStatement("UPDATE " + TABLE + " SET "
                            + COL_SORT + "=? WHERE " + COL_ID + "=?")) {
                for (int i = 0; i < orderedIds.length; i++) {
                    stmt.clearBindings();
                    stmt.bindLong(1, i);
                    stmt.bindLong(2, orderedIds[i]);
                    stmt.executeUpdateDelete();
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** HOTP 专用：递增计数器并返回新值。 */
    public long incrementCounter(long id, long currentCounter) {
        long next = currentCounter + 1;
        android.content.ContentValues cv = new android.content.ContentValues();
        cv.put(COL_COUNTER, next);
        SQLiteDatabase db = helper_.getWritableDatabase();
        db.update(TABLE, cv, COL_ID + "=?",
                new String[]{String.valueOf(id)});
        return next;
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

    /**
     * 判断是否已存在相同账号（按 issuer + account + secret 组合判重）。
     * 用于导入时去重，避免重复导入产生完全重复的条目。
     */
    public boolean exists(OtpAccount acc) throws Exception {
        List<OtpAccount> all = listAll();
        for (OtpAccount existing : all) {
            if (equalsIgnoreNull(existing.issuer, acc.issuer)
                    && equalsIgnoreNull(existing.account, acc.account)
                    && equalsIgnoreNull(existing.secret, acc.secret)) {
                return true;
            }
        }
        return false;
    }

    private static boolean equalsIgnoreNull(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
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
        cv.put(COL_TYPE, acc.type == null ? OtpAccount.TYPE_TOTP : acc.type);
        cv.put(COL_COUNTER, acc.counter);
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
        int typeIdx = c.getColumnIndex(COL_TYPE);
        a.type = (typeIdx >= 0 && !c.isNull(typeIdx))
                ? c.getString(typeIdx) : OtpAccount.TYPE_TOTP;
        int counterIdx = c.getColumnIndex(COL_COUNTER);
        a.counter = (counterIdx >= 0 && !c.isNull(counterIdx))
                ? c.getLong(counterIdx) : 0;
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
                    + COL_TYPE + " TEXT DEFAULT 'totp', "
                    + COL_COUNTER + " INTEGER DEFAULT 0, "
                    + COL_SORT + " INTEGER DEFAULT 0)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE " + TABLE
                        + " ADD COLUMN " + COL_TYPE + " TEXT DEFAULT 'totp'");
                db.execSQL("ALTER TABLE " + TABLE
                        + " ADD COLUMN " + COL_COUNTER + " INTEGER DEFAULT 0");
            }
        }
    }
}
