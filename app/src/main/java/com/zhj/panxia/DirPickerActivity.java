package com.zhj.panxia;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 目录选择器：给「复制到 / 移动到」选目标目录。 */
public class DirPickerActivity extends Activity {

    public static final String EXTRA_START = "start";
    public static final String EXTRA_RESULT = "path";

    private Store store;
    private Api api;
    private ListView list;
    private ProgressBar loading;
    private TextView tvPath;
    private TextView tvTitle;

    private final List<Api.Entry> dirs = new ArrayList<>();
    private DirAdapter adapter;
    private String path = "/";
    private String start = "/";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        store = new Store(this);
        Theme.apply(this, store);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_picker);
        Theme.install(this, R.id.topBar);

        api = new Api(store);
        Api.applyInsecure(store.insecure());

        list = findViewById(R.id.list);
        loading = findViewById(R.id.loading);
        tvPath = findViewById(R.id.tvPath);
        tvTitle = findViewById(R.id.tvTitle);

        start = getIntent().getStringExtra(EXTRA_START);
        if (start == null || start.isEmpty()) {
            start = "/";
        }
        tvTitle.setText("选择目标目录");

        adapter = new DirAdapter();
        list.setAdapter(adapter);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btnPick).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent out = new Intent();
                out.putExtra(EXTRA_RESULT, path);
                setResult(RESULT_OK, out);
                finish();
            }
        });
        Feedback.press(findViewById(R.id.btnBack));
        Feedback.press(findViewById(R.id.btnPick));

        load(start);
    }

    @Override
    public void onBackPressed() {
        if (!"/".equals(path)) {
            load(Ui.parent(path));
            return;
        }
        super.onBackPressed();
    }

    private void load(final String target) {
        loading.setVisibility(View.VISIBLE);
        path = target;
        tvPath.setText(target);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<Api.Entry> result = api.dirs(target);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            dirs.clear();
                            if (!"/".equals(target)) {
                                Api.Entry up = new Api.Entry();
                                up.name = "..";
                                up.dir = true;
                                dirs.add(up);
                            }
                            dirs.addAll(result);
                            adapter.notifyDataSetChanged();
                            loading.setVisibility(View.GONE);
                        }
                    });
                } catch (final Api.ApiError e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            loading.setVisibility(View.GONE);
                            Ui.toast(DirPickerActivity.this, e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private class DirAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return dirs.size();
        }

        @Override
        public Object getItem(int i) {
            return dirs.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            View v = convert;
            if (v == null) {
                v = LayoutInflater.from(DirPickerActivity.this)
                        .inflate(R.layout.item_dir, parent, false);
            }
            TextView name = v.findViewById(R.id.tvName);
            final Api.Entry e = dirs.get(i);
            name.setText("..".equals(e.name) ? "上级目录" : e.name);

            // 点击直接挂在行上，不在行内挂触摸监听，避免和列表滚动/点击打架
            v.setScaleX(1f);
            v.setScaleY(1f);
            v.setClickable(true);
            final int pos = i;
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Feedback.tick(view);
                    Feedback.pulse(view);
                    Api.Entry item = dirs.get(pos);
                    if ("..".equals(item.name)) {
                        load(Ui.parent(path));
                    } else {
                        load(Ui.join(path, item.name));
                    }
                }
            });
            return v;
        }
    }
}
