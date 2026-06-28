/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.risonliang.mfa.R;
import com.risonliang.mfa.crypto.OtpGenerator;
import com.risonliang.mfa.data.GaMigrationDecoder;
import com.risonliang.mfa.data.OtpRepository;
import com.risonliang.mfa.data.OtpUriParser;
import com.risonliang.mfa.model.OtpAccount;
import com.risonliang.mfa.security.ClipboardCleaner;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends BaseSecureActivity {

    private static final String kLogTag = "MFA-Scan";

    private RecyclerView rvAccounts_;
    private TextView tvEmpty_;
    private EditText etSearch_;
    private ImageButton btnSearchClear_;
    private OtpAdapter adapter_;
    /** 适配器实际渲染的列表（受搜索条件过滤）。 */
    private final List<OtpAccount> data_ = new ArrayList<>();
    /** 全量数据缓存：搜索过滤的输入源，避免重复读 DB。 */
    private final List<OtpAccount> allData_ = new ArrayList<>();
    /** 当前搜索词（trim 后的小写）。空字符串表示无过滤。 */
    private String currentQuery_ = "";
    /**
     * 临时显形超时类型型装载：帐号 id → 该帐号可见明文验证码的截止时间戳（ms）。
     * 超过该时间后 renderCode 重新走默认隔离分支。
     */
    private final java.util.Map<Long, Long> revealUntilMs_ = new HashMap<>();
    private static final long kRevealDurationMs = 5_000L;
    private final Handler tickHandler_ = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<Intent> scanLauncher_;
    private ActivityResultLauncher<String> albumLauncher_;
    private final ExecutorService bgExecutor_ =
            Executors.newSingleThreadExecutor();

    private final Runnable tick_ = new Runnable() {
        @Override
        public void run() {
            if (adapter_ != null && rvAccounts_ != null) {
                // 仅刷新当前可见范围内的条目，避免全量刷新
                LinearLayoutManager lm = (LinearLayoutManager)
                        rvAccounts_.getLayoutManager();
                if (lm != null) {
                    int first = lm.findFirstVisibleItemPosition();
                    int last = lm.findLastVisibleItemPosition();
                    if (first >= 0 && last >= first) {
                        adapter_.notifyItemRangeChanged(
                                first, last - first + 1, Boolean.TRUE);
                    }
                }
            }
            tickHandler_.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setSupportActionBar((Toolbar) findViewById(R.id.toolbar));

        rvAccounts_ = findViewById(R.id.rv_accounts);
        tvEmpty_ = findViewById(R.id.tv_empty);
        etSearch_ = findViewById(R.id.et_search);
        btnSearchClear_ = findViewById(R.id.btn_search_clear);
        rvAccounts_.setLayoutManager(new LinearLayoutManager(this));
        adapter_ = new OtpAdapter(data_, this::onItemClick, this::onItemLong,
                this::isCodeRevealed);
        rvAccounts_.setAdapter(adapter_);
        attachSwipeToDelete();
        attachSearch();

        FloatingActionButton fab = findViewById(R.id.fab_add);
        fab.setOnClickListener(v -> showAddSheet());

        // 列表底部留出导航栏/手势条空间（保留原 96dp 视觉留白），左右适配曲面屏。
        InsetsUtils.applySidesAndBottomAsPadding(rvAccounts_);
        // FAB 跟随导航栏 / 曲面屏调整 margin，初始基线 20dp。
        InsetsUtils.applySidesAndBottomAsMargin(fab, 20, 20, 20);

        scanLauncher_ = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK
                            && result.getData() != null) {
                        String content = result.getData().getStringExtra(
                                ScanActivity.EXTRA_RESULT);
                        handleScanResult(content);
                    }
                });

        albumLauncher_ = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        decodeQrFromImage(uri);
                    }
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
        tickHandler_.post(tick_);
        // 应用密码启停可能在 LockActivity 中改变，回前台时刷新菜单可见性。
        invalidateOptionsMenu();
    }

    @Override
    protected void onPause() {
        super.onPause();
        tickHandler_.removeCallbacks(tick_);
        // 后台遮罩：进入后台前清空可见验证码，仅保留占位符；
        // 配合 FLAG_SECURE，确保 Recents 缩略图与肩窥风险都被覆盖。
        if (adapter_ != null) {
            adapter_.maskAllCodes();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(android.view.Menu menu) {
        // 应用密码未启用：仅显示"启用"；已启用：显示"修改"+"关闭"。
        boolean enabled =
                com.risonliang.mfa.security.AppLockManager.get(this)
                        .isLockEnabled();
        android.view.MenuItem mEnable = menu.findItem(R.id.action_enable_pin);
        android.view.MenuItem mChange = menu.findItem(R.id.action_change_pin);
        android.view.MenuItem mDisable = menu.findItem(R.id.action_disable_pin);
        if (mEnable != null) {
            mEnable.setVisible(!enabled);
        }
        if (mChange != null) {
            mChange.setVisible(enabled);
        }
        if (mDisable != null) {
            mDisable.setVisible(enabled);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_import_export) {
            startActivity(new Intent(this, ImportExportActivity.class));
            return true;
        }
        if (id == R.id.action_enable_pin) {
            // 防御：若 prefs 中有残留 PIN（旧版本遗留 / 异常态），先彻底清掉，
            // 确保 LockActivity 一定走 SETUP 流程而非自动弹出生物识别。
            com.risonliang.mfa.security.AppLockManager.get(this)
                    .clearAllSecrets();
            Intent it = new Intent(this, LockActivity.class);
            it.putExtra(LockActivity.EXTRA_MODE, LockActivity.MODE_SETUP);
            startActivity(it);
            return true;
        }
        if (id == R.id.action_change_pin) {
            Intent it = new Intent(this, LockActivity.class);
            it.putExtra(LockActivity.EXTRA_MODE, LockActivity.MODE_CHANGE);
            startActivity(it);
            return true;
        }
        if (id == R.id.action_disable_pin) {
            Intent it = new Intent(this, LockActivity.class);
            it.putExtra(LockActivity.EXTRA_MODE, LockActivity.MODE_DISABLE);
            startActivity(it);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void reload() {
        try {
            allData_.clear();
            allData_.addAll(OtpRepository.get(this).listAll());
            applyFilter();
        } catch (Exception e) {
            Toast.makeText(this,
                    getString(R.string.error_load_failed, e.getMessage()),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 绑定搜索栏：文本变化时即时过滤；清除按钮一键还原全量列表。
     * 仅做本地 issuer / account 字段大小写不敏感的子串匹配，不涉及 secret。
     */
    private void attachSearch() {
        if (etSearch_ == null) {
            return;
        }
        etSearch_.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start,
                                          int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start,
                                      int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                String q = s == null ? "" : s.toString().trim();
                currentQuery_ = q;
                if (btnSearchClear_ != null) {
                    btnSearchClear_.setVisibility(
                            q.isEmpty() ? View.GONE : View.VISIBLE);
                }
                applyFilter();
            }
        });
        if (btnSearchClear_ != null) {
            btnSearchClear_.setOnClickListener(v -> {
                etSearch_.setText("");
            });
        }
    }

    /**
     * 根据 {@link #currentQuery_} 过滤 {@link #allData_} → {@link #data_}，
     * 并刷新空态文案。空查询直接全量回填，避免不必要的字符串比较。
     */
    private void applyFilter() {
        data_.clear();
        if (currentQuery_.isEmpty()) {
            data_.addAll(allData_);
        } else {
            String needle = currentQuery_.toLowerCase(java.util.Locale.ROOT);
            for (OtpAccount acc : allData_) {
                if (matches(acc, needle)) {
                    data_.add(acc);
                }
            }
        }
        adapter_.notifyDataSetChanged();
        if (data_.isEmpty()) {
            tvEmpty_.setVisibility(View.VISIBLE);
            tvEmpty_.setText(currentQuery_.isEmpty()
                    ? getString(R.string.empty_tip)
                    : getString(R.string.empty_search_tip, currentQuery_));
        } else {
            tvEmpty_.setVisibility(View.GONE);
        }
    }

    /** issuer / account 任一字段含查询词（小写）即视为命中。 */
    private static boolean matches(OtpAccount acc, String needleLower) {
        if (acc == null) {
            return false;
        }
        if (acc.issuer != null
                && acc.issuer.toLowerCase(java.util.Locale.ROOT)
                        .contains(needleLower)) {
            return true;
        }
        if (acc.account != null
                && acc.account.toLowerCase(java.util.Locale.ROOT)
                        .contains(needleLower)) {
            return true;
        }
        return false;
    }

    private void showAddSheet() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.title_add_account)
                .setItems(new CharSequence[]{
                                getString(R.string.action_scan),
                                getString(R.string.action_from_album),
                                getString(R.string.action_manual)},
                        (d, which) -> {
                            if (which == 0) {
                                launchScan();
                            } else if (which == 1) {
                                albumLauncher_.launch("image/*");
                            } else {
                                startActivity(new Intent(this,
                                        AddManualActivity.class));
                            }
                        })
                .show();
    }

    private void launchScan() {
        scanLauncher_.launch(new Intent(this, ScanActivity.class));
    }

    /**
     * 从相册选取的图片中解码二维码。
     * 在后台线程执行图片解码和二维码识别，避免阻塞 UI 线程导致 ANR。
     * 同时对大图片进行降采样，防止 OOM。
     */
    private void decodeQrFromImage(Uri imageUri) {
        Toast.makeText(this, R.string.decoding_image,
                Toast.LENGTH_SHORT).show();
        bgExecutor_.execute(() -> {
            String decoded = decodeQrInBackground(imageUri);
            runOnUiThread(() -> {
                if (decoded == null) {
                    // 错误已在后台方法中标记，此处不重复提示
                } else if (decoded.isEmpty()) {
                    Toast.makeText(this, R.string.error_qr_not_found,
                            Toast.LENGTH_SHORT).show();
                } else {
                    handleScanResult(decoded);
                }
            });
        });
    }

    /**
     * 后台线程：读取图片、降采样、解码二维码。
     * @return 解码内容；空字符串表示未找到二维码；null 表示读取失败（已 Toast）。
     */
    private String decodeQrInBackground(Uri imageUri) {
        try {
            // 第一遍：仅读取尺寸，不加载像素
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            try (InputStream is = getContentResolver().openInputStream(imageUri)) {
                if (is == null) {
                    Log.w(kLogTag, "openInputStream returned null (bounds), uri="
                            + imageUri);
                    runOnUiThread(() -> Toast.makeText(this,
                            R.string.error_image_read,
                            Toast.LENGTH_SHORT).show());
                    return null;
                }
                BitmapFactory.decodeStream(is, null, opts);
            }
            int origW = opts.outWidth;
            int origH = opts.outHeight;
            int maxDim = Math.max(origW, origH);
            // 截图里的二维码往往只占整图一小块，过早降采样会把模块缩到 1~2px，
            // 直接导致 ZXing 找不到 finder pattern。
            // 策略：在多个采样率上串行尝试，原图优先；每个采样率内尝试
            // Hybrid/GlobalHistogram/Invert 三档快速二值化，作为 ML Kit 的
            // 快速路径；ZXing 全部失败时才唤起 ML Kit 模型兜底。
            int firstSample = 1;
            while (maxDim / firstSample > 4096) {
                firstSample *= 2;
            }
            Log.d(kLogTag, "image bounds=" + origW + "x" + origH
                    + ", mime=" + opts.outMimeType
                    + ", firstSample=" + firstSample);

            int[] sampleCandidates = {firstSample, firstSample * 2,
                    firstSample * 4};
            String content = null;
            for (int s : sampleCandidates) {
                if (maxDim / s < 200) {
                    // 缩得太小已没识别价值，跳过
                    continue;
                }
                if (content == null) {
                    Log.d(kLogTag, "trying decode with sample=" + s);
                    content = decodeQrWithSample(imageUri, s);
                    if (content != null) {
                        break;
                    }
                }
            }
            if (content == null) {
                // ZXing 全管线失败：上 ML Kit 兜底（bundled 模型，离线，
                // 对屏摄/水印噪点截图鲁棒性远强于 ZXing 的阈值二值化）。
                Log.d(kLogTag, "ZXing missed, fallback to ML Kit");
                content = decodeWithMlKit(imageUri, firstSample);
            }
            if (content == null) {
                Log.w(kLogTag,
                        "QRCode not found after all retries, origSize="
                                + origW + "x" + origH
                                + ", mime=" + opts.outMimeType
                                + ", samplesTried="
                                + java.util.Arrays.toString(sampleCandidates));
                return "";
            }
            Log.d(kLogTag, "QR decoded, length=" + content.length()
                    + ", schemePrefix=" + safeSchemePrefix(content));
            return content;
        } catch (Exception e) {
            Log.e(kLogTag, "decodeQrInBackground failed", e);
            runOnUiThread(() -> Toast.makeText(this,
                    R.string.error_image_read,
                    Toast.LENGTH_SHORT).show());
            return null;
        }
    }

    /**
     * 以指定采样率读取图片并尝试解码二维码。失败返回 null，
     * 由调用者决定是否换一个采样率重试。
     */
    private String decodeQrWithSample(Uri imageUri, int sampleSize) {
        Bitmap bitmap = null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = Math.max(1, sampleSize);
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream is = getContentResolver().openInputStream(imageUri)) {
                if (is == null) {
                    Log.w(kLogTag,
                            "openInputStream returned null (decode), sample="
                                    + sampleSize);
                    return null;
                }
                bitmap = BitmapFactory.decodeStream(is, null, opts);
            }
            if (bitmap == null) {
                Log.w(kLogTag, "decodeStream returned null, sample="
                        + sampleSize);
                return null;
            }
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

            RGBLuminanceSource source =
                    new RGBLuminanceSource(width, height, pixels);

            // 准备 hints。开启 TRY_HARDER：以更慢的速度换取更高的识别成功率，
            // 适用于相册截图、有压缩噪声、占比较小的二维码。
            Map<DecodeHintType, Object> hints =
                    new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);

            // ZXing 快速路径：依次尝试 Hybrid/GlobalHistogram/Invert 三档
            // 二值化。这三档都很轻量，能覆盖绝大多数清晰截图；任何一档命中
            // 都立即返回。复杂样本（屏摄/水印/噪点等）让外层走 ML Kit 兜底，
            // 不再在 ZXing 这里堆 Cleaned/Multi 等高成本路径。

            // 1) HybridBinarizer + TRY_HARDER：通用最佳。
            String r = tryDecode(new BinaryBitmap(new HybridBinarizer(source)),
                    hints, "Hybrid+TryHarder", width, height);
            if (r != null) {
                return r;
            }
            // 2) GlobalHistogramBinarizer + TRY_HARDER：对低对比度截图更友好。
            r = tryDecode(
                    new BinaryBitmap(new GlobalHistogramBinarizer(source)),
                    hints, "GlobalHist+TryHarder", width, height);
            if (r != null) {
                return r;
            }
            // 3) 反色：极少数浅底深码 / 深底浅码翻转的截图。
            BinaryBitmap inverted =
                    new BinaryBitmap(new HybridBinarizer(source.invert()));
            r = tryDecode(inverted, hints, "Hybrid+Invert", width, height);
            return r;
        } catch (Exception e) {
            Log.w(kLogTag, "decodeQrWithSample(" + sampleSize + ") error: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            return null;
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    /** 单次 ZXing 解码尝试，封装异常并记录耗时。 */
    private static String tryDecode(BinaryBitmap bb,
            Map<DecodeHintType, Object> hints, String label,
            int w, int h) {
        QRCodeReader reader = new QRCodeReader();
        try {
            Result result = reader.decode(bb, hints);
            Log.d(kLogTag, "QR decode hit by " + label + ", size="
                    + w + "x" + h);
            return result.getText();
        } catch (com.google.zxing.NotFoundException miss) {
            return null;
        } catch (Exception e) {
            Log.w(kLogTag, "QR decode " + label + " threw: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            return null;
        } finally {
            reader.reset();
        }
    }

    /**
     * ML Kit 兜底解码：仅在 ZXing 全管线失败时调用。
     *
     * 设计要点：
     *  1) 复用与 ZXing 相同的 inSampleSize 策略读 Bitmap，避免大图 OOM；
     *  2) 必须运行在后台线程（外层 bgExecutor_ 已经是后台线程）；
     *     ML Kit 返回 Task 异步结果，这里用 CountDownLatch 同步等待，
     *     与现有"返回 String"的同步签名保持一致；
     *  3) 限制识别格式为 QR_CODE，加快速度；
     *  4) 加 8 秒超时，避免极端情况下卡住后台线程；
     *  5) BarcodeScanner 是 Closeable，使用 try-with-resources 正确释放
     *     底层 native 模型句柄。
     *
     * @return 解码到的内容；任何失败返回 null。
     */
    private String decodeWithMlKit(Uri imageUri, int sampleSize) {
        Bitmap bitmap = null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = Math.max(1, sampleSize);
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream is = getContentResolver().openInputStream(imageUri)) {
                if (is == null) {
                    Log.w(kLogTag, "ML Kit: openInputStream returned null");
                    return null;
                }
                bitmap = BitmapFactory.decodeStream(is, null, opts);
            }
            if (bitmap == null) {
                Log.w(kLogTag, "ML Kit: decodeStream returned null");
                return null;
            }

            BarcodeScannerOptions scannerOpts =
                    new BarcodeScannerOptions.Builder()
                            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                            .build();
            // rotationDegrees=0：截图都是正向的，不需要旋转。
            InputImage input = InputImage.fromBitmap(bitmap, 0);
            final String[] holder = new String[1];
            final Throwable[] errHolder = new Throwable[1];
            final java.util.concurrent.CountDownLatch latch =
                    new java.util.concurrent.CountDownLatch(1);

            try (BarcodeScanner scanner =
                         BarcodeScanning.getClient(scannerOpts)) {
                scanner.process(input)
                        .addOnSuccessListener(barcodes -> {
                            if (barcodes != null && !barcodes.isEmpty()) {
                                Barcode b = barcodes.get(0);
                                String raw = b.getRawValue();
                                if (raw == null) {
                                    // 部分二进制 QR 没有 rawValue，
                                    // 退而求其次尝试 displayValue。
                                    raw = b.getDisplayValue();
                                }
                                holder[0] = raw;
                            }
                            latch.countDown();
                        })
                        .addOnFailureListener(e -> {
                            errHolder[0] = e;
                            latch.countDown();
                        });
                if (!latch.await(8,
                        java.util.concurrent.TimeUnit.SECONDS)) {
                    Log.w(kLogTag, "ML Kit: timeout after 8s");
                    return null;
                }
            }

            if (errHolder[0] != null) {
                Log.w(kLogTag, "ML Kit: process failed: "
                        + errHolder[0].getClass().getSimpleName()
                        + " " + errHolder[0].getMessage());
                return null;
            }
            if (holder[0] == null) {
                Log.w(kLogTag, "ML Kit: no QR found");
                return null;
            }
            Log.d(kLogTag, "ML Kit: QR decoded, length="
                    + holder[0].length()
                    + ", schemePrefix=" + safeSchemePrefix(holder[0]));
            return holder[0];
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            Log.w(kLogTag, "ML Kit: interrupted");
            return null;
        } catch (Exception e) {
            Log.w(kLogTag, "ML Kit: unexpected error: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            return null;
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    /** 仅取 scheme://host 段用于日志，避免把 secret 整段打到 logcat。 */
    private static String safeSchemePrefix(String s) {
        if (s == null) {
            return "<null>";
        }
        int q = s.indexOf('?');
        String head = q > 0 ? s.substring(0, q) : s;
        if (head.length() > 64) {
            head = head.substring(0, 64) + "...";
        }
        return head;
    }

    private void handleScanResult(String content) {
        // 先尝试解析为 Google Authenticator 迁移格式
        if (GaMigrationDecoder.isMigrationUri(content)) {
            Log.d(kLogTag, "handleScanResult: branch=GA_MIGRATION");
            handleGaMigration(content);
            return;
        }
        OtpAccount acc = OtpUriParser.parse(content);
        if (acc == null) {
            Log.w(kLogTag, "OtpUriParser.parse returned null, prefix="
                    + safeSchemePrefix(content));
            Toast.makeText(this, R.string.error_qr_invalid,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            if (OtpRepository.get(this).exists(acc)) {
                Log.d(kLogTag, "scan result duplicate, issuer="
                        + acc.issuer + ", account=" + acc.account);
                Toast.makeText(this, R.string.import_duplicate,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            OtpRepository.get(this).insert(acc);
            Log.d(kLogTag, "scan result inserted, issuer="
                    + acc.issuer + ", account=" + acc.account
                    + ", type=" + acc.type);
            reload();
        } catch (Exception e) {
            Log.e(kLogTag, "scan result save failed", e);
            Toast.makeText(this,
                    getString(R.string.error_save_failed, e.getMessage()),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** 处理 Google Authenticator 迁移二维码，批量导入。 */
    private void handleGaMigration(String uri) {
        List<OtpAccount> accounts = GaMigrationDecoder.decode(uri);
        Log.d(kLogTag, "GaMigrationDecoder.decode size=" + accounts.size());
        if (accounts.isEmpty()) {
            Toast.makeText(this, R.string.error_ga_migration_empty,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        int success = 0;
        int skipped = 0;
        for (OtpAccount acc : accounts) {
            try {
                if (OtpRepository.get(this).exists(acc)) {
                    skipped++;
                    continue;
                }
                OtpRepository.get(this).insert(acc);
                success++;
            } catch (Exception ignore) {}
        }
        String msg = getString(R.string.ga_migration_success, success);
        if (skipped > 0) {
            msg += "\n" + getString(R.string.import_skipped, skipped);
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        reload();
    }

    private void onItemClick(OtpAccount acc) {
        long now = System.currentTimeMillis();
        String code;
        if (acc.isHotp()) {
            // HOTP：点击时生成并递增计数器
            code = OtpGenerator.hotp(acc.secret, acc.algorithm,
                    acc.digits, acc.counter);
            acc.counter = OtpRepository.get(this)
                    .incrementCounter(acc.id, acc.counter);
            // 刷新当前条目显示
            int idx = data_.indexOf(acc);
            if (idx >= 0) {
                adapter_.notifyItemChanged(idx);
            }
        } else {
            code = OtpGenerator.totp(acc.secret, acc.algorithm,
                    acc.digits, acc.period, now);
        }
        // T3：若开启了"隐藏验证码"，则把当前条目加入 5s 显形窗口；
        // 否则不影响（下次 tick 渲染走默认路径）。
        if (UiPreferences.get(this).isHideCodes()) {
            revealUntilMs_.put(acc.id, now + kRevealDurationMs);
            int idx = data_.indexOf(acc);
            if (idx >= 0) {
                adapter_.notifyItemChanged(idx);
            }
        }
        ClipboardCleaner.get(this).copyOtp("otp", code,
                ClipboardCleaner.kDefaultClearDelayMs);
        Toast.makeText(this, R.string.copied_with_clear,
                Toast.LENGTH_SHORT).show();
    }

    /**
     * 供 OtpAdapter 询问"某账号此刻是否处于临时显形窗口"。
     * 自带过期清理，调用后表自动收缩。
     */
    private boolean isCodeRevealed(long accountId) {
        Long until = revealUntilMs_.get(accountId);
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() < until) {
            return true;
        }
        revealUntilMs_.remove(accountId);
        return false;
    }

    /** 长按改为编辑：仅允许修改 issuer / account，不动 secret。 */
    private void onItemLong(OtpAccount acc) {
        View dialogView = LayoutInflater.from(this)
                .inflate(R.layout.dialog_edit_account, null, false);
        final EditText etIssuer = dialogView.findViewById(R.id.et_edit_issuer);
        final EditText etAccount = dialogView.findViewById(R.id.et_edit_account);
        etIssuer.setText(acc.issuer == null ? "" : acc.issuer);
        etAccount.setText(acc.account == null ? "" : acc.account);

        new AlertDialog.Builder(this)
                .setTitle(R.string.title_edit_account)
                .setView(dialogView)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, (d, w) -> {
                    String newIssuer = etIssuer.getText() == null
                            ? "" : etIssuer.getText().toString().trim();
                    String newAccount = etAccount.getText() == null
                            ? "" : etAccount.getText().toString().trim();
                    if (TextUtils.isEmpty(newIssuer)
                            && TextUtils.isEmpty(newAccount)) {
                        Toast.makeText(this, R.string.error_required,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    acc.issuer = newIssuer;
                    acc.account = newAccount;
                    try {
                        OtpRepository.get(this).update(acc);
                        reload();
                    } catch (Exception e) {
                        Toast.makeText(this,
                                getString(R.string.error_save_failed,
                                        e.getMessage()),
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    /** 左滑删除（带二次确认）；取消则恢复条目。背景圆角与卡片保持一致。 */
    private void attachSwipeToDelete() {
        ItemTouchHelper.SimpleCallback cb = new ItemTouchHelper.SimpleCallback(
                0, ItemTouchHelper.LEFT) {
            private final Paint bgPaint_ = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint textPaint_ = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Rect textBounds_ = new Rect();
            private final RectF rectF_ = new RectF();
            private final String label_ = getString(R.string.action_delete);
            private final float textPx_ = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 16f,
                    getResources().getDisplayMetrics());
            private final int padPx_ = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 24f,
                    getResources().getDisplayMetrics());
            // 与 item_otp.xml 中 CardView 的视觉参数保持一致。
            private final float cornerPx_ = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 12f,
                    getResources().getDisplayMetrics());
            private final int marginHPx_ = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 12f,
                    getResources().getDisplayMetrics());
            private final int marginVPx_ = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 6f,
                    getResources().getDisplayMetrics());

            {
                bgPaint_.setColor(androidx.core.content.ContextCompat.getColor(
                        MainActivity.this, R.color.progress_warn));
                bgPaint_.setStyle(Paint.Style.FILL);
                textPaint_.setColor(Color.WHITE);
                textPaint_.setTextSize(textPx_);
                textPaint_.setTypeface(Typeface.DEFAULT_BOLD);
            }

            @Override
            public boolean onMove(@NonNull RecyclerView rv,
                                  @NonNull RecyclerView.ViewHolder vh,
                                  @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh,
                                 int direction) {
                int pos = vh.getBindingAdapterPosition();
                if (pos < 0 || pos >= data_.size()) {
                    return;
                }
                OtpAccount acc = data_.get(pos);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(acc.displayLabel())
                        .setMessage(R.string.confirm_delete)
                        .setNegativeButton(R.string.dialog_cancel,
                                (d, w) -> adapter_.notifyItemChanged(pos))
                        .setOnCancelListener(
                                d -> adapter_.notifyItemChanged(pos))
                        .setPositiveButton(R.string.dialog_ok, (d, w) -> {
                            OtpRepository.get(MainActivity.this).delete(acc.id);
                            reload();
                        })
                        .show();
            }

            @Override
            public void onChildDraw(@NonNull Canvas c,
                                    @NonNull RecyclerView rv,
                                    @NonNull RecyclerView.ViewHolder vh,
                                    float dX, float dY,
                                    int actionState, boolean isCurrentlyActive) {
                View item = vh.itemView;
                if (dX < 0) {
                    // 与 CardView 的 marginHorizontal=12dp / marginVertical=6dp 对齐
                    float top = item.getTop() + marginVPx_;
                    float bottom = item.getBottom() - marginVPx_;
                    float right = item.getRight() - marginHPx_;
                    float left = right + dX;
                    // 防止滑动距离很小时，left 超过 right 造成绘制异常
                    if (left > right) {
                        left = right;
                    }
                    rectF_.set(left, top, right, bottom);
                    c.drawRoundRect(rectF_, cornerPx_, cornerPx_, bgPaint_);

                    textPaint_.getTextBounds(label_, 0, label_.length(),
                            textBounds_);
                    // 仅在背景宽度足以容纳文字时绘制，避免越界压在卡片上
                    float bgWidth = right - left;
                    float textWidth = textBounds_.width();
                    if (bgWidth >= textWidth + padPx_) {
                        float ty = top + (bottom - top + textBounds_.height())
                                / 2f;
                        float tx = right - padPx_ - textWidth;
                        c.drawText(label_, tx, ty, textPaint_);
                    }
                }
                super.onChildDraw(c, rv, vh, dX, dY,
                        actionState, isCurrentlyActive);
            }
        };
        new ItemTouchHelper(cb).attachToRecyclerView(rvAccounts_);
    }

    /** RecyclerView Adapter（内部类，避免拆分小文件、控制类数量）。 */
    static final class OtpAdapter
            extends RecyclerView.Adapter<OtpAdapter.VH> {

        interface ItemAction { void on(OtpAccount acc); }
        interface RevealQuery { boolean isRevealed(long accountId); }

        /** payload 标识：脱敏遮罩刷新。 */
        private static final Object kPayloadMask = new Object();

        private final List<OtpAccount> data_;
        private final ItemAction onClick_;
        private final ItemAction onLong_;
        private final RevealQuery reveal_;

        OtpAdapter(List<OtpAccount> data, ItemAction onClick,
                   ItemAction onLong, RevealQuery reveal) {
            data_ = data;
            onClick_ = onClick;
            onLong_ = onLong;
            reveal_ = reveal;
            setHasStableIds(true);
        }

        @Override
        public long getItemId(int position) {
            return data_.get(position).id;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_otp, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            OtpAccount acc = data_.get(position);
            h.bind(acc, reveal_);
            h.itemView.setOnClickListener(v -> onClick_.on(acc));
            h.itemView.setOnLongClickListener(v -> {
                onLong_.on(acc);
                return true;
            });
        }

        /** 脱敏所有 item 的验证码（后台遮罩用），并停掉进度条数值。 */
        void maskAllCodes() {
            notifyItemRangeChanged(0, getItemCount(), kPayloadMask);
        }

        /** 仅刷新可见 item 的码与进度，避免整体重绘闪烁。 */
        void notifyTimeChanged() {
            notifyItemRangeChanged(0, getItemCount(), Boolean.TRUE);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position,
                                     @NonNull List<Object> payloads) {
            if (!payloads.isEmpty()) {
                if (payloads.contains(kPayloadMask)) {
                    h.maskCode();
                } else {
                    h.refreshCode(data_.get(position), reveal_);
                }
            } else {
                onBindViewHolder(h, position);
            }
        }

        @Override
        public int getItemCount() {
            return data_.size();
        }

        static final class VH extends RecyclerView.ViewHolder {
            private static final int kWarnThresholdSec = 5;
            /** 隐藏验证码时的默认遮罩字符串（与 6 位对齐）。 */
            private static final String kHiddenMask = "••• •••";

            final TextView tvIssuer_;
            final TextView tvAccount_;
            final TextView tvCode_;
            final TextView tvNextCode_;
            final TextView tvRemaining_;
            final ProgressBar pb_;

            VH(View v) {
                super(v);
                tvIssuer_ = v.findViewById(R.id.tv_issuer);
                tvAccount_ = v.findViewById(R.id.tv_account);
                tvCode_ = v.findViewById(R.id.tv_code);
                tvNextCode_ = v.findViewById(R.id.tv_next_code);
                tvRemaining_ = v.findViewById(R.id.tv_remaining);
                pb_ = v.findViewById(R.id.pb_remaining);
            }

            void bind(OtpAccount acc, RevealQuery reveal) {
                tvIssuer_.setText(acc.issuer == null ? "" : acc.issuer);
                tvAccount_.setText(acc.account == null ? "" : acc.account);
                refreshCode(acc, reveal);
            }

            /** 后台遮罩：仅显示占位字符，不暴露真实验证码。 */
            void maskCode() {
                tvCode_.setText("•• ••• •••");
                tvNextCode_.setVisibility(View.GONE);
                tvRemaining_.setText("");
                pb_.setProgress(0);
            }

            void refreshCode(OtpAccount acc, RevealQuery reveal) {
                Context ctx = itemView.getContext();
                UiPreferences uiPrefs = UiPreferences.get(ctx);
                boolean hideByPref = uiPrefs.isHideCodes();
                boolean revealed = reveal != null && reveal.isRevealed(acc.id);
                boolean shouldHide = hideByPref && !revealed;

                if (acc.isHotp()) {
                    // HOTP：显示当前计数器对应的验证码，无倒计时
                    String code = OtpGenerator.hotp(acc.secret, acc.algorithm,
                            acc.digits, acc.counter);
                    tvCode_.setText(shouldHide ? kHiddenMask : formatCode(code));
                    tvNextCode_.setVisibility(View.GONE);
                    tvRemaining_.setText(ctx.getString(R.string.hotp_tap_hint));
                    int color = androidx.core.content.ContextCompat.getColor(
                            ctx, R.color.progress_active);
                    tvRemaining_.setTextColor(color);
                    pb_.setVisibility(View.GONE);
                } else {
                    // TOTP：正常倒计时逻辑
                    pb_.setVisibility(View.VISIBLE);
                    long now = System.currentTimeMillis();
                    String code = OtpGenerator.totp(acc.secret, acc.algorithm,
                            acc.digits, acc.period, now);
                    tvCode_.setText(shouldHide ? kHiddenMask : formatCode(code));
                    int remain = OtpGenerator.remainingSeconds(acc.period, now);
                    pb_.setMax(acc.period);
                    pb_.setProgress(remain);

                    int colorRes = remain <= kWarnThresholdSec
                            ? R.color.progress_warn
                            : R.color.progress_active;
                    int color = androidx.core.content.ContextCompat.getColor(
                            ctx, colorRes);
                    tintProgress(pb_, color);
                    tvRemaining_.setTextColor(color);
                    tvRemaining_.setText(ctx.getString(
                            R.string.fmt_remaining_seconds, remain));

                    // T4：剩余 ≤5s 且偏好开启时、且不隐藏时，呈现下一码。
                    if (!shouldHide && uiPrefs.isShowNextCode()
                            && remain <= kWarnThresholdSec) {
                        long nextWindowStart =
                                ((now / 1000L) / acc.period + 1) * acc.period
                                        * 1000L;
                        String nextCode = OtpGenerator.totp(acc.secret,
                                acc.algorithm, acc.digits, acc.period,
                                nextWindowStart);
                        tvNextCode_.setText(ctx.getString(
                                R.string.fmt_next_code, formatCode(nextCode)));
                        tvNextCode_.setVisibility(View.VISIBLE);
                    } else {
                        tvNextCode_.setVisibility(View.GONE);
                    }
                }
            }

            /** 仅替换前景色，保留 layer-list 背景轨道色，避免整条进度条变色。 */
            private void tintProgress(ProgressBar bar, int color) {
                Drawable d = bar.getProgressDrawable();
                if (d instanceof LayerDrawable) {
                    Drawable progress = ((LayerDrawable) d).findDrawableByLayerId(
                            android.R.id.progress);
                    if (progress != null) {
                        progress.mutate().setColorFilter(
                                color, PorterDuff.Mode.SRC_IN);
                    }
                } else if (d != null) {
                    d.mutate().setColorFilter(color, PorterDuff.Mode.SRC_IN);
                }
            }

            private String formatCode(String code) {
                if (code.length() == 6) {
                    return code.substring(0, 3) + " " + code.substring(3);
                }
                return code;
            }
        }
    }
}
