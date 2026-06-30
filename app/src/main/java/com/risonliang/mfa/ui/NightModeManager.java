/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.ui;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * 深色模式偏好与运行时切换。
 *
 * <p>暴露一个布尔型偏好「跟随系统深色模式」：
 * <ul>
 *   <li>{@code true}（默认）→ {@link AppCompatDelegate#MODE_NIGHT_FOLLOW_SYSTEM}：
 *       由系统设置决定亮/暗，符合 Android 13+ 用户的一致预期；</li>
 *   <li>{@code false} → {@link AppCompatDelegate#MODE_NIGHT_NO}：强制亮色，
 *       便于在系统全局深色但希望本应用保持原配色的用户。</li>
 * </ul>
 *
 * <p>设计要点：
 * <ol>
 *   <li>偏好独立写在 {@code mfa_night_prefs}，与 {@link UiPreferences} 解耦。
 *       原因：{@link AppCompatDelegate#setDefaultNightMode} 必须在
 *       Application.onCreate 阶段尽早调用，那时构造 UiPreferences 会拉起
 *       完整偏好集合，开销不必要；独立 prefs 保持启动时只读必要的一个键。</li>
 *   <li>切换后 AppCompat 会自动调用 {@link android.app.Activity#recreate()}
 *       重建所有可见 Activity，调用方不需要主动 recreate。</li>
 *   <li>不提供 {@code MODE_NIGHT_YES}（强制深色）。需求是"跟随系统、可关闭"，
 *       双态足够；新增第三态会让开关 UI 退化为单选/下拉，超出当前需求范围。</li>
 * </ol>
 */
public final class NightModeManager {

    private static final String kPrefsName = "mfa_night_prefs";
    private static final String kKeyFollowSystem = "follow_system";

    private static volatile NightModeManager sInstance;

    private final SharedPreferences prefs_;

    /**
     * 私有构造，禁用拷贝与赋值，确保进程内只有一个偏好读写实例。
     */
    private NightModeManager(Context appCtx) {
        prefs_ = appCtx.getSharedPreferences(kPrefsName, Context.MODE_PRIVATE);
    }

    public static NightModeManager get(Context ctx) {
        if (sInstance == null) {
            synchronized (NightModeManager.class) {
                if (sInstance == null) {
                    sInstance = new NightModeManager(ctx.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    /**
     * 是否启用「跟随系统深色模式」。默认 true。
     */
    public boolean isFollowSystem() {
        return prefs_.getBoolean(kKeyFollowSystem, true);
    }

    /**
     * 切换跟随系统开关。
     *
     * <p>同步：1）落地偏好；2）调用 {@link #apply()} 把新策略推给
     * AppCompat。AppCompat 会在适当时机重建当前可见的 Activity。
     */
    public void setFollowSystem(boolean enabled) {
        prefs_.edit().putBoolean(kKeyFollowSystem, enabled).apply();
        apply();
    }

    /**
     * 按当前偏好应用一次 night mode 到 AppCompat 全局。
     *
     * <p>调用时机：
     * <ul>
     *   <li>{@code Application.onCreate()}：进程启动后的第一次应用，必须早于
     *       任何 AppCompatActivity 的 setContentView；</li>
     *   <li>{@link #setFollowSystem(boolean)}：偏好变更时的二次应用。</li>
     * </ul>
     */
    public void apply() {
        int mode = isFollowSystem()
                ? AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                : AppCompatDelegate.MODE_NIGHT_NO;
        AppCompatDelegate.setDefaultNightMode(mode);
    }
}
