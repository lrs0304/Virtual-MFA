/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.risonliang.mfa.BuildConfig;
import com.risonliang.mfa.security.AppLockManager;

/**
 * 安全 Activity 基类。所有承载敏感数据/操作的 Activity 必须继承此类。
 *
 * 提供以下能力：
 *  1. {@link WindowManager.LayoutParams#FLAG_SECURE}：禁止截屏、屏幕录制、
 *     最近任务缩略图捕获。
 *  2. 解锁路由：onStart 时若需要重新解锁则跳转 {@link LockActivity}。
 *  3. onPause/onStop 时通知 {@link AppLockManager} 进入后台计时。
 *
 * 注意：LockActivity 自身不应继承此类，避免无限重定向。
 */
public abstract class BaseSecureActivity extends AppCompatActivity {

    /**
     * 子类可覆盖以禁用 FLAG_SECURE（例如某些演示场景）。
     *
     * 默认策略：release 构建开启、debug 构建关闭。这样可以在开发调试时
     * 自由使用截屏 / Layout Inspector / 录屏，而正式包仍然禁止外部抓屏。
     * 子类如有特殊需求可直接覆盖此方法返回固定值。
     */
    protected boolean isFlagSecureEnabled() {
        return !BuildConfig.DEBUG;
    }

    /**
     * 子类可覆盖以禁用启动锁定（例如 LockActivity 自身或引导页）。
     * 默认开启。
     */
    protected boolean isLockEnforced() {
        return true;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        if (isFlagSecureEnabled()) {
            getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE);
        }
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!isLockEnforced()) {
            return;
        }
        AppLockManager lock = AppLockManager.get(this);
        // 由 manager 维护应用级前台计数：仅在 0->1 翻转时返回 true（真正回到前台）。
        if (lock.onActivityStarted()) {
            Intent it = new Intent(this, LockActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(it);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isLockEnforced()) {
            AppLockManager.get(this).onActivityStopped();
        }
    }
}
