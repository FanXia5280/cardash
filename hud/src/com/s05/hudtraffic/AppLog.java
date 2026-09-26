package com.s05.hudtraffic;

import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 极简全局日志：既写入内存环形缓冲（供界面展示），也实时推送给已注册的 UI。
 */
public final class AppLog {

    public interface Sink {
        void onLog(String line);
    }

    private static final Object LOCK = new Object();
    private static final CopyOnWriteArrayList<Sink> SINKS = new CopyOnWriteArrayList<>();
    private static final ArrayDeque<String> BUFFER = new ArrayDeque<>();
    private static final int MAX_LINES = 400;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 复用同一个 SimpleDateFormat（每次 new 一个在高频日志下很费），访问都在 LOCK 内 */
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private AppLog() {
    }

    public static void addSink(Sink sink) {
        if (sink != null && !SINKS.contains(sink)) {
            SINKS.add(sink);
        }
    }

    public static void removeSink(Sink sink) {
        SINKS.remove(sink);
    }

    public static void i(String tag, String msg) {
        write("[" + tag + "] " + msg);
    }

    public static void i(String msg) {
        write(msg);
    }

    public static String dump() {
        StringBuilder sb = new StringBuilder();
        synchronized (LOCK) {
            for (String s : BUFFER) {
                sb.append(s).append('\n');
            }
        }
        return sb.toString();
    }

    public static void clear() {
        synchronized (LOCK) {
            BUFFER.clear();
        }
    }

    private static void write(final String msg) {
        String line;
        synchronized (LOCK) {
            line = FMT.format(new Date()) + "  " + msg;
            BUFFER.addLast(line);
            while (BUFFER.size() > MAX_LINES) {
                BUFFER.removeFirst();
            }
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                for (Sink s : SINKS) {
                    try {
                        s.onLog(line);
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }
}
