package com.zhj.panxia;

import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * 触屏按压反馈。
 * <p>
 * 系统自带的 ListView 行是没有按下效果的（我们把 listSelector 设成了透明），
 * 点上去会觉得很“生硬”。这里给控件补两件事：
 * 1. 按下时轻微缩小（0.965），抬起回弹，制造“被按到”的物理感；
 * 2. 抬起时一次短震动（可在设置里关掉）。
 * <p>
 * 只挂 OnTouchListener 并返回 false，不拦截原有点击逻辑。
 */
public final class Feedback {

    /** 由 Theme.install 按设置同步；关闭后不再震动 */
    static boolean enabled = true;

    private static final float PRESS_SCALE = 0.965f;
    private static final long DOWN_MS = 70L;
    private static final long UP_MS = 130L;

    private Feedback() {
    }

    /** 按压回弹 + 抬起震动 */
    public static void press(final View v) {
        press(v, true);
    }

    public static void press(final View v, final boolean withHaptic) {
        if (v == null) {
            return;
        }
        v.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private boolean moved;

            @Override
            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getX();
                        downY = e.getY();
                        moved = false;
                        animate(view, PRESS_SCALE, DOWN_MS);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        float slop = Ui.dp(view.getContext(), 12);
                        if (!moved && (Math.abs(e.getX() - downX) > slop
                                || Math.abs(e.getY() - downY) > slop)) {
                            // 手指在滑动（列表滚动），撤销按压态
                            moved = true;
                            animate(view, 1f, UP_MS);
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        animate(view, 1f, UP_MS);
                        if (!moved && withHaptic) {
                            tick(view);
                        }
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        animate(view, 1f, UP_MS);
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
    }

    /**
     * 点击后的"确认反馈"：先轻压再回弹。
     * <p>
     * 只在点击确认后播放，不挂触摸监听，因此绝不会吃掉点击事件——
     * 列表行用这个，而不是 {@link #press(View)}。
     */
    public static void pulse(final View v) {
        if (v == null) {
            return;
        }
        try {
            v.animate().cancel();
            v.animate().scaleX(0.975f).scaleY(0.975f).setDuration(70)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            v.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                        }
                    }).start();
        } catch (Exception ignored) {
        }
    }

    /** 单独触发一次触感反馈（已按设置过滤） */
    public static void tick(View v) {        if (!enabled || v == null) {
            return;
        }
        try {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        } catch (Exception ignored) {
        }
    }

    private static void animate(View v, float scale, long duration) {
        try {
            v.animate().cancel();
            v.animate().scaleX(scale).scaleY(scale).setDuration(duration).start();
        } catch (Exception ignored) {
        }
    }
}
