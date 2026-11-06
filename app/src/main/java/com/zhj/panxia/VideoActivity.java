package com.zhj.panxia;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Locale;

/**
 * 视频播放：SurfaceView + MediaPlayer，控制栏自绘。
 * <p>
 * 触摸操作：单击画面呼出/收起控制栏，双击左半边退十秒、右半边进十秒、中间暂停，
 * 进度条可以直接按住拖。右上角按钮切换横竖屏，横屏由按钮决定，不跟随传感器自动翻转。
 */
public class VideoActivity extends Activity
        implements SurfaceHolder.Callback, SeekLine.OnSeekListener {

    public static final String EXTRA_DIR = "dir";
    public static final String EXTRA_NAMES = "names";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_INDEX = "index";

    private static final long HIDE_DELAY = 4000;
    private static final float[] SPEEDS = {0.75f, 1f, 1.25f, 1.5f, 2f};
    private static final String[] SPEED_LABELS = {"0.75×", "1.0×", "1.25×", "1.5×", "2.0×"};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Store store;
    private Api api;
    private AudioFocus focus;
    private String dir = "/";
    private ArrayList<String> names = new ArrayList<>();
    private int index;
    private int aspect = 0;
    private float speed = 1f;
    private int speedIndex = 1;
    private boolean landscape;

    private MediaPlayer mp;
    private SurfaceHolder holder;
    private boolean surfaceReady;
    private boolean prepared;
    private boolean controlsVisible = true;
    private boolean dragging;
    private boolean ended;
    private String pendingUrl;
    private int videoW;
    private int videoH;
    private long lastSeekAt;
    private int seekStep;

    private FrameLayout box;
    private SurfaceView surface;
    private View topBar;
    private View bottomBar;
    private View buffer;
    private TextView tvTitle;
    private TextView tvSub;
    private TextView tvPos;
    private TextView tvDur;
    private TextView tvError;
    private TextView tvToast;
    private TextView tvSkip;
    private TextView btnSpeed;
    private TextView btnAspect;
    private TextView btnRotate;
    private ImageView btnPlay;
    private SeekLine seek;
    private GestureDetector gestures;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (mp != null && prepared) {
                ui.postDelayed(this, 400);
            }
        }
    };

    private final Runnable hideControls = new Runnable() {
        @Override
        public void run() {
            if (isPlaying()) {
                setControls(false);
            }
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
        ArrayList<String> list = getIntent().getStringArrayListExtra(EXTRA_NAMES);
        if (list != null && !list.isEmpty()) {
            names = list;
        } else {
            String[] arr = getIntent().getStringArrayExtra(EXTRA_NAMES);
            if (arr != null) {
                java.util.Collections.addAll(names, arr);
            }
            String one = getIntent().getStringExtra(EXTRA_NAME);
            if (names.isEmpty() && one != null && !one.isEmpty()) {
                names.add(one);
            }
        }
        if (names.isEmpty()) {
            finish();
            return;
        }
        index = Math.max(0, Math.min(getIntent().getIntExtra(EXTRA_INDEX, 0), names.size() - 1));

        api = new Api(store);
        Api.applyInsecure(store.insecure());
        focus = new AudioFocus(this);
        speed = store.speed();
        speedIndex = nearestSpeed(speed);

        setContentView(R.layout.activity_video);
        Theme.install(this, R.id.topBar);
        Bars.icons(this, false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 保持进入时的方向，横竖屏完全由按钮决定
        landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        try {
            setRequestedOrientation(landscape
                    ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } catch (Exception ignored) {
        }

        box = findViewById(R.id.videoBox);
        surface = findViewById(R.id.surface);
        topBar = findViewById(R.id.topBar);
        bottomBar = findViewById(R.id.bottomBar);
        buffer = findViewById(R.id.buffer);
        tvTitle = findViewById(R.id.tvTitle);
        tvSub = findViewById(R.id.tvSub);
        tvPos = findViewById(R.id.tvPos);
        tvDur = findViewById(R.id.tvDur);
        tvError = findViewById(R.id.tvError);
        tvToast = findViewById(R.id.tvToast);
        tvSkip = findViewById(R.id.tvSkip);
        btnSpeed = findViewById(R.id.btnSpeed);
        btnAspect = findViewById(R.id.btnAspect);
        btnRotate = findViewById(R.id.btnRotate);
        btnPlay = findViewById(R.id.btnPlay);
        seek = findViewById(R.id.seek);

        holder = surface.getHolder();
        holder.addCallback(this);

        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlay();
            }
        });
        click(R.id.btnBack, new Runnable() {
            @Override
            public void run() {
                finish();
            }
        });
        click(R.id.btnSave, new Runnable() {
            @Override
            public void run() {
                save();
            }
        });
        click(R.id.btnRotate, new Runnable() {
            @Override
            public void run() {
                toggleOrientation();
            }
        });
        click(R.id.btnPrev, new Runnable() {
            @Override
            public void run() {
                playIndex(index - 1);
            }
        });
        click(R.id.btnNext, new Runnable() {
            @Override
            public void run() {
                playIndex(index + 1);
            }
        });
        click(R.id.btnRew, new Runnable() {
            @Override
            public void run() {
                seekRelative(-10000);
            }
        });
        click(R.id.btnFf, new Runnable() {
            @Override
            public void run() {
                seekRelative(10000);
            }
        });
        click(R.id.btnSpeed, new Runnable() {
            @Override
            public void run() {
                chooseSpeed();
            }
        });
        click(R.id.btnAspect, new Runnable() {
            @Override
            public void run() {
                cycleAspect();
            }
        });

        seek.setOnSeekListener(this);
        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                box.performClick();
                setControls(!controlsVisible);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                float w = box.getWidth();
                if (e.getX() < w * 0.35f) {
                    seekRelative(-10000, true);
                } else if (e.getX() > w * 0.65f) {
                    seekRelative(10000, true);
                } else {
                    togglePlay();
                }
                return true;
            }
        });
        box.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                return gestures.onTouchEvent(e);
            }
        });
        box.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                applyAspect();
            }
        });

        btnSpeed.setText(speedText());
        btnAspect.setText(aspectText());
        updateRotateBtn();
        updateTitle();
        load(index, true);
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                setControls(true);
            }
        }, 80);
    }

    private void click(int id, final Runnable action) {
        View v = findViewById(id);
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                action.run();
            }
        });
        Feedback.press(v);
    }

    // ------------------------------------------------------------ 载入

    private void load(final int i, final boolean allowResume) {
        if (i < 0 || i >= names.size()) {
            toast("没有更多的视频了");
            return;
        }
        index = i;
        ended = false;
        prepared = false;
        pendingUrl = null;
        videoW = 0;
        videoH = 0;
        tvError.setVisibility(View.GONE);
        buffer.setVisibility(View.VISIBLE);
        updateTitle();
        release();
        final String name = names.get(i);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String url = api.rawUrl(Ui.join(dir, name));
                    if (url == null || url.isEmpty()) {
                        throw new Api.ApiError(-1, "服务器没有返回可播放的直链");
                    }
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (index != i) {
                                return;
                            }
                            pendingUrl = url;
                            prepareIfReady(allowResume);
                        }
                    });
                } catch (final Api.ApiError e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            buffer.setVisibility(View.GONE);
                            showError(e.getMessage() == null ? "获取播放地址失败" : e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void prepareIfReady(final boolean allowResume) {
        if (!surfaceReady || pendingUrl == null) {
            return;
        }
        release();
        try {
            mp = new MediaPlayer();
            mp.setDataSource(pendingUrl);
            mp.setDisplay(holder);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer player) {
                    prepared = true;
                    videoW = player.getVideoWidth();
                    videoH = player.getVideoHeight();
                    applyAspect();
                    buffer.setVisibility(View.GONE);
                    long resume = store.resumeAt(Ui.join(dir, names.get(index)));
                    long dur = player.getDuration();
                    if (resume > 15000 && dur > 0 && resume < dur - 15000) {
                        player.seekTo((int) resume);
                        toast("从上次位置继续播放");
                    }
                    applySpeed();
                    focus.request(new AudioFocus.OnLoss() {
                        @Override
                        public void onFocusLoss() {
                            onAudioLost();
                        }
                    });
                    player.start();
                    btnPlay.setImageResource(R.drawable.ic_pause);
                    updateTitle();
                    updateProgress();
                    ui.removeCallbacks(tick);
                    ui.post(tick);
                    setControls(true);
                }
            });
            mp.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                @Override
                public boolean onInfo(MediaPlayer player, int what, int extra) {
                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                        buffer.setVisibility(View.VISIBLE);
                    } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                        buffer.setVisibility(View.GONE);
                    }
                    return false;
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer player, int what, int extra) {
                    prepared = false;
                    buffer.setVisibility(View.GONE);
                    showError("播放失败（" + what + "/" + extra + "）\n可能是编码不受支持，或直链已过期");
                    return true;
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer player) {
                    store.clearResume(Ui.join(dir, names.get(index)));
                    if (index + 1 < names.size()) {
                        toast("自动播放下一个");
                        load(index + 1, false);
                    } else {
                        ended = true;
                        prepared = false;
                        buffer.setVisibility(View.GONE);
                        btnPlay.setImageResource(R.drawable.ic_play);
                        setControls(true);
                        toast("已经是最后一个视频");
                    }
                }
            });
            mp.prepareAsync();
        } catch (Exception e) {
            buffer.setVisibility(View.GONE);
            showError("无法播放：" + e.getMessage());
        }
    }

    private void release() {
        ui.removeCallbacks(tick);
        if (focus != null) {
            focus.abandon();
        }
        if (mp != null) {
            try {
                mp.reset();
                mp.release();
            } catch (Exception ignored) {
            }
            mp = null;
        }
        prepared = false;
    }

    // ------------------------------------------------------------ 播放控制

    /** 焦点被别的应用抢走：停下来，别和来电或别的播放器抢声音 */
    private void onAudioLost() {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (isPlaying()) {
                    try {
                        mp.pause();
                    } catch (Exception ignored) {
                    }
                    btnPlay.setImageResource(R.drawable.ic_play);
                    setControls(true);
                    toast("其他应用正在使用声音，已暂停");
                }
            }
        });
    }

    private boolean isPlaying() {
        try {
            return mp != null && prepared && mp.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    private void togglePlay() {
        if (mp == null || !prepared) {
            if (ended) {
                load(index, false);
            }
            return;
        }
        try {
            if (mp.isPlaying()) {
                mp.pause();
                btnPlay.setImageResource(R.drawable.ic_play);
                setControls(true);
            } else {
                mp.start();
                btnPlay.setImageResource(R.drawable.ic_pause);
                updateProgress();
                setControls(true);
            }
        } catch (Exception ignored) {
        }
    }

    private void playIndex(int i) {
        if (i < 0) {
            toast("已经是第一个");
            return;
        }
        if (i >= names.size()) {
            toast("已经是最后一个");
            return;
        }
        setControls(true);
        load(i, false);
    }

    private void seekRelative(int delta) {
        seekRelative(delta, false);
    }

    /** 连按加速：10 秒 → 30 → 60 → 120 */
    private void seekRelative(int delta, boolean flash) {
        if (mp == null || !prepared) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSeekAt > 1300) {
            seekStep = 0;
        } else {
            seekStep = Math.min(seekStep + 1, 3);
        }
        lastSeekAt = now;
        int[] steps = {10000, 30000, 60000, 120000};
        int amount = steps[seekStep];
        if (delta < 0) {
            amount = -amount;
        }
        try {
            long dur = mp.getDuration();
            long target = mp.getCurrentPosition() + amount;
            if (target < 0) {
                target = 0;
            }
            if (dur > 0 && target > dur - 1000) {
                target = Math.max(0, dur - 1000);
            }
            mp.seekTo((int) target);
            updateProgress();
            String text = (delta > 0 ? "快进 " : "快退 ") + Math.abs(amount) / 1000 + " 秒";
            toast(text);
            if (flash) {
                tvSkip.setText(text);
                tvSkip.setAlpha(1f);
                tvSkip.animate().cancel();
                tvSkip.animate().alpha(0f).setStartDelay(450).setDuration(280).start();
            }
            setControls(true);
        } catch (Exception ignored) {
        }
    }

    private void chooseSpeed() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("播放速度")
                .setSingleChoiceItems(SPEED_LABELS, speedIndex,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int which) {
                                speedIndex = which;
                                speed = SPEEDS[which];
                                store.speed(speed);
                                btnSpeed.setText(speedText());
                                applySpeed();
                                d.dismiss();
                                setControls(true);
                            }
                        })
                .show();
        setControls(true);
    }

    private void applySpeed() {
        if (mp == null || !prepared) {
            return;
        }
        try {
            android.media.PlaybackParams params = mp.getPlaybackParams();
            params.setSpeed(speed);
            mp.setPlaybackParams(params);
        } catch (Exception ignored) {
        }
    }

    private String speedText() {
        return SPEED_LABELS[nearestSpeed(speed)];
    }

    private int nearestSpeed(float s) {
        int best = 1;
        float diff = Float.MAX_VALUE;
        for (int i = 0; i < SPEEDS.length; i++) {
            float d = Math.abs(SPEEDS[i] - s);
            if (d < diff) {
                diff = d;
                best = i;
            }
        }
        return best;
    }

    private void cycleAspect() {
        aspect = (aspect + 1) % 3;
        btnAspect.setText(aspectText());
        applyAspect();
        toast("画面：" + aspectText());
        setControls(true);
    }

    private String aspectText() {
        switch (aspect) {
            case 1:
                return "拉伸";
            case 2:
                return "裁剪";
            default:
                return "适应";
        }
    }

    private void applyAspect() {
        if (videoW <= 0 || videoH <= 0 || box.getWidth() == 0) {
            return;
        }
        int boxW = box.getWidth();
        int boxH = box.getHeight();
        float ratio = videoW / (float) videoH;
        int w;
        int h;
        if (aspect == 1) {
            w = boxW;
            h = boxH;
        } else if (aspect == 2) {
            if (boxW / (float) boxH > ratio) {
                w = boxW;
                h = (int) (boxW / ratio);
            } else {
                h = boxH;
                w = (int) (boxH * ratio);
            }
        } else {
            if (boxW / (float) boxH > ratio) {
                h = boxH;
                w = (int) (boxH * ratio);
            } else {
                w = boxW;
                h = (int) (boxW / ratio);
            }
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.gravity = android.view.Gravity.CENTER;
        surface.setLayoutParams(lp);
    }

    private void updateProgress() {
        if (mp == null || !prepared || dragging) {
            return;
        }
        try {
            int pos = mp.getCurrentPosition();
            int dur = mp.getDuration();
            tvPos.setText(Ui.clock(pos));
            tvDur.setText(Ui.clock(dur));
            seek.setProgress(pos, dur);
        } catch (Exception ignored) {
        }
    }

    private void updateTitle() {
        tvTitle.setText(names.get(index));
        String res = videoW > 0 ? (videoW + "×" + videoH) : "";
        String pos = names.size() > 1
                ? String.format(Locale.getDefault(), "第 %d/%d 个 · ", index + 1, names.size())
                : "";
        String tail = res.isEmpty() ? "" : " · " + res;
        tvSub.setText(pos + dir + tail);
    }

    private void showError(String msg) {
        tvError.setText(msg + "\n\n点画面重新载入，右上角可保存后用其他播放器打开");
        tvError.setVisibility(View.VISIBLE);
        tvError.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                load(index, false);
            }
        });
        setControls(true);
    }

    private void toast(String text) {
        tvToast.setText(text);
        tvToast.animate().cancel();
        tvToast.setAlpha(1f);
        tvToast.animate().alpha(0f).setStartDelay(1100).setDuration(400).start();
    }

    private void setControls(boolean show) {
        controlsVisible = show;
        ui.removeCallbacks(hideControls);
        topBar.animate().cancel();
        bottomBar.animate().cancel();
        if (show) {
            topBar.setVisibility(View.VISIBLE);
            bottomBar.setVisibility(View.VISIBLE);
            topBar.animate().alpha(1f).setDuration(150).start();
            bottomBar.animate().alpha(1f).setDuration(150).start();
            seek.setActive(true);
            if (isPlaying()) {
                ui.postDelayed(hideControls, HIDE_DELAY);
            }
        } else {
            topBar.animate().alpha(0f).setDuration(200).withEndAction(new Runnable() {
                @Override
                public void run() {
                    if (!controlsVisible) {
                        topBar.setVisibility(View.GONE);
                    }
                }
            }).start();
            bottomBar.animate().alpha(0f).setDuration(200).withEndAction(new Runnable() {
                @Override
                public void run() {
                    if (!controlsVisible) {
                        bottomBar.setVisibility(View.GONE);
                    }
                }
            }).start();
        }
    }

    // ------------------------------------------------------------ 拖动进度条

    @Override
    public void onSeekStart() {
        dragging = true;
        ui.removeCallbacks(hideControls);
    }

    @Override
    public void onSeekMove(long position) {
        tvPos.setText(Ui.clock(position));
        try {
            if (mp != null && prepared) {
                tvDur.setText(Ui.clock(mp.getDuration()));
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onSeekEnd(long position) {
        dragging = false;
        try {
            if (mp != null && prepared) {
                mp.seekTo((int) position);
            }
        } catch (Exception ignored) {
        }
        updateProgress();
        setControls(true);
    }

    // ------------------------------------------------------------ 方向

    private void toggleOrientation() {
        landscape = !landscape;
        try {
            setRequestedOrientation(landscape
                    ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } catch (Exception ignored) {
        }
        updateRotateBtn();
        updateTitle();
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                applyAspect();
            }
        }, 300);
    }

    private void updateRotateBtn() {
        btnRotate.setText(landscape ? "竖屏" : "横屏");
        btnRotate.setContentDescription(landscape ? "切换为竖屏" : "切换为横屏");
    }

    // ------------------------------------------------------------ 保存

    private void save() {
        Saver.save(this, api, dir, names.get(index));
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        surfaceReady = true;
        prepareIfReady(true);
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        surfaceReady = false;
        saveResume();
        release();
    }

    private void saveResume() {
        if (mp == null || !prepared) {
            return;
        }
        try {
            int pos = mp.getCurrentPosition();
            int dur = mp.getDuration();
            String path = Ui.join(dir, names.get(index));
            if (pos > 10000 && dur > 0 && pos < dur - 15000) {
                store.resume(path, pos);
            } else {
                store.clearResume(path);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isPlaying()) {
            try {
                mp.pause();
                btnPlay.setImageResource(R.drawable.ic_play);
            } catch (Exception ignored) {
            }
        }
        saveResume();
        setControls(true);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        saveResume();
        release();
        super.onDestroy();
    }
}
