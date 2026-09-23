package com.cardash.inject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 车机导航的目的地。
 *
 * 挖自 D.apk 的 {@code com.deepalhome.launcher.util.AssistantUtil}：
 * 它注册了一个 logcat 监听器
 * （{@code LogcatUtil.addOnLogcatPrintListener(Function1, ListenerFilter)}），
 * 用一组正则从**车机语音助手的 NLU 日志**里解析出目的地：
 *
 * <pre>
 *   destNamePattern   "destName":"([^"]+)"     目的地名称（已确认）
 *   destNaviPattern                             是否开始导航
 *   destPattern                                 目的地整体
 *   queryPattern / queryPlainPattern            用户说的话
 *
 *   AssistantUtil.ParsedLog {
 *       String destName;
 *       Coord coord;        // { double lat; double lon; }
 *       boolean isStartNavi;
 *       String query;
 *   }
 * </pre>
 *
 * 我们和 D.apk 在同一个进程，自己也有一套 logcat 读取器，
 * 所以直接套同一套正则，不用去反射它那个 private 的 parseNluLog。
 *
 * ⚠️ 只有 destName 那条正则是从字符串池里确认过的，
 * **坐标那部分的真实格式还不知道**（猜了三种常见写法一起试）。
 * 所以每次匹配到的**原始日志行**都会进环形缓冲，在 /logcat 里能看到 ——
 * 拿到真实格式就可以把解析补准，而不是靠猜。
 */
public final class DestSignals {

    /** 已确认：D.apk 字符串池里的原式 */
    private static final Pattern DEST_NAME = Pattern.compile("\"destName\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * 坐标。真实格式未知，三种常见写法一起试。
     * 只要哪条能出数，/logcat 里的原始行就能告诉我们该留哪个。
     */
    private static final Pattern[] COORD_PATTERNS = {
            // JSON: "lat":31.23,"lon":121.47   （可能叫 lat/latitude/lng/lon）
            Pattern.compile("\"lat(?:itude)?\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*\"(?:lon|lng|longitude)\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)"),
            // 日志风格: lat=31.23, lon=121.47
            Pattern.compile("\\blat(?:itude)?\\s*[=:]\\s*(-?\\d+(?:\\.\\d+)?)\\s*[, ]+\\s*(?:lon|lng|longitude)\\s*[=:]\\s*(-?\\d+(?:\\.\\d+)?)"),
            // D.apk 的 Coord.toString(): Coord(lat=31.23, lon=121.47) 或 Coord(lon=121.47, lat=31.23)
            Pattern.compile("\\(\\s*lon\\s*=\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*lat\\s*=\\s*(-?\\d+(?:\\.\\d+)?)"),
    };

    /** 开始导航的信号词（车机说要导航了） */
    private static final Pattern START_NAVI = Pattern.compile("\"isStartNavi\"\\s*:\\s*true");

    private static final int LOG_MAX = 25;

    /** 给 /logcat 用的原始行缓冲，用来校准坐标格式 */
    private static final java.util.ArrayDeque<String> RAW =
            new java.util.ArrayDeque<>();

    private static volatile String lastDestName;
    private static volatile Double lastLat;
    private static volatile Double lastLon;
    private static volatile long lastAt;
    private static volatile String lastMatchedAt;

    private DestSignals() { }

    /**
     * 每读一行 logcat 就喂进来。
     * 解析很便宜（先做子串粗筛，命中才跑正则），不会拖慢主循环。
     */
    public static void onLine(String line) {
        if (line == null || line.isEmpty()) return;
        if (line.indexOf("destName") < 0
                && line.indexOf("lat") < 0
                && line.indexOf("Coord") < 0) {
            return;
        }

        boolean hit = false;

        Matcher m = DEST_NAME.matcher(line);
        if (m.find()) {
            String name = m.group(1).trim();
            if (!name.isEmpty() && !name.equals(lastDestName)) {
                lastDestName = name;
                lastAt = System.currentTimeMillis();
                lastMatchedAt = "destName";
                StateHub.get().setSource("dest", "name");
                Diagnostics.log("目的地(名称) = " + name);
            }
            hit = true;
        }

        for (int i = 0; i < COORD_PATTERNS.length; i++) {
            Matcher c = COORD_PATTERNS[i].matcher(line);
            if (!c.find()) continue;
            try {
                Double a = Double.valueOf(c.group(1));
                Double b = Double.valueOf(c.group(2));
                Double lat, lon;
                if (i == 2) {                    // 这条是 lon, lat 顺序
                    lon = a; lat = b;
                } else {
                    lat = a; lon = b;
                }
                if (!plausible(lat, lon)) continue;
                if (lastLat == null || Math.abs(lat - lastLat) > 1e-6
                        || Math.abs(lon - lastLon) > 1e-6) {
                    lastLat = lat;
                    lastLon = lon;
                    lastAt = System.currentTimeMillis();
                    lastMatchedAt = "coord#" + i;
                    StateHub.get().setSource("dest", "name+coord#" + i);
                    Diagnostics.log("目的地(坐标) = " + lat + "," + lon);
                }
                hit = true;
                break;
            } catch (Throwable ignored) {
                // 数值不对就试下一条
            }
        }

        if (START_NAVI.matcher(line).find()) {
            StateHub.get().setSource("destStart", String.valueOf(System.currentTimeMillis()));
            hit = true;
        }

        if (hit) record(line);
    }

    /** 经纬度合理性检查：挡掉 0,0 和明显不是中国范围的数 */
    private static boolean plausible(double lat, double lon) {
        if (lat == 0 && lon == 0) return false;
        return lat > 3.5 && lat < 53.6 && lon > 73.5 && lon < 135.1;
    }

    private static void record(String line) {
        String s = line.length() > 160 ? line.substring(0, 160) + "…" : line;
        synchronized (RAW) {
            if (!s.equals(RAW.peekLast())) {
                RAW.addLast(s);
                while (RAW.size() > LOG_MAX) RAW.pollFirst();
            }
        }
    }

    // ─────────────────────────────────────── 对外

    public static String destName() { return lastDestName; }

    public static Double lat() { return lastLat; }

    public static Double lon() { return lastLon; }

    /** 目的地信息还新不新鲜（30 分钟）。过期就不画路线了，免得显示上一次的。 */
    public static boolean fresh() {
        return lastAt > 0 && (System.currentTimeMillis() - lastAt) < 30 * 60 * 1000L;
    }

    public static long at() { return lastAt; }

    /** 给 /logcat 用 */
    public static String report() {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("  目的地名称 = ").append(lastDestName == null ? "--" : lastDestName).append('\n');
        sb.append("  目的地坐标 = ");
        if (lastLat == null || lastLon == null) {
            sb.append("--（还没解析出来，看下面的原始行校准格式）");
        } else {
            sb.append(lastLat).append(", ").append(lastLon);
        }
        sb.append('\n');
        sb.append("  命中规则   = ").append(lastMatchedAt == null ? "--" : lastMatchedAt).append('\n');
        sb.append("  距上次更新 = ").append(lastAt == 0 ? "从未"
                : ((System.currentTimeMillis() - lastAt) / 1000) + " 秒前").append('\n');

        sb.append("\n【命中的原始日志行（用来校准坐标格式）】\n");
        synchronized (RAW) {
            if (RAW.isEmpty()) {
                sb.append("  （还没有。用车机语音说「导航到 XXX」试试）\n");
            } else {
                for (String s : RAW) sb.append("  ").append(s).append('\n');
            }
        }
        return sb.toString();
    }
}
