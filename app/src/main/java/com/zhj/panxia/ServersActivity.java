package com.zhj.panxia;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务器列表：查看 / 切换 / 重命名 / 测试 / 删除本机保存的多台 OpenList 服务器。
 * 切换后返回时把 RESULT_OK 带回主界面，由主界面重新连接并刷新。
 */
public class ServersActivity extends Activity {

    private static final int REQ_ADD = 201;

    private Store store;
    private final List<Store.Server> items = new ArrayList<>();
    private Adapter adapter;
    private ListView list;
    private TextView empty;

    /** 有过任何改动就回传 RESULT_OK，主界面据此重新加载 */
    private boolean changed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        store = new Store(this);
        Theme.apply(this, store);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_servers);
        Theme.install(this, R.id.topBar);

        list = findViewById(R.id.lst);
        empty = findViewById(R.id.empty);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        View.OnClickListener add = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(ServersActivity.this, LoginActivity.class)
                        .putExtra(LoginActivity.EXTRA_NEW, true), REQ_ADD);
            }
        };
        findViewById(R.id.btnAdd).setOnClickListener(add);
        findViewById(R.id.btnAddServer).setOnClickListener(add);
        Feedback.press(findViewById(R.id.btnBack));
        Feedback.press(findViewById(R.id.btnAdd));
        Feedback.press(findViewById(R.id.btnAddServer));

        adapter = new Adapter();
        list.setAdapter(adapter);
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    @Override
    public void finish() {
        setResult(changed ? RESULT_OK : RESULT_CANCELED);
        super.finish();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ADD && resultCode == RESULT_OK) {
            changed = true;
            load();
        }
    }

    private void load() {
        items.clear();
        items.addAll(store.servers());
        adapter.notifyDataSetChanged();
        boolean none = items.isEmpty();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        list.setVisibility(none ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------ 操作

    /** 切换当前服务器 */
    private void use(Store.Server s) {
        if (s.base.equals(store.base())) {
            Ui.toast(this, "已经是当前服务器");
            return;
        }
        store.activate(s.base);
        Api.applyInsecure(s.insecure);
        changed = true;
        Ui.toast(this, "已切换到 " + s.label());
        finish();
    }

    private void showMenu(Store.Server s, View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add("切换到此服务器");
        pm.getMenu().add("重命名");
        pm.getMenu().add("测试连接");
        pm.getMenu().add("删除");
        final String base = s.base;
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(android.view.MenuItem item) {
                String t = item.getTitle().toString();
                Store.Server cur = store.byBase(base);
                if (cur == null) {
                    return true;
                }
                if ("切换到此服务器".equals(t)) {
                    use(cur);
                } else if ("重命名".equals(t)) {
                    rename(cur);
                } else if ("测试连接".equals(t)) {
                    test(cur);
                } else if ("删除".equals(t)) {
                    remove(cur);
                }
                return true;
            }
        });
        pm.show();
    }

    private void rename(final Store.Server s) {
        Ui.input(this, "重命名服务器", "只影响本机显示，例如「家里的 NAS」", s.label(), new Ui.OnText() {
            @Override
            public void onText(String value) {
                store.renameServer(s.base, value);
                changed = true;
                load();
            }
        });
    }

    private void test(final Store.Server s) {
        Ui.toast(this, "正在连接 " + s.label() + " …");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Api.applyInsecure(s.insecure);
                    final String version = new Api(s.base).ping();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Ui.toastLong(ServersActivity.this,
                                    "连接正常" + (version.isEmpty() ? "" : " · " + version));
                        }
                    });
                } catch (final Api.ApiError e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Ui.toastLong(ServersActivity.this, "连接失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void remove(final Store.Server s) {
        Ui.confirm(this, "删除服务器",
                "只会移除本机保存的地址和登录信息，服务器上的文件不受影响。",
                "删除", new Runnable() {
                    @Override
                    public void run() {
                        boolean wasActive = s.base.equals(store.base());
                        store.removeServer(s.base);
                        changed = true;
                        if (store.servers().isEmpty()) {
                            // 一台都不剩了，直接回登录页重新添加
                            goLogin();
                            return;
                        }
                        if (wasActive) {
                            Api.applyInsecure(store.insecure());
                        }
                        load();
                    }
                });
    }

    private void goLogin() {
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    // ------------------------------------------------------------ 列表

    private class Adapter extends BaseAdapter {

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            View v = convert;
            if (v == null) {
                v = LayoutInflater.from(ServersActivity.this)
                        .inflate(R.layout.item_server, parent, false);
            }
            // 复用行复位，避免上次的按压缩放残留
            v.setScaleX(1f);
            v.setScaleY(1f);
            v.setAlpha(1f);

            final Store.Server s = items.get(position);
            TextView name = v.findViewById(R.id.tvName);
            TextView meta = v.findViewById(R.id.tvMeta);
            TextView badge = v.findViewById(R.id.tvBadge);
            ImageView more = v.findViewById(R.id.btnMore);

            name.setText(s.label());
            StringBuilder sb = new StringBuilder(Api.label(s.base));
            if (!s.user.isEmpty()) {
                sb.append(" · ").append(s.user);
            }
            if (!s.loggedIn()) {
                sb.append(" · 未登录");
            }
            meta.setText(sb.toString());
            badge.setVisibility(s.base.equals(store.base()) ? View.VISIBLE : View.GONE);

            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Feedback.pulse(view);
                    use(s);
                }
            });
            more.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Feedback.press(view);
                    showMenu(s, view);
                }
            });
            return v;
        }
    }
}
