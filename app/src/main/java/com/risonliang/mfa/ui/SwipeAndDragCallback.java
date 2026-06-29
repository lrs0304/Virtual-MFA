/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import com.risonliang.mfa.R;
import com.risonliang.mfa.data.OtpRepository;
import com.risonliang.mfa.model.OtpAccount;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 列表条目的"长按拖拽 + 左滑删除"组合手势 Callback。
 *
 * 设计意图：
 *  - 把这套绘制 / 状态 / 事件回调从 MainActivity 抽出，使主 Activity 只负责
 *    "构造 Callback 并 attach"，而不再承担约 200 行的视觉与排序细节；
 *  - 通过 {@link Host} 接口与 Activity 解耦，Callback 不直接持有 Activity 引用，
 *    便于将来给排序 / 软删除单测加 fake Host；
 *  - 行为与原 MainActivity 中的匿名内部类一致：
 *      · 搜索过滤态下仅保留左滑删除，禁止拖拽以避免子集顺序污染全量；
 *      · 拖拽仅允许在同一 favorite 分区内移动，跨分区由"长按菜单 -&gt; 取消置顶"完成；
 *      · 抬手触发时事务批量写回 sortOrder，不阻塞主线程。
 */
final class SwipeAndDragCallback extends ItemTouchHelper.Callback {

    private static final String kLogTag = "MFA-Swipe";

    /** Activity 侧需要为 Callback 暴露的能力。 */
    interface Host {
        /** 当前可见列表（受过滤后的子集），与 Adapter 的数据源一致。 */
        @NonNull List<OtpAccount> getDataList();
        /** 全量缓存，未过滤时与 dataList 同顺序但是不同 List 引用。 */
        @NonNull List<OtpAccount> getAllDataList();
        /** 当前是否处于搜索过滤状态。 */
        boolean isFilterActive();
        /** 用于刷新 UI 的 Adapter 句柄。 */
        @NonNull RecyclerView.Adapter<?> getAdapter();
        /** 后台单线程，用于把排序写入 SQLite。 */
        @NonNull Executor getBackgroundExecutor();
        /** Repository 句柄；放在 Host 内便于 Mock。 */
        @NonNull OtpRepository getRepository();
        /** 用户在卡片上左滑触发删除，由 Host 决定是否二次确认 / 软删除。 */
        void onItemSwipedToDelete(@NonNull OtpAccount acc, int adapterPosition);
    }

    private final Host host_;

    private final Paint bgPaint_ = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint_ = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect textBounds_ = new Rect();
    private final RectF rectF_ = new RectF();
    private final String label_;

    private final float textPx_;
    private final int padPx_;
    private final float cornerPx_;
    private final int marginHPx_;
    private final int marginVPx_;

    /** 本次拖拽是否发生过位置变化，决定 clearView 是否需要落库。 */
    private boolean dragMoved_;

    SwipeAndDragCallback(@NonNull Context ctx, @NonNull Host host) {
        host_ = host;
        label_ = ctx.getString(R.string.action_delete);

        textPx_ = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 16f,
                ctx.getResources().getDisplayMetrics());
        padPx_ = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 24f,
                ctx.getResources().getDisplayMetrics());
        // 与 item_otp.xml 中 CardView 的视觉参数保持一致。
        cornerPx_ = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 12f,
                ctx.getResources().getDisplayMetrics());
        marginHPx_ = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 12f,
                ctx.getResources().getDisplayMetrics());
        marginVPx_ = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 6f,
                ctx.getResources().getDisplayMetrics());

        bgPaint_.setColor(androidx.core.content.ContextCompat.getColor(
                ctx, R.color.progress_warn));
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
        int drag = host_.isFilterActive()
                ? 0 : (ItemTouchHelper.UP | ItemTouchHelper.DOWN);
        return makeMovementFlags(drag, swipe);
    }

    @Override
    public boolean isLongPressDragEnabled() {
        // 仅全量列表下才允许长按发起拖动，避免过滤态下顺序错乱。
        return !host_.isFilterActive();
    }

    @Override
    public boolean isItemViewSwipeEnabled() {
        return true;
    }

    @Override
    public boolean onMove(@NonNull RecyclerView rv,
                          @NonNull RecyclerView.ViewHolder vh,
                          @NonNull RecyclerView.ViewHolder target) {
        List<OtpAccount> data = host_.getDataList();
        List<OtpAccount> allData = host_.getAllDataList();
        int from = vh.getBindingAdapterPosition();
        int to = target.getBindingAdapterPosition();
        if (from < 0 || to < 0 || from >= data.size() || to >= data.size()) {
            return false;
        }
        // 收藏分区隔离：仅允许在同一 favorite 状态内拖动，避免出现
        // "把一个非收藏拖到收藏区然后顺序错乱"的视觉/语义不一致。
        // 用户想跨区移动应通过长按菜单"取消置顶"先改变分区。
        if (data.get(from).favorite != data.get(to).favorite) {
            return false;
        }
        // 同步调整 data 与 allData（两者在未过滤时为同顺序，但不是同一个
        // List 引用）。clearView 时按 data 顺序写回 DB。
        Collections.swap(data, from, to);
        if (from < allData.size() && to < allData.size()) {
            Collections.swap(allData, from, to);
        }
        host_.getAdapter().notifyItemMoved(from, to);
        dragMoved_ = true;
        return true;
    }

    @Override
    public void onSelectedChanged(@Nullable RecyclerView.ViewHolder vh, int actionState) {
        super.onSelectedChanged(vh, actionState);
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && vh != null) {
            // 拖拽中轻微隐去阴影提示"拿起"状态；在 clearView 中复原。
            vh.itemView.setAlpha(0.85f);
            vh.itemView.setScaleX(1.02f);
            vh.itemView.setScaleY(1.02f);
        }
    }

    @Override
    public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
        super.clearView(rv, vh);
        vh.itemView.setAlpha(1f);
        vh.itemView.setScaleX(1f);
        vh.itemView.setScaleY(1f);
        if (!dragMoved_) {
            return;
        }
        dragMoved_ = false;
        // 抓一份 id 快照后用后台线程事务批量更新，避免阻塞主线程。
        List<OtpAccount> data = host_.getDataList();
        long[] orderedIds = new long[data.size()];
        for (int i = 0; i < data.size(); i++) {
            orderedIds[i] = data.get(i).id;
        }
        host_.getBackgroundExecutor().execute(() -> {
            try {
                host_.getRepository().updateSortOrder(orderedIds);
            } catch (Exception e) {
                Log.w(kLogTag, "persist sortOrder failed", e);
            }
        });
    }

    @Override
    public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
        int pos = vh.getBindingAdapterPosition();
        List<OtpAccount> data = host_.getDataList();
        if (pos < 0 || pos >= data.size()) {
            return;
        }
        host_.onItemSwipedToDelete(data.get(pos), pos);
    }

    @Override
    public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView rv,
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

            textPaint_.getTextBounds(label_, 0, label_.length(), textBounds_);
            // 仅在背景宽度足以容纳文字时绘制，避免越界压在卡片上
            float bgWidth = right - left;
            float textWidth = textBounds_.width();
            if (bgWidth >= textWidth + padPx_) {
                float ty = top + (bottom - top + textBounds_.height()) / 2f;
                float tx = right - padPx_ - textWidth;
                c.drawText(label_, tx, ty, textPaint_);
            }
        }
        super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive);
    }
}
