package com.zhj.panxia;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 登录页：服务器地址 + 用户名 + 密码，支持 IP / 域名 + 端口。
 * <p>
 * 支持保存多台服务器：顶部会列出已保存的服务器，点一下即可切换，选中的服务器登录成功后
 * 会写回对应的那条记录；在新地址上登录则自动新增一条（同名地址覆盖）。
 */
public class LoginActivity extends Activity {

    /** true = 从「添加服务器」进来：不自动登录，字段留空 */
    public static final String EXTRA_NEW = "new";
    /** 预填某个服务器地址（切换服务器但没登录态时用） */
    public static final String EXTRA_SERVER = "server";

    private Store store;
    private EditText etServer;
    private EditText etUser;
    private EditText etPass;
    private Switch swRemember;
    private Switch swInsecure;
    private TextView tvMsg;
    private Button btnLogin;
    private Button btnTest;
    private View boxPick;
    private TextView tvPick;

    /** 当前表单对应哪台已保存的服务器（空表示新地址） */
    private String editingBase = "";
    private boolean addMode;

    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        store = new Store(this);
        Theme.apply(this, store);
        super.onCreate(savedInstanceState);

        addMode = getIntent().getBooleanExtra(EXTRA_NEW, false);
        if (!addMode && !store.token().isEmpty() && !store.base().isEmpty()) {
            gotoMain();
            return;
        }

        setContentView(R.layout.activity_login);
        Theme.install(this, 0);
        etServer = findViewById(R.id.etServer);
        etUser = findViewById(R.id.etUser);
        etPass = findViewById(R.id.etPass);
        swRemember = findViewById(R.id.swRemember);
        swInsecure = findViewById(R.id.swInsecure);
        tvMsg = findViewById(R.id.tvMsg);
        btnLogin = findViewById(R.id.btnLogin);
        btnTest = findViewById(R.id.btnTest);
        boxPick = findViewById(R.id.boxPick);
        tvPick = findViewById(R.id.tvPick);
        Feedback.press(btnLogin);
        Feedback.press(btnTest);
        Feedback.press(boxPick);

        boxPick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickServer();
            }
        });

        swRemember.setChecked(store.remember());

        String preset = getIntent().getStringExtra(EXTRA_SERVER);
        if (addMode) {
            fillFrom(null);
        } else if (preset != null && !preset.isEmpty()) {
            fillFrom(store.byBase(Api.normalize(preset)));
        } else if (!store.base().isEmpty() || !store.user().isEmpty()) {
            fillFrom(store.active());
        } else {
            fillFrom(null);
        }

        btnLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doLogin();
            }
        });
        btnTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doTest();
            }
        });
        etPass.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    doLogin();
                    return true;
                }
                return false;
            }
        });
        if (etServer.getText().length() == 0) {
            etServer.requestFocus();
        }
        refreshPick();
    }

    // ------------------------------------------------------------ 服务器选择

    /** 把某台已保存服务器（null 表示新地址）填进表单 */
    private void fillFrom(Store.Server s) {
        editingBase = s == null ? "" : s.base;
        etServer.setText(s == null ? "" : s.base);
        etUser.setText(s == null ? "" : s.user);
        etPass.setText(s == null ? "" : s.pass);
        swInsecure.setChecked(s != null && s.insecure);
        refreshPick();
    }

    private void refreshPick() {
        List<Store.Server> all = store.servers();
        if (all.isEmpty()) {
            boxPick.setVisibility(View.GONE);
        } else {
            boxPick.setVisibility(View.VISIBLE);
            Store.Server cur = store.byBase(editingBase);
            if (cur != null) {
                tvPick.setText(cur.label() + " · " + Api.label(cur.base));
            } else {
                tvPick.setText("选择已保存的服务器 · 共 " + all.size() + " 台");
            }
        }
        // 选择器出现时收紧一点上方间距，省一屏高度
        View label = findViewById(R.id.lblServer);
        if (label != null && label.getLayoutParams() instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) label.getLayoutParams();
            lp.topMargin = Ui.dp(this, boxPick.getVisibility() == View.VISIBLE ? 14 : 34);
            label.setLayoutParams(lp);
        }
    }

    private void pickServer() {
        final List<Store.Server> all = store.servers();
        final List<String> items = new ArrayList<>();
        for (Store.Server s : all) {
            items.add(s.label() + "\n" + s.base + (s.base.equals(editingBase) ? "  ✓" : ""));
        }
        items.add("＋ 添加新服务器");
        new AlertDialog.Builder(this)
                .setTitle("服务器")
                .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == all.size()) {
                            fillFrom(null);
                        } else {
                            fillFrom(all.get(which));
                        }
                    }
                })
                .show();
    }

    // ------------------------------------------------------------ 登录 / 测试

    private void gotoMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private void showMsg(String text, boolean error) {
        if (text == null || text.isEmpty()) {
            tvMsg.setVisibility(View.GONE);
            return;
        }
        tvMsg.setVisibility(View.VISIBLE);
        tvMsg.setText(text);
        tvMsg.setTextColor(Ui.color(this, error ? R.attr.olDanger : R.attr.olDim));
    }

    private void setBusy(boolean on) {
        busy = on;
        btnLogin.setEnabled(!on);
        btnTest.setEnabled(!on);
        btnLogin.setText(on ? "连接中…" : getString(R.string.login));
    }

    private void doLogin() {
        if (busy) {
            return;
        }
        final String server = Api.normalize(etServer.getText().toString());
        final String user = etUser.getText().toString().trim();
        final String pass = etPass.getText().toString();
        if (server.isEmpty()) {
            showMsg("请填写服务器地址，例如 192.168.1.10:5244", true);
            etServer.requestFocus();
            return;
        }
        if (user.isEmpty()) {
            showMsg("请填写用户名", true);
            etUser.requestFocus();
            return;
        }
        if (pass.isEmpty()) {
            showMsg("请填写密码", true);
            etPass.requestFocus();
            return;
        }

        setBusy(true);
        showMsg("正在连接 " + server + " …", false);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final boolean insecure = swInsecure.isChecked();
                    Api.applyInsecure(insecure);
                    final Api api = new Api(server);
                    api.login(server, user, pass);
                    // 登录成功才落盘：同地址覆盖、新地址新增，并设为当前服务器
                    store.saveLogin(server, user, pass, api.token(),
                            swRemember.isChecked(), insecure);
                    store.remember(swRemember.isChecked());
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(false);
                            if (addMode) {
                                Ui.toast(LoginActivity.this, "已添加 " + Api.label(server));
                                setResult(RESULT_OK);
                                finish();
                            } else {
                                gotoMain();
                            }
                        }
                    });
                } catch (final Api.ApiError e) {
                    failed(e.getMessage());
                } catch (final Exception e) {
                    failed(e.getMessage());
                }
            }

            private void failed(final String msg) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setBusy(false);
                        showMsg("登录失败：" + msg, true);
                    }
                });
            }
        }).start();
    }

    private void doTest() {
        if (busy) {
            return;
        }
        final String server = Api.normalize(etServer.getText().toString());
        if (server.isEmpty()) {
            showMsg("先填服务器地址", true);
            return;
        }
        setBusy(true);
        showMsg("正在测试 " + server + " …", false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Api.applyInsecure(swInsecure.isChecked());
                    // 只测连通性，不落盘、不动已保存的服务器
                    final String version = new Api(server).ping();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(false);
                            showMsg("连接正常" + (version.isEmpty() ? "" : " · " + version), false);
                        }
                    });
                } catch (final Api.ApiError e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(false);
                            showMsg("连接失败：" + e.getMessage(), true);
                        }
                    });
                }
            }
        }).start();
    }
}
