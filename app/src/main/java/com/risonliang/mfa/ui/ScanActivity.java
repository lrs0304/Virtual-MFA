/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.BarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;
import com.google.zxing.BarcodeFormat;
import com.risonliang.mfa.R;
import java.util.Collections;
import java.util.List;

/**
 * 扫码 Activity：纯离线，不联网。
 * 使用原始 BarcodeView 而非 DecoratedBarcodeView，避免出现方向切换按钮等装饰控件。
 */
public class ScanActivity extends AppCompatActivity {

    public static final String EXTRA_RESULT = "scan_result";
    private static final int REQ_CAMERA = 1001;

    private BarcodeView barcodeView_;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);
        barcodeView_ = findViewById(R.id.barcode_view);
        barcodeView_.setDecoderFactory(new DefaultDecoderFactory(
                Collections.singletonList(BarcodeFormat.QR_CODE)));

        // 让底部提示文字避让导航栏 / 手势条 / 曲面屏。
        final TextView tvStatus = findViewById(R.id.tv_status);
        final float density = getResources().getDisplayMetrics().density;
        final int baseBottom = (int) (48 * density);
        ViewCompat.setOnApplyWindowInsetsListener(tvStatus, (v, wic) -> {
            Insets bars = wic.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            android.view.ViewGroup.MarginLayoutParams mlp =
                    (android.view.ViewGroup.MarginLayoutParams)
                            v.getLayoutParams();
            mlp.bottomMargin = baseBottom + bars.bottom;
            mlp.leftMargin = bars.left;
            mlp.rightMargin = bars.right;
            v.setLayoutParams(mlp);
            return wic;
        });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        } else {
            startScan();
        }
    }

    private void startScan() {
        barcodeView_.decodeContinuous(new BarcodeCallback() {
            @Override
            public void barcodeResult(BarcodeResult result) {
                if (result == null || result.getText() == null) {
                    return;
                }
                Intent data = new Intent();
                data.putExtra(EXTRA_RESULT, result.getText());
                setResult(RESULT_OK, data);
                finish();
            }

            @Override
            public void possibleResultPoints(
                    List<com.google.zxing.ResultPoint> resultPoints) {}
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startScan();
            } else {
                android.widget.Toast.makeText(this,
                        R.string.permission_camera_denied,
                        android.widget.Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (barcodeView_ != null) {
            barcodeView_.resume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (barcodeView_ != null) {
            barcodeView_.pause();
        }
    }
}
