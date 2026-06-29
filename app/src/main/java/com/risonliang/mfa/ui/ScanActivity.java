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
import com.google.zxing.DecodeHintType;
import com.risonliang.mfa.R;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * 扫码 Activity：纯离线，不联网。
 * 使用原始 BarcodeView 而非 DecoratedBarcodeView，避免出现方向切换按钮等装饰控件。
 */
public class ScanActivity extends BaseSecureActivity {

    public static final String EXTRA_RESULT = "scan_result";
    private static final int REQ_CAMERA = 1001;

    private BarcodeView barcodeView_;
    private boolean cameraGranted_ = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);
        barcodeView_ = findViewById(R.id.barcode_view);
        // 解码 hint：
        //  - TRY_HARDER：牺牲性能换识别率，对模糊/小尺寸/部分倾斜的二维码更友好；
        //  - CHARACTER_SET=UTF-8：避免某些 ROM 上 issuer 含中文字符乱码。
        // 只解码 QR，不需要 PURE_BARCODE（实景扫码不可能是"纯净二维码"）。
        Map<DecodeHintType, Object> hints = new HashMap<>();
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        barcodeView_.setDecoderFactory(new DefaultDecoderFactory(
                Collections.singletonList(BarcodeFormat.QR_CODE),
                hints, "UTF-8", 0));
        // 取消默认 framingRect 裁剪：让解码作用于整帧预览。
        // 默认逻辑（CameraPreview#calculateFramingRect）：竖屏下会把 framing rect
        // 强制压缩为"屏幕宽 × 屏幕宽"的中央正方形，二维码靠上/靠下或占比较大时
        // 会丢掉两个 finder pattern，表现为 possiblePoints 永远=1 但永远调不出
        // barcodeResult。传入一个超大尺寸让 inset 被 max(0,...) 截成 0，从而
        // framingRect = 整个 intersection（全帧解码）。
        barcodeView_.setFramingRectSize(
                new com.journeyapps.barcodescanner.Size(10000, 10000));

        // 相机参数优化：必须在 resume() 之前设置。
        //  - ContinuousFocus：持续自动对焦，避免预览静止后二维码模糊；
        //    实测 Camera1 默认 focus-mode=auto 仅初始化时对焦一次，
        //    导致 finder pattern 找到（possiblePoints=3/4）但数据模块模糊解不出。
        //  - BarcodeSceneMode：若 ROM 支持，会切到针对扫码优化的预设；
        //  - Metering：基于中心区域测光/对焦，对小尺寸二维码识别更稳定。
        com.journeyapps.barcodescanner.camera.CameraSettings cs =
                barcodeView_.getCameraSettings();
        cs.setContinuousFocusEnabled(true);
        cs.setBarcodeSceneModeEnabled(true);
        cs.setMeteringEnabled(true);
        // ScanInverted：兼容反相二维码（白底黑码反相成黑底白码），少量 ROM
        // 上预览帧亮度通道会被反转，开启后让解码线程对正反两种采样都尝试。
        cs.setScanInverted(true);

        // 强制把预览分辨率压到 ≤1280×720 的最大候选项（zxing 实测的 sweet spot）。
        // 默认策略（FitCenterStrategy）会选最贴近 viewfinder 的尺寸，在 2K+
        // 屏的小米 15 Pro 上选到了 2400×1080，二维码数据模块过采样反而失真，
        // 表现为 possiblePoints=3/4 但 decode 永远失败。
        barcodeView_.setPreviewScalingStrategy(new LowResScalingStrategy());

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
            cameraGranted_ = true;
        }
    }

    /** zxing-android-embedded 官方建议：在 onResume 中注册回调并 resume。 */
    private final BarcodeCallback callback_ = new BarcodeCallback() {
        @Override
        public void barcodeResult(BarcodeResult result) {
            android.util.Log.d("MFA-Scan",
                    "barcodeResult: result=" + (result == null
                            ? "null" : result.getText()));
            if (result == null || result.getText() == null) {
                return;
            }
            // 命中后立即停止解码，避免重复触发回调。
            barcodeView_.stopDecoding();
            Intent data = new Intent();
            data.putExtra(EXTRA_RESULT, result.getText());
            setResult(RESULT_OK, data);
            finish();
        }

        @Override
        public void possibleResultPoints(
                List<com.google.zxing.ResultPoint> resultPoints) {
            // 即使没识别成功，只要 DecoderThread 在工作就会持续触发该回调；
            // 用作\"DecoderThread 是否真活着\"的探针。仅打印数量，避免刷屏。
            if (resultPoints != null && !resultPoints.isEmpty()) {
                android.util.Log.v("MFA-Scan",
                        "possiblePoints=" + resultPoints.size());
            }
        }
    };

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                cameraGranted_ = true;
                // 权限弹窗关闭后 onResume 还会再走一次，由 onResume 统一启动扫码。
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
        android.util.Log.d("MFA-Scan",
                "onResume: granted=" + cameraGranted_
                        + ", barcodeView=" + barcodeView_);
        if (barcodeView_ != null && cameraGranted_) {
            // 顺序：先 resume 让 CameraInstance 就绪，再 decodeContinuous 启动
            // DecoderThread；否则 DecoderThread 可能因 camera 未就绪而静默退出，
            // 表现为"预览正常但永远不识别"。
            barcodeView_.resume();
            android.util.Log.d("MFA-Scan", "onResume: barcodeView.resume() done");
            barcodeView_.decodeContinuous(callback_);
            android.util.Log.d("MFA-Scan", "onResume: decodeContinuous registered");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (barcodeView_ != null) {
            barcodeView_.stopDecoding();
            barcodeView_.pause();
        }
    }
}
