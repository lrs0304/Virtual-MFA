/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa;

import android.app.Application;

import com.risonliang.mfa.ui.NightModeManager;

/**
 * 应用入口。
 *
 * <p>当前仅负责一件事：在 {@link #onCreate()} 中尽早把持久化的「深色模式」
 * 偏好推给 AppCompat，确保后续 Activity 的 {@code setContentView} 走到
 * 正确的资源限定符（values/ 或 values-night/），避免出现"启动闪一下亮色"。
 *
 * <p>没有别的副作用：不做单例容器、不做埋点上报、不做模块预热——保持
 * Application 的轻量职责，符合工程偏好。
 */
public final class MfaApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // 默认 follow-system，用户可在 SettingsActivity 关闭。这里不依赖
        // SettingsActivity 已经初始化，只读裸 SharedPreferences。
        NightModeManager.get(this).apply();
    }
}
