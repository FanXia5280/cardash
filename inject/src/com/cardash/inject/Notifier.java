package com.cardash.inject;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

/**
 * 状态通知。
 *
 * 关键点：BootProvider 在进程刚起来时执行，这时 App 还在后台，Android 11
 * 不允许后台启动前台服务 —— 所以光靠 BridgeService 的通知是看不到地址的。
 * 这里直接发一条**普通通知**（后台允许），保证地址一定可见。
 * 前台服务起来后会用同一个 ID 覆盖它，不会出现两条。
 */
public final class Notifier {

    public static final String CHANNEL_ID = "cardash_status";
    public static final int ID = 8765;

    private Notifier() { }

    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "CarDash 桥接", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("显示车机桥接的监听地址与实时状态");
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    public static Notification build(Context ctx, String title, String text) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(ctx, CHANNEL_ID);
        } else {
            b = new Notification.Builder(ctx);
        }
        return b.setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .build();
    }

    public static void show(Context ctx, String title, String text) {
        ensureChannel(ctx);
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(ID, build(ctx, title, text));
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }
}
