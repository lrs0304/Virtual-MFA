/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.risonliang.mfa.R;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 扫码 Activity：CameraX 预览 + ML Kit 实时识别，纯离线、无联网。
 *
 * <p>历史上本页使用 zxing-android-embedded 的 BarcodeView，但其底层 Camera1
 * 路径在小米/华为新机型（如 15 Pro / Mate60 Pro）上长期出现"预览正常但永远
 * 识别不到二维码"的问题。A 方案重写后切换到 CameraX + ML Kit：
 * <ul>
 *   <li>CameraX 自动选择 Camera2 后端，绕开国产 ROM 的 Camera1 兼容坑；</li>
 *   <li>ML Kit 与相册解码（{@link AlbumQrDecoder} / {@link QrDecoder}）使用
 *       同一识别管线，技术栈统一；</li>
 *   <li>ImageAnalysis 默认 STRATEGY_KEEP_ONLY_LATEST，自动丢帧避免堆积。</li>
 * </ul>
 *
 * <p>对外契约保持不变：扫码成功后通过 {@link #EXTRA_RESULT} 回传字符串。
 */
public class ScanActivity extends BaseSecureActivity {

    public static final String EXTRA_RESULT = "scan_result";

    private static final String kLogTag = "MFA-Scan";
    private static final int kReqCamera = 1001;
    /**
     * ImageAnalysis 目标分辨率：1280×720 是 ML Kit 实测识别率与吞吐的甜点。
     * 更高分辨率下 module 像素密度过高反而拖慢检测，且更耗电。
     */
    private static final Size kAnalysisTargetSize = new Size(1280, 720);

    private PreviewView previewView_;
    private ExecutorService analysisExecutor_;
    private BarcodeScanner barcodeScanner_;
    /** 命中后置 true，阻止重复触发 finish。 */
    private volatile boolean handled_ = false;
    private boolean cameraGranted_ = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);
        previewView_ = findViewById(R.id.preview_view);

        // 单线程串行分析：避免多线程并发调用 ML Kit 导致 Task 排队混乱。
        analysisExecutor_ = Executors.newSingleThreadExecutor();
        // ML Kit BarcodeScanner：仅识别 QR_CODE，加快速度。
        BarcodeScannerOptions opts = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build();
        barcodeScanner_ = BarcodeScanning.getClient(opts);

        // 让底部提示文字避让导航栏 / 手势条 / 曲面屏。
        TextView tvStatus = findViewById(R.id.tv_status);
        float density = getResources().getDisplayMetrics().density;
        int baseBottom = (int) (48 * density);
        ViewCompat.setOnApplyWindowInsetsListener(tvStatus, (v, wic) -> {
            Insets bars = wic.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            android.view.ViewGroup.MarginLayoutParams mlp =
                    (android.view.ViewGroup.MarginLayoutParams) v.getLayoutParams();
            mlp.bottomMargin = baseBottom + bars.bottom;
            mlp.leftMargin = bars.left;
            mlp.rightMargin = bars.right;
            v.setLayoutParams(mlp);
            return wic;
        });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, kReqCamera);
        } else {
            cameraGranted_ = true;
            startCamera();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == kReqCamera) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                cameraGranted_ = true;
                startCamera();
            } else {
                Toast.makeText(this, R.string.permission_camera_denied,
                        Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    /**
     * 启动 CameraX：Preview + ImageAnalysis 双 use case 绑定到本 Activity 的
     * 生命周期，离开页面时自动停掉相机与分析线程。
     */
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> providerFuture =
                ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            try {
                ProcessCameraProvider provider = providerFuture.get();
                bindUseCases(provider);
            } catch (Exception e) {
                Log.e(kLogTag, "CameraX provider init failed", e);
                Toast.makeText(this, R.string.permission_camera_denied,
                        Toast.LENGTH_LONG).show();
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindUseCases(ProcessCameraProvider provider) {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView_.getSurfaceProvider());

        // 1280x720 接近时优先选用，达不到时回退到最近候选。
        ResolutionSelector resolutionSelector = new ResolutionSelector.Builder()
                .setResolutionStrategy(new ResolutionStrategy(
                        kAnalysisTargetSize,
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build();
        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setAnalyzer(analysisExecutor_, this::analyzeFrame);

        try {
            provider.unbindAll();
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, analysis);
            Log.d(kLogTag, "CameraX bound: preview + analysis");
        } catch (Exception e) {
            Log.e(kLogTag, "bindToLifecycle failed", e);
            Toast.makeText(this, R.string.permission_camera_denied,
                    Toast.LENGTH_LONG).show();
            finish();
        }
    }

    /**
     * 单帧分析：把 CameraX 的 ImageProxy 转成 ML Kit InputImage，识别完必须
     * close 释放底层 buffer，否则下一帧不会到达。
     *
     * @noinspection deprecation
     */
    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeFrame(@NonNull ImageProxy proxy) {
        if (handled_) {
            proxy.close();
            return;
        }
        android.media.Image media = proxy.getImage();
        if (media == null) {
            proxy.close();
            return;
        }
        InputImage input = InputImage.fromMediaImage(media,
                proxy.getImageInfo().getRotationDegrees());
        barcodeScanner_.process(input)
                .addOnSuccessListener(barcodes -> handleBarcodes(barcodes))
                .addOnFailureListener(e -> Log.w(kLogTag,
                        "ML Kit process failed: " + e.getMessage()))
                .addOnCompleteListener(t -> proxy.close());
    }

    /**
     * 命中处理：取首个 QR 的内容，置 handled_ 阻止重复触发，然后回传并 finish。
     * 这里直接读取 rawValue，必要时回退 displayValue（少数二进制 QR 适用）。
     */
    private void handleBarcodes(@Nullable java.util.List<Barcode> barcodes) {
        if (handled_ || barcodes == null || barcodes.isEmpty()) {
            return;
        }
        Barcode b = barcodes.get(0);
        String raw = b.getRawValue();
        if (raw == null) {
            raw = b.getDisplayValue();
        }
        if (raw == null) {
            return;
        }
        handled_ = true;
        Log.d(kLogTag, "barcodeResult: length=" + raw.length());
        Intent data = new Intent();
        data.putExtra(EXTRA_RESULT, raw);
        setResult(RESULT_OK, data);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (barcodeScanner_ != null) {
            barcodeScanner_.close();
            barcodeScanner_ = null;
        }
        if (analysisExecutor_ != null) {
            analysisExecutor_.shutdown();
            analysisExecutor_ = null;
        }
    }
}
