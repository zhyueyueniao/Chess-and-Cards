package com.game65713.ccapp;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 最小 FileProvider：本工程不使用 androidx，自己实现一个只暴露内部缓存目录里
 * update.apk 的 ContentProvider，供系统安装器读取。
 */
public class ApkFileProvider extends ContentProvider {

    public static final String FILE_NAME = "update.apk";

    public static Uri uriFor(Context context) {
        return new Uri.Builder()
                .scheme("content")
                .authority(context.getPackageName() + ".fileprovider")
                .appendPath(FILE_NAME)
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!FILE_NAME.equals(uri.getLastPathSegment())) {
            throw new FileNotFoundException("unsupported path: " + uri);
        }
        File apk = new File(getContext().getCacheDir(), FILE_NAME);
        return ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
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
