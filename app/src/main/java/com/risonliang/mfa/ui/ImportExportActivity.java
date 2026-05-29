/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import com.google.android.material.button.MaterialButton;
import com.risonliang.mfa.R;
import com.risonliang.mfa.data.BackupCodec;
import com.risonliang.mfa.data.OtpRepository;
import com.risonliang.mfa.model.OtpAccount;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

public class ImportExportActivity extends BaseSecureActivity {

    private ActivityResultLauncher<Intent> exportLauncher_;
    private ActivityResultLauncher<Intent> importLauncher_;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_import_export);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        // 为内容区应用左右 + 底部安全距离。
        InsetsUtils.applySidesAndBottomAsPadding(
                findViewById(R.id.content_import_export));

        MaterialButton btnExport = findViewById(R.id.btn_export);
        MaterialButton btnImport = findViewById(R.id.btn_import);

        exportLauncher_ = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK
                            && result.getData() != null) {
                        promptPasswordForExport(result.getData().getData());
                    }
                });
        importLauncher_ = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK
                            && result.getData() != null) {
                        promptPasswordForImport(result.getData().getData());
                    }
                });

        btnExport.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/octet-stream");
            i.putExtra(Intent.EXTRA_TITLE, "mfa_backup.2fa");
            exportLauncher_.launch(i);
        });

        btnImport.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            importLauncher_.launch(i);
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void promptPasswordForExport(Uri uri) {
        showPasswordDialog(true, password -> doExport(uri, password));
    }

    private void promptPasswordForImport(Uri uri) {
        showPasswordDialog(false, password -> doImport(uri, password));
    }

    private interface PasswordCallback {
        void onPassword(char[] password);
    }

    private void showPasswordDialog(boolean confirm,
                                    PasswordCallback callback) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, 0);

        EditText et1 = new EditText(this);
        et1.setHint(R.string.hint_password);
        et1.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(et1);

        EditText et2 = null;
        if (confirm) {
            et2 = new EditText(this);
            et2.setHint(R.string.hint_password_confirm);
            et2.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            layout.addView(et2);
        }

        final EditText et2Final = et2;
        new AlertDialog.Builder(this)
                .setTitle(confirm ? R.string.action_export
                        : R.string.action_import)
                .setView(layout)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.dialog_ok, (d, w) -> {
                    String p1 = et1.getText().toString();
                    if (TextUtils.isEmpty(p1) || p1.length() < 6) {
                        Toast.makeText(this, R.string.password_too_short,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (confirm) {
                        String p2 = et2Final.getText().toString();
                        if (!p1.equals(p2)) {
                            Toast.makeText(this, R.string.password_mismatch,
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }
                    callback.onPassword(p1.toCharArray());
                })
                .show();
    }

    private void doExport(Uri uri, char[] password) {
        try {
            List<OtpAccount> all = OtpRepository.get(this).listAll();
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) {
                    throw new IllegalStateException("open output null");
                }
                BackupCodec.export(all, password, os);
            }
            Toast.makeText(this, R.string.export_success,
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.export_failed)
                    + "：" + e.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    private void doImport(Uri uri, char[] password) {
        try {
            List<OtpAccount> list;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                if (is == null) {
                    throw new IllegalStateException("open input null");
                }
                list = BackupCodec.importFrom(is, password);
            }
            int count = 0;
            for (OtpAccount a : list) {
                OtpRepository.get(this).insert(a);
                count++;
            }
            Toast.makeText(this,
                    getString(R.string.import_success, count),
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.import_failed,
                    Toast.LENGTH_LONG).show();
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }
}
