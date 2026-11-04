package com.zhj.panxia;

import android.content.Context;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.AbsListView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * 下拉刷新容器（零依赖实现，替代 androidx.swiperefreshlayout）。
 * <p>
 * 判定规则：只有当列表已经滑到顶部、且手指明确往下拖超过 touchSlop 时才接管手势，
 * 横向滑动一律放行给列表，避免误触发。接管后给子 View 下发 CANCEL，
 * 所以不会和列表自身的滚动、点击冲突。
 */
public class PullRefreshLayout extends FrameLayout {

    public interface OnRefreshListener {
        void onRefresh();
    }

    private final int headerHeight;
    private final int maxPull;
    private final int slop;
    private final float density;

    private OnRefreshListener listener;
    private View content;
    private LinearLayout header;
    private ProgressBar progress;
    private ImageView arrow;
    private TextView label;

    private float downX;
    private float downY;
    private float pull;
    private boolean dragging;
    private boolean horizontal;
    private boolean refreshing;

    private long refreshStart;

    private final Runnable timeoutReset = new Runnable() {
        @Override
        public void run() {
            if (refreshing) {
                setRefreshing(false);
            }
        }
    };

    public PullRefreshLayout(Context c) {
        this(c, null);
    }

    public PullRefreshLayout(Context c, AttributeSet attrs) {
        super(c, attrs);
        density = c.getResources().getDisplayMetrics().density;
        headerHeight = (int) (68 * density);
        maxPull = (int) (132 * density);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        buildHeader(c);
    }

    private void buildHeader(Context c) {
        header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER);
        header.setClickable(false);
        header.setFocusable(false);

        progress = new ProgressBar(c, null, android.R.attr.progressBarStyleSmall);
        progress.setVisibility(INVISIBLE);
        LinearLayout.LayoutParams plp =
                new LinearLayout.LayoutParams(dp(18), dp(18));
        plp.rightMargin = dp(8);
        header.addView(progress, plp);

        arrow = new ImageView(c);
        arrow.setImageResource(R.drawable.ic_arrow_down);
        arrow.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams alp =
                new LinearLayout.LayoutParams(dp(18), dp(18));
        alp.rightMargin = dp(8);
        header.addView(arrow, alp);

        label = new TextView(c);
        label.setTextSize(13);
        label.setLetterSpacing(0.03f);
        label.setText("下拉刷新");
        header.addView(label, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        int color = attrColor(R.attr.olDim);
        label.setTextColor(color);
        arrow.setColorFilter(color);

        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, headerHeight, Gravity.TOP);
        addView(header, lp);
        header.setTranslationY(-headerHeight);
        header.setAlpha(0f);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        // 头是构造时 addView 进来的第 0 个，正文是 XML 里最后一个
        if (getChildCount() > 0) {
            content = getChildAt(getChildCount() - 1);
        }
    }

    public void setOnRefreshListener(OnRefreshListener l) {
        listener = l;
    }

    public boolean isRefreshing() {
        return refreshing;
    }

    /** 数据加载结束后调用，收起指示器 */
    public void setRefreshing(boolean on) {
        if (on) {
            if (!refreshing) {
                startRefresh();
            }
            return;
        }
        if (!refreshing) {
            return;
        }
        refreshing = false;
        removeCallbacks(timeoutReset);
        long wait = Math.max(0L, 420L - (System.currentTimeMillis() - refreshStart));
        postDelayed(new Runnable() {
            @Override
            public void run() {
                arrow.setVisibility(VISIBLE);
                progress.setVisibility(INVISIBLE);
                arrow.setRotation(0f);
                settle();
            }
        }, wait);
    }

    // ---------------------------------------------------------------- 手势

    private boolean canScrollUp() {
        if (content == null) {
            return false;
        }
        if (content.canScrollVertically(-1)) {
            return true;
        }
        if (content instanceof AbsListView) {
            AbsListView l = (AbsListView) content;
            if (l.getFirstVisiblePosition() > 0) {
                return true;
            }
            View first = l.getChildAt(0);
            return first != null && first.getTop() < l.getPaddingTop() - 1;
        }
        return false;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (!isEnabled() || refreshing) {
            return false;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                dragging = false;
                horizontal = false;
                return false;
            case MotionEvent.ACTION_MOVE: {
                if (horizontal) {
                    return false;
                }
                float dy = e.getY() - downY;
                float dx = e.getX() - downX;
                if (Math.abs(dx) > slop * 1.5f && Math.abs(dx) > Math.abs(dy)) {
                    horizontal = true;
                    return false;
                }
                if (dy > slop && !canScrollUp()) {
                    dragging = true;
                    return true;
                }
                return false;
            }
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (refreshing) {
                    return true;
                }
                if (!dragging) {
                    if (e.getY() - downY > slop && !canScrollUp()) {
                        dragging = true;
                    } else {
                        return true;
                    }
                }
                applyPull((e.getY() - downY - slop) * 0.5f);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (refreshing) {
                    return true;
                }
                boolean fire = dragging && pull >= headerHeight;
                dragging = false;
                if (fire) {
                    startRefresh();
                } else {
                    settle();
                }
                return true;
            }
            default:
                return super.onTouchEvent(e);
        }
    }

    private void applyPull(float distance) {
        pull = Math.max(0f, Math.min(distance, maxPull));
        float ratio = Math.min(1f, pull / headerHeight);
        if (content != null) {
            content.setTranslationY(pull);
        }
        header.setTranslationY(-headerHeight + pull);
        header.setAlpha(Math.min(1f, 0.25f + ratio));
        boolean ready = pull >= headerHeight;
        label.setText(ready ? "松开刷新" : "下拉刷新");
        arrow.setRotation(ready ? 180f : 0f);
    }

    private void startRefresh() {
        refreshing = true;
        refreshStart = System.currentTimeMillis();
        arrow.setVisibility(INVISIBLE);
        arrow.setRotation(0f);
        progress.setVisibility(VISIBLE);
        label.setText("正在刷新…");
        int hold = (int) (headerHeight * 0.72f);
        pull = hold;
        if (content != null) {
            content.animate().translationY(hold).setDuration(180).start();
        }
        header.animate().translationY(-headerHeight + hold).alpha(1f)
                .setDuration(180).start();
        removeCallbacks(timeoutReset);
        postDelayed(timeoutReset, 15000L);
        if (listener != null) {
            listener.onRefresh();
        }
    }

    private void settle() {
        pull = 0f;
        if (content != null) {
            content.animate().translationY(0f).setDuration(220).start();
        }
        header.animate().translationY(-headerHeight).alpha(0f).setDuration(220).start();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(timeoutReset);
        super.onDetachedFromWindow();
    }

    private int dp(int v) {
        return (int) (v * density + 0.5f);
    }

    private int attrColor(int attr) {
        TypedValue tv = new TypedValue();
        if (getContext().getTheme().resolveAttribute(attr, tv, true)) {
            return tv.data;
        }
        return 0xFF888888;
    }
}
