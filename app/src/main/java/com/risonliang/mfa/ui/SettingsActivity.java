/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.ui;

import android.os.Bundle;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import androidx.appcompat.widget.Toolbar;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.risonliang.mfa.R;

/**
 * 简洁的「设置」页：集中管理对安全无影响的呈现偏好与宽限期偏好。
 *
 * 设计原则：
 *  1. 所有写操作直接落到 {@link UiPreferences}，离开页面即生效，无需"保存"按钮，
 *     符合 Android 平台 Material 设置页的肌肉记忆。
 *  2. 严格走 {@link BaseSecureActivity}，FLAG_SECURE 与解锁路由都和主页一致；
 *     避免出现"设置页能截屏、主页不能"的不一致体验。
 *  3. 不在此处暴露应用密码 / 生物识别开关——这些走 {@link LockActivity} 的专用流程，
 *     其本身需要先校验当前密码，安全模型与"无副作用偏好"差异较大，独立维护。
 */
public final class SettingsActivity extends BaseSecureActivity {

    private static final int[] kGraceOptions = {0, 15, 30, 60, 300};

    private SwitchMaterial swHideCodes_;
    private SwitchMaterial swShowNextCode_;
    private RadioGroup rgGrace_;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());
        InsetsUtils.applySidesAndBottomAsPadding(
                findViewById(R.id.content_settings));

        swHideCodes_ = findViewById(R.id.sw_hide_codes);
        swShowNextCode_ = findViewById(R.id.sw_show_next_code);
        rgGrace_ = findViewById(R.id.rg_grace);

        UiPreferences prefs = UiPreferences.get(this);

        // 初始化控件状态。先 setChecked 再注册监听，避免初始化触发一次写入。
        swHideCodes_.setChecked(prefs.isHideCodes());
        swHideCodes_.setOnCheckedChangeListener((btn, checked) ->
                UiPreferences.get(this).setHideCodes(checked));

        swShowNextCode_.setChecked(prefs.isShowNextCode());
        swShowNextCode_.setOnCheckedChangeListener((btn, checked) ->
                UiPreferences.get(this).setShowNextCode(checked));

        int currentGrace = prefs.getAutoLockGraceSec();
        int checkedId = mapGraceToRadioId(currentGrace);
        if (checkedId != 0) {
            ((RadioButton) findViewById(checkedId)).setChecked(true);
        }
        rgGrace_.setOnCheckedChangeListener((group, id) -> {
            int sec = mapRadioIdToGrace(id);
            UiPreferences.get(this).setAutoLockGraceSec(sec);
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    /**
     * 将持久化的秒数映射到 RadioButton id。未识别的旧值就近回落到 0（最严格）。
     */
    private static int mapGraceToRadioId(int sec) {
        if (sec <= 0) {
            return R.id.rb_grace_0;
        }
        if (sec <= 15) {
            return R.id.rb_grace_15;
        }
        if (sec <= 30) {
            return R.id.rb_grace_30;
        }
        if (sec <= 60) {
            return R.id.rb_grace_60;
        }
        return R.id.rb_grace_300;
    }

    private static int mapRadioIdToGrace(int id) {
        if (id == R.id.rb_grace_15) return 15;
        if (id == R.id.rb_grace_30) return 30;
        if (id == R.id.rb_grace_60) return 60;
        if (id == R.id.rb_grace_300) return 300;
        return 0;
    }
}
