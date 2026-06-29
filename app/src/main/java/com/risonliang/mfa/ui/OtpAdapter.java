/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.ui;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.risonliang.mfa.R;
import com.risonliang.mfa.crypto.OtpGenerator;
import com.risonliang.mfa.model.OtpAccount;
import java.util.List;

/**
 * OTP 列表适配器。
 *
 * 职责：
 *  - 把 {@link OtpAccount} 列表绑定到 item_otp.xml 视图；
 *  - 通过 {@link RevealQuery} 询问外部"某账号此刻是否处于临时显形窗口"，
 *    实现"默认遮罩 / 点按 5s 内显形"的行为；
 *  - 通过 {@link ItemAction} 把点击 / 长按事件回传给 Activity 处理；
 *  - 后台时支持 {@link #maskAllCodes()} 一键脱敏，配合 FLAG_SECURE 防肩窥。
 *
 * 之前嵌在 MainActivity 中作为 static 内部类，文件膨胀到 1200+ 行难以阅读，
 * 这里独立成一个 package-private 文件，与 MainActivity 解耦但不暴露给外部包。
 */
final class OtpAdapter extends RecyclerView.Adapter<OtpAdapter.VH> {

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

        final ImageView ivIcon_;
        final TextView tvIssuer_;
        final TextView tvAccount_;
        final TextView tvCode_;
        final TextView tvNextCode_;
        final TextView tvRemaining_;
        final ProgressBar pb_;

        VH(View v) {
            super(v);
            ivIcon_ = v.findViewById(R.id.iv_issuer_icon);
            tvIssuer_ = v.findViewById(R.id.tv_issuer);
            tvAccount_ = v.findViewById(R.id.tv_account);
            tvCode_ = v.findViewById(R.id.tv_code);
            tvNextCode_ = v.findViewById(R.id.tv_next_code);
            tvRemaining_ = v.findViewById(R.id.tv_remaining);
            pb_ = v.findViewById(R.id.pb_remaining);
        }

        void bind(OtpAccount acc, RevealQuery reveal) {
            String issuerText = acc.issuer == null ? "" : acc.issuer;
            // T9：收藏置顶在 issuer 前加 ★，零新增资源；不影响搜索匹配语义，
            // 因为 matches() 走的是 acc.issuer 字段而非这里的渲染文本。
            if (acc.favorite) {
                issuerText = "★ " + issuerText;
            }
            tvIssuer_.setText(issuerText);
            tvAccount_.setText(acc.account == null ? "" : acc.account);
            if (ivIcon_ != null) {
                ivIcon_.setImageDrawable(
                        new IssuerIconDrawable(acc.issuer, acc.account));
            }
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
