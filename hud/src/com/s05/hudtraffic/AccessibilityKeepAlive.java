package com.s05.hudtraffic;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;

/**
 * 无障碍保活的开关/状态辅助。
 *
 * <p>开关逻辑参考 D 应用的 {@code AccessibilityManagerHelper}：
 * 车机上是系统应用（有 {@code WRITE_SECURE_SETTINGS}）时可直接写 Settings.Secure 自启用；
 * 否则只能拉起系统设置页让用户手动打开。</p>
 */
public final class AccessibilityKeepAlive {

    private static final String TAG = "KeepAlive";
    private static final String SERVICE_CLASS = "com.s05.hudtraffic.KeepAliveAccessibilityService";

    private AccessibilityKeepAlive() {
    }

    public static ComponentName component(Context ctx) {
        return new ComponentName(ctx.getPackageName(), KeepAliveAccessibilityService.class.getName());
    }

    /** 是否已开启（已连上或已写入系统开关都算）。 */
    public static boolean isEnabled(Context ctx) {
        if (KeepAliveAccessibilityService.isConnected()) {
            return true;
        }
        try {
            String enabled = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || enabled.length() == 0) {
                return false;
            }
            String full = ctx.getPackageName() + "/" + KeepAliveAccessibilityService.class.getName();
            String shortId = ctx.getPackageName() + "/.KeepAliveAccessibilityService";
            return enabled.contains(full) || enabled.contains(shortId);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean hasWriteSecureSettings(Context ctx) {
        try {
            return ctx.checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 车机系统应用可直接自启用；普通应用会抛 SecurityException，返回 false。
     */
    public static boolean tryEnableViaSecureSettings(Context ctx) {
        if (!allowWriteSoft()) return false;   // 只读：不写 Settings.Secure
        if (!hasWriteSecureSettings(ctx)) {
            AppLog.i(TAG, "无 WRITE_SECURE_SETTINGS 权限，需手动开启无障碍");
            return false;
        }
        try {
            String me = ctx.getPackageName() + "/" + KeepAliveAccessibilityService.class.getName();
            String current = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (current == null) {
                current = "";
            }
            if (!current.contains(me)) {
                String next = current.length() == 0 ? me : current + ":" + me;
                Settings.Secure.putString(ctx.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
            }
            Settings.Secure.putInt(ctx.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            AppLog.i(TAG, "已通过 WRITE_SECURE_SETTINGS 自启用无障碍服务");
            return true;
        } catch (Throwable t) {
            AppLog.i(TAG, "自启用失败: " + t);
            return false;
        }
    }

    /** 打开系统无障碍设置页；部分车机没有该页面，退回应用详情页。 */
    public static void openSettings(Context ctx) {
        if (!allowWriteSoft()) return;          // 只读：不跳系统设置页
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            AppLog.i(TAG, "已打开系统无障碍设置页");
            return;
        } catch (Throwable t) {
            AppLog.i(TAG, "打开无障碍设置页失败: " + t);
        }
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + ctx.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Throwable t2) {
            AppLog.i(TAG, "打开应用详情页也失败: " + t2);
        }
    }

    /** 供界面提示用的状态文案。 */
    public static String describe(Context ctx) {
        if (KeepAliveAccessibilityService.isConnected()) {
            return "已开启（进程常驻 ✔）";
        }
        if (isEnabled(ctx)) {
            return "已在系统中开启，等待系统绑定…";
        }
        return "未开启";
    }

    /**
     * 是否允许向车机下发指令。软引用桥接的只读开关（com.cardash.inject.ReadOnly）：
     * 独立 App 里没有那个类 ⇒ catch 分支，保持原行为（允许）。
     */
    private static boolean allowWriteSoft() {
        try {
            Class<?> c = Class.forName("com.cardash.inject.ReadOnly");
            Object v = c.getField("enabled").get(null);
            return !Boolean.TRUE.equals(v);
        } catch (Throwable ignored) {
            return true;
        }
    }

}

