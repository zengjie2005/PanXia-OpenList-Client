package com.zhj.panxia;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** 直链下载 / 文本读取 / 图片解码（直链是带 sign 的，不需要再带认证头）。 */
public final class Net {

    private static final String UA = "OpenListMobile/1.1 (Android)";

    public interface Progress {
        void onProgress(long done, long total);
    }

    private Net() {
    }

    public static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        return c;
    }

    public static InputStream stream(String url) throws IOException {
        HttpURLConnection c = open(url);
        int code = c.getResponseCode();
        if (code >= 400) {
            c.disconnect();
            throw new IOException("HTTP " + code);
        }
        return c.getInputStream();
    }

    /** 读到的文本 + 探测出的编码信息（编辑保存时要按同样的编码写回） */
    public static final class Text {
        public final String text;
        /** UTF-8 / GBK */
        public final String charset;
        /** 原文件是否带 UTF-8 BOM */
        public final boolean bom;

        Text(String text, String charset, boolean bom) {
            this.text = text;
            this.charset = charset;
            this.bom = bom;
        }
    }

    /** 读纯文本，超过 max 字节则截断并提示 */
    public static String text(String url, int max) throws IOException {
        return textData(url, max).text;
    }

    /** 读纯文本，同时返回编码信息，供「在线编辑并保存」原样写回 */
    public static Text textData(String url, int max) throws IOException {
        return decode(bytes(url, max));
    }

    /** 按 BOM / UTF-8 / GBK 规则解码字节，并记录编码信息 */
    public static Text decode(byte[] data) {
        boolean bom = data.length >= 3
                && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF;
        int off = bom ? 3 : 0;
        String s = new String(data, off, data.length - off, StandardCharsets.UTF_8);
        String charset = "UTF-8";
        // 简单编码探测：UTF-8 解出替换字符则按 GBK 再解一次（中文老文件常见）
        if (s.indexOf('\uFFFD') >= 0) {
            try {
                s = new String(data, off, data.length - off, Charset.forName("GBK"));
                charset = "GBK";
            } catch (Exception ignored) {
            }
        }
        return new Text(s, charset, bom);
    }

    /** 读原始字节（上限 max） */
    public static byte[] bytes(String url, int max) throws IOException {
        InputStream in = stream(url);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
                if (bos.size() > max) {
                    break;
                }
            }
        } finally {
            close(in);
        }
        return bos.toByteArray();
    }

    /** 按视图尺寸采样解码，避免大图直接把内存打爆 */
    public static Bitmap image(String url, int viewW, int viewH) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream in = stream(url);
        try {
            BitmapFactory.decodeStream(in, null, bounds);
        } finally {
            close(in);
        }
        int sample = 1;
        int w = bounds.outWidth;
        int h = bounds.outHeight;
        if (w > 0 && h > 0 && viewW > 0 && viewH > 0) {
            while (w / (sample * 2) >= viewW && h / (sample * 2) >= viewH) {
                sample *= 2;
            }
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        InputStream in2 = stream(url);
        try {
            return BitmapFactory.decodeStream(in2, null, opts);
        } finally {
            close(in2);
        }
    }

    /** 下载到本地文件 */
    public static void toFile(String url, java.io.File dest, Progress p) throws IOException {
        HttpURLConnection c = open(url);
        try {
            int code = c.getResponseCode();
            if (code >= 400) {
                throw new IOException("HTTP " + code);
            }
            long total = c.getContentLength();
            InputStream in = c.getInputStream();
            java.io.OutputStream out = new java.io.FileOutputStream(dest);
            try {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    done += n;
                    if (p != null) {
                        p.onProgress(done, total);
                    }
                }
                out.flush();
            } finally {
                close(in);
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
        } finally {
            c.disconnect();
        }
    }

    public static void close(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }
}
