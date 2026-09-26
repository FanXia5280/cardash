package com.s05.hudtraffic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Display;

/**
 * 常驻前台服务：
 * <ul>
 *   <li>注册高德广播接收器（必须在进程存活期间动态注册）；</li>
 *   <li>监听副屏插拔，自动把红绿灯面板挂到 HUD 上；</li>
 *   <li>提供“模拟数据源”，方便没有高德车机版的模拟器上验证 HUD 显示效果；</li>
 * </ul>
 *
 * <p>2026-09-27 起去掉全部保活机关（AlarmManager 心跳 / DaemonService 守护进程 /
 * 无障碍保活）：本模块内嵌在 D+ 桌面进程里运行，D+ 是常驻桌面不会被杀，
 * 我们跟着进程活着即可，之前的保活三件套纯属多余。</p>
 */
public class HudTrafficService extends Service implements DisplayManager.DisplayListener {

    public static final String ACTION_START = "com.s05.hudtraffic.action.START";
    public static final String ACTION_STOP = "com.s05.hudtraffic.action.STOP";
    public static final String ACTION_REFRESH = "com.s05.hudtraffic.action.REFRESH";

    private static final String CHANNEL_ID = "hud_traffic_service";
    private static final int NOTIFICATION_ID = 1001;

    /** 本进程内服务是否存活（仅主进程有效） */
    private static volatile boolean running = false;

    public static boolean isRunning() {
        return running;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());

    private AmapTrafficReceiver amapReceiver;
    private DisplayManager displayManager;

    private final TrafficLightBus.Listener busListener = new TrafficLightBus.Listener() {
        @Override
        public void onTrafficLightChanged(TrafficLightState state) {
            HudTrafficManager.get().render(state);
        }
    };

    /**
     * HUD 看门狗周期。
     *
     * <p>退出导航 / 副屏重建时，系统会把 HUD 的窗口或 surface 回收掉，而我们的
     * {@link HudTrafficManager} 里那个 Java 对象还活着 —— 不做检查的话 HUD 就永久空白，
     * 只能手动关一次再打开"HUD 显示"开关才恢复（用户反馈的 bug）。</p>
     *
     * <p>{@link HudTrafficManager#needsRebuild()} 只会把"窗口还在、但已经画不出来"判为需要重建，
     * 所以这里不会和"本来就还没有 HUD 副屏"的情况形成重建循环。</p>
     */
    private static final long HUD_WATCHDOG_MS = 5000L;

    private final Runnable hudWatchdog = new Runnable() {
        @Override
        public void run() {
            try {
                HudTrafficManager mgr = HudTrafficManager.get();
                if (mgr.isShowing() && mgr.needsRebuild()) {
                    AppLog.i("Service", "HUD 看门狗：叠加窗已失效（surface/窗口被回收），重建并重画");
                    refreshHud("watchdog");
                }
                maybeAutoWake();
                // 同层级窗口谁后添加谁在上面：D 桌面的歌词/SR 窗口会比我们晚加，
                // 定期把自己重新 addView 一次夺回最上层。
                // ⚠️ re-add 的瞬间 HUD 内容会闪一下（约几十毫秒），这就是用户看到的
                // 「HUD 闪烁」——原间隔 60 秒太密，改成 5 分钟一次：
                // 被盖住最多 5 分钟自动恢复（也可回设置页动一下开关立刻刷新），
                // 闪烁频率从每分钟一次降到 5 分钟一次。
                long now2 = android.os.SystemClock.elapsedRealtime();
                if (Prefs.isHudEnabled(HudTrafficService.this)
                        && now2 - lastBringFrontAt > 300000L) {
                    lastBringFrontAt = now2;
                    HudTrafficManager.get().bringToFront();
                }
            } catch (Throwable t) {
                AppLog.i("Service", "HUD 看门狗异常: " + t);
            }
            handler.postDelayed(this, HUD_WATCHDOG_MS);
        }
    };

    /* ---------------- 模拟数据源 ---------------- */
    // 每个方向有自己独立的信号周期，互不联动
    //（真实高德也是按方向分别广播 60073 的，各自带自己的 trafficLightStatus 和倒计时）
    private static final int[] SIM_DIRS = {
            TrafficLightState.DIR_STRAIGHT,
            TrafficLightState.DIR_LEFT,
            TrafficLightState.DIR_RIGHT,
    };
    /** 每个方向的绿灯 / 黄灯 / 红灯时长，刻意取不同的值，保证三个灯不同步 */
    private static final int[] SIM_GREEN_SEC = {18, 10, 8};
    private static final int[] SIM_YELLOW_SEC = {3, 3, 3};
    private static final int[] SIM_RED_SEC = {22, 30, 35};

    private final int[] simStatus = {
            TrafficLightState.ST_GREEN,
            TrafficLightState.ST_ABOUT_GREEN,
            TrafficLightState.ST_ABOUT_GREEN,
    };
    private final int[] simLeft = {18, 10, 8};
    /** 模拟路口类型轮换：0=只有直行 1=直行+左转 2=直行+左转+右转 */
    private int simIntersection = 1;
    private int simTickCount = 0;

    private final Runnable simTick = new Runnable() {
        @Override
        public void run() {
            if (!Prefs.isSimulate(HudTrafficService.this)) {
                return;
            }
            publishSimState();
            handler.postDelayed(this, 1000L);
        }
    };

    private void publishSimState() {
        long now = System.currentTimeMillis();
        simTickCount++;

        // 每 40 秒换一种路口，用来演示"只有该方向有灯时才显示"：
        // 0 = 只有直行，1 = 直行+左转，2 = 直行+左转+右转
        if (simTickCount % 40 == 0) {
            simIntersection = (simIntersection + 1) % 3;
        }
        int visible = simIntersection + 1;

        TrafficLightState s = new TrafficLightState();
        for (int i = 0; i < SIM_DIRS.length; i++) {
            // 每个方向自己走自己的周期
            simLeft[i]--;
            if (simLeft[i] <= 0) {
                if (simStatus[i] == TrafficLightState.ST_GREEN) {
                    simStatus[i] = TrafficLightState.ST_YELLOW;
                    simLeft[i] = SIM_YELLOW_SEC[i];
                } else if (simStatus[i] == TrafficLightState.ST_YELLOW) {
                    simStatus[i] = TrafficLightState.ST_ABOUT_GREEN;
                    simLeft[i] = SIM_RED_SEC[i];
                } else {
                    simStatus[i] = TrafficLightState.ST_GREEN;
                    simLeft[i] = SIM_GREEN_SEC[i];
                }
            }
            if (i < visible) {
                s.applyLight(SIM_DIRS[i], simStatus[i], simLeft[i], 0, 0, now);
            }
        }

        s.routeLightNum = 7;
        s.remainLightNum = 3;
        s.roadName = "模拟路段";
        s.limitedSpeed = 60;
        s.sapaDist = 300 + (simTickCount % 60) * 10;
        // 电子眼：距离递减，模拟"前方 xx米 测速 60"
        s.cameraDist = 200 + (simTickCount % 50) * 5;
        s.cameraType = 1;
        s.cameraSpeed = 60;
        // 终点信息（HUD 第二行：2.3km · 6min · 到达时间）
        s.routeRemainDist = 2300 - (simTickCount % 60) * 10;
        s.routeRemainTime = 360;
        s.etaText = android.text.format.DateFormat.format("HH:mm", now + 360_000L).toString();
        s.source = "sim";
        s.updateTime = now;
        TrafficLightBus.publish(s);
    }

    /* ---------------- 生命周期 ---------------- */

    @Override
    public void onCreate() {
        super.onCreate();
        // 内置进高德时我们的 App（Application 子类）不会被使用，这里补装崩溃捕获
        CrashHandler.install(getApplicationContext());
        running = true;
        HudTrafficManager.get().setAppContext(getApplicationContext());
        S05HudWakeHelper.setAppContext(getApplicationContext());
        // 车速兜底（高德没给 CUR_SPEED 时用本车 GPS 速度）
        SpeedProvider.start(this);
        // 文字黑描边（默认关闭，粗描边在 HUD 上会有锯齿毛边）
        HudPanelRenderer.setOutlineEnabled(Prefs.isTextOutline(this));
        // 全部控件预览（三个格子都用示例数据）
        HudPanelRenderer.setPreviewAll(Prefs.isPreviewAll(this));
        // 控件独立控制要读配置
        HudPanelRenderer.setAppContext(getApplicationContext());

        amapReceiver = new AmapTrafficReceiver();
        amapReceiver.register(this);
        TrafficLightBus.addListener(busListener);

        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager != null) {
            displayManager.registerDisplayListener(this, handler);
        }

        // 保活机关已全部移除（D+ 桌面常驻 ⇒ 我们跟着常驻），只剩 HUD 窗口看门狗

        // HUD 常驻看门狗：窗口/surface 被系统回收后自动重建（服务活着就一直跑）
        handler.removeCallbacks(hudWatchdog);
        handler.postDelayed(hudWatchdog, HUD_WATCHDOG_MS);

        AppLog.i("Service", "服务 onCreate 完成");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 通知构造 / startForeground 在个别 ROM 上会抛异常，这里兜住并自裁，
        // 否则系统会在 5 秒后抛 RemoteServiceException 直接杀掉进程（表现为闪退）
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (Throwable t) {
            AppLog.i("Service", "startForeground 失败，主动停止服务: " + t);
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            AppLog.i("Service", "收到停止指令");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (Prefs.isSimulate(this)) {
            handler.removeCallbacks(simTick);
            handler.post(simTick);
        } else {
            handler.removeCallbacks(simTick);
        }

        refreshHud("onStartCommand");
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 从最近任务划掉时把服务重新拉起（D+ 桌面没有"最近任务"入口，这里只是兜底）
        AppLog.i("Service", "任务被划掉，重新拉起服务");
        if (Prefs.isHudEnabled(this) || Prefs.isSimulate(this)) {
            ensureAlive(this);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(simTick);
        handler.removeCallbacks(hudWatchdog);
        if (displayManager != null) {
            try {
                displayManager.unregisterDisplayListener(this);
            } catch (Throwable ignored) {
            }
        }
        TrafficLightBus.removeListener(busListener);
        if (amapReceiver != null) {
            amapReceiver.unregister(this);
        }
        HudTrafficManager.get().hide();
        running = false;
        AppLog.i("Service", "服务已销毁");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /* ---------------- HUD 刷新 ---------------- */

    public void refreshHud(String reason) {
        Context ctx = getApplicationContext();
        boolean hudEnabled = Prefs.isHudEnabled(ctx);
        boolean mirrorEnabled = Prefs.isMirrorMain(ctx);
        boolean relaxed = Prefs.isRelaxedDisplay(ctx);
        HudTrafficManager.get().setScale(Prefs.getScale(ctx) / 100f);

        // 两个开关互相独立：只开「主屏悬浮窗」时也要能显示
        // （之前的 bug：HUD 开关没开就直接 hide()，把主屏悬浮窗一起关掉了）
        if (!hudEnabled && !mirrorEnabled) {
            HudTrafficManager.get().hide();
            return;
        }
        if (!hudEnabled) {
            // 只开「主屏悬浮窗」
            HudTrafficManager.get().ensureShown(ctx, null);
            return;
        }
        Display hud = HudDisplayHelper.findHudDisplay(ctx, relaxed);
        if (hud == null) {
            // 没找到 HUD 副屏（模拟器 / HUD 未接入）→ 自动退回主屏悬浮窗，保证至少能看到内容
            boolean fallback = Prefs.isAutoFallback(ctx);
            HudTrafficManager.get().ensureShown(ctx, null, fallback);
            AppLog.i("Service", "未找到 HUD 副屏 (" + reason + ")"
                    + (fallback ? "，已自动退回主屏悬浮窗" : ""));
            return;
        }
        HudTrafficManager.get().ensureShown(ctx, hud);
    }

    /* ---------------- DisplayListener ---------------- */

    @Override
    public void onDisplayAdded(int displayId) {
        AppLog.i("Service", "副屏接入 displayId=" + displayId);
        refreshHud("displayAdded");
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        AppLog.i("Service", "副屏移除 displayId=" + displayId);
        refreshHud("displayRemoved");
    }

    @Override
    public void onDisplayChanged(int displayId) {
        if (!Prefs.isHudEnabled(this)) {
            return;
        }
        refreshHud("displayChanged");
    }

    /* ---------------- 通知 ---------------- */

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel old = nm.getNotificationChannel(CHANNEL_ID);
            // 早期版本建的是 IMPORTANCE_LOW 静默通道，部分车机会把它当成"没有提示的后台服务"清理掉。
            // 这里升级为 DEFAULT（通知可见），并重建通道让新重要度立刻生效。
            if (old != null && old.getImportance() < NotificationManager.IMPORTANCE_DEFAULT) {
                try {
                    nm.deleteNotificationChannel(CHANNEL_ID);
                } catch (Throwable ignored) {
                }
                old = null;
            }
            if (old == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "HUD 红绿灯",
                        NotificationManager.IMPORTANCE_DEFAULT);
                channel.setShowBadge(false);
                channel.enableVibration(false);
                channel.setSound(null, null);
                nm.createNotificationChannel(channel);
            }
        }

        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("HUD 红绿灯倒计时")
                .setContentText("正在把高德的红绿灯显示到 HUD")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .setContentIntent(pi);
        return builder.build();
    }

    /* ---------------- 静态便捷方法 ---------------- */

    public static void start(Context ctx) {
        Context app = ctx.getApplicationContext();
        Intent i = new Intent(app, HudTrafficService.class);
        i.setAction(ACTION_START);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                app.startForegroundService(i);
            } else {
                app.startService(i);
            }
        } catch (Throwable t) {
            AppLog.i("Service", "启动服务失败: " + t);
        }
    }

    /**
     * ensureAlive 的最小调用间隔。
     * 曾经因为「守护进程 onStartCommand → ensureAlive → 又 start 守护进程」形成过
     * 每秒几百次的跨进程死循环（表现为界面极卡 + 日志刷屏），这个节流是最后一道保险。
     */
    private static final long ENSURE_MIN_INTERVAL_MS = 5000L;
    private static volatile long lastEnsureAt = 0L;

    /**
     * 确保前台服务活着（原"保活核心"：心跳 + 守护进程已按需求移除，
     * 现在只剩把 HudTrafficService 自己拉起来）。
     */
    public static void ensureAlive(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (!Prefs.isHudEnabled(app) && !Prefs.isSimulate(app)) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastEnsureAt < ENSURE_MIN_INTERVAL_MS) {
            return;
        }
        lastEnsureAt = now;
        start(app);
    }

    public static void stop(Context ctx) {
        Context app = ctx.getApplicationContext();
        Intent i = new Intent(app, HudTrafficService.class);
        i.setAction(ACTION_STOP);
        try {
            app.startService(i);
        } catch (Throwable t) {
            app.stopService(new Intent(app, HudTrafficService.class));
        }
    }

    /** 车机退出导航会把 HUD 投影关掉，补发开屏指令把它重新点亮。 */
    private static final android.os.Handler WAKE_HANDLER =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static volatile long lastAutoWakeAt = 0L;
    private static volatile long lastBringFrontAt = 0L;

    /** 延迟补发一次 HUD 开屏指令（给车机一点时间处理完退出导航的收尾）。 */
    public static void wakeHudLater(Context ctx, long delayMs) {
        final Context app = ctx.getApplicationContext();
        WAKE_HANDLER.postDelayed(new Runnable() {
            @Override
            public void run() {
                S05HudWakeHelper.setAppContext(app);
                S05HudWakeHelper.fullWakeHud(app);
            }
        }, delayMs);
    }

    /** 防止 bounceHudSwitch 重入。 */
    private static volatile boolean bouncingHud = false;
    private static volatile int bouncePhase = 0;

    /**
     * 结束导航后 HUD 上不显示内容 → 自动后台"关一下再开"HUD 显示开关。
     *
     * <p>这是用户实测唯一可靠的办法（等效手动关一次再开）：关会把叠加窗整个销毁，
     * 开会重建窗口并顺带补发 HUD 开屏指令。只在本来的 HUD 是开着的时候才做，
     * 不会替用户把 HUD 打开。</p>
     */
    public static void bounceHudSwitch(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        if (!Prefs.isHudEnabled(app)) {
            return;
        }
        if (bouncingHud) {
            return;
        }
        bouncingHud = true;
        bouncePhase = 1;
        AppLog.i("Service", "结束导航：自动后台关-开 HUD（两轮，2.5 秒内完成）");
        chainBounce(app, 200L);
    }

    private static void chainBounce(final Context app, long delay) {
        WAKE_HANDLER.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (bouncePhase == 1 || bouncePhase == 3) {
                        Prefs.setHudEnabled(app, false);
                        HudTrafficManager.get().hide();
                    } else {
                        Prefs.setHudEnabled(app, true);
                        ensureAlive(app);
                        refresh(app);
                    }
                } catch (Throwable ignored) {
                }
                bouncePhase++;
                long next;
                if (bouncePhase == 2) {
                    next = 300L;
                } else if (bouncePhase == 3) {
                    next = 1500L;
                } else if (bouncePhase == 4) {
                    next = 300L;
                } else {
                    bouncePhase = 0;
                    bouncingHud = false;
                    AppLog.i("Service", "HUD 自动开关完成（共 2 轮）");
                    return;
                }
                chainBounce(app, next);
            }
        }, delay);
    }

    /** 延迟把 HUD 叠加窗（含第二区域小窗）重新置顶。 */
    public static void bringFrontLater(Context ctx, long delayMs) {
        final Context app = ctx.getApplicationContext();
        WAKE_HANDLER.postDelayed(new Runnable() {
            @Override
            public void run() {
                HudTrafficManager.get().bringToFront();
            }
        }, delayMs);
    }

    /** 看门狗兜底：非导航状态下每 60 秒补一次开屏，防止车机把 HUD 再次熄掉。 */
    private void maybeAutoWake() {
        if (!Prefs.isHudEnabled(this)) {
            return;
        }
        if (AmapTrafficReceiver.currentMode == 1) {
            return;   // 导航中高德自己会点亮 HUD
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastAutoWakeAt < 60000L) {
            return;
        }
        lastAutoWakeAt = now;
        AppLog.i("Service", "非导航状态补发 HUD 开屏指令（防止车机熄掉 HUD）");
        wakeHudLater(this, 0L);
    }

    public static void refresh(Context ctx) {
        Context app = ctx.getApplicationContext();
        Intent i = new Intent(app, HudTrafficService.class);
        i.setAction(ACTION_REFRESH);
        try {
            app.startService(i);
        } catch (Throwable ignored) {
        }
    }
}
