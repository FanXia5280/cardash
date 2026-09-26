package com.s05.hudtraffic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * 持有 MediaProjection 的前台服务。
 * Android 14+ 要求：必须先 startForeground(FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
 * 才允许 getMediaProjection —— 所以授权回调后先拉起本服务，再在服务里初始化捕获。
 */
public class MapCaptureService extends Service {

    public static final String ACTION_START = "com.s05.hudtraffic.action.MAP_CAPTURE_START";
    public static final String ACTION_STOP = "com.s05.hudtraffic.action.MAP_CAPTURE_STOP";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "data";
    private static final String CHANNEL_ID = "map_capture";
    private static final int NOTIFICATION_ID = 1002;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            ScreenMapCapture.stop();
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Throwable t) {
            AppLog.i("MapCap", "startForeground 失败: " + t);
            stopSelf();
            return START_NOT_STICKY;
        }
        int code = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = null;
        try {
            data = intent.getParcelableExtra(EXTRA_DATA);
        } catch (Throwable ignored) {
        }
        ScreenMapCapture.start(this, code, data);
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "路线图投屏",
                NotificationManager.IMPORTANCE_LOW));
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("HUD 路线图投屏运行中")
                .setContentText("正在抓取高德导航路线图投到 HUD")
                .setSmallIcon(android.R.drawable.ic_menu_mapmode)
                .setOngoing(true)
                .build();
    }
}
