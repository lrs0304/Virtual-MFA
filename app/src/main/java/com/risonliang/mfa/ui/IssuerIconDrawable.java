/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;

/**
 * 基于 issuer 文本的"首字母色块"图标。
 *
 * 设计要点：
 *  1. 颜色稳定：由 label 的哈希决定，相同 issuer 永远是同一种颜色；
 *  2. 视觉一致：HSV 中 S/V 固定，仅旋转 H，避免出现刺眼或灰暗色；
 *  3. 零资源：纯代码绘制，不引入 PNG / SVG，不增加 APK 体积；
 *  4. 离线/可重入：不联网获取真实品牌图标，与"零联网权限"基线一致。
 */
public final class IssuerIconDrawable extends Drawable {

    private static final float kCornerRatio = 0.22f;
    private static final float kSaturation = 0.62f;
    private static final float kValue = 0.78f;

    private final Paint bgPaint_ = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint_ = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect textBounds_ = new Rect();
    private final RectF rectF_ = new RectF();
    private final String letter_;

    public IssuerIconDrawable(String issuer, String account) {
        letter_ = pickLetter(issuer, account);
        int color = colorFor(issuer != null && !issuer.isEmpty()
                ? issuer
                : (account == null ? "" : account));
        bgPaint_.setColor(color);
        bgPaint_.setStyle(Paint.Style.FILL);
        textPaint_.setColor(Color.WHITE);
        textPaint_.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint_.setTextAlign(Paint.Align.CENTER);
    }

    /** 选首个可见字符（非空白）；都没有则用占位符"·"。 */
    private static String pickLetter(String issuer, String account) {
        String src = (issuer != null && !issuer.trim().isEmpty())
                ? issuer.trim()
                : (account == null ? "" : account.trim());
        if (src.isEmpty()) {
            return "·";
        }
        int cp = src.codePointAt(0);
        return new String(Character.toChars(cp))
                .toUpperCase(java.util.Locale.ROOT);
    }

    /** 由 label 派生稳定色相，再换算为 RGB。 */
    private static int colorFor(String label) {
        // 32-bit FNV-1a 风格的轻量哈希；不需要密码学强度，只需稳定。
        int h = 0x811c9dc5;
        for (int i = 0; i < label.length(); i++) {
            h ^= label.charAt(i);
            h *= 0x01000193;
        }
        float hue = ((h & 0x7fffffff) % 360);
        float[] hsv = {hue, kSaturation, kValue};
        return Color.HSVToColor(hsv);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (b.width() == 0 || b.height() == 0) {
            return;
        }
        rectF_.set(b);
        float corner = Math.min(b.width(), b.height()) * kCornerRatio;
        canvas.drawRoundRect(rectF_, corner, corner, bgPaint_);

        // 自适应字号：用 0.55 * min(w,h) 作为初始大小，再按文字 bounds 微调。
        float baseSize = Math.min(b.width(), b.height()) * 0.55f;
        textPaint_.setTextSize(baseSize);
        textPaint_.getTextBounds(letter_, 0, letter_.length(), textBounds_);
        float cx = b.exactCenterX();
        float cy = b.exactCenterY()
                - textBounds_.exactCenterY();
        canvas.drawText(letter_, cx, cy, textPaint_);
    }

    @Override
    public void setAlpha(int alpha) {
        bgPaint_.setAlpha(alpha);
        textPaint_.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        bgPaint_.setColorFilter(colorFilter);
        textPaint_.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
