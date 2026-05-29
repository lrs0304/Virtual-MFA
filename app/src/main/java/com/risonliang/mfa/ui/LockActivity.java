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
 * 解锁 / 设置 / 修改 / 关闭 PIN 的入口 Activity。
 *
 * 四种模式（按 EXTRA_MODE 决定，默认按 PIN 是否已设置自动选择）：
 *  - {@link #MODE_SETUP}：首次设置 PIN，需输入两次相同的 4-12 位数字。
 *  - {@link #MODE_UNLOCK}：解锁；输入正确即标记会话已解锁并进入主界面。
 *  - {@link #MODE_CHANGE}：修改 PIN；先校验旧 PIN，再分两次输入新 PIN。
 *  - {@link #MODE_DISABLE}：关闭应用密码；校验当前 PIN 通过后清除 PIN 与启用标记。
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
    public static final int MODE_DISABLE = 4;

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

    /**
     * 根据外部传入的 mode 与当前 PIN 配置态，校正成最终模式。
     *
     * 关键约束：
     *  - 用户主动点击"启用应用密码"传入 MODE_SETUP 时，意图明确就是重新设置一个 PIN。
     *    无论 prefs 中是否有残留 PIN（旧版本遗留 / 异常态），都先 clearAllSecrets 再进入 SETUP，
     *    避免被错误地降级为 UNLOCK 而触发自动生物识别弹窗。
     *  - MODE_CHANGE / MODE_DISABLE 必须 PIN 已启用，否则视为非法请求 → 走 SETUP / 直接退出。
     *  - MODE_UNLOCK 必须应用密码已启用，否则不应该来到这里，走 finish 防御。
     */
    private int resolveMode(int requested) {
        boolean enabled = lock_.isLockEnabled();
        boolean pinExists = lock_.isPinConfigured();

        if (requested == MODE_SETUP) {
            // 用户主动要求"启用"。无论残留态如何，先清空再让用户重新设。
            if (pinExists) {
                lock_.clearAllSecrets();
            }
            return MODE_SETUP;
        }
        if (requested == MODE_CHANGE) {
            return enabled ? MODE_CHANGE : MODE_SETUP;
        }
        if (requested == MODE_DISABLE) {
            return enabled ? MODE_DISABLE : MODE_SETUP;
        }
        if (requested == MODE_UNLOCK) {
            return enabled ? MODE_UNLOCK : MODE_SETUP;
        }
        // AUTO：根据应用密码是否启用自动选择。
        return enabled ? MODE_UNLOCK : MODE_SETUP;
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
            case MODE_DISABLE:
                tvTitle_.setText(R.string.lock_disable_title);
                tvSubtitle_.setText(R.string.lock_disable_subtitle);
                etPinConfirm_.setVisibility(View.GONE);
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
        // 仅"解锁"模式且应用密码已启用、用户开启了生物识别时，自动唤起一次。
        // isLockEnabled 校验作为防御纵深：即便 mode_ 被错误置为 UNLOCK，
        // 只要功能未启用也不会弹出生物识别框，避免误导用户。
        if (mode_ == MODE_UNLOCK
                && lock_.isLockEnabled()
                && canUseBiometric() && lock_.isBiometricEnabled()) {
            etPin_.post(this::showBiometricPrompt);
        }
    }

    /**
     * LockActivity 也参与应用级前台计数，但不参与"是否需要解锁"判定。
     * 这样从 MainActivity 跳来 LockActivity、再跳回时，前台计数始终 >= 1，
     * 不会触发 0->1 翻转，避免 onActivityStarted 在回 MainActivity 时再要求解锁。
     */
    @Override
    protected void onStart() {
        super.onStart();
        AppLockManager.get(this).onActivityStarted();
    }

    @Override
    protected void onStop() {
        super.onStop();
        AppLockManager.get(this).onActivityStopped();
    }

    /**
     * 解锁模式按返回 = 退到桌面；修改/关闭模式按返回 = 取消并回到调用者。
     * 设置模式（首次启动）也按退到桌面处理，强制完成首次设置。
     */
    @Override
    public void onBackPressed() {
        if (mode_ == MODE_CHANGE || mode_ == MODE_DISABLE) {
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
            case MODE_DISABLE:
                handleDisable(pin);
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

    private void handleDisable(String pin) {
        if (!lock_.verifyPin(pin.toCharArray())) {
            showError(getString(R.string.lock_pin_wrong));
            etPin_.setText("");
            return;
        }
        lock_.disableLock();
        Toast.makeText(this, R.string.lock_disable_done,
                Toast.LENGTH_SHORT).show();
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
