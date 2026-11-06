package com.zhj.panxia;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

/**
 * 歌词：优先同目录同名 .lrc，其次在线歌词库（LRCLIB，免费无需密钥），
 * 拿到后写到本地缓存，下次离线也能显示。
 */
public final class Lyrics {

    private static final String TAG = "Lyrics";
    private static final String API_GET = "https://lrclib.net/api/get";
    private static final String API_SEARCH = "https://lrclib.net/api/search";
    private static final String UA = "PanXia/1.1 (Android)";

    public static class Result {
        public final Lrc lrc;
        public final String source;

        public Result(Lrc lrc, String source) {
            this.lrc = lrc;
            this.source = source;
        }
    }

    private Lyrics() {
    }

    /**
     * @param entries 当前目录的文件名列表（用来找同名 .lrc），可为 null
     * @param size    音频体积，参与缓存 key，文件换了缓存自动失效
     */
    public static Result fetch(Context c, Api api, String dir, String name,
                               List<String> entries, long size, int durationMs) {
        // 1) 同目录同名 .lrc（优先于缓存，用户后来补的歌词能立刻生效）
        String base = baseName(name);
        if (entries != null) {
            for (String e : entries) {
                if (e.toLowerCase(Locale.ROOT).equals((base + ".lrc").toLowerCase(Locale.ROOT))) {
                    try {
                        String raw = Net.text(api.rawUrl(Ui.join(dir, e)), 2 * 1024 * 1024);
                        Lrc lrc = Lrc.parse(raw);
                        if (!lrc.lines.isEmpty()) {
                            writeCache(c, dir, name, size, raw);
                            return new Result(lrc, "本地歌词");
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        // 2) 本地缓存
        String cached = readCache(c, dir, name, size);
        if (cached != null) {
            if ("#none".equals(cached)) {
                return null;
            }
            Lrc lr = Lrc.parse(cached);
            if (!lr.lines.isEmpty()) {
                return new Result(lr, "缓存");
            }
        }

        // 3) 在线歌词
        String[] parsed = parseName(name);
        String artist = parsed[0];
        String track = parsed[1];
        String raw = null;
        String source = null;
        try {
            raw = queryGet(artist, track, durationMs, name);
            if (raw != null) {
                source = "LRCLIB";
            }
            if (raw == null) {
                raw = querySearch(artist, track);
                if (raw != null) {
                    source = "LRCLIB 搜索";
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "在线歌词失败: " + e.getMessage());
        }
        if (raw == null) {
            writeCache(c, dir, name, size, "#none");
            return null;
        }
        Lrc lrc = Lrc.parse(raw);
        if (lrc.lines.isEmpty()) {
            writeCache(c, dir, name, size, "#none");
            return null;
        }
        writeCache(c, dir, name, size, raw);
        return new Result(lrc, source);
    }

    // ------------------------------------------------------------ 在线查询

    private static String queryGet(String artist, String track, int durationMs, String name)
            throws Exception {
        if (track.isEmpty()) {
            return null;
        }
        StringBuilder url = new StringBuilder(API_GET)
                .append("?track_name=").append(enc(track))
                .append("&artist_name=").append(enc(artist.isEmpty() ? "-" : artist));
        if (durationMs > 0) {
            url.append("&duration=").append(durationMs / 1000);
        }
        String body = get(url.toString());
        if (body == null || body.trim().isEmpty()) {
            return null;
        }
        JSONObject o = new JSONObject(body);
        String synced = o.optString("syncedLyrics", "");
        if (synced != null && !synced.trim().isEmpty()) {
            return synced;
        }
        String plain = o.optString("plainLyrics", "");
        if (plain != null && !plain.trim().isEmpty()) {
            return toLrc(plain);
        }
        return null;
    }

    private static String querySearch(String artist, String track) throws Exception {
        if (track.isEmpty()) {
            return null;
        }
        String q = artist.isEmpty() ? track : artist + " " + track;
        String body = get(API_SEARCH + "?q=" + enc(q));
        if (body == null || body.trim().isEmpty()) {
            return null;
        }
        JSONArray arr = new JSONArray(body);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String synced = o.optString("syncedLyrics", "");
            if (synced != null && !synced.trim().isEmpty()) {
                return synced;
            }
        }
        return null;
    }

    private static String get(String url) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            if (code == 404) {
                return null;
            }
            if (code >= 400) {
                return null;
            }
            java.io.InputStream in = c.getInputStream();
            try {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    bos.write(buf, 0, n);
                    if (bos.size() > 512 * 1024) {
                        break;
                    }
                }
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                Net.close(in);
            }
        } finally {
            c.disconnect();
        }
    }

    /** 纯文本歌词按行均分时间轴（只在没有时间标签时兜底） */
    private static String toLrc(String plain) {
        String[] rows = plain.split("\\r?\\n");
        StringBuilder sb = new StringBuilder();
        long t = 0;
        for (String row : rows) {
            String s = row.trim();
            if (s.isEmpty()) {
                continue;
            }
            sb.append('[').append(fmt(t)).append(']').append(s).append('\n');
            t += 4000;
        }
        return sb.toString();
    }

    private static String fmt(long ms) {
        long min = ms / 60000;
        long sec = (ms % 60000) / 1000;
        long cs = (ms % 1000) / 10;
        return String.format(Locale.US, "%02d:%02d.%02d", min, sec, cs);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // ------------------------------------------------------------ 文件名解析

    /** 返回 {歌手, 曲名}，尽量从「歌手 - 曲名」「01. 曲名」这类命名里拆出来 */
    static String[] parseName(String file) {
        String base = baseName(file);
        base = base.replace('_', ' ').trim();
        // 去掉常见的音质 / 来源标记
        base = base.replaceAll("\\[[^\\]]*\\]", " ")
                .replaceAll("\\((?i)(320k|flac|hq|官方|完整版|remix)\\)", " ")
                .replaceAll("\\s+", " ").trim();
        // 01. 曲名 / 01 - 曲名
        base = base.replaceFirst("^\\d{1,3}[.\\-、]\\s*", "");
        String artist = "";
        String track = base;
        int idx = base.indexOf(" - ");
        if (idx > 0) {
            artist = base.substring(0, idx).trim();
            track = base.substring(idx + 3).trim();
        }
        if (track.isEmpty()) {
            track = base;
        }
        return new String[]{artist, track};
    }

    static String baseName(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    // ------------------------------------------------------------ 缓存

    private static File cacheFile(Context c, String dir, String name, long size) {
        File d = new File(c.getFilesDir(), "lyrics");
        if (!d.exists()) {
            d.mkdirs();
        }
        return new File(d, md5(dir + "|" + name + "|" + size) + ".lrc");
    }

    private static String readCache(Context c, String dir, String name, long size) {
        try {
            File f = cacheFile(c, dir, name, size);
            if (!f.exists() || f.length() == 0) {
                return null;
            }
            byte[] data = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                int read = 0;
                while (read < data.length) {
                    int n = in.read(data, read, data.length - read);
                    if (n <= 0) {
                        break;
                    }
                    read += n;
                }
            } finally {
                in.close();
            }
            return new String(data, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeCache(Context c, String dir, String name, long size, String text) {
        try {
            File f = cacheFile(c, dir, name, size);
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
        } catch (Exception ignored) {
        }
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
