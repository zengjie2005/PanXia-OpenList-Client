package com.zhj.panxia;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.VideoView;

/** 视频 / 音频在线播放：直链带 sign，可直接交给系统播放器组件流式播放。 */
public class MediaActivity extends Activity {

    public static final String EXTRA_DIR = "dir";
    public static final String EXTRA_NAME = "name";

    private Store store;
    private Api api;
    private String dir = "/";
    private String name = "";

    private VideoView video;
    private ProgressBar loadingView;
    private TextView tvError;
    private TextView tvAudio;
    private TextView tvTitle;
    private TextView tvSub;
    private TextView btnRotate;
    private View topBar;

    /** 当前是否锁在横屏（由按钮控制，不再跟随传感器，避免误判） */
    private boolean landscape;
    /** 是否视频（音频不隐藏信息栏，也不需要方向按钮） */
    private boolean videoMode = true;
    /** 信息栏当前是否可见 */
    private boolean barShown = true;
    /** 是否已进入播放（在此之前信息栏保持常显） */
    private boolean started;

    private MediaController controller;
    private final Handler barPoll = new Handler(Looper.getMainLooper());
    private final Runnable barTick = new Runnable() {
        @Override
        public void run() {
            syncBarWithController();
            barPoll.postDelayed(this, 160);
        }
    };

    private AlertDialog progressDialog;
    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        store = new Store(this);
        Theme.apply(this, store);
        super.onCreate(savedInstanceState);

        dir = getIntent().getStringExtra(EXTRA_DIR);
        name = getIntent().getStringExtra(EXTRA_NAME);
        if (dir == null || dir.isEmpty()) {
            dir = "/";
        }
        if (name == null || name.isEmpty()) {
            finish();
            return;
        }

        api = new Api(store);
        Api.applyInsecure(store.insecure());

        setContentView(R.layout.activity_media);
        Theme.install(this, R.id.topBar);
        Bars.icons(this, false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        topBar = findViewById(R.id.topBar);
        video = findViewById(R.id.video);
        loadingView = findViewById(R.id.loading);
        tvError = findViewById(R.id.tvError);
        tvAudio = findViewById(R.id.tvAudio);
        tvTitle = findViewById(R.id.tvTitle);
        tvSub = findViewById(R.id.tvSub);
        btnRotate = findViewById(R.id.btnRotate);

        videoMode = Mime.kind(name) != Mime.Kind.AUDIO;
        tvTitle.setText(name);
        if (!videoMode) {
            tvAudio.setVisibility(View.VISIBLE);
            btnRotate.setVisibility(View.GONE);
        } else {
            // 锁定在进入时的方向：不跟传感器自动翻转，是否横屏完全由按钮决定
            landscape = getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_LANDSCAPE;
            try {
                setRequestedOrientation(landscape
                        ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            } catch (Exception ignored) {
            }
            updateRotateBtn();
        }
        updateSub();

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
        btnRotate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleOrientation();
            }
        });
        Feedback.press(findViewById(R.id.btnBack));
        Feedback.press(findViewById(R.id.btnSave));
        Feedback.press(btnRotate);

        final MediaController mc = new MediaController(this);
        controller = mc;
        mc.setAnchorView(video);
        video.setMediaController(mc);
        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                loadingView.setVisibility(View.GONE);
                video.start();
                // 播放一开始把控制栏和信息栏一起亮出来，3 秒后一起收掉
                mc.show(3000);
                started = true;
                barPoll.removeCallbacks(barTick);
                barPoll.post(barTick);
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                loadingView.setVisibility(View.GONE);
                tvError.setText("播放失败（编码不支持或直链已失效）\n" + hint(what, extra));
                tvError.setVisibility(View.VISIBLE);
                return true;
            }
        });
        video.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                // 播完后控制栏常驻，避免画面停住又点不出按钮
                mc.show(0);
                setBarVisible(true);
            }
        });

        load();
    }

    // ------------------------------------------------------------ 方向控制

    /**
     * 信息栏跟着播放控制栏一起显隐。
     * <p>
     * 系统的 MediaController 没有对外暴露「显示状态变化」的监听，子类化重写 show/hide
     * 又会踩到它内部互相调用的坑，所以直接按固定间隔读它的 isShowing()，
     * 以控制栏的状态为准同步顶部信息栏（160ms 一次，几乎不可感知）。
     */
    private void syncBarWithController() {
        if (!videoMode || !started || controller == null) {
            return;
        }
        setBarVisible(controller.isShowing());
    }

    private void setBarVisible(boolean visible) {
        if (!videoMode || topBar == null) {
            return;
        }
        if (visible == barShown) {
            return;
        }
        barShown = visible;
        topBar.animate().cancel();
        if (visible) {
            topBar.setVisibility(View.VISIBLE);
            topBar.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(180)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        } else {
            topBar.animate()
                    .alpha(0f)
                    .translationY(-Ui.dp(this, 10))
                    .setDuration(200)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            // 必须真正 GONE：否则透明栏仍然占着顶部，点不响控制栏
                            if (!barShown) {
                                topBar.setVisibility(View.GONE);
                            }
                        }
                    })
                    .start();
        }
    }

    private void toggleOrientation() {
        landscape = !landscape;
        try {
            setRequestedOrientation(landscape
                    ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } catch (Exception ignored) {
        }
        updateRotateBtn();
        updateSub();
        Ui.toast(this, landscape ? "已切换为横屏" : "已切换为竖屏");
    }

    /** 按钮上写的是「按下去会变成什么」 */
    private void updateRotateBtn() {
        if (!videoMode) {
            return;
        }
        btnRotate.setText(landscape ? "竖屏" : "横屏");
        btnRotate.setContentDescription(landscape ? "切换为竖屏" : "切换为横屏");
    }

    private void updateSub() {
        String host = Api.hostOf(store.base());
        if (!videoMode) {
            tvSub.setText("音频 · " + host);
        } else {
            tvSub.setText((landscape ? "横屏" : "竖屏") + " · " + host);
        }
    }

    private String hint(int what, int extra) {
        if (what == MediaPlayer.MEDIA_ERROR_UNSUPPORTED) {
            return "这个容器/编码手机不支持，可点右上「保存」后用其他播放器打开";
        }
        if (what == MediaPlayer.MEDIA_ERROR_IO) {
            return "网络读取中断，注意直链有时效";
        }
        return "错误码 " + what + "/" + extra;
    }

    private void load() {
        loadingView.setVisibility(View.VISIBLE);
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
                            video.setVideoURI(Uri.parse(raw));
                            video.requestFocus();
                        }
                    });
                } catch (final Api.ApiError e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            loadingView.setVisibility(View.GONE);
                            tvError.setText(e.getMessage());
                            tvError.setVisibility(View.VISIBLE);
                        }
                    });
                }
            }
        }).start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        barPoll.removeCallbacks(barTick);
        try {
            if (video.isPlaying()) {
                video.pause();
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (started) {
            barPoll.removeCallbacks(barTick);
            barPoll.post(barTick);
            // 回来时如果是暂停状态，把控制栏和信息栏一起亮出来
            if (controller != null && !video.isPlaying()) {
                controller.show(0);
                setBarVisible(true);
            }
        }
    }

    @Override
    protected void onDestroy() {
        barPoll.removeCallbacks(barTick);
        try {
            video.stopPlayback();
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------ 保存

    private void save() {
        showProgress("准备保存 " + name);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String raw = api.rawUrl(Ui.join(dir, name));
                    if (raw.isEmpty()) {
                        throw new Exception("服务器未返回直链");
                    }
                    final String where = Dl.save(MediaActivity.this, raw, name, new Dl.Progress() {
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
                            Ui.toastLong(MediaActivity.this, "已保存到 " + where);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            dismissProgress();
                            Ui.toastLong(MediaActivity.this, "保存失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

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
        progressDialog = new AlertDialog.Builder(this).setView(box).setCancelable(false).create();
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
