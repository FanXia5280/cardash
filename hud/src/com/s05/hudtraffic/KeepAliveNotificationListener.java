package com.s05.hudtraffic;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * 通知使用权保活服务 —— 照抄 D 应用的 {@code MusicService}（也是 NotificationListenerService）。
 *
 * <p><b>为什么它能保活</b>：NotificationListenerService 由系统的
 * NotificationManagerService 通过 bindService 绑定，只要它处于启用状态，
 * 系统就持有一条指向本进程的 binder 连接，进程优先级被显著抬高，
 * 车机自带的"一键清理后台"通常不会去杀被系统绑定的进程。</p>
 *
 * <p>相比无障碍的好处：<b>不需要无障碍权限</b>，而且不会被车机的
 * "自动关闭无障碍"策略影响（它走的是"通知使用权"这个独立开关）。</p>
 *
 * <p>本服务不读取、不上传任何通知内容：回调里只读一下"来源包名"，用于判断高德是否在活动
 * （发现高德活动就拉起 HUD 服务，实现"跟随高德一起活"）。</p>
 *
 * <p>开启方式（任选其一）：</p>
 * <pre>
 * adb shell settings put secure enabled_notification_listeners \
 *     com.s05.hudtraffic/com.s05.hudtraffic.KeepAliveNotificationListener
 * adb shell cmd notification allow_listener \
 *     com.s05.hudtraffic/com.s05.hudtraffic.KeepAliveNotificationListener
 * </pre>
 * 或在车机上：设置 → 通知 → 通知使用权 → 允许「HUD红绿灯」。
 */
public class KeepAliveNotificationListener extends NotificationListenerService {

    private static final String TAG = "NLS";

    private static volatile boolean connected = false;

    public static boolean isConnected() {
        return connected;
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        connected = true;
        AppLog.i(TAG, "通知使用权已连接：系统已绑定本进程，抗杀能力大幅提升");
        // 顺手把 HUD 服务拉起来
        try {
            HudTrafficService.ensureAlive(this);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        connected = false;
        AppLog.i(TAG, "通知使用权已断开（车机可能关掉了它）");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // 只读通知的"来源包名"（不读内容）：发现高德在活动（比如导航通知）就把我们的服务拉起，
        // 实现"跟随高德一起活"。ensureAlive 内部有 5 秒节流，通知再多也不会频繁启动。
        if (sbn == null) {
            return;
        }
        try {
            if (AmapTrafficReceiver.isAmapPackage(sbn.getPackageName())) {
                HudTrafficService.ensureAlive(this);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroy() {
        connected = false;
        AppLog.i(TAG, "通知使用权服务被销毁");
        super.onDestroy();
    }
}
