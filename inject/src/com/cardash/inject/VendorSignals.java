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

    /**
     * 车身信号的取值类 —— ⚠️ **两个底包的类名不一样**，已用 dex 逐个核对过：
     *   旧底包 v26.0521 → {@code com.deepalhome.launcher.util.CarS05InfoUtil}
     *   新底包 v26.0923 → {@code com.deepalhome.launcher.util.s05.S05VehicleDataMonitor}
     *
     * 2026-09-24 踩的坑：移植新底包时只写了 S05VehicleDataMonitor，
     * 结果旧底包打出来的包装到车上 Class.forName 直接失败 ⇒
     * **车速 / 档位 / 续航 / 电量 / 转向灯全是空的**。
     * 所以这里按顺序两个都试，哪个存在用哪个 —— 一份代码同时支持两个底包。
     *
     * 两个类里我们要反射的成员名字是一样的：cacheCarS05Info（CacheCarS05Info，字段全同）、
     * currentDrivingSpeedKmh、onPsAliasChangedListeners / onInfoChangedListeners /
     * onDetailedInfoChangedListeners、virtualCarPropertyManager、polymericService、
     * virtualCarRegistered、bindVirtualCarPropertyManager / connectPolymericService /
     * registerVirtualCarCallbacks ⇒ 只是换类名，读缓存 + 挂监听 + 主动补绑原样复用。
     */
    private static final String[] UTIL_CANDIDATES = {
            "com.deepalhome.launcher.util.s05.S05VehicleDataMonitor",   // v26.0923 起
            "com.deepalhome.launcher.util.CarS05InfoUtil",              // v26.0521 及更早
    };
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
    private static final String A_SPEED      = "vc_alias_vehicle_speed";
    private static final String A_GEAR       = "vc_alias_vehicle_gear";
    private static final String A_DRIVE      = "vc_alias_drive_style";
    private static final String A_TIRE_FL    = "vc_alias_tire_pressure";

    /**
     * 转向灯。⚠️ 2026-09-24 实车校准（用户三次导航日志）：真正在推的别名是
     * vc_alias_turn_left_signal_on / vc_alias_turn_right_signal_on。
     *
     * 最早照 D.apk 的 Aliases 表猜的 Light/TurnLeft / Light/TurnLightStatus
     * **车机根本不推** —— 所以转向灯/双闪一直没数据（/state 里 turn 恒为 null、
     * iPhone 两边不冒绿光）。别改回去。
     *
     * ⚠️⚠️ **真值语义（2026-09-25 第二轮实车定案）：1 = 灯灭、2 = 灯亮** ——
     * 见 {@link #TURN_OFF_VALUE}。
     */
    private static final String A_TURN_L     = "vc_alias_turn_left_signal_on";
    private static final String A_TURN_R     = "vc_alias_turn_right_signal_on";

    /**
     * 转向灯原始值的真值语义：**1 = 灭、≥ 2 = 亮**（2026-09-25 第二轮实车定案）。
     *
     * <p>证据一（灭）：2026-09-25 第一轮属性全量扫描，**没打灯**时
     * <pre>
     *   vc_alias_turn_left_signal_on  = 1
     *   vc_alias_turn_right_signal_on = 1
     * </pre>
     *
     * <p>证据二（亮、而且在闪）：2026-09-25 11:47:30 用户在车上打灯，
     * /logcat 的「转向灯（原始值变化历史）」原始记录：
     * <pre>
     *   11:47:30   left = 2      11:47:30   right = 2
     *   11:47:30   left = 1      11:47:30   right = 1
     *   11:47:31   left = 2      11:47:31   right = 2
     *   …（1 ↔ 2 反复跳，一秒两三次 —— 属性是跟着灯泡一起闪的）
     * </pre>
     *
     * <p>⇒ 灭时**只会**是 1；亮时是 2（并且随闪动在 1/2 之间跳）。
     * 上一版按"0 = 亮"实现，而打灯时值是 2 ⇒ 判成"什么都不亮"，
     * 于是用户报「打左转 / 右转 / 双闪都没生效」。
     *
     * <p>⚠️ 值本身在闪，**不能**当布尔直接下发（iPhone 250ms 轮询会抽到随机的
     * on/off，看着就是抖）—— 保持逻辑在 {@link StateHub#turnValue()}。
     *
     * <p>这个功能已经猜错两次（第一次猜 {@code Light/Turn*} 别名名、第二次猜 0=亮）。
     * 真值语义**只能**靠真车实测定，别再照别名名字或"0/1 二进制"的直觉猜。
     */
    private static final int TURN_OFF_VALUE = 1;
    /** 亮 = **≥ 2**（实测打灯时是 2；0 / 负数从没见过，按"不表态"处理，见 turnActive） */
    private static final int TURN_ON_MIN = 2;

    /**
     * 续航候选别名，按优先级从高到低。
     *
     * 车机会推好几个续航：left_ev_dte / edte / e_dte / disp_dte。
     * 实测 disp_dte 一直是 0（显示续航没启用），所以必须按优先级挑，
     * 而且 **0 要当成「这一路没数据」跳过** —— 不能把 0 当成真的没续航。
     */
    private static final String[] RANGE_ALIASES = {
            "vc_alias_left_ev_dte",
            "vc_alias_edte",
            "vc_alias_e_dte",
            "vc_alias_disp_dte",
    };

    /**
     * 总里程候选别名。
     *
     * 车机把总里程以 0.1 km 为单位放在 REV 里程族里：
     *   vc_alias_reev_long_term_driving_elec_driving_distance = 24593
     * 换算成 2459.3 km，和 cacheCarS05Info.totalDistance 完全吻合。
     * 而 journey_all_distance 是 D.apk 保护的正规别名，单位待定。
     * 单位不统一，所以不写死比例，拿缓存值当标尺校准（见 scaleOdometer）。
     */
    private static final String[] ODO_ALIASES = {
            "vc_alias_journey_all_distance",
            "vc_alias_reev_long_term_driving_elec_driving_distance",
            "vc_alias_vehicle_odometer",
            "vc_alias_odometer",
            "vc_alias_total_distance",
    };

    private static final String[] PROBE = {
            A_SPEED, A_GEAR, "vc_alias_journey_all_distance",
            A_RANGE, "vc_alias_disp_dte", "vc_alias_e_dte", A_DRIVE,
            A_TURN_L, A_TURN_R,
    };

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private Object util;              // 取值类的 INSTANCE（两个底包名不同，见 UTIL_CANDIDATES）
    /** 实际连上的那个类名（诊断里能看到用的是哪个底包的类） */
    private String utilName;
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

    // ── 续航：RANGE_ALIASES 里的优先级，值越小越优先 ──
    private volatile int rangeRankUsed = 99;
    private volatile long rangeAt;

    // ── 总里程：缓存里的值只当量纲标尺用（它滞后但数值是对的）──
    private volatile double cacheOdometer = -1;
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
        for (String name : UTIL_CANDIDATES) {
            if (connectOne(name)) {
                hub.setSource("vendor", "connected");
                hub.setSource("vcarMgr", mgr == null ? "null" : "ok");
                Diagnostics.log("厂商通道已连上: " + utilName);
                return true;
            }
        }
        hub.setSource("vendor", "unavailable");
        hub.carError = "厂商SDK: 两个底包的取值类都连不上（见 /log 里逐条原因）";
        Diagnostics.log("厂商通道不可用：两个候选类都失败");
        return false;
    }

    /** 拿某一个候选类名去连；失败把状态清干净，别影响下一个候选。 */
    private boolean connectOne(String name) {
        StateHub hub = StateHub.get();
        try {
            Class<?> c = Class.forName(name);
            utilClass = c;
            util = c.getDeclaredField("INSTANCE").get(null);
            if (util == null) {
                Diagnostics.log("厂商通道 " + name + ": INSTANCE 为 null");
                return false;
            }

            // 新版把 psGetValueSync 改成了 psGetValue，而且是**异步 void**（推给
            // onPsAliasChangedListeners），同步按别名取值这条路已经没了。
            // 所以这里拿不到 psGetValueSync 就置空 —— 转向灯/续航/档位都改走
            // cacheCarS05Info 缓存 + 实时监听，功能不受影响（/scan 那个诊断也会随之失效）。
            try {
                psGet = c.getDeclaredMethod("psGetValueSync", String.class);
                psGet.setAccessible(true);
            } catch (Throwable t) {
                psGet = null;
            }

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
            utilName = name;
            return true;
        } catch (Throwable t) {
            Diagnostics.log("厂商通道 " + name + " 不可用: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            // 这次试失败了，把手柄清干净，别让上一个候选的残留影响下一个
            utilClass = null;
            util = null;
            utilName = null;
            psGet = null;
            startMonitor = null;
            cacheField = null;
            mgr = null;
            getValueMethod = null;
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
            // 不是别名推送。`onInfoChangedListeners` 是 Function1，回推的是
            // **整个 CacheCarS05Info 对象** —— 这正好是胎压"动态更新"的来源：
            // 桌面每刷新一次缓存就回推一份新的，我们当场把四个胎压重读一遍。
            //
            // 别的挂钩（Function2 的 detail 等）参数形状不定，readTire 内部
            // 对"字段读不到"是静默吞掉的，所以这里无脑调是安全的。
            if (value != null) {
                readTire(StateHub.get(), value);
            }
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
        hub.setSource("carSpeedSrc",
                "实时推送=" + (speedAliasFresh() ? "在用" : (speedAliasLive ? "已断供" : "没收到"))
                + " 字段=" + (speedFieldLive ? "在用" : "没动过")
                + "（车机车速仅诊断，仪表用 iPhone GPS）");

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
        // ── 续航：按优先级择一。0 表示这一路没数据，不能当成「真的没续航」 ──
        int rr = rangeRank(alias);
        if (rr >= 0) {
            Double d = num(raw);
            if (d == null || d <= 0) return false;
            // 当前这个来源 10 秒没再更新就允许更低优先级的接管，
            // 免得高优先级的别名只推过一次就把续航钉死。
            boolean stale = System.currentTimeMillis() - rangeAt > 10000;
            if (rr <= rangeRankUsed || stale) {
                hub.rangeKm = d;
                rangeRankUsed = rr;
                rangeAt = System.currentTimeMillis();
                hub.setSource("range", "vendor:" + alias);
            }
            return true;
        }

        // ── 总里程：实时推送。缓存那个是滞后快照，开着车都不涨 ──
        if (isOdoAlias(alias)) {
            Double d = num(raw);
            if (d == null || d < 0) return false;
            double km = scaleOdometer(d);
            // 和缓存/已有值差太远说明量纲判错了，宁可不采信
            double ref = hub.odometerKm != null ? hub.odometerKm : cacheOdometer;
            if (ref > 0 && Math.abs(km - ref) > 300) {
                hub.setSource("odoReject", alias + "=" + d + "->" + Math.round(km) + " 差太远");
                return false;
            }
            hub.odometerKm = km;
            hub.setSource("odometer", "vendor:" + alias);
            return true;
        }
        if (A_DRIVE.equals(alias)) {
            hub.setSource("driveStyle", String.valueOf(raw));
            return true;
        }
        if (A_TIRE_FL.equals(alias)) {
            // ⚠️ 只记诊断，**不用它填胎压**：`vc_alias_tire_pressure` 只有一个别名，
            // 分不出是哪个轮子（底包的字段映射是 native 的 handleAliasValue，看不到）。
            // 真正下发给 iPhone 的四个值走 readTire()（桌面缓存里那四个已格式化字符串）。
            // 特意用另一个 key，免得和 readTire 写的 src.tire 互相覆盖。
            hub.setSource("tireAlias", alias + "=" + raw);
            return true;
        }

        // 车机自己推的导航信息。格式未知，先把原始值记进 src（/logcat 里能看到），
        // 只有 NaviInfo 才当作转向提示用，免得被 NavMapState 之类的顶掉。
        if (alias != null && alias.startsWith("Navigation/")) {
            String s = String.valueOf(raw);
            hub.setSource("nav:" + alias, s.length() > 60 ? s.substring(0, 60) : s);
            if (alias.startsWith("Navigation/NaviInfo")) {
                // 来源仲裁：高德广播（每秒在推）/ 通知栏在推时让位 ——
                // 用户习惯原厂导航和第三方高德同时开，两路都往 navTitle/navEta
                // 这些槽位写就会来回跳（见 StateHub.navClaim）。
                if (!hub.navClaim("vendor:" + alias)) return true;
                hub.navTitle = s;
                hub.navActive = true;
                hub.navUpdatedAt = System.currentTimeMillis();
            }
            return true;
        }
        if (A_SPEED.equals(alias)) {
            Double d = num(raw);
            if (d == null || d < 0) return false;
            // 别名就带 Kmh 字样，按 km/h 处理；数值明显过小才当成 m/s
            hub.carSpeedKmh = d < 0.5 && d > 0 && isProbablyMs(raw) ? d * 3.6 : d;
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
        if (A_TURN_L.equals(alias) || A_TURN_R.equals(alias)) {
            return applyTurn(hub, alias, raw);
        }
        return false;
    }

    /**
     * 转向灯：把原始值写进 StateHub 的槽位。
     *
     * <p>⚠️ 这里写的是**"最近一次亮是什么时候"（时间戳）**，不是布尔 ——
     * 车机属性跟着灯泡闪（1↔2，每秒两三次），布尔会把闪动原样透给 iPhone，
     * 250ms 轮询抽到的就是随机 on/off。归一化成「0=灭 1=左 2=右 3=双闪」
     * 放在 {@link StateHub#turnValue()} 里做，那里带 2.5 秒保持。
     *
     * <p>左右分开存、最后统一算 ⇒ 与两条别名的到达顺序无关
     * （左灯先到、右灯后到都不会互相覆盖）。
     */
    private static boolean applyTurn(StateHub hub, String alias, Object raw) {
        recordTurn(alias, raw);
        if (A_TURN_L.equals(alias)) {
            lastTurnLeftRaw = raw == null ? null : String.valueOf(raw);
            refreshTurnRaw(hub);
            Boolean b = turnActive(raw);
            if (b == null) return false;          // 没见过的值：只记历史，不改状态
            hub.turnLeftSeen = true;
            if (b) {
                hub.turnLeftOnAt = System.currentTimeMillis();
                // 左灯刚亮 ⇒ 检查右灯该不该灭（"右转切左转，别两边同时亮"，见 TURN_SWITCH_MS）
                hub.turnSwitch('L');
            }
            return true;
        }
        if (A_TURN_R.equals(alias)) {
            lastTurnRightRaw = raw == null ? null : String.valueOf(raw);
            refreshTurnRaw(hub);
            Boolean b = turnActive(raw);
            if (b == null) return false;
            hub.turnRightSeen = true;
            if (b) {
                hub.turnRightOnAt = System.currentTimeMillis();
                hub.turnSwitch('R');
            }
            return true;
        }
        Double d = num(raw);
        if (d != null) {
            hub.turnStatus = d.intValue();
            return true;
        }
        Boolean b = turnActive(raw);
        if (b != null) {
            hub.turnStatus = b ? 3 : 0;      // 布尔形态当「双闪/灭」
            return true;
        }
        return false;
    }

    /** 左 / 右别名的最近原始值（只给诊断用 —— 两个值一起看才知道是左、右还是双闪） */
    private static volatile String lastTurnLeftRaw;
    private static volatile String lastTurnRightRaw;

    /**
     * 把左右**两个**原始值一起写进诊断。
     *
     * ⚠️ 以前只显示"最后到达的那一个"，而左灯右灯是两个独立别名、先后到达，
     * 所以诊断里永远只看到一个值（用户上一轮的 `turnRaw` 就只有 right）
     * —— 定不了"到底是左还是右"。两个一起显示才看得出来。
     */
    private static void refreshTurnRaw(StateHub hub) {
        hub.setSource("turnRaw", "left=" + (lastTurnLeftRaw == null ? "?" : lastTurnLeftRaw)
                + " right=" + (lastTurnRightRaw == null ? "?" : lastTurnRightRaw));
    }

    /**
     * 原始值 → 「这盏灯现在亮着吗」。
     *
     * <p>判据：{@link #TURN_OFF_VALUE}（1）= 灭、{@link #TURN_ON_MIN}（≥2）= 亮。
     *
     * <p>没见过的值（0 / 负数）返回 null ⇒ 调用方**保持上一次状态、不刷新时间戳**，
     * 于是"没有灯"会在 2.5 秒后自然收掉。宁可少亮，也不能像第一轮那样
     * 「从连上车机起两边一直闪」。
     */
    private static Boolean turnActive(Object raw) {
        if (raw == null) return null;
        Double d = num(raw);
        if (d != null) {
            int v = d.intValue();
            if (v == TURN_OFF_VALUE) return false;
            if (v >= TURN_ON_MIN) return true;
            return null;
        }
        if (raw instanceof Boolean) return (Boolean) raw;   // 本车没出现过这种形态
        String s = String.valueOf(raw).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;
        String t = s.toLowerCase(java.util.Locale.ROOT);
        if (t.startsWith("true") || t.startsWith("on") || t.startsWith("yes")) return true;
        if (t.startsWith("false") || t.startsWith("off") || t.startsWith("no")) return false;
        return null;
    }

    // ─────────────────────────────────────── 转向灯原始值历史（校准用）

    /**
     * 转向灯两个别名的**原始值变化历史**（带时间，最近 24 条）。
     *
     * <p>为什么要留这个：真值语义已经猜错两次（先猜别名、再猜 1=亮）。
     * 光看"当前值"永远定不了案 —— 得看到"我打左灯那一刻它变成了什么"。
     * 用户下次上车：打一次左、一次右、一次双闪、再关掉，然后抓一份诊断，
     * 这段历史会把每次变化**带时间**列出来 ⇒ 语义一眼可定。
     *
     * <p>⚠️ 别删：这是唯一能证明真值语义的现场证据。
     */
    private static final java.util.ArrayDeque<String> TURN_LOG =
            new java.util.ArrayDeque<>();
    private static final int TURN_LOG_MAX = 24;

    private static void recordTurn(String alias, Object raw) {
        String name = alias == null ? "?" : alias;
        if (name.startsWith("vc_alias_turn_")) {
            name = name.substring("vc_alias_turn_".length());
        }
        if (name.endsWith("_signal_on")) {
            name = name.substring(0, name.length() - "_signal_on".length());
        }
        String line = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date()) + "   " + name + " = " + raw;
        synchronized (TURN_LOG) {
            if (!line.equals(TURN_LOG.peekLast())) {      // 连续重复不记
                TURN_LOG.addLast(line);
                while (TURN_LOG.size() > TURN_LOG_MAX) TURN_LOG.pollFirst();
            }
        }
    }

    /** 给 /logcat 用 */
    public static String turnLogText() {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("  当前语义 = 原始值 ").append(TURN_OFF_VALUE).append(" 当成\"灯灭\"、≥ ")
          .append(TURN_ON_MIN).append(" 当成\"灯亮\"（1=灭 / 2=亮，2026-09-25 第二轮实车定案）\n");
        sb.append("  下发前   = 0.7 秒保持 + 换边互斥（属性跟着灯泡闪，1↔2 反复跳，直接发会抖）\n");
        sb.append("  说明     = 打一次左 / 右 / 双闪 / 关掉，看下面的变化记录就能核对\n");
        synchronized (TURN_LOG) {
            if (TURN_LOG.isEmpty()) {
                sb.append("  （还没收到转向灯别名）\n");
            } else {
                for (String s : TURN_LOG) sb.append("  ").append(s).append('\n');
            }
        }
        return sb.toString();
    }

    // ─────────────────────────────────────── 续航 / 总里程的候选与量纲

    /** 是不是续航别名的优先级（越小越优先）；-1 表示不是续航别名 */
    private static int rangeRank(String alias) {
        for (int i = 0; i < RANGE_ALIASES.length; i++) {
            if (RANGE_ALIASES[i].equals(alias)) return i;
        }
        return -1;
    }

    private static boolean isOdoAlias(String alias) {
        for (String a : ODO_ALIASES) {
            if (a.equals(alias)) return true;
        }
        return false;
    }

    /**
     * 总里程的量纲校准。
     *
     * 实时别名的单位和缓存不一定一致：REV 里程族是 0.1 km，
     * 而 journey_all_distance 可能直接是 km。这里不猜，拿缓存的值当标尺 ——
     * 缓存虽然滞后（几分钟不更新），但数值本身是对的，
     * 哪个比例算出来最接近就用哪个。
     */
    private double scaleOdometer(double raw) {
        double ref = cacheOdometer;
        if (ref <= 0) return raw;

        double[] cand = {raw, raw / 10.0, raw / 100.0};
        double best = cand[0];
        double bestErr = Math.abs(cand[0] - ref);
        for (int i = 1; i < cand.length; i++) {
            double e = Math.abs(cand[i] - ref);
            if (e < bestErr) {
                bestErr = e;
                best = cand[i];
            }
        }
        return best;
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
            // 电量百分比（2026-09-26 修「iPhone 上电量一直显示上车时的值」）：
            // 缓存里本来就有真实值（cacheCarS05Info.socPercent = 84），以前没读，
            // 一直靠「剩余续航 ÷ 满电续航」折算 —— 而那段代码写成
            // `if (hub.soc == null ...)`，只会在第一次算出来，之后**永远不更新**，
            // 看起来就是电量冻住了。现在优先用缓存里的真实值，每次轮询都刷新。
            Double sp = numField(cache, "socPercent");
            if (sp != null && sp > 0 && sp <= 100) {
                hub.soc = sp;
                hub.setSource("soc", "cache:socPercent=" + sp);
            } else {
                // 缓存没给就清掉，让下面按续航折算的那段**每轮重算**（不再冻住旧值）
                hub.soc = null;
            }

            // 胎压：同一个缓存对象上的四个字符串字段（已格式化，原样透传）
            // （缓存每 250ms 读一次 ⇒ 胎压本身就是动态的，不是只取一次）
            readTire(hub, cache);
            // 兜底（2026-09-26 截图实锤）：CarS05InfoUtil.cacheCarS05Info 上明明有
            // tirePressureFrontLeft = 2.59 这样的值，但当前绑的 util 的缓存里可能没有
            //（新/旧底包各有一个 util，各挂各的缓存）。读不到就换另一个 util 的缓存再试。
            if (hub.tireFl == null && hub.tireFr == null
                    && hub.tireRl == null && hub.tireRr == null) {
                for (String name : UTIL_CANDIDATES) {
                    try {
                        Class<?> c2 = Class.forName(name);
                        java.lang.reflect.Field f2 = c2.getDeclaredField("cacheCarS05Info");
                        f2.setAccessible(true);
                        Object other = f2.get(null);
                        if (other != null && other != cache) {
                            readTire(hub, other);
                            if (hub.tireFl != null || hub.tireFr != null
                                    || hub.tireRl != null || hub.tireRr != null) {
                                hub.setSource("tire", "cache2(" + name + ")");
                                break;
                            }
                        }
                    } catch (Throwable ignored) {
                        // 这个 util 没有就换下一个
                    }
                }
            }
        } catch (Throwable t) {
            hub.setSource("cache", "err:" + t.getClass().getSimpleName());
        }
    }

    /**
     * 读车机桌面缓存里的四个胎压。
     *
     * <p>字段名来自 `_tools/dex_class.py` 对 {@code com.deepalhome.launcher.carinfo.CacheCarS05Info}
     * 的 dump（2026-09-26 核对当前底包 v26.0925.beta）：
     * <pre>
     *   Ljava/lang/String;  tirePressureFrontLeft
     *   Ljava/lang/String;  tirePressureFrontRight
     *   Ljava/lang/String;  tirePressureRearLeft
     *   Ljava/lang/String;  tirePressureRearRight
     * </pre>
     * 两个底包（旧 `CarS05InfoUtil` / 新 `S05VehicleDataMonitor`）的缓存**是同一个类**
     * （就是 {@link #CACHE}），所以这里**不需要候选名单**（不像 {@link #UTIL_CANDIDATES}）。
     *
     * <p>⚠️ 值**不做数值解析**：它是底包 native 方法格式化好的字符串，
     * 直接塞进 StateHub 原样下发。理由见 {@code StateHub.tireFl} 的注释。
     *
     * <p>每次都覆盖（包括覆盖成 null）—— 缓存是真的没有就让它显示成 "--"，
     * 而不是把上次的旧值一直挂着。
     */
    private static void readTire(StateHub hub, Object cache) {
        if (cache == null) return;
        String fl = strField(cache, "tirePressureFrontLeft");
        String fr = strField(cache, "tirePressureFrontRight");
        String rl = strField(cache, "tirePressureRearLeft");
        String rr = strField(cache, "tirePressureRearRight");

        // 兜底（2026-09-26）：有底包把四个胎压存成**数组**（日志诊断里看到
        // `[Ljava.lang.Integer;@…`），上面四个 String 字段根本不存在 ⇒ 全是 null
        // ⇒ iPhone 胎压页四个轮子一直 "--"。这里去缓存对象上找名字带 tire/press
        // 的数组字段，取前四个值；数值按「>=100 当 kPa 除 100」折成 bar 字符串
        //（iPhone 端不做换算，只原样显示，见 TireInfo 的注释）。
        String src = "cache";
        if (fl == null && fr == null && rl == null && rr == null) {
            String[] four = findTireArray(cache);
            if (four != null) {
                fl = four[0];
                fr = four[1];
                rl = four[2];
                rr = four[3];
                src = "array";
            }
        }

        hub.tireFl = fl;
        hub.tireFr = fr;
        hub.tireRl = rl;
        hub.tireRr = rr;

        if (fl != null || fr != null || rl != null || rr != null) {
            hub.tireSeen = true;
            hub.tireAt = System.currentTimeMillis();
            hub.setSource("tire", src + ":" + fl + "/" + fr + "/" + rl + "/" + rr);
        } else {
            hub.setSource("tire", hub.tireSeen ? "cache:空(曾收到过)" : "cache:无此字段");
        }
        // 只读地把缓存对象的字段名/类型列出来 —— 下次再换底包，一眼就能看出
        // 胎压到底叫什么、是什么类型，不用再来回猜。
        hub.setSource("tireFields", describeTireFields(cache));
    }

    /** 在缓存对象（含父类）上找名字带 tire/press 的数组字段，取前四个值。 */
    private static String[] findTireArray(Object cache) {
        Class<?> c = cache.getClass();
        int guard = 0;
        while (c != null && c != Object.class && guard++ < 6) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                String n = f.getName().toLowerCase(java.util.Locale.US);
                if (!n.contains("tire") && !n.contains("press")) continue;
                try {
                    f.setAccessible(true);
                    String[] four = fourTireValues(f.get(cache));
                    if (four != null) return four;
                } catch (Throwable ignored) {
                    // 读不到就换下一个字段
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /** 把一个字段值变成四个胎压字符串；不是"能凑出四个胎压"的形状就返回 null。 */
    private static String[] fourTireValues(Object v) {
        if (v == null) return null;
        String[] out = new String[4];
        if (v instanceof Object[]) {
            Object[] a = (Object[]) v;
            if (a.length < 4) return null;
            for (int i = 0; i < 4; i++) out[i] = tireValueOf(a[i]);
        } else if (v instanceof int[]) {
            int[] a = (int[]) v;
            if (a.length < 4) return null;
            for (int i = 0; i < 4; i++) out[i] = tireNum(a[i]);
        } else if (v instanceof float[]) {
            float[] a = (float[]) v;
            if (a.length < 4) return null;
            for (int i = 0; i < 4; i++) out[i] = tireNum(a[i]);
        } else if (v instanceof double[]) {
            double[] a = (double[]) v;
            if (a.length < 4) return null;
            for (int i = 0; i < 4; i++) out[i] = tireNum(a[i]);
        } else if (v instanceof long[]) {
            long[] a = (long[]) v;
            if (a.length < 4) return null;
            for (int i = 0; i < 4; i++) out[i] = tireNum(a[i]);
        } else {
            return null;
        }
        boolean any = false;
        for (String s : out) {
            if (s != null) any = true;
        }
        return any ? out : null;
    }

    private static String tireValueOf(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return tireNum(((Number) o).doubleValue());
        String s = String.valueOf(o).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;
        try {
            return tireNum(Double.parseDouble(s));
        } catch (Throwable e) {
            return s;
        }
    }

    /** 数值 → bar 字符串：>=100 当成 kPa 除 100；否则原样（两位小数）。 */
    private static String tireNum(double v) {
        if (v <= 0) return null;
        double bar = v >= 100 ? v / 100.0 : v;
        return String.format(java.util.Locale.US, "%.2f", bar);
    }

    /** 只读：把缓存对象里名字带 tire/press 的字段列成 "名:类型=值" 供 /diag 看。 */
    private static String describeTireFields(Object cache) {
        if (cache == null) return "cache=null";
        StringBuilder sb = new StringBuilder(120);
        Class<?> c = cache.getClass();
        int guard = 0;
        while (c != null && c != Object.class && guard++ < 6) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                String n = f.getName();
                String ln = n.toLowerCase(java.util.Locale.US);
                if (!ln.contains("tire") && !ln.contains("press")) continue;
                String ty = f.getType().getSimpleName();
                String val;
                try {
                    f.setAccessible(true);
                    Object o = f.get(cache);
                    if (o instanceof Object[]) {
                        val = java.util.Arrays.toString((Object[]) o);
                    } else if (o instanceof int[]) {
                        val = java.util.Arrays.toString((int[]) o);
                    } else if (o instanceof float[]) {
                        val = java.util.Arrays.toString((float[]) o);
                    } else if (o instanceof double[]) {
                        val = java.util.Arrays.toString((double[]) o);
                    } else {
                        val = String.valueOf(o);
                    }
                } catch (Throwable e) {
                    val = "<?>";
                }
                if (val != null && val.length() > 40) val = val.substring(0, 40);
                if (sb.length() > 0) sb.append(" | ");
                sb.append(n).append(':').append(ty).append('=').append(val);
                if (sb.length() > 300) break;
            }
            c = c.getSuperclass();
        }
        return sb.length() == 0 ? "（没有带 tire/press 的字段）" : sb.toString();
    }

    /** 反射读一个 String 字段，取不到/为空都返回 null（不抛）。 */
    private static String strField(Object obj, String field) {
        try {
            Object v = obj.getClass().getField(field).get(obj);
            if (v == null) return null;
            String s = String.valueOf(v).trim();
            if (s.isEmpty() || "null".equals(s)) return null;
            return s;
        } catch (Throwable t) {
            return null;
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
                    hub.carSpeedKmh = d;
                    hub.setSource("speed", "cache:" + field);
                }
            } else if ("odometer".equals(kind)) {
                // 只作量纲标尺（scaleOdometer 用它判断实时值要不要 /10），
                // 不作为显示值 —— 它是滞后快照，开着车都不涨。
                cacheOdometer = d;
                if (hub.odometerKm == null) {
                    hub.odometerKm = d;
                    hub.setSource("odometer", "cache:" + field + "(待实时值)");
                }
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
            hub.carSpeedKmh = d;
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

    /** 反射读一个数字字段（int/long/float/double 或数字字符串），取不到返回 null。 */
    private static Double numField(Object obj, String name) {
        if (obj == null) return null;
        try {
            java.lang.reflect.Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(obj);
            if (v instanceof Number) return ((Number) v).doubleValue();
            if (v != null) {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty()) return Double.parseDouble(s);
            }
        } catch (Throwable ignored) {
            // 字段不存在或类型不对
        }
        return null;
    }

}

