package com.zhj.panxia;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 设备本地文件管理页。
 *
 * 设计要点：
 * - 独立 SharedPreferences（panxia_local_prefs），不碰 openlist_prefs，已有账号 / 断点 / 歌词缓存全部不受影响。
 * - 没有「所有文件访问权限」时自动退到应用私有外部目录，核心流程依然可用，并给出授权引导。
 * - 所有文件操作都走 LocalFs（纯 Java、可 JVM 自检），失败逐项报告，不抛未检查异常。
 * - 删除进回收站可恢复；复制 / 移动采用「同名自动改名」，绝不静默覆盖。
 */
public class LocalFilesActivity extends Activity {

    private static final int REQ_PERM = 201;
    private static final String PREF = "panxia_local_prefs";
    // API 30 字段的字面值。minSdk 24 若直接引用 Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
    // 会被 lint 判为 InlinedApi；这里只在「授权页找不到」的兜底分支里使用，行为完全一致。
    private static final String ACTION_MANAGE_ALL_FILES =
            "android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION";

    private Store store;
    private Api api;
    private SharedPreferences prefs;
    private LocalAdapter adapter;

    private ListView list;
    private LinearLayout bannerPerm;
    private TextView btnPermAction;
    private TextView tvPermHint;
    private LinearLayout barSel;
    private LinearLayout rowSearch;
    private EditText etSearch;
    private TextView tvSelCount;
    private TextView tvSub;
    private LinearLayout crumb;
    private HorizontalScrollView scrollCrumb;
    private View stateView;
    private ImageView ivState;
    private TextView tvStateTitle;
    private TextView tvStateHint;
    private TextView btnStateAction;
    private ProgressBar loading;

    private TextView btnSelAll;
    private TextView btnSelCopy;
    private TextView btnSelMove;
    private TextView btnSelUpload;
    private TextView btnSelDel;
    private TextView btnSelCancel;

    private String rootPath;
    private String path;
    private String trashDir;
    private boolean fullAccess;
    private boolean showHidden;
    private int sortField;
    private boolean sortDesc;
    private boolean searching;
    private String query = "";
    private volatile long loadSeq;
    private volatile boolean cancelFlag;
    private boolean destroyed;

    private final List<LocalFs.Entry> all = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable searchTask = new Runnable() {
        @Override
        public void run() {
            load();
        }
    };

    private AlertDialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressLabel;

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        store = new Store(this);
        Theme.apply(this, store);
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(PREF, MODE_PRIVATE);
        showHidden = prefs.getBoolean("show_hidden", false);
        sortField = prefs.getInt("sort", LocalFs.SORT_NAME);
        sortDesc = prefs.getBoolean("desc", false);

        setContentView(R.layout.activity_local);
        Theme.install(this, R.id.topBar);

        fullAccess = hasAllFilesAccess();
        initPaths();
        bind();
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean now = hasAllFilesAccess();
        if (now != fullAccess) {
            fullAccess = now;
            initPaths();
            load();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        cancelFlag = true;
        ui.removeCallbacks(searchTask);
        dismissProgress();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (adapter != null && adapter.selectionMode()) {
            adapter.exitSelection();
            updateSelBar();
            return;
        }
        if (searching) {
            closeSearch();
            return;
        }
        if (path != null && rootPath != null && !path.equals(rootPath)) {
            File parent = new File(path).getParentFile();
            if (parent != null && parent.getAbsolutePath().startsWith(rootPath)) {
                path = parent.getAbsolutePath();
                savePath();
                updateCrumb();
                load();
                return;
            }
        }
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_PERM) {
            fullAccess = hasAllFilesAccess();
            initPaths();
            load();
        }
    }

    // ---------------------------------------------------------------- 绑定

    private void bind() {
        adapter = new LocalAdapter(this);
        list = findViewById(R.id.list);
        list.setAdapter(adapter);
        list.setEmptyView(null);

        bannerPerm = findViewById(R.id.bannerPerm);
        btnPermAction = findViewById(R.id.btnPermAction);
        tvPermHint = findViewById(R.id.tvPermHint);
        barSel = findViewById(R.id.barSel);
        rowSearch = findViewById(R.id.rowSearch);
        etSearch = findViewById(R.id.etSearch);
        tvSelCount = findViewById(R.id.tvSelCount);
        tvSub = findViewById(R.id.tvSub);
        crumb = findViewById(R.id.crumb);
        scrollCrumb = findViewById(R.id.scrollCrumb);
        stateView = findViewById(R.id.stateView);
        ivState = findViewById(R.id.ivState);
        tvStateTitle = findViewById(R.id.tvStateTitle);
        tvStateHint = findViewById(R.id.tvStateHint);
        btnStateAction = findViewById(R.id.btnStateAction);
        loading = findViewById(R.id.loading);

        btnSelAll = findViewById(R.id.btnSelAll);
        btnSelCopy = findViewById(R.id.btnSelCopy);
        btnSelMove = findViewById(R.id.btnSelMove);
        btnSelUpload = findViewById(R.id.btnSelUpload);
        btnSelDel = findViewById(R.id.btnSelDel);
        btnSelCancel = findViewById(R.id.btnSelCancel);

        // 行点击交给 ListView：框架会在惯性滑动期间抑制误触
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View v, int pos, long id) {
                final LocalFs.Entry e = adapter.at(pos);
                if (e == null) {
                    return;
                }
                if (adapter.selectionMode()) {
                    adapter.toggle(e.key());
                    updateSelBar();
                } else {
                    open(e);
                }
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View v, int pos, long id) {
                final LocalFs.Entry e = adapter.at(pos);
                if (e == null) {
                    return true;
                }
                if (!adapter.selectionMode()) {
                    adapter.enterSelection(e.key());
                } else {
                    adapter.toggle(e.key());
                }
                updateSelBar();
                Feedback.tick(v);
                return true;
            }
        });
        adapter.setMoreListener(new LocalAdapter.OnMore() {
            @Override
            public void onMore(int position) {
                final LocalFs.Entry e = adapter.at(position);
                if (e != null && new File(e.path).exists()) {
                    showActions(e);
                } else {
                    Ui.toast(LocalFilesActivity.this, "该文件已不存在，请刷新");
                }
            }
        });

        View.OnClickListener click = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Feedback.press(v);
                int id = v.getId();
                if (id == R.id.btnBack) {
                    onBackPressed();
                } else if (id == R.id.btnSearch) {
                    toggleSearch();
                } else if (id == R.id.btnSearchClose) {
                    closeSearch();
                } else if (id == R.id.btnRefresh) {
                    adapter.exitSelection();
                    updateSelBar();
                    load();
                } else if (id == R.id.btnMore) {
                    showMoreMenu();
                } else if (id == R.id.btnPermAction) {
                    requestAccess();
                } else if (id == R.id.btnSelAll) {
                    adapter.selectAll();
                    updateSelBar();
                } else if (id == R.id.btnSelCopy) {
                    startTransfer(false);
                } else if (id == R.id.btnSelMove) {
                    startTransfer(true);
                } else if (id == R.id.btnSelUpload) {
                    startUpload(adapter.selectedPaths());
                } else if (id == R.id.btnSelDel) {
                    confirmDelete(adapter.selectedPaths());
                } else if (id == R.id.btnSelCancel) {
                    adapter.exitSelection();
                    updateSelBar();
                } else if (id == R.id.btnNewFile) {
                    doCreate(true);
                } else if (id == R.id.btnNewFolder) {
                    doCreate(false);
                } else if (id == R.id.btnSort) {
                    showSortDialog();
                } else if (id == R.id.btnTrash) {
                    showTrash();
                } else if (id == R.id.btnCloud) {
                    finish();
                } else if (id == R.id.btnStateAction) {
                    load();
                }
            }
        };

        int[] ids = {R.id.btnBack, R.id.btnSearch, R.id.btnSearchClose, R.id.btnRefresh,
                R.id.btnMore, R.id.btnPermAction, R.id.btnSelAll, R.id.btnSelCopy,
                R.id.btnSelMove, R.id.btnSelUpload, R.id.btnSelDel, R.id.btnSelCancel,
                R.id.btnNewFile, R.id.btnNewFolder, R.id.btnSort, R.id.btnTrash,
                R.id.btnCloud, R.id.btnStateAction};
        for (int id : ids) {
            View v = findViewById(id);
            if (v != null) {
                v.setOnClickListener(click);
            }
        }

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s == null ? "" : s.toString();
                searching = !query.trim().isEmpty();
                ui.removeCallbacks(searchTask);
                ui.postDelayed(searchTask, 250);
            }
        });

        updatePermBanner();
        updateCrumb();
        updateSelBar();
    }

    // ---------------------------------------------------------------- 路径与权限

    private boolean hasAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                return Environment.isExternalStorageManager();
            } catch (Exception e) {
                return false;
            }
        }
        // minSdk 24，运行时权限自 23 起，无需再判 SDK_INT
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAccess() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } else {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
            }
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(ACTION_MANAGE_ALL_FILES));
            } catch (Exception ignored) {
                Ui.toastLong(this, "当前系统没有提供文件授权入口，可继续使用应用私有目录");
            }
        } catch (Exception e) {
            Ui.toastLong(this, "无法打开授权设置：" + LocalFs.message(e));
        }
    }

    private void initPaths() {
        File pub = Environment.getExternalStorageDirectory();
        File priv = getExternalFilesDir(null);
        if (priv == null) {
            priv = getFilesDir();
        }
        rootPath = fullAccess && pub != null ? pub.getAbsolutePath() : priv.getAbsolutePath();
        trashDir = new File(rootPath, ".PanXiaTrash").getAbsolutePath();
        String saved = prefs.getString("path", "");
        File f = new File(saved);
        if (!saved.isEmpty() && f.isDirectory() && f.getAbsolutePath().startsWith(rootPath)) {
            path = f.getAbsolutePath();
        } else {
            path = rootPath;
        }
        updatePermBanner();
    }

    private void updatePermBanner() {
        if (bannerPerm == null) {
            return;
        }
        bannerPerm.setVisibility(fullAccess ? View.GONE : View.VISIBLE);
        if (!fullAccess) {
            tvPermHint.setText(getString(R.string.local_perm_hint));
        }
    }

    private void savePath() {
        prefs.edit().putString("path", path).apply();
    }

    // ---------------------------------------------------------------- 加载与渲染

    private void load() {
        if (destroyed) {
            return;
        }
        final long seq = ++loadSeq;
        final String target = path;
        final String q = query;
        final boolean useSearch = searching;
        loading.setVisibility(View.VISIBLE);
        hideState();
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<LocalFs.Entry> result = null;
                String error = null;
                try {
                    result = useSearch
                            ? LocalFs.search(target, q, new LocalFs.Cancel() {
                        @Override
                        public boolean cancelled() {
                            return seq != loadSeq || destroyed;
                        }
                    })
                            : LocalFs.list(target);
                } catch (Throwable t) {
                    error = LocalFs.message(t);
                }
                final List<LocalFs.Entry> r = result;
                final String err = error;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (seq != loadSeq || destroyed) {
                            return;
                        }
                        loading.setVisibility(View.GONE);
                        if (r == null) {
                            all.clear();
                            adapter.submit(new ArrayList<LocalFs.Entry>());
                            showState(R.drawable.ic_error, "无法读取这个目录",
                                    err == null ? "请检查权限或换个目录" : err, "重试");
                            return;
                        }
                        all.clear();
                        all.addAll(r);
                        render();
                    }
                });
            }
        }).start();
    }

    private void render() {
        List<LocalFs.Entry> v = new ArrayList<>(all);
        v = LocalFs.filterHidden(v, showHidden);
        LocalFs.sort(v, sortField, sortDesc);
        adapter.submit(v);
        updateSelBar();

        if (v.isEmpty()) {
            if (searching) {
                showState(R.drawable.ic_search, "没有找到匹配的文件", "换个关键词，或清空搜索框", null);
            } else {
                showState(R.drawable.ic_empty,
                        getString(R.string.empty_folder), getString(R.string.empty_folder_hint), null);
            }
        } else {
            hideState();
        }
        tvSub.setText(searching
                ? getString(R.string.sub_search, query, shortPath(path))
                : shortPath(path));
        updateCrumb();
    }

    private void showState(int icon, String title, String hint, String action) {
        ivState.setImageResource(icon);
        tvStateTitle.setText(title);
        tvStateHint.setText(hint == null ? "" : hint);
        btnStateAction.setVisibility(action == null ? View.GONE : View.VISIBLE);
        if (action != null) {
            btnStateAction.setText(action);
        }
        stateView.setVisibility(View.VISIBLE);
        list.setVisibility(View.GONE);
    }

    private void hideState() {
        stateView.setVisibility(View.GONE);
        list.setVisibility(View.VISIBLE);
    }

    private void updateCrumb() {
        if (crumb == null || rootPath == null || path == null) {
            return;
        }
        crumb.removeAllViews();
        addCrumb(rootPath, fullAccess ? "手机存储" : "应用目录", path.equals(rootPath));
        String rel = path.startsWith(rootPath) ? path.substring(rootPath.length()) : "";
        String built = rootPath;
        if (!rel.isEmpty()) {
            String[] parts = rel.replace('\\', '/').split("/");
            for (String part : parts) {
                if (part.isEmpty()) {
                    continue;
                }
                built = new File(built, part).getAbsolutePath();
                addCrumb(built, part, built.equals(path));
            }
        }
        ui.post(new Runnable() {
            @Override
            public void run() {
                scrollCrumb.fullScroll(HorizontalScrollView.FOCUS_RIGHT);
            }
        });
    }

    private void addCrumb(final String target, String label, boolean current) {
        if (crumb.getChildCount() > 0) {
            TextView sep = new TextView(this);
            sep.setText("›");
            sep.setTextSize(16);
            sep.setTextColor(Ui.color(this, R.attr.olDim));
            sep.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
            crumb.addView(sep);
        }
        TextView t = new TextView(this);
        t.setText(label);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setTextSize(13);
        t.setTextColor(Ui.color(this, current ? R.attr.olAccent : R.attr.olDim));
        t.setBackgroundResource(current ? R.drawable.bg_chip_on : R.drawable.bg_chip);
        int padH = Ui.dp(this, 12);
        t.setPadding(padH, Ui.dp(this, 6), padH, Ui.dp(this, 6));
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (target.equals(path)) {
                    return;
                }
                path = target;
                savePath();
                adapter.exitSelection();
                closeSearch();
                updateCrumb();
                load();
            }
        });
        Feedback.press(t);
        crumb.addView(t);
    }

    private void updateSelBar() {
        if (adapter == null || barSel == null) {
            return;
        }
        boolean on = adapter.selectionMode();
        barSel.setVisibility(on ? View.VISIBLE : View.GONE);
        int n = adapter.selectedCount();
        tvSelCount.setText(n == 0 ? getString(R.string.selected_none)
                : getString(R.string.selected_count, n));
        boolean has = n > 0;
        setActionEnabled(btnSelAll, true);
        setActionEnabled(btnSelCopy, has);
        setActionEnabled(btnSelMove, has);
        setActionEnabled(btnSelUpload, has);
        setActionEnabled(btnSelDel, has);
        setActionEnabled(btnSelCancel, true);
    }

    private void setActionEnabled(TextView v, boolean on) {
        if (v == null) {
            return;
        }
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.38f);
    }

    // ---------------------------------------------------------------- 搜索 / 排序 / 菜单

    private void toggleSearch() {
        if (rowSearch.getVisibility() == View.VISIBLE) {
            closeSearch();
        } else {
            rowSearch.setVisibility(View.VISIBLE);
            etSearch.requestFocus();
        }
    }

    private void closeSearch() {
        ui.removeCallbacks(searchTask);
        rowSearch.setVisibility(View.GONE);
        etSearch.setText("");
        query = "";
        searching = false;
        Ui.hideKeyboard(this);
        load();
    }

    private void showSortDialog() {
        String[] labels = {"名称 ↑", "名称 ↓", "大小 ↑", "大小 ↓",
                "修改时间 ↑", "修改时间 ↓", "类型 ↑", "类型 ↓"};
        Ui.choose(this, "排序方式", labels, new Ui.OnIndex() {
            @Override
            public void onIndex(int index) {
                sortField = index / 2;
                sortDesc = index % 2 == 1;
                prefs.edit().putInt("sort", sortField).putBoolean("desc", sortDesc).apply();
                render();
            }
        });
    }

    private void showMoreMenu() {
        String[] labels = {
                showHidden ? "隐藏隐藏文件" : "显示隐藏文件",
                "回到存储根目录", "刷新当前目录", "本地文件说明"};
        Ui.choose(this, "更多", labels, new Ui.OnIndex() {
            @Override
            public void onIndex(int i) {
                if (i == 0) {
                    showHidden = !showHidden;
                    prefs.edit().putBoolean("show_hidden", showHidden).apply();
                    render();
                } else if (i == 1) {
                    path = rootPath;
                    savePath();
                    adapter.exitSelection();
                    closeSearch();
                    load();
                } else if (i == 2) {
                    load();
                } else {
                    Ui.info(LocalFilesActivity.this, "本地文件",
                            "这里管理的是手机上的文件，与网盘相互独立。\n\n"
                                    + "· 删除会先进入回收站，可以在里面恢复\n"
                                    + "· 复制、移动遇到同名会自动改名，不会覆盖\n"
                                    + "· 大文件操作可以中途取消，不会留下半成品\n"
                                    + "· 选中文件后可上传到当前网盘目录");
                }
            }
        });
    }

    // ---------------------------------------------------------------- 打开 / 操作菜单

    private void open(LocalFs.Entry e) {
        File f = new File(e.path);
        if (!f.exists()) {
            Ui.toast(this, "文件已不存在，请刷新");
            return;
        }
        if (e.dir || f.isDirectory()) {
            path = f.getAbsolutePath();
            savePath();
            adapter.exitSelection();
            if (searching) {
                closeSearch();
            }
            updateCrumb();
            load();
        } else {
            openWithSystem(e);
        }
    }

    private void openWithSystem(LocalFs.Entry e) {
        Uri uri = LocalProvider.uriForAbs(e.path);
        if (Uri.EMPTY.equals(uri)) {
            Ui.toast(this, "无法生成预览地址");
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, Mime.mime(e.name));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (ActivityNotFoundException ex) {
            Ui.toast(this, "没有应用能打开这类文件，可用「更多」里的重命名或上传");
        } catch (Exception ex) {
            Ui.toastLong(this, "打开失败：" + LocalFs.message(ex));
        }
    }

    private void showActions(final LocalFs.Entry e) {
        String[] labels = {"打开", "详情", "重命名", "上传到网盘", "复制到…", "移动到…", "删除"};
        Ui.choose(this, e.name, labels, new Ui.OnIndex() {
            @Override
            public void onIndex(int i) {
                if (i == 0) {
                    open(e);
                } else if (i == 1) {
                    showDetail(e);
                } else if (i == 2) {
                    doRename(e);
                } else if (i == 3) {
                    startUpload(java.util.Collections.singletonList(e.path));
                } else if (i == 4) {
                    startTransferWith(java.util.Collections.singletonList(e.path), false);
                } else if (i == 5) {
                    startTransferWith(java.util.Collections.singletonList(e.path), true);
                } else if (i == 6) {
                    confirmDelete(java.util.Collections.singletonList(e.path));
                }
            }
        });
    }

    private void showDetail(LocalFs.Entry e) {
        String msg = "名称：" + e.name + "\n\n路径：" + e.path
                + "\n\n类型：" + LocalFs.typeName(e)
                + "\n\n大小：" + (e.dir ? "—" : LocalFs.size(e.size))
                + "\n\n修改时间：" + (Ui.time(e.modified).isEmpty() ? "未知" : Ui.time(e.modified));
        Ui.info(this, "文件详情", msg);
    }

    // ---------------------------------------------------------------- 新建 / 重命名

    private void doCreate(final boolean file) {
        Ui.input(this, file ? "新建文件" : "新建文件夹",
                file ? "文件名（含扩展名）" : "文件夹名称", "", new Ui.OnText() {
                    @Override
                    public void onText(final String value) {
                        runSimple(file ? "创建文件" : "创建文件夹", new Work() {
                            @Override
                            public Object run() throws Exception {
                                final String d = path;
                                return file
                                        ? LocalFs.createFile(d, value, LocalFs.CONFLICT_RENAME)
                                        : LocalFs.createDir(d, value, LocalFs.CONFLICT_RENAME);
                            }
                        });
                    }
                });
    }

    private void doRename(final LocalFs.Entry e) {
        Ui.input(this, "重命名", "新名称", e.name, new Ui.OnText() {
            @Override
            public void onText(final String value) {
                runSimple("重命名", new Work() {
                    @Override
                    public Object run() throws Exception {
                        return LocalFs.rename(e.path, value, LocalFs.CONFLICT_RENAME);
                    }
                });
            }
        });
    }

    // ---------------------------------------------------------------- 删除 / 回收站

    private void confirmDelete(final List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            Ui.toast(this, "请先选择文件");
            return;
        }
        StringBuilder names = new StringBuilder();
        int n = Math.min(paths.size(), 5);
        for (int i = 0; i < n; i++) {
            names.append("· ").append(new File(paths.get(i)).getName()).append('\n');
        }
        if (paths.size() > n) {
            names.append("· 其余 ").append(paths.size() - n).append(" 项…");
        }
        Ui.confirm(this, "移到回收站",
                "确定删除这 " + paths.size() + " 项吗？\n\n" + names
                        + "\n删除后可在「回收站」恢复，不会立即抹掉数据。",
                "移到回收站", new Runnable() {
                    @Override
                    public void run() {
                        final List<String> copy = new ArrayList<>(paths);
                        runBatch("删除", new BatchWork() {
                            @Override
                            public LocalFs.Result run(LocalFs.Cancel cancel,
                                                      LocalFs.Progress progress) {
                                return LocalFs.trash(copy, trashDir);
                            }
                        });
                    }
                });
    }

    private void showTrash() {
        final List<LocalFs.TrashItem> items = LocalFs.listTrash(trashDir);
        if (items.isEmpty()) {
            Ui.toast(this, "回收站是空的");
            return;
        }
        List<String> labels = new ArrayList<>();
        for (LocalFs.TrashItem t : items) {
            String parent = new File(t.origin).getParent();
            labels.add(t.name + "\n原位置：" + shortPath(parent == null ? t.origin : parent));
        }
        labels.add("清空回收站（不可恢复）");
        Ui.choose(this, "回收站（" + items.size() + " 项）",
                labels.toArray(new String[0]), new Ui.OnIndex() {
                    @Override
                    public void onIndex(final int index) {
                        if (index == items.size()) {
                            Ui.confirm(LocalFilesActivity.this, "清空回收站",
                                    "将永久删除回收站里的 " + items.size() + " 项，此操作不可恢复。",
                                    "永久删除", new Runnable() {
                                        @Override
                                        public void run() {
                                            boolean ok = LocalFs.purgeTrash(trashDir);
                                            Ui.toast(LocalFilesActivity.this,
                                                    ok ? "回收站已清空" : "部分项目删除失败");
                                            load();
                                        }
                                    });
                            return;
                        }
                        final LocalFs.TrashItem item = items.get(index);
                        Ui.choose(LocalFilesActivity.this, item.name,
                                new String[]{"恢复到原位置", "彻底删除"}, new Ui.OnIndex() {
                                    @Override
                                    public void onIndex(int which) {
                                        if (which == 0) {
                                            runSimple("恢复", new Work() {
                                                @Override
                                                public Object run() throws Exception {
                                                    return LocalFs.restore(trashDir,
                                                            java.util.Collections.singletonList(item.id));
                                                }
                                            });
                                        } else {
                                            Ui.confirm(LocalFilesActivity.this, "彻底删除",
                                                    "将永久删除「" + item.name + "」，不可恢复。",
                                                    "永久删除", new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            runSimple("彻底删除", new Work() {
                                                                @Override
                                                                public Object run() throws Exception {
                                                                    return LocalFs.deletePermanent(
                                                                            java.util.Collections
                                                                                    .singletonList(
                                                                                            new File(trashDir,
                                                                                                    item.id)
                                                                                            .getAbsolutePath()),
                                                                            null);
                                                                }
                                                            });
                                                        }
                                                    });
                                        }
                                    }
                                });
                    }
                });
    }

    // ---------------------------------------------------------------- 复制 / 移动

    private void startTransfer(final boolean move) {
        startTransferWith(adapter.selectedPaths(), move);
    }

    private void startTransferWith(final List<String> paths, final boolean move) {
        if (paths == null || paths.isEmpty()) {
            Ui.toast(this, "请先选择文件");
            return;
        }
        Ui.toast(this, "选择目标目录；同名自动改名，大文件可中途取消");
        pickLocalDir(path, new OnDir() {
            @Override
            public void onDir(final String dest) {
                if (dest.equals(path)) {
                    Ui.toast(LocalFilesActivity.this, "目标目录与当前目录相同");
                    return;
                }
                final List<String> copy = new ArrayList<>(paths);
                final boolean isMove = move;
                runBatch(isMove ? "移动" : "复制", new BatchWork() {
                    @Override
                    public LocalFs.Result run(LocalFs.Cancel cancel, LocalFs.Progress progress) {
                        return isMove
                                ? LocalFs.move(copy, dest, LocalFs.CONFLICT_RENAME, cancel, progress)
                                : LocalFs.copy(copy, dest, LocalFs.CONFLICT_RENAME, cancel, progress);
                    }
                });
            }
        });
    }

    private void pickLocalDir(final String start, final OnDir cb) {
        final File cur = new File(start);
        List<LocalFs.Entry> kids;
        try {
            kids = LocalFs.list(cur.getAbsolutePath());
        } catch (Exception e) {
            Ui.toastLong(this, "无法读取目标目录：" + LocalFs.message(e));
            return;
        }
        final List<String> targets = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        targets.add(cur.getAbsolutePath());
        labels.add("选择此目录");
        File parent = cur.getParentFile();
        if (parent != null && !cur.getAbsolutePath().equals(rootPath)
                && parent.getAbsolutePath().startsWith(rootPath)) {
            targets.add(parent.getAbsolutePath());
            labels.add("上一级");
        }
        List<LocalFs.Entry> dirs = new ArrayList<>();
        for (LocalFs.Entry e : kids) {
            if (e.dir && !e.name.startsWith(".PanXiaTrash")) {
                dirs.add(e);
            }
        }
        LocalFs.sort(dirs, LocalFs.SORT_NAME, false);
        for (LocalFs.Entry e : dirs) {
            targets.add(e.path);
            labels.add("文件夹：" + e.name);
        }
        Ui.choose(this, "目标：" + shortPath(cur.getAbsolutePath()),
                labels.toArray(new String[0]), new Ui.OnIndex() {
                    @Override
                    public void onIndex(int i) {
                        String t = targets.get(i);
                        if (t.equals(cur.getAbsolutePath())) {
                            cb.onDir(t);
                        } else {
                            pickLocalDir(t, cb);
                        }
                    }
                });
    }

    // ---------------------------------------------------------------- 上传网盘

    private void startUpload(final List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            Ui.toast(this, "请先选择文件");
            return;
        }
        if (store.token().isEmpty() || store.base().isEmpty()) {
            Ui.toastLong(this, "请先在网盘页登录服务器");
            return;
        }
        final List<File> files = new ArrayList<>();
        int dirs = 0;
        for (String p : paths) {
            File f = new File(p);
            if (!f.exists()) {
                continue;
            }
            if (f.isDirectory()) {
                dirs++;
            } else {
                files.add(f);
            }
        }
        if (files.isEmpty()) {
            Ui.toastLong(this, dirs > 0 ? "暂不支持上传整个文件夹，请进入文件夹后选择文件"
                    : "所选文件已不存在");
            return;
        }
        final String remote = store.lastPath().isEmpty() ? "/" : store.lastPath();
        String msg = "将 " + files.size() + " 个文件上传到网盘目录：\n" + remote;
        if (dirs > 0) {
            msg += "\n\n（已跳过 " + dirs + " 个文件夹）";
        }
        final int skippedDirs = dirs;
        Ui.confirm(this, "上传到网盘", msg, "开始上传", new Runnable() {
            @Override
            public void run() {
                final List<File> uploadList = new ArrayList<>(files);
                runUpload(uploadList, remote, skippedDirs);
            }
        });
    }

    private void runUpload(final List<File> files, final String remote, final int skippedDirs) {
        showProgress("上传到网盘", true);
        cancelFlag = false;
        final long[] total = {0};
        for (File f : files) {
            total[0] += f.length();
        }
        final long[] done = {0};
        new Thread(new Runnable() {
            @Override
            public void run() {
                int ok = 0;
                final List<String> errors = new ArrayList<>();
                Api client = new Api(store);
                Api.applyInsecure(store.insecure());
                for (final File f : files) {
                    if (cancelFlag || destroyed) {
                        errors.add("已取消，其余文件未上传");
                        break;
                    }
                    FileInputStream in = null;
                    try {
                        final long len = f.length();
                        in = new FileInputStream(f);
                        final long base = done[0];
                        client.upload(Ui.join(remote, f.getName()), in, len,
                                new Api.Progress() {
                                    @Override
                                    public void onProgress(long sent, long size) {
                                        setProgress(base + sent, total[0], f.getName());
                                    }
                                }, new Api.Cancel() {
                                    @Override
                                    public boolean cancelled() {
                                        return cancelFlag || destroyed;
                                    }
                                });
                        done[0] += len;
                        ok++;
                    } catch (Throwable t) {
                        errors.add(f.getName() + "：" + LocalFs.message(t));
                    } finally {
                        if (in != null) {
                            try {
                                in.close();
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
                final int success = ok;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        dismissProgress();
                        if (destroyed) {
                            return;
                        }
                        StringBuilder b = new StringBuilder();
                        b.append("成功 ").append(success).append(" / ").append(files.size()).append(" 个");
                        if (skippedDirs > 0) {
                            b.append("\n跳过文件夹 ").append(skippedDirs).append(" 个");
                        }
                        if (!errors.isEmpty()) {
                            b.append("\n\n");
                            int n = Math.min(errors.size(), 5);
                            for (int i = 0; i < n; i++) {
                                b.append("· ").append(errors.get(i)).append('\n');
                            }
                            if (errors.size() > n) {
                                b.append("· 其余 ").append(errors.size() - n).append(" 项…");
                            }
                        }
                        Ui.info(LocalFilesActivity.this, "上传结果", b.toString());
                        if (success > 0) {
                            adapter.exitSelection();
                            updateSelBar();
                        }
                    }
                });
            }
        }).start();
    }

    // ---------------------------------------------------------------- 后台执行框架

    private interface Work {
        Object run() throws Exception;
    }

    private interface BatchWork {
        LocalFs.Result run(LocalFs.Cancel cancel, LocalFs.Progress progress) throws Exception;
    }

    private interface OnDir {
        void onDir(String dir);
    }

    private void runSimple(final String action, final Work work) {
        showProgress(action, false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                Object result = null;
                Throwable error = null;
                try {
                    result = work.run();
                } catch (Throwable t) {
                    error = t;
                }
                final Throwable err = error;
                final Object res = result;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        dismissProgress();
                        if (destroyed) {
                            return;
                        }
                        if (err != null) {
                            Ui.toastLong(LocalFilesActivity.this,
                                    action + "失败：" + LocalFs.message(err));
                            return;
                        }
                        if (res instanceof LocalFs.Result && !((LocalFs.Result) res).allOk()) {
                            report((LocalFs.Result) res, action);
                        } else {
                            Ui.toast(LocalFilesActivity.this, action + "成功");
                        }
                        adapter.exitSelection();
                        updateSelBar();
                        load();
                    }
                });
            }
        }).start();
    }

    private void runBatch(final String action, final BatchWork work) {
        showProgress(action, true);
        cancelFlag = false;
        final LocalFs.Cancel cancel = new LocalFs.Cancel() {
            @Override
            public boolean cancelled() {
                return cancelFlag || destroyed;
            }
        };
        final LocalFs.Progress progress = new LocalFs.Progress() {
            @Override
            public void onProgress(long done, long total) {
                setProgress(done, total, "");
            }
        };
        new Thread(new Runnable() {
            @Override
            public void run() {
                LocalFs.Result result = null;
                Throwable error = null;
                try {
                    result = work.run(cancel, progress);
                } catch (Throwable t) {
                    error = t;
                }
                final LocalFs.Result r = result;
                final Throwable err = error;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        dismissProgress();
                        if (destroyed) {
                            return;
                        }
                        if (err != null) {
                            Ui.toastLong(LocalFilesActivity.this,
                                    action + "失败：" + LocalFs.message(err));
                        } else if (r != null) {
                            report(r, action);
                        }
                        adapter.exitSelection();
                        updateSelBar();
                        load();
                    }
                });
            }
        }).start();
    }

    private void report(LocalFs.Result r, String action) {
        StringBuilder b = new StringBuilder();
        b.append("成功 ").append(r.ok).append(" 项");
        if (r.skipped > 0) {
            b.append("，跳过 ").append(r.skipped).append(" 项");
        }
        if (!r.allOk()) {
            b.append("，失败 ").append(r.errors.size()).append(" 项\n\n");
            int n = Math.min(r.errors.size(), 5);
            for (int i = 0; i < n; i++) {
                b.append("· ").append(r.errors.get(i)).append('\n');
            }
            if (r.errors.size() > n) {
                b.append("· 其余 ").append(r.errors.size() - n).append(" 项…");
            }
            Ui.info(this, action + "结果", b.toString());
        } else {
            Ui.toast(this, b.toString());
        }
    }

    // ---------------------------------------------------------------- 进度框

    private void showProgress(String title, final boolean cancellable) {
        dismissProgress();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        box.setPadding(pad, Ui.dp(this, 8), pad, 0);

        progressLabel = new TextView(this);
        progressLabel.setText(getString(R.string.progress_title, title));
        progressLabel.setTextSize(13);
        progressLabel.setTextColor(Ui.color(this, R.attr.olText));
        box.addView(progressLabel);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setIndeterminate(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 24));
        lp.topMargin = Ui.dp(this, 12);
        box.addView(progressBar, lp);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setCancelable(false);
        if (cancellable) {
            b.setNegativeButton("取消", new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface d, int w) {
                    cancelFlag = true;
                    if (progressLabel != null) {
                        progressLabel.setText("正在停止…");
                    }
                }
            });
        }
        progressDialog = b.create();
        progressDialog.show();
    }

    private void setProgress(final long done, final long total, final String name) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (progressBar == null || progressDialog == null
                        || !progressDialog.isShowing()) {
                    return;
                }
                progressBar.setIndeterminate(false);
                int percent = total > 0 ? (int) (done * 100 / total) : 0;
                progressBar.setProgress(Math.max(0, Math.min(100, percent)));
                if (progressLabel != null) {
                    progressLabel.setText(name.isEmpty()
                            ? getString(R.string.progress_detail_no_name, percent,
                                    LocalFs.size(done), LocalFs.size(total))
                            : getString(R.string.progress_detail, name, percent,
                                    LocalFs.size(done), LocalFs.size(total)));
                }
            }
        });
    }

    private void dismissProgress() {
        try {
            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.dismiss();
            }
        } catch (Exception ignored) {
        }
        progressDialog = null;
        progressBar = null;
        progressLabel = null;
    }

    // ---------------------------------------------------------------- 工具

    private String shortPath(String p) {
        if (p == null) {
            return "";
        }
        if (rootPath != null && p.startsWith(rootPath)) {
            String rel = p.substring(rootPath.length());
            return rel.isEmpty() ? (fullAccess ? "手机存储" : "应用目录") : rel;
        }
        return p;
    }
}
