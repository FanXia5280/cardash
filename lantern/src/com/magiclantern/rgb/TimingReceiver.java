package com.magiclantern.rgb;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;

/** 定时开/关灯 */
public class TimingReceiver extends BroadcastReceiver {

    public static final String ACTION_TIMING = "com.magiclantern.rgb.ACTION_TIMING";
    public static final String EXTRA_ON = "on";

    @Override
    public void onReceive(Context context, Intent intent) {
        boolean on = intent.getBooleanExtra(EXTRA_ON, true);
        Prefs.get(context).setPowerOn(on);
        // 通知前台页面执行（已连接时真正下发指令）
        Intent forward = new Intent(ACTION_TIMING);
        forward.putExtra(EXTRA_ON, on);
        forward.setPackage(context.getPackageName());
        context.sendBroadcast(forward);
    }

    public static void scheduleAll(Context c) {
        Prefs prefs = Prefs.get(c);
        cancel(c, 1);
        cancel(c, 2);
        if (prefs.isTimingOnEnabled()) schedule(c, 1, true, prefs.getTimingOnHour(), prefs.getTimingOnMinute());
        if (prefs.isTimingOffEnabled()) schedule(c, 2, false, prefs.getTimingOffHour(), prefs.getTimingOffMinute());
    }

    /**
     * 定时闹钟的投递意图。
     *
     * 用 action + 包名（而不是 new Intent(c, TimingReceiver.class)）：
     * 内置进车机桌面后，内置包的清单里**没有**声明这个 receiver，
     * 指向具体类的意图会无人接收；改成 action 投递后，由常驻的
     * LanternBootstrap 动态注册的接收者接住。
     */
    private static Intent timingIntent(Context c, boolean on) {
        Intent i = new Intent(ACTION_TIMING);
        i.setPackage(c.getPackageName());
        i.putExtra(EXTRA_ON, on);
        return i;
    }

    private static void schedule(Context c, int id, boolean on, int hour, int minute) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = timingIntent(c, on);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(c, id, i, flags);

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        long trigger = cal.getTimeInMillis();
        if (Prefs.get(c).getTimingRepeat() == 0) {
            am.set(AlarmManager.RTC_WAKEUP, trigger, pi);
        } else {
            am.setRepeating(AlarmManager.RTC_WAKEUP, trigger, AlarmManager.INTERVAL_DAY, pi);
        }
    }

    private static void cancel(Context c, int id) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = timingIntent(c, true);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(c, id, i, flags);
        am.cancel(pi);
    }
}
