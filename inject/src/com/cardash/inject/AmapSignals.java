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
    /** 最近一条引导广播，用来在 /logcat 里原样 dump 全部 extras */
    private static volatile Intent lastIntent;

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
        // 留一份原始 Intent 给 /logcat 的全量 extras dump。
        // ⚠️ 2026-09-24 之前这里**漏了赋值**（声明了、读了、从没写过），
        //    所以 /logcat 里那段「最近一条引导广播的全部 extras」永远是
        //    "（还没收到过广播）" —— 而它正是用来核对 key 真名的工具。
        lastIntent = intent;

        int keyType = intent.getIntExtra("KEY_TYPE", -1);
        noteKeyType(keyType, intent);

        // 目的地：**每一条广播都翻一遍**（KEY_TYPE 各家版本不一样，与其猜不如全试）。
        // 用户 2026-09-24 的要求：车机上手动点导航，IPA 也要能识别目的地 ——
        // 手动导航没有语音日志，只能从高德自己的广播里拿。只读，不干扰。
        //
        // ⚠️ 2026-09-25 第二轮起多了一道闸门（在 DestSignals 里）：目的地必须被
        //    **带活路线的引导广播**背书才采信。因为停车不导航时，那些周期广播
        //    里也夹着 POI 载荷 —— 不挡的话车机早退出导航了，IPA 又会"自己开始导航"。
        try {
            DestSignals.onAmapBroadcast(intent, keyType);
        } catch (Throwable t) {
            Diagnostics.log("高德目的地解析失败: " + t);
        }

        if (keyType == TYPE_GUIDE) {
            onGuide(intent);
        } else if (keyType == TYPE_STATE) {
            onState(intent);
        }

        // ⚠️ 每一条广播都顺手问一次"导航是不是已经结束了"。
        //    为什么放在这里、而不是只靠"驾驶模式变化"那一刻：
        //    车机一退出导航，高德**一条 10001 都不发了**，但各种心跳广播一直在发
        //    （实测 12205 每秒一条、10019 每秒 1.4 条）—— 正好拿它们当"定时器"，
        //    于是退出检测的延迟就等于 GUIDE_END_MS 本身（15 秒），
        //    而不是"15 秒 + 下一个心跳"（那会拖到 25 秒）。
        maybeEndIfStale();
    }

    /**
     * 车机高德现在是不是**真的在导航**。
     *
     * <p>⚠️ 2026-09-25 第二轮**重新定义**：只看**"引导广播里还有活路线"**
     * —— 引导广播里出现了 {@code ROUTE_REMAIN_DIS / ROUTE_REMAIN_TIME > 0}，
     * 而且是最近 {@link #GUIDE_END_MS} 内的事（见 {@link #lastLiveRouteAt}）。
     *
     * <p>为什么把以前两条判据都废掉（都是实车日志打脸）：
     * <ul>
     *   <li><b>EXTRA_STATE=8（"导航中"）不能当判据</b>：09-24 19:47~21:14 用户人在办公室、
     *       车停着没导航，日志里「高德驾驶模式 -> 导航中 (8)」**每分钟来一次**
     *       （和 40/315/316/2001 这些"空闲"值一对一对地刷）。拿它当判据 ⇒
     *       车机早就退出导航了，IPA 还当在导航 —— 用户报的「车机退出导航,IPA 不退出」。</li>
     *   <li><b>"收到过引导广播"也不能当判据</b>：引导信息（KEY_TYPE=10001）**巡航时也发**
     *       （见类注释），所以"收到引导"≠"有一条在跑的路线"。</li>
     * </ul>
     *
     * <p>而"还有多远 / 多久"只有真有一条路线时才有值 ⇒ 拿它当判据。
     * 导航中实测引导广播每秒一条、最长断流 7 秒 ⇒ {@link #GUIDE_END_MS} 取 30 秒（4 倍余量）。
     */
    public static boolean navigating() {
        long t = lastLiveRouteAt;
        return t > 0 && (System.currentTimeMillis() - t) < GUIDE_END_MS;
    }

    /**
     * 引导广播里**带活路线**（还有多远 / 多久 > 0）的最后时刻 ——
     * 这是"车机真在导航"的唯一判据，见 {@link #navigating()}。
     */
    private static volatile long lastLiveRouteAt;

    /**
     * 活路线断这么久 = 导航结束。
     *
     * <p>⚠️ 2026-09-25 第三轮从 30 秒收到 **15 秒**。两条实测依据：
     * <ul>
     *   <li>导航中引导广播实测 **2 条/秒**（本轮日志：10001 共 68 条 / 33 秒），
     *       最长断流 12 秒 ⇒ 15 秒略微高于最坏情况；</li>
     *   <li>车机**一退出导航，10001 就完全停了**（前一份日志里 12:09:19~12:09:50
     *       车停着没导航，31 秒里一条 10001 都没有，会话判据一直判"已结束"）
     *       ⇒ 用"多久没活路线"当退出信号是可靠的。</li>
     * </ul>
     * 用户要求"车机退出导航 IPA 也自动退出"，所以宁可贴着最坏情况取值，
     * 也不要为了保险留到 30 秒 —— 那是用户能明显感觉到的等待。
     */
    private static final long GUIDE_END_MS = 15000L;

    /**
     * 从"驾驶模式变成空闲/巡航"起，活路线断了这么久就收尾。
     *
     * <p>⚠️ 和 {@link #GUIDE_END_MS} 取同一个值：EXTRA_STATE 会 8↔40 每分钟翻一次
     *（**导航中也会**），所以"快收"路径在行车途中同样会被触发，不能比它更激进 ——
     * 否则一次隧道/重算就会把正在跑的导航掐掉（用户的第一要求：不中断）。
     */
    private static final long FAST_END_MS = 15000L;

    /** {@link #navigating()} 连续不成立是从什么时候开始的（0 = 现在还成立） */
    private static volatile long notNavigatingSince;

    /**
     * 导航会话看门狗（兜底）—— 由 {@link BridgeRuntime} 的心跳线程每 10 秒调一次。
     *
     * <p>真正的判定在 {@link #maybeEndIfStale()}，而它**每一条高德广播都会调一次**
     * （实测每秒 2~3 条）⇒ 退出检测的延迟≈{@link GUIDE_END_MS} 本身（15 秒）。
     * 这个方法只是"万一广播全停了"的兜底。
     *
     * <p>⚠️ 为什么必须**周期性地**判（2026-09-25 修）：以前清导航字段只发生在
     * 「驾驶模式变化的那一刻」，那一刻引导广播可能还新鲜（用户实测是 30 秒前）
     * ⇒ 判定失败之后就**再也不会重判** ⇒ 车机早就退出导航了，
     * iPhone 顶栏一直挂着「15 分钟 6.7 公里」（用户报的 bug）。
     */
    public static void tick() {
        if (navigating()) {
            notNavigatingSince = 0;
            return;
        }
        // notNavigatingSince 现在只是**诊断用**（/logcat 里显示"不成立多少秒"）：
        // 真正的判定统一在 maybeEndIfStale() 里，且每一条广播都会调一次，
        // 所以这个 10 秒心跳只是兜底（万一广播全停了）。
        if (notNavigatingSince == 0) notNavigatingSince = System.currentTimeMillis();
        maybeEndIfStale();
    }

    /**
     * 导航是不是已经结束了？结束了就把**导航字段 + 目的地一起**收干净。
     *
     * <p>由 {@link #handle}（每一条高德广播，实测每秒 2~3 条）和
     * {@link #tick}（10 秒心跳，兜底）调用 ⇒ 检测延迟≈{@link GUIDE_END_MS} 本身。
     */
    private static void maybeEndIfStale() {
        long live = lastLiveRouteAt;
        // 本次开机**从没出现过活路线** ⇒ 没有会话要收。
        // ⚠️ 少了这个闸门，每次"空闲"状态变化都会打一条「导航会话结束」，
        //    日志被刷满还容易误判（用户上一轮抓的日志里就是这样）。
        if (live == 0) return;
        long age = System.currentTimeMillis() - live;
        if (age < FAST_END_MS) return;      // 还在导航（实测最长断流 12 秒）
        endSession(age);
    }

    /**
     * 导航会话真的结束了：**导航字段 + 目的地一起收干净**。
     *
     * <p>导航字段（顶栏的「还有多久 / 多远 / 几点到」、转向）和目的地是两套东西，
     * 少清一套就会出现「地图已经回普通地图了，顶栏还挂着导航摘要」这种半死状态
     *（用户 2026-09-25 实测就是这个）。
     */
    private static void endSession(long liveGapMs) {
        clearNavFields(StateHub.get());
        DestSignals.endSession();
        maxGuideGapMs = 0;
        maxGuideGapAt = 0;
        // 活路线清零：下次必须**重新出现活路线**才算新会话。
        // 不然"退出导航后高德还缓存着几秒旧引导"会立刻把会话又点亮。
        lastLiveRouteAt = 0;
        notNavigatingSince = 0;
        // 把"为什么判结束"写进日志 —— 以后再说"IPA 没退出"，看这一行就能分清
        // 是我们判早了/判晚了，还是**压根没判**（后者说明高德退出导航后还在发活路线，
        // 那就得换判据，光调时间窗没用）。
        Diagnostics.log("导航会话结束（活路线已断 " + (liveGapMs / 1000) + " 秒）："
                + "导航字段 + 目的地已清空");
    }

    /** 顶栏摘要/转向这些都从这几个字段来，会话结束时必须一起清 */
    private static void clearNavFields(StateHub hub) {
        hub.navActive = false;
        hub.navTurn = null;
        hub.navEta = null;
        hub.navRemain = null;
        hub.navArrive = null;
        hub.navAfter = null;
        hub.navDistance = null;
        hub.navSub = null;
        hub.navTitle = null;
    }

    // ── 校准用：本次导航里引导广播最长断了多久 ──

    /**
     * 本次会话中两次引导广播之间的**最大间隔**。
     *
     * <p>用来校准 {@link #GUIDE_END_MS}：2026-09-25 实车实测这个最大值只有 **7 秒**，
     * 所以阈值已经从 60 秒收到 **30 秒**（4 倍余量），模式变空闲后走
     * {@link #FAST_END_MS}（15 秒）更快。要是以后跑长途这个值涨到 20 秒以上，
     * 说明高德在某些路段会长时间不发引导，那就把阈值调回去。
     * <p>⚠️ 记的是**引导广播之间的间隔**，不再等于"活路线断流"（见 {@link #navigating()}）。
     */
    private static volatile long maxGuideGapMs;
    private static volatile long maxGuideGapAt;

    private static void onGuide(Intent i) {
        StateHub hub = StateHub.get();

        // 高德的 key 名字在不同版本里不一致（EXTRA_ 前缀的有无），
        // 所以每个字段都按候选表依次取，第一个有值的算数。
        int dist       = pickInt(i, -1, "EXTRA_DISTANCE", "DISTANCE", "SAPA_DIST");
        int icon       = pickInt(i, -1, "EXTRA_ICON", "ICON", "SAPA_TYPE");
        int remainDis  = pickInt(i, -1, "EXTRA_ROUTE_REMAIN_DIS", "ROUTE_REMAIN_DIS");
        int remainTime = pickInt(i, -1, "EXTRA_ROUTE_REMAIN_TIME", "ROUTE_REMAIN_TIME");
        int speed      = pickInt(i, -1, "EXTRA_CUR_SPEED", "CUR_SPEED");
        int limit      = pickInt(i, -1, "EXTRA_LIMIT_SPEED", "LIMITED_SPEED", "EXTRA_LIMITED_SPEED");
        int lights     = pickInt(i, -1, "EXTRA_TRAFFIC_LIGHT_NUM", "routeRemainTrafficLightNum");
        // 电子眼：距离 / 类型 / 该电子眼的限速。iPhone 拿它决定"两边要不要冒红" ——
        // 用户 2026-09-24 强调：高德的红色脉冲是**会被拍限速的电子眼**才冒，
        // 不是超了路段限速就冒（很多电子眼根本不测速）。
        // 类型枚举（高德官方 AMapNaviCameraType）：
        //   0 测速 / 1 监控 / 2 闯红灯 / 3 违章 / 4 公交道 / 5 应急车道 / 6 非机动车道
        //   8 区间测速起始 / 9 区间测速终止
        int camDist    = pickInt(i, -1, "EXTRA_CAMERA_DIST", "CAMERA_DIST");
        int camType    = pickInt(i, -1, "EXTRA_CAMERA_TYPE", "CAMERA_TYPE");
        int camSpeed   = pickInt(i, -1, "EXTRA_CAMERA_SPEED", "CAMERA_SPEED");

        String eta     = pickStr(i, "EXTRA_ETA_TEXT", "ETA_TEXT");
        String curRoad = pickStr(i, "EXTRA_ROAD_NAME", "CUR_ROAD_NAME");
        String nextRoad = pickStr(i, "EXTRA_NEXT_ROAD_NAME", "NEXT_ROAD_NAME");

        // 记下"上一条引导广播到现在隔了多久"的最大值（校准 GUIDE_END_MS 用）
        long now = System.currentTimeMillis();
        if (lastGuideAt > 0) {
            long gap = now - lastGuideAt;
            if (gap > maxGuideGapMs) {
                maxGuideGapMs = gap;
                maxGuideGapAt = now;
            }
        }
        lastGuideAt = now;
        lastRaw = "dist=" + dist + " icon=" + icon + " remain=" + remainDis
                + " time=" + remainTime + " cur=" + curRoad + " next=" + nextRoad
                + " limit=" + limit;

        // ── "活路线"证据（这是"在导航"的唯一判据，见 navigating()）──
        // "还有多远 / 多久 > 0" 只有真有一条在跑的路线时才有值：
        //   * 导航中：实测 remain=2457 time=430（每秒一条引导）
        //   * 巡航（没目的地）：没有路线 ⇒ 这两个字段给 -1
        // 有了它才能区分"车机在导航"和"车机只是收到了广播"。
        boolean live = remainDis > 0 || remainTime > 0;
        if (live) {
            lastLiveRouteAt = now;
            // 有活路线了 ⇒ 把"等着被背书"的候选目的地转正
            //（手动点导航时，目的地载荷常常比第一条引导广播早到几十毫秒）
            DestSignals.confirmPending();
        }

        // ── 导航卡片：转向距离 + 「进入 XX 路」 ──
        // 车机上高德卡片写的是「↑ 24米 进入 天高路」，其中「天高路」是
        // **转向之后进入**的路，对应 NEXT_ROAD_NAME；
        // ROAD_NAME 是当前所在道路，放第二行。
        if (dist >= 0) hub.navDistance = fmtDistance(dist);
        if (nextRoad != null) {
            hub.navSub = nextRoad;
        } else if (curRoad != null) {
            hub.navSub = curRoad;
        }
        if (curRoad != null && !curRoad.equals(hub.navSub)) hub.navAfter = curRoad;

        // ── 转向图标 ──
        String turn = turnOfIcon(icon);
        if (turn != null) {
            hub.navTurn = turn;
        } else if (icon >= 0) {
            // 认不出的图标编号：记下来，别偷偷当成直行
            hub.setSource("amapIconUnknown", String.valueOf(icon));
        }
        hub.setSource("amapIcon", String.valueOf(icon));

        // 每次引导都记一条，凑够一趟车就能对着实际路况校准图标表
        if (icon >= 0 || dist >= 0) {
            recordIcon(icon, dist, nextRoad != null ? nextRoad : curRoad);
        }

        // ── 顶栏中间：还有多久 / 还有多远 ──
        if (remainTime >= 0) hub.navEta = fmtDuration(remainTime);
        if (remainDis >= 0) hub.navRemain = fmtDistance(remainDis);
        if (eta != null) hub.navArrive = eta;

        // ── 车速：**故意不采用高德广播的 CUR_SPEED** ──
        // 用户要求只显示原车数据、和车机仪表盘一致。高德是第三方导航软件，
        // 它给的车速（GPS 推算）和车机自身会有偏差，所以这里只把它记进
        // 诊断信息，绝不写进 hub.speedKmh。
        if (speed >= 0) hub.setSource("amapSpeed", String.valueOf(speed) + "（仅诊断，不采用）");

        // 限速：车机高德广播里本来就有（LIMITED_SPEED）。以前只记诊断没下发，
        // 现在给 iPhone 用 —— 它拿这个跟车速比，超速就两边冒红
        // （高德 SDK 那个 showOverSpeedPulse 是收费接口，我们自己做一份不依赖它）。
        // 0 = 当前路段没有限速（高德约定），所以 0 要当成"清空"，别留着上一段的限速。
        if (limit >= 0) hub.speedLimit = (limit > 0) ? Integer.valueOf(limit) : null;
        if (limit > 0) hub.setSource("limit", String.valueOf(limit));

        // 电子眼：每条引导广播覆盖一次（没有就置空 —— 车开过去之后必须消失，
        // 不然会一直以为前方有测速）。iPhone 只看"会拍限速的类型 + 超速"来决定冒不冒红。
        hub.cameraDist = (camDist >= 0) ? Integer.valueOf(camDist) : null;
        hub.cameraType = (camType >= 0) ? Integer.valueOf(camType) : null;
        hub.cameraSpeed = (camSpeed > 0) ? Integer.valueOf(camSpeed) : null;
        if (camDist >= 0 || camType >= 0 || camSpeed >= 0) {
            hub.setSource("camera", "dist=" + camDist + " type=" + camType + " speed=" + camSpeed);
        }
        if (lights >= 0) hub.setSource("lights", String.valueOf(lights));

        hub.navActive = true;
        hub.navUpdatedAt = lastGuideAt;
        // 来源仲裁：高德优先级最高，永远抢得到（原厂导航/通知栏那几路会被它压住，
        // 免得两个导航 App 同时开时顶栏数字来回跳，见 StateHub.navClaim）。
        hub.navClaim("amap");
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
        // ⚠️ 这里**不再**判"导航结束"：EXTRA_STATE 本身不可信（停车时也每分钟发 8），
        //    而 handle() 的末尾对**每一条广播**都会调 maybeEndIfStale()，
        //    判据是"活路线断了多久"，那条路已经覆盖了这里。
    }

    /**
     * 真的结束导航了吗？
     *
     * ⚠️ 2026-09-23 实测：导航中 EXTRA_STATE 会 **8 → 40 → 8 每分钟翻一次**
     * （log 里一整串「导航中(8) / 空闲(40)」就是这么来的）。
     * 之前的写法是「一看到空闲就把 navTurn/navEta/... 全清掉」，
     * 于是仪表每分钟闪一次「导航没了」，而且随后 navActive=false 又让
     * iPhone 那边的导航栏整块消失 —— 这是个大坑，别改回去。
     *
     * 现在只看**引导广播**断没断：超过 60 秒没有引导信息才算导航结束。
     * （引导广播在真正导航时每秒都来，所以这个判据很稳。）
     */
    // 说明：以前这里叫 maybeClearIfNaviEnded（只在"驾驶模式变化的那一刻"判一次）。
    // ⚠️ 那种写法有两个坑，2026-09-25 已经全部换掉，别再改回去：
    //   ① 只在模式变化时判 ⇒ 那一刻引导广播可能还新鲜，判定失败之后就**再也不重判**
    //      （用户上一轮报的"顶栏摘要挂着不消失"就是这么来的）；
    //   ② 模式本身不可信（EXTRA_STATE=8 停车时也每分钟发）。
    // 现在统一由 maybeEndIfStale() 判，而且**每一条广播都会调一次**。

    /** 本次导航里引导广播最长断了多久（毫秒）—— 校准 GUIDE_END_MS 用，见字段注释 */
    public static long maxGuideGapMs() { return maxGuideGapMs; }

    /** 那个最长间隔发生在什么时候（毫秒时间戳，0 = 没有） */
    public static long maxGuideGapAt() { return maxGuideGapAt; }

    /** 给 /logcat 用 */
    public static String rawSummary() {
        long now = System.currentTimeMillis();
        return "  模式 = " + (mode == 1 ? "导航中" : mode == 2 ? "巡航中" : "空闲")
                + "（⚠️ EXTRA_STATE 本身不可信：停车不导航时也每分钟发 8）"
                + "   距上次引导信息 = "
                + (lastGuideAt == 0 ? "从未" : ((now - lastGuideAt) / 1000) + " 秒前")
                + "\n  活路线     = "
                + (lastLiveRouteAt == 0 ? "本次开机还没有过（= 车机没在导航）"
                        : ((now - lastLiveRouteAt) / 1000) + " 秒前")
                + "   ← **这才是判据**：引导广播里 remain/time > 0 才算真在导航"
                + "\n  结论       = " + (navigating() ? "车机在导航" : "车机不在导航")
                + "（断 " + (GUIDE_END_MS / 1000) + " 秒算结束；模式变空闲后 " + (FAST_END_MS / 1000) + " 秒就收）"
                + "\n  目的地来源 = " + (DestSignals.source() == null ? "--" : DestSignals.source())
                + "   会话看门狗 = " + (notNavigatingSince == 0 ? "在导航/未开始"
                        : ("不成立 " + ((now - notNavigatingSince) / 1000) + " 秒"))
                + "   （本次导航最长断流 = "
                + (maxGuideGapMs == 0 ? "--" : (maxGuideGapMs / 1000) + " 秒）")
                + "\n  最后一条 = " + lastRaw + "\n";
    }

    /** 给 /logcat 用：图标校准表 */
    public static String iconCalibration() {
        return "【高德转向图标校准（开一趟车对着实际转弯看）】\n" + iconLogText();
    }

    /** 给 /logcat 用：最近一条引导广播的全部 extras */
    public static String extrasAll() {
        return "【最近一条引导广播的全部 extras】\n" + extrasDump();
    }

    // ─────────────────────────────────────── 广播类型统计（诊断）

    /** 一种 KEY_TYPE 的统计 */
    private static final class KStat {
        long count;
        long firstAt;
        long lastAt;
        String keys = "";
    }

    private static final java.util.LinkedHashMap<Integer, KStat> KEYTYPE =
            new java.util.LinkedHashMap<>();

    /**
     * 记一次"收到哪种 KEY_TYPE 的广播"。
     *
     * <p>为什么要有这个：「车机到底还在不在导航」这件事，以前只能靠
     * EXTRA_STATE / 引导广播间接猜，而两个都不靠谱（见 {@link #navigating()}）。
     * 把每种 KEY_TYPE 的**条数 / 首次 / 最近 / 关键字段名**列出来，下一份日志就能
     * 直接回答三个问题：
     * <ol>
     *   <li>停车、没导航时，10001（引导）**还在不在发**？</li>
     *   <li>哪个 KEY_TYPE 在**夹带目的地载荷**（TO_POI_* 之类）？</li>
     *   <li>高德退出导航时**有没有专门的"导航结束"广播**（有的话就能秒退，不用等 30 秒）？</li>
     * </ol>
     * 只记前 12 种，免得新类型无限涨。
     */
    private static void noteKeyType(int keyType, Intent i) {
        if (keyType == -1) return;
        long now = System.currentTimeMillis();
        synchronized (KEYTYPE) {
            KStat s = KEYTYPE.get(keyType);
            if (s == null) {
                // 上限 24 种：实测这台车一次导航就出现 12 种以上，
                // 而"有没有专门的导航结束广播"就藏在那些少见类型里（别卡在 12）。
                if (KEYTYPE.size() >= 24) return;
                s = new KStat();
                s.firstAt = now;
                s.keys = keyNames(i);
                KEYTYPE.put(keyType, s);
            }
            s.count++;
            s.lastAt = now;
        }
    }

    /** 一条广播的 extras 字段名（最多 6 个，只给诊断看） */
    private static String keyNames(Intent i) {
        android.os.Bundle b = i.getExtras();
        if (b == null || b.isEmpty()) return "(无 extras)";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String k : new java.util.TreeSet<>(b.keySet())) {
            if (n++ >= 6) {
                sb.append(" …");
                break;
            }
            if (sb.length() > 0) sb.append(' ');
            sb.append(k);
        }
        return sb.toString();
    }

    // ─────────────────────────────────────── 高德自己的"退出导航"日志（诊断）

    /**
     * 高德自己打出来的、像是"进/出导航"的日志行（最近 8 条）。
     *
     * <p>为什么留着：车机退出导航这件事，高德**没有**给我们广播（这一版没找到），
     * 所以现在只能靠"活路线断多久"去猜（{@link #GUIDE_END_MS} = 15 秒）。
     * 但高德 App 自己极可能打了"退出导航/结束导航"之类的日志 ——
     * 一旦在真机上看到原文，就能把它变成**秒级**的退出触发，不用再等 15 秒。
     * 这一块纯粹是"取证"，不参与任何判断。
     */
    private static final java.util.ArrayDeque<String> NAVLOG = new java.util.ArrayDeque<>();

    /** 由 {@code LogcatSignals} 把每一行 logcat 喂进来（只做关键字粗筛，很便宜） */
    public static void noteLogLine(String line) {
        if (line == null || line.length() < 6) return;
        if (line.indexOf("导航") < 0) return;                    // 粗筛 1：必须提到导航
        // 粗筛 2：必须是高德自己打的（避免把别的 App 的日志记进来）
        boolean fromAmap = line.indexOf("mahjong") >= 0
                || line.indexOf("utonavi") >= 0           // AutoNavi / autonavi
                || line.indexOf("amap") >= 0
                || line.indexOf("高德") >= 0;
        if (!fromAmap) return;
        // 粗筛 3：必须带"结束"语义的词
        if (line.indexOf("退出") < 0 && line.indexOf("结束") < 0
                && line.indexOf("停止") < 0 && line.indexOf("取消") < 0
                && line.indexOf("exit") < 0 && line.indexOf("stop") < 0) return;
        String s = line.length() > 200 ? line.substring(0, 200) + "…" : line;
        synchronized (NAVLOG) {
            if (!s.equals(NAVLOG.peekLast())) {
                NAVLOG.addLast(s);
                while (NAVLOG.size() > 8) NAVLOG.pollFirst();
            }
        }
    }

    /** 给 /logcat 用 */
    public static String navLogText() {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("  （看看高德自己有没有打\"退出/结束导航\"的日志 —— 有的话\n");
        sb.append("    就能把退出检测从 15 秒缩到 1 秒内，直接把原文发我）\n");
        synchronized (NAVLOG) {
            if (NAVLOG.isEmpty()) {
                sb.append("  （还没抓到；在车机上退出一次导航再看这里）\n");
            } else {
                for (String s : NAVLOG) sb.append("  ").append(s).append('\n');
            }
        }
        return sb.toString();
    }

    /** 给 /logcat 用：广播类型统计 */
    public static String keyTypeStats() {
        StringBuilder sb = new StringBuilder(768);
        long now = System.currentTimeMillis();
        synchronized (KEYTYPE) {
            if (KEYTYPE.isEmpty()) return "  （还没收到过高德广播）\n";
            for (java.util.Map.Entry<Integer, KStat> e : KEYTYPE.entrySet()) {
                KStat s = e.getValue();
                sb.append("  KEY_TYPE=").append(e.getKey())
                  .append("   共 ").append(s.count).append(" 条")
                  .append("   最近 ").append((now - s.lastAt) / 1000).append(" 秒前")
                  .append("   首次 ").append((now - s.firstAt) / 1000).append(" 秒前")
                  .append("\n      字段: ").append(s.keys)
                  .append('\n');
            }
        }
        sb.append("  ⚠️ 看这张表就能断定：车机停车/退出导航后 10001（引导）还在不在发、\n");
        sb.append("     哪个类型在夹带目的地载荷(TO_POI_*/NAVI_INFO)、有没有\"导航结束\"广播。\n");
        return sb.toString();
    }

    // ─────────────────────────────────────── 转向图标

    private static final int ICON_LOG_MAX = 30;
    private static final java.util.ArrayDeque<String> ICON_LOG =
            new java.util.ArrayDeque<>();

    /**
     * 记一条 (图标编号, 距离, 路名)。
     *
     * 为什么要有这个：高德**没有公开** EXTRA_ICON 的枚举，社区流传的版本
     * 还互相矛盾（有的资料说 2=左转，另一些说 2=右转）。与其猜，
     * 不如把每次收到的原始值都记下来 —— 开一趟车，在 /logcat 里
     * 对着实际转弯方向一看就知道哪个编号是什么，然后再校准。
     */
    private static void recordIcon(int icon, int dist, String road) {
        String line = "icon=" + (icon < 0 ? "?" : String.valueOf(icon))
                + "  " + (dist < 0 ? "?" : dist + "米")
                + "  " + (road == null ? "-" : road);
        synchronized (ICON_LOG) {
            if (!line.equals(ICON_LOG.peekLast())) {      // 去掉连续重复
                ICON_LOG.addLast(line);
                while (ICON_LOG.size() > ICON_LOG_MAX) ICON_LOG.pollFirst();
            }
        }
    }

    /** 给 /logcat 用 */
    public static String iconLogText() {
        synchronized (ICON_LOG) {
            if (ICON_LOG.isEmpty()) return "  （还没收到引导信息）\n";
            StringBuilder sb = new StringBuilder();
            for (String s : ICON_LOG) sb.append("  ").append(s).append('\n');
            return sb.toString();
        }
    }

    /**
     * 高德转向图标编号 → 转向类型。
     *
     * ⚠️ 这是**待校准**的映射（见 recordIcon 的说明）。
     * 认不出来时返回 null，调用方会保持上一次的箭头并且记进诊断，
     * 不会偷偷显示成直行 —— 那正是之前「一直显示直线箭头」的原因。
     */
    private static String turnOfIcon(int t) {
        switch (t) {
            case 1:  return "straight";
            case 2:  return "left";
            case 3:  return "right";
            case 4:  return "slightLeft";
            case 5:  return "slightRight";
            case 6:  return "leftUturn";
            case 7:  return "rightUturn";
            case 8:  return "uturn";
            case 9:  return "keepLeft";
            case 10: return "keepRight";
            case 11: return "slightLeft";
            case 12: return "slightRight";
            case 13: return "round";
            case 14: return "arrive";
            default: return null;
        }
    }

    // ─────────────────────────────────────── 工具

    /**
     * 按候选 key 依次取一个整数。
     *
     * ⚠️ 不能直接用 getIntExtra：高德有的版本把数值发成 Double/String，
     * getIntExtra 会抛 ClassCastException，hasExtra 却是 true ——
     * 结果这个字段就整个丢了。所以统一取出 Object 再自己转。
     */
    private static int pickInt(Intent i, int def, String... keys) {
        for (String k : keys) {
            try {
                if (!i.hasExtra(k)) continue;
                Object v = i.getExtras() == null ? null : i.getExtras().get(k);
                if (v instanceof Number) return ((Number) v).intValue();
                if (v instanceof String) {
                    String s = ((String) v).trim();
                    if (!s.isEmpty()) return (int) Double.parseDouble(s);
                }
            } catch (Throwable ignored) {
                // 这个 key 取不出来就试下一个
            }
        }
        return def;
    }

    private static String pickStr(Intent i, String... keys) {
        for (String k : keys) {
            try {
                if (!i.hasExtra(k)) continue;
                String s = str(i.getStringExtra(k));
                if (s != null) return s;
            } catch (Throwable ignored) {
                // 类型不对就试下一个
            }
        }
        return null;
    }

    /**
     * 把所有 extras 原样打出来。
     * 高德改 key 名字的时候，看一眼这个就知道现在的真名是什么。
     */
    public static String extrasDump() {
        Intent i = lastIntent;
        if (i == null) return "  （还没收到过广播）\n";
        android.os.Bundle b = i.getExtras();
        if (b == null || b.isEmpty()) return "  （这一条没有 extras）\n";

        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String k : new java.util.TreeSet<>(b.keySet())) {
            if (n++ > 40) {
                sb.append("  …还有更多\n");
                break;
            }
            Object v = b.get(k);
            String s = v == null ? "null" : String.valueOf(v);
            if (s.length() > 60) s = s.substring(0, 60) + "…";
            sb.append("  ").append(k).append(" = ").append(s).append('\n');
        }
        return sb.toString();
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
