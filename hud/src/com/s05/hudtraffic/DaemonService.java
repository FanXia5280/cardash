package com.s05.hudtraffic;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.util.List;

/**
 * 独立进程守护服务（{@code android:process=":daemon"}）。
 *
 * <p>国内 ROM 杀后台通常是“按进程杀”，主进程被杀时守护进程还活着，
 * 于是守护进程把主服务重新拉起来；主进程反过来也会拉起守护进程。</p>
 *
 * <p><b>防自激</b>：早期版本在 {@code onStartCommand} 里也会调用
 * {@link HudTrafficService#ensureAlive}，而 ensureAlive 又会 start 守护进程本身，
 * 两个进程就会互相拉起，形成每秒几百次的死循环（表现为界面极卡、日志刷屏）。现在：</p>
 * <ul>
 *   <li>{@code onStartCommand} 里<b>不再</b>拉主服务；</li>
 *   <li>只有周期检查会拉，且带冷却时间；</li>
 *   <li>存活判断失败时一律当作“活着”处理，宁可不重启也不要造成拉取风暴。</li>
 * </ul>
 */
public class DaemonService extends Service {

    private static final String TAG = "Daemon";
    private static final String CHANNEL_ID = "hud_traffic_daemon";
    private static final int NOTIFICATION_ID = 1002;

    /** 周期检查间隔 */
    private static final long CHECK_INTERVAL_MS = 10000L;
    /** 两次尝试拉起主服务之间的最小间隔（冷却） */
    private static final long RESTART_COOLDOWN_MS = 15000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastRestartAttemptAt = 0L;

    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            try {
                if (Prefs.isHudEnabled(DaemonService.this) || Prefs.isSimulate(DaemonService.this)) {
                    if (!isMainServiceAlive()) {
                        long now = SystemClock.elapsedRealtime();
                        if (now - lastRestartAttemptAt >= RESTART_COOLDOWN_MS) {
                            lastRestartAttemptAt = now;
                            AppLog.i(TAG, "主服务不在，守护进程拉起（冷却 " + (RESTART_COOLDOWN_MS / 1000) + "s）");
                            HudTrafficService.ensureAlive(DaemonService.this);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            handler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    /**
     * 判断主进程里的 HudTrafficService 是否还活着。
     *
     * <p>注意：{@code getRunningServices} 在 Android 8+ 之后对第三方应用并不可靠
     * （可能返回空列表甚至抛异常），所以<b>判断不出来时一律返回 true</b>，
     * 避免误判成“主服务死了”而造成无限互相拉起。</p>
     */
    private boolean isMainServiceAlive() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return true;
            }
            List<ActivityManager.RunningServiceInfo> list = am.getRunningServices(64);
            if (list == null || list.isEmpty()) {
                // 拿不到信息，保守认为还活着
                return true;
            }
            String target = HudTrafficService.class.getName();
            int myPid = android.os.Process.myPid();
            for (ActivityManager.RunningServiceInfo info : list) {
                if (info == null || info.service == null) {
                    continue;
                }
                if (target.equals(info.service.getClassName()) && info.pid != 0 && info.pid != myPid) {
                    return true;
                }
            }
            // 列表里确实没有它，但为了稳妥再确认一次：只有列表非空且明确不含它才认为不在
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.i(TAG, "守护进程启动");
        AlarmScheduler.schedule(this);
        handler.removeCallbacks(check);
        handler.postDelayed(check, CHECK_INTERVAL_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (Throwable t) {
            AppLog.i(TAG, "守护进程 startForeground 失败: " + t);
        }
        // 这里刻意不调用 ensureAlive：onStartCommand 会被 ensureAlive 间接触发，
        // 再调回去就形成跨进程死循环
        AlarmScheduler.schedule(this);
        handler.removeCallbacks(check);
        handler.postDelayed(check, CHECK_INTERVAL_MS);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        AppLog.i(TAG, "任务被划掉，安排心跳立即拉起");
        AlarmScheduler.schedule(this, 1000L);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        AppLog.i(TAG, "守护进程被销毁，安排心跳重新拉起");
        AlarmScheduler.schedule(this, 2000L);
        super.onDestroy();
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
            // 用 LOW 而不是 MIN：通知可见的常驻进程更不容易被车机清理器当成"无用后台"干掉
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "HUD 红绿灯守护进程", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("HUD 红绿灯守护进程")
                .setContentText("防止后台被杀")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true);
        return builder.build();
    }

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, DaemonService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable t) {
            AppLog.i("Daemon", "启动守护进程失败: " + t);
        }
    }
}
