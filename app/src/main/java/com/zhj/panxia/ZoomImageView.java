package com.zhj.panxia;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

/**
 * 支持双指缩放、双击放大、单指拖动的 ImageView。
 * <p>
 * 未放大时横向拖动会让整张图**跟着手指走**（带边界阻尼），松手超过阈值才回调
 * {@link OnSwipe} 请求切换上一张 / 下一张，没超过就回弹——这样翻页是连续的，
 * 不会再「一滑就硬切」。
 */
public class ZoomImageView extends ImageView {

    public interface OnSwipe {
        /** dir = 1 向左滑（下一张），-1 向右滑（上一张） */
        void onSwipe(int dir);
    }

    private static final float MAX_SCALE = 6f;
    /** 松手时超过这个比例的位移才真正翻页 */
    private static final float COMMIT_RATIO = 0.2f;

    private final ScaleGestureDetector pinch;
    private final GestureDetector gestures;
    private final int slop;
    private VelocityTracker tracker;

    private float scale = 1f;
    private float panX;
    private float panY;
    private float lastX;
    private float lastY;
    private float downX;
    private float downY;

    private boolean dragging;
    private boolean multi;
    private boolean swipeEnabled = true;
    private boolean hasPrev = true;
    private boolean hasNext = true;

    private OnSwipe swipe;

    public ZoomImageView(Context c) {
        this(c, null);
    }

    public ZoomImageView(Context c, AttributeSet attrs) {
        super(c, attrs);
        setScaleType(ScaleType.MATRIX);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        pinch = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float target = Math.max(1f, Math.min(MAX_SCALE, scale * d.getScaleFactor()));
                zoomTo(target, d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (scale > 1.2f) {
                    resetZoom();
                } else {
                    zoomTo(2.5f, e.getX(), e.getY());
                }
                return true;
            }
        });
    }

    public void setOnSwipe(OnSwipe l) {
        this.swipe = l;
    }

    /** 切换动画期间关掉手势，避免和动画抢位置 */
    public void setSwipeEnabled(boolean enabled) {
        this.swipeEnabled = enabled;
    }

    /** 告诉视图前后还有没有图，到边界时给阻尼而不是死拽 */
    public void setSwipeBounds(boolean prev, boolean next) {
        this.hasPrev = prev;
        this.hasNext = next;
    }

    public boolean isZoomed() {
        return scale > 1.01f;
    }

    public void resetZoom() {
        scale = 1f;
        panX = 0;
        panY = 0;
        applyMatrix();
    }

    /** 立刻把位移 / 透明度 / 缩放复位（切换动画的起点和终点都用它兜底） */
    public void resetSwipeOffset() {
        animate().cancel();
        setTranslationX(0f);
        setAlpha(1f);
        setScaleX(1f);
        setScaleY(1f);
    }

    /** 没滑够就回到原位 */
    public void snapBack() {
        animate().translationX(0f)
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(210)
                .setInterpolator(new DecelerateInterpolator(1.8f))
                .start();
    }

    @Override
    public void setImageBitmap(Bitmap bm) {
        super.setImageBitmap(bm);
        resetZoom();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        applyMatrix();
    }

    // ------------------------------------------------------------ 矩阵计算

    private float drawW() {
        Drawable d = getDrawable();
        return d == null ? 0 : d.getIntrinsicWidth();
    }

    private float drawH() {
        Drawable d = getDrawable();
        return d == null ? 0 : d.getIntrinsicHeight();
    }

    private float fitScale() {
        float dw = drawW();
        float dh = drawH();
        if (dw <= 0 || dh <= 0 || getWidth() == 0 || getHeight() == 0) {
            return 1f;
        }
        return Math.min(getWidth() / dw, getHeight() / dh);
    }

    private float total() {
        return fitScale() * scale;
    }

    private float tx() {
        return (getWidth() - drawW() * total()) / 2f + panX;
    }

    private float ty() {
        return (getHeight() - drawH() * total()) / 2f + panY;
    }

    private void applyMatrix() {
        if (drawW() <= 0 || drawH() <= 0 || getWidth() == 0) {
            return;
        }
        float t = total();
        Matrix m = new Matrix();
        m.setScale(t, t);
        m.postTranslate(tx(), ty());
        setImageMatrix(m);
    }

    private void zoomTo(float target, float fx, float fy) {
        float t = total();
        if (t <= 0) {
            scale = target;
            applyMatrix();
            return;
        }
        // 保持手指下方的图像点不动
        float qx = (fx - tx()) / t;
        float qy = (fy - ty()) / t;
        scale = target;
        float t2 = total();
        float ntx = fx - qx * t2;
        float nty = fy - qy * t2;
        panX = ntx - (getWidth() - drawW() * t2) / 2f;
        panY = nty - (getHeight() - drawH() * t2) / 2f;
        clampPan();
        applyMatrix();
    }

    private void clampPan() {
        float t = total();
        float maxX = Math.max(0, (drawW() * t - getWidth()) / 2f);
        float maxY = Math.max(0, (drawH() * t - getHeight()) / 2f);
        panX = Math.max(-maxX, Math.min(maxX, panX));
        panY = Math.max(-maxY, Math.min(maxY, panY));
    }

    // ------------------------------------------------------------ 触摸

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        pinch.onTouchEvent(e);
        gestures.onTouchEvent(e);

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getX();
                downY = lastY = e.getY();
                dragging = false;
                multi = false;
                if (tracker == null) {
                    tracker = VelocityTracker.obtain();
                } else {
                    tracker.clear();
                }
                tracker.addMovement(e);
                if (swipeEnabled) {
                    // 打断可能还在跑的回弹动画，并归位（动画终点就是这些值，看不出跳动）
                    resetSwipeOffset();
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                multi = true;
                if (dragging) {
                    dragging = false;
                    snapBack();
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (tracker != null) {
                    tracker.addMovement(e);
                }
                if (multi || e.getPointerCount() > 1 || pinch.isInProgress()) {
                    break;
                }
                if (scale > 1.01f) {
                    // 放大状态：单指拖动画面本身
                    panX += e.getX() - lastX;
                    panY += e.getY() - lastY;
                    lastX = e.getX();
                    lastY = e.getY();
                    clampPan();
                    applyMatrix();
                    break;
                }
                float dx = e.getX() - downX;
                float dy = e.getY() - downY;
                if (!dragging) {
                    if (!swipeEnabled || Math.abs(dx) <= slop
                            || Math.abs(dx) < Math.abs(dy) * 1.3f
                            || (!hasPrev && !hasNext)) {
                        break;
                    }
                    dragging = true;
                }
                followSwipe(dx);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    float off = getTranslationX();
                    float w = Math.max(1, getWidth());
                    float vx = 0f;
                    if (tracker != null) {
                        tracker.computeCurrentVelocity(1000);
                        vx = tracker.getXVelocity();
                    }
                    boolean forward = off < 0f;
                    boolean allowed = forward ? hasNext : hasPrev;
                    // 拖得够远，或者甩得够快（短距离快滑也能翻页）
                    boolean farEnough = Math.abs(off) > w * COMMIT_RATIO;
                    boolean flicked = Math.abs(vx) > 700 && Math.abs(off) > w * 0.05f;
                    if (e.getActionMasked() == MotionEvent.ACTION_UP && allowed
                            && (farEnough || flicked)) {
                        // 交给 Activity 接着滑出、换图、再从另一侧滑进来
                        if (swipe != null) {
                            swipe.onSwipe(forward ? 1 : -1);
                        }
                    } else {
                        snapBack();
                    }
                } else if (scale < 1.02f) {
                    panX = 0;
                    panY = 0;
                    applyMatrix();
                }
                break;
            default:
                break;
        }
        return true;
    }

    /** 手指拖动跟随：位移越大图越淡越小，翻页时有明确的「要换了一张」的感觉 */
    private void followSwipe(float dx) {
        float w = Math.max(1, getWidth());
        boolean atEdge = (dx > 0 && !hasPrev) || (dx < 0 && !hasNext);
        float off = atEdge ? dx * 0.26f : dx * 0.92f;
        setTranslationX(off);
        float k = Math.min(0.6f, Math.abs(off) / w);
        setAlpha(1f - k * 0.55f);
        setScaleX(1f - k * 0.10f);
        setScaleY(1f - k * 0.10f);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (tracker != null) {
            tracker.recycle();
            tracker = null;
        }
        super.onDetachedFromWindow();
    }
}
