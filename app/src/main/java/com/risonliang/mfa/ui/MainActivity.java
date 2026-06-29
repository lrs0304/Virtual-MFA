/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
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
import android.widget.EditText;
import android.widget.ImageButton;
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
import com.google.android.material.snackbar.Snackbar;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.risonliang.mfa.R;
import com.risonliang.mfa.crypto.OtpGenerator;
import com.risonliang.mfa.data.GaMigrationDecoder;
import com.risonliang.mfa.data.OtpRepository;
import com.risonliang.mfa.data.OtpUriParser;
import com.risonliang.mfa.model.OtpAccount;
import com.risonliang.mfa.security.ClipboardCleaner;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    /** 图片编辑页（ImageEditActivity）回调：用户调整后识别成功的内容。 */
    private ActivityResultLauncher<Intent> imageEditLauncher_;
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

        // 图片编辑页回调：用户在 ImageEditActivity 内调整后识别成功，
        // 内容通过 EXTRA_RESULT 回传，按和扫码相同的路径处理。
        imageEditLauncher_ = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK
                            && result.getData() != null) {
                        String content = result.getData().getStringExtra(
                                ImageEditActivity.EXTRA_RESULT);
                        if (content != null && !content.isEmpty()) {
                            handleScanResult(content);
                        }
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
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
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

    /**
     * 展示删除撤销 Snackbar：10 秒内点击"撤销"则把账号重新插入回库。
     *
     * 注意：账号 id 在新插入时会由 SQLite 重新分配，sortOrder 会回到当前末尾，
     * 与原条目的相对顺序可能略有差异；不持久化"原 sortOrder"是为了保持
     * Repository 层结构尽可能简单，且对用户体验没有可感知影响。
     */
    private void showUndoDeleteSnackbar(OtpAccount snapshot) {
        if (snapshot == null) {
            return;
        }
        View root = findViewById(R.id.root_main);
        if (root == null) {
            return;
        }
        String title = snapshot.displayLabel();
        Snackbar bar = Snackbar.make(root,
                getString(R.string.delete_done_with_undo, title),
                10_000);
        bar.setAction(R.string.action_undo, v -> {
            try {
                // id 字段重置：让 SQLite 重新分配，避免主键冲突。
                snapshot.id = 0;
                OtpRepository.get(this).insert(snapshot);
                reload();
            } catch (Exception e) {
                Toast.makeText(this,
                        getString(R.string.error_save_failed, e.getMessage()),
                        Toast.LENGTH_SHORT).show();
            }
        });
        bar.show();
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
                    // ML Kit 首轮未识别 → 进入图片编辑页让用户手动调整后重识。
                    // 不再仅一句 Toast 让用户卡死。
                    Toast.makeText(this, R.string.error_qr_not_found,
                            Toast.LENGTH_SHORT).show();
                    imageEditLauncher_.launch(
                            ImageEditActivity.newIntent(this, imageUri));
                } else {
                    handleScanResult(decoded);
                }
            });
        });
    }

    /**
     * 后台线程：读取图片、降采样、直接调用 ML Kit 解码二维码。
     *
     * 实验分支（experiment/album-mlkit-only）：去掉 ZXing 3 档二值化的快速路径，
     * 相册解码直接走 ML Kit bundled 模型，验证以下假设：
     *   1. ML Kit 自身对屏摄/水印噪点截图的鲁棒性已足够覆盖绝大多数 case；
     *   2. ZXing 三档快速路径在多数情况下并非"快速"，反而拖慢失败 case 的总耗时；
     *   3. 单一管线更简单、可维护性更高。
     *
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
            // inSampleSize 阈值：长边 > 2048 才降采样。
            //
            // 为什么不是更大的阈值（如 4096）：在华为高分屏典型截图（2400-3060
            // 边长）上实测——4096 阈值下 sampleSize=1，原图直接喂 ML Kit 时
            // 偶现识别失败；ImageEditActivity 内部用 2048 阈值（等价 1/2 降采
            // 样），同一张图"什么都不点直接重新识别"反而能命中。
            //
            // 原理：ML Kit 的 QR finder pattern 检测对"每个 module ≈ 4-8 像
            // 素"是甜点区间，原图上 module 往往 12-20 像素，且摩尔纹/JPEG 高
            // 频噪声未经低通滤波，反而干扰检测。一次 1/2 sampleSize 做了一次
            // box-filter 低通，噪点被均化，module 边缘更干净。
            //
            // 注：这只是单尺度修正，覆盖典型华为机型尺寸。后续若再撞到不同尺寸
            // 失败 case，应升级为多尺度兜底（2048 / 原图 / 1024 三档）。
            int sampleSize = 1;
            while (maxDim / sampleSize > 2048) {
                sampleSize *= 2;
            }
            Log.d(kLogTag, "image bounds=" + origW + "x" + origH
                    + ", mime=" + opts.outMimeType
                    + ", sampleSize=" + sampleSize);

            String content = decodeWithMlKit(imageUri, sampleSize);
            if (content == null) {
                Log.w(kLogTag,
                        "QRCode not found by ML Kit, origSize="
                                + origW + "x" + origH
                                + ", mime=" + opts.outMimeType
                                + ", sampleSize=" + sampleSize);
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
     * ML Kit 解码：本分支唯一相册解码路径。
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
            // 不再仅 Toast 草草了事：把原文塞进预览页让用户看到全文 + 一键复制。
            // 这是"识别到了二维码但内容不是 MFA 规范"的兜底入口。
            startActivity(QrContentPreviewActivity.newIntent(this, content));
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

    /**
     * 长按弹出条目操作菜单：编辑 / 置顶（或取消置顶）/ 删除。
     * 删除入口与左滑删除走同一个 confirm + Snackbar 撤销路径，避免不一致。
     */
    private void onItemLong(OtpAccount acc) {
        CharSequence[] items = new CharSequence[]{
                getString(R.string.action_edit),
                getString(acc.favorite
                        ? R.string.action_unpin
                        : R.string.action_pin),
                getString(R.string.action_delete)
        };
        new AlertDialog.Builder(this)
                .setTitle(acc.displayLabel())
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        showEditDialog(acc);
                    } else if (which == 1) {
                        toggleFavorite(acc);
                    } else if (which == 2) {
                        confirmDelete(acc);
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    /** 切换收藏置顶状态：仅写一列 + 重新加载列表，不影响 secret 路径。 */
    private void toggleFavorite(OtpAccount acc) {
        boolean target = !acc.favorite;
        try {
            OtpRepository.get(this).setFavorite(acc.id, target);
            acc.favorite = target;
            reload();
        } catch (Exception e) {
            Toast.makeText(this,
                    getString(R.string.error_save_failed, e.getMessage()),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** 提取出来的"二次确认 + 软删除 + Snackbar 撤销"流程，长按和滑动共用。 */
    private void confirmDelete(OtpAccount acc) {
        new AlertDialog.Builder(this)
                .setTitle(acc.displayLabel())
                .setMessage(R.string.confirm_delete)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.dialog_ok, (d, w) -> {
                    OtpRepository.get(this).delete(acc.id);
                    reload();
                    showUndoDeleteSnackbar(acc);
                })
                .show();
    }

    /** 长按改为编辑：仅允许修改 issuer / account，不动 secret。 */
    private void showEditDialog(OtpAccount acc) {
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

    /**
     * 为列表绑定拖拽排序 + 左滑删除。
     * 拖拽：长按 item 在豆点区可上下拖动调整顺序，抬手时事务批量写回 sortOrder。
     * 为避免搜索过滤态下 data_ 只是 allData_ 的子集造成原序错乱，
     * 仅在无搜索词时启用拖动。
     */
    private void attachSwipeToDelete() {
        ItemTouchHelper.Callback cb = new ItemTouchHelper.Callback() {
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
            /** 本次拖拽是否发生过位置变化，决定 clearView 是否需要落库。 */
            private boolean dragMoved_;

            {
                bgPaint_.setColor(androidx.core.content.ContextCompat.getColor(
                        MainActivity.this, R.color.progress_warn));
                bgPaint_.setStyle(Paint.Style.FILL);
                textPaint_.setColor(Color.WHITE);
                textPaint_.setTextSize(textPx_);
                textPaint_.setTypeface(Typeface.DEFAULT_BOLD);
            }

            @Override
            public int getMovementFlags(@NonNull RecyclerView rv,
                                        @NonNull RecyclerView.ViewHolder vh) {
                // 搜索过滤态下仅保留左滑删除；仅在全量列表下才启用拖拽。
                int swipe = ItemTouchHelper.LEFT;
                int drag = currentQuery_.isEmpty()
                        ? (ItemTouchHelper.UP | ItemTouchHelper.DOWN) : 0;
                return makeMovementFlags(drag, swipe);
            }

            @Override
            public boolean isLongPressDragEnabled() {
                // 仅全量列表下才允许长按发起拖动，避免过滤态详顺序错乱。
                return currentQuery_.isEmpty();
            }

            @Override
            public boolean isItemViewSwipeEnabled() {
                return true;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView rv,
                                  @NonNull RecyclerView.ViewHolder vh,
                                  @NonNull RecyclerView.ViewHolder target) {
                int from = vh.getBindingAdapterPosition();
                int to = target.getBindingAdapterPosition();
                if (from < 0 || to < 0
                        || from >= data_.size() || to >= data_.size()) {
                    return false;
                }
                // 收藏分区隔离：仅允许在同一 favorite 状态内拖动，避免出现
                // "把一个非收藏拖到收藏区然后顺序错乱"的视觉/语义不一致。
                // 用户想跨区移动应通过长按菜单"取消置顶"先改变分区。
                if (data_.get(from).favorite != data_.get(to).favorite) {
                    return false;
                }
                // 同步调整 data_ 与 allData_（两者在未过滤时为同顺序，但不是同一个
                // List 引用），其中主要列表是 data_；ClearView 时按 data_ 顺序写回 DB。
                java.util.Collections.swap(data_, from, to);
                if (from < allData_.size() && to < allData_.size()) {
                    java.util.Collections.swap(allData_, from, to);
                }
                adapter_.notifyItemMoved(from, to);
                dragMoved_ = true;
                return true;
            }

            @Override
            public void onSelectedChanged(
                    @androidx.annotation.Nullable RecyclerView.ViewHolder vh,
                    int actionState) {
                super.onSelectedChanged(vh, actionState);
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG
                        && vh != null) {
                    // 拖拽中轻微隐去阴影提示“拿起”状态；在 clearView 中复原。
                    vh.itemView.setAlpha(0.85f);
                    vh.itemView.setScaleX(1.02f);
                    vh.itemView.setScaleY(1.02f);
                }
            }

            @Override
            public void clearView(@NonNull RecyclerView rv,
                                  @NonNull RecyclerView.ViewHolder vh) {
                super.clearView(rv, vh);
                vh.itemView.setAlpha(1f);
                vh.itemView.setScaleX(1f);
                vh.itemView.setScaleY(1f);
                if (!dragMoved_) {
                    return;
                }
                dragMoved_ = false;
                // 抓一份 id 快照后用后台线程事务批量更新，避免阻塞主线程。
                long[] orderedIds = new long[data_.size()];
                for (int i = 0; i < data_.size(); i++) {
                    orderedIds[i] = data_.get(i).id;
                }
                bgExecutor_.execute(() -> {
                    try {
                        OtpRepository.get(MainActivity.this)
                                .updateSortOrder(orderedIds);
                    } catch (Exception e) {
                        Log.w(kLogTag, "persist sortOrder failed", e);
                    }
                });
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
                            // 软删除：先记住完整 OtpAccount（含 secret 明文）以便撤销，
                            // 再从数据库中删除。Snackbar 10s 内点击"撤销"则重新插入。
                            OtpAccount snapshot = acc;
                            OtpRepository.get(MainActivity.this)
                                    .delete(snapshot.id);
                            reload();
                            showUndoDeleteSnackbar(snapshot);
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
}
