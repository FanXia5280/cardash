package com.cardash.inject;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * 常驻前台服务。只是「锦上添花」—— 真正的数据通道在 BridgeRuntime 里，
 * 即使这个服务起不来（后台限制），通知与 HTTP 依然照常工作。
 */
public class BridgeService extends Service {

    @Override
    public void onCreate() {
        super.onCreate();
        Context app = getApplicationContext();
        Diagnostics.init(app);
        Diagnostics.log("BridgeService.onCreate");

        Notifier.ensureChannel(this);

        boolean foreground = false;
        try {
            Notification n = Notifier.build(this, "CarDash 桥接运行中", "正在启动…");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(Notifier.ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(Notifier.ID, n);
            }
            foreground = true;
            Diagnostics.log("已成为前台服务");
        } catch (Throwable t) {
            Diagnostics.log("startForeground 失败: " + t);
        }

        if (!foreground) {
            // startForegroundService 后系统要求 5 秒内 startForeground，
            // 做不到就立刻退出，否则会把桌面进程一起拖崩
            Diagnostics.log("无法成为前台服务，主动退出");
            stopSelf();
            return;
        }

        BridgeRuntime.start(app);
        Notifier.show(this, "CarDash 桥接运行中", BridgeRuntime.statusLine());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Context app = getApplicationContext();
        BridgeRuntime.start(app);
        Notifier.show(this, "CarDash 桥接运行中", BridgeRuntime.statusLine());
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
