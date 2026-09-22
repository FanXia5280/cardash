package com.cardash.inject;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;

/**
 * 自动启动钩子。
 * ContentProvider 会在进程创建时被系统实例化（早于任何 Activity），
 * 所以只要在清单里挂上这一个组件，车机一开机跑起桌面就会把桥接带起来。
 */
public class BootProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        Context ctx = getContext();
        if (ctx == null) return true;

        try {
            Intent svc = new Intent(ctx, BridgeService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(svc);
            } else {
                ctx.startService(svc);
            }
        } catch (Throwable ignored) {
            // Android 11 后台启动前台服务可能被拦，下面有兜底
        }

        // 兜底：直接在进程内把 HTTP 服务与采集器跑起来
        try {
            BridgeRuntime.start(ctx);
        } catch (Throwable ignored) {
            // 忽略
        }

        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
