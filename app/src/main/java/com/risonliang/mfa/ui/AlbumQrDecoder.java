/*
 * Copyright (c) 2026 risonliang. All rights reserved.
 */
package com.risonliang.mfa.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 相册二维码解码（ML Kit Only 实验分支）。
 *
 * 设计要点：
 *  1) 仅做"读图 + ML Kit 解码"，不做任何 UI / Toast / Activity 跳转，
 *     由调用方根据 {@link Result} 决定后续 UI 行为；
 *  2) 必须在后台线程调用 {@link #decode(Context, Uri)}（含同步等待 Task）；
 *  3) 沿用与 ZXing 时期相同的 inSampleSize 策略（&gt;2048 才 1/2 降采样），
 *     兼顾 OOM 与 ML Kit 检测窗口的"甜点像素密度"；
 *  4) 限定 QR_CODE 格式 + 8s 超时，避免极端图片卡死后台线程；
 *  5) {@link BarcodeScanner} 是 Closeable，使用 try-with-resources 释放
 *     底层 native 模型句柄。
 */
final class AlbumQrDecoder {

    private static final String kLogTag = "MFA-Scan";
    private static final long kMlKitTimeoutSec = 8L;
    private static final int kSampleSizeThresholdPx = 2048;

    /** 三态结果：调用方据此决定 UI 反馈。 */
    enum Status { SUCCESS, NOT_FOUND, READ_FAILED }

    static final class Result {
        final Status status;
        /** 仅在 {@link Status#SUCCESS} 时非空。 */
        final String content;

        private Result(Status status, String content) {
            this.status = status;
            this.content = content;
        }

        static Result success(String content) {
            return new Result(Status.SUCCESS, content);
        }

        static Result notFound() {
            return new Result(Status.NOT_FOUND, null);
        }

        static Result readFailed() {
            return new Result(Status.READ_FAILED, null);
        }
    }

    private AlbumQrDecoder() {}

    /**
     * 从相册 Uri 解码二维码。**必须在后台线程调用**。
     *
     * @param ctx      读 ContentResolver 用，使用 ApplicationContext 即可
     * @param imageUri 相册返回的图片 Uri
     */
    static Result decode(Context ctx, Uri imageUri) {
        try {
            // 第一遍：仅读取尺寸，不加载像素
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            try (InputStream is =
                         ctx.getContentResolver().openInputStream(imageUri)) {
                if (is == null) {
                    Log.w(kLogTag, "openInputStream returned null (bounds), uri="
                            + imageUri);
                    return Result.readFailed();
                }
                BitmapFactory.decodeStream(is, null, opts);
            }
            int origW = opts.outWidth;
            int origH = opts.outHeight;
            int maxDim = Math.max(origW, origH);
            // inSampleSize 阈值：长边 > 2048 才降采样。
            //
            // 为什么不是更大的阈值（如 4096）：在华为高分屏典型截图（2400-3060
            // 边长）上实测——4096 阈值下 sampleSize=1，原图直接喂 ML Kit 时
            // 偶现识别失败；ImageEditActivity 内部用 2048 阈值（等价 1/2 降采
            // 样），同一张图"什么都不点直接重新识别"反而能命中。
            //
            // 原理：ML Kit 的 QR finder pattern 检测对"每个 module ≈ 4-8 像
            // 素"是甜点区间，原图上 module 往往 12-20 像素，且摩尔纹/JPEG 高
            // 频噪声未经低通滤波，反而干扰检测。一次 1/2 sampleSize 做了一次
            // box-filter 低通，噪点被均化，module 边缘更干净。
            //
            // 注：这只是单尺度修正，覆盖典型华为机型尺寸。后续若再撞到不同尺寸
            // 失败 case，应升级为多尺度兜底（2048 / 原图 / 1024 三档）。
            int sampleSize = 1;
            while (maxDim / sampleSize > kSampleSizeThresholdPx) {
                sampleSize *= 2;
            }
            Log.d(kLogTag, "image bounds=" + origW + "x" + origH
                    + ", mime=" + opts.outMimeType
                    + ", sampleSize=" + sampleSize);

            String content = decodeWithMlKit(ctx, imageUri, sampleSize);
            if (content == null) {
                Log.w(kLogTag,
                        "QRCode not found by ML Kit, origSize="
                                + origW + "x" + origH
                                + ", mime=" + opts.outMimeType
                                + ", sampleSize=" + sampleSize);
                return Result.notFound();
            }
            Log.d(kLogTag, "QR decoded, length=" + content.length()
                    + ", schemePrefix=" + safeSchemePrefix(content));
            return Result.success(content);
        } catch (Exception e) {
            Log.e(kLogTag, "decode failed", e);
            return Result.readFailed();
        }
    }

    /**
     * ML Kit 解码：本分支唯一相册解码路径。
     *
     * 设计要点：
     *  1) 复用与 ZXing 相同的 inSampleSize 策略读 Bitmap，避免大图 OOM；
     *  2) 必须运行在后台线程（外层 bgExecutor 已经是后台线程）；
     *     ML Kit 返回 Task 异步结果，这里用 CountDownLatch 同步等待，
     *     与现有"返回 String"的同步签名保持一致；
     *  3) 限制识别格式为 QR_CODE，加快速度；
     *  4) 加 8 秒超时，避免极端情况下卡住后台线程；
     *  5) BarcodeScanner 是 Closeable，使用 try-with-resources 正确释放
     *     底层 native 模型句柄。
     *
     * @return 解码到的内容；任何失败返回 null。
     */
    private static String decodeWithMlKit(Context ctx, Uri imageUri,
                                          int sampleSize) {
        Bitmap bitmap = null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = Math.max(1, sampleSize);
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream is =
                         ctx.getContentResolver().openInputStream(imageUri)) {
                if (is == null) {
                    Log.w(kLogTag, "ML Kit: openInputStream returned null");
                    return null;
                }
                bitmap = BitmapFactory.decodeStream(is, null, opts);
            }
            if (bitmap == null) {
                Log.w(kLogTag, "ML Kit: decodeStream returned null");
                return null;
            }

            BarcodeScannerOptions scannerOpts =
                    new BarcodeScannerOptions.Builder()
                            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                            .build();
            // rotationDegrees=0：截图都是正向的，不需要旋转。
            InputImage input = InputImage.fromBitmap(bitmap, 0);
            final String[] holder = new String[1];
            final Throwable[] errHolder = new Throwable[1];
            final CountDownLatch latch = new CountDownLatch(1);

            try (BarcodeScanner scanner =
                         BarcodeScanning.getClient(scannerOpts)) {
                scanner.process(input)
                        .addOnSuccessListener(barcodes -> {
                            if (barcodes != null && !barcodes.isEmpty()) {
                                Barcode b = barcodes.get(0);
                                String raw = b.getRawValue();
                                if (raw == null) {
                                    // 部分二进制 QR 没有 rawValue，
                                    // 退而求其次尝试 displayValue。
                                    raw = b.getDisplayValue();
                                }
                                holder[0] = raw;
                            }
                            latch.countDown();
                        })
                        .addOnFailureListener(e -> {
                            errHolder[0] = e;
                            latch.countDown();
                        });
                if (!latch.await(kMlKitTimeoutSec, TimeUnit.SECONDS)) {
                    Log.w(kLogTag, "ML Kit: timeout after "
                            + kMlKitTimeoutSec + "s");
                    return null;
                }
            }

            if (errHolder[0] != null) {
                Log.w(kLogTag, "ML Kit: process failed: "
                        + errHolder[0].getClass().getSimpleName()
                        + " " + errHolder[0].getMessage());
                return null;
            }
            if (holder[0] == null) {
                Log.w(kLogTag, "ML Kit: no QR found");
                return null;
            }
            Log.d(kLogTag, "ML Kit: QR decoded, length="
                    + holder[0].length()
                    + ", schemePrefix=" + safeSchemePrefix(holder[0]));
            return holder[0];
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            Log.w(kLogTag, "ML Kit: interrupted");
            return null;
        } catch (Exception e) {
            Log.w(kLogTag, "ML Kit: unexpected error: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            return null;
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    /** 仅取 scheme://host 段用于日志，避免把 secret 整段打到 logcat。 */
    static String safeSchemePrefix(String s) {
        if (s == null) {
            return "<null>";
        }
        int q = s.indexOf('?');
        String head = q > 0 ? s.substring(0, q) : s;
        if (head.length() > 64) {
            head = head.substring(0, 64) + "...";
        }
        return head;
    }
}
