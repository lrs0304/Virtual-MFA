package com.risonliang.mfa.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.risonliang.mfa.R;

/**
 * 二维码内容预览页。
 *
 * <p>触发场景：相册/扫码已识别出二维码内容，但内容不是合法的 MFA otpauth URI
 * （也不是 Google Authenticator 迁移格式），无法添加到账号列表。
 *
 * <p>该页面让用户能看到解析出的原文 + 一键复制，从而：
 * <ul>
 *   <li>判断是不是错选了图（例如随手拍的快递单 / 公众号收款码）；</li>
 *   <li>把原文复制出来贴到浏览器或 IM 里查证；</li>
 *   <li>当 MFA 服务商使用非 otpauth 自有协议时，用户能拿到原始 payload。</li>
 * </ul>
 *
 * <p>本页不写任何状态，纯展示 + 复制。安全考虑：不在 Recents 缩略图显示文本
 * （父 Activity 已设 FLAG_SECURE，本页继承策略，且 toolbar 文案不含 secret）。
 */
public class QrContentPreviewActivity extends AppCompatActivity {

    /** Intent 中携带的原始二维码内容字符串。 */
    public static final String EXTRA_CONTENT = "qr_content";

    /**
     * 启动该页面的快捷方法。
     */
    public static Intent newIntent(@NonNull Context ctx, @NonNull String content) {
        Intent it = new Intent(ctx, QrContentPreviewActivity.class);
        it.putExtra(EXTRA_CONTENT, content);
        return it;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_content_preview);

        // 标题栏。
        Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.title_qr_content_preview);
        }
        tb.setNavigationOnClickListener(v -> finish());

        String content = getIntent() == null
                ? null : getIntent().getStringExtra(EXTRA_CONTENT);
        if (content == null) {
            content = "";
        }

        TextView tvHint = findViewById(R.id.tv_hint);
        TextView tvContent = findViewById(R.id.tv_content);
        Button btnCopy = findViewById(R.id.btn_copy);
        Button btnClose = findViewById(R.id.btn_close);

        // 失败提示文案（含字符长度，方便用户初步判断是不是被截断了）。
        tvHint.setText(getString(
                R.string.qr_content_preview_hint, content.length()));
        tvContent.setText(content);

        final String finalContent = content;
        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                Toast.makeText(this, R.string.copy_failed,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            // Label 不暴露 secret，仅做 ClipData 描述。
            cm.setPrimaryClip(ClipData.newPlainText("qr_content", finalContent));
            Toast.makeText(this, R.string.copied_to_clipboard,
                    Toast.LENGTH_SHORT).show();
        });

        btnClose.setOnClickListener(v -> finish());

        // 长内容时把按钮压在底部，短内容时按钮跟随文本流；ScrollView 已处理。
        View root = findViewById(R.id.root_container);
        InsetsUtils.applySidesAndBottomAsPadding(root);
    }
}
