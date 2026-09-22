package com.cardash.inject;

import android.content.Context;

/** 桥接运行时：幂等启动，ContentProvider 与前台服务都调用它。 */
public final class BridgeRuntime {

    public static final int PORT = 8765;

    private static volatile boolean started;
    private static HttpServer server;
    private static LogcatSignals logcat;
    private static CarSignals car;
    private static MediaSignals media;

    private BridgeRuntime() { }

    public static synchronized void start(Context context) {
        if (started) return;
        started = true;

        Context app = context.getApplicationContext();
        if (app == null) app = context;

        StateHub hub = StateHub.get();
        hub.setSource("server", "starting");

        try {
            logcat = new LogcatSignals();
            logcat.start();
        } catch (Throwable t) {
            hub.logcatError = "启动失败: " + t.getMessage();
        }

        try {
            car = new CarSignals();
            car.start(app);
        } catch (Throwable ignored) {
            // 忽略
        }

        try {
            media = new MediaSignals();
            media.start(app);
        } catch (Throwable ignored) {
            // 忽略
        }

        // 把自己注册进无障碍列表，用于读导航界面上的文字
        try {
            AccessibilityHelper.ensureEnabled(app);
        } catch (Throwable ignored) {
            // 忽略
        }

        server = new HttpServer(PORT, new HttpServer.Handler() {
            @Override
            public String handle(String path) {
                return route(path);
            }
        });
        server.start();

        hub.setSource("server", "listening:" + PORT);
    }

    public static void stop() {
        if (server != null) server.stop();
        if (logcat != null) logcat.stop();
        if (car != null) car.stop();
        if (media != null) media.stop();
        started = false;
    }

    /** 当前监听地址，取第一个可用的局域网 IPv4。 */
    public static String primaryUrl() {
        java.util.List<String> ips = Net.ipv4Addresses();
        String ip = ips.isEmpty() ? "127.0.0.1" : ips.get(0);
        return "http://" + ip + ":" + PORT;
    }

    private static String route(String path) {
        if ("/".equals(path) || "/index.html".equals(path)) {
            return StatusPage.render();
        }
        if ("/state".equals(path)) {
            return StateHub.get().toJson();
        }
        if ("/health".equals(path)) {
            return "ok";
        }
        return null;
    }
}
