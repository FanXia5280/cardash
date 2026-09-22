package com.cardash.inject;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

/**
 * 自动把自己注册进「无障碍」列表。
 *
 * 靠的是 system uid 天然持有的 WRITE_SECURE_SETTINGS —— 桥接注入进 D.apk 后
 * 与桌面同进程同 UID，所以不用麻烦用户去设置里手动勾选。
 *
 * D.apk 自己也是这么干的（AccessibilityManagerHelper）。
 * 失败的话不影响其它功能，用户手动开启即可。
 */
public final class AccessibilityHelper {

    private static final String SERVICE_CLASS = "com.cardash.inject.NaviAccessibilityService";

    private AccessibilityHelper() { }

    public static String selfComponent(Context context) {
        return context.getPackageName() + "/" + SERVICE_CLASS;
    }

    public static void ensureEnabled(Context context) {
        StateHub hub = StateHub.get();
        final String self = selfComponent(context);
        try {
            ContentResolver cr = context.getContentResolver();
            String current = Settings.Secure.getString(
                    cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

            if (current != null && current.contains(self)) {
                hub.setSource("a11y", "enabled");
                return;
            }

            String merged = (current == null || current.isEmpty())
                    ? self
                    : current + ":" + self;

            Settings.Secure.putString(cr,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, merged);
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);

            // 复读一次确认写入生效
            String after = Settings.Secure.getString(
                    cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (after != null && after.contains(self)) {
                hub.setSource("a11y", "auto-enabled");
            } else {
                hub.setSource("a11y", "need-manual");
            }
        } catch (Throwable t) {
            hub.setSource("a11y", "need-manual");
        }
    }

    /** 供状态页/通知展示 */
    public static boolean isEnabled(Context context) {
        try {
            String current = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return current != null && current.contains(selfComponent(context));
        } catch (Throwable t) {
            return false;
        }
    }
}
