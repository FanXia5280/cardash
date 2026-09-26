package com.cardash.inject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 车机**原生导航**（梧桐 tinnove）的数据 —— 直接反射读 D.apk 自己那条链路。
 *
 * <h3>为什么要有这个类</h3>
 *
 * 用户反馈：「不用语音导航的时候，D.apk 也能显示目的地，看看它是怎么拿到的」。
 * 把 D.apk 的 dex 挖了一遍（字符串池 + 类结构），结论是：
 *
 * <pre>
 *   HudNaviManager（HUD 上的导航两行字）
 *        └─ NaviIpcBridge           com.deepalhome.launcher.util.s05.navi.NaviIpcBridge
 *              ├─ 绑系统导航服务      com.tinnove.skill.service-new
 *              ├─ AIDL 调度器         com.tinnove.navi.skill.IPCSkillDispatcher
 *              │                     （queryInfo(String) / sendRequest(String, IPCResponseObserver)）
 *              ├─ AIDL 回调           ...observer.IPCGuideInfoObserver
 *              │                     （onNaviStart/onNaviInfo/onNextRoadName/
 *              │                       onRemainInfo/onRouteInfoChanged/onTurnInfo …）
 *              ├─ 主动查终点          queryS05CurrentDestination(...)
 *              │                     日志：「主动查询当前路线终点」「检测到终点发生变化」
 *              └─ 解析结果            parseS05BasicPoiJson(String)
 *                                     → S05PoiResult{ String name; Double latitude; Double longitude; }
 *                                      ← **这就是目的地名称 + 坐标**
 * </pre>
 *
 * 也就是说：**目的地一直存在于车机原生导航里，D.apk 是通过 IPC 拿到的**，
 * 跟语音没关系（语音只是众多触发方式之一）。
 *
 * <h3>这个类干什么、不干什么</h3>
 *
 * 车机上没法调试（我们只能靠用户开一趟车 + 回传 /diag），所以这一版是**探测器**：
 *
 *   1. 每秒把 D.apk 那几个静态字段原样读出来（HUD 两行字、导航中的标志、POI 指纹）；
 *   2. 提供一个**按需触发**的「主动查终点」（/diag 或 /logcat 会调一次，30 秒限流），
 *      触发后 D.apk 自己会去 IPC 查询，效果会落到上面那些字段里；
 *   3. 全部原样进 /diag，**不猜、不乱解析** —— 拿到真实字段格式后再决定怎么用。
 *
 * ⚠️ 特别注意：**不要**把这里读到的任何东西直接当目的地喂给 iPhone。
 * 2026-09-23 那次「IPA 目的地乱跳」就是乱猜坐标来的（见 DestSignals 的注释）。
 *
 * 所有反射都包了 try/catch：类找不到、字段改名、签名变了都只是「读不到」，
 * 绝不会把桥接搞崩。
 */
public final class S05Navi {

    private static final String C_BRIDGE  = "com.deepalhome.launcher.util.s05.navi.NaviIpcBridge";
    private static final String C_HUD     = "com.deepalhome.launcher.hud.s05eta.HudNaviManager";
    private static final String C_OVERLAY = "com.deepalhome.launcher.hud.s05eta.HudEtaOverlayController";

    private static final long PROBE_MIN_GAP_MS = 30 * 1000L;

    // ── 反射句柄（只解析一次）──
    private static volatile boolean resolved;
    private static Object bridge;          // NaviIpcBridge.INSTANCE
    private static Object hud;             // HudNaviManager.INSTANCE
    private static Class<?> bridgeClass;
    private static Class<?> hudClass;

    private static Field fDispatcher;      // NaviIpcBridge.ipcSkillDispatcher
    private static Field fBound;           // NaviIpcBridge.bound
    private static Field fStarted;         // NaviIpcBridge.started
    private static Field fLastPoiSig;      // NaviIpcBridge.lastPoiSignature
    private static Field fActivePoiSig;    // NaviIpcBridge.activeGuidePoiSignature
    private static Field fPendingPoiSig;   // NaviIpcBridge.pendingSystemStartedPoiSignature
    private static Field fRouteQueryKey;   // NaviIpcBridge.lastRouteQueryKey
    private static Method mQDest;          // queryS05CurrentDestination(String, String)
    private static Method mQDestAccess;    // access$queryS05CurrentDestination(NaviIpcBridge, String, String)

    private static Field hNavigating;      // HudNaviManager.isNavigating
    private static Field hLine1;           // HudNaviManager.lastPrimaryLine
    private static Field hLine2;           // HudNaviManager.lastSecondaryLine
    private static Field hRendered;        // HudNaviManager.lastRenderedText
    private static Field hOverlay;         // HudNaviManager.overlayController
    private static Class<?> overlayClass;
    private static Field oLine1;           // HudEtaOverlayController.primaryLine
    private static Field oLine2;           // HudEtaOverlayController.secondaryLine

    // ── 采集结果（全是原样字符串，供 /diag 看）──
    public static volatile String line1;
    public static volatile String line2;
    public static volatile String rendered;
    public static volatile Boolean navigating;
    public static volatile String poiSignature;
    public static volatile String activePoiSignature;
    public static volatile String dispatcherDesc;
    public static volatile String lastError;

    private static volatile long lastPollAt;
    /**
     * 「主动查终点」总开关 —— 2026-09-26 起**默认关闭**。
     *
     * 为什么要关：这个探针不是纯查询。车机语音退出导航后，只要它每 30 秒去 IPC
     * 问一次「当前路线终点」，**深蓝定制版高德（com.wt.mahjong）就会隔几十秒
     * 重新开始导航上次那个目的地**（用户 2026-09-26 实测，239 / 241 两个版本都复现；
     * 原车导航不受影响，只有定制高德会重开；30 秒正好等于这里的限流间隔）。
     *
     * 它本来只是挖 D.apk 字段格式用的探测器，目的已经达到 ⇒ 默认关掉。
     * 真要再看那些字段：/setprobe?on=1 临时打开（重启桌面恢复默认关）。
     */
    public static volatile boolean probeEnabled = false;

    private static volatile long lastProbeAt;
    private static volatile String probeNote = "还没触发";

    private S05Navi() { }

    // ─────────────────────────────────────── 启动

    /** 每秒读一遍。纯字段读取，不阻塞、不发 IPC。 */
    public static void start() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        poll();
                    } catch (Throwable t2) {
                        lastError = String.valueOf(t2);
                    }
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "cardash-s05navi");
        t.setDaemon(true);
        t.start();
        Diagnostics.log("S05Navi 已启动（探测车机原生导航的 IPC 桥）");
    }

    // ─────────────────────────────────────── 反射解析

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            bridgeClass = Class.forName(C_BRIDGE);
            bridge = staticInstance(bridgeClass);
            fDispatcher    = field(bridgeClass, "ipcSkillDispatcher");
            fBound         = field(bridgeClass, "bound");
            fStarted       = field(bridgeClass, "started");
            fLastPoiSig    = field(bridgeClass, "lastPoiSignature");
            fActivePoiSig  = field(bridgeClass, "activeGuidePoiSignature");
            fPendingPoiSig = field(bridgeClass, "pendingSystemStartedPoiSignature");
            fRouteQueryKey = field(bridgeClass, "lastRouteQueryKey");
            // 私有实例方法优先；找不到再退到 Kotlin 生成的合成访问器
            try {
                mQDest = bridgeClass.getDeclaredMethod("queryS05CurrentDestination",
                        String.class, String.class);
                mQDest.setAccessible(true);
            } catch (Throwable ignored) {
                mQDest = null;
            }
            try {
                mQDestAccess = bridgeClass.getDeclaredMethod("access$queryS05CurrentDestination",
                        bridgeClass, String.class, String.class);
                mQDestAccess.setAccessible(true);
            } catch (Throwable ignored) {
                mQDestAccess = null;
            }
        } catch (Throwable t) {
            lastError = "NaviIpcBridge 解析失败: " + t;
            Diagnostics.log(lastError);
        }

        try {
            hudClass = Class.forName(C_HUD);
            hud = staticInstance(hudClass);
            hNavigating = field(hudClass, "isNavigating");
            hLine1      = field(hudClass, "lastPrimaryLine");
            hLine2      = field(hudClass, "lastSecondaryLine");
            hRendered   = field(hudClass, "lastRenderedText");
            hOverlay    = field(hudClass, "overlayController");
        } catch (Throwable t) {
            Diagnostics.log("HudNaviManager 解析失败: " + t);
        }

        try {
            overlayClass = Class.forName(C_OVERLAY);
            oLine1 = field(overlayClass, "primaryLine");
            oLine2 = field(overlayClass, "secondaryLine");
        } catch (Throwable t) {
            oLine1 = null;
            oLine2 = null;
        }
    }

    /** Kotlin object → 静态 INSTANCE 字段 */
    private static Object staticInstance(Class<?> c) {
        try {
            Field f = c.getDeclaredField("INSTANCE");
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field field(Class<?> c, String name) {
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 静态/实例字段都能读：静态字段传实例进去也无害 */
    private static Object read(Field f, Object inst) {
        if (f == null) return null;
        try {
            return f.get(inst);
        } catch (Throwable t) {
            try {
                return f.get(null);
            } catch (Throwable t2) {
                return null;
            }
        }
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o);
        return s.isEmpty() || "null".equals(s) ? null : s;
    }

    // ─────────────────────────────────────── 采集

    private static void poll() {
        resolve();
        lastPollAt = System.currentTimeMillis();

        // ── HudNaviManager：HUD 上那两行字（车机自己算的 ETA/剩余）──
        Object l1 = str(read(hLine1, hud));
        Object l2 = str(read(hLine2, hud));
        Object rd = str(read(hRendered, hud));
        Object nav = read(hNavigating, hud);
        if (l1 == null || l2 == null) {
            // 也可能挂在 overlayController 上（HudEtaOverlayController.primaryLine/secondaryLine）
            Object ov = read(hOverlay, hud);
            if (ov != null) {
                if (l1 == null) l1 = str(read(oLine1, ov));
                if (l2 == null) l2 = str(read(oLine2, ov));
            }
        }
        line1 = (String) l1;
        line2 = (String) l2;
        rendered = (String) rd;
        if (nav instanceof Boolean) navigating = (Boolean) nav;

        // 车机自己在 HUD 上显示的两行 → 也塞进 /state，方便对照
        StateHub hub2 = StateHub.get();
        hub2.carNavLine1 = line1;
        hub2.carNavLine2 = line2;
        if (navigating != null) {
            hub2.setSource("s05navi", navigating ? "navigating" : "idle");
        }

        // ── NaviIpcBridge：IPC 状态 + POI 指纹 ──
        Object disp = read(fDispatcher, bridge);
        dispatcherDesc = disp == null ? "null" : disp.getClass().getName();
        Object bound = read(fBound, bridge);
        Object started = read(fStarted, bridge);
        poiSignature = str(read(fLastPoiSig, bridge));
        activePoiSignature = str(read(fActivePoiSig, bridge));
        String pending = str(read(fPendingPoiSig, bridge));
        String routeKey = str(read(fRouteQueryKey, bridge));

        StringBuilder sb = new StringBuilder(160);
        sb.append("dispatcher=").append(dispatcherDesc)
          .append(" bound=").append(bound)
          .append(" started=").append(started);
        if (routeKey != null) sb.append(" queryKey=").append(routeKey);
        if (pending != null) sb.append(" pending=").append(pending);
        hub2.setSource("s05ipc", sb.toString());
    }

    // ─────────────────────────────────────── 主动查终点（按需）

    /**
     * 让 D.apk 自己去 IPC 查一次「当前路线终点」。
     *
     * 这是 D.apk 自己的逻辑（日志里的「主动查询当前路线终点 trigger=」），
     * 我们只是反射调一下它那个私有方法 —— 结果会落到它自己的字段/回调里，
     * 靠 poll() 读出来。
     *
     * 由 /diag、/logcat 触发（30 秒限流），不要在轮询里调，免得刷屏。
     */
    public static void probeDestination(String trigger) {
        if (!probeEnabled) {
            probeNote = "已关闭（默认关：它会让定制高德退出导航后自动重开导航；开：/setprobe?on=1）";
            return;
        }
        resolve();
        long now = System.currentTimeMillis();
        if (now - lastProbeAt < PROBE_MIN_GAP_MS) {
            probeNote = "限流中（距上次 " + ((now - lastProbeAt) / 1000) + " 秒）";
            return;
        }
        lastProbeAt = now;

        if (mQDest == null && mQDestAccess == null) {
            probeNote = "没有 queryS05CurrentDestination（版本不同或已被混淆）";
            return;
        }
        try {
            if (mQDest != null) {
                mQDest.invoke(bridge, trigger, "cardash");
            } else {
                mQDestAccess.invoke(null, bridge, trigger, "cardash");
            }
            probeNote = "已触发（" + trigger + "），几秒后看 s05 字段";
            Diagnostics.log("S05Navi 主动查终点: " + trigger);
        } catch (Throwable t) {
            probeNote = "调用失败: " + t;
            Diagnostics.log("S05Navi 查终点失败: " + t);
        }
    }

    // ─────────────────────────────────────── 报告

    /** 给 /diag、/logcat 用 */
    public static String report() {
        resolve();
        StringBuilder sb = new StringBuilder(1024);
        sb.append("  bridge 类      = ").append(bridgeClass == null ? "没找到" : C_BRIDGE).append('\n');
        sb.append("  dispatcher     = ").append(dispatcherDesc == null ? "还没读" : dispatcherDesc).append('\n');
        sb.append("  POI 指纹(上次) = ").append(poiSignature == null ? "--" : poiSignature).append('\n');
        sb.append("  POI 指纹(当前) = ").append(activePoiSignature == null ? "--" : activePoiSignature).append('\n');
        sb.append("  HUD 第一行     = ").append(line1 == null ? "--" : line1).append('\n');
        sb.append("  HUD 第二行     = ").append(line2 == null ? "--" : line2).append('\n');
        sb.append("  最近渲染文本   = ").append(rendered == null ? "--" : rendered).append('\n');
        sb.append("  导航中标志     = ").append(navigating == null ? "--" : navigating).append('\n');
        sb.append("  距上次采集     = ").append(lastPollAt == 0 ? "从未"
                : ((System.currentTimeMillis() - lastPollAt) / 1000) + " 秒前").append('\n');
        sb.append("  主动查终点     = ").append(probeNote).append('\n');
        if (lastError != null) sb.append("  错误           = ").append(lastError).append('\n');
        return sb.toString();
    }
}
