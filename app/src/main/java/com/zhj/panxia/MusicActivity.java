package com.zhj.panxia;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 音乐播放：进入时所在目录里的音频就是播放列表。
 * <p>
 * 歌词优先用同目录同名 .lrc，没有就联网取（免费歌词库），取到后缓存在本地，
 * 下次即使离线也能滚动显示。
 */
public class MusicActivity extends Activity implements SeekLine.OnSeekListener {

    public static final String EXTRA_DIR = "dir";
    public static final String EXTRA_NAMES = "names";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_INDEX = "index";

    private static final String[] LOOP_LABEL = {"顺序播放", "列表循环", "单曲循环", "随机播放"};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private Store store;
    private Api api;
    private AudioFocus focus;
    private String dir = "/";
    private ArrayList<String> names = new ArrayList<>();
    private int index;
    private int loopMode = 1;
    private MediaPlayer mp;
    private boolean prepared;
    private boolean dragging;
    private Lrc lrc;
    private int lyricIndex = -2;
    private List<String> dirEntries;
    private java.util.Map<String, Long> dirSizes;
    private long currentSize;

    private FrameLayout cover;
    private TextView tvInitial;
    private TextView tvTitle;
    private TextView tvSub;
    private TextView[] lyrics;
    private TextView tvNoLyric;
    private TextView tvPos;
    private TextView tvDur;
    private TextView tvSource;
    private TextView btnLoop;
    private ImageView btnPlay;
    private SeekLine seek;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            ui.postDelayed(this, 250);
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

        loopMode = store.loopMode();
        api = new Api(store);
        Api.applyInsecure(store.insecure());
        focus = new AudioFocus(this);

        setContentView(R.layout.activity_music);
        Theme.install(this, R.id.topBar);
        Bars.icons(this, false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        cover = findViewById(R.id.cover);
        tvInitial = findViewById(R.id.tvInitial);
        tvTitle = findViewById(R.id.tvTitle);
        tvSub = findViewById(R.id.tvSub);
        tvNoLyric = findViewById(R.id.tvNoLyric);
        tvPos = findViewById(R.id.tvPos);
        tvDur = findViewById(R.id.tvDur);
        tvSource = findViewById(R.id.tvSource);
        btnLoop = findViewById(R.id.btnLoop);
        btnPlay = findViewById(R.id.btnPlay);
        seek = findViewById(R.id.seek);
        lyrics = new TextView[]{
                findViewById(R.id.ly0), findViewById(R.id.ly1), findViewById(R.id.ly2),
                findViewById(R.id.ly3), findViewById(R.id.ly4)
        };

        click(R.id.btnBack, new Runnable() {
            @Override
            public void run() {
                finish();
            }
        });
        click(R.id.btnPrev, new Runnable() {
            @Override
            public void run() {
                prev();
            }
        });
        click(R.id.btnNext, new Runnable() {
            @Override
            public void run() {
                next(true);
            }
        });
        click(R.id.btnPlay, new Runnable() {
            @Override
            public void run() {
                toggle();
            }
        });
        click(R.id.btnLoop, new Runnable() {
            @Override
            public void run() {
                cycleLoop();
            }
        });
        click(R.id.btnList, new Runnable() {
            @Override
            public void run() {
                showPlaylist();
            }
        });
        click(R.id.btnSave, new Runnable() {
            @Override
            public void run() {
                Saver.save(MusicActivity.this, api, dir, names.get(index));
            }
        });
        click(R.id.btnMore, new Runnable() {
            @Override
            public void run() {
                showOptions();
            }
        });

        seek.setOnSeekListener(this);
        seek.setActive(true);
        btnLoop.setText(LOOP_LABEL[loopMode]);
        start(index);
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

    // ------------------------------------------------------------ 播放

    private void start(final int i) {
        if (i < 0 || i >= names.size()) {
            return;
        }
        index = i;
        prepared = false;
        lrc = null;
        lyricIndex = -2;
        paintLyrics();
        tvNoLyric.setVisibility(View.GONE);
        tvSource.setText("正在获取…");
        release();
        updateHeader();
        final String name = names.get(i);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String url = api.rawUrl(Ui.join(dir, name));
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (index == i) {
                                prepare(url);
                            }
                        }
                    });
                } catch (final Api.ApiError e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            tvSource.setText("播放失败：" + e.getMessage());
                            tvNoLyric.setVisibility(View.VISIBLE);
                            tvNoLyric.setText("无法获取播放地址：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
        loadLyrics(i, name);
    }

    private void prepare(String url) {
        release();
        try {
            mp = new MediaPlayer();
            mp.setDataSource(url);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer player) {
                    prepared = true;
                    focus.request(new AudioFocus.OnLoss() {
                        @Override
                        public void onFocusLoss() {
                            onAudioLost();
                        }
                    });
                    player.start();
                    btnPlay.setImageResource(R.drawable.ic_pause);
                    tvDur.setText(Ui.clock(player.getDuration()));
                    ui.removeCallbacks(tick);
                    ui.post(tick);
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer player) {
                    onTrackEnd();
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer player, int what, int extra) {
                    prepared = false;
                    btnPlay.setImageResource(R.drawable.ic_play);
                    tvSource.setText("播放失败，格式可能不受支持");
                    return true;
                }
            });
            mp.prepareAsync();
        } catch (Exception e) {
            tvSource.setText("播放失败：" + e.getMessage());
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

    /** 焦点被抢：暂停并更新按钮，不自动续播（来电话后不该自己响起来） */
    private void onAudioLost() {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (playing()) {
                    try {
                        mp.pause();
                    } catch (Exception ignored) {
                    }
                    btnPlay.setImageResource(R.drawable.ic_play);
                    tvSource.setText("其他应用正在使用声音，已暂停");
                }
            }
        });
    }

    private boolean playing() {
        try {
            return mp != null && prepared && mp.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    private void toggle() {
        if (mp == null || !prepared) {
            start(index);
            return;
        }
        try {
            if (mp.isPlaying()) {
                mp.pause();
                btnPlay.setImageResource(R.drawable.ic_play);
            } else {
                mp.start();
                btnPlay.setImageResource(R.drawable.ic_pause);
                ui.removeCallbacks(tick);
                ui.post(tick);
            }
        } catch (Exception ignored) {
        }
    }

    private void prev() {
        if (index > 0) {
            start(index - 1);
        } else if (loopMode == 1 || loopMode == 3) {
            start(names.size() - 1);
        } else {
            Ui.toast(this, "已经是第一首");
        }
    }

    private void next(boolean manual) {
        if (names.size() <= 1) {
            if (loopMode == 2 && prepared) {
                try {
                    mp.seekTo(0);
                    mp.start();
                } catch (Exception ignored) {
                }
            }
            return;
        }
        if (loopMode == 3) {
            int n;
            do {
                n = random.nextInt(names.size());
            } while (n == index);
            start(n);
            return;
        }
        if (index + 1 < names.size()) {
            start(index + 1);
            return;
        }
        if (loopMode == 1 || manual) {
            start(0);
        } else {
            btnPlay.setImageResource(R.drawable.ic_play);
            tvSource.setText("播放列表已结束");
        }
    }

    private void onTrackEnd() {
        if (loopMode == 2) {
            start(index);
        } else {
            next(false);
        }
    }

    private void cycleLoop() {
        loopMode = (loopMode + 1) % LOOP_LABEL.length;
        store.loopMode(loopMode);
        btnLoop.setText(LOOP_LABEL[loopMode]);
        Ui.toast(this, LOOP_LABEL[loopMode]);
    }

    // ------------------------------------------------------------ 拖动进度

    @Override
    public void onSeekStart() {
        dragging = true;
    }

    @Override
    public void onSeekMove(long position) {
        tvPos.setText(Ui.clock(position));
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
    }

    // ------------------------------------------------------------ 歌词

    private void loadLyrics(final int i, final String name) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (dirEntries == null || dirSizes == null) {
                    List<String> flat = new ArrayList<>();
                    java.util.Map<String, Long> sizes = new java.util.HashMap<>();
                    try {
                        for (Api.Entry e : api.list(dir)) {
                            flat.add(e.name);
                            sizes.put(e.name, e.size);
                        }
                    } catch (Exception ignored) {
                    }
                    dirEntries = flat;
                    dirSizes = sizes;
                }
                Long sz = dirSizes.get(name);
                // 体积参与缓存键，同名文件换了内容缓存自动失效
                currentSize = sz == null ? 0 : sz;
                int dur = 0;
                try {
                    if (mp != null && prepared) {
                        dur = mp.getDuration();
                    }
                } catch (Exception ignored) {
                }
                final Lyrics.Result r;
                try {
                    r = Lyrics.fetch(MusicActivity.this, api, dir, name,
                            dirEntries, currentSize, dur);
                } catch (Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (index == i) {
                                tvSource.setText("歌词获取失败");
                            }
                        }
                    });
                    return;
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (index != i) {
                            return;
                        }
                        if (r == null) {
                            lrc = null;
                            tvNoLyric.setVisibility(View.VISIBLE);
                            tvSource.setText("暂无歌词");
                        } else {
                            lrc = r.lrc;
                            tvNoLyric.setVisibility(View.GONE);
                            tvSource.setText("歌词：" + r.source);
                        }
                        lyricIndex = -2;
                        paintLyrics();
                    }
                });
            }
        }).start();
    }

    private void paintLyrics() {
        int[] offsets = {-2, -1, 0, 1, 2};
        for (int i = 0; i < lyrics.length; i++) {
            TextView v = lyrics[i];
            if (lrc == null || lrc.lines.isEmpty()) {
                v.setText("");
                continue;
            }
            int li = lyricIndex + offsets[i];
            if (li < 0 || li >= lrc.lines.size()) {
                v.setText("");
            } else {
                v.setText(lrc.lines.get(li).text);
            }
        }
    }

    private void syncLyric(long position) {
        if (lrc == null || lrc.lines.isEmpty()) {
            return;
        }
        int idx = lrc.indexAt(position);
        if (idx != lyricIndex) {
            lyricIndex = idx;
            paintLyrics();
        }
    }

    // ------------------------------------------------------------ 界面

    private void updateHeader() {
        String name = names.get(index);
        String track = Lyrics.baseName(name);
        String[] parsed = Lyrics.parseName(name);
        String title = parsed[1].isEmpty() ? track : parsed[1];
        tvTitle.setText(title);
        tvInitial.setText(title.isEmpty() ? "♪" : title.substring(0, 1).toUpperCase(Locale.getDefault()));
        String artist = parsed[0];
        String sub = artist.isEmpty() ? "" : artist + " · ";
        tvSub.setText(String.format(Locale.getDefault(), "%s第 %d/%d 首", sub, index + 1, names.size()));
        int c = Ui.coverTint(name);
        int c2 = darker(c);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{c, c2});
        bg.setCornerRadius(Ui.dp(this, 20));
        cover.setBackground(bg);
        tvSource.setText("");
    }

    private static int darker(int color) {
        int r = (int) (((color >> 16) & 0xFF) * 0.55f);
        int g = (int) (((color >> 8) & 0xFF) * 0.55f);
        int b = (int) ((color & 0xFF) * 0.55f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
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
            syncLyric(pos);
        } catch (Exception ignored) {
        }
    }

    private void showPlaylist() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("播放列表（" + names.size() + " 首）")
                .setSingleChoiceItems(names.toArray(new String[0]), index,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                d.dismiss();
                                start(which);
                            }
                        })
                .show();
    }

    private void showOptions() {
        String[] items = {"播放列表", "循环模式：" + LOOP_LABEL[loopMode], "重新获取歌词", "保存到本机"};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("更多")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            showPlaylist();
                        } else if (which == 1) {
                            cycleLoop();
                        } else if (which == 2) {
                            lrc = null;
                            lyricIndex = -2;
                            paintLyrics();
                            tvSource.setText("正在获取…");
                            loadLyrics(index, names.get(index));
                        } else {
                            Saver.save(MusicActivity.this, api, dir, names.get(index));
                        }
                    }
                })
                .show();
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    protected void onPause() {
        super.onPause();
        // 音乐不随界面暂停：锁屏或切到别的应用时继续放，退出本页才释放
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        release();
        super.onDestroy();
    }
}
