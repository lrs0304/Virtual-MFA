/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;
import com.google.zxing.BarcodeFormat;
import com.risonliang.mfa.R;
import java.util.Collections;
import java.util.List;

/**
 * 扫码 Activity：纯离线，不联网。
 */
public class ScanActivity extends AppCompatActivity {

    public static final String EXTRA_RESULT = "scan_result";
    private static final int REQ_CAMERA = 1001;

    private DecoratedBarcodeView barcodeView_;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 直接构建 view，避免再写一份布局，减小 APK
        barcodeView_ = new DecoratedBarcodeView(this);
        barcodeView_.setStatusText(getString(R.string.scan_prompt));
        setContentView(barcodeView_);

        barcodeView_.getBarcodeView().setDecoderFactory(
                new DefaultDecoderFactory(
                        Collections.singletonList(BarcodeFormat.QR_CODE)));

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
