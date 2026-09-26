package com.cardash.inject;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;

/**
 * 自动把我们的通知监听服务写进系统白名单。
 *
 * 车机上我们以 system 身份运行、持有 WRITE_SECURE_SETTINGS，可以直接改
 * Settings.Secure.ENABLED_NOTIFICATION_LISTENERS，省掉让用户去
 * 「设置 → 通知使用权」里手动勾选。
 *
 * 拿不到权限时（模拟器）会失败，那就退回让用户手动开，不影响其它功能。
 */
public final class NotificationHelper {

    private static final String KEY = "enabled_notification_listeners";
    private static final String SERVICE = "com.cardash.inject.NavListenerService";

    private NotificationHelper() { }

    public static void ensureEnabled(Context ctx) {
        StateHub hub = StateHub.get();
        ComponentName cn = new ComponentName(ctx, SERVICE);
        String flat = cn.flattenToString();

        if (isEnabled(ctx, flat)) {
            hub.setSource("notify", "already-on");
            return;
        }

        try {
            String cur = Settings.Secure.getString(ctx.getContentResolver(), KEY);
            String next;
            if (cur == null || cur.isEmpty()) {
                next = flat;
            } else if (cur.contains(flat)) {
                hub.setSource("notify", "already-on");
                return;
            } else {
                next = cur + ":" + flat;
            }

            if (!ReadOnly.allowWrite()) {
                hub.setSource("notify", "read-only（未自动开启；需手动在设置里开）");
                return;
            }
            Settings.Secure.putString(ctx.getContentResolver(), KEY, next);
            hub.setSource("notify", "auto-enabled");
            Diagnostics.log("通知使用权已自动写入");
        } catch (Throwable t) {
            hub.setSource("notify", "need-manual");
            Diagnostics.log("自动开启通知使用权失败（可在设置里手动开）: " + t);
        }
    }

    private static boolean isEnabled(Context ctx, String flat) {
        try {
            String cur = Settings.Secure.getString(ctx.getContentResolver(), KEY);
            return cur != null && cur.contains(flat);
        } catch (Throwable t) {
            return false;
        }
    }
}
