package com.cardash.inject;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.List;

/** 桥接运行时：幂等启动，ContentProvider / 前台服务 / 启动广播都调用它。 */
public final class BridgeRuntime {

    public static final int PORT = 8765;

    private static volatile boolean started;
    private static long startedAt;
    private static HttpServer server;
    private static VendorSignals vendor;
    private static LogcatSignals logcat;
    private static CarSignals car;
    private static MediaSignals media;
    private static Thread updater;
    private static Context appCtx;

    private BridgeRuntime() { }

    public static Context app() { return appCtx; }

    public static HttpServer httpServer() { return server; }

    public static synchronized void start(Context context) {
        if (started) return;
        started = true;
        startedAt = System.currentTimeMillis();

        Context app = context.getApplicationContext();
        if (app == null) app = context;
        appCtx = app;

        Diagnostics.init(app);
        Diagnostics.log("BridgeRuntime.start 进入");

        try {
            NaviSignals.initPrefs(app);
        } catch (Throwable ignored) {
            // 附加导航包加载失败无所谓
        }

        // 导航的主力数据源：高德车机版主动发的隐式广播
        try {
            AmapSignals.register(app);
        } catch (Throwable t) {
            Diagnostics.log("注册高德广播失败: " + t);
        }

        // 这里 hub 还没声明，直接用 get()
        StateHub.get().setSource("apkVer", versionName(app));

        StateHub hub = StateHub.get();
        hub.setSource("server", "starting");

        // 主力通道：反射调用 D.apk 自带的厂商 SDK（虚拟车辆属性）
        try {
            vendor = new VendorSignals();
            vendor.start(app);
            Diagnostics.log("VendorSignals 已启动");
        } catch (Throwable t) {
            hub.setSource("vendor", "start-failed");
            Diagnostics.log("VendorSignals 启动失败: " + t);
        }

        // 兼容保留：有些车型确实走 logcat，但本车实测没有
        try {
            logcat = new LogcatSignals();
            logcat.start();
            Diagnostics.log("LogcatSignals 已启动");
        } catch (Throwable t) {
            hub.logcatError = "启动失败: " + t;
            Diagnostics.log("LogcatSignals 启动失败: " + t);
        }

        try {
            car = new CarSignals();
            car.start(app);
            Diagnostics.log("CarSignals 已启动");
        } catch (Throwable t) {
            Diagnostics.log("CarSignals 启动失败: " + t);
        }

        try {
            media = new MediaSignals();
            media.start(app);
            Diagnostics.log("MediaSignals 已启动");
        } catch (Throwable t) {
            Diagnostics.log("MediaSignals 启动失败: " + t);
        }

        try {
            AccessibilityHelper.ensureEnabled(app);
            Diagnostics.log("无障碍自注册完成: " + hub.src.get("a11y"));
        } catch (Throwable t) {
            Diagnostics.log("无障碍自注册失败: " + t);
        }

        // 没有通知使用权就拿不到媒体会话、也收不到导航通知
        try {
            NotificationHelper.ensureEnabled(app);
            Diagnostics.log("通知使用权: " + hub.src.get("notify"));
        } catch (Throwable t) {
            Diagnostics.log("通知使用权处理失败: " + t);
        }

        try {
            server = new HttpServer(PORT, new HttpServer.Handler() {
                @Override
                public String handle(String path, String query) {
                    return route(path, query);
                }
            });
            server.start();
            hub.setSource("server", "listening:" + PORT);
            Diagnostics.log("HTTP 服务已启动，端口 " + PORT);
        } catch (Throwable t) {
            hub.setSource("server", "failed");
            Diagnostics.log("HTTP 服务启动失败: " + t);
        }

        Diagnostics.log("网络接口: " + Net.describe());
        try {
            Notifier.show(app, "CarDash 桥接运行中", statusLine(app));
        } catch (Throwable t) {
            Diagnostics.log("发通知失败: " + t);
        }

        startUpdater(app);

        // 尽力拉起前台服务（后台会被拦，拦截了就靠普通通知，不影响 HTTP）
        tryForegroundService(app);
    }

    public static void stop() {
        if (server != null) server.stop();
        if (logcat != null) logcat.stop();
        if (car != null) car.stop();
        if (media != null) media.stop();
        started = false;
    }

    /** 当前监听地址：优先 WiFi 类接口，避开蜂窝网地址 */
    public static String primaryUrl() {
        return "http://" + Net.primary() + ":" + PORT;
    }

    /**
     * 当前注入包的 versionName。
     *
     * 排查时最常见的坑就是「车机上装的其实是旧版」—— 之前为此白折腾过好几轮。
     * 现在把它写进 /diag 和 /state 的 src，一眼就能确认。
     */
    private static String versionName(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /** 一行状态，通知与心跳文件共用 */
    public static String statusLine() {
        return statusLine(appCtx);
    }

    public static String statusLine(Context context) {
        StateHub hub = StateHub.get();
        StringBuilder sb = new StringBuilder(primaryUrl());
        sb.append(" · http=").append(server != null && server.isRunning() ? "ok" : "down");
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
        } else if (a11y != null) {
            sb.append(" · 读屏 ").append(a11y);
        }
        if (server != null && server.lastError() != null) {
            sb.append(" · 端口错误:").append(server.lastError());
        }
        return sb.toString();
    }

    public static void tryForegroundService(Context app) {
        try {
            Intent svc = new Intent(app, BridgeService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(svc);
            } else {
                app.startService(svc);
            }
        } catch (Throwable t) {
            Diagnostics.log("拉起前台服务被拦（正常，不影响 HTTP）: " + t.getClass().getSimpleName());
        }
    }

    // ─────────────────────────────────────── 心跳与通知刷新

    private static void startUpdater(final Context app) {
        if (updater != null) return;
        updater = new Thread(new Runnable() {
            @Override
            public void run() {
                int tick = 0;
                while (true) {
                    try {
                        Thread.sleep(10000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    tick++;
                    try {
                        Diagnostics.heart(heartbeatText());
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                    try {
                        Notifier.show(app, "CarDash 桥接运行中", statusLine(app));
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                    // 每 30 秒试一次前台服务；App 一旦回到前台就能成功
                    if (tick % 3 == 0) {
                        tryForegroundService(app);
                    }
                }
            }
        }, "cardash-updater");
        updater.setDaemon(true);
        updater.start();
    }

    /** 完整诊断文本，写 heartbeat.txt 与 /diag 接口共用 */
    public static String heartbeatText() {
        StateHub hub = StateHub.get();
        StringBuilder sb = new StringBuilder(768);

        sb.append("运行时长: ").append((System.currentTimeMillis() - startedAt) / 1000).append(" 秒\n");
        sb.append("监听地址: ").append(statusLine()).append("\n");
        sb.append("日志目录: ").append(Diagnostics.dir()).append("\n\n");

        sb.append("网络接口:\n");
        List<Net.Iface> ifaces = Net.interfaces();
        if (ifaces.isEmpty()) {
            sb.append("  （没有可用 IPv4）\n");
        } else {
            for (Net.Iface f : ifaces) {
                sb.append("  ").append(f.name).append(" = ").append(f.ip).append('\n');
            }
        }
        sb.append("  → iPhone 上填: ").append(primaryUrl()).append("\n\n");

        sb.append("实时数据:\n");
        sb.append("  车速   ").append(fmt(hub.speedKmh, " km/h")).append('\n');
        sb.append("  档位   ").append(hub.gear == null ? "--" : hub.gear).append('\n');
        sb.append("  电量   ").append(fmt(hub.soc, " %")).append('\n');
        sb.append("  续航   ").append(fmt(hub.rangeKm, " km")).append('\n');
        sb.append("  总里程 ").append(fmt(hub.odometerKm, " km")).append('\n');
        sb.append("  音乐   ").append(hub.mTitle == null ? "--" : hub.mTitle).append('\n');
        sb.append("  导航   ").append(hub.navTitle == null ? "--" : hub.navTitle).append('\n');

        sb.append("\n信号来源:\n");
        synchronized (hub.src) {
            for (java.util.Map.Entry<String, String> e : hub.src.entrySet()) {
                sb.append("  ").append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
            }
        }
        if (hub.logcatError != null) sb.append("  logcatError = ").append(hub.logcatError).append('\n');
        if (hub.carError != null) sb.append("  carError = ").append(hub.carError).append('\n');
        if (hub.mediaError != null) sb.append("  mediaError = ").append(hub.mediaError).append('\n');
        sb.append("  logcatLines = ").append(hub.logcatLines).append('\n');
        sb.append("  logcatSeen = ").append(hub.logcatSeen)
          .append("   logcatMatched = ").append(hub.logcatMatched).append('\n');

        sb.append("\n想看 logcat 抽样打开: ").append(primaryUrl()).append("/logcat\n");
        sb.append("想看厂商属性全量扫描: ").append(primaryUrl()).append("/scan\n");
        sb.append("想看桥接运行日志打开: ").append(primaryUrl()).append("/log\n");

        return sb.toString();
    }

    private static String fmt(Double d, String unit) {
        return d == null ? "--" : String.valueOf(Math.round(d)) + unit;
    }

    private static String route(String path, String query) {
        if ("/".equals(path) || "/index.html".equals(path)) {
            return StatusPage.render();
        }
        if ("/state".equals(path)) {
            return StateHub.get().toJson();
        }
        if ("/diag".equals(path)) {
            return StatusPage.diag();
        }
        if ("/logcat".equals(path)) {
            return logcatReport();
        }
        if ("/scan".equals(path)) {
            VendorSignals v = vendor;
            return v == null
                    ? "厂商通道未启动，先看 /diag 里的 carError"
                    : v.scanEntry(query != null && query.contains("all"));
        }
        if ("/setfull".equals(path)) {
            // 车机不上报 SOC 百分比时，用「剩余续航 / 满电续航」折算，这里设定满电续航
            return VendorSignals.setFullRange(query);
        }
        if ("/setnav".equals(path)) {
            // 知道导航包名但不想等重新打包时，直接加，立刻生效并持久化
            return NaviSignals.addPackage(query);
        }
        if ("/log".equals(path)) {
            return Diagnostics.tail(64 * 1024);
        }
        if ("/hi".equals(path)) {
            // 一个极短的连通性探测，给 iOS 的自动扫描用
            return "ok";
        }
        if ("/health".equals(path)) {
            return "ok";
        }
        return null;
    }

    /**
     * logcat 取数的体检报告。
     *
     * 最关键的三个数：
     *   logcatSeen    = 车机日志里含 CarPropertyValue 的行数
     *   logcatMatched = 正则认出来的行数
     *   topic 统计     = 认出来的信号名和出现次数
     *
     * seen 很大而 matched 是 0，说明车机打的格式和预想不一样 —— 看下面的原始样本；
     * seen 本身就是 0，说明这条路根本不通，得换信号来源。
     */
    private static String logcatReport() {
        StateHub hub = StateHub.get();
        LogcatSignals lc = logcat;
        StringBuilder sb = new StringBuilder(8192);

        sb.append("logcat 取数诊断\n");
        sb.append("  含 CarPropertyValue 的行 : ").append(hub.logcatSeen).append('\n');
        sb.append("  正则匹配成功             : ").append(hub.logcatMatched).append('\n');
        sb.append("  认出的 topic 种类        : ")
          .append(lc == null ? "未启动" : String.valueOf(lc.topicCount())).append('\n');
        sb.append("  logcatError              : ")
          .append(hub.logcatError == null ? "无" : hub.logcatError).append('\n');
        sb.append("  距上次匹配               : ")
          .append(hub.logcatUpdatedAt == 0 ? "从未"
                  : ((System.currentTimeMillis() - hub.logcatUpdatedAt) / 1000) + " 秒前")
          .append('\n');

        sb.append("\n【认出的 topic 统计】\n");
        sb.append(lc == null ? "  （未启动）\n" : lc.topicSummary(25) + "\n");

        sb.append("\n【原始日志样本：含 CarPropertyValue 的行】\n");
        sb.append(lc == null ? "  （未启动）\n" : lc.rawSampleText());

        sb.append("\n【没匹配上的车辆日志样本】\n");
        sb.append(lc == null ? "  （未启动）\n" : lc.missSampleText());

        sb.append("\n当前解析出的值\n");
        sb.append("  车速   ").append(hub.speedKmh == null ? "--" : String.valueOf(hub.speedKmh)).append('\n');
        sb.append("  档位   ").append(hub.gear == null ? "--" : hub.gear).append('\n');
        sb.append("  电量   ").append(hub.soc == null ? "--" : String.valueOf(hub.soc)).append('\n');
        sb.append("  续航   ").append(hub.rangeKm == null ? "--" : String.valueOf(hub.rangeKm)).append('\n');
        sb.append("  总里程 ").append(hub.odometerKm == null ? "--" : String.valueOf(hub.odometerKm)).append('\n');
        sb.append("  封面   ").append(hub.mCover == null ? "无" : (hub.mCover.length() + " 字符")).append('\n');

        sb.append("\n【导航】\n");
        sb.append("  listener     = ")
          .append(hub.src.get("listener") == null ? "未连接" : hub.src.get("listener")).append('\n');
        sb.append("  notify 权限  = ")
          .append(hub.src.get("notify") == null ? "未知" : hub.src.get("notify")).append('\n');
        sb.append("  a11y 状态    = ")
          .append(hub.src.get("a11y") == null ? "未知" : hub.src.get("a11y")).append('\n');
        sb.append("  收到通知条数 = ").append(hub.navSeen).append('\n');
        sb.append("  当前导航     = ")
          .append(hub.navTitle == null ? "--" : hub.navTitle).append('\n');

        sb.append("\n【无障碍事件来源统计（用来找车机导航的真包名）】\n");
        sb.append("  无障碍连接次数 = ").append(NaviAccessibilityService.connectCount()).append('\n');
        sb.append(NaviSignals.a11yPackageSummary());

        sb.append("\n【歌词（优先复用 D.apk 已解析好的时间线）】\n");
        sb.append("  ").append(LauncherLyrics.describe()).append('\n');
        sb.append("  src.lyrics = ")
          .append(hub.src.get("lyrics") == null ? "（还没取）" : hub.src.get("lyrics"))
          .append('\n');

        sb.append("\n【车速来源判定】\n");
        sb.append("  车机缓存车速可信 = ").append(hub.carSpeedTrusted)
          .append("（false 时一律用高德广播的车速）\n");

        sb.append("\n【高德导航广播（导航数据的主力来源）】\n");
        sb.append(AmapSignals.rawSummary());

        sb.append("\n【车机实时推送（挂钩 D.apk 的监听器，档位就在这里）】\n");
        if (vendor != null) {
            sb.append(vendor.liveMapSummary());
        } else {
            sb.append("  （厂商通道未启动）\n");
        }

        sb.append("\n【内容像导航但不在白名单的通知】\n");
        sb.append(NaviSignals.candidateSummary());

        return sb.toString();
    }
}
