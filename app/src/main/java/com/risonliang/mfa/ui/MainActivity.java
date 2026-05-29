/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
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

public class MainActivity extends AppCompatActivity {

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

        FloatingActionButton fab = findViewById(R.id.fab_add);
        fab.setOnClickListener(v -> showAddSheet());

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
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_scan) {
            launchScan();
            return true;
        }
        if (id == R.id.action_import_export) {
            startActivity(new Intent(this, ImportExportActivity.class));
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

    private void onItemLong(OtpAccount acc) {
        new AlertDialog.Builder(this)
                .setTitle(acc.displayLabel())
                .setMessage(R.string.confirm_delete)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.dialog_ok, (d, w) -> {
                    OtpRepository.get(this).delete(acc.id);
                    reload();
                })
                .show();
    }

    /** RecyclerView Adapter（内部类，避免拆分小文件、控制类数量）。 */
    static final class OtpAdapter
            extends RecyclerView.Adapter<OtpAdapter.VH> {

        interface ItemAction { void on(OtpAccount acc); }

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

        /** 仅刷新可见 item 的码与进度，避免整体重绘闪烁。 */
        void notifyTimeChanged() {
            notifyItemRangeChanged(0, getItemCount(), Boolean.TRUE);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position,
                                     @NonNull List<Object> payloads) {
            if (!payloads.isEmpty()) {
                h.refreshCode(data_.get(position));
            } else {
                onBindViewHolder(h, position);
            }
        }

        @Override
        public int getItemCount() {
            return data_.size();
        }

        static final class VH extends RecyclerView.ViewHolder {
            final TextView tvIssuer_;
            final TextView tvAccount_;
            final TextView tvCode_;
            final ProgressBar pb_;

            VH(View v) {
                super(v);
                tvIssuer_ = v.findViewById(R.id.tv_issuer);
                tvAccount_ = v.findViewById(R.id.tv_account);
                tvCode_ = v.findViewById(R.id.tv_code);
                pb_ = v.findViewById(R.id.pb_remaining);
            }

            void bind(OtpAccount acc) {
                tvIssuer_.setText(acc.issuer == null ? "" : acc.issuer);
                tvAccount_.setText(acc.account == null ? "" : acc.account);
                refreshCode(acc);
            }

            void refreshCode(OtpAccount acc) {
                long now = System.currentTimeMillis();
                String code = OtpGenerator.totp(acc.secret, acc.algorithm,
                        acc.digits, acc.period, now);
                tvCode_.setText(formatCode(code));
                int remain = OtpGenerator.remainingSeconds(acc.period, now);
                pb_.setMax(acc.period);
                pb_.setProgress(remain);
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
