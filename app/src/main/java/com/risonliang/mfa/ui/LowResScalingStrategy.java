/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.ui;

import com.journeyapps.barcodescanner.Size;
import com.journeyapps.barcodescanner.camera.FitCenterStrategy;
import java.util.List;

/**
 * 自定义预览尺寸挑选策略：在相机支持的所有预览分辨率里，挑出 ≤ 1280×720
 * 的最大候选项（按面积），如果一个都没有就退化为最小的那一项。
 *
 * 背景：zxing-android-embedded 默认的 FitCenterStrategy 会按"贴近 viewfinder
 * 尺寸"打分，在 2K+ 屏的旗舰机（小米 15 Pro）上会选到 2400×1080，二维码
 * 数据模块在帧内过采样 + 镜头模糊会让 BinaryBitmap 的 sampler 持续失败，
 * 表现为 finder pattern 找到（possiblePoints=3/4）但 decode 永远不返回。
 *
 * 1280×720 是 zxing 社区长期验证的"识别率甜点"：
 *  - 数据模块清晰，对焦不稳时也能保留位流；
 *  - YUV 带宽下来，解码线程能在 30fps 内多采样几帧；
 *  - 国产 ROM 的 Camera1 兼容路径在 720p 上调用最稳。
 */
final class LowResScalingStrategy extends FitCenterStrategy {

    /** 目标上限：1280×720（横屏维度，zxing 内部已按相机原生方向给我们）。 */
    private static final int kMaxPixels = 1280 * 720;

    @Override
    public Size getBestPreviewSize(List<Size> sizes, Size desired) {
        Size best = null;
        long bestPixels = -1L;
        Size smallest = null;
        long smallestPixels = Long.MAX_VALUE;
        for (Size s : sizes) {
            long p = (long) s.width * s.height;
            if (p <= kMaxPixels && p > bestPixels) {
                best = s;
                bestPixels = p;
            }
            if (p < smallestPixels) {
                smallest = s;
                smallestPixels = p;
            }
        }
        if (best != null) {
            return best;
        }
        // 没有任何候选 ≤ 上限：退化为最小那项，至少避开 2K+ 路径。
        return smallest != null ? smallest : sizes.get(0);
    }
}
