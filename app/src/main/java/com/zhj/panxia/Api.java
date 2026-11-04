package com.zhj.panxia;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * OpenList v4 HTTP API 客户端（零依赖，用 HttpURLConnection + org.json）。
 * <p>
 * 只做同步调用，调用方放到后台线程执行。
 */
public class Api {

    /** 业务异常：code 为 OpenList 返回的 code，-1 表示网络层错误，-2 表示用户取消 */
    public static class ApiError extends Exception {
        public final int code;

        public ApiError(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    public interface Progress {
        void onProgress(long done, long total);
    }

    public interface Cancel {
        boolean cancelled();
    }

    /** 文件 / 目录条目 */
    public static class Entry {
        public String name = "";
        public boolean dir;
        public long size;
        public String modified = "";
        public String thumb = "";
    }

    private static final HostnameVerifier DEFAULT_VERIFIER = HttpsURLConnection.getDefaultHostnameVerifier();
    private static final SSLSocketFactory DEFAULT_FACTORY = HttpsURLConnection.getDefaultSSLSocketFactory();

    private String baseUrl;
    private String token;

    /** 连接「当前服务器」 */
    public Api(Store store) {
        this.baseUrl = normalize(store.base());
        this.token = store.token();
    }

    /** 独立连接：登录 / 测试连接时用，不读写本地配置 */
    public Api(String base) {
        this.baseUrl = normalize(base);
        this.token = "";
    }

    public String token() {
        return token;
    }

    // ---------------------------------------------------------------- 工具

    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String v = raw.trim();
        if (v.isEmpty()) {
            return "";
        }
        String lower = v.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            v = "http://" + v;
        }
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    public static String hostOf(String base) {
        try {
            return new URL(base).getHost();
        } catch (Exception e) {
            return base;
        }
    }

    /** host:port（端口为默认值时不带端口），用作服务器默认显示名 */
    public static String label(String base) {
        try {
            URL u = new URL(base);
            String host = u.getHost();
            int port = u.getPort();
            return port > 0 ? host + ":" + port : host;
        } catch (Exception e) {
            return base == null ? "" : base;
        }
    }

    /** 允许自签名证书（用户显式开启时才调用） */
    public static void applyInsecure(boolean on) {
        try {
            if (on) {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[]{new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }}, new SecureRandom());
                HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
                HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
                    @Override
                    public boolean verify(String hostname, SSLSession session) {
                        return true;
                    }
                });
            } else {
                HttpsURLConnection.setDefaultSSLSocketFactory(DEFAULT_FACTORY);
                HttpsURLConnection.setDefaultHostnameVerifier(DEFAULT_VERIFIER);
            }
        } catch (Exception ignored) {
        }
    }

    private String base() {
        if (baseUrl == null || baseUrl.isEmpty()) {
            throw new IllegalStateException("no base");
        }
        return baseUrl;
    }

    private HttpURLConnection open(String path, String method, boolean json) throws IOException {
        URL url = new URL(base() + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(180000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept", json ? "application/json" : "*/*");
        c.setRequestProperty("User-Agent", "OpenListMobile/1.0 (Android)");
        if (token != null && !token.isEmpty()) {
            c.setRequestProperty("Authorization", token);
        }
        return c;
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        char[] buf = new char[4096];
        int n;
        while ((n = br.read(buf)) != -1) {
            sb.append(buf, 0, n);
        }
        br.close();
        return sb.toString();
    }

    private JSONObject parse(int httpCode, String text) throws ApiError {
        String body = text == null ? "" : text.trim();
        if (body.isEmpty()) {
            if (httpCode >= 200 && httpCode < 300) {
                return new JSONObject();
            }
            if (httpCode == 401) {
                throw new ApiError(401, "登录已失效，请重新登录");
            }
            throw new ApiError(httpCode, "服务器无响应内容（HTTP " + httpCode + "）");
        }
        JSONObject o;
        try {
            o = new JSONObject(body);
        } catch (JSONException e) {
            if (httpCode == 401) {
                throw new ApiError(401, "登录已失效，请重新登录");
            }
            throw new ApiError(httpCode, "返回内容不是合法 JSON（HTTP " + httpCode + "），请确认地址指向 OpenList");
        }
        int code = o.optInt("code", httpCode);
        if (code != 200) {
            String msg = o.optString("message", "");
            if (code == 401) {
                msg = "登录已失效，请重新登录";
            } else if (msg.isEmpty()) {
                msg = "错误码 " + code;
            }
            throw new ApiError(code, msg);
        }
        return o;
    }

    private JSONObject call(String path, String method, String body) throws ApiError {
        HttpURLConnection c = null;
        try {
            c = open(path, method, true);
            if (body != null) {
                byte[] data = body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setFixedLengthStreamingMode(data.length);
                OutputStream o = c.getOutputStream();
                o.write(data);
                o.flush();
                o.close();
            }
            int sc = c.getResponseCode();
            InputStream in = sc >= 400 ? c.getErrorStream() : c.getInputStream();
            return parse(sc, read(in));
        } catch (IOException e) {
            throw new ApiError(-1, describe(e));
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String describe(IOException e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String lower = m.toLowerCase(Locale.ROOT);
        if (lower.contains("cleartext")) {
            return "系统禁止明文 HTTP，请改用 https";
        }
        if (lower.contains("econnrefused") || lower.contains("connection refused")) {
            return "连接被拒绝，请检查地址和端口";
        }
        if (lower.contains("timed out") || lower.contains("timeout")) {
            return "连接超时，请检查网络或地址";
        }
        if (lower.contains("unable to resolve host") || lower.contains("no address associated")) {
            return "域名解析失败，请检查地址";
        }
        if (lower.contains("certificate") || lower.contains("trust anchor") || lower.contains("ssl")) {
            return "HTTPS 证书校验失败，可在设置里开启「忽略证书错误」";
        }
        if (lower.contains("network is unreachable")) {
            return "网络不可达，请检查手机网络";
        }
        return "网络错误：" + m;
    }

    private static JSONObject data(JSONObject resp) {
        JSONObject d = resp.optJSONObject("data");
        return d == null ? new JSONObject() : d;
    }

    private static JSONObject body(String... kv) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            try {
                o.put(kv[i], kv[i + 1]);
            } catch (JSONException ignored) {
            }
        }
        return o;
    }

    private static JSONArray toArray(List<String> values) {
        JSONArray a = new JSONArray();
        for (String v : values) {
            a.put(v);
        }
        return a;
    }

    private static Entry toEntry(JSONObject it) {
        Entry e = new Entry();
        e.name = it.optString("name", "");
        e.dir = it.optBoolean("is_dir", false);
        e.size = it.optLong("size", 0);
        e.modified = it.optString("modified", "");
        e.thumb = it.optString("thumb", "");
        return e;
    }

    // ---------------------------------------------------------------- 账号

    /** 探测服务可用性，返回版本号；不需要登录 */
    public String ping() throws ApiError {
        JSONObject resp = call("/api/public/settings", "GET", null);
        JSONObject d = data(resp);
        String v = d.optString("version", "");
        if (v.isEmpty()) {
            v = d.optString("site_title", "");
        }
        return v;
    }

    /** 登录并返回 token（不落盘，由调用方保存到对应服务器） */
    public JSONObject login(String server, String user, String pass) throws ApiError {
        String normalized = normalize(server);
        if (normalized.isEmpty()) {
            throw new ApiError(-1, "请填写服务器地址");
        }
        baseUrl = normalized;
        token = null;
        JSONObject payload = new JSONObject();
        try {
            payload.put("username", user);
            payload.put("password", pass);
            payload.put("otp_code", "");
        } catch (JSONException ignored) {
        }
        JSONObject resp = call("/api/auth/login", "POST", payload.toString());
        JSONObject d = data(resp);
        String t = d.optString("token", "");
        if (t.isEmpty()) {
            throw new ApiError(-1, "登录成功但没有返回 token");
        }
        token = t;
        return d;
    }

    public JSONObject me() throws ApiError {
        return data(call("/api/me", "GET", null));
    }

    // ---------------------------------------------------------------- 文件

    public List<Entry> list(String path) throws ApiError {
        JSONObject payload = new JSONObject();
        try {
            payload.put("path", path);
            payload.put("password", "");
            payload.put("page", 1);
            payload.put("per_page", 0);
            payload.put("refresh", false);
        } catch (JSONException ignored) {
        }
        JSONObject d = data(call("/api/fs/list", "POST", payload.toString()));
        JSONArray arr = d.optJSONArray("content");
        List<Entry> out = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.optJSONObject(i);
                if (it == null) {
                    continue;
                }
                Entry e = toEntry(it);
                if (!e.name.isEmpty()) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    public JSONObject get(String path) throws ApiError {
        return data(call("/api/fs/get", "POST", body("path", path, "password", "").toString()));
    }

    public String rawUrl(String path) throws ApiError {
        return get(path).optString("raw_url", "");
    }

    public void mkdir(String path) throws ApiError {
        call("/api/fs/mkdir", "POST", body("path", path).toString());
    }

    public void remove(String dir, List<String> names) throws ApiError {
        JSONObject payload = new JSONObject();
        try {
            payload.put("dir", dir);
            payload.put("names", toArray(names));
        } catch (JSONException ignored) {
        }
        call("/api/fs/remove", "POST", payload.toString());
    }

    public void rename(String path, String newName) throws ApiError {
        call("/api/fs/rename", "POST", body("path", path, "name", newName).toString());
    }

    public void move(String srcDir, String dstDir, List<String> names) throws ApiError {
        JSONObject payload = new JSONObject();
        try {
            payload.put("src_dir", srcDir);
            payload.put("dst_dir", dstDir);
            payload.put("names", toArray(names));
        } catch (JSONException ignored) {
        }
        call("/api/fs/move", "POST", payload.toString());
    }

    public void copy(String srcDir, String dstDir, List<String> names) throws ApiError {
        JSONObject payload = new JSONObject();
        try {
            payload.put("src_dir", srcDir);
            payload.put("dst_dir", dstDir);
            payload.put("names", toArray(names));
        } catch (JSONException ignored) {
        }
        call("/api/fs/copy", "POST", payload.toString());
    }

    /** 只列子目录，用于选择移动 / 复制目标 */
    public List<Entry> dirs(String path) throws ApiError {
        JSONObject resp = call("/api/fs/dirs", "POST", body("path", path, "password", "").toString());
        JSONArray arr = resp.optJSONArray("data");
        if (arr == null) {
            JSONObject d = resp.optJSONObject("data");
            arr = d == null ? null : d.optJSONArray("content");
        }
        List<Entry> out = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.optJSONObject(i);
                if (it == null) {
                    continue;
                }
                Entry e = toEntry(it);
                e.dir = true;
                if (!e.name.isEmpty()) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 上传

    /**
     * 上传单个文件。OpenList 用 PUT /api/fs/put，路径放在 File-Path 头里（需 URL 编码）。
     */
    public void upload(String remotePath, InputStream in, long length, Progress progress, Cancel cancel)
            throws ApiError {
        HttpURLConnection c = null;
        try {
            c = open("/api/fs/put", "PUT", false);
            c.setDoOutput(true);
            c.setRequestProperty("File-Path", Uri.encode(remotePath));
            c.setRequestProperty("Content-Type", "application/octet-stream");
            c.setRequestProperty("As-Task", "false");
            c.setFixedLengthStreamingMode(length);

            OutputStream out = c.getOutputStream();
            byte[] buf = new byte[64 * 1024];
            long sent = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                if (cancel != null && cancel.cancelled()) {
                    try {
                        c.disconnect();
                    } catch (Exception ignored) {
                    }
                    throw new ApiError(-2, "已取消上传");
                }
                out.write(buf, 0, n);
                sent += n;
                if (progress != null) {
                    progress.onProgress(sent, length);
                }
            }
            out.flush();
            out.close();

            int sc = c.getResponseCode();
            InputStream ris = sc >= 400 ? c.getErrorStream() : c.getInputStream();
            parse(sc, read(ris));
        } catch (ApiError e) {
            throw e;
        } catch (IOException e) {
            if (cancel != null && cancel.cancelled()) {
                throw new ApiError(-2, "已取消上传");
            }
            throw new ApiError(-1, describe(e));
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
