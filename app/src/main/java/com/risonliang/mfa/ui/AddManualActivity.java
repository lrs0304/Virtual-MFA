/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.ui;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Spinner;
import android.widget.Toast;
import androidx.appcompat.widget.Toolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.risonliang.mfa.R;
import com.risonliang.mfa.crypto.Base32;
import com.risonliang.mfa.data.OtpRepository;
import com.risonliang.mfa.model.OtpAccount;

public class AddManualActivity extends BaseSecureActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_manual);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        // 让内容区遵循左右曲面屏与底部导航/IME 安全距离。
        InsetsUtils.applySidesAndBottomAsPadding(
                findViewById(R.id.sv_content));

        TextInputEditText etIssuer = findViewById(R.id.et_issuer);
        TextInputEditText etAccount = findViewById(R.id.et_account);
        TextInputEditText etSecret = findViewById(R.id.et_secret);
        TextInputEditText etDigits = findViewById(R.id.et_digits);
        TextInputEditText etPeriod = findViewById(R.id.et_period);
        Spinner spAlgo = findViewById(R.id.sp_algorithm);
        Spinner spType = findViewById(R.id.sp_type);
        View layoutPeriod = findViewById(R.id.layout_period);
        View layoutCounter = findViewById(R.id.layout_counter);
        TextInputEditText etCounter = findViewById(R.id.et_counter);
        MaterialButton btnSave = findViewById(R.id.btn_save);
        MaterialButton btnCancel = findViewById(R.id.btn_cancel);

        // 根据类型切换周期/计数器输入框的可见性
        spType.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent,
                                       View view, int position, long id) {
                boolean isHotp = position == 1; // 0=TOTP, 1=HOTP
                layoutPeriod.setVisibility(isHotp ? View.GONE : View.VISIBLE);
                layoutCounter.setVisibility(isHotp ? View.VISIBLE : View.GONE);
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        btnCancel.setOnClickListener(v -> finish());
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String issuer = textOf(etIssuer);
                String account = textOf(etAccount);
                String secret = textOf(etSecret).replace(" ", "");
                if (TextUtils.isEmpty(issuer) || TextUtils.isEmpty(secret)) {
                    Toast.makeText(AddManualActivity.this,
                            R.string.error_required, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!Base32.isValid(secret)) {
                    Toast.makeText(AddManualActivity.this,
                            R.string.error_secret_invalid,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                OtpAccount acc = new OtpAccount();
                acc.issuer = issuer;
                acc.account = account;
                acc.secret = secret;
                acc.algorithm = spAlgo.getSelectedItem().toString();
                acc.digits = parseInt(textOf(etDigits),
                        OtpAccount.DEFAULT_DIGITS);
                // 类型：0=TOTP, 1=HOTP
                boolean isHotp = spType.getSelectedItemPosition() == 1;
                acc.type = isHotp ? OtpAccount.TYPE_HOTP
                        : OtpAccount.TYPE_TOTP;
                if (isHotp) {
                    acc.counter = parseLong(textOf(etCounter), 0);
                    acc.period = OtpAccount.DEFAULT_PERIOD;
                } else {
                    acc.period = parseInt(textOf(etPeriod),
                            OtpAccount.DEFAULT_PERIOD);
                }
                try {
                    OtpRepository.get(AddManualActivity.this).insert(acc);
                    finish();
                } catch (Exception e) {
                    Toast.makeText(AddManualActivity.this,
                            getString(R.string.error_save_failed,
                                    e.getMessage()),
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private static String textOf(TextInputEditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception ignore) {
            return def;
        }
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s);
        } catch (Exception ignore) {
            return def;
        }
    }
}
