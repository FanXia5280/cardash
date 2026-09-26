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
 *
 * ContentProvider 会在进程创建时被系统实例化（早于任何 Activity），所以只要在
 * 清单里挂上这一个组件，车机一开机跑起桌面就会把桥接带起来。
 */
public class BootProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        Context ctx = getContext();
        if (ctx == null) return true;

        // 最早的落点，先把诊断开起来
        Diagnostics.init(ctx);
        Diagnostics.log("BootProvider.onCreate 触发");

        try {
            Intent svc = new Intent(ctx, BridgeService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(svc);
            } else {
                ctx.startService(svc);
            }
            Diagnostics.log("已请求启动前台服务");
        } catch (Throwable t) {
            // Android 11 起禁止后台启动前台服务，被拦是正常现象，
            // 下面会直接在进程内把 HTTP 服务跑起来，不影响功能
            Diagnostics.log("前台服务被拦（正常）: " + t.getClass().getSimpleName()
                    + " " + t.getMessage());
        }

        try {
            BridgeRuntime.start(ctx);
        } catch (Throwable t) {
            Diagnostics.log("BridgeRuntime.start 抛异常: " + t);
        }

        // 氛围灯：跟随桌面进程保活 + 在「桌面设置」里挂入口。
        // 整段包在 try 里 —— 氛围灯出任何问题都不允许影响原车功能。
        try {
            LanternEntry.install(ctx);
        } catch (Throwable t) {
            Diagnostics.log("氛围灯初始化失败: " + t);
        }

        // （HUD 红绿灯入口已于 2026-09-26 移除：用户改用「内置到高德里」那份，
        //   桌面设置里只保留「氛围灯设置」）

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
