package com.cardash.inject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局状态容器。各个采集器往这里写，HTTP 服务器从这里读。
 * 字段都是 volatile，读取端每次拿到的都是一个完整快照。
 */
public final class StateHub {

    private static final StateHub INSTANCE = new StateHub();

    public static StateHub get() { return INSTANCE; }

    private StateHub() { }

    // ── 车辆信号 ──
    /** km/h */
    /**
     * 车机车速。
     *
     * ⚠️ **不参与显示**。
     *
     * 用户要求仪表上的车速只用 iPhone 自己的 GPS，所以车机侧采集到的速度
     * 一律只落在这里，通过 /state 的 carSpeed 和 /diag 暴露出来做对照，
     * 绝不会画到仪表上。
     *
     * 想改回「用车机车速」：把 iOS 侧 DashboardModel.displaySpeed
     * 换回 car?.speed 就行，车机这边不用动。
     */
    public volatile Double carSpeedKmh;
    /** P / R / N / D */
    public volatile String gear;
    /** 电量百分比 0~100 */
    public volatile Double soc;
    /** 剩余续航 km */
    public volatile Double rangeKm;
    /** 总里程 km */
    public volatile Double odometerKm;
    /** 海拔 m（车机侧若有） */
    public volatile Double altitudeM;
    /** 车机是否已点火（部分信号只在点火后上报） */
    public volatile Boolean ignition;

    /**
     * 当前路段限速（km/h），来自车机高德引导广播的 LIMITED_SPEED。
     * null = 未知/当前路段没有限速。iPhone 拿它和车速比 → 超速就两边冒红。
     */
    public volatile Integer speedLimit;

    /**
     * 前方电子眼：距离（米）／类型（高德 AMapNaviCameraType）／**该电子眼自己的限速**。
     * iPhone 用它决定"两边要不要冒红"：只有 0 测速、8/9 区间测速这类**会拍限速的**，
     * 且车速超过该电子眼限速（没给就用路段限速）时才冒。
     * 三个都为 null = 前方没有电子眼上报（或者车机高德版本不发这些 key）。
     */
    public volatile Integer cameraDist;
    public volatile Integer cameraType;
    public volatile Integer cameraSpeed;

    // ── 转向灯（左右分开，见 turnValue 的"保持"逻辑）──
    /** 左转向灯**最近一次"亮"**是什么时候（0 = 从没见过亮）。见 {@link #TURN_HOLD_MS} */
    public volatile long turnLeftOnAt;
    /** 右转向灯最近一次"亮"是什么时候 */
    public volatile long turnRightOnAt;
    /** 这个别名推过数据吗（推过但最近没亮 ⇒ 显示"灭"；从没推过 ⇒ 整个转向灯不显示） */
    public volatile boolean turnLeftSeen;
    public volatile boolean turnRightSeen;
    /** 合并状态（D.apk 的「灯光 / 转向灯状态」原值，可能是 0/1/2/3 枚举） */
    public volatile Integer turnStatus;

    /**
     * 转向灯"亮"的保持时间。
     *
     * <p>⚠️ 车机的转向灯属性**是跟着灯泡一起闪的**（实测 1↔2 每秒跳两三次，
     * 见 {@code VendorSignals.TURN_OFF_VALUE}）。直接把某一帧的值发出去，
     * iPhone 250ms 轮询到的就是随机 on/off —— 一闪一闪地抖，而不是稳定的
     * "左转灯亮着"。所以这里做保持：**最近 2.5 秒内见过"亮"就算亮着**；
     * 灯关掉后属性停在"灭"（1）不再更新，2.5 秒后自然收掉。
     */
    private static final long TURN_HOLD_MS = 2500L;

    /**
     * 归一化转向灯：**0=灭 1=左 2=右 3=双闪**，null = 完全没数据（iPhone 就别显示）。
     *
     * <p>为什么在这里算而不是在采集端：左灯、右灯是两个独立别名，到达顺序不定。
     * 分开存、最后统一算，结果才与顺序无关。
     */
    public Integer turnValue() {
        if (turnStatus != null) return turnStatus;
        long now = System.currentTimeMillis();
        boolean l = turnLeftOnAt > 0 && (now - turnLeftOnAt) < TURN_HOLD_MS;
        boolean r = turnRightOnAt > 0 && (now - turnRightOnAt) < TURN_HOLD_MS;
        if (l && r) return 3;
        if (l) return 1;
        if (r) return 2;
        if (turnLeftSeen || turnRightSeen) return 0;
        return null;
    }


    // ── 导航 ──
    /** 监听服务收到过多少条通知（判断监听是否真的连上） */
    public volatile long navSeen;
    public volatile boolean navActive;
    /** 转向类型：left/right/slightLeft/slightRight/straight/uturn/round/arrive/merge，给 iPhone 画图标 */
    public volatile String navTurn;
    /** 第二个距离，例如「注意距离 191 米」 */
    public volatile String navAfter;
    /** 剩余时间，例如「48 分钟」—— 显示在顶栏中间 */
    public volatile String navEta;
    /** 剩余总里程，例如「201 公里」—— 显示在顶栏中间 */
    public volatile String navRemain;
    /** 预计到达时间文案，高德 ETA_TEXT 原样带过来 */
    public volatile String navArrive;
    public volatile String navTitle;
    public volatile String navSub;
    public volatile String navDistance;
    public volatile long navUpdatedAt;
    /** 数据来源：notify:<包名> 或 a11y:<包名> */
    public volatile String navSource;
    /**
     * 车机**自己 HUD 上显示的两行字**（反射读 D.apk 的 HudNaviManager）。
     * 只做对照用：iPhone 现在的导航栏还是走高德广播那套，
     * 这两行先原样透出来，方便判断「车机原生导航到底有没有数据」。
     */
    public volatile String carNavLine1;
    public volatile String carNavLine2;

    // ── 诊断 ──
    public final Map<String, String> src = new LinkedHashMap<>();
    public volatile String logcatError;
    public volatile String carError;

    /** 收到过多少条车辆信号日志，用于判断链路是否活着 */
    public volatile long logcatLines;
    public volatile long logcatUpdatedAt;
    /** 含 CarPropertyValue 的行数（不管认不认识） */
    public volatile long logcatSeen;
    /** 正则匹配成功的行数。seen>0 而 matched=0 说明日志格式和预想不一样，看 /logcat */
    public volatile long logcatMatched;

    public void setSource(String key, String value) {
        synchronized (src) {
            src.put(key, value);
        }
    }

    /**
     * 导航数据还新鲜吗（决定 /state 里 nav.active 报不报 true）。
     *
     * ⚠️ 2026-09-24 从 **15 秒放宽到 60 秒**：iPhone 是 250ms 轮询、**单帧就认** ——
     * 只要某一帧报 active:false，它就会把整条路线拆掉（`NaviCoordinator` 收到
     * 目的地 nil 就 `stopNavi()`）。而引导广播偶尔断十几秒是正常的
     * （高德重算路线、切前后台、进隧道），15 秒的窗口太紧，
     * 表现就是"导航自己退出了 / 闪一下从头算路"。
     *
     * 60 秒和 {@link AmapSignals#maybeClearIfNaviEnded} 的判据一致；
     * 真的结束了由那边清 navActive，或者由会话看门狗兜底。
     * 用户明确要求：**中间不中断导航**，宁可多显示一会儿旧数据。
     */
    // ── 导航字段的来源仲裁 ──

    /**
     * 导航数据来源的优先级。
     *
     * ⚠️ 2026-09-24 新增。用户的习惯是**车机原厂导航和第三方高德同时开着**，
     * 而「剩余多久 / 还有多远 / 转向」是同一批槽位 —— 多路来源会互相覆盖，
     * 表现就是顶栏数字在两个 App 之间来回跳。所以按来源仲裁：
     *
     * <pre>
     *   高德广播 (3)  >  通知栏 / 读屏 (2)  >  车身信号 (1)
     * </pre>
     *
     * 高德优先是故意的：它是**唯一**能给目的地的那一路（手动导航也靠它），
     * 而且导航中每秒都在推，最实时。
     */
    private static int navPrio(String src) {
        if (src == null) return 0;
        if (src.startsWith("amap")) return 3;
        if (src.startsWith("notify") || src.startsWith("a11y")) return 2;
        if (src.startsWith("vendor")) return 1;
        return 0;
    }

    /** 当前来源超过这么久没推数据，就算"让位了"，别的来源可以接手 */
    private static final long NAV_TAKEOVER_MS = 5000L;

    /**
     * 这一路来源现在能不能写导航字段（能写就顺手把自己记成当前来源）。
     *
     * <p>规则：优先级不低于当前来源，**或者**当前来源已经 {@value #NAV_TAKEOVER_MS}
     * 毫秒没动静了。
     *
     * <p>效果：高德导航时每秒都在推 ⇒ 通知栏/读屏那几路一直被压着，
     * 两个导航 App 同时开也不会来回跳；反过来高德没在导航时，
     * 通知栏那条 5 秒后就能接手（原厂导航单独用时顶栏照样有数据）。
     *
     * @return true = 可以写；false = 被更高优先级的来源压住了（会记进诊断）
     */
    public synchronized boolean navClaim(String src) {
        long now = System.currentTimeMillis();
        boolean free = navSource == null || now - navUpdatedAt > NAV_TAKEOVER_MS;
        if (free || navPrio(src) >= navPrio(navSource)) {
            navSource = src;
            return true;
        }
        // 被压住了：记进诊断 —— 这样在 /state 的 src 里一眼就能看出
        // "两个导航确实都在推、我们挑了哪一个"，不用靠猜。
        setSource("navIgnored", src + "（当前 " + navSource + " 优先级更高）");
        return false;
    }

    /**
     * 导航数据能不能下发（决定 /state 里 nav.active 报不报 true）。
     *
     * ⚠️ 这里以前是"数据新鲜度"窗口（15 秒 → 60 秒），结果是：iPhone 250ms 轮询、
     * **单帧就认**，某一帧报 active:false 它就把整条路线拆掉
     *（`NaviCoordinator` 收到目的地 nil 立刻 `stopNavi()`）——
     * 而引导广播偶尔断十几秒是正常的（高德重算路线、切前后台、隧道）。
     *
     * ⚠️ 2026-09-25 改成**只认 navActive 这个会话标志**：什么时候置 false 由车机侧
     * 统一判（`AmapSignals.navigating()` + 看门狗：引导广播断 60 秒、或巡航），
     * 这里不再自己按时间窗口猜。**两边各有一套阈值时总有一套会先误判** ——
     * 用户实测的"车机退出导航了，顶栏摘要还挂着"就是这套双阈值错位的后果。
     *
     * 只留一个 10 分钟兜底：万一 navActive 被别的路径卡住，也别一直显示十几天前的旧数据。
     */
    private boolean navFresh() {
        return navActive && (System.currentTimeMillis() - navUpdatedAt) < 600000L;
    }

    /** 车速在 3 秒内没更新就认为链路断了（车不动时车机仍会周期上报，通常没问题） */
    private boolean logcatFresh() {
        return logcatLines > 0 && (System.currentTimeMillis() - logcatUpdatedAt) < 30000L;
    }

    public String toJson() {
        StringBuilder b = new StringBuilder(768);
        b.append('{');
        b.append("\"v\":1");
        b.append(",\"ts\":").append(System.currentTimeMillis());
        // 车速不下发 —— 仪表只用 iPhone 自己的 GPS。
        // 车机测到的值放在 carSpeed 里，仅供诊断对照。
        b.append(",\"carSpeed\":").append(Json.num(carSpeedKmh));
        // 车机导航目的地。有值的话 iPhone 那边会自动跟着导航。
        //
        // 来源有两条（见 DestSignals）：高德广播 = 权威源（手动点导航也能认出来，
        // 只要会话还在就一直下发）；语音 NLU = 兜底（30 分钟窗口）。
        // ⚠️ 只有名字没有坐标的一律不下发 —— iPhone 拿名字去地理编码可能编到
        //    完全另一个地方，那正是"目的地被改掉"。
        if (DestSignals.usable()) {
            b.append(",\"dest\":{\"name\":").append(Json.esc(DestSignals.destName()))
             // 坐标必须 num6：num 只留 2 位小数 ≈ 1.1 公里误差，
             // 就是「IPA 终点和车机不一致 + 切目的地不重算」的根因
             .append(",\"lat\":").append(Json.num6(DestSignals.lat()))
             .append(",\"lon\":").append(Json.num6(DestSignals.lon()))
             .append(",\"src\":").append(Json.esc(DestSignals.source()))
             .append('}');
        }
        // 车机自己 HUD 上的两行导航文字（对照用，iPhone 目前不显示）
        if (carNavLine1 != null || carNavLine2 != null) {
            b.append(",\"carNav\":{\"line1\":").append(Json.esc(carNavLine1))
             .append(",\"line2\":").append(Json.esc(carNavLine2))
             .append('}');
        }
        b.append(",\"gear\":").append(Json.esc(gear));
        b.append(",\"soc\":").append(Json.num(soc));
        b.append(",\"range\":").append(Json.num(rangeKm));
        b.append(",\"odometer\":").append(Json.num(odometerKm));
        b.append(",\"altitude\":").append(Json.num(altitudeM));
        // 限速 → iPhone 自己算超速（两边冒红）；转向灯 → 两边闪绿光
        Integer turn = turnValue();
        b.append(",\"limit\":").append(Json.num(
                speedLimit == null ? null : speedLimit.doubleValue()));
        b.append(",\"turn\":").append(Json.num(
                turn == null ? null : turn.doubleValue()));
        // 前方电子眼（距离/类型/该眼的限速）。iPhone 只认"会拍限速的类型"才冒红，
        // 用户明确要求不要"超了路段限速就冒"那种误报。
        b.append(",\"camera\":");
        if (cameraDist != null || cameraType != null || cameraSpeed != null) {
            b.append("{\"dist\":").append(Json.num(
                        cameraDist == null ? null : cameraDist.doubleValue()))
             .append(",\"type\":").append(Json.num(
                        cameraType == null ? null : cameraType.doubleValue()))
             .append(",\"speed\":").append(Json.num(
                        cameraSpeed == null ? null : cameraSpeed.doubleValue()))
             .append('}');
        } else {
            b.append("null");
        }

        b.append(",\"nav\":");
        if (navFresh()) {
            b.append('{')
             .append("\"active\":true")
             .append(",\"title\":").append(Json.esc(navTitle))
             .append(",\"subtitle\":").append(Json.esc(navSub))
             .append(",\"distance\":").append(Json.esc(navDistance))
             .append(",\"after\":").append(Json.esc(navAfter))
             .append(",\"eta\":").append(Json.esc(navEta))
             .append(",\"remain\":").append(Json.esc(navRemain))
             .append(",\"arrive\":").append(Json.esc(navArrive))
             .append(",\"turn\":").append(Json.esc(navTurn))
             .append(",\"from\":").append(Json.esc(navSource))
             .append('}');
        } else {
            b.append("{\"active\":false}");
        }

        b.append(",\"src\":{");
        boolean first = true;
        synchronized (src) {
            for (Map.Entry<String, String> e : src.entrySet()) {
                if (!first) b.append(',');
                first = false;
                b.append(Json.esc(e.getKey())).append(':').append(Json.esc(e.getValue()));
            }
        }
        first = appendDiag(b, first, "logcatLines", String.valueOf(logcatLines));
        first = appendDiag(b, first, "logcatSeen", String.valueOf(logcatSeen));
        first = appendDiag(b, first, "logcatMatched", String.valueOf(logcatMatched));
        first = appendDiag(b, first, "navSeen", String.valueOf(navSeen));
        appendDiag(b, first, "logcatAge", logcatFresh() ? "fresh" : "stale");
        appendDiag(b, first, "logcatError", logcatError);
        appendDiag(b, first, "carError", carError);
        b.append('}');

        b.append('}');
        return b.toString();
    }

    private static boolean appendDiag(StringBuilder b, boolean first, String key, String value) {
        if (value == null) return first;
        if (!first) b.append(',');
        b.append(Json.esc(key)).append(':').append(Json.esc(value));
        return false;
    }
}
