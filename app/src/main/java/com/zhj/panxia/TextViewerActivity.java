package com.zhj.panxia;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** 文本 / 代码 / Markdown 在线查看 + 编辑保存。 */
public class TextViewerActivity extends Activity {

    public static final String EXTRA_DIR = "dir";
    public static final String EXTRA_NAME = "name";

    private static final int MAX_BYTES = 2 * 1024 * 1024;

    private Store store;
    private Api api;
    private String dir = "/";
    private String name = "";
    private String source = "";
    private String charset = "UTF-8";
    private boolean bom = false;
    private boolean truncated = false;
    private boolean markdown = false;
    private boolean rawView = false;
    private int fontSize = 13;

    private boolean editing;
    private boolean viewBeforeEdit;
    private int byteLength;

    private TextView text;
    private EditText etBody;
    private TextView tvTitle;
    private TextView tvSub;
    private TextView tvError;
    private ProgressBar loadingView;
    private ScrollView scroll;
    private View barEdit;
    private View divEdit;
    private View btnEdit;
    private View btnFontMinus;
    private View btnFontPlus;

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
        markdown = Mime.isMarkdown(name);

        api = new Api(store);
        Api.applyInsecure(store.insecure());

        setContentView(R.layout.activity_text);
        Theme.install(this, R.id.topBar);

        text = findViewById(R.id.text);
        etBody = findViewById(R.id.etBody);
        tvTitle = findViewById(R.id.tvTitle);
        tvSub = findViewById(R.id.tvSub);
        tvError = findViewById(R.id.tvError);
        loadingView = findViewById(R.id.loading);
        scroll = findViewById(R.id.scroll);
        barEdit = findViewById(R.id.barEdit);
        divEdit = findViewById(R.id.divEdit);
        btnEdit = findViewById(R.id.btnEdit);
        btnFontMinus = findViewById(R.id.btnFontMinus);
        btnFontPlus = findViewById(R.id.btnFontPlus);

        tvTitle.setText(name);
        tvSub.setText("纯文本 · " + Api.hostOf(store.base()));
        text.setTextSize(fontSize);
        etBody.setTextSize(fontSize);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBackPressed();
            }
        });
        findViewById(R.id.btnFontMinus).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (fontSize > 9) {
                    fontSize -= 2;
                    paint();
                }
            }
        });
        findViewById(R.id.btnFontPlus).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (fontSize < 26) {
                    fontSize += 2;
                    paint();
                }
            }
        });
        findViewById(R.id.btnEdit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                enterEdit();
            }
        });
        findViewById(R.id.btnCancelEdit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDiscard();
            }
        });
        findViewById(R.id.btnSaveEdit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveEdit();
            }
        });
        findViewById(R.id.btnMore).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMore(v);
            }
        });
        int[] barIds = {R.id.btnBack, R.id.btnFontMinus, R.id.btnFontPlus,
                R.id.btnEdit, R.id.btnMore};
        for (int id : barIds) {
            Feedback.press(findViewById(id));
        }
        Feedback.press(findViewById(R.id.btnCancelEdit));
        Feedback.press(findViewById(R.id.btnSaveEdit));

        load();
    }

    @Override
    public void onBackPressed() {
        if (editing) {
            confirmDiscard();
            return;
        }
        super.onBackPressed();
    }

    private void paint() {
        text.setTextSize(fontSize);
        etBody.setTextSize(fontSize);
        boolean render = markdown && !rawView;
        if (source.isEmpty()) {
            return;
        }
        if (render) {
            text.setTypeface(Typeface.DEFAULT);
            text.setTextIsSelectable(false);
            text.setMovementMethod(LinkMovementMethod.getInstance());
            text.setText(Md.render(this, source,
                    Ui.color(this, R.attr.olAccent), Ui.color(this, R.attr.olDim)));
        } else {
            text.setTypeface(Typeface.MONOSPACE);
            text.setTextIsSelectable(true);
            text.setMovementMethod(null);
            text.setText(source);
        }
        tvSub.setText((render ? "Markdown 渲染" : Mime.isCode(name) ? "代码" : "纯文本")
                + " · " + Ui.size(byteLength) + " · " + Api.hostOf(store.base()));
        scroll.scrollTo(0, 0);
    }

    private void load() {
        loadingView.setVisibility(View.VISIBLE);
        new Thread(new Runnable() {
            @Override
            public void run() {
                Net.Text info = null;
                int len = 0;
                String err = null;
                try {
                    String raw = api.rawUrl(Ui.join(dir, name));
                    if (raw.isEmpty()) {
                        throw new Exception("服务器未返回直链");
                    }
                    byte[] data = Net.bytes(raw, MAX_BYTES);
                    len = data.length;
                    info = Net.decode(data);
                } catch (Exception e) {
                    err = e.getMessage() == null ? "读取失败" : e.getMessage();
                }
                final Net.Text fi = info;
                final int flen = len;
                final String fe = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        loadingView.setVisibility(View.GONE);
                        if (fi == null) {
                            tvError.setText("打开失败：" + fe);
                            tvError.setVisibility(View.VISIBLE);
                            return;
                        }
                        source = fi.text;
                        charset = fi.charset;
                        bom = fi.bom;
                        truncated = flen >= MAX_BYTES;
                        byteLength = flen;
                        paint();
                        if (truncated) {
                            Ui.toastLong(TextViewerActivity.this,
                                    "文件超过 " + Ui.size(MAX_BYTES) + "，只显示前一部分，且不支持在线编辑");
                        }
                    }
                });
            }
        }).start();
    }

    // ------------------------------------------------------------ 编辑

    private void enterEdit() {
        if (editing) {
            return;
        }
        if (source.isEmpty()) {
            Ui.toast(this, "内容还没加载完成");
            return;
        }
        if (truncated) {
            Ui.toastLong(this, "文件过大，暂不支持在线编辑");
            return;
        }
        editing = true;
        viewBeforeEdit = rawView;
        rawView = true;
        etBody.setText(source);
        etBody.setTextSize(fontSize);
        etBody.setSelection(0);
        scroll.setVisibility(View.GONE);
        etBody.setVisibility(View.VISIBLE);
        barEdit.setVisibility(View.VISIBLE);
        divEdit.setVisibility(View.VISIBLE);
        btnEdit.setVisibility(View.GONE);
        btnFontMinus.setVisibility(View.GONE);
        btnFontPlus.setVisibility(View.GONE);
        tvSub.setText("编辑中 · " + Ui.size(byteLength) + " · " + charset
                + (bom ? " BOM" : ""));
        if (!"UTF-8".equalsIgnoreCase(charset)) {
            Ui.toastLong(this, "该文件按 " + charset + " 打开，保存时会用同样的编码写回");
        }
        etBody.requestFocus();
        etBody.post(new Runnable() {
            @Override
            public void run() {
                InputMethodManager imm = (InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(etBody, InputMethodManager.SHOW_IMPLICIT);
                }
            }
        });
    }

    /** 退出编辑态（不保存） */
    private void exitEdit() {
        editing = false;
        rawView = viewBeforeEdit;
        etBody.setVisibility(View.GONE);
        barEdit.setVisibility(View.GONE);
        divEdit.setVisibility(View.GONE);
        scroll.setVisibility(View.VISIBLE);
        btnEdit.setVisibility(View.VISIBLE);
        btnFontMinus.setVisibility(View.VISIBLE);
        btnFontPlus.setVisibility(View.VISIBLE);
        Ui.hideKeyboard(this);
        paint();
    }

    private boolean hasChanges() {
        return editing && !etBody.getText().toString().equals(source);
    }

    private void confirmDiscard() {
        if (!hasChanges()) {
            exitEdit();
            return;
        }
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("还没保存")
                .setMessage("修改后的内容还没有保存到服务器。")
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        saveEdit();
                    }
                })
                .setNeutralButton("放弃修改", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        exitEdit();
                    }
                })
                .setNegativeButton("继续编辑", null)
                .create();
        d.show();
        int color = Ui.color(this, R.attr.olAccent);
        try {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(color);
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(Ui.color(this, R.attr.olDanger));
        } catch (Exception ignored) {
        }
    }

    private void saveEdit() {
        final String content = etBody.getText().toString();
        if (content.equals(source)) {
            Ui.toast(this, "没有修改");
            exitEdit();
            return;
        }
        final byte[] data = encode(content);
        showBusy("正在保存 " + name);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    api.upload(Ui.join(dir, name), new ByteArrayInputStream(data), data.length,
                            null, null);
                } catch (Exception e) {
                    err = e.getMessage() == null ? "写入失败" : e.getMessage();
                }
                final String fe = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        dismissProgress();
                        if (fe != null) {
                            Ui.toastLong(TextViewerActivity.this, "保存失败：" + fe);
                            return;
                        }
                        source = content;
                        byteLength = data.length;
                        exitEdit();
                        Ui.toast(TextViewerActivity.this, "已保存");
                    }
                });
            }
        }).start();
    }

    /** 按原文件编码写回，保留 BOM */
    private byte[] encode(String s) {
        Charset cs;
        try {
            cs = Charset.forName(charset);
        } catch (Exception e) {
            cs = StandardCharsets.UTF_8;
        }
        byte[] body = s.getBytes(cs);
        if (!bom) {
            return body;
        }
        byte[] out = new byte[body.length + 3];
        out[0] = (byte) 0xEF;
        out[1] = (byte) 0xBB;
        out[2] = (byte) 0xBF;
        System.arraycopy(body, 0, out, 3, body.length);
        return out;
    }

    // ------------------------------------------------------------ 菜单

    private void showMore(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        if (!editing) {
            pm.getMenu().add(truncated ? "文件过大，无法编辑" : "编辑");
        } else {
            pm.getMenu().add("保存修改");
        }
        if (markdown) {
            pm.getMenu().add(rawView ? "渲染 Markdown" : "查看源码");
        }
        pm.getMenu().add("复制全部");
        pm.getMenu().add("保存到本机");
        pm.getMenu().add("用浏览器打开");
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                String t = item.getTitle().toString();
                if ("编辑".equals(t) || "保存修改".equals(t)) {
                    if (editing) {
                        saveEdit();
                    } else {
                        enterEdit();
                    }
                } else if ("文件过大，无法编辑".equals(t)) {
                    Ui.toastLong(TextViewerActivity.this, "文件超过 " + Ui.size(MAX_BYTES)
                            + "，暂不支持在线编辑");
                } else if ("查看源码".equals(t)) {
                    if (editing) {
                        return true;
                    }
                    rawView = !rawView;
                    paint();
                } else if ("渲染 Markdown".equals(t)) {
                    if (editing) {
                        return true;
                    }
                    rawView = !rawView;
                    paint();
                } else if ("复制全部".equals(t)) {
                    Ui.copy(TextViewerActivity.this, name,
                            editing ? etBody.getText().toString() : source);
                } else if ("保存到本机".equals(t)) {
                    save();
                } else if ("用浏览器打开".equals(t)) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                final String raw = api.rawUrl(Ui.join(dir, name));
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(raw)));
                                        } catch (Exception e) {
                                            Ui.toast(TextViewerActivity.this, "没有可用的浏览器");
                                        }
                                    }
                                });
                            } catch (final Api.ApiError e) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        Ui.toastLong(TextViewerActivity.this, e.getMessage());
                                    }
                                });
                            }
                        }
                    }).start();
                }
                return true;
            }
        });
        pm.show();
    }

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
                    final String where = Dl.save(TextViewerActivity.this, raw, name, new Dl.Progress() {
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
                            Ui.toastLong(TextViewerActivity.this, "已保存到 " + where);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            dismissProgress();
                            Ui.toastLong(TextViewerActivity.this, "保存失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    // ------------------------------------------------------------ 进度框

    private TextView progressLabel;

    private void showProgress(String title) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        box.setPadding(pad, Ui.dp(this, 6), pad, 0);
        progressLabel = new TextView(this);
        progressLabel.setText(title);
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

    /** 没有百分比可显示的场合（保存文本）用转圈框 */
    private void showBusy(String title) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        int pad = Ui.dp(this, 20);
        box.setPadding(pad, pad, pad, pad);
        ProgressBar spinner = new ProgressBar(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26));
        slp.rightMargin = Ui.dp(this, 14);
        box.addView(spinner, slp);
        progressLabel = new TextView(this);
        progressLabel.setText(title);
        progressLabel.setTextSize(13);
        progressLabel.setTextColor(Ui.color(this, R.attr.olText));
        box.addView(progressLabel);
        progressBar = null;
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
