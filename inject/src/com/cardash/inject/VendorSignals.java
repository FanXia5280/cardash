package com.cardash.inject;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 真正的取数通道：反射调用 D.apk 自带的厂商 SDK。
 *
 * ⚠️ 前面判断错了一次：以为车机像标准 AAOS 那样把 VHAL 属性变化打进 logcat，
 * 于是照着 D.apk 里的正则去解析日志。实测抓到的日志样本说明这台车
 * **压根不往 logcat 打车辆信号**（只有两条厂商服务的异常堆栈），所以档位、
 * 电量、续航、里程全是空的。
 *
 * 真正的体系是腾讯梧桐车联（Tinnove）的「虚拟车辆属性」：
 *
 *   com.openos.virtualcar.VirtualCar / VirtualCarPropertyManager   虚拟车辆属性
 *   com.tinnove.polymericservice.IPolymericService                 聚合服务，按别名取值
 *   com.deepalhome.launcher.util.CarS05InfoUtil                    D.apk 的封装层
 *
 * 而 D.apk 里已经带了完整客户端。我们注入的代码和 D.apk 同一个进程、
 * 同一个 ClassLoader，直接反射用它的类即可 —— 不需要厂商 SDK，
 * 也不需要 android.car.permission.*。
 *
 * 取值两条路，互为补充：
 *   1. CarS05InfoUtil.psGetValueSync(alias) —— 按别名直接取，最准
 *   2. CarS05InfoUtil.cacheCarS05Info 静态字段 —— D.apk 自己的缓存，最稳
 */
public final class VendorSignals {

    private static final String UTIL = "com.deepalhome.launcher.util.CarS05InfoUtil";
    private static final String CACHE = "com.deepalhome.launcher.carinfo.CacheCarS05Info";

    // ── 我们需要的别名（来自厂商完整别名表 Aliases）──
    private static final String A_SOC        = "EnergyInfo/SocPercent";
    private static final String A_SOC_ALT    = "EnergyInfo/BatterySOC";
    private static final String A_RANGE      = "EnergyInfo/RemainingMileage";
    private static final String A_RANGE_STD  = "EnergyInfo/RemainingMileageStandard";
    private static final String A_RANGE_RES  = "EnergyInfo/SocResidualRange";
    private static final String A_RANGE_LOW  = "EnergyInfo/ResidualRange";
    private static final String A_SPEED      = "DrivingInfo/Speed";
    private static final String A_GEAR       = "DrivingInfo/Gear";
    private static final String A_ODO        = "DrivingInfo/Odometer/Total";

    private static final String[] PROBE = {
            A_SOC, A_SOC_ALT, A_RANGE, A_RANGE_STD, A_RANGE_RES, A_RANGE_LOW,
            A_SPEED, A_GEAR, A_ODO,
    };

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private Object util;              // CarS05InfoUtil.INSTANCE
    private Method psGet;             // psGetValueSync(String)
    private Method startMonitor;      // startMonitor()
    private Field cacheField;         // static CacheCarS05Info cacheCarS05Info
    private HandlerThread thread;
    private Handler handler;
    private volatile boolean running;

    public void start(Context context) {
        if (running) return;
        running = true;

        final Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;

        thread = new HandlerThread("cardash-vendor");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(new Runnable() {
            @Override public void run() {
                if (connect()) {
                    startPolling();
                }
            }
        });
    }

    public void stop() {
        running = false;
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
    }

    private boolean connect() {
        StateHub hub = StateHub.get();
        try {
            Class<?> c = Class.forName(UTIL);
            util = c.getDeclaredField("INSTANCE").get(null);
            if (util == null) {
                hub.setSource("vendor", "CarS05InfoUtil.INSTANCE 为 null");
                return false;
            }

            psGet = c.getDeclaredMethod("psGetValueSync", String.class);
            psGet.setAccessible(true);

            try {
                startMonitor = c.getDeclaredMethod("startMonitor");
                startMonitor.setAccessible(true);
                startMonitor.invoke(util);
            } catch (Throwable t) {
                // 已经启动过就会走到这里，不影响
            }

            try {
                cacheField = c.getDeclaredField("cacheCarS05Info");
                cacheField.setAccessible(true);
            } catch (Throwable t) {
                cacheField = null;
            }

            Class.forName(CACHE);   // 只是确认类在

            hub.setSource("vendor", "connected");
            Diagnostics.log("厂商通道已连上: " + UTIL);
            return true;
        } catch (Throwable t) {
            hub.setSource("vendor", "unavailable");
            hub.carError = "厂商SDK: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            Diagnostics.log("厂商通道不可用: " + t);
            return false;
        }
    }

    private void startPolling() {
        Runnable poller = new Runnable() {
            private int tick;

            @Override public void run() {
                if (!running) return;
                try {
                    poll(tick++ % 4 == 0);
                } catch (Throwable ignored) {
                    // 忽略
                }
                if (running && handler != null) {
                    handler.postDelayed(this, 500L);
                }
            }
        };
        handler.post(poller);
    }

    /**
     * @param probeAliases 这一轮要不要走 IPC 按别名取。
     *                     缓存每 500ms 读一次（车速要跟得上），
     *                     别名每 2 秒探一次就够 —— 电量、续航变化很慢，
     *                     而每次 probe 都是一次阻塞式 IPC，能省则省。
     */
    private void poll(boolean probeAliases) {
        StateHub hub = StateHub.get();

        // 1) 先读 D.apk 自己的缓存 —— 纯字段读取，绝不会阻塞，
        //    档位/车速/总里程/续航都在里面，先把保底数据拿到手。
        readCache(hub);

        if (!probeAliases) return;

        // 2) 再按别名补（主要是拿电量百分比，缓存里没有这个字段）。
        //    psGetValueSync 是阻塞式 IPC，万一车机那边卡住，也只是这一轮
        //    补不到，上面缓存读到的值仍然有效 —— 顺序不能反。
        int ok = 0;
        for (String alias : PROBE) {
            Object v = psGet(alias);
            if (v == null) continue;
            if (apply(hub, alias, v)) ok++;
        }
        hub.setSource("vendor", ok > 0 ? "ps ok=" + ok : "cache-only");
    }

    private Object psGet(String alias) {
        Method m = psGet;
        Object u = util;
        if (m == null || u == null) return null;
        try {
            return m.invoke(u, alias);
        } catch (Throwable t) {
            return null;
        }
    }

    /** @return true 表示这个值真的被用上了 */
    private boolean apply(StateHub hub, String alias, Object raw) {
        if (A_SOC.equals(alias) || A_SOC_ALT.equals(alias)) {
            Double d = num(raw);
            if (d == null) return false;
            hub.soc = d <= 1.0 ? d * 100.0 : d;
            hub.setSource("soc", "vendor:" + alias);
            return true;
        }
        if (A_RANGE.equals(alias) || A_RANGE_RES.equals(alias) || A_RANGE_LOW.equals(alias)) {
            Double d = num(raw);
            if (d == null || d < 0) return false;
            hub.rangeKm = d;
            hub.setSource("range", "vendor:" + alias);
            return true;
        }
        if (A_RANGE_STD.equals(alias)) {
            Double d = num(raw);
            if (d == null || d < 0) return false;
            // 实时续航优先，标准续航只做兜底
            if (hub.rangeKm == null) {
                hub.rangeKm = d;
                hub.setSource("range", "vendor:" + alias + "(std)");
            }
            return true;
        }
        if (A_SPEED.equals(alias)) {
            Double d = num(raw);
            if (d == null || d < 0) return false;
            // 别名就带 Kmh 字样，按 km/h 处理；数值明显过小才当成 m/s
            hub.speedKmh = d < 0.5 && d > 0 && isProbablyMs(raw) ? d * 3.6 : d;
            hub.setSource("speed", "vendor:" + alias);
            return true;
        }
        if (A_GEAR.equals(alias)) {
            String g = gear(raw);
            if (g == null) {
                hub.setSource("gear", "vendor:raw=" + String.valueOf(raw));
                return false;
            }
            hub.gear = g;
            hub.setSource("gear", "vendor:" + alias);
            return true;
        }
        if (A_ODO.equals(alias)) {
            Double d = num(raw);
            if (d == null || d < 0) return false;
            hub.odometerKm = d;
            hub.setSource("odometer", "vendor:" + alias);
            return true;
        }
        return false;
    }

    /** 单位不确定时的保守判断：整数且小于等于 60 才怀疑是 m/s */
    private static boolean isProbablyMs(Object raw) {
        return raw instanceof Number && raw instanceof Integer;
    }

    // ─────────────────────────────────────── D.apk 自己的缓存

    private void readCache(StateHub hub) {
        Field f = cacheField;
        if (f == null) return;
        try {
            Object cache = f.get(null);
            if (cache == null) {
                hub.setSource("cache", "null");
                return;
            }
            hub.setSource("cache", "present");

            str(hub, cache, "gearMode", "gear");
            str(hub, cache, "speed", "speed");
            str(hub, cache, "totalDistance", "odometer");
            // 电续航优先，其次总续航，最后油续航
            if (hub.rangeKm == null) {
                str(hub, cache, "socRemainRange", "range");
            }
            if (hub.rangeKm == null) {
                str(hub, cache, "totalRemainRange", "range");
            }
            if (hub.rangeKm == null) {
                str(hub, cache, "oilRemainRange", "range");
            }
        } catch (Throwable t) {
            hub.setSource("cache", "err:" + t.getClass().getSimpleName());
        }
    }

    private void str(StateHub hub, Object cache, String field, String kind) {
        try {
            Object v = cache.getClass().getField(field).get(cache);
            if (v == null) return;
            String s = String.valueOf(v).trim();
            if (s.isEmpty() || "null".equals(s)) return;

            if ("gear".equals(kind)) {
                if (hub.gear == null) {
                    String g = gear(s);
                    if (g != null) {
                        hub.gear = g;
                        hub.setSource("gear", "cache:" + field);
                    }
                }
                return;
            }

            Double d = num(s);
            if (d == null || d < 0) return;
            if ("speed".equals(kind) && hub.speedKmh == null) {
                hub.speedKmh = d;
                hub.setSource("speed", "cache:" + field);
            } else if ("odometer".equals(kind) && hub.odometerKm == null) {
                hub.odometerKm = d;
                hub.setSource("odometer", "cache:" + field);
            } else if ("range".equals(kind) && hub.rangeKm == null) {
                hub.rangeKm = d;
                hub.setSource("range", "cache:" + field);
            }
        } catch (Throwable ignored) {
            // 字段不存在就跳过
        }
    }

    // ─────────────────────────────────────── 全量扫描（/scan）

    /**
     * 别名全量扫描。
     *
     * ⚠️ 必须异步。psGetValueSync 是阻塞式 IPC，1276 个别名逐个调过去
     * 实测会卡住 HTTP 请求（模拟器上 30 秒都没返回）。所以改成：
     * 第一次请求启动后台扫描并立刻返回进度，之后再刷新就能看到累积结果。
     */
    private final java.util.List<String> scanHits = new java.util.ArrayList<>();
    private volatile int scanDone;
    private volatile int scanTotal;
    private volatile boolean scanRunning;

    public String scanEntry(boolean includeAll) {
        if (!scanRunning && scanDone == 0) {
            startScan(includeAll);
        }
        return scanText();
    }

    private void startScan(final boolean includeAll) {
        if (util == null || psGet == null) return;
        scanRunning = true;
        scanHits.clear();
        scanDone = 0;

        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                String[] all = Aliases.all();
                java.util.List<String> todo = new java.util.ArrayList<>(all.length);
                for (String a : all) {
                    if (includeAll || a.startsWith("EnergyInfo/") || a.startsWith("DrivingInfo/")) {
                        todo.add(a);
                    }
                }
                scanTotal = todo.size();

                for (String alias : todo) {
                    if (!running) break;
                    scanDone++;
                    try {
                        Object v = psGet.invoke(util, alias);
                        if (v == null) continue;
                        String s = String.valueOf(v);
                        if (s.isEmpty() || "null".equals(s)) continue;
                        if (s.length() > 70) s = s.substring(0, 70) + "…";
                        synchronized (scanHits) {
                            scanHits.add(alias + " = " + s);
                        }
                    } catch (Throwable ignored) {
                        // 单个别名失败不影响其它
                    }
                }
                scanRunning = false;
            }
        }, "cardash-scan");
        t.setDaemon(true);
        t.start();
    }

    private String scanText() {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("厂商属性扫描\n");
        sb.append("  SDK           : ").append(util != null ? "已加载" : "未加载").append('\n');
        sb.append("  psGetValueSync: ").append(psGet != null ? "可用" : "不可用").append('\n');
        sb.append("  别名总数      : ").append(Aliases.COUNT).append('\n');
        sb.append("  进度          : ").append(scanDone).append('/').append(scanTotal)
          .append(scanRunning ? "（进行中，刷新本页看更多）" : "（已完成）").append('\n');

        sb.append("\n【CarS05InfoUtil.cacheCarS05Info】\n");
        sb.append(cacheDump());

        if (util == null || psGet == null) {
            sb.append("\n厂商 SDK 没连上，原因见 /diag 的 carError\n");
            return sb.toString();
        }

        java.util.List<String> hits;
        synchronized (scanHits) {
            hits = new java.util.ArrayList<>(scanHits);
        }
        sb.append("\n【别名取值结果（只列非空的，共 ").append(hits.size()).append(" 条）】\n");
        if (hits.isEmpty()) {
            sb.append("  （还没有。若进度卡住不动，说明 psGetValueSync 阻塞了）\n");
        } else {
            for (String h : hits) {
                sb.append("  ").append(h).append('\n');
            }
        }
        return sb.toString();
    }

    private String cacheDump() {
        Field f = cacheField;
        if (f == null) return "  （读不到 cacheCarS05Info 字段）\n";
        try {
            Object cache = f.get(null);
            if (cache == null) return "  null（D.apk 的监听还没启动）\n";
            StringBuilder sb = new StringBuilder();
            for (Field ff : cache.getClass().getFields()) {
                Object v;
                try {
                    v = ff.get(cache);
                } catch (Throwable t) {
                    continue;
                }
                if (v == null) continue;
                String s = String.valueOf(v);
                if (s.isEmpty()) continue;
                sb.append("  ").append(ff.getName()).append(" = ").append(s).append('\n');
            }
            return sb.length() == 0 ? "  （所有字段都是空的）\n" : sb.toString();
        } catch (Throwable t) {
            return "  读取失败: " + t + "\n";
        }
    }

    // ─────────────────────────────────────── 工具

    /** psGetValueSync 可能返回 Integer/Float/String/Boolean，统一抽成数字 */
    private static Double num(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof Boolean) return ((Boolean) v) ? 1.0 : 0.0;
        String s = String.valueOf(v).trim().replace(",", "");
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;
        Matcher m = NUMBER.matcher(s);
        if (!m.find()) return null;
        try {
            return Double.parseDouble(m.group());
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 档位可能是数字（VehicleGear 位掩码）也可能是 "P"/"D" 这种字符串 */
    private static String gear(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return LogcatSignals.gearName(((Number) v).intValue());

        String s = String.valueOf(v).trim().toUpperCase(Locale.US);
        if (s.isEmpty() || "null".equals(s)) return null;
        if (s.length() == 1 && "PRND".indexOf(s.charAt(0)) >= 0) return s;

        // 形如 GEAR_P / P档 / D 挡 —— 找独立出现的 P/R/N/D，避免 DISTANCE 里的 D
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ("PRND".indexOf(c) < 0) continue;
            boolean left = i == 0 || !Character.isLetter(s.charAt(i - 1));
            boolean right = i == s.length() - 1 || !Character.isLetter(s.charAt(i + 1));
            if (left && right) return String.valueOf(c);
        }
        return null;
    }
}
