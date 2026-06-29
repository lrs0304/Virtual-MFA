package com.risonliang.mfa.ui;

import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 二维码解码的统一入口：基于 ML Kit bundled 模型，仅识别 QR_CODE。
 *
 * <p>用于：
 * <ul>
 *   <li>{@link MainActivity} 的相册首次解码（已迁移）；</li>
 *   <li>{@link ImageEditActivity} 的"重新识别"按钮（调整后的 bitmap 再次解码）。</li>
 * </ul>
 *
 * <p>所有方法必须在后台线程调用：内部用 CountDownLatch 同步等待 ML Kit
 * 的异步回调，避免在 UI 线程阻塞引发 ANR。
 */
public final class QrDecoder {

    private static final String kLogTag = "MFA-QrDecoder";

    /** ML Kit process 超时时间。超过则视为失败。 */
    private static final long kTimeoutMs = 8000L;

    private QrDecoder() {}

    /**
     * 解码给定 Bitmap 中的二维码。
     *
     * @param bitmap 输入位图；可以是原图也可以是调整后的图。null 直接返回 null。
     * @return 二维码内容字符串；未识别到 / 超时 / 异常均返回 null。
     */
    @WorkerThread
    @Nullable
    public static String decode(@Nullable Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return null;
        }
        BarcodeScannerOptions opts =
                new BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                        .build();
        InputImage input = InputImage.fromBitmap(bitmap, 0);
        final String[] holder = new String[1];
        final Throwable[] errHolder = new Throwable[1];
        final CountDownLatch latch = new CountDownLatch(1);

        try (BarcodeScanner scanner = BarcodeScanning.getClient(opts)) {
            scanner.process(input)
                    .addOnSuccessListener(barcodes -> {
                        if (barcodes != null && !barcodes.isEmpty()) {
                            Barcode b = barcodes.get(0);
                            String raw = b.getRawValue();
                            if (raw == null) {
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
            if (!latch.await(kTimeoutMs, TimeUnit.MILLISECONDS)) {
                Log.w(kLogTag, "ML Kit: timeout after " + kTimeoutMs + "ms");
                return null;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            Log.w(kLogTag, "ML Kit: unexpected error: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            return null;
        }

        if (errHolder[0] != null) {
            Log.w(kLogTag, "ML Kit: process failed: "
                    + errHolder[0].getClass().getSimpleName()
                    + " " + errHolder[0].getMessage());
            return null;
        }
        if (holder[0] != null) {
            Log.d(kLogTag, "ML Kit: QR decoded, length=" + holder[0].length());
        }
        return holder[0];
    }
}
