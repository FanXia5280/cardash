package com.s05.hudtraffic;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

/**
 * 后台保活用的无障碍服务。
 *
 * <p>与 D 应用的 {@code com.deepalhome.launcher.accessibility.AutoNaviAccessibilityService} 同一思路：
 * 无障碍服务由系统绑定，进程会被提升为“前台级别”优先级，普通后台清理/内存回收不会杀掉它，
 * 被杀后系统也会自动重新绑定并把进程拉起来。这样动态注册的高德广播接收器才能一直活着。</p>
 *
 * <p>本服务不做任何 UI 读取（{@code canRetrieveWindowContent=false}），只负责：</p>
 * <ol>
 *   <li>被连接时把 HUD 前台服务拉起来；</li>
 *   <li>周期性看门狗，发现前台服务不在了就补一次（前台服务本身是 START_STICKY，通常不需要）。</li>
 * </ol>
 */
public class KeepAliveAccessibilityService extends AccessibilityService {

    private static final String TAG = "KeepAlive";
    private static final long WATCHDOG_INTERVAL_MS = 30000L;

    private static volatile boolean connected = false;
    private static volatile KeepAliveAccessibilityService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            try {
                if (Prefs.isHudEnabled(KeepAliveAccessibilityService.this)
                        || Prefs.isSimulate(KeepAliveAccessibilityService.this)) {
                    if (!HudTrafficService.isRunning()) {
                        AppLog.i(TAG, "看门狗：前台服务不在，拉起");
                        HudTrafficService.start(KeepAliveAccessibilityService.this);
                    }
                }
            } catch (Throwable ignored) {
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };

    public static boolean isConnected() {
        return connected;
    }

    public static KeepAliveAccessibilityService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        connected = true;
        AppLog.i(TAG, "无障碍服务已连接：进程已获得后台保活优先级");
        try {
            HudTrafficService.start(this);
        } catch (Throwable ignored) {
        }
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不读任何界面内容，只做一件事：发现高德切到前台就把我们的服务确保拉起，
        // 实现"跟随高德一起活"（ensureAlive 内部有 5 秒节流，事件再密也不会频繁启动）。
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        try {
            CharSequence pkg = event.getPackageName();
            if (pkg != null && AmapTrafficReceiver.isAmapPackage(pkg.toString())) {
                HudTrafficService.ensureAlive(this);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onInterrupt() {
        connected = false;
        AppLog.i(TAG, "无障碍服务 onInterrupt");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        connected = false;
        instance = null;
        handler.removeCallbacks(watchdog);
        AppLog.i(TAG, "无障碍服务已断开，后台保活失效");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        connected = false;
        instance = null;
        handler.removeCallbacks(watchdog);
        super.onDestroy();
    }
}
