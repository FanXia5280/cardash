package com.cardash.inject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
 */
public final class LogcatSignals {

    /**
     * 抓 topic、属性 ID、数值三段。
     * 对应 D.apk 内部那条更宽松的正则，这里收紧到只认我们需要的字段。
     */
    private static final Pattern PATTERN = Pattern.compile(
            "([A-Za-z][\\w\\-]*(?:/[\\w\\-]+)+)\\s*,\\s*value\\s*:\\s*CarPropertyValue\\{"
                    + "id=(0x[0-9a-fA-F]+)\\s*,\\s*value=([^,}]*)");

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

    private volatile boolean running;
    private Process process;

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
                    // 快速排除绝大多数无关行，避免每行都跑正则
                    if (line.indexOf("CarPropertyValue") < 0) continue;
                    try {
                        handleLine(hub, line);
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

    private void handleLine(StateHub hub, String line) {
        Matcher m = PATTERN.matcher(line);
        if (!m.find()) return;

        String topic = m.group(1);
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

        hub.logcatLines++;
        hub.logcatUpdatedAt = System.currentTimeMillis();
        hub.setSource("logcat", "running");
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
