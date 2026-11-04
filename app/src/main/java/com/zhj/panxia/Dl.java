package com.zhj.panxia;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;

/** 把直链文件保存到系统「下载」目录（Android 10+ 免存储权限）。 */
public final class Dl {

    public interface Progress {
        void onProgress(int percent);
    }

    private Dl() {
    }

    /** @return 保存位置的可读描述 */
    public static String save(Context ctx, String url, String name, Progress p) throws Exception {
        HttpURLConnection c = Net.open(url);
        InputStream in = null;
        OutputStream out = null;
        File outFile = null;
        try {
            int code = c.getResponseCode();
            if (code >= 400) {
                throw new Exception("下载失败（HTTP " + code + "）");
            }
            long total = c.getContentLength();
            in = c.getInputStream();

            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE, Mime.mime(name));
                cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PanXia");
                Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) {
                    throw new Exception("无法创建下载文件");
                }
                out = ctx.getContentResolver().openOutputStream(uri);
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "PanXia");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new Exception("无法创建下载目录");
                }
                outFile = new File(dir, name);
                out = new FileOutputStream(outFile);
            }
            if (out == null) {
                throw new Exception("无法写入文件");
            }
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int n;
            int last = -1;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                done += n;
                int pct = total > 0 ? (int) (done * 100 / total) : 0;
                if (p != null && pct != last) {
                    last = pct;
                    p.onProgress(pct);
                }
            }
            out.flush();
            return Build.VERSION.SDK_INT >= 29
                    ? "下载/PanXia/" + name
                    : (outFile == null ? name : outFile.getAbsolutePath());
        } finally {
            Net.close(in);
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
            c.disconnect();
        }
    }
}
