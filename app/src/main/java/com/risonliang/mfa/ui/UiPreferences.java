/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.ui;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * UI 偏好集中读写。
 *
 * 仅保存"对安全无影响"的呈现偏好，例如：
 *  - 是否默认隐藏验证码（点按显形）
 *  - 是否在剩余 ≤5s 时预显示下一码
 *  - 自动锁定宽限期秒数（与 AppLockManager 协作）
 *
 * 一律走普通 SharedPreferences；不引入 EncryptedSharedPreferences，
 * 因为这里没有可逆秘密。
 */
public final class UiPreferences {

    private static final String kPrefsName = "mfa_ui_prefs";
    private static final String kKeyHideCodes = "hide_codes";
    private static final String kKeyShowNextCode = "show_next_code";
    private static final String kKeyAutoLockGraceSec = "auto_lock_grace_sec";
    private static final String kKeyShowInvalidQrPreview = "show_invalid_qr_preview";

    private static volatile UiPreferences sInstance;

    private final SharedPreferences prefs_;

    public static UiPreferences get(Context ctx) {
        if (sInstance == null) {
            synchronized (UiPreferences.class) {
                if (sInstance == null) {
                    sInstance = new UiPreferences(ctx.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private UiPreferences(Context appCtx) {
        prefs_ = appCtx.getSharedPreferences(kPrefsName, Context.MODE_PRIVATE);
    }

    /**
     * 是否默认隐藏验证码。开启后列表项默认显示遮罩，点按账号才短暂显形。
     * 默认 {@code false}：保持老用户的肌肉记忆。
     */
    public boolean isHideCodes() {
        return prefs_.getBoolean(kKeyHideCodes, false);
    }

    public void setHideCodes(boolean enabled) {
        prefs_.edit().putBoolean(kKeyHideCodes, enabled).apply();
    }

    /**
     * 是否在剩余 ≤5s 时同时显示"下一码"，避免临近过期复制后立即失效。
     * 默认 {@code true}：体验提升明显，且不暴露任何额外秘密。
     */
    public boolean isShowNextCode() {
        return prefs_.getBoolean(kKeyShowNextCode, true);
    }

    public void setShowNextCode(boolean enabled) {
        prefs_.edit().putBoolean(kKeyShowNextCode, enabled).apply();
    }

    /**
     * 后台返回前台的自动锁定宽限期（秒）。
     *  - 0      ：原有行为，立即锁定（默认值，安全最严）
     *  - 15/30/60/300：分别为常用宽限选项
     * AppLockManager 在 shouldRequireUnlock 时读取。
     */
    public int getAutoLockGraceSec() {
        return prefs_.getInt(kKeyAutoLockGraceSec, 0);
    }

    public void setAutoLockGraceSec(int seconds) {
        prefs_.edit().putInt(kKeyAutoLockGraceSec, Math.max(0, seconds)).apply();
    }

    /**
     * 扫码 / 相册导入识别到二维码但内容不是合法 MFA 配置时，是否跳转到
     * {@link QrContentPreviewActivity} 展示原文 + 一键复制。
     *
     * <p>默认 {@code true}：让用户能看到原文判断是否选错图、是否为非
     * otpauth 的私有协议；如果只想要"识别失败一笔带过"，可在设置中关闭，
     * 关闭后两条链路都仅以 Toast 提示，不再跳页。
     */
    public boolean isShowInvalidQrPreview() {
        return prefs_.getBoolean(kKeyShowInvalidQrPreview, true);
    }

    public void setShowInvalidQrPreview(boolean enabled) {
        prefs_.edit().putBoolean(kKeyShowInvalidQrPreview, enabled).apply();
    }
}
