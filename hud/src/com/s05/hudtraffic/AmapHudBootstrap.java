package com.s05.hudtraffic;

import android.app.Application;

/**
 * 高德启动挂钩入口 —— 由改过的 {@code MapApplicationProxy.onCreate()} 调用。
 *
 * <p><b>这是"HUD 红绿灯"与高德融为一体的关键</b>：光靠 BroadcastReceiver 是不够的
 * （开机广播只在重启时来一次；Android 12+ 后台启动前台服务还会被限制），
 * 只有挂到高德的 Application.onCreate 上，才能做到"打开高德 = 我们被拉起"。</p>
 *
 * <p>这里只做三件很轻的事：装崩溃捕获、显示悬浮球入口、拉起常驻服务。
 * 全部用 try/catch 包住 —— <b>绝不能因为我们的初始化异常影响高德启动</b>。</p>
 */
public final class AmapHudBootstrap {

    private static final String TAG = "Bootstrap";

    /** 同一个进程里只跑一次（Application.onCreate 本来也只跑一次，这里再兜一层） */
    private static volatile boolean started;

    private AmapHudBootstrap() {
    }

    public static void onAmapStart(Application app) {
        try {
            if (started || app == null) {
                return;
            }
            started = true;

            CrashHandler.install(app);
            AppLog.i(TAG, "高德已启动 -> 拉起 HUD 红绿灯");

            // 悬浮球入口：高德界面上点它就能打开我们的设置（不打断导航）
            if (Prefs.isEntryBall(app)) {
                FloatingEntry.show(app);
            }
            // 常驻服务：跑起来才有通知栏入口，HUD 画面仍由"开启 HUD 显示"开关控制
            HudTrafficService.start(app);
        } catch (Throwable t) {
            // 吞掉所有异常：高德自身的启动流程不能受影响
            try {
                AppLog.i(TAG, "启动 HUD 模块失败: " + t);
            } catch (Throwable ignored) {
            }
        }
    }
}
