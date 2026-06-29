/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.ui;

import android.app.Activity;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.snackbar.Snackbar;
import com.risonliang.mfa.R;
import com.risonliang.mfa.data.OtpRepository;
import com.risonliang.mfa.model.OtpAccount;

/**
 * 把"账号条目"的高频交互（长按菜单 / 编辑 / 收藏置顶 / 二次确认删除 /
 * 撤销 Snackbar）从 MainActivity 抽离出来，避免 Activity 同时承担
 * 列表渲染、手势调度、对话框组织等多重职责。
 *
 * 持有一个 {@link Activity} 与一个用于 Snackbar 的根 View，所有 UI 操作
 * 必须在主线程触发。当账号数据被修改后会通过 {@link Runnable} 通知调用方
 * 进行 reload，避免本类直接耦合 Activity 内的 reload() 私有方法。
 */
final class AccountActions {

    private final Activity activity_;
    private final View snackbarRoot_;
    private final Runnable onDataChanged_;

    /**
     * @param activity        宿主 Activity，用于弹对话框、构造 Toast、读 string
     * @param snackbarRoot    Snackbar 挂靠的视图，通常是根 CoordinatorLayout
     * @param onDataChanged   数据落库后通知调用方刷新列表
     */
    AccountActions(@NonNull Activity activity,
                   @Nullable View snackbarRoot,
                   @NonNull Runnable onDataChanged) {
        activity_ = activity;
        snackbarRoot_ = snackbarRoot;
        onDataChanged_ = onDataChanged;
    }

    /**
     * 长按弹出条目操作菜单：编辑 / 置顶（或取消置顶）/ 删除。
     * 删除入口与左滑删除走同一个二次确认 + 撤销 Snackbar 路径，避免不一致。
     */
    void showLongPressMenu(@NonNull OtpAccount acc) {
        CharSequence[] items = new CharSequence[]{
                activity_.getString(R.string.action_edit),
                activity_.getString(acc.favorite
                        ? R.string.action_unpin
                        : R.string.action_pin),
                activity_.getString(R.string.action_delete)
        };
        new AlertDialog.Builder(activity_)
                .setTitle(acc.displayLabel())
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        showEditDialog(acc);
                    } else if (which == 1) {
                        toggleFavorite(acc);
                    } else if (which == 2) {
                        promptDelete(acc);
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    /** 切换收藏置顶状态：仅写一列 + 重新加载列表，不影响 secret 路径。 */
    void toggleFavorite(@NonNull OtpAccount acc) {
        boolean target = !acc.favorite;
        try {
            OtpRepository.get(activity_).setFavorite(acc.id, target);
            acc.favorite = target;
            onDataChanged_.run();
        } catch (Exception e) {
            Toast.makeText(activity_,
                    activity_.getString(R.string.error_save_failed, e.getMessage()),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** "二次确认 + 软删除 + Snackbar 撤销"流程，长按和滑动均可路由到此。 */
    void promptDelete(@NonNull OtpAccount acc) {
        new AlertDialog.Builder(activity_)
                .setTitle(acc.displayLabel())
                .setMessage(R.string.confirm_delete)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.dialog_ok, (d, w) -> {
                    OtpRepository.get(activity_).delete(acc.id);
                    onDataChanged_.run();
                    showUndoSnackbar(acc);
                })
                .show();
    }

    /**
     * 展示删除撤销 Snackbar：10 秒内点击"撤销"则把账号重新插入回库。
     *
     * 注意：账号 id 在新插入时会由 SQLite 重新分配，sortOrder 会回到当前末尾，
     * 与原条目的相对顺序可能略有差异；不持久化"原 sortOrder"是为了保持
     * Repository 层结构尽可能简单，且对用户体验没有可感知影响。
     */
    void showUndoSnackbar(@Nullable OtpAccount snapshot) {
        if (snapshot == null || snackbarRoot_ == null) {
            return;
        }
        String title = snapshot.displayLabel();
        Snackbar bar = Snackbar.make(snackbarRoot_,
                activity_.getString(R.string.delete_done_with_undo, title),
                10_000);
        bar.setAction(R.string.action_undo, v -> {
            try {
                // id 字段重置：让 SQLite 重新分配，避免主键冲突。
                snapshot.id = 0;
                OtpRepository.get(activity_).insert(snapshot);
                onDataChanged_.run();
            } catch (Exception e) {
                Toast.makeText(activity_,
                        activity_.getString(R.string.error_save_failed, e.getMessage()),
                        Toast.LENGTH_SHORT).show();
            }
        });
        bar.show();
    }

    /** 编辑对话框：仅允许修改 issuer / account，不动 secret。 */
    private void showEditDialog(@NonNull OtpAccount acc) {
        View dialogView = LayoutInflater.from(activity_)
                .inflate(R.layout.dialog_edit_account, null, false);
        final EditText etIssuer = dialogView.findViewById(R.id.et_edit_issuer);
        final EditText etAccount = dialogView.findViewById(R.id.et_edit_account);
        etIssuer.setText(acc.issuer == null ? "" : acc.issuer);
        etAccount.setText(acc.account == null ? "" : acc.account);

        new AlertDialog.Builder(activity_)
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
                        Toast.makeText(activity_, R.string.error_required,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    acc.issuer = newIssuer;
                    acc.account = newAccount;
                    try {
                        OtpRepository.get(activity_).update(acc);
                        onDataChanged_.run();
                    } catch (Exception e) {
                        Toast.makeText(activity_,
                                activity_.getString(R.string.error_save_failed, e.getMessage()),
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }
}
