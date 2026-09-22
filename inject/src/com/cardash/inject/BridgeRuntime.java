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

        StateHub hub = StateHub.get();
        hub.setSource("server", "starting");

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

        try {
            server = new HttpServer(PORT, new HttpServer.Handler() {
                @Override
                public String handle(String path) {
                    return route(path);
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

        return sb.toString();
    }

    private static String fmt(Double d, String unit) {
        return d == null ? "--" : String.valueOf(Math.round(d)) + unit;
    }

    private static String route(String path) {
        if ("/".equals(path) || "/index.html".equals(path)) {
            return StatusPage.render();
        }
        if ("/state".equals(path)) {
            return StateHub.get().toJson();
        }
        if ("/diag".equals(path)) {
            return StatusPage.diag();
        }
        if ("/health".equals(path)) {
            return "ok";
        }
        return null;
    }
}
