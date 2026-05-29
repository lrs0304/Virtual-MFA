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
import com.risonliang.mfa.data.CsvBackup;
import com.risonliang.mfa.data.OtpRepository;
import com.risonliang.mfa.model.OtpAccount;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ImportExportActivity extends BaseSecureActivity {

    /** 导出格式枚举：加密 .2fa 或 明文 .csv。 */
    private enum ExportFormat { ENCRYPTED_2FA, PLAIN_CSV }

    private ActivityResultLauncher<Intent> exportLauncher_;
    private ActivityResultLauncher<Intent> importLauncher_;
    /** 当前导出选择的格式。在用户挑选目标文件前由对话框选定。 */
    private ExportFormat pendingExportFormat_ = ExportFormat.ENCRYPTED_2FA;

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
                        Uri uri = result.getData().getData();
                        if (pendingExportFormat_
                                == ExportFormat.ENCRYPTED_2FA) {
                            promptPasswordForExport(uri);
                        } else {
                            doExportCsv(uri);
                        }
                    }
                });
        importLauncher_ = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK
                            && result.getData() != null) {
                        handleImport(result.getData().getData());
                    }
                });

        btnExport.setOnClickListener(v -> showExportFormatChooser());

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

    /** 弹出导出格式选择对话框：卡片化展示，加密 .2fa 与 明文 .csv 一目了然。 */
    private void showExportFormatChooser() {
        android.view.View view = android.view.LayoutInflater.from(this)
                .inflate(R.layout.dialog_export_format, null);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.export_format_title)
                .setView(view)
                .setNegativeButton(R.string.dialog_cancel, null)
                .create();
        view.findViewById(R.id.card_encrypted).setOnClickListener(v -> {
            dlg.dismiss();
            pendingExportFormat_ = ExportFormat.ENCRYPTED_2FA;
            launchSaveDocument(
                    "mfa_backup.2fa",
                    "application/octet-stream");
        });
        view.findViewById(R.id.card_csv).setOnClickListener(v -> {
            dlg.dismiss();
            confirmCsvRiskThenExport();
        });
        dlg.show();
    }

    /** 二次确认明文 CSV 风险后再触发系统选择保存路径。 */
    private void confirmCsvRiskThenExport() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.csv_warning_title)
                .setMessage(R.string.csv_warning_message)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.csv_warning_continue, (d, w) -> {
                    pendingExportFormat_ = ExportFormat.PLAIN_CSV;
                    launchSaveDocument("mfa_backup.csv", "text/csv");
                })
                .show();
    }

    private void launchSaveDocument(String suggestedName, String mimeType) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mimeType);
        i.putExtra(Intent.EXTRA_TITLE, suggestedName);
        exportLauncher_.launch(i);
    }

    private void promptPasswordForExport(Uri uri) {
        showPasswordDialog(true, password -> doExport2fa(uri, password));
    }

    private void promptPasswordForImport2fa(Uri uri, byte[] cachedBytes) {
        showPasswordDialog(false,
                password -> doImport2fa(cachedBytes, password));
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

    private void doExport2fa(Uri uri, char[] password) {
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

    private void doExportCsv(Uri uri) {
        try {
            List<OtpAccount> all = OtpRepository.get(this).listAll();
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) {
                    throw new IllegalStateException("open output null");
                }
                CsvBackup.export(all, os);
            }
            Toast.makeText(this, R.string.export_success,
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.export_failed)
                    + "：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 自动识别导入文件格式：先一次性读全字节，按内容判定走哪条路径。
     *  - 以 '{' 起始且能解出 v/salt/data 字段：视为加密 .2fa
     *  - 否则视为 CSV
     */
    private void handleImport(Uri uri) {
        byte[] bytes;
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) {
                throw new IllegalStateException("open input null");
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int n;
            while ((n = is.read(tmp)) > 0) {
                buf.write(tmp, 0, n);
            }
            bytes = buf.toByteArray();
        } catch (Exception e) {
            Toast.makeText(this, R.string.import_failed,
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (looksLikeEncrypted2fa(bytes)) {
            promptPasswordForImport2fa(uri, bytes);
        } else if (looksLikeCsv(bytes)) {
            doImportCsv(bytes);
        } else {
            Toast.makeText(this, R.string.import_format_unknown,
                    Toast.LENGTH_LONG).show();
        }
    }

    /** 简单嗅探：去 BOM/空白后看首字符是否为 '{'。 */
    private static boolean looksLikeEncrypted2fa(byte[] bytes) {
        int i = skipBomAndWs(bytes);
        return i < bytes.length && bytes[i] == '{';
    }

    private static boolean looksLikeCsv(byte[] bytes) {
        // 兜底：非 JSON 都按 CSV 尝试解析。让真正的解析器去判定行列结构。
        return bytes.length > 0;
    }

    private static int skipBomAndWs(byte[] bytes) {
        int i = 0;
        if (bytes.length >= 3
                && (bytes[0] & 0xff) == 0xEF
                && (bytes[1] & 0xff) == 0xBB
                && (bytes[2] & 0xff) == 0xBF) {
            i = 3;
        }
        while (i < bytes.length) {
            byte b = bytes[i];
            if (b == ' ' || b == '\t' || b == '\r' || b == '\n') {
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    private void doImport2fa(byte[] bytes, char[] password) {
        try {
            List<OtpAccount> list;
            try (InputStream is = new ByteArrayInputStream(bytes)) {
                list = BackupCodec.importFrom(is, password);
            }
            int count = persist(list);
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

    private void doImportCsv(byte[] bytes) {
        try {
            List<OtpAccount> list;
            try (InputStream is = new ByteArrayInputStream(bytes)) {
                list = CsvBackup.importFrom(is);
            }
            int count = persist(list);
            Toast.makeText(this,
                    getString(R.string.import_success, count),
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.import_failed,
                    Toast.LENGTH_LONG).show();
        }
    }

    private int persist(List<OtpAccount> list) throws Exception {
        int count = 0;
        for (OtpAccount a : list) {
            OtpRepository.get(this).insert(a);
            count++;
        }
        return count;
    }
}
