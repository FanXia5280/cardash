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
 * ⚠️⚠️ **2026-09-23 踩的大坑，别再把这段改回"多试几种坐标写法"**：
 *
 * 之前坐标是"猜三种常见写法一起试"，其中两条是
 *   `"lat":..,"lon":..`（JSON）和 `(lon=.., lat=..)`
 * 结果车机上**到处都是这两个形状的日志**，但它们不是目的地：
 *   I/okhttp.OkHttpClient( 3287): {"data":{"lat":30.79,"lon":104.06},...}   ← 某 App 上报 GPS
 *   I/miniApp-- HeatTemp: gps:GPS(lon=104.04, lat=30.80)                    ← 另一个 App 上报 GPS
 * 于是「目的地」变成了**车机自己几公里前的 GPS 位置**：
 *   - IPA 算出来的路线是从当前点往回开 → 和车机导航的路线完全不是一个东西；
 *   - 车一动坐标就变 → 看着就像「IPA 自己在改目的地」。
 *
 * 现在只认 D.apk 语音助手 NLU 日志里那两条**真·目的地**字段（字符串池里已确认的原式）：
 *   "dest":"lat,lon"        （纬度在前）
 *   "destNavi":"lat,lon"    （纬度在前）
 * 它们只出现在「导航到 XXX」的语义日志里，不会跟 GPS 上报混淆。
 *
 * 副作用（已知并接受）：不喊语音导航就大概率没有目的地 → IPA 不再画路线，
 * 只显示跟随车机的地图。要彻底解决得走车机原生导航的 IPC（见 S05Navi）。
 */
public final class DestSignals {

    /** 已确认：D.apk 字符串池里的原式 */
    private static final Pattern DEST_NAME = Pattern.compile("\"destName\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * 目的地坐标。**只认语音 NLU 日志里的这两条**，两种都是「纬度,经度」。
     * 顺序：先 destNavi 再 dest（两者都命中时 destNavi 更明确）。
     */
    private static final Pattern[] COORD_PATTERNS = {
            // "destNavi":"30.801,104.049"
            Pattern.compile("\"destNavi\"\\s*:\\s*\"\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*\""),
            // "dest":"30.801,104.049"
            Pattern.compile("\"dest\"\\s*:\\s*\"\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*\""),
    };

    /** 目的地的坐标和名字必须来自同一次导航会话：名字出现后 5 分钟内的坐标才算数 */
    private static final long COORD_TRUST_MS = 5 * 60 * 1000L;

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
    /** 上一次「目的地名字」出现的时间。坐标必须在这之后的 COORD_TRUST_MS 内才算数 */
    private static volatile long nameAt;
    private static volatile String lastMatchedAt;

    private DestSignals() { }

    /**
     * 每读一行 logcat 就喂进来。
     * 解析很便宜（先做子串粗筛，命中才跑正则），不会拖慢主循环。
     */
    public static void onLine(String line) {
        if (line == null || line.isEmpty()) return;
        // 粗筛：只有带 "dest" / "Dest" 的行才值得跑正则。
        // ⚠️ 千万别在这里放 "lat" / "Coord" —— 那正是上次把 GPS 上报
        // 当成目的地的入口（见类注释）。
        if (line.indexOf("dest") < 0 && line.indexOf("Dest") < 0) return;

        boolean hit = false;

        Matcher m = DEST_NAME.matcher(line);
        if (m.find()) {
            String name = m.group(1).trim();
            if (!name.isEmpty() && !name.equals(lastDestName)) {
                lastDestName = name;
                nameAt = System.currentTimeMillis();
                lastAt = nameAt;
                // 换了目的地：把上一次的坐标丢掉，
                // 免得「新名字 + 旧坐标」又凑出一条错路线
                lastLat = null;
                lastLon = null;
                lastMatchedAt = "destName";
                StateHub.get().setSource("dest", "name");
                Diagnostics.log("目的地(名称) = " + name);
            }
            hit = true;
        }

        for (int i = 0; i < COORD_PATTERNS.length; i++) {
            Matcher c = COORD_PATTERNS[i].matcher(line);
            if (!c.find()) continue;
            hit = true;
            try {
                // 两条正则都是「纬度,经度」
                if (lastDestName == null) continue;                       // 没名字不采信
                if (System.currentTimeMillis() - nameAt > COORD_TRUST_MS) continue;

                Double lat = Double.valueOf(c.group(1));
                Double lon = Double.valueOf(c.group(2));
                if (!plausible(lat, lon)) continue;
                if (lastLat == null || Math.abs(lat - lastLat) > 1e-6
                        || Math.abs(lon - lastLon) > 1e-6) {
                    lastLat = lat;
                    lastLon = lon;
                    lastAt = System.currentTimeMillis();
                    lastMatchedAt = "dest#" + i;
                    StateHub.get().setSource("dest", "name+coord#" + i);
                    Diagnostics.log("目的地(坐标) = " + lat + "," + lon);
                }
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

        sb.append("  说明       = 只认语音 NLU 的 \"dest\"/\"destNavi\"（2026-09-23 起）").append('\n');
        sb.append("               GPS 上报的 lat/lon 一律不当目的地 —— 那会导致 IPA 路线乱跳").append('\n');

        sb.append("\n【命中的原始日志行】\n");
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
