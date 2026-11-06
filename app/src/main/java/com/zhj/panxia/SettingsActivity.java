package com.zhj.panxia;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

/** 设置页：服务器 / 账号 / 主题 / 浏览偏好。 */
public class SettingsActivity extends Activity {

    private Store store;

    private EditText etUser;
    private EditText etPass;
    private Switch swInsecure;
    private Switch swHidden;
    private Switch swDesc;
    private Switch swHaptic;
    private TextView chipName;
    private TextView chipSize;
    private TextView chipTime;
    private TextView chipThemeFollow;
    private TextView chipThemeLight;
    private TextView chipThemeDark;
    private Button btnSave;
    private Button btnLogout;
    private TextView tvServerName;
    private TextView tvServerBase;
    private TextView tvAbout;
    private boolean serverChanged;

    private static final int REQ_SERVERS = 210;

    private String sort = "name";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        store = new Store(this);
        Theme.apply(this, store);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        Theme.install(this, R.id.topBar);

        etUser = findViewById(R.id.etUser);
        etPass = findViewById(R.id.etPass);
        swInsecure = findViewById(R.id.swInsecure);
        swHidden = findViewById(R.id.swHidden);
        swDesc = findViewById(R.id.swDesc);
        swHaptic = findViewById(R.id.swHaptic);
        chipName = findViewById(R.id.chipName);
        chipSize = findViewById(R.id.chipSize);
        chipTime = findViewById(R.id.chipTime);
        chipThemeFollow = findViewById(R.id.chipThemeFollow);
        chipThemeLight = findViewById(R.id.chipThemeLight);
        chipThemeDark = findViewById(R.id.chipThemeDark);
        btnSave = findViewById(R.id.btnSave);
        btnLogout = findViewById(R.id.btnLogout);
        tvServerName = findViewById(R.id.tvServerName);
        tvServerBase = findViewById(R.id.tvServerBase);
        tvAbout = findViewById(R.id.tvAbout);

        etUser.setText(store.user());
        swInsecure.setChecked(store.insecure());
        swHidden.setChecked(store.showHidden());
        swDesc.setChecked(store.desc());
        swHaptic.setChecked(store.haptic());
        sort = store.sort();

        refreshServer();

        chipThemeFollow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setThemeMode(Theme.FOLLOW);
            }
        });
        chipThemeLight.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setThemeMode(Theme.LIGHT);
            }
        });
        chipThemeDark.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setThemeMode(Theme.DARK);
            }
        });

        chipName.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sort = "name";
                paintChips();
            }
        });
        chipSize.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sort = "size";
                paintChips();
            }
        });
        chipTime.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sort = "time";
                paintChips();
            }
        });
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        btnLogout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Ui.confirm(SettingsActivity.this, "退出登录", "会清除本机保存的登录信息，确定退出？", "退出",
                        new Runnable() {
                            @Override
                            public void run() {
                                store.logout();
                                goLogin();
                            }
                        });
            }
        });
        findViewById(R.id.btnServers).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(SettingsActivity.this, ServersActivity.class),
                        REQ_SERVERS);
            }
        });
        int[] pressIds = {R.id.btnBack, R.id.btnSave, R.id.btnLogout, R.id.btnServers,
                R.id.chipThemeFollow, R.id.chipThemeLight, R.id.chipThemeDark,
                R.id.chipName, R.id.chipSize, R.id.chipTime};
        for (int id : pressIds) {
            Feedback.press(findViewById(id));
        }
        paintChips();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshServer();
    }

    @Override
    public void finish() {
        if (serverChanged) {
            setResult(RESULT_OK);
        }
        super.finish();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SERVERS && resultCode == RESULT_OK) {
            // 服务器换了：账号信息和「忽略证书」跟着走，密码框留空（沿用已保存的）
            serverChanged = true;
            etUser.setText(store.user());
            etPass.setText("");
            swInsecure.setChecked(store.insecure());
            refreshServer();
        }
    }

    /** 顶部「当前服务器」信息 + 关于文案 */
    private void refreshServer() {
        Store.Server s = store.active();
        if (s == null) {
            tvServerName.setText("未选择");
            tvServerBase.setText("点右侧添加或切换服务器");
        } else {
            tvServerName.setText(s.label());
            tvServerBase.setText(s.base + (s.user.isEmpty() ? "" : " · " + s.user));
        }
        String ver = appVersion();
        tvAbout.setText("盘匣" + (ver.isEmpty() ? "" : " " + ver)
                + "\n作者 zhj · 兼容 OpenList v4 API\n"
                + (s == null ? "" : "当前 " + Api.label(s.base) + "\n")
                + "凭据仅保存在本机，不上传任何服务器");
    }

    /** 版本号取自 PackageInfo，以后改 build.gradle 时关于页自动跟着变 */
    private String appVersion() {
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return v == null ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    private void setThemeMode(int mode) {
        if (store.themeMode() == mode) {
            return;
        }
        store.themeMode(mode);
        // 立刻预览：重建当前页
        recreate();
    }

    private void paintChips() {
        chipName.setBackgroundResource("name".equals(sort) ? R.drawable.bg_chip_on : R.drawable.bg_chip);
        chipSize.setBackgroundResource("size".equals(sort) ? R.drawable.bg_chip_on : R.drawable.bg_chip);
        chipTime.setBackgroundResource("time".equals(sort) ? R.drawable.bg_chip_on : R.drawable.bg_chip);
        int on = Ui.color(this, R.attr.olAccent);
        int off = Ui.color(this, R.attr.olText);
        chipName.setTextColor("name".equals(sort) ? on : off);
        chipSize.setTextColor("size".equals(sort) ? on : off);
        chipTime.setTextColor("time".equals(sort) ? on : off);

        int mode = store.themeMode();
        paintThemeChip(chipThemeFollow, mode == Theme.FOLLOW, on, off);
        paintThemeChip(chipThemeLight, mode == Theme.LIGHT, on, off);
        paintThemeChip(chipThemeDark, mode == Theme.DARK, on, off);
    }

    private void paintThemeChip(TextView chip, boolean on, int onColor, int offColor) {
        chip.setBackgroundResource(on ? R.drawable.bg_chip_on : R.drawable.bg_chip);
        chip.setTextColor(on ? onColor : offColor);
    }

    private void save() {
        final String server = store.base();
        final String user = etUser.getText().toString().trim();
        final String pass = etPass.getText().toString();
        if (server.isEmpty()) {
            Ui.toast(this, "还没有选择服务器");
            return;
        }
        if (user.isEmpty()) {
            Ui.toast(this, "用户名不能为空");
            return;
        }

        // 地址由服务器列表管理，这里只改账号密码与开关
        final boolean relogin = serverChanged
                || !user.equals(store.user())
                || !pass.isEmpty()
                || !store.hasPass();

        store.user(user);
        if (!pass.isEmpty()) {
            store.pass(pass);
        }
        store.insecure(swInsecure.isChecked());
        store.showHidden(swHidden.isChecked());
        store.haptic(swHaptic.isChecked());
        store.sort(sort);
        store.desc(swDesc.isChecked());
        Api.applyInsecure(swInsecure.isChecked());

        if (!relogin) {
            Ui.toast(this, "已保存");
            setResult(RESULT_OK);
            finish();
            return;
        }

        store.clearToken();
        btnSave.setEnabled(false);
        btnSave.setText("连接中…");
        final String savedPass = store.pass();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Api api = new Api(store);
                    api.login(server, user, savedPass);
                    store.token(api.token());
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Ui.toast(SettingsActivity.this, "已保存并重新登录");
                            setResult(RESULT_OK);
                            finish();
                        }
                    });
                } catch (final Api.ApiError e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            btnSave.setEnabled(true);
                            btnSave.setText("保存并重新连接");
                            Ui.toastLong(SettingsActivity.this, "已保存设置，但登录失败：" + e.getMessage());
                            goLogin();
                        }
                    });
                }
            }
        }).start();
    }

    private void goLogin() {
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }
}
