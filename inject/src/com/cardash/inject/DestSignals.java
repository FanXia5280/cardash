package com.cardash.inject;

import android.content.Intent;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 车机导航的目的地。
 *
 * <p>有**两条**来源，优先级不同：
 *
 * <ol>
 *   <li><b>高德车机版的广播</b>（{@link #SRC_AMAP}）—— **权威源**。
 *       用户 2026-09-24 的要求：「车机端手动导航 IPA 也要能识别，不是只有语音导航」。
 *       手动在屏幕上点导航时没有任何语音日志，但高德自己会把整份路线信息广播出来，
 *       目的地就在里面（{@link #onAmapBroadcast}）。</li>
 *   <li><b>车机语音助手的 NLU 日志</b>（{@link #SRC_VOICE}）—— 兜底。
 *       就是原来那条路（挖自 D.apk 的 {@code AssistantUtil}），只覆盖「喊语音导航」。</li>
 * </ol>
 *
 * <h3>语音那条的原始说明（挖 D.apk 的结论，别改回去）</h3>
 *
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
 * <h3>2026-09-24 这一轮改了什么（用户要求「中间不中断导航、不导航到其他目的地」）</h3>
 *
 * <ul>
 *   <li>新增高德广播这条权威源（手动导航也能认出来，见 {@link #onAmapBroadcast}）。</li>
 *   <li><b>权威源在场时，语音那条完全不参与</b>：防止"别的语音指令带出 destName"
 *       把正在导航的目的地改掉（用户担心的正是这个）。</li>
 *   <li>同一个地方（100 米内）**不更新坐标** —— 一更新 iPhone 那边就会重新算路，
 *       表现就是"导航闪一下从头再来"。</li>
 *   <li>导航会话由 {@link #endSession()}（看门狗判定真的结束了）来收尾，
 *       权威源不再靠 30 分钟墙钟过期 —— 那会让长途（>30 分钟）必然掉导航。</li>
 * </ul>
 */
public final class DestSignals {

    // ─────────────────────────────────────── 来源标志

    /** 车机语音助手 NLU 日志（兜底源） */
    public static final String SRC_VOICE = "voice";
    /** 高德车机版广播（权威源） */
    public static final String SRC_AMAP = "amap";

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
    /** 数据来源：null = 没有，见 SRC_VOICE / SRC_AMAP */
    private static volatile String source;

    private DestSignals() { }

    // ─────────────────────────────────────── 来源一：语音 NLU 日志（兜底）

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

        // 已经有高德广播给的权威目的地时，语音这条**一律不参与** ——
        // 否则任何一条带 "destName" 的日志（别的进程也可能打）都能把
        // 正在导航的目的地改掉，那正是用户要求避免的「导航到其他目的地」。
        boolean locked = SRC_AMAP.equals(source);

        boolean hit = false;

        Matcher m = DEST_NAME.matcher(line);
        if (m.find()) {
            String name = m.group(1).trim();
            if (!locked && !name.isEmpty() && !name.equals(lastDestName)) {
                lastDestName = name;
                nameAt = System.currentTimeMillis();
                lastAt = nameAt;
                // 换了目的地：把上一次的坐标丢掉，
                // 免得「新名字 + 旧坐标」又凑出一条错路线
                lastLat = null;
                lastLon = null;
                source = SRC_VOICE;
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
                if (locked) continue;
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
                    source = SRC_VOICE;
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

    // ─────────────────────────────────────── 来源二：高德广播（权威）

    /** 同一个地方的去重阈值（度）：纬度 0.001° ≈ 111 米 */
    private static final double SAME_DEST_DEG = 0.001;

    /** 目的地名字的候选字段（归一化后比对，见 normKey） */
    private static final Set<String> NAME_KEYS = new HashSet<>();
    /** 目的地纬度 */
    private static final Set<String> LAT_KEYS = new HashSet<>();
    /** 目的地经度（含高德自己拼错过的 LONGUTUDE，别删） */
    private static final Set<String> LON_KEYS = new HashSet<>();
    /**
     * **只有这些 key 里的字符串载荷才当成「路线信息」来挖目的地**。
     *
     * ⚠️ 绝不能去扫 {@code EXTRA_POI_RESULT} 那类**搜索结果**：用户只是在车机上
     * 搜了个地方、并没有去导航，而 IPA 会把目的地改过去 —— 这正是用户要求
     * 避免的「导航到其他目的地」。
     */
    private static final Set<String> ROUTE_PAYLOAD_KEYS = new HashSet<>();

    static {
        // 字段名是在后装高德 com.wt.mahjong 9.5.0.600013 的字符串池里确认的。
        // 同一份数据有**三套写法**（不同版本/不同载荷里换过名字）：
        //   TO_POI_NAME / toPoiName / ToPoiName
        // 所以统一走 normKey（大写 + 只留字母数字 + 去掉 EXTRA 前缀）再比，
        // 上面三种写法会落到同一个 key 上。
        for (String s : new String[]{
                "TO_POI_NAME", "END_POI_NAME", "DEST_NAME", "DESTINATION_NAME",
                "TO_POI_ADDR", "END_POI_ADDR", "DEST_ADDRESS",
        }) NAME_KEYS.add(normKey(s));

        for (String s : new String[]{
                "TO_POI_LATITUDE", "TO_POI_LAT", "END_POI_LATITUDE", "END_POI_LAT",
                "DEST_LATITUDE", "DEST_LAT", "DESTINATION_LATITUDE", "END_LATITUDE", "END_LAT",
        }) LAT_KEYS.add(normKey(s));

        for (String s : new String[]{
                "TO_POI_LONGITUDE", "TO_POI_LONGUTUDE", "TO_POI_LON",
                "END_POI_LONGITUDE", "DEST_LONGITUDE", "DEST_LON",
                "DESTINATION_LONGITUDE", "END_LONGITUDE", "END_LON",
        }) LON_KEYS.add(normKey(s));

        for (String s : new String[]{
                "NAVI_INFO", "PLAN_ROUTE", "ROUTE", "AUTO_BACK_NAVI_DATA",
                "SEND2CAR_DATA", "ROUTE_REFRESH_INFO", "NAVI_REROUTE_RESULT",
        }) ROUTE_PAYLOAD_KEYS.add(normKey(s));
    }

    /**
     * 字段名归一化：大写、只留字母数字、去掉 {@code EXTRA} 前缀。
     *
     * 这样 {@code TO_POI_LATITUDE} / {@code toPoiLatitude} / {@code ToPoiLatitude} /
     * {@code EXTRA_TO_POI_LATITUDE} 四种写法会落到同一个 key 上。
     */
    static String normKey(String k) {
        if (k == null) return "";
        StringBuilder b = new StringBuilder(k.length());
        for (int i = 0; i < k.length(); i++) {
            char c = Character.toUpperCase(k.charAt(i));
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) b.append(c);
        }
        String s = b.toString();
        if (s.startsWith("EXTRA")) s = s.substring(5);
        return s;
    }

    /** 一次广播里挖到的目的地 */
    private static final class Hit {
        String name;
        Double lat;
        Double lon;

        boolean usable() { return lat != null && lon != null; }
    }

    /**
     * 收到**任意**一条高德广播就喂进来（{@link AmapSignals} 在分发 KEY_TYPE 之前调）。
     *
     * <p>为什么要在所有 KEY_TYPE 上都试一遍：高德把路线信息放在哪个 KEY_TYPE 里
     * 各版本不一样（我们已知 10001 引导 / 10019 状态 / 60073 红绿灯），
     * 与其猜，不如"每条都翻一遍"，命中就记下来。
     *
     * <p>两种承载方式都覆盖：
     * <ol>
     *   <li>直接就是 extras（{@code TO_POI_NAME} / {@code TO_POI_LATITUDE} …）；</li>
     *   <li>整份路线信息塞在**一个字符串 extra 里**（{@code EXTRA_NAVI_INFO} /
     *       {@code EXTRA_PLAN_ROUTE} …），里面是 JSON —— 递归翻。</li>
     * </ol>
     *
     * ⚠️ 只读，绝不往高德发任何东西（用户要求：中间不干扰导航）。
     */
    public static void onAmapBroadcast(Intent intent) {
        if (intent == null) return;
        Bundle b;
        try {
            b = intent.getExtras();
        } catch (Throwable t) {
            return;
        }
        if (b == null || b.isEmpty()) return;

        Hit hit = new Hit();

        // 1) 直接 extras
        for (String k : b.keySet()) {
            Object v;
            try {
                v = b.get(k);
            } catch (Throwable t) {
                continue;
            }
            classify(k, v, hit);
        }

        // 2) 路线载荷里的 JSON
        if (!hit.usable()) {
            for (String k : b.keySet()) {
                if (!ROUTE_PAYLOAD_KEYS.contains(normKey(k))) continue;
                Object v;
                try {
                    v = b.get(k);
                } catch (Throwable t) {
                    continue;
                }
                walk(v, hit, 0);
                if (hit.usable()) break;
            }
        }

        if (hit.usable()) {
            applyAmap(hit.name, hit.lat, hit.lon);
        } else if (hit.name != null && source == null) {
            // 只有名字没有坐标：**不采信**（2026-09-24 用户要求"绝不导航到别的目的地"，
            // 拿名字去地理编码正是"目的地被改掉"的入口）。留在诊断里就好。
            StateHub.get().setSource("destAmapNameOnly", hit.name);
        }
    }

    /**
     * 把一个**直接 extra** 归类到 名字/纬度/经度。
     *
     * ⚠️ 这里**只认确切的字段名**，不做任何"往下钻"：
     * 一钻就会把 `EXTRA_POI_RESULT` 那类**搜索结果**也翻一遍 ——
     * 用户只是搜了个地方、没去导航，IPA 却把目的地改过去了，
     * 正是用户要求避免的「导航到其他目的地」。
     * 载荷类的（JSON / 嵌套 Bundle）只从 {@link #ROUTE_PAYLOAD_KEYS} 那几个 key 进
     *（见 {@link #onAmapBroadcast} 的第 2 步）。
     */
    private static void classify(String key, Object value, Hit hit) {
        String n = normKey(key);
        if (n.isEmpty()) return;
        if (NAME_KEYS.contains(n)) {
            hit.name = firstNonEmpty(hit.name, asString(value));
        } else if (LAT_KEYS.contains(n)) {
            hit.lat = firstNonNull(hit.lat, asDouble(value));
        } else if (LON_KEYS.contains(n)) {
            hit.lon = firstNonNull(hit.lon, asDouble(value));
        }
    }

    /**
     * 递归翻 JSON 载荷找目的地字段。
     *
     * ⚠️ 命中的字段**不往下钻**（值已经是名字/坐标），没命中的才继续 ——
     * 否则 {@code TO_POI_NAME} 里如果正好嵌了个 json 就会重复处理。
     */
    private static void walk(Object node, Hit hit, int depth) {
        if (node == null || hit.usable() || depth > 6) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                Object v;
                try {
                    v = o.opt(k);
                } catch (Throwable t) {
                    continue;
                }
                String n = normKey(k);
                if (NAME_KEYS.contains(n)) {
                    hit.name = firstNonEmpty(hit.name, asString(v));
                } else if (LAT_KEYS.contains(n)) {
                    hit.lat = firstNonNull(hit.lat, asDouble(v));
                } else if (LON_KEYS.contains(n)) {
                    hit.lon = firstNonNull(hit.lon, asDouble(v));
                } else {
                    walk(v, hit, depth + 1);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            int n = Math.min(a.length(), 64);
            for (int i = 0; i < n; i++) {
                Object v;
                try {
                    v = a.opt(i);
                } catch (Throwable t) {
                    continue;
                }
                walk(v, hit, depth + 1);
            }
        } else if (node instanceof Bundle) {
            // 有些版本把路线信息放成嵌套 Bundle 而不是 JSON 字符串
            Bundle b = (Bundle) node;
            for (String k : b.keySet()) {
                Object v;
                try {
                    v = b.get(k);
                } catch (Throwable t) {
                    continue;
                }
                String n = normKey(k);
                if (NAME_KEYS.contains(n)) {
                    hit.name = firstNonEmpty(hit.name, asString(v));
                } else if (LAT_KEYS.contains(n)) {
                    hit.lat = firstNonNull(hit.lat, asDouble(v));
                } else if (LON_KEYS.contains(n)) {
                    hit.lon = firstNonNull(hit.lon, asDouble(v));
                } else {
                    walk(v, hit, depth + 1);
                }
            }
        } else if (node instanceof String) {
            String s = (String) node;
            if (s.indexOf('{') < 0 && s.indexOf('[') < 0) return;
            try {
                walk(new JSONObject(s), hit, depth + 1);
            } catch (Throwable ignored) {
                try {
                    walk(new JSONArray(s), hit, depth + 1);
                } catch (Throwable ignored2) {
                    // 不是 JSON 就算了
                }
            }
        }
    }

    /**
     * 记下高德给的目的地。
     *
     * ⚠️ 同一个地方（100 米内）**只刷新时间戳、不动坐标**：坐标一变，
     * iPhone 那边就会重新算路 → 用户看到的是"导航闪一下从头再来"。
     * 高德在导航途中会反复广播同一份路线（每秒级的引导也会带上），
     * 不做这层去重的话每一帧都会重算。
     */
    private static void applyAmap(String name, Double lat, Double lon) {
        if (lat == null || lon == null || !plausible(lat, lon)) return;
        long now = System.currentTimeMillis();

        boolean samePlace = lastLat != null && lastLon != null
                && Math.abs(lat - lastLat) < SAME_DEST_DEG
                && Math.abs(lon - lastLon) < SAME_DEST_DEG;
        if (samePlace) {
            lastAt = now;
            if (!SRC_AMAP.equals(source)) promoteToAmap(name);
            return;
        }

        lastLat = lat;
        lastLon = lon;
        lastAt = now;
        nameAt = now;
        if (name != null && !name.isEmpty()) lastDestName = name;
        source = SRC_AMAP;
        lastMatchedAt = "amap";
        StateHub.get().setSource("dest", "amap:" + (lastDestName == null ? "?" : lastDestName));
        Diagnostics.log("目的地(高德广播) = " + lastDestName + "  " + lat + "," + lon);
    }

    /** 语音源确认到的目的地正好是高德报的那个地方 → 升格成权威源（会话更稳）。 */
    private static void promoteToAmap(String name) {
        source = SRC_AMAP;
        if (name != null && !name.isEmpty()) lastDestName = name;
        lastMatchedAt = "amap";
        StateHub.get().setSource("dest", "amap:" + (lastDestName == null ? "?" : lastDestName));
    }

    /**
     * 导航会话结束（{@link AmapSignals#tick()} 的看门狗判定真的结束了才调）。
     *
     * <p>只清**权威源**那边的目的地：语音源走自己的 30 分钟窗口，
     * 因为我们无法确定用户是不是压根没用高德（那 {@link AmapSignals#navigating()} 一直是 false，
     * 一刀切会把语音那条也清掉，等于把「喊语音导航」这个能用功能弄坏）。
     */
    public static void endSession() {
        if (!SRC_AMAP.equals(source)) return;
        source = null;
        lastDestName = null;
        lastLat = null;
        lastLon = null;
        lastAt = 0;
        nameAt = 0;
        lastMatchedAt = "会话结束";
        StateHub.get().setSource("dest", "会话结束");
        Diagnostics.log("目的地(高德广播) 已随导航会话结束清空");
    }

    // ─────────────────────────────────────── 小工具

    /** 经纬度合理性检查：挡掉 0,0 和明显不是中国范围的数 */
    private static boolean plausible(double lat, double lon) {
        if (lat == 0 && lon == 0) return false;
        return lat > 3.5 && lat < 53.6 && lon > 73.5 && lon < 135.1;
    }

    private static String asString(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
    }

    private static Double asDouble(Object v) {
        if (v == null) return null;
        try {
            if (v instanceof Number) return ((Number) v).doubleValue();
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) return null;
            return Double.valueOf(s);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.isEmpty()) return a;
        return b;
    }

    private static Double firstNonNull(Double a, Double b) {
        return a != null ? a : b;
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

    /** 数据来源（null = 没有目的地），见 SRC_VOICE / SRC_AMAP */
    public static String source() { return source; }

    /**
     * 这份目的地能不能下发。
     *
     * <ul>
     *   <li>权威源（高德广播）：**只要还在导航就一直有效**，由
     *       {@link #endSession()} 在导航真的结束后清掉，不再用墙钟过期 ——
     *       否则长途（>30 分钟）必然中途掉导航（用户 2026-09-24 报的就是这个）。</li>
     *   <li>语音源：沿用 30 分钟窗口（讲一小时的语音导航中途会消失，是已知副作用）。</li>
     * </ul>
     *
     * 两种源都要求**有坐标**：只有名字的一律不下发（否则 iPhone 会拿名字去
     * 地理编码，可能编到完全另一个地方 —— 就是"目的地被改掉"）。
     */
    public static boolean usable() {
        if (lastLat == null || lastLon == null) return false;
        if (SRC_AMAP.equals(source)) return true;
        return fresh();
    }

    /** 语音源的新鲜度窗口（30 分钟）。权威源不用它，见 {@link #usable()}。 */
    public static boolean fresh() {
        return lastAt > 0 && (System.currentTimeMillis() - lastAt) < 30 * 60 * 1000L;
    }

    public static long at() { return lastAt; }

    /** 给 /logcat 用 */
    public static String report() {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("  来源       = ").append(source == null ? "--" : source
                + (SRC_AMAP.equals(source) ? "（高德广播，权威：手动导航也能认）" : "（语音 NLU，兜底）"))
                .append('\n');
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
        sb.append("  能不能用   = ").append(usable() ? "能（会下发给 iPhone）" : "不能").append('\n');

        sb.append("  说明       = 高德广播那条是权威源：语音这条在它面前不参与\n");
        sb.append("               只有名字没有坐标的一律不采信（防止地理编码跑到别的地方）\n");
        sb.append("               GPS 上报的 lat/lon 一律不当目的地 —— 那会导致 IPA 路线乱跳\n");

        sb.append("\n【命中的原始日志行（语音那条）】\n");
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
