package com.zhj.panxia;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.util.Base64;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * 把文件以 content:// 暴露给系统里的 PDF / Office / 图片查看器，
 * 避免 Android 7 起禁止的 file:// 传参。
 *
 * 两类 URI：
 * - 旧：content://authority/&lt;文件名&gt;，指向缓存 open/ 目录（下载预览用）
 * - 新：content://authority/abs/&lt;urlsafe-base64 绝对路径&gt;，供本地文件页打开设备文件
 *
 * 后者只允许访问外部存储、应用私有外部目录与缓存目录，避免被当成任意文件读取通道。
 */
public class LocalProvider extends ContentProvider {

    public static final String AUTHORITY = "com.zhj.panxia.local";
    private static final String SEG_ABS = "abs";

    public static File dir(Context c) {
        File d = new File(c.getCacheDir(), "open");
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    public static Uri uriFor(String fileName) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(fileName).build();
    }

    /** 设备绝对路径 -> 安全的 content:// URI；失败返回 Uri.EMPTY */
    public static Uri uriForAbs(String absolutePath) {
        if (absolutePath == null || absolutePath.isEmpty()) {
            return Uri.EMPTY;
        }
        try {
            String enc = Base64.encodeToString(absolutePath.getBytes("UTF-8"),
                    Base64.URL_SAFE | Base64.NO_WRAP);
            return new Uri.Builder().scheme("content").authority(AUTHORITY)
                    .appendPath(SEG_ABS).appendPath(enc).build();
        } catch (Exception e) {
            return Uri.EMPTY;
        }
    }

    private File fileFor(Uri uri) {
        List<String> segs = uri.getPathSegments();
        if (segs.size() >= 2 && SEG_ABS.equals(segs.get(0))) {
            try {
                String dec = new String(Base64.decode(segs.get(1), Base64.URL_SAFE), "UTF-8");
                File f = new File(dec);
                return allowed(f) ? f : null;
            } catch (Exception e) {
                return null;
            }
        }
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty()) {
            return null;
        }
        return new File(dir(getContext()), name);
    }

    private boolean allowed(File f) {
        if (f == null || getContext() == null) {
            return false;
        }
        try {
            String target = f.getCanonicalPath();
            return under(target, Environment.getExternalStorageDirectory())
                    || under(target, getContext().getExternalFilesDir(null))
                    || under(target, getContext().getCacheDir());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean under(String path, File root) {
        if (root == null) {
            return false;
        }
        try {
            String r = root.getCanonicalPath();
            return path.equals(r) || path.startsWith(r + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileFor(uri);
        if (f == null || !f.exists() || !f.isFile()) {
            throw new FileNotFoundException("file not found or not allowed");
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        List<String> segs = uri.getPathSegments();
        String name;
        if (segs.size() >= 2 && SEG_ABS.equals(segs.get(0))) {
            try {
                String dec = new String(Base64.decode(segs.get(1), Base64.URL_SAFE), "UTF-8");
                name = new File(dec).getName();
            } catch (Exception e) {
                return "application/octet-stream";
            }
        } else {
            name = uri.getLastPathSegment();
        }
        return name == null ? "application/octet-stream" : Mime.mime(name);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
