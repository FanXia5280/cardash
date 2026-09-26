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

        // 保活诊断：系统应用不会被车机后台清理杀掉，普通三方应用会被 force-stop
        // （LogcatKeeper/AccessibilityKeepAlive 等保活机关已随 2026-09-27 的只读化移除）
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(getPackageName(), 0);
            boolean isSystem = (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            AppLog.i("App", "系统应用=" + isSystem
                    + (isSystem ? "  ← 已具备不被杀的条件" : "  ← 普通应用，可能被车机 force-stop，建议装进 /system/app"));
        } catch (Throwable ignored) {
        }
    }
}
