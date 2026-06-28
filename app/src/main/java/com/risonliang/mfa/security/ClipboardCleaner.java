/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.security;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;

/**
 * 剪贴板敏感写入助手。
 *
 * 设计目标：
 *  1. 写入时打"敏感"标记（{@link ClipDescription#EXTRA_IS_SENSITIVE}），
 *     使 Android 13+ 系统通知不回显验证码内容；并在低版本上加一个
 *     私有 boolean extra 作为我们识别自家写入的指纹；
 *  2. 延时（默认 30s）后自动清空——但只清"仍是我们写入的内容"，
 *     如果期间用户已复制了别的东西，则不动用户剪贴板；
 *  3. 进程级单例，统一持有一个 main looper Handler，避免多次复制时
 *     旧任务把新写入的也一并清掉（每次写入都会先 cancel 旧任务）。
 *
 * 安全说明：写入剪贴板的瞬间数据在系统层面就属于"用户可粘贴的明文"，
 * 本类只能尽力降低残留时间和被通知 / Now in Tap 类系统组件抓走的风险，
 * 不能保证剪贴板上下游（IME、其它 App）不缓存明文。
 */
public final class ClipboardCleaner {

    /** 我们写入剪贴板时附带的隐私指纹 key。 */
    private static final String kExtraOwner = "com.risonliang.mfa.clip.owner";
    /** 默认清理延时（毫秒）：30 秒，与 OTP 30s 周期一致。 */
    public static final long kDefaultClearDelayMs = 30_000L;

    /** Android 13+ 公共敏感标记 key（API 33 才有常量，低版本用字符串值兼容）。 */
    private static final String kExtraIsSensitive =
            "android.content.extra.IS_SENSITIVE";

    private static volatile ClipboardCleaner sInstance;

    private final Context appCtx_;
    private final Handler mainHandler_ = new Handler(Looper.getMainLooper());
    private Runnable pending_;

    private ClipboardCleaner(Context appCtx) {
        appCtx_ = appCtx;
    }

    public static ClipboardCleaner get(Context ctx) {
        if (sInstance == null) {
            synchronized (ClipboardCleaner.class) {
                if (sInstance == null) {
                    sInstance = new ClipboardCleaner(
                            ctx.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    /**
     * 写入验证码到剪贴板并安排自动清理。
     *
     * @param label    剪贴板标签（用户在系统剪贴板 UI 里看到的）
     * @param code     验证码字符串
     * @param delayMs  清理延时；&lt;=0 表示不自动清理
     */
    public void copyOtp(String label, String code, long delayMs) {
        ClipboardManager cm = (ClipboardManager) appCtx_.getSystemService(
                Context.CLIPBOARD_SERVICE);
        if (cm == null || code == null) {
            return;
        }
        ClipData clip = ClipData.newPlainText(label, code);
        ClipDescription desc = clip.getDescription();
        // Android 13+：系统会自动隐藏剪贴板提示中的内容回显。
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean(kExtraIsSensitive, true);
        // 自家写入指纹，用于自动清理时的归属校验。
        extras.putBoolean(kExtraOwner, true);
        desc.setExtras(extras);
        try {
            cm.setPrimaryClip(clip);
        } catch (Exception ignore) {
            // 个别 ROM 在锁屏 / 多用户切换瞬间会拒绝写剪贴板，静默放弃。
            return;
        }

        cancelPendingClear();
        if (delayMs <= 0) {
            return;
        }
        pending_ = () -> clearIfStillOurs(cm);
        mainHandler_.postDelayed(pending_, delayMs);
    }

    /**
     * 立即取消尚未触发的延时清理任务。Activity onDestroy 时可以调用，
     * 但通常无需手动调；多次 {@link #copyOtp} 之间也会自动 cancel 旧任务。
     */
    public void cancelPendingClear() {
        if (pending_ != null) {
            mainHandler_.removeCallbacks(pending_);
            pending_ = null;
        }
    }

    /**
     * 仅当当前剪贴板仍是我们写入的内容时才清空，避免误删用户后续复制。
     */
    private void clearIfStillOurs(ClipboardManager cm) {
        try {
            ClipData current = cm.getPrimaryClip();
            if (current == null || current.getItemCount() == 0) {
                return;
            }
            ClipDescription desc = current.getDescription();
            if (desc == null) {
                return;
            }
            PersistableBundle extras = desc.getExtras();
            if (extras == null || !extras.getBoolean(kExtraOwner, false)) {
                // 用户已经复制了别的东西，不能动。
                return;
            }
            // 用空文本覆盖；部分系统 clearPrimaryClip 不可用，统一用覆盖法。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    cm.clearPrimaryClip();
                    return;
                } catch (Exception ignore) {
                    // 落到下面的覆盖兜底
                }
            }
            ClipData empty = ClipData.newPlainText("", "");
            cm.setPrimaryClip(empty);
        } catch (Exception ignore) {
            // 任何剪贴板异常都不影响主流程。
        } finally {
            pending_ = null;
        }
    }
}
