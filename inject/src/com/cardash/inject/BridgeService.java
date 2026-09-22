package com.cardash.inject;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** 常驻前台服务，把监听地址显示在通知栏，方便在车机上直接看到。 */
public class BridgeService extends Service {

    private static final String CHANNEL_ID = "cardash_bridge";
    private static final int NOTIFICATION_ID = 8765;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        try {
            startForegroundCompat(buildNotification("正在启动…"));
        } catch (Throwable ignored) {
            // 前台服务起不来也不影响进程内运行
        }
        BridgeRuntime.start(getApplicationContext());
        refreshNotification();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        BridgeRuntime.start(getApplicationContext());
        refreshNotification();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "CarDash 桥接", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("向 iPhone 仪表盘提供车辆数据");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("CarDash 桥接运行中")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .build();
    }

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void refreshNotification() {
        StateHub hub = StateHub.get();
        StringBuilder sb = new StringBuilder(BridgeRuntime.primaryUrl());
        if (hub.logcatLines > 0) {
            sb.append(" · 车辆信号 ").append(hub.logcatLines).append(" 条");
        } else if (hub.logcatError != null) {
            sb.append(" · 日志读取失败");
        } else {
            sb.append(" · 等待车辆信号");
        }

        String a11y = null;
        synchronized (hub.src) {
            a11y = hub.src.get("a11y");
        }
        if ("need-manual".equals(a11y)) {
            sb.append(" · 需手动开无障碍");
        } else if ("connected".equals(a11y) || "enabled".equals(a11y)
                || "auto-enabled".equals(a11y)) {
            sb.append(" · 读屏已就绪");
        }

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            try {
                nm.notify(NOTIFICATION_ID, buildNotification(sb.toString()));
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }
}
