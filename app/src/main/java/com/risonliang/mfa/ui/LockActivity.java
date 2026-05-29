/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import com.risonliang.mfa.R;
import com.risonliang.mfa.security.AppLockManager;
import java.util.concurrent.Executor;

/**
 * 解锁 / 设置 / 修改 PIN 的入口 Activity。
 *
 * 三种模式（按 EXTRA_MODE 决定，默认按 PIN 是否已设置自动选择）：
 *  - {@link #MODE_SETUP}：首次设置 PIN，需输入两次相同的 4-12 位数字。
 *  - {@link #MODE_UNLOCK}：解锁；输入正确即标记会话已解锁并进入主界面。
 *  - {@link #MODE_CHANGE}：修改 PIN；先校验旧 PIN，再分两次输入新 PIN。
 *
 * 安全考虑：
 *  - 启用 FLAG_SECURE 防止截屏 / 录屏 / Recents 缩略图泄露 PIN 输入界面。
 *  - 自身不继承 BaseSecureActivity，避免 onStart 时再次跳回自己造成循环。
 *  - 解锁前禁止返回桌面以外的逃逸：返回键 == 退出 App；
 *    修改模式则按返回键 = 取消并回到调用者。
 */
public class LockActivity extends AppCompatActivity {

    /** Intent extra：模式选择。可选值见 MODE_* 常量；缺省时按状态自动判断。 */
    public static final String EXTRA_MODE = "lock_mode";
    public static final int MODE_AUTO = 0;
    public static final int MODE_SETUP = 1;
    public static final int MODE_UNLOCK = 2;
    public static final int MODE_CHANGE = 3;

    private AppLockManager lock_;
    private int mode_ = MODE_AUTO;
    /** 修改模式的内部步骤：0=校验旧 PIN，1=输入新 PIN（含确认）。 */
    private int changeStep_ = 0;

    private EditText etPin_;
    private EditText etPinConfirm_;
    private TextView tvTitle_;
    private TextView tvSubtitle_;
    private TextView tvError_;
    private Button btnConfirm_;
    private Button btnBiometric_;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 防截屏 / 录屏 / Recents 缩略图。
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lock);

        lock_ = AppLockManager.get(this);
        mode_ = resolveMode(getIntent().getIntExtra(EXTRA_MODE, MODE_AUTO));

        etPin_ = findViewById(R.id.et_pin);
        etPinConfirm_ = findViewById(R.id.et_pin_confirm);
        tvTitle_ = findViewById(R.id.tv_lock_title);
        tvSubtitle_ = findViewById(R.id.tv_lock_subtitle);
        tvError_ = findViewById(R.id.tv_error);
        btnConfirm_ = findViewById(R.id.btn_confirm);
        btnBiometric_ = findViewById(R.id.btn_biometric);

        applyModeUi();

        btnConfirm_.setOnClickListener(v -> onConfirm());
        btnBiometric_.setOnClickListener(v -> showBiometricPrompt());
    }

    /** 根据外部传入的 mode 与当前 PIN 配置态，校正成最终模式。 */
    private int resolveMode(int requested) {
        if (requested == MODE_CHANGE && lock_.isPinConfigured()) {
            return MODE_CHANGE;
        }
        if (requested == MODE_SETUP && !lock_.isPinConfigured()) {
            return MODE_SETUP;
        }
        if (requested == MODE_UNLOCK && lock_.isPinConfigured()) {
            return MODE_UNLOCK;
        }
        // AUTO 或参数与状态不匹配时按状态自动选择。
        return lock_.isPinConfigured() ? MODE_UNLOCK : MODE_SETUP;
    }

    /** 根据当前模式（及修改模式的子步骤）刷新文案与控件可见性。 */
    private void applyModeUi() {
        etPin_.setText("");
        etPinConfirm_.setText("");
        tvError_.setVisibility(View.INVISIBLE);

        switch (mode_) {
            case MODE_SETUP:
                tvTitle_.setText(R.string.lock_setup_title);
                tvSubtitle_.setText(R.string.lock_setup_subtitle);
                etPinConfirm_.setVisibility(View.VISIBLE);
                btnBiometric_.setVisibility(View.GONE);
                break;
            case MODE_CHANGE:
                if (changeStep_ == 0) {
                    tvTitle_.setText(R.string.lock_change_title);
                    tvSubtitle_.setText(R.string.lock_change_old_subtitle);
                    etPinConfirm_.setVisibility(View.GONE);
                } else {
                    tvTitle_.setText(R.string.lock_change_title);
                    tvSubtitle_.setText(R.string.lock_change_new_subtitle);
                    etPinConfirm_.setVisibility(View.VISIBLE);
                }
                btnBiometric_.setVisibility(View.GONE);
                break;
            case MODE_UNLOCK:
            default:
                tvTitle_.setText(R.string.lock_unlock_title);
                tvSubtitle_.setText(R.string.lock_pin_hint);
                etPinConfirm_.setVisibility(View.GONE);
                updateBiometricButton();
                break;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 仅"解锁"模式且用户开启时，自动唤起一次生物识别。
        if (mode_ == MODE_UNLOCK
                && canUseBiometric() && lock_.isBiometricEnabled()) {
            etPin_.post(this::showBiometricPrompt);
        }
    }

    /**
     * 解锁模式按返回 = 退到桌面；修改模式按返回 = 取消修改并回到调用者。
     * 设置模式（首次启动）也按退到桌面处理，强制完成首次设置。
     */
    @Override
    public void onBackPressed() {
        if (mode_ == MODE_CHANGE) {
            setResult(RESULT_CANCELED);
            super.onBackPressed();
            return;
        }
        moveTaskToBack(true);
    }

    private void onConfirm() {
        String pin = etPin_.getText() == null
                ? "" : etPin_.getText().toString();
        if (TextUtils.isEmpty(pin) || pin.length() < 4) {
            showError(getString(R.string.lock_pin_too_short));
            return;
        }
        switch (mode_) {
            case MODE_SETUP:
                handleSetup(pin);
                break;
            case MODE_UNLOCK:
                handleUnlock(pin);
                break;
            case MODE_CHANGE:
                handleChange(pin);
                break;
            default:
                break;
        }
    }

    private void handleSetup(String pin) {
        String confirm = etPinConfirm_.getText() == null
                ? "" : etPinConfirm_.getText().toString();
        if (!pin.equals(confirm)) {
            showError(getString(R.string.lock_pin_mismatch));
            return;
        }
        try {
            lock_.setPin(pin.toCharArray());
        } catch (Exception e) {
            showError(e.getMessage() == null ? "error" : e.getMessage());
            return;
        }
        Toast.makeText(this, R.string.lock_setup_done,
                Toast.LENGTH_SHORT).show();
        grantAndEnter();
    }

    private void handleUnlock(String pin) {
        if (lock_.verifyPin(pin.toCharArray())) {
            grantAndEnter();
        } else {
            showError(getString(R.string.lock_pin_wrong));
            etPin_.setText("");
        }
    }

    private void handleChange(String pin) {
        if (changeStep_ == 0) {
            // 第一步：校验旧 PIN。
            if (!lock_.verifyPin(pin.toCharArray())) {
                showError(getString(R.string.lock_pin_wrong));
                etPin_.setText("");
                return;
            }
            changeStep_ = 1;
            applyModeUi();
            return;
        }
        // 第二步：写入新 PIN（带二次确认）。
        String confirm = etPinConfirm_.getText() == null
                ? "" : etPinConfirm_.getText().toString();
        if (!pin.equals(confirm)) {
            showError(getString(R.string.lock_pin_mismatch));
            return;
        }
        try {
            lock_.setPin(pin.toCharArray());
        } catch (Exception e) {
            showError(e.getMessage() == null ? "error" : e.getMessage());
            return;
        }
        Toast.makeText(this, R.string.lock_change_done,
                Toast.LENGTH_SHORT).show();
        // 当前会话保持已解锁状态（用户刚刚做完密码校验），无需再回登陆页。
        setResult(RESULT_OK);
        finish();
    }

    private void showError(String msg) {
        tvError_.setText(msg);
        tvError_.setVisibility(View.VISIBLE);
    }

    private void grantAndEnter() {
        lock_.markUnlocked();
        // 不主动 startActivity(MainActivity)：finish 后系统会回到调用者，
        // 若是冷启动会回到 LAUNCHER（即 MainActivity）。这里显式跳一次更稳妥。
        Intent it = new Intent(this, MainActivity.class);
        it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(it);
        finish();
    }

    // ---------- 生物识别 ----------

    private boolean canUseBiometric() {
        BiometricManager bm = BiometricManager.from(this);
        int result = bm.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
                        | BiometricManager.Authenticators.BIOMETRIC_WEAK);
        return result == BiometricManager.BIOMETRIC_SUCCESS;
    }

    private void updateBiometricButton() {
        if (canUseBiometric() && lock_.isBiometricEnabled()) {
            btnBiometric_.setVisibility(View.VISIBLE);
        } else {
            btnBiometric_.setVisibility(View.GONE);
        }
    }

    private void showBiometricPrompt() {
        if (!canUseBiometric()) {
            return;
        }
        Executor exec = ContextCompat.getMainExecutor(this);
        BiometricPrompt prompt = new BiometricPrompt(this, exec,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            @NonNull BiometricPrompt.AuthenticationResult r) {
                        super.onAuthenticationSucceeded(r);
                        grantAndEnter();
                    }

                    @Override
                    public void onAuthenticationError(
                            int errorCode, @NonNull CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                        // 错误时静默：用户可继续输入 PIN。
                    }
                });
        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.lock_biometric_title))
                .setSubtitle(getString(R.string.lock_biometric_subtitle))
                .setNegativeButtonText(getString(R.string.lock_biometric_negative))
                .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG
                                | BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .build();
        prompt.authenticate(info);
    }
}
