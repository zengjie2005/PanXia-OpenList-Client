package com.zhj.panxia;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 图片预览：双指缩放 / 双击放大 / 左右滑动切换同目录图片 / 旋转 / 保存 / 分享。 */
public class ImageActivity extends Activity {

    public static final String EXTRA_DIR = "dir";
    public static final String EXTRA_NAMES = "names";
    public static final String EXTRA_INDEX = "index";

    private Store store;
    private Api api;

    private final List<String> names = new ArrayList<>();
    private String dir = "/";
    private int index;
    private int rotation;
    private int seq;

    private ZoomImageView img;
    private ProgressBar loadingView;
    private TextView tvTitle;
    private TextView tvSub;
    private TextView tvIndex;
    private TextView tvError;

    /** 正在播放切换动画（期间屏蔽手势，避免动画互相打断） */
    private boolean transitioning;

    private AlertDialog progressDialog;
    private ProgressBar progressBar;

    /** 只缓存最近几张，避免内存吃紧 */
    private final Map<String, Bitmap> cache = new LinkedHashMap<String, Bitmap>(4, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > 3;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        store = new Store(this);
        Theme.apply(this, store);
        super.onCreate(savedInstanceState);

        dir = getIntent().getStringExtra(EXTRA_DIR);
        if (dir == null || dir.isEmpty()) {
            dir = "/";
        }
        String[] arr = getIntent().getStringArrayExtra(EXTRA_NAMES);
        if (arr != null) {
            names.addAll(Arrays.asList(arr));
        }
        index = Math.max(0, Math.min(getIntent().getIntExtra(EXTRA_INDEX, 0), Math.max(0, names.size() - 1)));

        api = new Api(store);
        Api.applyInsecure(store.insecure());

        setContentView(R.layout.activity_image);
        Theme.install(this, R.id.topBar);
        Bars.icons(this, false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        img = findViewById(R.id.img);
        loadingView = findViewById(R.id.loading);
        tvTitle = findViewById(R.id.tvTitle);
        tvSub = findViewById(R.id.tvSub);
        tvIndex = findViewById(R.id.tvIndex);
        tvError = findViewById(R.id.tvError);

        img.setOnSwipe(new ZoomImageView.OnSwipe() {
            @Override
            public void onSwipe(int d) {
                if (d > 0) {
                    if (index + 1 < names.size()) {
                        show(index + 1, 1);
                    }
                } else {
                    if (index > 0) {
                        show(index - 1, -1);
                    }
                }
            }
        });

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btnSave).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        findViewById(R.id.btnShare).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                share();
            }
        });
        findViewById(R.id.btnMore).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMore(v);
            }
        });
        int[] barIds = {R.id.btnBack, R.id.btnSave, R.id.btnShare, R.id.btnMore};
        for (int id : barIds) {
            Feedback.press(findViewById(id));
        }

        if (names.isEmpty()) {
            finish();
            return;
        }
        show(index);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 切后台可能把切换动画掐断，这里兜底复位，避免手势被永久锁住
        transitioning = false;
        if (img != null) {
            img.setSwipeEnabled(true);
            img.resetSwipeOffset();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        for (Bitmap b : cache.values()) {
            if (b != null && !b.isRecycled()) {
                b.recycle();
            }
        }
        cache.clear();
    }

    // ------------------------------------------------------------ 展示

    private void show(int i) {
        show(i, 0);
    }

    /**
     * 显示第 i 张。
     *
     * @param from 0 = 直接显示（首次进入）；1 = 从下一张切过来（旧图向左出、新图从右入）；
     *             -1 = 从上一张切过来（旧图向右出、新图从左入）
     */
    private void show(final int i, final int from) {
        if (transitioning) {
            return;
        }
        index = i;
        final String name = names.get(i);
        tvTitle.setText(name);
        tvSub.setText((i + 1) + " / " + names.size() + " · " + Api.hostOf(store.base()));
        tvIndex.setText("左右滑动切换 · 双击放大");
        tvError.setVisibility(View.GONE);
        rotation = 0;
        img.setRotation(0);
        img.setSwipeBounds(i > 0, i + 1 < names.size());

        Bitmap cached;
        synchronized (cache) {
            cached = cache.get(name);
        }
        if (cached != null && !cached.isRecycled()) {
            loadingView.setVisibility(View.GONE);
            if (from == 0) {
                img.resetSwipeOffset();
                img.setImageBitmap(cached);
            } else {
                swapTo(cached, from);
            }
            return;
        }

        // 没缓存：旧图先按方向滑出去，加载圈顶上，新图到了再从另一侧滑进来，
        // 全程不出现「黑屏硬切」
        final int my = ++seq;
        final int enterFrom = from;
        if (from != 0) {
            transitioning = true;
            img.setSwipeEnabled(false);
            slideOut(from, new Runnable() {
                @Override
                public void run() {
                    if (my == seq) {
                        loadingView.setVisibility(View.VISIBLE);
                    }
                }
            });
        } else {
            loadingView.setVisibility(View.VISIBLE);
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = null;
                String err = null;
                try {
                    String raw = api.rawUrl(Ui.join(dir, name));
                    if (raw.isEmpty()) {
                        throw new Exception("服务器未返回直链");
                    }
                    int w = getResources().getDisplayMetrics().widthPixels;
                    int h = getResources().getDisplayMetrics().heightPixels;
                    bmp = Net.image(raw, w, h);
                    if (bmp == null) {
                        err = "图片解码失败，可能是该格式不受支持";
                    }
                } catch (Exception e) {
                    err = e.getMessage() == null ? "加载失败" : e.getMessage();
                } catch (OutOfMemoryError e) {
                    err = "图片太大，内存不够";
                }
                final Bitmap fb = bmp;
                final String fe = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (my != seq) {
                            return;
                        }
                        loadingView.setVisibility(View.GONE);
                        if (fb == null) {
                            transitioning = false;
                            img.resetSwipeOffset();
                            img.setSwipeEnabled(true);
                            tvError.setText(fe);
                            tvError.setVisibility(View.VISIBLE);
                            return;
                        }
                        synchronized (cache) {
                            cache.put(name, fb);
                        }
                        if (enterFrom != 0) {
                            enterWith(fb, enterFrom);
                        } else {
                            img.resetSwipeOffset();
                            img.setImageBitmap(fb);
                        }
                    }
                });
                if (fb != null) {
                    prefetch(index + 1);
                    prefetch(index - 1);
                }
            }
        }).start();
    }

    // ------------------------------------------------------------ 切换动画

    /** 旧图沿手势方向继续滑出（同时略微缩小淡出，做出纵深） */
    private void slideOut(final int from, final Runnable end) {
        final float w = Math.max(1, img.getWidth());
        img.animate().cancel();
        img.animate()
                .translationX(from > 0 ? -w * 0.95f : w * 0.95f)
                .alpha(0f)
                .scaleX(0.9f)
                .scaleY(0.9f)
                .setDuration(150)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .withEndAction(end)
                .start();
    }

    /** 新图从反方向滑入并轻微放大到原尺寸，和出场动作接上 */
    private void enterWith(final Bitmap bmp, final int from) {
        final float w = Math.max(1, img.getWidth());
        img.animate().cancel();
        img.setImageBitmap(bmp);
        img.setTranslationX(from > 0 ? w * 0.3f : -w * 0.3f);
        img.setAlpha(0f);
        img.setScaleX(0.93f);
        img.setScaleY(0.93f);
        img.animate()
                .translationX(0f)
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(240)
                .setInterpolator(new DecelerateInterpolator(1.4f))
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        transitioning = false;
                        img.setSwipeEnabled(true);
                    }
                })
                .start();
    }

    /** 图片已在缓存里：出场 → 换图 → 入场，一气呵成 */
    private void swapTo(final Bitmap bmp, final int from) {
        transitioning = true;
        img.setSwipeEnabled(false);
        slideOut(from, new Runnable() {
            @Override
            public void run() {
                enterWith(bmp, from);
            }
        });
    }

    private void prefetch(final int i) {
        if (i < 0 || i >= names.size()) {
            return;
        }
        final String name = names.get(i);
        synchronized (cache) {
            if (cache.containsKey(name)) {
                return;
            }
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String raw = api.rawUrl(Ui.join(dir, name));
                    if (raw.isEmpty()) {
                        return;
                    }
                    // 预取的图之后会直接上屏，必须按真实显示尺寸解，否则翻过去是糊的
                    int w = getResources().getDisplayMetrics().widthPixels;
                    int h = getResources().getDisplayMetrics().heightPixels;
                    Bitmap b = Net.image(raw, w, h);
                    if (b != null) {
                        synchronized (cache) {
                            cache.put(name, b);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    // ------------------------------------------------------------ 操作

    private void showMore(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add("旋转 90°");
        pm.getMenu().add("重置缩放");
        pm.getMenu().add("保存到本机");
        pm.getMenu().add("复制直链");
        pm.getMenu().add("用浏览器打开");
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                String t = item.getTitle().toString();
                if ("旋转 90°".equals(t)) {
                    rotation = (rotation + 90) % 360;
                    img.resetZoom();
                    img.setRotation(rotation);
                } else if ("重置缩放".equals(t)) {
                    img.resetZoom();
                } else if ("保存到本机".equals(t)) {
                    save();
                } else if ("复制直链".equals(t)) {
                    withUrl(new UrlAction() {
                        @Override
                        public void run(String url) {
                            Ui.copy(ImageActivity.this, "直链", url);
                        }
                    });
                } else if ("用浏览器打开".equals(t)) {
                    withUrl(new UrlAction() {
                        @Override
                        public void run(String url) {
                            try {
                                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                            } catch (Exception e) {
                                Ui.toast(ImageActivity.this, "没有可用的浏览器");
                            }
                        }
                    });
                }
                return true;
            }
        });
        pm.show();
    }

    private interface UrlAction {
        void run(String url);
    }

    private void withUrl(final UrlAction cb) {
        final String name = names.get(index);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String raw = api.rawUrl(Ui.join(dir, name));
                    if (raw.isEmpty()) {
                        throw new Api.ApiError(-1, "服务器未返回直链");
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            cb.run(raw);
                        }
                    });
                } catch (final Api.ApiError e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Ui.toastLong(ImageActivity.this, e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void share() {
        withUrl(new UrlAction() {
            @Override
            public void run(String url) {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_SUBJECT, names.get(index));
                i.putExtra(Intent.EXTRA_TEXT, names.get(index) + "\n" + url);
                try {
                    startActivity(Intent.createChooser(i, "分享链接"));
                } catch (Exception e) {
                    Ui.toast(ImageActivity.this, "没有可分享的应用");
                }
            }
        });
    }

    private void save() {
        final String name = names.get(index);
        showProgress("准备保存 " + name);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String raw = api.rawUrl(Ui.join(dir, name));
                    if (raw.isEmpty()) {
                        throw new Exception("服务器未返回直链");
                    }
                    final String where = Dl.save(ImageActivity.this, raw, name, new Dl.Progress() {
                        @Override
                        public void onProgress(final int percent) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    updateProgress(percent);
                                }
                            });
                        }
                    });
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            dismissProgress();
                            Ui.toastLong(ImageActivity.this, "已保存到 " + where);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            dismissProgress();
                            Ui.toastLong(ImageActivity.this, "保存失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    // ------------------------------------------------------------ 进度

    private TextView progressLabel;

    private void showProgress(String text) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        box.setPadding(pad, Ui.dp(this, 6), pad, 0);
        progressLabel = new TextView(this);
        progressLabel.setText(text);
        progressLabel.setTextSize(13);
        progressLabel.setTextColor(Ui.color(this, R.attr.olText));
        box.addView(progressLabel);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 28));
        lp.topMargin = Ui.dp(this, 10);
        box.addView(progressBar, lp);
        progressDialog = new AlertDialog.Builder(this)
                .setView(box)
                .setCancelable(false)
                .create();
        progressDialog.show();
    }

    private void updateProgress(int percent) {
        if (progressDialog != null && progressDialog.isShowing() && progressBar != null) {
            progressBar.setProgress(Math.max(0, Math.min(100, percent)));
        }
    }

    private void dismissProgress() {
        if (progressDialog != null) {
            try {
                progressDialog.dismiss();
            } catch (Exception ignored) {
            }
            progressDialog = null;
        }
    }
}
