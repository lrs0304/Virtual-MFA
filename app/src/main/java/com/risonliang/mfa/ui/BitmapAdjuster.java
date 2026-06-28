package com.risonliang.mfa.ui;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * 位图调整工具：按 LUT（lookup table）的方式对 Bitmap 做像素级处理。
 *
 * <p>支持的操作（按调用顺序在 {@link #apply(Bitmap, Params)} 内串联）：
 * <ol>
 *   <li>R/G/B 通道亮度偏移（线性，-127 ~ +127）；</li>
 *   <li>整体对比度（围绕 128 中点的斜率，0.5 ~ 3.0）；</li>
 *   <li>反色（开关）；</li>
 *   <li>阈值二值化（0~255，或调用 {@link #otsuThreshold(Bitmap)} 自动计算）。
 *       当阈值 < 0 时表示"不二值化"。</li>
 * </ol>
 *
 * <p>性能：所有操作通过 256 维 LUT + 一次性像素遍历完成。1080×1080 图在
 * 中端机上耗时 < 30ms。
 */
public final class BitmapAdjuster {

    private BitmapAdjuster() {}

    /** 调整参数。各字段含义见 {@link BitmapAdjuster} 类注释。 */
    public static final class Params {
        public int brightnessR;  // [-127, 127]
        public int brightnessG;
        public int brightnessB;
        public float contrast = 1.0f;  // [0.5, 3.0]
        public boolean invert;
        /** -1 表示不二值化；0~255 为阈值；UI 上"自动"时先调 Otsu 得值再赋。 */
        public int threshold = -1;

        public boolean isIdentity() {
            return brightnessR == 0 && brightnessG == 0 && brightnessB == 0
                    && Math.abs(contrast - 1.0f) < 1e-3
                    && !invert
                    && threshold < 0;
        }
    }

    /**
     * 把 {@code src} 按 {@code p} 处理后返回一张新 Bitmap（ARGB_8888）。
     * 不修改 src，调用方负责回收返回值。
     */
    public static Bitmap apply(Bitmap src, Params p) {
        if (src == null) {
            return null;
        }
        if (p == null || p.isIdentity()) {
            // 走到调整模式但什么都没改 → 返回拷贝避免共用 bitmap 导致后续混乱。
            return src.copy(Bitmap.Config.ARGB_8888, false);
        }
        int w = src.getWidth();
        int h = src.getHeight();
        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);

        // 预计算三个 256 维 LUT，对比度也合并进去。
        int[] lutR = buildLut(p.brightnessR, p.contrast);
        int[] lutG = buildLut(p.brightnessG, p.contrast);
        int[] lutB = buildLut(p.brightnessB, p.contrast);

        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int r = lutR[(c >> 16) & 0xff];
            int g = lutG[(c >> 8) & 0xff];
            int b = lutB[c & 0xff];

            if (p.invert) {
                r = 255 - r;
                g = 255 - g;
                b = 255 - b;
            }
            if (p.threshold >= 0) {
                // 用感知亮度（Rec.601 BT.601 系数）判断，比简单平均更接近肉眼。
                int gray = (r * 299 + g * 587 + b * 114) / 1000;
                int v = gray >= p.threshold ? 255 : 0;
                r = g = b = v;
            }
            pixels[i] = (c & 0xff000000) | (r << 16) | (g << 8) | b;
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(pixels, 0, w, 0, 0, w, h);
        return out;
    }

    /**
     * 构造一个 256 维 LUT：
     *   y = clip( contrast * (x - 128) + 128 + brightness )
     *   x ∈ [0,255], brightness ∈ [-127,127], contrast ∈ [0.5,3.0]
     */
    private static int[] buildLut(int brightness, float contrast) {
        int[] lut = new int[256];
        for (int x = 0; x < 256; x++) {
            float y = contrast * (x - 128) + 128 + brightness;
            if (y < 0) y = 0;
            if (y > 255) y = 255;
            lut[x] = (int) y;
        }
        return lut;
    }

    /**
     * 用 Otsu 法（最大类间方差）在灰度直方图上自动求阈值。
     * 返回 0~255 的整数阈值；空位图返回 128。
     */
    public static int otsuThreshold(Bitmap src) {
        if (src == null) {
            return 128;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);
        int[] hist = new int[256];
        for (int c : pixels) {
            int gray = (Color.red(c) * 299
                    + Color.green(c) * 587
                    + Color.blue(c) * 114) / 1000;
            hist[gray]++;
        }
        int total = pixels.length;
        long sum = 0;
        for (int t = 0; t < 256; t++) {
            sum += (long) t * hist[t];
        }
        long sumB = 0;
        int wB = 0;
        double maxVar = 0;
        int bestT = 128;
        for (int t = 0; t < 256; t++) {
            wB += hist[t];
            if (wB == 0) continue;
            int wF = total - wB;
            if (wF == 0) break;
            sumB += (long) t * hist[t];
            double mB = (double) sumB / wB;
            double mF = (double) (sum - sumB) / wF;
            double var = (double) wB * wF * (mB - mF) * (mB - mF);
            if (var > maxVar) {
                maxVar = var;
                bestT = t;
            }
        }
        return bestT;
    }
}
