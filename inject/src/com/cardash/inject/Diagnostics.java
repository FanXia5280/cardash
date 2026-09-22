package com.cardash.inject;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 落盘诊断。
 *
 * 通知栏不一定看得到、HTTP 又不一定连得上，所以启动过程的每一步都写进
 * /sdcard/CarDash/bridge.log，并维护一份 heartbeat.txt 显示当前状态 ——
 * 用任意文件管理器就能看到桥接到底有没有跑起来。
 */
public final class Diagnostics {

    private static final String LOG_TAG = "CarDash";
    private static final long MAX_LOG_BYTES = 256 * 1024L;

    private static volatile File dir;
    private static volatile File logFile;
    private static volatile boolean inited;

    private Diagnostics() { }

    public static synchronized void init(Context ctx) {
        if (inited) return;
        inited = true;

        File d = new File(Environment.getExternalStorageDirectory(), "CarDash");
        if (!usable(d)) {
            // 退而求其次：应用自己的外部目录，同样能用文件管理器访问
            File ext = null;
            try {
                ext = ctx.getExternalFilesDir(null);
            } catch (Throwable ignored) {
                // 忽略
            }
            if (ext != null && usable(ext)) {
                d = ext;
            } else {
                d = new File(ctx.getFilesDir(), "diag");
                usable(d);
            }
        }
        dir = d;
        logFile = new File(d, "bridge.log");
        log("======== 启动 ========");
    }

    private static boolean usable(File d) {
        try {
            if (!d.exists() && !d.mkdirs()) return false;
            return d.isDirectory() && d.canWrite();
        } catch (Throwable t) {
            return false;
        }
    }

    public static File dir() {
        return dir;
    }

    public static void log(String msg) {
        try {
            Log.i(LOG_TAG, msg);
        } catch (Throwable ignored) {
            // 忽略
        }
        write(logFile, stamp() + "  " + msg + "\n", true);
    }

    public static void heart(String text) {
        File d = dir;
        if (d == null) return;
        write(new File(d, "heartbeat.txt"), stamp() + "\n" + text + "\n", false);
    }

    /**
     * 读日志尾部，供 /log 接口用 —— 这样在 iPhone 浏览器里直接就能看，
     * 不用去车机的文件管理器里翻。
     */
    public static String tail(int maxChars) {
        File f = logFile;
        if (f == null) return "（日志尚未初始化）";
        if (!f.exists()) return "（日志文件不存在: " + f.getAbsolutePath() + "）";
        try {
            long len = f.length();
            int take = (int) Math.min(len, Math.max(1024, maxChars));
            byte[] buf = new byte[take];
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
            try {
                raf.seek(len - take);
                raf.readFully(buf);
            } finally {
                raf.close();
            }
            return "文件: " + f.getAbsolutePath() + "  (" + len + " 字节)\n\n"
                    + new String(buf, "UTF-8");
        } catch (Throwable t) {
            return "读取失败: " + t;
        }
    }

    private static void write(File f, String text, boolean append) {
        if (f == null) return;
        try {
            if (append && f.length() > MAX_LOG_BYTES) {
                File old = new File(f.getParentFile(), "bridge.old.log");
                if (old.exists()) old.delete();
                f.renameTo(old);
            }
            FileOutputStream out = new FileOutputStream(f, append);
            try {
                out.write(text.getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
            // 落盘失败不能影响主流程
        }
    }

    private static String stamp() {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
    }
}
