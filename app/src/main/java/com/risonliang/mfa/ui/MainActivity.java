/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
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
import com.risonliang.mfa.R;
import com.risonliang.mfa.crypto.OtpGenerator;
import com.risonliang.mfa.data.GaMigrationDecoder;
import com.risonliang.mfa.data.OtpRepository;
import com.risonliang.mfa.data.OtpUriParser;
import com.risonliang.mfa.model.OtpAccount;
import com.risonliang.mfa.security.ClipboardCleaner;
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
     * 解码逻辑全部下沉到 {@link AlbumQrDecoder}，本方法仅做 UI 调度：
     *  - SUCCESS 走 {@link #handleScanResult(String)}；
     *  - NOT_FOUND 进入 {@link ImageEditActivity} 让用户手动调整后重识；
     *  - READ_FAILED 显示 Toast。
     */
    private void decodeQrFromImage(Uri imageUri) {
        Toast.makeText(this, R.string.decoding_image,
                Toast.LENGTH_SHORT).show();
        bgExecutor_.execute(() -> {
            AlbumQrDecoder.Result r =
                    AlbumQrDecoder.decode(getApplicationContext(), imageUri);
            runOnUiThread(() -> {
                switch (r.status) {
                    case SUCCESS:
                        handleScanResult(r.content);
                        break;
                    case NOT_FOUND:
                        Toast.makeText(this, R.string.error_qr_not_found,
                                Toast.LENGTH_SHORT).show();
                        imageEditLauncher_.launch(
                                ImageEditActivity.newIntent(this, imageUri));
                        break;
                    case READ_FAILED:
                    default:
                        Toast.makeText(this, R.string.error_image_read,
                                Toast.LENGTH_SHORT).show();
                        break;
                }
            });
        });
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
                    + AlbumQrDecoder.safeSchemePrefix(content));
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
     * 实际的视觉绘制 / 状态管理 / 排序持久化下沉到 {@link SwipeAndDragCallback}，
     * 本方法只构造 Callback 并 attach 到 RecyclerView。
     */
    private void attachSwipeToDelete() {
        SwipeAndDragCallback cb = new SwipeAndDragCallback(this, swipeHost_);
        new ItemTouchHelper(cb).attachToRecyclerView(rvAccounts_);
    }

    /**
     * SwipeAndDragCallback 的 Host 实现：把 Activity 内的列表 / Adapter /
     * 后台线程 / 仓库 暴露给 Callback，并把"用户左滑请求删除"路由到带
     * 二次确认 + Snackbar 撤销 的标准删除流程。
     */
    private final SwipeAndDragCallback.Host swipeHost_ = new SwipeAndDragCallback.Host() {
        @Override
        @NonNull
        public List<OtpAccount> getDataList() { return data_; }

        @Override
        @NonNull
        public List<OtpAccount> getAllDataList() { return allData_; }

        @Override
        public boolean isFilterActive() { return !currentQuery_.isEmpty(); }

        @Override
        @NonNull
        public RecyclerView.Adapter<?> getAdapter() { return adapter_; }

        @Override
        @NonNull
        public java.util.concurrent.Executor getBackgroundExecutor() {
            return bgExecutor_;
        }

        @Override
        @NonNull
        public OtpRepository getRepository() {
            return OtpRepository.get(MainActivity.this);
        }

        @Override
        public void onItemSwipedToDelete(@NonNull OtpAccount acc, int adapterPosition) {
            // 与原匿名内部类等价的二次确认逻辑：取消 / 关闭对话框时把被滑开的
            // 卡片复位（notifyItemChanged），确认删除则走 软删除 + Snackbar 撤销。
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle(acc.displayLabel())
                    .setMessage(R.string.confirm_delete)
                    .setNegativeButton(R.string.dialog_cancel,
                            (d, w) -> adapter_.notifyItemChanged(adapterPosition))
                    .setOnCancelListener(
                            d -> adapter_.notifyItemChanged(adapterPosition))
                    .setPositiveButton(R.string.dialog_ok, (d, w) -> {
                        OtpRepository.get(MainActivity.this).delete(acc.id);
                        reload();
                        showUndoDeleteSnackbar(acc);
                    })
                    .show();
        }
    };
}
