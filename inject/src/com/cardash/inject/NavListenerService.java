package com.cardash.inject;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * 注入进 D.apk 的通知监听组件：
 *  · 让 MediaSessionManager#getActiveSessions 能拿到媒体会话
 *  · 从导航应用的通知里解析转向信息
 */
public class NavListenerService extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            NaviSignals.onPosted(sbn);
        } catch (Throwable ignored) {
            // 单条通知解析失败不影响其它
        }
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        StateHub.get().setSource("listener", "connected");
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        StateHub.get().setSource("listener", "disconnected");
    }
}
