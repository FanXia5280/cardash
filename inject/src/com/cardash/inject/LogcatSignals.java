package com.cardash.inject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 核心取数模块：解析车机系统 logcat 里的车辆信号日志。
 *
 * 车机系统服务会把每次 VHAL 属性变化打到日志里，形如：
 *   MessageProperty: receive DrivingInfo/Speed, value: CarPropertyValue{id=0x11600207,
 *                    value=12.5, time=..., ext=...}
 *
 * D.apk 用的就是这个办法（它一条 android.car.permission.* 都没申请，靠 system uid 的
 * READ_LOGS 读日志）。我们注入进 D.apk 后同样持有 READ_LOGS，所以这条路是稳的。
 *
 * ⚠️ 正则必须和 D.apk 内部那条保持一致。之前自己收紧成「topic 必须带斜杠」+
 *    「id= 后面紧跟 value=」，在车机上一条都匹配不上，档位/电量/里程全空。
 *    这里改回宽松版，并保留原始样本供 /logcat 排查。
 */
public final class LogcatSignals {

    /**
     * 照抄 D.apk 里的原式，只把末尾强制的 `,\s*time=.*?ext=(.*)\}` 去掉 ——
     * 不是每个属性都带 time/ext 两段。
     *
     * D.apk 原式：
     *   (?:receive|(?:\d+\s+)?send)\s+(.+?),\s+value\s*:\s*CarPropertyValue\{
     *     id=(0x[0-9a-fA-F]+).*?value=(.*?),\s*time=.*?ext=(.*)\}
     *
     * 关键点是 `(.+?)` 和 `.*?value=` 都很宽松：topic 可以没有斜杠，
     * id 与 value 之间可以夹别的字段。
     */
    private static final Pattern PATTERN = Pattern.compile(
            "(?:receive|(?:\\d+\\s+)?send)\\s+(.+?),\\s*value\\s*:\\s*CarPropertyValue\\{"
                    + "id=(0x[0-9a-fA-F]+).*?value=([^,}]*)");

    // ── 信号名（来自 D.apk 内部的信号表）──
    private static final String T_SPEED       = "DrivingInfo/Speed";
    private static final String T_GEAR        = "DrivingInfo/Gear";
    private static final String T_ODOMETER    = "DrivingInfo/Odometer/Total";
    private static final String T_SOC         = "EnergyInfo/SocPercent";
    private static final String T_SOC_ALT     = "EnergyInfo/BatterySOC";
    private static final String T_RANGE       = "EnergyInfo/RemainingMileage";
    private static final String T_RANGE_STD   = "EnergyInfo/RemainingMileageStandard";
    private static final String T_RANGE_RES   = "EnergyInfo/SocResidualRange";
    private static final String T_IGNITION    = "DrivingInfo/IgnitionState";

    // ── 属性 ID 兜底（topic 名字变了也还能认出来）──
    private static final int ID_SPEED      = 0x11600207;
    private static final int ID_SPEED_DISP = 0x11600208;
    private static final int ID_ODOMETER   = 0x11600204;
    private static final int ID_GEAR       = 0x11400400;
    private static final int ID_GEAR_CUR   = 0x11400401;
    private static final int ID_EV_SOC     = 0x11600305;
    private static final int ID_EV_RANGE   = 0x11600309;
    private static final int ID_FUEL_LEVEL = 0x11600307;
    private static final int ID_RANGE_REM  = 0x11600308;

    /** 诊断采样上限：只留最近这些条，避免占内存 */
    private static final int SAMPLE_MAX = 40;

    private volatile boolean running;
    private Process process;

    // ── 诊断用的采样缓冲 ──
    private final Deque<String> rawSample = new ArrayDeque<>();   // 含 CarPropertyValue 的原始行
    private final Deque<String> missSample = new ArrayDeque<>();  // 含 MessageProperty 但没匹配上的
    private final Map<String, Integer> topics = new LinkedHashMap<>();

    public void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() { loop(); }
        }, "cardash-logcat");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        Process p = process;
        if (p != null) {
            try { p.destroy(); } catch (Throwable ignored) { }
        }
    }

    private void loop() {
        StateHub hub = StateHub.get();
        while (running) {
            BufferedReader reader = null;
            try {
                // 只保留最近一段，避免开机时刷出几万行旧日志
                Process p = new ProcessBuilder("logcat", "-v", "brief", "-T", "200")
                        .redirectErrorStream(true)
                        .start();
                process = p;
                hub.logcatError = null;
                hub.setSource("logcat", "running");

                reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));

                String line;
                while (running && (line = reader.readLine()) != null) {
                    // 快速排除绝大多数无关行，避免每行都跑正则。
                    // 顺带把 VehicleProperty 也收进来 —— 万一车机打的不是
                    // CarPropertyValue 而是另一种格式，采样里能看到，不至于瞎猜。
                    boolean hasProp = line.indexOf("CarPropertyValue") >= 0;
                    if (!hasProp
                            && line.indexOf("MessageProperty") < 0
                            && line.indexOf("VehicleProperty") < 0) {
                        continue;
                    }
                    try {
                        handleLine(hub, line, hasProp);
                    } catch (Throwable ignored) {
                        // 单行解析失败忽略
                    }
                }
            } catch (Throwable t) {
                hub.logcatError = t.getClass().getSimpleName() + ": " + t.getMessage();
                hub.setSource("logcat", "error");
            } finally {
                if (reader != null) {
                    try { reader.close(); } catch (Throwable ignored) { }
                }
                process = null;
            }

            if (!running) return;
            try {
                Thread.sleep(2000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void handleLine(StateHub hub, String line, boolean hasProp) {
        if (hasProp) {
            hub.logcatSeen++;
            push(rawSample, line);
        }

        Matcher m = PATTERN.matcher(line);
        if (!m.find()) {
            // 是车辆日志但没认出来 —— 采样下来，/logcat 一看就知道格式差在哪
            if (!hasProp) push(missSample, line);
            return;
        }

        String topic = m.group(1).trim();
        String idHex = m.group(2);
        String rawValue = m.group(3);

        // 有些日志带 /Set 后缀，统一去掉
        String topicKey = topic.endsWith("/Set")
                ? topic.substring(0, topic.length() - 4) : topic;

        Double value = parse(rawValue);
        if (value == null) return;

        int id = 0;
        try {
            id = (int) Long.parseLong(idHex.substring(2), 16);
        } catch (Throwable ignored) {
            // 忽略
        }

        apply(hub, topicKey, id, value);

        hub.logcatMatched++;
        hub.logcatLines++;
        hub.logcatUpdatedAt = System.currentTimeMillis();
        hub.setSource("logcat", "running");

        synchronized (topics) {
            Integer c = topics.get(topicKey);
            topics.put(topicKey, c == null ? 1 : c + 1);
        }
    }

    private void apply(StateHub hub, String topic, int id, double v) {
        // 1) 优先按信号名匹配
        if (T_SPEED.equals(topic)) {
            hub.speedKmh = v * 3.6;          // VHAL 车速单位是 m/s
            hub.setSource("speed", "logcat:" + topic);
            return;
        }
        if (T_GEAR.equals(topic)) {
            String g = gearName((int) v);
            if (g != null) {
                hub.gear = g;
                hub.setSource("gear", "logcat:" + topic);
            } else {
                hub.setSource("gear", "logcat:raw=" + (int) v);
            }
            return;
        }
        if (T_ODOMETER.equals(topic)) {
            hub.odometerKm = v / 1000.0;     // VHAL 里程单位是米
            hub.setSource("odometer", "logcat:" + topic);
            return;
        }
        if (T_SOC.equals(topic) || T_SOC_ALT.equals(topic)) {
            hub.soc = v <= 1.0 ? v * 100.0 : v;
            hub.setSource("soc", "logcat:" + topic);
            return;
        }
        if (T_RANGE.equals(topic) || T_RANGE_STD.equals(topic) || T_RANGE_RES.equals(topic)) {
            // 标准续航和实时续航都存在，实时值优先
            if (hub.rangeKm == null || !T_RANGE_STD.equals(topic)) {
                hub.rangeKm = v;
                hub.setSource("range", "logcat:" + topic);
            }
            return;
        }
        if (T_IGNITION.equals(topic)) {
            hub.ignition = v != 0;
            return;
        }

        // 2) 信号名不认识时，用属性 ID 兜底
        switch (id) {
            case ID_SPEED:
            case ID_SPEED_DISP:
                hub.speedKmh = v * 3.6;
                hub.setSource("speed", "logcat:id");
                break;
            case ID_GEAR:
            case ID_GEAR_CUR: {
                String g = gearName((int) v);
                if (g != null) {
                    hub.gear = g;
                    hub.setSource("gear", "logcat:id");
                } else {
                    hub.setSource("gear", "logcat:id raw=" + (int) v);
                }
                break;
            }
            case ID_ODOMETER:
                hub.odometerKm = v / 1000.0;
                hub.setSource("odometer", "logcat:id");
                break;
            case ID_EV_SOC:
            case ID_FUEL_LEVEL:
                hub.soc = v <= 1.0 ? v * 100.0 : v;
                hub.setSource("soc", "logcat:id");
                break;
            case ID_EV_RANGE:
            case ID_RANGE_REM:
                hub.rangeKm = v;
                hub.setSource("range", "logcat:id");
                break;
            default:
                break;
        }
    }

    private static void push(Deque<String> q, String line) {
        synchronized (q) {
            while (q.size() >= SAMPLE_MAX) q.pollFirst();
            q.addLast(line.length() > 260 ? line.substring(0, 260) + "…" : line);
        }
    }

    /** 出现次数最多的前 N 个 topic，一眼就能看出车机到底在报哪些信号。 */
    public String topicSummary(int max) {
        List<Map.Entry<String, Integer>> list;
        synchronized (topics) {
            list = new ArrayList<>(topics.entrySet());
        }
        Collections.sort(list, new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return b.getValue() - a.getValue();
            }
        });
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size() && i < max; i++) {
            if (i > 0) sb.append('\n');
            sb.append("  ").append(list.get(i).getKey())
              .append("  x").append(list.get(i).getValue());
        }
        if (sb.length() == 0) sb.append("  （一条都没认出来）");
        return sb.toString();
    }

    public int topicCount() {
        synchronized (topics) {
            return topics.size();
        }
    }

    public String rawSampleText() {
        return join(rawSample);
    }

    public String missSampleText() {
        return join(missSample);
    }

    private static String join(Deque<String> q) {
        List<String> copy;
        synchronized (q) {
            copy = new ArrayList<>(q);
        }
        if (copy.isEmpty()) return "  （空）";
        StringBuilder sb = new StringBuilder();
        for (String s : copy) sb.append("  ").append(s).append('\n');
        return sb.toString();
    }

    /** VehicleGear：1=N 2=R 4=P 8=D */
    public static String gearName(int g) {
        if ((g & 0x0004) != 0) return "P";
        if ((g & 0x0002) != 0) return "R";
        if ((g & 0x0001) != 0) return "N";
        if ((g & 0x0008) != 0) return "D";
        return null;
    }

    private static Double parse(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        // 有些值带类型前缀，例如 Int32Vec{...}、Float(1.0)
        int brace = t.indexOf('{');
        if (brace >= 0 && brace + 1 < t.length()) {
            t = t.substring(brace + 1);
            int end = t.indexOf('}');
            if (end >= 0) t = t.substring(0, end);
            int comma = t.indexOf(',');
            if (comma >= 0) t = t.substring(0, comma);
            t = t.trim();
        }
        int paren = t.indexOf('(');
        if (paren >= 0 && t.endsWith(")")) {
            t = t.substring(paren + 1, t.length() - 1).trim();
        }
        try {
            return Double.parseDouble(t);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
