package com.s05.hudtraffic;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.InputStream;

/**
 * 常驻 logcat 子进程保活 —— 照抄 D 应用的 {@code LogcatMonitorService} 思路。
 *
 * <p>D 的做法是一个前台服务里常驻跑 {@code logcat}（{@code LogcatUtil.start()}）。
 * 这里做同样的事：起一个 {@code logcat} 子进程并持续读它的输出（读到就丢弃）。
 * 好处有两个：</p>
 * <ul>
 *   <li>进程里多了一个"正在工作"的子进程，很多车机的后台清理会跳过这类进程；</li>
 *   <li>把主进程的优先级/活跃度抬起来，配合前台服务更难被杀。</li>
 * </ul>
 *
 * <p>说明：普通应用拿不到 READ_LOGS，所以 logcat 只会输出本应用自己的日志，
 * 但这不影响效果 —— 只要子进程活着、管道一直有数据流即可。</p>
 */
public final class LogcatKeeper {

    private static final String TAG = "LogcatKeeper";
    private static final long CHECK_INTERVAL_MS = 30000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static Context appContext;
    private static Process process;
    private static Thread readerThread;
    private static volatile boolean running = false;

    private LogcatKeeper() {
    }

    private static final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            try {
                boolean need = appContext != null
                        && (Prefs.isHudEnabled(appContext) || Prefs.isSimulate(appContext));
                if (need) {
                    ensureRunning();
                } else {
                    stop();
                }
            } catch (Throwable ignored) {
            }
            MAIN.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    public static void start(Context ctx) {
        if (ctx != null) {
            appContext = ctx.getApplicationContext();
        }
        MAIN.removeCallbacks(watchdog);
        MAIN.postDelayed(watchdog, 5000L);
    }

    private static synchronized void ensureRunning() {
        if (running && process != null && isAlive(process)) {
            return;
        }
        stop();
        try {
            // 只收警告以上级别，音量很小；目的是让子进程一直活着
            process = Runtime.getRuntime().exec(new String[]{
                    "logcat", "-v", "time", "*:W"});
            running = true;
            final InputStream in = process.getInputStream();
            readerThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    byte[] buf = new byte[4096];
                    try {
                        while (running) {
                            int n = in.read(buf);
                            if (n < 0) {
                                break;
                            }
                            // 读到就丢，只为了保持管道有数据流
                        }
                    } catch (Throwable ignored) {
                    }
                    running = false;
                }
            }, "logcat-keeper");
            readerThread.setDaemon(true);
            readerThread.start();
            AppLog.i(TAG, "logcat 子进程已启动（保活用）");
        } catch (Throwable t) {
            running = false;
            AppLog.i(TAG, "启动 logcat 子进程失败: " + t);
        }
    }

    private static boolean isAlive(Process p) {
        try {
            // exitValue 抛异常说明进程还在
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static synchronized void stop() {
        running = false;
        Process p = process;
        process = null;
        if (p != null) {
            try {
                p.destroy();
            } catch (Throwable ignored) {
            }
        }
        readerThread = null;
    }

    public static boolean isRunning() {
        return running;
    }
}
