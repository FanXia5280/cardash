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

    // ── 转向灯（左右分开 + 合并状态，三个槽位，见 turnValue 的归一化）──
    /** 左转向灯亮着吗（null = 这个别名没在推数据） */
    public volatile Boolean turnLeft;
    /** 右转向灯亮着吗 */
    public volatile Boolean turnRight;
    /** 合并状态（D.apk 的「灯光 / 转向灯状态」原值，可能是 0/1/2/3 枚举） */
    public volatile Integer turnStatus;

    /**
     * 归一化转向灯：**0=灭 1=左 2=右 3=双闪**，null = 完全没数据（iPhone 就别显示）。
     *
     * 为什么在这里算而不是在采集端：左灯、右灯是两个独立别名，到达顺序不定
     * （左灯先推 0、右灯后推 1）。分开存、最后统一算，结果才与顺序无关。
     */
    public Integer turnValue() {
        if (turnStatus != null) return turnStatus;
        if (Boolean.TRUE.equals(turnLeft) && Boolean.TRUE.equals(turnRight)) return 3;
        if (Boolean.TRUE.equals(turnLeft)) return 1;
        if (Boolean.TRUE.equals(turnRight)) return 2;
        if (turnLeft != null || turnRight != null) return 0;
        return null;
    }

    // ── 音乐 ──
    public volatile String mTitle;
    public volatile String mArtist;
    public volatile String mAlbum;
    public volatile Boolean mPlaying;
    public volatile Double mPosition;
    public volatile Double mDuration;
    /** 专辑封面，base64(JPEG)。体积压到 ~10KB 以内，直接塞进 JSON 给 iPhone 用。 */
    public volatile String mCover;
    /**
     * 同步歌词，紧凑格式：「起始秒|歌词」逐行、\n 连接。
     * 只在切歌时更新一次，iPhone 按播放位置自己切行 —— 比每 200ms 推一次
     * 「当前歌词」省得多，也能跟到几十毫秒的精度。
     */
    public volatile String mLrc;

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
    public volatile String mediaError;

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

    public void clearMusic() {
        mTitle = null;
        mArtist = null;
        mAlbum = null;
        mPlaying = null;
        mPosition = null;
        mDuration = null;
        mCover = null;
        mLrc = null;
    }

    private boolean navFresh() {
        return navActive && (System.currentTimeMillis() - navUpdatedAt) < 15000L;
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
        // 车机导航目的地。有值的话 iPhone 那边会自动算一条路线画在地图上。
        if (DestSignals.fresh()) {
            b.append(",\"dest\":{\"name\":").append(Json.esc(DestSignals.destName()))
             .append(",\"lat\":").append(Json.num(DestSignals.lat()))
             .append(",\"lon\":").append(Json.num(DestSignals.lon()))
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

        b.append(",\"music\":");
        if (mTitle != null || mArtist != null) {
            b.append('{')
             .append("\"title\":").append(Json.esc(mTitle))
             .append(",\"artist\":").append(Json.esc(mArtist))
             .append(",\"album\":").append(Json.esc(mAlbum))
             .append(",\"playing\":").append(Json.bool(mPlaying))
             .append(",\"position\":").append(Json.num(mPosition))
             .append(",\"duration\":").append(Json.num(mDuration))
             .append(",\"cover\":").append(Json.esc(mCover))
             .append(",\"lrc\":").append(Json.esc(mLrc))
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
        appendDiag(b, first, "mediaError", mediaError);
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
