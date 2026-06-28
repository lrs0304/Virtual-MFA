package com.risonliang.mfa.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.risonliang.mfa.R;

import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 图片编辑页：当相册导入未识别到二维码时进入，让用户在原图上手动调整后重试。
 *
 * <p>两种模式：
 * <ul>
 *   <li><b>缩放模式</b>（默认）：双指缩放/单指平移，把二维码区域放大到画面中央；
 *       点击「重新识别」会按当前可见区域裁切后送入 ML Kit。</li>
 *   <li><b>调整模式</b>：滑块调对比度 / 阈值 / R/G/B 亮度，按钮触发反色 /
 *       Otsu 自动二值化 / 重置；点击「重新识别」会把调整后的整张图（不裁切）
 *       送入 ML Kit。</li>
 * </ul>
 *
 * <p>识别成功 → 把结果以 {@link #EXTRA_RESULT} 形式 setResult(OK) 回传给上层。
 * 上层（MainActivity）拿到结果走原有 {@code handleScanResult} 流程；解析失败
 * 时再次进入 {@link QrContentPreviewActivity}。
 *
 * <p>识别失败 → 弹 Toast，留在当前页继续调整，不退出。
 */
public class ImageEditActivity extends AppCompatActivity {

    private static final String kLogTag = "MFA-ImageEdit";

    /** Intent 入参：相册原始 Uri。 */
    public static final String EXTRA_IMAGE_URI = "image_uri";
    /** setResult 出参：识别到的二维码原文。 */
    public static final String EXTRA_RESULT = "decoded_content";

    /** SeekBar 编码约定：对比度 progress 50 = 1.0x；范围 0~250 映射到 0.5~3.0。 */
    private static final int kContrastBase = 50;
    /** R/G/B 亮度 progress 127 = 0；范围 0~254 映射到 -127~+127。 */
    private static final int kBrightnessBase = 127;
    /** 阈值 progress：0 = OFF（不二值化），1~256 映射到 0~255 阈值。 */
    private static final int kThresholdOffProgress = 0;

    /**
     * 启动该 Activity 的快捷方法。
     */
    public static Intent newIntent(@NonNull Context ctx, @NonNull Uri uri) {
        Intent it = new Intent(ctx, ImageEditActivity.class);
        it.putExtra(EXTRA_IMAGE_URI, uri);
        return it;
    }

    private ZoomableImageView zoomImage_;
    private View adjustPanel_;
    private TextView tvHint_;
    private ProgressBar progressDecoding_;
    private MaterialButtonToggleGroup toggleMode_;
    private MaterialButton btnModeZoom_;
    private MaterialButton btnModeAdjust_;
    private MaterialButton btnRetry_;
    private MaterialButton btnInvert_;
    private MaterialButton btnOtsu_;
    private MaterialButton btnReset_;
    private SeekBar sbContrast_;
    private SeekBar sbThreshold_;
    private SeekBar sbR_;
    private SeekBar sbG_;
    private SeekBar sbB_;
    private TextView tvContrastVal_;
    private TextView tvThresholdVal_;
    private TextView tvRVal_;
    private TextView tvGVal_;
    private TextView tvBVal_;

    @Nullable
    private Bitmap originalBitmap_;
    /** 当前展示用的位图（缩放模式 = original，调整模式 = adjusted）。 */
    @Nullable
    private Bitmap currentBitmap_;

    private final BitmapAdjuster.Params params_ = new BitmapAdjuster.Params();
    /** true=缩放模式；false=调整模式。 */
    private boolean modeZoom_ = true;

    private final Handler uiHandler_ = new Handler(Looper.getMainLooper());
    private final ExecutorService bgExecutor_ =
            Executors.newSingleThreadExecutor();
    /** 避免拖滑块时密集触发 LUT，串行最新一次。 */
    @Nullable
    private Runnable pendingPreview_;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_edit);

        Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.title_image_edit);
        }
        tb.setNavigationOnClickListener(v -> finish());

        bindViews();
        loadImage();
        bindControls();
        switchMode(true);
    }

    private void bindViews() {
        zoomImage_ = findViewById(R.id.zoom_image);
        adjustPanel_ = findViewById(R.id.sv_adjust_panel);
        tvHint_ = findViewById(R.id.tv_hint);
        progressDecoding_ = findViewById(R.id.progress_decoding);
        toggleMode_ = findViewById(R.id.toggle_mode);
        btnModeZoom_ = findViewById(R.id.btn_mode_zoom);
        btnModeAdjust_ = findViewById(R.id.btn_mode_adjust);
        btnRetry_ = findViewById(R.id.btn_retry);
        btnInvert_ = findViewById(R.id.btn_invert);
        btnOtsu_ = findViewById(R.id.btn_otsu);
        btnReset_ = findViewById(R.id.btn_reset);
        sbContrast_ = findViewById(R.id.sb_contrast);
        sbThreshold_ = findViewById(R.id.sb_threshold);
        sbR_ = findViewById(R.id.sb_r);
        sbG_ = findViewById(R.id.sb_g);
        sbB_ = findViewById(R.id.sb_b);
        tvContrastVal_ = findViewById(R.id.tv_contrast_val);
        tvThresholdVal_ = findViewById(R.id.tv_threshold_val);
        tvRVal_ = findViewById(R.id.tv_r_val);
        tvGVal_ = findViewById(R.id.tv_g_val);
        tvBVal_ = findViewById(R.id.tv_b_val);
    }

    private void loadImage() {
        Uri uri = getIntent() == null
                ? null : getIntent().getParcelableExtra(EXTRA_IMAGE_URI);
        if (uri == null) {
            Toast.makeText(this, R.string.error_image_read,
                    Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                if (is != null) {
                    BitmapFactory.decodeStream(is, null, opts);
                }
            }
            // 控件最多展示 2K，原图过大时降采样：长边 > 2048 降采。
            int maxDim = Math.max(opts.outWidth, opts.outHeight);
            int sample = 1;
            while (maxDim / sample > 2048) {
                sample *= 2;
            }
            BitmapFactory.Options loadOpts = new BitmapFactory.Options();
            loadOpts.inSampleSize = Math.max(1, sample);
            loadOpts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                if (is == null) {
                    throw new IllegalStateException("openInputStream returned null");
                }
                originalBitmap_ = BitmapFactory.decodeStream(is, null, loadOpts);
            }
            if (originalBitmap_ == null) {
                throw new IllegalStateException("decodeStream returned null");
            }
            currentBitmap_ = originalBitmap_;
            zoomImage_.setImageBitmap(originalBitmap_);
            Log.d(kLogTag, "loaded image " + originalBitmap_.getWidth()
                    + "x" + originalBitmap_.getHeight() + ", sample=" + sample);
        } catch (Exception e) {
            Log.e(kLogTag, "loadImage failed", e);
            Toast.makeText(this, R.string.error_image_read,
                    Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void bindControls() {
        // 模式切换
        toggleMode_.check(R.id.btn_mode_zoom);
        toggleMode_.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.btn_mode_zoom) {
                switchMode(true);
            } else if (checkedId == R.id.btn_mode_adjust) {
                switchMode(false);
            }
        });

        btnRetry_.setOnClickListener(v -> doRetryDecode());

        // 调整模式控件
        sbContrast_.setOnSeekBarChangeListener(new SeekBarChange(progress -> {
            params_.contrast = mapContrast(progress);
            tvContrastVal_.setText(String.format(Locale.ROOT, "%.2fx",
                    params_.contrast));
            schedulePreview();
        }));
        sbThreshold_.setOnSeekBarChangeListener(new SeekBarChange(progress -> {
            params_.threshold = mapThreshold(progress);
            tvThresholdVal_.setText(params_.threshold < 0
                    ? "OFF" : String.valueOf(params_.threshold));
            schedulePreview();
        }));
        sbR_.setOnSeekBarChangeListener(new SeekBarChange(progress -> {
            params_.brightnessR = progress - kBrightnessBase;
            tvRVal_.setText(formatSigned(params_.brightnessR));
            schedulePreview();
        }));
        sbG_.setOnSeekBarChangeListener(new SeekBarChange(progress -> {
            params_.brightnessG = progress - kBrightnessBase;
            tvGVal_.setText(formatSigned(params_.brightnessG));
            schedulePreview();
        }));
        sbB_.setOnSeekBarChangeListener(new SeekBarChange(progress -> {
            params_.brightnessB = progress - kBrightnessBase;
            tvBVal_.setText(formatSigned(params_.brightnessB));
            schedulePreview();
        }));

        // 初始化数值显示
        tvContrastVal_.setText("1.00x");
        tvThresholdVal_.setText("OFF");
        tvRVal_.setText("0");
        tvGVal_.setText("0");
        tvBVal_.setText("0");

        btnInvert_.setOnClickListener(v -> {
            params_.invert = !params_.invert;
            schedulePreview();
        });
        btnOtsu_.setOnClickListener(v -> {
            if (originalBitmap_ == null) return;
            // Otsu 在原图灰度直方图上求最佳阈值，反映到 SeekBar 上。
            bgExecutor_.execute(() -> {
                int t = BitmapAdjuster.otsuThreshold(originalBitmap_);
                uiHandler_.post(() -> {
                    sbThreshold_.setProgress(t + 1);  // +1 因 0=OFF
                    // setProgress 会触发 listener → 自动 schedulePreview。
                });
            });
        });
        btnReset_.setOnClickListener(v -> {
            params_.brightnessR = 0;
            params_.brightnessG = 0;
            params_.brightnessB = 0;
            params_.contrast = 1.0f;
            params_.invert = false;
            params_.threshold = -1;
            sbContrast_.setProgress(kContrastBase);
            sbThreshold_.setProgress(kThresholdOffProgress);
            sbR_.setProgress(kBrightnessBase);
            sbG_.setProgress(kBrightnessBase);
            sbB_.setProgress(kBrightnessBase);
            schedulePreview();
        });
    }

    /** progress[0,250] → contrast[0.5, 3.0]，progress=50 → 1.0。 */
    private static float mapContrast(int progress) {
        if (progress < kContrastBase) {
            return 0.5f + (1.0f - 0.5f) * progress / kContrastBase;
        }
        return 1.0f + (3.0f - 1.0f) * (progress - kContrastBase) / 200f;
    }

    /** progress 0 → -1 (OFF)，progress 1..256 → 0..255。 */
    private static int mapThreshold(int progress) {
        return progress <= 0 ? -1 : Math.min(255, progress - 1);
    }

    private static String formatSigned(int v) {
        return (v > 0 ? "+" : "") + v;
    }

    /** 切换模式：缩放模式隐藏调整面板，调整模式显示。 */
    private void switchMode(boolean zoom) {
        modeZoom_ = zoom;
        adjustPanel_.setVisibility(zoom ? View.GONE : View.VISIBLE);
        tvHint_.setText(zoom
                ? R.string.image_edit_hint_zoom
                : R.string.image_edit_hint_adjust);
        if (zoom) {
            // 切回缩放模式：展示原图，丢弃调整后的中间结果，但保留滑块状态
            // 以便用户切回调整模式时不丢失参数。
            if (originalBitmap_ != null) {
                if (currentBitmap_ != null
                        && currentBitmap_ != originalBitmap_
                        && !currentBitmap_.isRecycled()) {
                    currentBitmap_.recycle();
                }
                currentBitmap_ = originalBitmap_;
                zoomImage_.setImageBitmap(originalBitmap_);
            }
        } else {
            // 切到调整模式：立即用当前参数渲染一遍。
            schedulePreview();
        }
    }

    /** 节流：合并连续 SeekBar 回调，最后一次生效。 */
    private void schedulePreview() {
        if (modeZoom_) return;  // 只在调整模式生效
        if (pendingPreview_ != null) {
            uiHandler_.removeCallbacks(pendingPreview_);
        }
        pendingPreview_ = this::renderPreview;
        uiHandler_.postDelayed(pendingPreview_, 80);
    }

    private void renderPreview() {
        if (originalBitmap_ == null) return;
        final BitmapAdjuster.Params snapshot = copyParams(params_);
        bgExecutor_.execute(() -> {
            Bitmap out = null;
            try {
                out = BitmapAdjuster.apply(originalBitmap_, snapshot);
            } catch (Throwable t) {
                Log.w(kLogTag, "BitmapAdjuster.apply failed", t);
            }
            final Bitmap result = out;
            uiHandler_.post(() -> {
                if (isFinishing() || isDestroyed() || result == null) {
                    if (result != null && !result.isRecycled()) {
                        result.recycle();
                    }
                    return;
                }
                if (currentBitmap_ != null
                        && currentBitmap_ != originalBitmap_
                        && !currentBitmap_.isRecycled()) {
                    currentBitmap_.recycle();
                }
                currentBitmap_ = result;
                zoomImage_.setImageBitmap(result);
            });
        });
    }

    private static BitmapAdjuster.Params copyParams(BitmapAdjuster.Params src) {
        BitmapAdjuster.Params p = new BitmapAdjuster.Params();
        p.brightnessR = src.brightnessR;
        p.brightnessG = src.brightnessG;
        p.brightnessB = src.brightnessB;
        p.contrast = src.contrast;
        p.invert = src.invert;
        p.threshold = src.threshold;
        return p;
    }

    /** 点击「重新识别」：把当前展示的位图喂给 ML Kit。 */
    private void doRetryDecode() {
        if (currentBitmap_ == null) {
            Toast.makeText(this, R.string.error_image_read,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        progressDecoding_.setVisibility(View.VISIBLE);
        btnRetry_.setEnabled(false);
        Toast.makeText(this, R.string.msg_decoding,
                Toast.LENGTH_SHORT).show();

        final Bitmap snapshot = currentBitmap_;
        bgExecutor_.execute(() -> {
            String content = QrDecoder.decode(snapshot);
            uiHandler_.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                progressDecoding_.setVisibility(View.GONE);
                btnRetry_.setEnabled(true);
                if (content != null && !content.isEmpty()) {
                    Intent data = new Intent();
                    data.putExtra(EXTRA_RESULT, content);
                    setResult(RESULT_OK, data);
                    finish();
                } else {
                    Toast.makeText(ImageEditActivity.this,
                            R.string.msg_decode_still_fail,
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bgExecutor_.shutdownNow();
        if (currentBitmap_ != null
                && currentBitmap_ != originalBitmap_
                && !currentBitmap_.isRecycled()) {
            currentBitmap_.recycle();
        }
        if (originalBitmap_ != null && !originalBitmap_.isRecycled()) {
            originalBitmap_.recycle();
        }
    }

    /** SeekBar 简化回调，仅关心 progress 变化。 */
    private static final class SeekBarChange
            implements SeekBar.OnSeekBarChangeListener {
        interface Cb { void onChanged(int progress); }
        private final Cb cb_;
        SeekBarChange(Cb cb) { this.cb_ = cb; }
        @Override public void onProgressChanged(SeekBar seekBar, int progress,
                                                boolean fromUser) {
            cb_.onChanged(progress);
        }
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    }
}
