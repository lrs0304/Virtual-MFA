package com.risonliang.mfa.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

/**
 * 支持双指缩放 + 单指平移的图片预览控件。
 *
 * <p>设计要点：
 * <ul>
 *   <li>初始展示：把图片按 fitCenter 方式贴满控件，记录基准 baseMatrix；</li>
 *   <li>缩放范围：1x（基准）~ 8x，越界时弹回；</li>
 *   <li>平移：缩放后允许图片在控件内自由平移，但禁止"图小于控件"时让图越界 → 始终保持图至少有一边贴边；</li>
 *   <li>把内部 Matrix 通过 {@link #getDisplayMatrix()} 暴露给外部，
 *       供"调整模式"按当前可见区裁切交给 ML Kit 重识别。</li>
 * </ul>
 *
 * <p>本类不依赖任何第三方手势库，纯 {@link ScaleGestureDetector} + 自实现平移。
 */
public class ZoomableImageView extends AppCompatImageView {

    /** 最小缩放比例（相对于 baseMatrix）。 */
    private static final float kMinScale = 1.0f;
    /** 最大缩放比例。 */
    private static final float kMaxScale = 8.0f;

    /** 适配控件后的基准矩阵（不含用户手势）。 */
    private final Matrix baseMatrix_ = new Matrix();
    /** 用户手势叠加的矩阵（缩放 + 平移）。 */
    private final Matrix supportMatrix_ = new Matrix();
    /** 当前展示矩阵 = baseMatrix_ × supportMatrix_，用于真正 setImageMatrix。 */
    private final Matrix displayMatrix_ = new Matrix();

    private final float[] matrixValues_ = new float[9];
    private final RectF tempRect_ = new RectF();

    private ScaleGestureDetector scaleDetector_;
    private GestureDetector gestureDetector_;

    /** 控件接收到 setImageBitmap 时缓存原始位图引用（不持有副本）。 */
    @Nullable
    private Bitmap sourceBitmap_;

    public ZoomableImageView(Context context) {
        this(context, null);
    }

    public ZoomableImageView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ZoomableImageView(Context context, @Nullable AttributeSet attrs,
                             int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        // 我们自己用 Matrix 控制，避免 ImageView 的 fitCenter 干预。
        setScaleType(ScaleType.MATRIX);
        init(context);
    }

    private void init(Context context) {
        scaleDetector_ = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        float scaleFactor = detector.getScaleFactor();
                        float current = getCurrentScale();
                        float next = current * scaleFactor;
                        // 钳制到 [kMinScale, kMaxScale]。
                        if (next < kMinScale) {
                            scaleFactor = kMinScale / current;
                        } else if (next > kMaxScale) {
                            scaleFactor = kMaxScale / current;
                        }
                        supportMatrix_.postScale(scaleFactor, scaleFactor,
                                detector.getFocusX(), detector.getFocusY());
                        applyAndConstrain();
                        return true;
                    }
                });

        gestureDetector_ = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onScroll(MotionEvent e1, MotionEvent e2,
                                            float distanceX, float distanceY) {
                        if (scaleDetector_.isInProgress()) {
                            return false;
                        }
                        supportMatrix_.postTranslate(-distanceX, -distanceY);
                        applyAndConstrain();
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        // 双击在 1x ↔ 2.5x 之间切换，便于"快速放大局部"。
                        float target = getCurrentScale() < 1.5f ? 2.5f : 1.0f;
                        float factor = target / Math.max(0.01f, getCurrentScale());
                        supportMatrix_.postScale(factor, factor, e.getX(), e.getY());
                        applyAndConstrain();
                        return true;
                    }
                });
    }

    @Override
    public void setImageBitmap(@Nullable Bitmap bm) {
        super.setImageBitmap(bm);
        sourceBitmap_ = bm;
        // 新图：重置手势矩阵，等待 onSizeChanged 计算 baseMatrix。
        supportMatrix_.reset();
        recomputeBaseMatrix();
        applyAndConstrain();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        recomputeBaseMatrix();
        applyAndConstrain();
    }

    /** 按 fitCenter 计算 baseMatrix。 */
    private void recomputeBaseMatrix() {
        if (sourceBitmap_ == null || getWidth() == 0 || getHeight() == 0) {
            baseMatrix_.reset();
            return;
        }
        float viewW = getWidth();
        float viewH = getHeight();
        float imgW = sourceBitmap_.getWidth();
        float imgH = sourceBitmap_.getHeight();
        float scale = Math.min(viewW / imgW, viewH / imgH);
        float dx = (viewW - imgW * scale) / 2f;
        float dy = (viewH - imgH * scale) / 2f;
        baseMatrix_.reset();
        baseMatrix_.postScale(scale, scale);
        baseMatrix_.postTranslate(dx, dy);
    }

    /** 把当前矩阵应用到 ImageView，并按边界约束修正越界平移。 */
    private void applyAndConstrain() {
        // 修正越界。
        constrainTranslate();
        displayMatrix_.set(baseMatrix_);
        displayMatrix_.postConcat(supportMatrix_);
        setImageMatrix(displayMatrix_);
        invalidate();
    }

    /** 让图片在控件中"至少有一边贴边"，禁止露白。 */
    private void constrainTranslate() {
        if (sourceBitmap_ == null) {
            return;
        }
        Matrix combined = new Matrix(baseMatrix_);
        combined.postConcat(supportMatrix_);
        tempRect_.set(0, 0, sourceBitmap_.getWidth(), sourceBitmap_.getHeight());
        combined.mapRect(tempRect_);

        float dx = 0;
        float dy = 0;
        float viewW = getWidth();
        float viewH = getHeight();

        if (tempRect_.width() < viewW) {
            // 图小于控件宽度 → 水平居中。
            dx = (viewW - tempRect_.width()) / 2f - tempRect_.left;
        } else if (tempRect_.left > 0) {
            dx = -tempRect_.left;
        } else if (tempRect_.right < viewW) {
            dx = viewW - tempRect_.right;
        }

        if (tempRect_.height() < viewH) {
            dy = (viewH - tempRect_.height()) / 2f - tempRect_.top;
        } else if (tempRect_.top > 0) {
            dy = -tempRect_.top;
        } else if (tempRect_.bottom < viewH) {
            dy = viewH - tempRect_.bottom;
        }

        if (dx != 0 || dy != 0) {
            supportMatrix_.postTranslate(dx, dy);
        }
    }

    /** 当前用户施加的缩放倍数（不含 base）。 */
    private float getCurrentScale() {
        supportMatrix_.getValues(matrixValues_);
        return matrixValues_[Matrix.MSCALE_X];
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (sourceBitmap_ == null) {
            return false;
        }
        scaleDetector_.onTouchEvent(event);
        gestureDetector_.onTouchEvent(event);
        return true;
    }

    /** 重置所有手势变换。 */
    public void resetTransform() {
        supportMatrix_.reset();
        applyAndConstrain();
    }

    /**
     * 取出当前展示用的位图（不包含手势裁切，单纯是 sourceBitmap_）。
     * 上层可以基于此 bitmap + getDisplayMatrix 自行渲染到 canvas 再喂给 ML Kit。
     */
    @Nullable
    public Bitmap getSourceBitmap() {
        return sourceBitmap_;
    }

    /**
     * 当前可见区域对应到原图坐标的 RectF（用于裁切局部送入识别）。
     * 控件可见区(0,0,w,h) 反映射到原图坐标。
     */
    public RectF getVisibleSourceRect() {
        RectF rect = new RectF(0, 0, getWidth(), getHeight());
        Matrix inverse = new Matrix();
        if (displayMatrix_.invert(inverse)) {
            inverse.mapRect(rect);
        }
        if (sourceBitmap_ != null) {
            rect.left = Math.max(0, rect.left);
            rect.top = Math.max(0, rect.top);
            rect.right = Math.min(sourceBitmap_.getWidth(), rect.right);
            rect.bottom = Math.min(sourceBitmap_.getHeight(), rect.bottom);
        }
        return rect;
    }
}
