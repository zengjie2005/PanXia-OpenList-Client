package com.zhj.panxia;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;

/**
 * 主题：跟随系统 / 强制浅色 / 强制深色三档。
 * 切换后需要 recreate() 才会生效。
 */
public final class Theme {

    public static final int FOLLOW = 0;
    public static final int LIGHT = 1;
    public static final int DARK = 2;

    private Theme() {
    }

    /** 当前实际是不是深色（跟随系统时读系统 uiMode） */
    public static boolean isDark(Context c, Store s) {
        int mode = s.themeMode();
        if (mode == LIGHT) {
            return false;
        }
        if (mode == DARK) {
            return true;
        }
        int ui = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return ui == Configuration.UI_MODE_NIGHT_YES;
    }

    public static void apply(Activity a, Store s) {
        a.setTheme(isDark(a, s) ? R.style.AppThemeDark : R.style.AppThemeLight);
    }

    /** setContentView 之后调用：处理系统栏避让 + 图标明暗 + 触感开关 */
    public static void install(Activity a, int topBarId) {
        Bars.apply(a, topBarId);
        Bars.icons(a, !isDark(a, new Store(a)));
        Feedback.enabled = new Store(a).haptic();
    }
}
