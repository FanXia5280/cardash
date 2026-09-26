package com.s05.hudtraffic;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全局崩溃捕获：把未捕获异常的完整堆栈
 * <ul>
 *   <li>写进内存（下次打开 App 会弹窗展示，可一键复制）；</li>
 *   <li>写进 {@code Android/data/com.s05.hudtraffic/files/crash/crash-*.txt}；</li>
 *   <li>同时也打到 logcat（tag=Crash）。</li>
 * </ul>
 * 这样即使不会用 adb，也能拿到闪退原因。
 */
public final class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "Crash";

    private static volatile String lastCrash;
    private static Thread.UncaughtExceptionHandler systemHandler;

    public static void install(Context ctx) {
        if (systemHandler != null) {
            return;
        }
        Context app = ctx.getApplicationContext();
        systemHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(app));
    }

    public static String getLastCrash() {
        return lastCrash;
    }

    public static void clearLastCrash() {
        lastCrash = null;
    }

    private final Context appContext;

    private CrashHandler(Context appContext) {
        this.appContext = appContext;
    }

    @Override
    public void uncaughtException(Thread thread, Throwable ex) {
        String trace;
        try {
            trace = buildTrace(thread, ex);
        } catch (Throwable t) {
            trace = "构建堆栈失败: " + t;
        }
        lastCrash = trace;
        try {
            AppLog.i(TAG, "!! 进程崩溃 !!\n" + trace);
        } catch (Throwable ignored) {
        }
        Log.e(TAG, trace);
        writeToFile(trace);

        Thread.UncaughtExceptionHandler h = systemHandler;
        if (h != null) {
            h.uncaughtException(thread, ex);
        } else {
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(10);
        }
    }

    private String buildTrace(Thread thread, Throwable ex) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("时间   : " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        pw.println("线程   : " + (thread != null ? thread.getName() : "?"));
        pw.println("设备   : " + Build.MANUFACTURER + " " + Build.MODEL
                + " | Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        pw.println("应用   : " + appContext.getPackageName() + " v1.0");
        pw.println("----------------------------------------");
        ex.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    private void writeToFile(String trace) {
        try {
            File dir = appContext.getExternalFilesDir("crash");
            if (dir == null) {
                return;
            }
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            String name = "crash-"
                    + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
            File f = new File(dir, name);
            FileOutputStream fos = new FileOutputStream(f);
            try {
                fos.write(trace.getBytes("UTF-8"));
                fos.flush();
            } finally {
                fos.close();
            }
            Log.e(TAG, "崩溃日志已写入: " + f.getAbsolutePath());
        } catch (Throwable t) {
            Log.e(TAG, "写崩溃日志失败: " + t);
        }
    }
}
