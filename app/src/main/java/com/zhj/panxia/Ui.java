package com.zhj.panxia;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** 界面小工具：尺寸、时间、对话框、剪贴板、路径拼接。 */
public final class Ui {

    public interface OnText {
        void onText(String value);
    }

    public interface OnIndex {
        void onIndex(int index);
    }

    private Ui() {
    }

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static void toast(Context c, String msg) {
        Toast.makeText(c, msg, Toast.LENGTH_SHORT).show();
    }

    /** 收起软键盘（列表开始滚动时用，避免键盘占掉半个屏幕） */
    public static void hideKeyboard(android.app.Activity a) {
        try {
            android.view.View focus = a.getCurrentFocus();
            if (focus == null) {
                return;
            }
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            a.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
                focus.clearFocus();
            }
        } catch (Exception ignored) {
        }
    }

    public static void toastLong(Context c, String msg) {
        Toast.makeText(c, msg, Toast.LENGTH_LONG).show();
    }

    public static void info(Context c, String title, String msg) {
        new AlertDialog.Builder(c)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("关闭", null)
                .show();
    }

    public static void confirm(Context c, String title, String msg, String ok, final Runnable run) {
        new AlertDialog.Builder(c)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        run.run();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 单行文本输入框 */
    public static void input(Context c, String title, String hint, String initial, final OnText cb) {
        final EditText et = new EditText(c);
        et.setSingleLine(true);
        et.setHint(hint);
        et.setText(initial == null ? "" : initial);
        et.setSelection(et.getText().length());
        et.setInputType(InputType.TYPE_CLASS_TEXT);

        LinearLayout box = new LinearLayout(c);
        int pad = dp(c, 20);
        box.setPadding(pad, dp(c, 6), pad, 0);
        box.addView(et, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 48)));

        final AlertDialog d = new AlertDialog.Builder(c)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("确定", null)
                .setNegativeButton("取消", null)
                .create();
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialog) {
                d.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new android.view.View.OnClickListener() {
                    @Override
                    public void onClick(android.view.View v) {
                        String value = et.getText().toString().trim();
                        if (value.isEmpty()) {
                            et.setError("不能为空");
                            return;
                        }
                        d.dismiss();
                        cb.onText(value);
                    }
                });
            }
        });
        d.show();
    }

    public static void choose(Context c, String title, String[] items, final OnIndex cb) {
        new AlertDialog.Builder(c)
                .setTitle(title)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int i) {
                        cb.onIndex(i);
                    }
                })
                .show();
    }

    public static void copy(Context c, String label, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            toast(c, "已复制到剪贴板");
        } catch (Exception e) {
            toast(c, "复制失败");
        }
    }

    public static String size(long bytes) {        if (bytes < 0) {
            return "-";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double v = bytes;
        String[] unit = {"B", "KB", "MB", "GB", "TB", "PB"};
        int i = 0;
        while (v >= 1024 && i < unit.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.getDefault(), i == 1 ? "%.0f %s" : "%.1f %s", v, unit[i]);
    }

    /** 服务器返回 2026-11-04T15:25:24.319+08:00 这类时间，转成本地可读格式 */
    public static String time(String iso) {
        if (iso == null || iso.length() < 19) {
            return "";
        }
        try {
            String datePart = iso.substring(0, 19);
            String offset = iso.length() >= 25 ? iso.substring(22) : "";
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            if (offset.matches("[+-]\\d{2}:\\d{2}")) {
                in.setTimeZone(TimeZone.getTimeZone("GMT" + offset));
            } else {
                in.setTimeZone(TimeZone.getDefault());
            }
            Date d = in.parse(datePart);
            if (d == null) {
                return iso;
            }
            SimpleDateFormat out = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
            return out.format(d);
        } catch (Exception e) {
            return iso;
        }
    }

    /** 读取当前主题下某个 attr 的颜色值 */
    public static int color(Context c, int attr) {
        android.util.TypedValue tv = new android.util.TypedValue();
        c.getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }

    // ---------------------------------------------------------------- 路径

    public static String join(String dir, String name) {
        if (dir == null || dir.isEmpty() || "/".equals(dir)) {
            return "/" + name;
        }
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    public static String parent(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return "/";
        }
        int i = path.lastIndexOf('/');
        if (i <= 0) {
            return "/";
        }
        return path.substring(0, i);
    }

    public static String name(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return "/";
        }
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    // ---------------------------------------------------------------- 播放器

    /** 播放器上的时间：mm:ss，超过一小时显示 h:mm:ss */
    public static String clock(long ms) {
        if (ms < 0) {
            ms = 0;
        }
        long total = ms / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) {
            return String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(java.util.Locale.US, "%02d:%02d", m, s);
    }

    /** 音乐封面按文件名散列取色，每首歌的底色都不一样 */
    public static int coverTint(String name) {
        int h = name == null ? 0 : name.hashCode();
        int[] hues = {212, 190, 158, 268, 336, 24, 96};
        float hue = hues[Math.abs(h) % hues.length];
        return android.graphics.Color.HSVToColor(new float[]{hue, 0.42f, 0.55f});
    }
}
