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
 * 解锁/设置 PIN 的入口 Activity。
 *
 * 行为：
 *  - 若未设置 PIN，进入"设置 PIN"模式，需输入两次相同的 4-12 位数字。
 *  - 已设置 PIN 时进入"解锁"模式，输入正确即标记会话已解锁并启动主界面。
 *  - 设备具备生物识别条件时显示"使用指纹/面容解锁"按钮，
 *    成功亦视为已通过密码验证（前提是已设置过 PIN）。
 *
 * 安全考虑：
 *  - 启用 FLAG_SECURE 防止截屏 / 录屏 / Recents 缩略图泄露 PIN 输入界面。
 *  - 自身不继承 BaseSecureActivity，避免 onStart 时再次跳回自己造成循环。
 *  - 解锁前禁止返回桌面以外的逃逸：返回键 == 退出 App。
 */
public class LockActivity extends AppCompatActivity {

    private AppLockManager lock_;
    private boolean isSetupMode_ = false;

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
        isSetupMode_ = !lock_.isPinConfigured();

        etPin_ = findViewById(R.id.et_pin);
        etPinConfirm_ = findViewById(R.id.et_pin_confirm);
        tvTitle_ = findViewById(R.id.tv_lock_title);
        tvSubtitle_ = findViewById(R.id.tv_lock_subtitle);
        tvError_ = findViewById(R.id.tv_error);
        btnConfirm_ = findViewById(R.id.btn_confirm);
        btnBiometric_ = findViewById(R.id.btn_biometric);

        if (isSetupMode_) {
            tvTitle_.setText(R.string.lock_setup_title);
            tvSubtitle_.setText(R.string.lock_setup_subtitle);
            etPinConfirm_.setVisibility(View.VISIBLE);
            btnBiometric_.setVisibility(View.GONE);
        } else {
            tvTitle_.setText(R.string.lock_unlock_title);
            tvSubtitle_.setText(R.string.lock_pin_hint);
            etPinConfirm_.setVisibility(View.GONE);
            updateBiometricButton();
        }

        btnConfirm_.setOnClickListener(v -> onConfirm());
        btnBiometric_.setOnClickListener(v -> showBiometricPrompt());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 自动唤起一次生物识别（仅解锁模式且用户开启）。
        if (!isSetupMode_ && canUseBiometric() && lock_.isBiometricEnabled()) {
            etPin_.post(this::showBiometricPrompt);
        }
    }

    /** 阻止用户在解锁前返回桌面以外的位置：直接退出 Task。 */
    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    private void onConfirm() {
        String pin = etPin_.getText() == null
                ? "" : etPin_.getText().toString();
        if (TextUtils.isEmpty(pin) || pin.length() < 4) {
            showError(getString(R.string.lock_pin_too_short));
            return;
        }
        if (isSetupMode_) {
            String confirm = etPinConfirm_.getText() == null
                    ? "" : etPinConfirm_.getText().toString();
            if (!pin.equals(confirm)) {
                showError(getString(R.string.lock_pin_mismatch));
                return;
            }
            try {
                lock_.setPin(pin.toCharArray());
            } catch (Exception e) {
                showError(e.getMessage() == null
                        ? "error" : e.getMessage());
                return;
            }
            Toast.makeText(this, R.string.lock_setup_done,
                    Toast.LENGTH_SHORT).show();
            grantAndEnter();
        } else {
            if (lock_.verifyPin(pin.toCharArray())) {
                grantAndEnter();
            } else {
                showError(getString(R.string.lock_pin_wrong));
                etPin_.setText("");
            }
        }
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
