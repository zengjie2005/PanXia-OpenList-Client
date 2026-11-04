package com.zhj.panxia;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 把缓存目录里的文件以 content:// 暴露给系统里的 PDF / Office 查看器，
 * 避免 Android 7 起禁止的 file:// 传参。
 */
public class LocalProvider extends ContentProvider {

    public static final String AUTHORITY = "com.zhj.panxia.local";

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

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty()) {
            throw new FileNotFoundException("bad uri");
        }
        File f = new File(dir(getContext()), name);
        if (!f.exists()) {
            throw new FileNotFoundException(name);
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
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
