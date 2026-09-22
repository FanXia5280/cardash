package com.cardash.inject;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

/**
 * 高德车机版的引导信息广播 —— 导航数据的正解。
 *
 * 移植自用户的另一个项目 S05HudTrafficLight（那个已经在真机上验证过）。
 * 高德用**隐式广播** {@code AUTONAVI_STANDARD_BROADCAST_SEND} 对外发数据，
 * 任何 App 动态注册都能收（Android 8+ 禁止清单静态注册收隐式广播，必须动态注册）。
 *
 * <pre>
 * KEY_TYPE = 10001  引导信息（导航和巡航都发），这是我们要的：
 *   ROUTE_REMAIN_DIS   到终点还有多少米   → 顶栏「201 公里」
 *   ROUTE_REMAIN_TIME  还要多久（秒）      → 顶栏「48 分钟」
 *   ETA_TEXT           预计到达时间文案    → 导航卡片第三行
 *   CUR_ROAD_NAME      当前路名            → 导航卡片「高速路」
 *   NEXT_ROAD_NAME     下一段路名
 *   SAPA_DIST          到转向点多远（米）  → 导航卡片「110 米」
 *   SAPA_TYPE          转向类型            → 画箭头
 *   CUR_SPEED          高德的车速          → 车速兜底
 *   LIMITED_SPEED      限速
 *   routeRemainTrafficLightNum  剩余红绿灯数
 *   CAMERA_DIST/TYPE/SPEED      电子眼
 *
 * KEY_TYPE = 10019  驾驶模式：EXTRA_STATE 8=导航 9/24/25=巡航 其它=空闲
 * </pre>
 *
 * 注意：这台车上的高德包名是 {@code com.wt.mahjong}（深蓝定制版），不是 amapauto。
 */
public final class AmapSignals {

    private static final String ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND";

    private static final int TYPE_GUIDE = 10001;
    private static final int TYPE_STATE = 10019;

    /** 当前模式：0 空闲 / 1 导航 / 2 巡航 */
    private static volatile int mode;
    private static volatile long lastGuideAt;
    private static volatile String lastRaw = "";

    private static final BroadcastReceiver RECEIVER = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                handle(intent);
            } catch (Throwable t) {
                Diagnostics.log("高德广播解析失败: " + t);
            }
        }
    };

    private AmapSignals() { }

    public static void register(Context ctx) {
        IntentFilter f = new IntentFilter(ACTION);
        // Android 13+ 动态注册非系统广播必须显式声明导出标志
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(RECEIVER, f, Context.RECEIVER_EXPORTED);
        } else {
            ctx.registerReceiver(RECEIVER, f);
        }
        StateHub.get().setSource("amap", "listening");
        Diagnostics.log("已注册高德广播监听: " + ACTION);
    }

    private static void handle(Intent intent) {
        if (intent == null) return;
        int keyType = intent.getIntExtra("KEY_TYPE", -1);
        if (keyType == TYPE_GUIDE) {
            onGuide(intent);
        } else if (keyType == TYPE_STATE) {
            onState(intent);
        }
    }

    private static void onGuide(Intent i) {
        StateHub hub = StateHub.get();

        int sapaDist   = i.getIntExtra("SAPA_DIST", -1);
        int sapaType   = i.getIntExtra("SAPA_TYPE", -1);
        int remainDis  = i.getIntExtra("ROUTE_REMAIN_DIS", -1);
        int remainTime = i.getIntExtra("ROUTE_REMAIN_TIME", -1);
        int speed      = i.getIntExtra("CUR_SPEED", -1);
        int limit      = i.getIntExtra("LIMITED_SPEED", -1);
        int lights     = i.getIntExtra("routeRemainTrafficLightNum", -1);

        String eta      = str(i.getStringExtra("ETA_TEXT"));
        String road     = str(i.getStringExtra("CUR_ROAD_NAME"));
        String nextRoad = str(i.getStringExtra("NEXT_ROAD_NAME"));

        lastGuideAt = System.currentTimeMillis();
        lastRaw = "dist=" + sapaDist + " type=" + sapaType + " remain=" + remainDis
                + " time=" + remainTime + " road=" + road + " limit=" + limit;

        // ── 导航卡片：转向距离 / 路名 / 第二个距离 ──
        if (sapaDist >= 0) hub.navDistance = fmtDistance(sapaDist);
        if (road != null) hub.navSub = road;
        if (nextRoad != null) hub.navAfter = nextRoad;

        // 转向类型。SAPA_TYPE 高德没公开，这里按常见取值猜，
        // 猜不出来就用路名兜底，原始值记进 src 方便校准。
        String turn = turnOfType(sapaType);
        if (turn == null) turn = NaviSignals.turnOfText(road);
        if (turn != null) hub.navTurn = turn;
        hub.setSource("amapType", String.valueOf(sapaType));

        // ── 顶栏中间：还有多久 / 还有多远 ──
        if (remainTime >= 0) hub.navEta = fmtDuration(remainTime);
        if (remainDis >= 0) hub.navRemain = fmtDistance(remainDis);
        if (eta != null) hub.navArrive = eta;

        // ── 车速：**故意不采用高德广播的 CUR_SPEED** ──
        // 用户要求只显示原车数据、和车机仪表盘一致。高德是第三方导航软件，
        // 它给的车速（GPS 推算）和车机自身会有偏差，所以这里只把它记进
        // 诊断信息，绝不写进 hub.speedKmh。
        if (speed >= 0) hub.setSource("amapSpeed", String.valueOf(speed) + "（仅诊断，不采用）");

        if (limit > 0) hub.setSource("limit", String.valueOf(limit));
        if (lights >= 0) hub.setSource("lights", String.valueOf(lights));

        hub.navActive = true;
        hub.navUpdatedAt = lastGuideAt;
        hub.navSource = "amap";
        hub.setSource("amap", "guide");
    }

    private static void onState(Intent i) {
        int st = i.getIntExtra("EXTRA_STATE", -1);
        int m = (st == 8) ? 1 : ((st == 9 || st == 24 || st == 25) ? 2 : 0);
        if (m == mode) return;
        mode = m;
        Diagnostics.log("高德驾驶模式 -> " + (m == 1 ? "导航中" : m == 2 ? "巡航中" : "空闲")
                + " (EXTRA_STATE=" + st + ")");
        StateHub hub = StateHub.get();
        hub.setSource("amap", m == 1 ? "navigating" : m == 2 ? "cruising" : "idle");
        if (m == 0) {
            // 导航和巡航都退出了，清掉实时数据，免得仪表盘上残留旧信息
            hub.navActive = false;
            hub.navTurn = null;
            hub.navEta = null;
            hub.navRemain = null;
            hub.navArrive = null;
            hub.navAfter = null;
            hub.navDistance = null;
        }
    }

    /** 给 /logcat 用 */
    public static String rawSummary() {
        return "  模式 = " + (mode == 1 ? "导航中" : mode == 2 ? "巡航中" : "空闲")
                + "   距上次引导信息 = "
                + (lastGuideAt == 0 ? "从未" : ((System.currentTimeMillis() - lastGuideAt) / 1000) + " 秒前")
                + "\n  最后一条 = " + lastRaw + "\n";
    }

    // ─────────────────────────────────────── 工具

    /** SAPA_TYPE → 转向类型。高德没公开这个枚举，猜不出来时由调用方用路名兜底。 */
    private static String turnOfType(int t) {
        switch (t) {
            case 1: return "left";
            case 2: return "right";
            case 3: return "slightLeft";
            case 4: return "slightRight";
            case 7: return "straight";
            case 8: return "left";
            case 9: return "right";
            case 10: return "uturn";
            case 11: return "round";
            case 12: return "arrive";
            default: return null;
        }
    }

    /** 米 → 「80 米」/「2.3 公里」 */
    public static String fmtDistance(int meters) {
        if (meters < 0) return null;
        if (meters < 1000) return meters + " 米";
        double km = meters / 1000.0;
        return (Math.round(km * 10.0) / 10.0) + " 公里";
    }

    /** 秒 → 「6 分钟」/「1 小时 20 分钟」 */
    public static String fmtDuration(int seconds) {
        if (seconds < 0) return null;
        int min = seconds / 60;
        if (min < 60) return Math.max(1, min) + " 分钟";
        int h = min / 60;
        int m = min % 60;
        return m == 0 ? (h + " 小时") : (h + " 小时 " + m + " 分钟");
    }

    private static String str(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() || "null".equalsIgnoreCase(t) ? null : t;
    }
}
