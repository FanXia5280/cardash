package com.s05.hudtraffic;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

/**
 * 用 AlarmManager 做保活心跳。
 *
 * <p>闹钟是挂在 system_server 上的，**应用进程被杀掉后闹钟依然有效**（除非被 force-stop），
 * 所以这是比“进程内看门狗”强得多的保活手段：即使主进程整个被杀，
 * 一分钟内也会被这个心跳重新拉起来。</p>
 */
public final class AlarmScheduler {

    /** 心跳间隔：30 秒（被杀后最多 30 秒就会被拉回来） */
    public static final long INTERVAL_MS = 30_000L;

    private static final int REQUEST_CODE = 0x51D0;

    private AlarmScheduler() {
    }

    private static PendingIntent pending(Context ctx) {
        Intent intent = new Intent(ctx, KeepAliveReceiver.class);
        intent.setAction(KeepAliveReceiver.ACTION_KEEPALIVE);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(ctx.getApplicationContext(), REQUEST_CODE, intent, flags);
    }

    public static void schedule(Context ctx) {
        schedule(ctx, INTERVAL_MS);
    }

    public static void schedule(Context ctx, long delayMs) {
        Context app = ctx.getApplicationContext();
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        PendingIntent pi = pending(app);
        long trigger = SystemClock.elapsedRealtime() + delayMs;
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
            }
        } catch (Throwable t) {
            try {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
            } catch (Throwable t2) {
                AppLog.i("Alarm", "排定心跳失败: " + t + " / " + t2);
            }
        }
    }

    public static void cancel(Context ctx) {
        Context app = ctx.getApplicationContext();
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        am.cancel(pending(app));
    }
}
