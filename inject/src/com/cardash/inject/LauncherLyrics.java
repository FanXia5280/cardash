package com.cardash.inject;

import java.lang.reflect.Field;
import java.util.List;

/**
 * 直接读 D.apk 已经算好的歌词。
 *
 * D.apk 自己有一整套歌词设施：
 *   music/broadcast/LyricBroadcastDispatcher   单例，持有当前时间线
 *   music/broadcast/LyricTimelineEngine        LRC / Apple Music TTML 解析
 *   music/BroadcastLyricProvider               广播歌词
 *   music/LyricChineseConverter                简繁转换
 *
 * 它拉回来的歌词已经解析成带时间戳的时间线了：
 *   currentTimeline = List&lt;LyricTimelineLine{ text, startMs, endMs }&gt;
 *
 * 我们和它在同一个进程，直接反射读那个 List 就行 ——
 * **不用自己联网、不用自己解析，而且和车机上显示的歌词完全一致。**
 * （自己抓 lrclib 只是兜底：车机不一定有外网，歌也不一定有同步歌词。）
 */
public final class LauncherLyrics {

    private static final String DISPATCHER =
            "com.deepalhome.launcher.music.broadcast.LyricBroadcastDispatcher";
    private static final String LINE =
            "com.deepalhome.launcher.music.broadcast.LyricTimelineLine";
    /** 最后一次广播的状态，含 currentLyric / nextLyric 两行 */
    private static final String STATE =
            "com.deepalhome.launcher.music.broadcast.LyricBroadcastState";

    private static boolean resolved;
    private static Object dispatcher;        // LyricBroadcastDispatcher.INSTANCE
    private static Field timelineField;      // currentTimeline
    private static Field stateField;         // lastBroadcastState
    private static Field lineTextField;
    private static Field lineStartField;

    private LauncherLyrics() { }

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            Class<?> dc = Class.forName(DISPATCHER);
            dispatcher = dc.getDeclaredField("INSTANCE").get(null);

            timelineField = dc.getDeclaredField("currentTimeline");
            timelineField.setAccessible(true);

            try {
                stateField = dc.getDeclaredField("lastBroadcastState");
                stateField.setAccessible(true);
            } catch (Throwable ignored) {
                stateField = null;
            }

            Class<?> lc = Class.forName(LINE);
            lineTextField = lc.getField("text");
            lineStartField = lc.getField("startMs");
        } catch (Throwable t) {
            dispatcher = null;
            Diagnostics.log("读 D.apk 歌词失败: " + t);
        }
    }

    /**
     * @return 紧凑歌词「起始秒|歌词」逐行，用 \n 连接；读不到返回 null
     */
    public static String read() {
        resolve();
        if (dispatcher == null || timelineField == null || lineTextField == null) return null;

        try {
            Object tl = timelineField.get(dispatcher);
            if (!(tl instanceof List)) return null;
            List<?> list = (List<?>) tl;
            if (list.isEmpty()) return null;

            StringBuilder sb = new StringBuilder(4096);
            int n = 0;
            for (Object line : list) {
                if (line == null) continue;
                Object txt = lineTextField.get(line);
                Object st = lineStartField == null ? null : lineStartField.get(line);
                if (!(txt instanceof String)) continue;
                String s = ((String) txt).trim();
                if (s.isEmpty()) continue;
                double sec = (st instanceof Number) ? (((Number) st).doubleValue() / 1000.0) : n;
                if (n++ > 0) sb.append('\n');
                sb.append(Math.round(sec * 100.0) / 100.0).append('|').append(s);
                if (sb.length() > 32 * 1024) break;   // 超长截断
            }
            return n == 0 ? null : sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 给 /logcat 用：D.apk 那边现在有多少行歌词 */
    public static String describe() {
        resolve();
        if (dispatcher == null) return "D.apk 歌词设施没找到";
        try {
            Object tl = timelineField == null ? null : timelineField.get(dispatcher);
            int n = (tl instanceof List) ? ((List<?>) tl).size() : -1;

            String cur = null, next = null;
            if (stateField != null) {
                Object st = stateField.get(dispatcher);
                if (st != null) {
                    Class<?> sc = Class.forName(STATE);
                    Object c = sc.getField("currentLyric").get(st);
                    Object x = sc.getField("nextLyric").get(st);
                    cur = c == null ? null : String.valueOf(c);
                    next = x == null ? null : String.valueOf(x);
                }
            }
            return "timeline=" + n
                    + "  当前=" + (cur == null ? "--" : cur)
                    + "  下一句=" + (next == null ? "--" : next);
        } catch (Throwable t) {
            return "err:" + t.getClass().getSimpleName();
        }
    }
}
