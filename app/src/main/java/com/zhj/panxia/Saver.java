package com.zhj.panxia;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * 播放器里的「保存到本机」：带进度对话框，视频和音乐共用一份。
 * 取直链和下载都在子线程，进度回主线程刷新。
 */
final class Saver {

    private Saver() {
    }

    static void save(final Activity a, final Api api, final String dir, final String name) {
        final AlertDialog[] holder = new AlertDialog[1];
        final ProgressBar[] bar = new ProgressBar[1];

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(a, 20);
        box.setPadding(pad, Ui.dp(a, 6), pad, 0);
        TextView label = new TextView(a);
        label.setText("准备保存 " + name);
        label.setTextSize(13);
        label.setTextColor(0xFFEDF2F9);
        box.addView(label);
        bar[0] = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar[0].setMax(100);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 28));
        lp.topMargin = Ui.dp(a, 10);
        box.addView(bar[0], lp);
        holder[0] = new AlertDialog.Builder(a).setView(box).setCancelable(false).create();
        holder[0].show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String raw = api.rawUrl(Ui.join(dir, name));
                    if (raw == null || raw.isEmpty()) {
                        throw new Exception("服务器未返回直链");
                    }
                    final String where = Dl.save(a, raw, name, new Dl.Progress() {
                        @Override
                        public void onProgress(final int percent) {
                            a.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (bar[0] != null) {
                                        bar[0].setProgress(Math.max(0, Math.min(100, percent)));
                                    }
                                }
                            });
                        }
                    });
                    a.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            dismiss(holder[0]);
                            Ui.toastLong(a, "已保存到 " + where);
                        }
                    });
                } catch (final Exception e) {
                    a.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            dismiss(holder[0]);
                            Ui.toastLong(a, "保存失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private static void dismiss(AlertDialog d) {
        if (d != null) {
            try {
                d.dismiss();
            } catch (Exception ignored) {
            }
        }
    }
}
