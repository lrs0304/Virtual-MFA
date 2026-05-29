/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
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
import com.risonliang.mfa.R;
import com.risonliang.mfa.crypto.OtpGenerator;
import com.risonliang.mfa.data.OtpRepository;
import com.risonliang.mfa.data.OtpUriParser;
import com.risonliang.mfa.model.OtpAccount;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends BaseSecureActivity {

    private RecyclerView rvAccounts_;
    private TextView tvEmpty_;
    private OtpAdapter adapter_;
    private final List<OtpAccount> data_ = new ArrayList<>();
    private final Handler tickHandler_ = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<Intent> scanLauncher_;

    private final Runnable tick_ = new Runnable() {
        @Override
        public void run() {
            if (adapter_ != null) {
                adapter_.notifyTimeChanged();
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
        rvAccounts_.setLayoutManager(new LinearLayoutManager(this));
        adapter_ = new OtpAdapter(data_, this::onItemClick, this::onItemLong);
        rvAccounts_.setAdapter(adapter_);
        attachSwipeToDelete();

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
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
        tickHandler_.post(tick_);
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
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_import_export) {
            startActivity(new Intent(this, ImportExportActivity.class));
            return true;
        }
        if (id == R.id.action_change_pin) {
            Intent it = new Intent(this, LockActivity.class);
            it.putExtra(LockActivity.EXTRA_MODE, LockActivity.MODE_CHANGE);
            startActivity(it);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void reload() {
        try {
            data_.clear();
            data_.addAll(OtpRepository.get(this).listAll());
            adapter_.notifyDataSetChanged();
            tvEmpty_.setVisibility(data_.isEmpty() ? View.VISIBLE : View.GONE);
        } catch (Exception e) {
            Toast.makeText(this, "加载失败：" + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void showAddSheet() {
        new AlertDialog.Builder(this)
                .setTitle("添加账号")
                .setItems(new CharSequence[]{
                                getString(R.string.action_scan),
                                getString(R.string.action_manual)},
                        (d, which) -> {
                            if (which == 0) {
                                launchScan();
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

    private void handleScanResult(String content) {
        OtpAccount acc = OtpUriParser.parse(content);
        if (acc == null) {
            Toast.makeText(this, R.string.error_qr_invalid,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            OtpRepository.get(this).insert(acc);
            reload();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败：" + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void onItemClick(OtpAccount acc) {
        long now = System.currentTimeMillis();
        String code = OtpGenerator.totp(acc.secret, acc.algorithm,
                acc.digits, acc.period, now);
        ClipboardManager cm = (ClipboardManager) getSystemService(
                Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("otp", code));
            Toast.makeText(this, R.string.copied,
                    Toast.LENGTH_SHORT).show();
        }
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
                        Toast.makeText(this, "保存失败：" + e.getMessage(),
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

        /** payload 标识：脱敏遮罩刷新。 */
        private static final Object kPayloadMask = new Object();

        private final List<OtpAccount> data_;
        private final ItemAction onClick_;
        private final ItemAction onLong_;

        OtpAdapter(List<OtpAccount> data, ItemAction onClick,
                   ItemAction onLong) {
            data_ = data;
            onClick_ = onClick;
            onLong_ = onLong;
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
            h.bind(acc);
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
                    h.refreshCode(data_.get(position));
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

            final TextView tvIssuer_;
            final TextView tvAccount_;
            final TextView tvCode_;
            final TextView tvRemaining_;
            final ProgressBar pb_;

            VH(View v) {
                super(v);
                tvIssuer_ = v.findViewById(R.id.tv_issuer);
                tvAccount_ = v.findViewById(R.id.tv_account);
                tvCode_ = v.findViewById(R.id.tv_code);
                tvRemaining_ = v.findViewById(R.id.tv_remaining);
                pb_ = v.findViewById(R.id.pb_remaining);
            }

            void bind(OtpAccount acc) {
                tvIssuer_.setText(acc.issuer == null ? "" : acc.issuer);
                tvAccount_.setText(acc.account == null ? "" : acc.account);
                refreshCode(acc);
            }

            /** 后台遮罩：仅显示占位字符，不暴露真实验证码。 */
            void maskCode() {
                tvCode_.setText("•• ••• •••");
                tvRemaining_.setText("");
                pb_.setProgress(0);
            }

            void refreshCode(OtpAccount acc) {
                long now = System.currentTimeMillis();
                String code = OtpGenerator.totp(acc.secret, acc.algorithm,
                        acc.digits, acc.period, now);
                tvCode_.setText(formatCode(code));
                int remain = OtpGenerator.remainingSeconds(acc.period, now);
                pb_.setMax(acc.period);
                pb_.setProgress(remain);

                int colorRes = remain <= kWarnThresholdSec
                        ? R.color.progress_warn
                        : R.color.progress_active;
                int color = androidx.core.content.ContextCompat.getColor(
                        itemView.getContext(), colorRes);
                tintProgress(pb_, color);
                tvRemaining_.setTextColor(color);
                tvRemaining_.setText(itemView.getContext().getString(
                        R.string.fmt_remaining_seconds, remain));
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
