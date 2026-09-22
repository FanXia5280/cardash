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

    // ── 我们需要的别名 ──
    //
    // ⚠️ 实测车机**不认** EnergyInfo/SocPercent、DrivingInfo/Gear 这类名字 ——
    // 那些是厂商属性表里的名字，不是取值用的 key。
    // D.apk 真正用的是 vc_alias_*（从 CarS05InfoUtil 的 psOnlyAliases /
    // virtualCarProtectedAliases 静态字段里挖出来的）：
    // 注意：这 37 个 vc_alias_* 里**没有 SOC 百分比**，只有一个一个的续航
    // （DTE = Distance To Empty）。所以电量百分比只能按续航折算，见 poll()。
    private static final String A_RANGE      = "vc_alias_left_ev_dte";    // 剩余电续航
    private static final String A_RANGE_STD  = "vc_alias_disp_dte";       // 显示续航
    private static final String A_RANGE_LOW  = "vc_alias_e_dte";          // 电续航
    private static final String A_SPEED      = "vc_alias_vehicle_speed";
    private static final String A_GEAR       = "vc_alias_vehicle_gear";
    private static final String A_ODO        = "vc_alias_journey_all_distance";
    private static final String A_DRIVE      = "vc_alias_drive_style";
    private static final String A_TIRE_FL    = "vc_alias_tire_pressure";

    private static final String[] PROBE = {
            A_SPEED, A_GEAR, A_ODO, A_RANGE, A_RANGE_STD, A_RANGE_LOW, A_DRIVE,
    };

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private Object util;              // CarS05InfoUtil.INSTANCE
    private Method psGet;             // psGetValueSync(String)
    private Method startMonitor;      // startMonitor()
    private Field cacheField;         // static CacheCarS05Info cacheCarS05Info
    private Class<?> utilClass;

    /** CarS05InfoUtil.virtualCarPropertyManager —— 直接按属性 ID 取值 */
    private Object mgr;
    private Method getValueMethod;

    // D.apk 的三个私有初始化方法。实测它们不一定成功（虚拟车辆管理器为 null、
    // 聚合服务 psAvailable=false），而 vc_alias_vehicle_gear 只走聚合服务 ——
    // 这就是「挂了 D 档但档位不更新」的根因。所以这里主动替它补一遍。
    private Method bindVcarMgr;       // bindVirtualCarPropertyManager()
    private Method connectPs;         // connectPolymericService(boolean)
    private Method registerVcarCb;    // registerVirtualCarCallbacks()
    private int bindAttempts;

    private android.content.Context appCtx;
    private HandlerThread thread;
    private Handler handler;
    private volatile boolean running;

    // ── 车速：只认原车数据，三个来源按新鲜度排序 ──
    //   1) vc_alias_vehicle_speed 实时推送          → speedAliasLive
    //   2) CarS05InfoUtil.currentDrivingSpeedKmh    → speedFieldLive（看它会不会变）
    //   3) cacheCarS05Info.speed 格式化滞后快照      → 仅当前两个都没有时兜底
    // 明确不使用高德广播的 CUR_SPEED（第三方 GPS 推算，和仪表盘会有偏差）。
    private volatile boolean speedAliasLive;
    private volatile boolean speedFieldLive;
    private volatile double lastSpeedField = Double.NaN;
    /** 最后一次收到实时车速推送的时间。超过窗口没再收到就说明它断供了，让位给下一个来源 */
    private volatile long speedAliasAt;
    private static final long SPEED_ALIAS_WINDOW_MS = 15000;

    /** 实时推送是不是还新鲜 */
    private boolean speedAliasFresh() {
        return speedAliasLive
                && (System.currentTimeMillis() - speedAliasAt) < SPEED_ALIAS_WINDOW_MS;
    }

    public void start(Context context) {
        if (running) return;
        running = true;

        final Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        appCtx = app;
        initPrefs(app);

        thread = new HandlerThread("cardash-vendor");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(new Runnable() {
            @Override public void run() {
                if (connect()) {
                    ensureBound();
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
            utilClass = c;
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

            bindVcarMgr = priv(c, "bindVirtualCarPropertyManager");
            connectPs = priv(c, "connectPolymericService", boolean.class);
            registerVcarCb = priv(c, "registerVirtualCarCallbacks");

            // 挂进它的实时监听器列表 —— 档位这类「只走订阅」的信号全靠这个
            installLiveHooks();

            try {
                cacheField = c.getDeclaredField("cacheCarS05Info");
                cacheField.setAccessible(true);
            } catch (Throwable t) {
                cacheField = null;
            }

            // 拿到虚拟车辆属性管理器，才能按属性 ID 直接取值
            try {
                Field mf = c.getDeclaredField("virtualCarPropertyManager");
                mf.setAccessible(true);
                mgr = mf.get(null);
                if (mgr != null) {
                    getValueMethod = mgr.getClass().getMethod("getValue", int.class, int.class);
                }
            } catch (Throwable t) {
                mgr = null;
                getValueMethod = null;
            }

            Class.forName(CACHE);   // 只是确认类在

            hub.setSource("vendor", "connected");
            hub.setSource("vcarMgr", mgr == null ? "null" : "ok");
            Diagnostics.log("厂商通道已连上: " + UTIL);
            return true;
        } catch (Throwable t) {
            hub.setSource("vendor", "unavailable");
            hub.carError = "厂商SDK: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            Diagnostics.log("厂商通道不可用: " + t);
            return false;
        }
    }

    // ─────────────────────────────────────── 挂进 D.apk 的实时监听器

    /**
     * 关键一招：往 D.apk 自己的监听器列表里塞我们的回调。
     *
     * 实测 cacheCarS05Info.gearMode 是**滞后**的 —— 车机面板上「当前挡位」显示 N，
     * 而这个字段还停在 P。说明 D.apk 的界面走的是实时订阅，缓存只是一份快照。
     *
     * 它的实时订阅出口就是这几个 CopyOnWriteArrayList：
     *   onPsAliasChangedListeners   List<Function2>   (alias, value)  ← 最有价值
     *   onInfoChangedListeners      List<Function1>   (CacheCarS05Info)
     *   onDetailedInfoChangedListeners
     *
     * kotlin.jvm.functions.Function1/2 是接口，可以用动态代理实现，
     * 不需要编译期依赖 kotlin。挂上去之后车机每推一次数据我们就收到一次。
     */
    private final java.util.Map<String, String> liveMap = new java.util.LinkedHashMap<>();
    private volatile long liveEvents;
    private static Object KOTLIN_UNIT;

    private static Object kotlinUnit() {
        if (KOTLIN_UNIT == null) {
            try {
                KOTLIN_UNIT = Class.forName("kotlin.Unit").getField("INSTANCE").get(null);
            } catch (Throwable t) {
                KOTLIN_UNIT = null;
            }
        }
        return KOTLIN_UNIT;
    }

    /** 给 className 指定的接口造一个动态代理，回调里把参数交给 onLiveEvent。 */
    private Object makeListener(String className, final String tag) {
        try {
            Class<?> iface = Class.forName(className, false, utilClass.getClassLoader());
            return java.lang.reflect.Proxy.newProxyInstance(
                    utilClass.getClassLoader(), new Class<?>[]{iface},
                    new java.lang.reflect.InvocationHandler() {
                        @Override public Object invoke(Object proxy, Method m, Object[] args) {
                            try {
                                if ("invoke".equals(m.getName()) && args != null && args.length > 0) {
                                    onLiveEvent(tag, args);
                                }
                            } catch (Throwable ignored) {
                                // 绝不能把异常抛回 D.apk 的分发循环
                            }
                            return kotlinUnit();
                        }
                    });
        } catch (Throwable t) {
            return null;
        }
    }

    /** 把我们的代理塞进指定的静态 List 字段。 */
    private boolean hookList(String fieldName, String className, String tag) {
        try {
            Object listObj = readStatic(fieldName);
            if (!(listObj instanceof java.util.List)) return false;
            @SuppressWarnings("unchecked")
            java.util.List<Object> list = (java.util.List<Object>) listObj;
            Object listener = makeListener(className, tag);
            if (listener == null) return false;
            list.add(listener);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void installLiveHooks() {
        boolean a = hookList("onPsAliasChangedListeners",
                "kotlin.jvm.functions.Function2", "ps");
        boolean b = hookList("onInfoChangedListeners",
                "kotlin.jvm.functions.Function1", "info");
        boolean c = hookList("onDetailedInfoChangedListeners",
                "kotlin.jvm.functions.Function2", "detail");
        StateHub.get().setSource("hooks",
                "ps=" + a + " info=" + b + " detail=" + c);
        Diagnostics.log("实时挂钩: ps=" + a + " info=" + b + " detail=" + c);
    }

    /** 车机推过来的每一帧都会走到这里 */
    private void onLiveEvent(String tag, Object[] args) {
        liveEvents++;

        // 参数通常形如 (String alias, Object value)，但不保证顺序，宽容一点
        String alias = null;
        Object value = null;
        for (Object a : args) {
            if (alias == null && a instanceof String) {
                alias = (String) a;
            } else if (value == null && a != null) {
                value = a;
            }
        }

        if (alias == null) {
            return;
        }
        String text = value == null ? "null" : String.valueOf(value);
        recordLive(alias, text);

        // 复用和轮询完全相同的映射逻辑
        try {
            apply(StateHub.get(), alias, value);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private void recordLive(String alias, String value) {
        if (value.length() > 80) value = value.substring(0, 80) + "…";
        synchronized (liveMap) {
            // 只留最近的，避免无限增长
            if (!liveMap.containsKey(alias) && liveMap.size() >= 120) return;
            liveMap.put(alias, value);
        }
    }

    /** 给 /logcat 用：车机实际推过哪些别名、最后的值是什么 */
    public String liveMapSummary() {
        java.util.List<java.util.Map.Entry<String, String>> list;
        synchronized (liveMap) {
            list = new java.util.ArrayList<java.util.Map.Entry<String, String>>(liveMap.entrySet());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("  收到实时事件: ").append(liveEvents)
          .append(" 次，涉及别名 ").append(list.size()).append(" 个\n\n");
        if (list.isEmpty()) {
            sb.append("  （一个都没收到。挂钩失败或车机没在推数据，看 src.hooks）\n");
            return sb.toString();
        }
        for (java.util.Map.Entry<String, String> e : list) {
            sb.append("  ").append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    /** 取一个私有方法并放开访问权限 */
    private static Method priv(Class<?> c, String name, Class<?>... params) {
        try {
            Method m = c.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    private Object readStatic(String name) {
        Class<?> c = utilClass;
        if (c == null) return null;
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 主动补绑：D.apk 自己的初始化不一定成功。
     *
     * 实测 virtualCarPropertyManager=null、psAvailable=false、
     * virtualCarRegistered=false，而 vc_alias_vehicle_gear 只走聚合服务 ——
     * 这就是「挂了 D 档但档位不更新」的根因。这里替它补一遍。
     */
    private void ensureBound() {
        if (mgr != null && readStatic("polymericService") != null
                && Boolean.TRUE.equals(readStatic("virtualCarRegistered"))) {
            return;
        }
        if (bindAttempts++ > 8) return;
        StateHub hub = StateHub.get();

        try {
            if (mgr == null && bindVcarMgr != null) {
                bindVcarMgr.invoke(util);
                mgr = readStatic("virtualCarPropertyManager");
                if (mgr != null) {
                    getValueMethod = mgr.getClass().getMethod("getValue", int.class, int.class);
                }
            }
        } catch (Throwable ignored) {
            // 拿不到就靠缓存，不影响其它字段
        }

        try {
            if (registerVcarCb != null
                    && !Boolean.TRUE.equals(readStatic("virtualCarRegistered"))) {
                registerVcarCb.invoke(util);
            }
        } catch (Throwable ignored) {
            // 忽略
        }

        try {
            if (connectPs != null && readStatic("polymericService") == null) {
                connectPs.invoke(util, Boolean.FALSE);
            }
        } catch (Throwable ignored) {
            // 聚合服务连不上就靠缓存兜底
        }

        hub.setSource("vcarMgr", mgr == null ? "null" : "ok");
        hub.setSource("ps", readStatic("polymericService") == null ? "off" : "on");
        Diagnostics.log("主动补绑#" + bindAttempts + ": mgr=" + (mgr != null)
                + " ps=" + (readStatic("polymericService") != null)
                + " registered=" + readStatic("virtualCarRegistered"));
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
                    // 250ms 读一次缓存。纯字段读取，开销可以忽略，
                    // 但能让 iPhone 上的车速尽量贴着车机仪表走。
                    handler.postDelayed(this, 250L);
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
        readSpeedField(hub);
        hub.setSource("speedSrc", "实时推送=" + (speedAliasFresh() ? "在用" : (speedAliasLive ? "已断供" : "没收到"))
                + " 字段=" + (speedFieldLive ? "在用" : "没动过")
                + "（高德车速已禁用）");

        // 2) 电量百分比：车机实测不上报 EnergyInfo/SocPercent（psGetValueSync
        //    对全部 1276 个别名都返回 null），所以按用户要求用剩余续航折算。
        //    满电续航可在 /setfull?km=500 里改。
        if (hub.soc == null && hub.rangeKm != null && hub.rangeKm > 0) {
            double full = fullRangeKm();
            if (full > 1) {
                double pct = hub.rangeKm / full * 100.0;
                hub.soc = Math.max(0, Math.min(100, pct));
                hub.setSource("soc", "derived:" + Math.round(hub.rangeKm) + "/" + (int) full + "km");
            }
        }

        if (!probeAliases) return;

        // 每 2 秒给一次补绑机会（前几次可能拿不到，D.apk 那边是异步的）
        ensureBound();

        // 3) 再按别名补（有则更准，没有也不影响上面折算出来的值）。
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

    // ─────────────────────────────────────── 电量百分比：车机不上报就折算

    private static final String PREF = "cardash";
    private static final String KEY_FULL = "fullRangeKm";
    private static final float DEFAULT_FULL = 500f;

    private static volatile android.content.SharedPreferences prefs;

    public static void initPrefs(android.content.Context ctx) {
        try {
            prefs = ctx.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE);
        } catch (Throwable ignored) {
            // 拿不到就用默认值
        }
    }

    public static double fullRangeKm() {
        android.content.SharedPreferences p = prefs;
        if (p == null) return DEFAULT_FULL;
        try {
            return p.getFloat(KEY_FULL, DEFAULT_FULL);
        } catch (Throwable t) {
            return DEFAULT_FULL;
        }
    }

    /** /setfull?km=520 用；不带参数就只回显当前值。 */
    public static String setFullRange(String query) {
        StringBuilder sb = new StringBuilder();
        sb.append("满电续航（用于把剩余续航折算成电量百分比）\n");
        sb.append("  当前 = ").append((int) fullRangeKm()).append(" km\n");

        if (query != null) {
            for (String kv : query.split("&")) {
                int eq = kv.indexOf('=');
                if (eq <= 0) continue;
                if (!"km".equals(kv.substring(0, eq).trim())) continue;
                try {
                    float v = Float.parseFloat(kv.substring(eq + 1).trim());
                    if (v < 50 || v > 1200) {
                        sb.append("  拒绝：km 要在 50~1200 之间\n");
                        return sb.toString();
                    }
                    android.content.SharedPreferences p = prefs;
                    if (p != null) {
                        p.edit().putFloat(KEY_FULL, v).commit();
                    }
                    sb.append("  已改为 = ").append((int) v).append(" km\n");
                    sb.append("  电量百分比 = 剩余续航 / ").append((int) v).append(" * 100\n");
                    return sb.toString();
                } catch (Throwable ignored) {
                    sb.append("  参数不对，用法: /setfull?km=500\n");
                    return sb.toString();
                }
            }
        }
        sb.append("\n  改法: ").append(BridgeRuntime.primaryUrl()).append("/setfull?km=500\n");
        return sb.toString();
    }

    /** @return true 表示这个值真的被用上了 */
    private boolean apply(StateHub hub, String alias, Object raw) {
        if (A_RANGE.equals(alias) || A_RANGE_LOW.equals(alias)) {
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
        if (A_DRIVE.equals(alias)) {
            hub.setSource("driveStyle", String.valueOf(raw));
            return true;
        }
        if (A_TIRE_FL.equals(alias)) {
            hub.setSource("tire", String.valueOf(raw));
            return true;
        }

        // 车机自己推的导航信息。格式未知，先把原始值记进 src（/logcat 里能看到），
        // 只有 NaviInfo 才当作转向提示用，免得被 NavMapState 之类的顶掉。
        if (alias != null && alias.startsWith("Navigation/")) {
            String s = String.valueOf(raw);
            hub.setSource("nav:" + alias, s.length() > 60 ? s.substring(0, 60) : s);
            if (alias.startsWith("Navigation/NaviInfo")) {
                hub.navTitle = s;
                hub.navActive = true;
                hub.navUpdatedAt = System.currentTimeMillis();
                hub.navSource = "vendor:" + alias;
            }
            return true;
        }
        if (A_SPEED.equals(alias)) {
            Double d = num(raw);
            if (d == null || d < 0) return false;
            // 别名就带 Kmh 字样，按 km/h 处理；数值明显过小才当成 m/s
            hub.speedKmh = d < 0.5 && d > 0 && isProbablyMs(raw) ? d * 3.6 : d;
            // 车机主动推过来的实时值，是三个原车来源里最新鲜的一个
            speedAliasLive = true;
            speedAliasAt = System.currentTimeMillis();
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

            if ("speed".equals(kind)) {
                // 这个缓存是格式化过的滞后快照，实测会卡在 0。
                // 只要已经有更活的来源（实时推送 / currentDrivingSpeedKmh），
                // 就绝不让它覆盖；完全没有时才拿它当保底。
                if (!speedAliasFresh() && !speedFieldLive) {
                    hub.speedKmh = d;
                    hub.setSource("speed", "cache:" + field);
                }
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
    /** 属性 ID 逐个取值的结果。会很慢，所以只有后台线程填，这里只读。 */
    private volatile String idDumpText = "（还没采到，看下面进度走完再刷新）";

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
                // 实测 EnergyInfo/DrivingInfo 两个命名空间全部返回 null，
                // 所以默认就把 1276 个别名全扫一遍，不给结论留死角。
                String[] all = Aliases.all();
                java.util.List<String> todo = new java.util.ArrayList<>(all.length);
                for (String a : all) {
                    todo.add(a);
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

                // 属性 ID 逐个取值放在最后。
                // ⚠️ 实测 VirtualCarPropertyManager.getValue 在车机上会阻塞，
                // 放前面会导致别名扫描永远轮不到 —— 上一版就是这么踩的坑。
                try {
                    idDumpText = idDump();
                } catch (Throwable t) {
                    idDumpText = "  采集失败: " + t;
                }

                scanRunning = false;
            }
        }, "cardash-scan");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 把 CarS05InfoUtil 的所有静态字段倒出来。
     *
     * 里面藏着 D.apk 到底注册了哪些虚拟车辆属性 ID（virtualCarSensorIds 等）、
     * 哪些别名受保护、最近哪些字段变过（pendingChangedFields）。
     * 「挂了 D 档但档位不更新」的答案大概率就在这里面。
     */
    private String staticsDump() {
        Class<?> c = utilClass;
        if (c == null) return "  （类未加载）\n";
        StringBuilder sb = new StringBuilder(4096);
        for (Field f : c.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            String n = f.getName();
            if (n.startsWith("$") || "INSTANCE".equals(n)) continue;
            sb.append("  ").append(n).append(" = ");
            try {
                f.setAccessible(true);
                sb.append(describe(f.get(null)));
            } catch (Throwable t) {
                sb.append('<').append(t.getClass().getSimpleName()).append('>');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * 逐个读虚拟车辆属性 ID 的当前值。
     *
     * 拿到 virtualCarSensorIds 后直接 getValue(id, 0)，就能看到车机真实的原始
     * 属性值 —— 档位有没有在更新、SOC 到底在哪个 ID 上，一看便知。
     */
    private String idDump() {
        Class<?> c = utilClass;
        if (c == null) return "  （类未加载）\n";
        if (mgr == null || getValueMethod == null) {
            return "  （拿不到 virtualCarPropertyManager，无法按 ID 取值）\n";
        }
        StringBuilder sb = new StringBuilder(4096);
        String[] names = {"virtualCarSensorIds", "virtualCarHvacIds", "virtualCarCabinIds"};
        for (String n : names) {
            int[] ids = readIntArray(c, n);
            if (ids == null) {
                sb.append("  ").append(n).append(" —— 读不到\n");
                continue;
            }
            sb.append("  ").append(n).append("（").append(ids.length).append(" 个）:\n");
            for (int id : ids) {
                sb.append("    0x")
                  .append(Integer.toHexString(id).toUpperCase(java.util.Locale.US))
                  .append("  ").append(readId(id)).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 车速来源之二：CarS05InfoUtil.currentDrivingSpeedKmh。
     *
     * 它是数值型、由厂商回调直接写，比 cacheCarS05Info.speed 那个格式化字符串新鲜得多。
     *
     * 怎么判断它是「活的」：**看它变不变**。
     * 只要观察到它变过一次，就说明这个字段真的在被刷新，可以采信；
     * 一次都没变过就说明它是个死字段（或者车一直停着 —— 那种情况下
     * 缓存里也是 0，回落到缓存结果一样）。
     */
    private void readSpeedField(StateHub hub) {
        if (speedAliasFresh()) return;       // 实时推送最优先，无需再读字段

        Object v = readStatic("currentDrivingSpeedKmh");
        if (!(v instanceof Number)) return;
        double d = ((Number) v).doubleValue();

        if (Double.isNaN(lastSpeedField)) {
            lastSpeedField = d;
        } else if (Math.abs(d - lastSpeedField) > 0.01) {
            speedFieldLive = true;           // 它变过 —— 是活的
            lastSpeedField = d;
        }

        if (speedFieldLive) {
            hub.speedKmh = d;
            hub.setSource("speed", "field:currentDrivingSpeedKmh");
        }
    }

    private static int[] readIntArray(Class<?> c, String name) {
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(null);
            return v instanceof int[] ? (int[]) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private String readId(int id) {
        try {
            Object v = getValueMethod.invoke(mgr, id, 0);
            return v == null ? "null" : String.valueOf(v);
        } catch (Throwable t) {
            return "<" + t.getClass().getSimpleName() + ">";
        }
    }

    /** 把任意反射出来的对象压成一行可读文本 */
    private static String describe(Object v) {
        if (v == null) return "null";
        if (v instanceof int[]) {
            int[] a = (int[]) v;
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < a.length && i < 40; i++) {
                if (i > 0) b.append(", ");
                b.append(a[i]);
            }
            if (a.length > 40) b.append(", …共").append(a.length);
            return b.append(']').toString();
        }
        if (v instanceof Object[]) {
            Object[] a = (Object[]) v;
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < a.length && i < 40; i++) {
                if (i > 0) b.append(", ");
                b.append(a[i]);
            }
            if (a.length > 40) b.append(", …共").append(a.length);
            return b.append(']').toString();
        }
        if (v instanceof java.util.Map) {
            java.util.Map<?, ?> m = (java.util.Map<?, ?>) v;
            StringBuilder b = new StringBuilder("{");
            int i = 0;
            for (java.util.Map.Entry<?, ?> e : m.entrySet()) {
                if (i++ > 0) b.append(", ");
                if (i > 25) {
                    b.append("…共").append(m.size()).append(" 项");
                    break;
                }
                b.append(e.getKey()).append('=').append(e.getValue());
            }
            return b.append('}').toString();
        }
        if (v instanceof java.util.Collection) {
            java.util.Collection<?> col = (java.util.Collection<?>) v;
            StringBuilder b = new StringBuilder("[");
            int i = 0;
            for (Object o : col) {
                if (i++ > 0) b.append(", ");
                if (i > 30) {
                    b.append("…共").append(col.size()).append(" 项");
                    break;
                }
                b.append(o);
            }
            return b.append(']').toString();
        }
        String s = String.valueOf(v);
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private String scanText() {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("厂商属性扫描\n");
        sb.append("  SDK           : ").append(util != null ? "已加载" : "未加载").append('\n');
        sb.append("  psGetValueSync: ").append(psGet != null ? "可用" : "不可用").append('\n');
        sb.append("  别名总数      : ").append(Aliases.COUNT).append('\n');
        sb.append("  进度          : ").append(scanDone).append('/').append(scanTotal)
          .append(scanRunning ? "（进行中，刷新本页看更多）" : "（已完成）").append('\n');

        sb.append("  满电续航      : ").append((int) fullRangeKm())
          .append(" km（用于折算电量% ，用 /setfull?km=N 改）\n");

        sb.append("\n【CarS05InfoUtil.cacheCarS05Info】\n");
        sb.append(cacheDump());

        // 纯反射，无 IPC，放在同步路径上是安全的
        sb.append("\n【CarS05InfoUtil 静态字段（含 D.apk 注册了哪些属性）】\n");
        sb.append(staticsDump());

        sb.append("\n【虚拟车辆属性 ID 逐个取值】\n");
        sb.append(idDumpText);

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
