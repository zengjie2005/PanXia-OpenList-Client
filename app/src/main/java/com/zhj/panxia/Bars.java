package com.zhj.panxia;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

/**
 * 系统栏避让。
 * <p>
 * targetSdk 35 起 Android 15 会强制 edge-to-edge：窗口内容直接铺到状态栏和导航栏下面，
 * targetSdk 35 的应用无法再用 statusBarColor / fitsSystemWindows 自动让位，
 * 结果就是顶部栏被状态栏盖住、返回键点不到。这里统一用 insets 手动补内边距。
 */
public final class Bars {

    private Bars() {
    }

    /**
     * 给页面根布局挂上 insets 监听：
     * 顶部栏让出状态栏高度（背景色因此延伸到状态栏，视觉更完整），
     * 整个内容底部让出导航栏 / 键盘高度。
     *
     * @param topBarId 顶部栏 id，传 0 表示整个内容顶部一起让位
     */
    public static void apply(final Activity a, final int topBarId) {
        edgeToEdge(a);
        View root = null;
        ViewGroup content = a.findViewById(android.R.id.content);
        if (content != null && content.getChildCount() > 0) {
            root = content.getChildAt(0);
        }
        if (root == null) {
            return;
        }
        final View topBar = topBarId == 0 ? null : a.findViewById(topBarId);
        final int topBase = topBar != null ? topBar.getPaddingTop() : root.getPaddingTop();
        final int bottomBase = root.getPaddingBottom();

        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int top;
                int bottom;
                if (Build.VERSION.SDK_INT >= 30) {
                    top = insets.getInsets(
                            WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout()).top;
                    int nav = insets.getInsets(
                            WindowInsets.Type.navigationBars() | WindowInsets.Type.displayCutout()).bottom;
                    int ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
                    bottom = Math.max(nav, ime);
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                if (topBar != null) {
                    topBar.setPadding(topBar.getPaddingLeft(), topBase + top,
                            topBar.getPaddingRight(), topBar.getPaddingBottom());
                    v.setPadding(v.getPaddingLeft(), v.getPaddingTop(),
                            v.getPaddingRight(), bottomBase + bottom);
                } else {
                    v.setPadding(v.getPaddingLeft(), topBase + top,
                            v.getPaddingRight(), bottomBase + bottom);
                }
                return insets;
            }
        });
        root.requestApplyInsets();
    }

    /**
     * 统一让窗口铺满全屏，保证各版本拿到的 insets 都是真实值，
     * 不会出现「系统已经让过位、我们再让一次」的双重留白。
     */
    private static void edgeToEdge(Activity a) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                a.getWindow().setDecorFitsSystemWindows(false);
            } else {
                a.getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 系统栏图标明暗：深色背景配浅色图标，浅色背景配深色图标。
     *
     * @param darkIcons true 表示背景是浅色、需要深色图标
     */
    public static void icons(Activity a, boolean darkIcons) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController c = a.getWindow().getInsetsController();
                if (c != null) {
                    int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    c.setSystemBarsAppearance(darkIcons ? mask : 0, mask);
                }
            } else if (Build.VERSION.SDK_INT >= 26) {
                View d = a.getWindow().getDecorView();
                int flags = d.getSystemUiVisibility();
                if (darkIcons) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                } else {
                    flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
                }
                d.setSystemUiVisibility(flags);
            }
        } catch (Exception ignored) {
        }
    }
}
