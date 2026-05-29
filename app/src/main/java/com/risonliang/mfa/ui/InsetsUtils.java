/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.ui;

import android.view.View;
import android.view.ViewGroup;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * 安全区适配工具：
 * 应用 edge-to-edge 主题后，需要在内容视图上手动消费 system bar / display cutout / IME 的 insets，
 * 否则内容会被状态栏、刘海、底部手势条遮挡。
 */
final class InsetsUtils {

    private InsetsUtils() {}

    /** 顶部 + 左右 + 底部 全部应用为 padding（通常用于带 Toolbar 的根布局）。 */
    static void applyAllSidesAsPadding(View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, wic) -> {
            Insets bars = wic.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout()
                            | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    /** 仅左右 + 底部应用为 padding，顶部由上层 AppBar 自行处理。 */
    static void applySidesAndBottomAsPadding(View root) {
        final int paddingTop = root.getPaddingTop();
        final int paddingLeft = root.getPaddingLeft();
        final int paddingRight = root.getPaddingRight();
        final int paddingBottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, wic) -> {
            Insets bars = wic.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout()
                            | WindowInsetsCompat.Type.ime());
            v.setPadding(paddingLeft + bars.left,
                    paddingTop,
                    paddingRight + bars.right,
                    paddingBottom + bars.bottom);
            return wic;
        });
    }

    /** 仅左右 + 底部应用为 margin（适用于浮动控件如 FAB）。 */
    static void applySidesAndBottomAsMargin(View view,
                                            int baseLeftDp,
                                            int baseRightDp,
                                            int baseBottomDp) {
        final float density = view.getResources().getDisplayMetrics().density;
        final int baseLeft = (int) (baseLeftDp * density);
        final int baseRight = (int) (baseRightDp * density);
        final int baseBottom = (int) (baseBottomDp * density);
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, wic) -> {
            Insets bars = wic.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp =
                        (ViewGroup.MarginLayoutParams) lp;
                mlp.leftMargin = baseLeft + bars.left;
                mlp.rightMargin = baseRight + bars.right;
                mlp.bottomMargin = baseBottom + bars.bottom;
                v.setLayoutParams(mlp);
            }
            return wic;
        });
    }
}
