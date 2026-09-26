package com.s05.hudtraffic;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.os.Build;

/** 应用入口：装上全局崩溃捕获，并打印保活相关的关键信息。 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        CrashHandler.install(this);
        AppLog.i("App", "进程启动 | Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ") | " + Build.MANUFACTURER + " " + Build.MODEL);

        // D 应用同款：常驻 logcat 子进程，抬升进程活跃度，降低被清理概率
        LogcatKeeper.start(this);

        // 保活诊断：系统应用不会被车机后台清理杀掉，普通三方应用会被 force-stop
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(getPackageName(), 0);
            boolean isSystem = (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            boolean priv = AccessibilityKeepAlive.hasWriteSecureSettings(this);
            AppLog.i("App", "系统应用=" + isSystem + "  WRITE_SECURE_SETTINGS=" + priv
                    + (isSystem ? "  ← 已具备不被杀的条件" : "  ← 普通应用，可能被车机 force-stop，建议装进 /system/app"));
        } catch (Throwable ignored) {
        }
    }
}
