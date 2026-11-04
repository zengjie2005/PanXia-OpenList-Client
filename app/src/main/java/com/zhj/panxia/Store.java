package com.zhj.panxia;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 本机设置存储。
 * <p>
 * 支持保存多台 OpenList 服务器：每台各自记住地址、账号、密码、token 和上次所在目录，
 * 「当前服务器」的概念由 {@link #active()} 提供。旧版本（单服务器）的键会在首次读取时
 * 自动迁移成一条服务器记录，不会丢登录态。
 * <p>
 * 密码用简单异或 + Base64 混淆后存进私有 SharedPreferences，不会离开设备。
 */
public class Store {

    private static final String FILE = "openlist_prefs";
    private static final String K_SERVERS = "servers";
    private static final String K_ACTIVE = "active";

    /** 一台已保存的服务器 */
    public static class Server {
        public String base = "";
        public String name = "";
        public String user = "";
        public String pass = "";
        public String token = "";
        public boolean insecure;
        public String lastPath = "/";

        /** 列表里显示的标题：用户起的名，没起就用 host:port */
        public String label() {
            if (name != null && !name.trim().isEmpty()) {
                return name.trim();
            }
            return Api.label(base);
        }

        public boolean loggedIn() {
            return token != null && !token.isEmpty();
        }
    }

    private final SharedPreferences p;

    public Store(Context c) {
        p = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- 服务器列表

    /** 全部服务器（按添加顺序）。首次调用会做老数据迁移 */
    public List<Server> servers() {
        List<Server> out = new ArrayList<>();
        String raw = p.getString(K_SERVERS, "");
        if (raw != null && !raw.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    Server s = fromJson(arr.optJSONObject(i));
                    if (s != null && !s.base.isEmpty()) {
                        out.add(s);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (out.isEmpty()) {
            Server legacy = legacyServer();
            if (legacy != null) {
                out.add(legacy);
                persist(out);
                // 迁移完就把老键删掉，否则全部服务器删光后会被重新「迁移」回来
                p.edit().putString(K_ACTIVE, legacy.base)
                        .remove("base").remove("user").remove("pass")
                        .remove("token").remove("insecure").remove("lastPath")
                        .apply();
            }
        }
        return out;
    }

    /** 当前使用的服务器；没有任何记录时返回 null */
    public Server active() {
        List<Server> all = servers();
        if (all.isEmpty()) {
            return null;
        }
        String key = p.getString(K_ACTIVE, "");
        if (key != null && !key.isEmpty()) {
            for (Server s : all) {
                if (s.base.equals(key)) {
                    return s;
                }
            }
        }
        return all.get(0);
    }

    public Server byBase(String base) {
        if (base == null || base.isEmpty()) {
            return null;
        }
        for (Server s : servers()) {
            if (s.base.equals(base)) {
                return s;
            }
        }
        return null;
    }

    /** 新增或按 base 覆盖一条记录 */
    public void upsert(Server s) {
        if (s == null || s.base == null || s.base.isEmpty()) {
            return;
        }
        List<Server> all = servers();
        boolean found = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).base.equals(s.base)) {
                all.set(i, s);
                found = true;
                break;
            }
        }
        if (!found) {
            all.add(s);
        }
        persist(all);
    }

    public void removeServer(String base) {
        List<Server> all = servers();
        boolean wasActive = base != null && base.equals(p.getString(K_ACTIVE, ""));
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).base.equals(base)) {
                all.remove(i);
            }
        }
        persist(all);
        if (wasActive) {
            p.edit().putString(K_ACTIVE, all.isEmpty() ? "" : all.get(0).base).apply();
        }
    }

    public void renameServer(String base, String name) {
        Server s = byBase(base);
        if (s != null) {
            s.name = name == null ? "" : name.trim();
            upsert(s);
        }
    }

    /** 切换当前服务器（不会动 token，切回去还是登录态） */
    public void activate(String base) {
        if (base == null || base.isEmpty()) {
            return;
        }
        p.edit().putString(K_ACTIVE, base).apply();
    }

    /** 登录成功后统一落盘：同地址覆盖，否则新增，并设为当前服务器 */
    public void saveLogin(String base, String user, String pass, String token,
                          boolean remember, boolean insecure) {
        String normalized = Api.normalize(base);
        if (normalized.isEmpty()) {
            return;
        }
        Server s = byBase(normalized);
        if (s == null) {
            s = new Server();
            s.base = normalized;
            s.name = Api.label(normalized);
        }
        s.user = user == null ? "" : user;
        if (remember) {
            s.pass = pass == null ? "" : pass;
        } else {
            s.pass = "";
        }
        s.token = token == null ? "" : token;
        s.insecure = insecure;
        if (s.lastPath == null || s.lastPath.isEmpty()) {
            s.lastPath = "/";
        }
        upsert(s);
        activate(s.base);
    }

    // ---------------------------------------------------------------- 当前服务器属性
    // 下面这些老接口都转发到「当前服务器」，上层的 Api / 各 Activity 不用改。

    public String base() {
        Server s = active();
        return s == null ? "" : s.base;
    }

    public void base(String v) {
        String normalized = Api.normalize(v);
        if (normalized.isEmpty()) {
            return;
        }
        Server s = active();
        if (s == null) {
            Server n = new Server();
            n.base = normalized;
            n.name = Api.label(normalized);
            upsert(n);
            activate(normalized);
            return;
        }
        if (!s.base.equals(normalized)) {
            // base 是记录的键，改地址等于换了一条记录
            removeServer(s.base);
            s.base = normalized;
            if (s.name == null || s.name.isEmpty()) {
                s.name = Api.label(normalized);
            }
            upsert(s);
            activate(normalized);
        }
    }

    public String user() {
        Server s = active();
        return s == null ? "" : s.user;
    }

    public void user(String v) {
        Server s = active();
        if (s != null) {
            s.user = v == null ? "" : v;
            upsert(s);
        }
    }

    public String pass() {
        Server s = active();
        return s == null ? "" : s.pass;
    }

    public void pass(String v) {
        Server s = active();
        if (s != null) {
            s.pass = v == null ? "" : v;
            upsert(s);
        }
    }

    public boolean hasPass() {
        return !pass().isEmpty();
    }

    public String token() {
        Server s = active();
        return s == null ? "" : s.token;
    }

    public void token(String v) {
        Server s = active();
        if (s != null) {
            s.token = v == null ? "" : v;
            upsert(s);
        }
    }

    public void clearToken() {
        Server s = active();
        if (s != null) {
            s.token = "";
            upsert(s);
        }
    }

    public String lastPath() {
        Server s = active();
        return s == null ? "/" : (s.lastPath == null || s.lastPath.isEmpty() ? "/" : s.lastPath);
    }

    public void lastPath(String v) {
        Server s = active();
        if (s != null) {
            s.lastPath = v;
            upsert(s);
        }
    }

    /** 该服务器是否忽略证书校验 */
    public boolean insecure() {
        Server s = active();
        return s != null && s.insecure;
    }

    public void insecure(boolean v) {
        Server s = active();
        if (s != null) {
            s.insecure = v;
            upsert(s);
        }
    }

    /** 退出登录：只清当前服务器的 token 与密码，服务器条目保留，方便再登 */
    public void logout() {
        Server s = active();
        if (s != null) {
            s.token = "";
            s.pass = "";
            upsert(s);
        } else {
            p.edit().remove("token").remove("pass").apply();
        }
    }

    // ---------------------------------------------------------------- 全局偏好

    public boolean remember() {
        return p.getBoolean("remember", true);
    }

    public void remember(boolean v) {
        p.edit().putBoolean("remember", v).apply();
    }

    /** 0=跟随系统 1=浅色 2=深色 */
    public int themeMode() {
        if (p.contains("themeMode")) {
            return p.getInt("themeMode", Theme.FOLLOW);
        }
        // 老版本只有 dark 布尔值，迁移一次
        if (p.contains("dark")) {
            return p.getBoolean("dark", true) ? Theme.DARK : Theme.LIGHT;
        }
        return Theme.FOLLOW;
    }

    public void themeMode(int v) {
        p.edit().putInt("themeMode", v).apply();
    }

    public boolean dark() {
        return themeMode() == Theme.DARK;
    }

    public boolean showHidden() {
        return p.getBoolean("showHidden", false);
    }

    public void showHidden(boolean v) {
        p.edit().putBoolean("showHidden", v).apply();
    }

    /** 按键触感反馈（震动） */
    public boolean haptic() {
        return p.getBoolean("haptic", true);
    }

    public void haptic(boolean v) {
        p.edit().putBoolean("haptic", v).apply();
        Feedback.enabled = v;
    }

    public String sort() {
        String v = p.getString("sort", "name");
        return v == null ? "name" : v;
    }

    public void sort(String v) {
        p.edit().putString("sort", v).apply();
    }

    public boolean desc() {
        return p.getBoolean("desc", false);
    }

    public void desc(boolean v) {
        p.edit().putBoolean("desc", v).apply();
    }

    // ---------------------------------------------------------------- 内部

    private void persist(List<Server> list) {
        JSONArray arr = new JSONArray();
        for (Server s : list) {
            arr.put(toJson(s));
        }
        p.edit().putString(K_SERVERS, arr.toString()).apply();
    }

    private static JSONObject toJson(Server s) {
        JSONObject o = new JSONObject();
        try {
            o.put("base", s.base);
            o.put("name", s.name);
            o.put("user", s.user);
            o.put("pass", encode(s.pass));
            o.put("token", s.token);
            o.put("insecure", s.insecure);
            o.put("lastPath", s.lastPath == null ? "/" : s.lastPath);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static Server fromJson(JSONObject o) {
        if (o == null) {
            return null;
        }
        Server s = new Server();
        s.base = Api.normalize(o.optString("base", ""));
        s.name = o.optString("name", "");
        s.user = o.optString("user", "");
        s.pass = decode(o.optString("pass", ""));
        s.token = o.optString("token", "");
        s.insecure = o.optBoolean("insecure", false);
        s.lastPath = o.optString("lastPath", "/");
        return s;
    }

    /** 1.4 及以前的单服务器键值，迁移成第一条记录 */
    private Server legacyServer() {
        String base = p.getString("base", "");
        if (base == null || base.isEmpty()) {
            return null;
        }
        Server s = new Server();
        s.base = Api.normalize(base);
        s.name = Api.label(s.base);
        String u = p.getString("user", "");
        s.user = u == null ? "" : u;
        s.pass = decode(p.getString("pass", ""));
        String t = p.getString("token", "");
        s.token = t == null ? "" : t;
        s.insecure = p.getBoolean("insecure", false);
        String lp = p.getString("lastPath", "/");
        s.lastPath = lp == null || lp.isEmpty() ? "/" : lp;
        return s;
    }

    private static String encode(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) (b[i] ^ (0x5A + (i % 7)));
        }
        return Base64.encodeToString(b, Base64.NO_WRAP);
    }

    private static String decode(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        try {
            byte[] b = Base64.decode(s, Base64.NO_WRAP);
            for (int i = 0; i < b.length; i++) {
                b[i] = (byte) (b[i] ^ (0x5A + (i % 7)));
            }
            return new String(b, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
