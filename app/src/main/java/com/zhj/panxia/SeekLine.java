package com.zhj.panxia;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 播放进度条：平时跟着播放位置走，手指按住可以直接拖到任意位置。
 * <p>
 * 拖动期间 {@link #setProgress} 不再覆盖显示（否则手指和播放进度会打架），
 * 松手时把最终位置回调给播放器。上下留白都算进可触区域，手指不用瞄得很准。
 */
public class SeekLine extends View {

    public interface OnSeekListener {
        /** 手指按下，播放器可以暂停刷新或先别 seek */
        void onSeekStart();

        /** 拖动中，position 是预览位置（毫秒） */
        void onSeekMove(long position);

        /** 松手，position 为最终位置（毫秒） */
        void onSeekEnd(long position);
    }

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint played = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint preview = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    private long position;
    private long duration;
    private boolean active = true;
    private boolean dragging;
    private OnSeekListener listener;

    public SeekLine(Context c) {
        super(c);
        init();
    }

    public SeekLine(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        track.setColor(0x33FFFFFF);
        played.setColor(0xFF5B8DFF);
        knob.setColor(0xFFFFFFFF);
        preview.setColor(0x665B8DFF);
    }

    public void setOnSeekListener(OnSeekListener l) {
        listener = l;
    }

    public void setProgress(long pos, long dur) {
        if (duration != dur) {
            duration = Math.max(0, dur);
        }
        if (!dragging) {
            position = Math.max(0, pos);
            invalidate();
        }
    }

    /** 拖动时把手的半径放大一点，视觉上告诉用户「正在拖」 */
    public void setActive(boolean a) {
        active = a;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float h = Ui.dp(getContext(), active || dragging ? 5 : 4);
        float cy = getHeight() / 2f;
        float knobR = Ui.dp(getContext(), dragging ? 9 : 6);
        float left = knobR;
        float right = getWidth() - knobR;
        float radius = h / 2f;

        r.set(left, cy - h / 2f, right, cy + h / 2f);
        canvas.drawRoundRect(r, radius, radius, track);

        float ratio = duration <= 0 ? 0 : Math.min(1f, position / (float) duration);
        float x = left + (right - left) * ratio;
        if (ratio > 0) {
            r.set(left, cy - h / 2f, x, cy + h / 2f);
            canvas.drawRoundRect(r, radius, radius, played);
        }
        if (dragging) {
            // 拖动时在把手外面套一圈光晕，弱化「进度条太细」的感觉
            canvas.drawCircle(x, cy, knobR * 2.1f, preview);
        }
        canvas.drawCircle(x, cy, knobR, knob);
    }

    /** 无障碍：拖动松手也算一次点击，读屏软件才能念出当前进度 */
    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (duration <= 0) {
                    return false;
                }
                dragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                if (listener != null) {
                    listener.onSeekStart();
                }
                updateFromTouch(e.getX());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) {
                    return false;
                }
                updateFromTouch(e.getX());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) {
                    return false;
                }
                dragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                if (listener != null) {
                    listener.onSeekEnd(position);
                }
                performClick();
                invalidate();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private void updateFromTouch(float x) {
        float knobR = Ui.dp(getContext(), 6);
        float left = knobR;
        float right = getWidth() - knobR;
        if (right <= left) {
            return;
        }
        float ratio = (x - left) / (right - left);
        ratio = Math.max(0f, Math.min(1f, ratio));
        position = (long) (ratio * duration);
        if (listener != null) {
            listener.onSeekMove(position);
        }
        invalidate();
    }
}
