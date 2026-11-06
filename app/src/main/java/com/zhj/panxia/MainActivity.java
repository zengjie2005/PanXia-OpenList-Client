package com.zhj.panxia;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 主界面：目录浏览 + 上传下载 + 批量操作 + 设置入口。 */
public class MainActivity extends Activity {

    private static final int REQ_PICK_FILES = 101;
    private static final int REQ_COPY_TARGET = 102;
    private static final int REQ_MOVE_TARGET = 103;
    private static final int REQ_SETTINGS = 104;
    private static final int REQ_PERM = 105;
    private static final int REQ_SERVERS = 106;

    private Store store;
    private Api api;
    private FileAdapter adapter;

    private ListView list;
    private PullRefreshLayout pull;
    private View empty;
    private View btnBack;
    private ProgressBar loading;
    private TextView tvTitle;
    private TextView tvSub;
    private TextView tvSelCount;
    private LinearLayout barSel;
    private LinearLayout rowSearch;
    private EditText etSearch;
    private LinearLayout crumb;
    private HorizontalScrollView scrollCrumb;

    private String path = "/";
    private final List<Api.Entry> all = new ArrayList<>();
    private String query = "";
    private boolean appliedDark;

    private AlertDialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressLabel;
    private String progressPrefix = "";
    private volatile boolean cancelFlag;
    private volatile int lastPercent = -1;

    private List<String> pendingNames = new ArrayList<>();
    private boolean pendingMove;

    private long lastBackTime;
    private volatile long loadSeq;

    // ------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        store = new Store(this);
        Theme.apply(this, store);
        super.onCreate(savedInstanceState);

        if (store.token().isEmpty() || store.base().isEmpty()) {
            goLogin();
            return;
        }

        api = new Api(store);
        Api.applyInsecure(store.insecure());
        appliedDark = Theme.isDark(this, store);

        setContentView(R.layout.activity_main);
        Theme.install(this, R.id.topBar);
        bind();
        path = store.lastPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        load(path);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Theme.isDark(this, store) != appliedDark) {
            recreate();
        }
    }

    @Override
    public void onBackPressed() {
        if (adapter.selectionMode()) {
            adapter.setSelectionMode(false);
            updateSelBar();
            return;
        }
        if (!"/".equals(path)) {
            load(Ui.parent(path));
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackTime < 2000) {
            super.onBackPressed();
        } else {
            lastBackTime = now;
            Ui.toast(this, "再按一次退出");
        }
    }

    // ------------------------------------------------------------ 绑定

    private void bind() {
        list = findViewById(R.id.list);
        pull = findViewById(R.id.pull);
        empty = findViewById(R.id.empty);
        loading = findViewById(R.id.loading);
        btnBack = findViewById(R.id.btnBack);
        tvTitle = findViewById(R.id.tvTitle);
        tvSub = findViewById(R.id.tvSub);
        tvSelCount = findViewById(R.id.tvSelCount);
        barSel = findViewById(R.id.barSel);
        rowSearch = findViewById(R.id.rowSearch);
        etSearch = findViewById(R.id.etSearch);
        crumb = findViewById(R.id.crumb);
        scrollCrumb = findViewById(R.id.scrollCrumb);

        adapter = new FileAdapter(this);
        adapter.setMoreListener(new FileAdapter.OnMore() {
            @Override
            public void onMore(int position) {
                if (position >= 0 && position < adapter.getCount()) {
                    showActions(adapter.at(position));
                }
            }
        });
        list.setAdapter(adapter);

        // 点击 / 长按由行 View 自己处理（FileAdapter.OnRow）。
        // 不要只依赖 ListView.onItemClick：行内 ⋮ 按钮曾因可获焦导致
        // AbsListView 判定整行不可点击，整个文件列表点不动。
        adapter.setRowListener(new FileAdapter.OnRow() {
            @Override
            public void onClick(int position, View v) {
                if (position < 0 || position >= adapter.getCount()) {
                    return;
                }
                final Api.Entry e = adapter.at(position);
                Feedback.tick(v);
                Feedback.pulse(v);
                if (adapter.selectionMode()) {
                    adapter.toggle(e);
                    updateSelBar();
                    return;
                }
                if (e.dir) {
                    load(Ui.join(path, e.name));
                } else {
                    open(e);
                }
            }

            @Override
            public boolean onLongClick(int position, View v) {
                if (position < 0 || position >= adapter.getCount()) {
                    return true;
                }
                Feedback.tick(v);
                if (!adapter.selectionMode()) {
                    adapter.setSelectionMode(true);
                }
                adapter.toggle(adapter.at(position));
                updateSelBar();
                return true;
            }
        });

        // 下拉刷新：只在列表已到顶、明确向下拖时接管，不会抢列表滚动
        pull.setOnRefreshListener(new PullRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                if (adapter.selectionMode()) {
                    adapter.setSelectionMode(false);
                    updateSelBar();
                }
                load(path, true);
            }
        });

        // 滑动列表时收起键盘，给列表腾出屏幕
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState != SCROLL_STATE_IDLE
                        && rowSearch.getVisibility() == View.VISIBLE) {
                    Ui.hideKeyboard(MainActivity.this);
                }
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
            }
        });

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBackPressed();
            }
        });
        findViewById(R.id.btnRefresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                load(path);
            }
        });
        findViewById(R.id.btnLocal).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Feedback.press(v);
                startActivity(new Intent(MainActivity.this, LocalFilesActivity.class));
            }
        });
        findViewById(R.id.btnMore).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMoreMenu(v);
            }
        });
        findViewById(R.id.btnSearch).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (rowSearch.getVisibility() == View.VISIBLE) {
                    rowSearch.setVisibility(View.GONE);
                    etSearch.setText("");
                } else {
                    rowSearch.setVisibility(View.VISIBLE);
                    etSearch.requestFocus();
                }
            }
        });
        findViewById(R.id.btnSearchClose).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                etSearch.setText("");
                rowSearch.setVisibility(View.GONE);
            }
        });
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString();
                render();
            }
        });

        findViewById(R.id.btnUpload).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFiles();
            }
        });
        findViewById(R.id.btnMkdir).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doMkdir();
            }
        });
        findViewById(R.id.btnSort).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSortDialog();
            }
        });
        findViewById(R.id.btnSettings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(MainActivity.this, SettingsActivity.class), REQ_SETTINGS);
            }
        });

        findViewById(R.id.btnSelAll).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                adapter.selectAll();
                updateSelBar();
            }
        });
        findViewById(R.id.btnSelCancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                adapter.setSelectionMode(false);
                updateSelBar();
            }
        });
        findViewById(R.id.btnSelCopy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startTransfer(false);
            }
        });
        findViewById(R.id.btnSelMove).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startTransfer(true);
            }
        });
        findViewById(R.id.btnSelDel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                List<String> names = adapter.selectedNames();
                if (names.isEmpty()) {
                    Ui.toast(MainActivity.this, "还没选择文件");
                    return;
                }
                doDelete(names, names.size() + " 项");
            }
        });
        attachFeedback();
    }

    /** 统一给可点控件挂上按压回弹 + 触感（不改变原有点击逻辑） */
    private void attachFeedback() {
        int[] ids = {R.id.btnBack, R.id.btnSearch, R.id.btnRefresh, R.id.btnMore,
                R.id.btnSearchClose, R.id.btnUpload, R.id.btnMkdir, R.id.btnSort,
                R.id.btnSettings, R.id.btnSelAll, R.id.btnSelCopy, R.id.btnSelMove,
                R.id.btnSelDel, R.id.btnSelCancel};
        for (int id : ids) {
            Feedback.press(findViewById(id));
        }
    }

    /** 根目录没有上一级，按文件管理器的习惯把返回箭头收起来 */
    private void updateBackButton() {
        if (btnBack == null) {
            return;
        }
        boolean show = !"/".equals(path);
        if (!show) {
            btnBack.setVisibility(View.GONE);
            return;
        }
        if (btnBack.getVisibility() == View.VISIBLE) {
            return;
        }
        btnBack.setVisibility(View.VISIBLE);
        btnBack.setAlpha(0f);
        btnBack.setTranslationX(-Ui.dp(this, 8));
        btnBack.animate().alpha(1f).translationX(0f).setDuration(160).start();
    }

    // ------------------------------------------------------------ 加载与渲染

    private void load(final String target) {
        load(target, false);
    }

    /** @param fromPull 下拉刷新触发时不显示居中加载圈，改用顶部指示器 */
    private void load(final String target, final boolean fromPull) {
        final long seq = ++loadSeq;
        // 刷新 / 换目录 / 换服务器一律退出多选，操作栏不会残留上一目录的勾选
        if (adapter.selectionMode()) {
            adapter.setSelectionMode(false);
        }
        updateSelBar();
        if (!fromPull) {
            loading.setVisibility(View.VISIBLE);
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Api.Entry> items = null;
                Throwable error = null;
                try {
                    items = api.list(target);
                } catch (Throwable t) {
                    error = t;
                }
                final List<Api.Entry> result = items;
                final Throwable err = error;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (seq != loadSeq) {
                            return; // 快速切换目录时丢弃过期结果
                        }
                        loading.setVisibility(View.GONE);
                        finishRefresh();
                        if (err != null) {
                            if (err instanceof Api.ApiError) {
                                onError((Api.ApiError) err);
                            } else {
                                Ui.toastLong(MainActivity.this, "加载失败："
                                        + (err.getMessage() == null ? "未知错误" : err.getMessage()));
                            }
                            showLoadErrorIfEmpty();
                            return;
                        }
                        path = target;
                        store.lastPath(target);
                        all.clear();
                        all.addAll(result);
                        tvTitle.setText("/".equals(target) ? "根目录" : Ui.name(target));
                        render();
                        updateCrumb();
                        updateBackButton();
                        if (!fromPull) {
                            list.setSelection(0);
                        }
                    }
                });
            }
        }).start();
    }

    private void finishRefresh() {
        if (pull != null) {
            pull.setRefreshing(false);
        }
    }

    private View rowViewAt(int position) {
        if (list == null) {
            return null;
        }
        return list.getChildAt(position - list.getFirstVisiblePosition());
    }

    private void render() {
        List<Api.Entry> view = new ArrayList<>();
        boolean showHidden = store.showHidden();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        for (Api.Entry e : all) {
            if (!showHidden && e.name.startsWith(".")) {
                continue;
            }
            if (!q.isEmpty() && !e.name.toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            view.add(e);
        }

        final String key = store.sort();
        final boolean desc = store.desc();
        Collections.sort(view, new Comparator<Api.Entry>() {
            @Override
            public int compare(Api.Entry a, Api.Entry b) {
                if (a.dir != b.dir) {
                    return a.dir ? -1 : 1;
                }
                int r;
                if ("size".equals(key)) {
                    r = Long.compare(a.size, b.size);
                } else if ("time".equals(key)) {
                    r = a.modified.compareTo(b.modified);
                } else {
                    r = a.name.compareToIgnoreCase(b.name);
                }
                return desc ? -r : r;
            }
        });

        adapter.submit(view);
        empty.setVisibility(view.isEmpty() ? View.VISIBLE : View.GONE);
        if (view.isEmpty()) {
            TextView title = empty.findViewById(R.id.tvEmptyTitle);
            TextView hint = empty.findViewById(R.id.tvEmptyHint);
            View action = empty.findViewById(R.id.btnEmptyAction);
            if (title != null) {
                title.setText(query != null && !query.trim().isEmpty()
                        ? "没有匹配的文件" : getString(R.string.empty_folder));
            }
            if (hint != null) {
                hint.setText(query != null && !query.trim().isEmpty()
                        ? "换个关键词试试" : getString(R.string.empty_folder_hint));
            }
            if (action != null) {
                action.setVisibility(View.GONE);
            }
        }
        tvSub.setText(store.user() + " · " + Api.hostOf(store.base()) + " · " + view.size() + " 项");
    }

    private void updateCrumb() {
        crumb.removeAllViews();
        addCrumb("/", "根目录");
        if (!"/".equals(path)) {
            String[] parts = path.split("/");
            StringBuilder acc = new StringBuilder();
            for (String p : parts) {
                if (p == null || p.isEmpty()) {
                    continue;
                }
                acc.append('/').append(p);
                addCrumb(acc.toString(), p);
            }
        }
        scrollCrumb.post(new Runnable() {
            @Override
            public void run() {
                scrollCrumb.fullScroll(View.FOCUS_RIGHT);
            }
        });
    }

    private void addCrumb(final String target, String label) {
        if (crumb.getChildCount() > 0) {
            android.widget.ImageView sep = new android.widget.ImageView(this);
            sep.setImageResource(R.drawable.ic_chevron);
            sep.setColorFilter(Ui.color(this, R.attr.olDim));
            sep.setAlpha(0.5f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    Ui.dp(this, 11), Ui.dp(this, 11));
            lp.leftMargin = Ui.dp(this, 3);
            lp.rightMargin = Ui.dp(this, 3);
            crumb.addView(sep, lp);
        }
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(12);
        t.setSingleLine(true);
        t.setLetterSpacing(0.02f);
        boolean here = target.equals(path);
        t.setTextColor(here ? Ui.color(this, R.attr.olAccent) : Ui.color(this, R.attr.olText));
        t.setBackgroundResource(here ? R.drawable.bg_chip_on : R.drawable.bg_chip);
        t.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (target.equals(path)) {
                    return;
                }
                adapter.setSelectionMode(false);
                updateSelBar();
                load(target);
            }
        });
        Feedback.press(t);
        crumb.addView(t);
    }

    private void updateSelBar() {
        boolean on = adapter.selectionMode();
        barSel.setVisibility(on ? View.VISIBLE : View.GONE);
        int n = adapter.selectedCount();
        tvSelCount.setText(n == 0 ? "未选择" : "已选 " + n + " 项");
        boolean has = n > 0;
        int[] actions = {R.id.btnSelCopy, R.id.btnSelMove, R.id.btnSelDel};
        for (int id : actions) {
            View v = findViewById(id);
            if (v != null) {
                v.setEnabled(has);
                v.setAlpha(has ? 1f : 0.38f);
            }
        }
    }

    /** 加载失败且列表为空时，给一个看得见、能点的错误态，而不是只弹 toast */
    private void showLoadErrorIfEmpty() {
        if (adapter.getCount() > 0) {
            return;
        }
        empty.setVisibility(View.VISIBLE);
        TextView title = empty.findViewById(R.id.tvEmptyTitle);
        TextView hint = empty.findViewById(R.id.tvEmptyHint);
        View action = empty.findViewById(R.id.btnEmptyAction);
        if (title != null) {
            title.setText("加载失败");
        }
        if (hint != null) {
            hint.setText("检查网络或服务器地址后重试");
        }
        if (action != null) {
            action.setVisibility(View.VISIBLE);
            action.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    load(path);
                }
            });
        }
    }

    // ------------------------------------------------------------ 菜单

    private void showMoreMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add("刷新");
        pm.getMenu().add("本地文件");
        pm.getMenu().add("上传文件");
        pm.getMenu().add("新建文件夹");
        pm.getMenu().add("回到根目录");
        pm.getMenu().add(store.servers().size() > 1 ? "切换服务器" : "服务器");
        pm.getMenu().add("设置");
        pm.getMenu().add("退出登录");
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(android.view.MenuItem item) {
                String title = item.getTitle().toString();
                if ("刷新".equals(title)) {
                    load(path);
                } else if ("本地文件".equals(title)) {
                    startActivity(new Intent(MainActivity.this, LocalFilesActivity.class));
                } else if ("上传文件".equals(title)) {
                    pickFiles();
                } else if ("新建文件夹".equals(title)) {
                    doMkdir();
                } else if ("回到根目录".equals(title)) {
                    load("/");
                } else if ("切换服务器".equals(title) || "服务器".equals(title)) {
                    startActivityForResult(new Intent(MainActivity.this, ServersActivity.class),
                            REQ_SERVERS);
                } else if ("设置".equals(title)) {
                    startActivityForResult(new Intent(MainActivity.this, SettingsActivity.class), REQ_SETTINGS);
                } else if ("退出登录".equals(title)) {
                    Ui.confirm(MainActivity.this, "退出登录", "清除本机登录信息？", "退出", new Runnable() {
                        @Override
                        public void run() {
                            store.logout();
                            goLogin();
                        }
                    });
                }
                return true;
            }
        });
        pm.show();
    }

    private void showSortDialog() {
        final String[] items = {"按名称" + ("name".equals(store.sort()) ? " ✓" : ""),
                "按大小" + ("size".equals(store.sort()) ? " ✓" : ""),
                "按时间" + ("time".equals(store.sort()) ? " ✓" : ""),
                store.desc() ? "顺序：倒序 ✓" : "顺序：正序"};
        new AlertDialog.Builder(this)
                .setTitle("排序方式")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int i) {
                        if (i == 0) {
                            store.sort("name");
                        } else if (i == 1) {
                            store.sort("size");
                        } else if (i == 2) {
                            store.sort("time");
                        } else {
                            store.desc(!store.desc());
                        }
                        render();
                    }
                })
                .show();
    }

    private void showActions(final Api.Entry e) {
        final List<String> ops = new ArrayList<>();
        if (!e.dir) {
            Mime.Kind k = Mime.kind(e.name);
            if (k == Mime.Kind.IMAGE) {
                ops.add("预览图片");
            } else if (k == Mime.Kind.VIDEO) {
                ops.add("播放视频");
            } else if (k == Mime.Kind.AUDIO) {
                ops.add("播放音频");
            } else if (k == Mime.Kind.TEXT) {
                ops.add("查看内容");
            } else if (k == Mime.Kind.PDF || k == Mime.Kind.OFFICE) {
                ops.add("打开");
            }
            ops.add("下载");
            ops.add("复制直链");
        }
        ops.add("详情");
        ops.add("重命名");
        ops.add("复制到…");
        ops.add("移动到…");
        ops.add("删除");

        new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setItems(ops.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int i) {
                        if (!all.contains(e)) {
                            Ui.toast(MainActivity.this, "列表已变化，请重新选择文件");
                            return;
                        }
                        String op = ops.get(i);
                        if ("预览图片".equals(op) || "播放视频".equals(op) || "播放音频".equals(op)
                                || "查看内容".equals(op) || "打开".equals(op)) {
                            open(e);
                        } else if ("下载".equals(op)) {
                            startDownload(e);
                        } else if ("复制直链".equals(op)) {
                            copyLink(e);
                        } else if ("详情".equals(op)) {
                            showDetail(e);
                        } else if ("重命名".equals(op)) {
                            doRename(e);
                        } else if ("复制到…".equals(op)) {
                            pendingNames = new ArrayList<>();
                            pendingNames.add(e.name);
                            pickTarget(false);
                        } else if ("移动到…".equals(op)) {
                            pendingNames = new ArrayList<>();
                            pendingNames.add(e.name);
                            pickTarget(true);
                        } else if ("删除".equals(op)) {
                            doDelete(Collections.singletonList(e.name), e.name);
                        }
                    }
                })
                .show();
    }

    // ------------------------------------------------------------ 打开 / 预览

    /** 点击文件的默认行为：能预览直接预览，不能预览的给出打开方式 */
    private void open(Api.Entry e) {
        switch (Mime.kind(e.name)) {
            case IMAGE:
                openImage(e);
                break;
            case VIDEO:
            case AUDIO:
                openMedia(e);
                break;
            case TEXT:
                openText(e);
                break;
            case PDF:
            case OFFICE:
                openDocument(e);
                break;
            default:
                showActions(e);
                break;
        }
    }

    private void openImage(Api.Entry e) {
        List<String> imgs = new ArrayList<>();
        int at = 0;
        for (Api.Entry x : adapter.items()) {
            if (!x.dir && Mime.kind(x.name) == Mime.Kind.IMAGE) {
                if (x.name.equals(e.name)) {
                    at = imgs.size();
                }
                imgs.add(x.name);
            }
        }
        if (imgs.isEmpty()) {
            imgs.add(e.name);
            at = 0;
        }
        Intent i = new Intent(this, ImageActivity.class);
        i.putExtra(ImageActivity.EXTRA_DIR, path);
        i.putExtra(ImageActivity.EXTRA_NAMES, imgs.toArray(new String[0]));
        i.putExtra(ImageActivity.EXTRA_INDEX, at);
        startActivity(i);
    }

    private void openMedia(Api.Entry e) {
        boolean audio = Mime.kind(e.name) == Mime.Kind.AUDIO;
        // 同一目录里的同类文件一起带过去：视频能连着播下一集，音乐整个文件夹就是歌单
        List<String> siblings = new ArrayList<>();
        int at = 0;
        for (Api.Entry x : adapter.items()) {
            if (x.dir) {
                continue;
            }
            Mime.Kind k = Mime.kind(x.name);
            boolean same = audio ? k == Mime.Kind.AUDIO : k == Mime.Kind.VIDEO;
            if (!same) {
                continue;
            }
            if (x.name.equals(e.name)) {
                at = siblings.size();
            }
            siblings.add(x.name);
        }
        if (siblings.isEmpty()) {
            siblings.add(e.name);
            at = 0;
        }
        Intent i = new Intent(this, audio ? MusicActivity.class : VideoActivity.class);
        i.putExtra(VideoActivity.EXTRA_DIR, path);
        i.putExtra(VideoActivity.EXTRA_NAMES, siblings.toArray(new String[0]));
        i.putExtra(VideoActivity.EXTRA_INDEX, at);
        startActivity(i);
    }

    private void openText(Api.Entry e) {
        Intent i = new Intent(this, TextViewerActivity.class);
        i.putExtra(TextViewerActivity.EXTRA_DIR, path);
        i.putExtra(TextViewerActivity.EXTRA_NAME, e.name);
        startActivity(i);
    }

    /** PDF / Office：手机端没法内嵌排版渲染，走「下载后交给系统查看器」 */
    private void openDocument(final Api.Entry e) {
        String[] items = {"用其他应用打开", "用浏览器打开", "下载到本机", "详情"};
        new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) {
                            downloadThenOpen(e);
                        } else if (which == 1) {
                            openInBrowser(e);
                        } else if (which == 2) {
                            startDownload(e);
                        } else {
                            showDetail(e);
                        }
                    }
                })
                .show();
    }

    private void downloadThenOpen(final Api.Entry e) {
        showProgressDialog("准备打开 " + e.name);
        cancelFlag = false;
        lastPercent = -1;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String raw = api.rawUrl(Ui.join(path, e.name));
                    if (raw.isEmpty()) {
                        throw new Api.ApiError(-1, "服务器未返回直链");
                    }
                    File dest = new File(LocalProvider.dir(MainActivity.this), e.name);
                    Net.toFile(raw, dest, new Net.Progress() {
                        @Override
                        public void onProgress(long done, long total) {
                            if (cancelFlag) {
                                throw new RuntimeException("已取消");
                            }
                            final int pct = total > 0 ? (int) (done * 100 / total) : 0;
                            if (pct == lastPercent) {
                                return;
                            }
                            lastPercent = pct;
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    updateProgress("打开中 · " + e.name + " · " + pct + "%", pct);
                                }
                            });
                        }
                    });
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            dismissProgress();
                            launchViewer(e.name);
                        }
                    });
                } catch (final Exception err) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            dismissProgress();
                            if ("已取消".equals(err.getMessage())) {
                                Ui.toast(MainActivity.this, "已取消");
                            } else {
                                Ui.toastLong(MainActivity.this, "打开失败：" + err.getMessage());
                            }
                        }
                    });
                }
            }
        }).start();
    }

    private void launchViewer(String name) {
        Uri u = LocalProvider.uriFor(name);
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(u, Mime.mime(name));
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        view.setClipData(android.content.ClipData.newRawUri(name, u));
        if (getPackageManager().resolveActivity(view, 0) == null) {
            Ui.toastLong(this, "手机里没有能打开该类型的应用，可改用「用浏览器打开」或先下载");
            return;
        }
        try {
            Intent chooser = Intent.createChooser(view, "打开 " + name);
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(chooser);
        } catch (Exception ex) {
            Ui.toastLong(this, "没有可用的应用打开此文件");
        }
    }

    private void openInBrowser(final Api.Entry e) {
        final String full = Ui.join(path, e.name);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String raw = api.rawUrl(full);
                    if (raw.isEmpty()) {
                        throw new Api.ApiError(-1, "服务器未返回直链");
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(raw)));
                            } catch (Exception ex) {
                                Ui.toast(MainActivity.this, "没有可用的浏览器");
                            }
                        }
                    });
                } catch (final Api.ApiError err) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            onError(err);
                        }
                    });
                }
            }
        }).start();
    }

    // ------------------------------------------------------------ 具体操作

    private void doMkdir() {
        Ui.input(this, "新建文件夹", "文件夹名称", "", new Ui.OnText() {
            @Override
            public void onText(String value) {
                final String target = Ui.join(path, value);
                runAction(new Action() {
                    @Override
                    public void run() throws Exception {
                        api.mkdir(target);
                    }
                }, new Runnable() {
                    @Override
                    public void run() {
                        Ui.toast(MainActivity.this, "已创建 " + value);
                        load(path);
                    }
                });
            }
        });
    }

    private void doRename(final Api.Entry e) {
        Ui.input(this, "重命名", "新名称", e.name, new Ui.OnText() {
            @Override
            public void onText(final String value) {
                if (value.equals(e.name)) {
                    return;
                }
                runAction(new Action() {
                    @Override
                    public void run() throws Exception {
                        api.rename(Ui.join(path, e.name), value);
                    }
                }, new Runnable() {
                    @Override
                    public void run() {
                        Ui.toast(MainActivity.this, "已重命名");
                        load(path);
                    }
                });
            }
        });
    }

    private void doDelete(final List<String> names, String label) {
        Ui.confirm(this, "删除", "确定删除 " + label + "？服务器上的文件会被直接移除，无法恢复。", "删除",
                new Runnable() {
                    @Override
                    public void run() {
                        runAction(new Action() {
                            @Override
                            public void run() throws Exception {
                                api.remove(path, names);
                            }
                        }, new Runnable() {
                            @Override
                            public void run() {
                                Ui.toast(MainActivity.this, "已删除 " + names.size() + " 项");
                                adapter.setSelectionMode(false);
                                updateSelBar();
                                load(path);
                            }
                        });
                    }
                });
    }

    private void pickTarget(boolean move) {
        pendingMove = move;
        if (adapter.selectionMode() && pendingNames.isEmpty()) {
            pendingNames = adapter.selectedNames();
        }
        if (pendingNames.isEmpty()) {
            Ui.toast(this, "先选择文件");
            return;
        }
        Intent i = new Intent(this, DirPickerActivity.class);
        i.putExtra(DirPickerActivity.EXTRA_START, path);
        startActivityForResult(i, move ? REQ_MOVE_TARGET : REQ_COPY_TARGET);
    }

    private void startTransfer(boolean move) {
        pendingNames = adapter.selectedNames();
        if (pendingNames.isEmpty()) {
            Ui.toast(this, "还没选择文件");
            return;
        }
        pendingMove = move;
        pickTarget(move);
    }

    private void doTransfer(final String target) {
        final String src = path;
        final List<String> names = new ArrayList<>(pendingNames);
        final boolean move = pendingMove;
        if (target.equals(src)) {
            Ui.toast(this, "目标目录和当前目录相同");
            return;
        }
        runAction(new Action() {
            @Override
            public void run() throws Exception {
                if (move) {
                    api.move(src, target, names);
                } else {
                    api.copy(src, target, names);
                }
            }
        }, new Runnable() {
            @Override
            public void run() {
                Ui.toast(MainActivity.this, (move ? "已移动 " : "已复制 ") + names.size() + " 项");
                adapter.setSelectionMode(false);
                updateSelBar();
                load(path);
            }
        });
    }

    private void copyLink(final Api.Entry e) {
        runAction(new Action() {
            @Override
            public void run() throws Exception {
                final String raw = api.rawUrl(Ui.join(path, e.name));
                if (raw.isEmpty()) {
                    throw new Api.ApiError(-1, "服务器未返回直链");
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Ui.copy(MainActivity.this, "直链", raw);
                    }
                });
            }
        }, null);
    }

    private void showDetail(final Api.Entry e) {
        final String full = Ui.join(path, e.name);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject d = api.get(full);
                    final StringBuilder sb = new StringBuilder();
                    sb.append("名称：").append(d.optString("name", e.name)).append('\n');
                    sb.append("路径：").append(full).append('\n');
                    sb.append("类型：").append(e.dir ? "文件夹" : "文件").append('\n');
                    if (!e.dir) {
                        sb.append("大小：").append(Ui.size(e.size)).append("（").append(e.size).append(" 字节）\n");
                    }
                    String t = Ui.time(d.optString("modified", e.modified));
                    if (!t.isEmpty()) {
                        sb.append("修改时间：").append(t).append('\n');
                    }
                    final String raw = d.optString("raw_url", "");
                    if (!raw.isEmpty()) {
                        sb.append("\n直链：\n").append(raw);
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("详情")
                                    .setMessage(sb.toString())
                                    .setPositiveButton("关闭", null)
                                    .setNeutralButton("复制直链", new android.content.DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(android.content.DialogInterface dd, int w) {
                                            if (!raw.isEmpty()) {
                                                Ui.copy(MainActivity.this, "直链", raw);
                                            }
                                        }
                                    })
                                    .show();
                        }
                    });
                } catch (final Api.ApiError err) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            onError(err);
                        }
                    });
                }
            }
        }).start();
    }

    // ------------------------------------------------------------ 上传

    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_PICK_FILES);
        } catch (Exception e) {
            Ui.toastLong(this, "没有找到文件选择器");
        }
    }

    /** 上传前先检查同名文件，避免静默覆盖 */
    private void uploadAll(final List<Uri> uris) {
        if (uris.isEmpty()) {
            return;
        }
        final List<String> conflicts = new ArrayList<>();
        for (Uri uri : uris) {
            String name = queryName(uri);
            for (Api.Entry e : all) {
                if (!e.dir && e.name.equals(name)) {
                    conflicts.add(name);
                    break;
                }
            }
        }
        if (conflicts.isEmpty()) {
            startUpload(uris);
            return;
        }
        StringBuilder sb = new StringBuilder();
        int show = Math.min(3, conflicts.size());
        for (int i = 0; i < show; i++) {
            sb.append("\n· ").append(conflicts.get(i));
        }
        if (conflicts.size() > show) {
            sb.append("\n… 等 ").append(conflicts.size()).append(" 个");
        }
        Ui.confirm(this, "存在同名文件",
                "当前目录已有同名文件：" + sb + "\n\n继续上传会覆盖服务器上的旧文件。",
                "覆盖上传", new Runnable() {
                    @Override
                    public void run() {
                        startUpload(uris);
                    }
                });
    }

    private void startUpload(final List<Uri> uris) {
        showProgressDialog("准备上传 " + uris.size() + " 个文件");
        cancelFlag = false;

        new Thread(new Runnable() {
            @Override
            public void run() {
                int done = 0;
                String fail = null;
                for (int i = 0; i < uris.size(); i++) {
                    if (cancelFlag) {
                        break;
                    }
                    Uri uri = uris.get(i);
                    String name = queryName(uri);
                    long size = querySize(uri);
                    final String prefix = "上传中 " + (i + 1) + "/" + uris.size() + " · " + name;
                    lastPercent = -1;
                    final long total = size;

                    File temp = null;
                    InputStream in = null;
                    try {
                        if (size <= 0) {
                            temp = cacheCopy(uri);
                            if (temp == null) {
                                throw new Api.ApiError(-1, "无法读取该文件");
                            }
                            size = temp.length();
                            in = new FileInputStream(temp);
                        } else {
                            in = getContentResolver().openInputStream(uri);
                        }
                        if (in == null) {
                            throw new Api.ApiError(-1, "无法打开文件");
                        }
                        final long length = size;
                        final String display = prefix;
                        api.upload(Ui.join(path, name), in, size, new Api.Progress() {
                            @Override
                            public void onProgress(long written, long all) {
                                int pct = all <= 0 ? 0 : (int) (written * 100 / all);
                                if (pct == lastPercent) {
                                    return;
                                }
                                lastPercent = pct;
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        updateProgress(display + " · " + pct + "%", pct);
                                    }
                                });
                            }
                        }, new Api.Cancel() {
                            @Override
                            public boolean cancelled() {
                                return cancelFlag;
                            }
                        });
                        done++;
                    } catch (Api.ApiError err) {
                        if (err.code == -2) {
                            break;
                        }
                        fail = name + "：" + err.getMessage();
                    } catch (Exception err) {
                        fail = name + "：" + err.getMessage();
                    } finally {
                        try {
                            if (in != null) {
                                in.close();
                            }
                        } catch (Exception ignored) {
                        }
                        if (temp != null) {
                            //noinspection ResultOfMethodCallIgnored
                            temp.delete();
                        }
                    }
                }

                final int okCount = done;
                final String failMsg = fail;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        dismissProgress();
                        StringBuilder sb = new StringBuilder();
                        sb.append("上传完成 ").append(okCount).append(" 个");
                        if (failMsg != null) {
                            sb.append("\n失败：").append(failMsg);
                        }
                        Ui.toastLong(MainActivity.this, sb.toString());
                        load(path);
                    }
                });
            }
        }).start();
    }

    // ------------------------------------------------------------ 下载

    private void startDownload(final Api.Entry e) {
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
            Ui.toast(this, "请允许写入存储后重新点下载");
            return;
        }
        showProgressDialog("准备下载 " + e.name);
        cancelFlag = false;
        lastPercent = -1;

        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection c = null;
                InputStream in = null;
                OutputStream out = null;
                Uri outUri = null;
                File outFile = null;
                boolean ok = false;
                try {
                    String raw = api.rawUrl(Ui.join(path, e.name));
                    if (raw.isEmpty()) {
                        throw new Api.ApiError(-1, "服务器未返回下载地址");
                    }
                    c = (HttpURLConnection) new URL(raw).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(180000);
                    c.setRequestProperty("User-Agent", "PanXia/1.2 (Android)");
                    int sc = c.getResponseCode();
                    if (sc >= 400) {
                        throw new Api.ApiError(sc, "下载失败（HTTP " + sc + "）");
                    }
                    final long total = c.getContentLength();
                    in = c.getInputStream();

                    String fileName = e.name;
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues cv = new ContentValues();
                        cv.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                        cv.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
                        cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PanXia");
                        outUri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                        if (outUri == null) {
                            throw new Api.ApiError(-1, "无法创建下载文件");
                        }
                        out = getContentResolver().openOutputStream(outUri);
                    } else {
                        File dir = new File(Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS), "PanXia");
                        if (!dir.exists() && !dir.mkdirs()) {
                            throw new Api.ApiError(-1, "无法创建下载目录");
                        }
                        outFile = new File(dir, fileName);
                        out = new FileOutputStream(outFile);
                    }
                    if (out == null) {
                        throw new Api.ApiError(-1, "无法写入下载文件");
                    }

                    byte[] buf = new byte[64 * 1024];
                    long written = 0;
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        if (cancelFlag) {
                            throw new Api.ApiError(-2, "已取消下载");
                        }
                        out.write(buf, 0, n);
                        written += n;
                        int pct = total > 0 ? (int) (written * 100 / total) : 0;
                        if (pct != lastPercent) {
                            lastPercent = pct;
                            final int p = pct;
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    updateProgress("下载中 · " + e.name + " · " + p + "%", p);
                                }
                            });
                        }
                    }
                    out.flush();
                    ok = true;

                    final String where = Build.VERSION.SDK_INT >= 29
                            ? "下载/PanXia/" + e.name
                            : (outFile == null ? e.name : outFile.getAbsolutePath());
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            dismissProgress();
                            Ui.toastLong(MainActivity.this, "已保存到 " + where);
                        }
                    });
                } catch (final Api.ApiError err) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            dismissProgress();
                            if (err.code == -2) {
                                Ui.toast(MainActivity.this, "已取消下载");
                            } else if (err.code == 401) {
                                onError(err);
                            } else {
                                Ui.toastLong(MainActivity.this, err.getMessage());
                            }
                        }
                    });
                } catch (final Exception err) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            dismissProgress();
                            Ui.toastLong(MainActivity.this, "下载失败：" + err.getMessage());
                        }
                    });
                } finally {
                    if (!ok) {
                        // 取消或失败时清掉半成品，避免「下载」目录里留下空壳
                        if (outFile != null) {
                            //noinspection ResultOfMethodCallIgnored
                            outFile.delete();
                        }
                        if (outUri != null) {
                            try {
                                getContentResolver().delete(outUri, null, null);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    try {
                        if (in != null) {
                            in.close();
                        }
                    } catch (Exception ignored) {
                    }
                    try {
                        if (out != null) {
                            out.close();
                        }
                    } catch (Exception ignored) {
                    }
                    if (c != null) {
                        c.disconnect();
                    }
                }
            }
        }).start();
    }

    // ------------------------------------------------------------ 进度框

    private void showProgressDialog(String text) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        box.setPadding(pad, Ui.dp(this, 6), pad, 0);

        progressLabel = new TextView(this);
        progressLabel.setText(text);
        progressPrefix = text;
        progressLabel.setTextSize(13);
        progressLabel.setTextColor(Ui.color(this, R.attr.olText));
        box.addView(progressLabel);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 28));
        lp.topMargin = Ui.dp(this, 10);
        box.addView(progressBar, lp);

        progressDialog = new AlertDialog.Builder(this)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("取消", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        cancelFlag = true;
                    }
                })
                .create();
        progressDialog.show();
    }

    private void updateProgress(String text, int percent) {
        if (progressDialog == null || !progressDialog.isShowing()) {
            return;
        }
        progressLabel.setText(text);
        progressBar.setProgress(Math.max(0, Math.min(100, percent)));
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

    // ------------------------------------------------------------ 结果回调

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_FILES) {
            if (resultCode != RESULT_OK || data == null) {
                return;
            }
            List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                for (int i = 0; i < count; i++) {
                    Uri u = data.getClipData().getItemAt(i).getUri();
                    if (u != null) {
                        uris.add(u);
                    }
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            uploadAll(uris);
        } else if (requestCode == REQ_COPY_TARGET || requestCode == REQ_MOVE_TARGET) {
            if (resultCode == RESULT_OK && data != null) {
                String target = data.getStringExtra(DirPickerActivity.EXTRA_RESULT);
                if (target != null && !target.isEmpty()) {
                    doTransfer(target);
                }
            }
        } else if (requestCode == REQ_SETTINGS) {
            if (Theme.isDark(this, store) != appliedDark) {
                recreate();
            } else if (resultCode == RESULT_OK) {
                // 设置里可能换了服务器 / 账号，连接一起重建
                api = new Api(store);
                Api.applyInsecure(store.insecure());
                path = store.lastPath();
                if (path == null || path.isEmpty()) {
                    path = "/";
                }
                load(path);
            }
        } else if (requestCode == REQ_SERVERS) {
            if (resultCode == RESULT_OK) {
                // 换了服务器：重建连接，回到那台服务器上次停留的目录
                api = new Api(store);
                Api.applyInsecure(store.insecure());
                path = store.lastPath();
                if (path == null || path.isEmpty()) {
                    path = "/";
                }
                load(path);
            }
        }
    }

    // ------------------------------------------------------------ 杂项

    private interface Action {
        void run() throws Exception;
    }

    private void runAction(final Action action, final Runnable after) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    action.run();
                    if (after != null) {
                        runOnUiThread(after);
                    }
                } catch (final Api.ApiError e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            onError(e);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Ui.toastLong(MainActivity.this,
                                    e.getMessage() == null ? "操作失败" : e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void onError(Api.ApiError e) {
        if (e.code == 401) {
            store.clearToken();
            Ui.toast(this, "登录已失效，请重新登录");
            goLogin();
            return;
        }
        Ui.toastLong(this, e.getMessage() == null ? "操作失败" : e.getMessage());
    }

    private void goLogin() {
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    private String queryName(Uri uri) {
        String name = null;
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    name = c.getString(idx);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        if (name == null || name.isEmpty()) {
            String seg = uri.getLastPathSegment();
            if (seg != null) {
                int i = seg.lastIndexOf('/');
                name = i >= 0 ? seg.substring(i + 1) : seg;
            }
        }
        return name == null || name.isEmpty() ? "upload.bin" : name;
    }

    private long querySize(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) {
                    return c.getLong(idx);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return -1;
    }

    private File cacheCopy(Uri uri) {
        InputStream in = null;
        FileOutputStream out = null;
        File f = null;
        try {
            f = new File(getCacheDir(), "upload_" + System.currentTimeMillis() + ".bin");
            in = getContentResolver().openInputStream(uri);
            if (in == null) {
                f.delete();
                return null;
            }
            out = new FileOutputStream(f);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
            return f;
        } catch (Exception e) {
            if (f != null) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            return null;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Exception ignored) {
            }
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Exception ignored) {
            }
        }
    }
}
