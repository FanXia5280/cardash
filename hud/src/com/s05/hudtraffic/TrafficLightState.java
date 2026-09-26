package com.s05.hudtraffic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 红绿灯状态快照。
 *
 * <p>字段全部来自高德车机版主动发出的隐式广播
 * {@code AUTONAVI_STANDARD_BROADCAST_SEND}：</p>
 * <ul>
 *   <li>{@code KEY_TYPE = 60073}：红绿灯倒计时
 *       （trafficLightStatus / redLightCountDownSeconds / dir / waitRound / greenLightLastSecond），
 *       <b>每个方向一条广播</b>，左转灯路口会有直行 + 左转两条；</li>
 *   <li>{@code KEY_TYPE = 10001}：引导信息（TRAFFIC_LIGHT_NUM / CUR_SPEED / CUR_ROAD_NAME ...）。</li>
 * </ul>
 *
 * <p>注意：{@link #lights} 只在主线程访问（广播接收器与渲染都在主线程）。</p>
 */
public class TrafficLightState {

    /* ---- trafficLightStatus 取值（来自高德车机协议） ---- */
    /** 即将绿灯（当前为红灯，即将转绿） */
    public static final int ST_ABOUT_GREEN = 1;
    /** 绿灯 */
    public static final int ST_GREEN = 2;
    /** 黄灯 */
    public static final int ST_YELLOW = 3;
    /** 即将红灯（当前为绿灯，即将转红） */
    public static final int ST_ABOUT_RED = 4;

    /* ---- dir 取值 ---- */
    public static final int DIR_LEFT = 1;
    public static final int DIR_RIGHT = 2;
    public static final int DIR_UTURN = 3;
    public static final int DIR_STRAIGHT = 4;

    public static final int COLOR_RED = 0xFFFF3B30;
    public static final int COLOR_GREEN = 0xFF34C759;
    public static final int COLOR_YELLOW = 0xFFFFCC00;
    public static final int COLOR_NONE = 0xFF78909C;

    /**
     * 红绿灯数据超过该时长未更新即视为过期。
     *
     * <p>高德导航时每秒都会重发当前路口各方向的灯态，所以 10 秒还没刷新就说明
     * 这个方向已经不再有效了。"过了路口"的情况由 {@code AmapTrafficReceiver}
     * 里的“转向点距离突然变大 → 清空红绿灯”逻辑即时处理。</p>
     */
    public static final long STALE_MS = 10000L;

    /** 最近一条 60073 广播的内容（保留用于调试展示与兼容） */
    public volatile int status = 0;
    public volatile int countdown = -1;
    public volatile int dir = 0;
    public volatile int waitRound = 0;
    public volatile int greenLightLastSecond = 0;

    /** 导航路线上信号灯总数（来自 KEY_TYPE 10001） */
    public volatile int routeLightNum = -1;
    /** 当前车速（来自 KEY_TYPE 10001） */
    public volatile int speed = -1;
    public volatile String roadName = "";
    public volatile long updateTime = 0L;
    /** 最近一次收到 60073 的时刻 */
    public volatile long lightUpdateTime = 0L;
    /** 数据来源标记："amap/60073"、"amap/10001"、"debug"、"sim"、"ui-test" */
    public volatile String source = "";

    /* ---- 来自 KEY_TYPE 10001 引导信息（HUD 参考图里的限速牌 / 距离 / 路名） ---- */

    /** 限速值（LIMITED_SPEED），-1 表示没有 */
    public volatile int limitedSpeed = -1;
    /** 到下一个转向点的距离，单位米（SAPA_DIST），-1 表示没有 —— 参考图里的 "6666米" */
    public volatile int sapaDist = -1;
    /** 转向点类型（SAPA_TYPE） */
    public volatile int sapaType = -1;
    /** 下一道路名 */
    public volatile String nextRoadName = "";
    /** 路线剩余红绿灯数（routeRemainTrafficLightNum） */
    public volatile int remainLightNum = -1;
    /** 剩余路程距离，单位米（ROUTE_REMAIN_DIS），-1 表示没有 */
    public volatile int routeRemainDist = -1;
    /** 剩余路程时间，单位秒（ROUTE_REMAIN_TIME），-1 表示没有 */
    public volatile int routeRemainTime = -1;
    /** 预计到达时间文本（ETA_TEXT，例如 "22:46"） */
    public volatile String etaText = "";

    /* ---- 电子眼（来自 10001 的 CAMERA_* 字段） ---- */

    /** 到前方电子眼的距离，单位米（CAMERA_DIST），-1 表示没有 */
    public volatile int cameraDist = -1;
    /** 电子眼类型（CAMERA_TYPE），-1 表示没有 */
    public volatile int cameraType = -1;
    /** 电子眼限速值（CAMERA_SPEED），-1 表示没有 */
    public volatile int cameraSpeed = -1;
    /** 高德给的电子眼图标（ICON 字段，可能是资源 ID，真机日志确认后再决定是否直接用原生图） */
    public volatile int cameraIcon = -1;

    /* ---- 车道信息（来自 KEY_TYPE 13012 的 EXTRA_DRIVE_WAY JSON） ---- */

    /** 车道列表，空表示当前没有车道数据 */
    public final List<Lane> lanes = new ArrayList<>();
    /** drive_way_enabled */
    public volatile boolean laneEnabled = false;

    /** 一条车道 */
    public static final class Lane {
        /** trafficLaneType：车道方向编码 */
        public final int type;
        /** trafficLaneAdvised：1 表示推荐车道 */
        public final int advised;
        /** drive_way_lane_Back_icon */
        public final String icon;

        public Lane(int type, int advised, String icon) {
            this.type = type;
            this.advised = advised;
            this.icon = icon;
        }
    }

    /** 按方向保存的信号灯，dir -> DirectionLight。只在主线程访问。 */
    public final Map<Integer, DirectionLight> lights = new LinkedHashMap<>();

    public TrafficLightState copy() {
        TrafficLightState s = new TrafficLightState();
        s.status = status;
        s.countdown = countdown;
        s.dir = dir;
        s.waitRound = waitRound;
        s.greenLightLastSecond = greenLightLastSecond;
        s.routeLightNum = routeLightNum;
        s.speed = speed;
        s.roadName = roadName;
        s.updateTime = updateTime;
        s.lightUpdateTime = lightUpdateTime;
        s.source = source;
        s.limitedSpeed = limitedSpeed;
        s.sapaDist = sapaDist;
        s.sapaType = sapaType;
        s.nextRoadName = nextRoadName;
        s.remainLightNum = remainLightNum;
        s.routeRemainDist = routeRemainDist;
        s.routeRemainTime = routeRemainTime;
        s.etaText = etaText;
        s.cameraDist = cameraDist;
        s.cameraType = cameraType;
        s.cameraSpeed = cameraSpeed;
        s.cameraIcon = cameraIcon;
        s.laneEnabled = laneEnabled;
        s.lanes.addAll(lanes);
        for (DirectionLight l : lights.values()) {
            s.lights.put(l.dir, l.copy());
        }
        return s;
    }

    /** 更新某个方向的信号灯，并同步“最近一条”字段。 */
    public void applyLight(int dir, int status, int countdown, int waitRound,
                           int greenLightLastSecond, long now) {
        DirectionLight l = lights.get(dir);
        if (l == null) {
            l = new DirectionLight(dir);
            lights.put(dir, l);
        }
        l.status = status;
        l.countdown = countdown;
        l.waitRound = waitRound;
        l.greenLightLastSecond = greenLightLastSecond;
        l.time = now;

        this.dir = dir;
        this.status = status;
        this.countdown = countdown;
        this.waitRound = waitRound;
        this.greenLightLastSecond = greenLightLastSecond;
        this.lightUpdateTime = now;
        this.updateTime = now;
    }

    /** 清除某方向（countdown < 0 时用）。 */
    public void removeLight(int dir) {
        lights.remove(dir);
        if (this.dir == dir) {
            this.status = 0;
            this.countdown = -1;
        }
    }

    /** 清空所有信号灯。 */
    public void clearLights() {
        lights.clear();
        status = 0;
        countdown = -1;
        dir = 0;
        lightUpdateTime = 0L;
    }

    /**
     * 所有未过期且有效的信号灯，按 直行/左转/右转/掉头 排序。
     *
     * <p><b>只按 {@code status > 0} 过滤</b>（status=0 表示高德没给灯态，画出来是灰色空箭头）。</p>
     *
     * <p><b>注意：{@code countdown} 允许为 0</b> —— 黄灯只有 2~3 秒，高德在黄灯和变灯瞬间
     * 会给 {@code countdown = 0}。之前这里写成 {@code countdown > 0} 会把它滤掉，
     * 表现就是"绿灯灭了 → 直接红灯、黄灯不显示"。</p>
     */
    public List<DirectionLight> freshLights() {
        List<DirectionLight> out = new ArrayList<>();
        for (DirectionLight l : lights.values()) {
            if (l.status > 0 && l.countdown >= 0 && l.isFresh(STALE_MS)) {
                out.add(l);
            }
        }
        out.sort((a, b) -> Integer.compare(dirOrder(a.dir), dirOrder(b.dir)));
        return out;
    }

    /**
     * 降级路径：{@link #lights} 为空时（例如外部只塞了 status/countdown/dir），
     * 用“最近一条”字段拼一个临时信号灯，保证老逻辑照常显示。
     */
    public DirectionLight legacyLight() {
        if (status == 0 || countdown < 0) {
            return null;
        }
        if (lightUpdateTime > 0L && System.currentTimeMillis() - lightUpdateTime > STALE_MS) {
            return null;
        }
        DirectionLight l = new DirectionLight(dir);
        l.status = status;
        l.countdown = countdown;
        l.waitRound = waitRound;
        l.greenLightLastSecond = greenLightLastSecond;
        l.time = lightUpdateTime > 0L ? lightUpdateTime : System.currentTimeMillis();
        return l;
    }

    /** 主方向：优先直行，其次左转、右转、掉头，都没有就取第一个。 */
    public DirectionLight primaryLight() {
        List<DirectionLight> fresh = freshLights();
        if (fresh.isEmpty()) {
            return legacyLight();
        }
        int[] order = {DIR_STRAIGHT, DIR_LEFT, DIR_RIGHT, DIR_UTURN};
        for (int want : order) {
            for (DirectionLight l : fresh) {
                if (l.dir == want) {
                    return l;
                }
            }
        }
        return fresh.get(0);
    }

    /** 除主方向以外的其它方向信号灯。 */
    public List<DirectionLight> secondaryLights() {
        List<DirectionLight> fresh = freshLights();
        if (fresh.isEmpty()) {
            return new ArrayList<>();
        }
        DirectionLight primary = primaryLight();
        List<DirectionLight> out = new ArrayList<>();
        for (DirectionLight l : fresh) {
            if (primary == null || l.dir != primary.dir) {
                out.add(l);
            }
        }
        return out;
    }

    private static int dirOrder(int dir) {
        switch (dir) {
            case DIR_STRAIGHT:
                return 0;
            case DIR_LEFT:
                return 1;
            case DIR_RIGHT:
                return 2;
            case DIR_UTURN:
                return 3;
            default:
                return 9;
        }
    }

    public boolean isLightFresh(long maxAgeMs) {
        if (lightUpdateTime <= 0L) {
            return false;
        }
        return System.currentTimeMillis() - lightUpdateTime <= maxAgeMs;
    }

    public String statusText() {
        return statusText(status);
    }

    /**
     * 灯色。高德语义里：
     * <ul>
     *   <li>1 = 即将绿灯 → 此刻仍是红灯，按红色显示</li>
     *   <li>4 = 即将红灯 → 此刻仍是绿灯，按绿色显示</li>
     * </ul>
     */
    public int lightColor() {
        return statusColor(status);
    }

    public static String statusText(int status) {
        switch (status) {
            case ST_ABOUT_GREEN:
                return "即将绿灯";
            case ST_GREEN:
                return "绿灯";
            case ST_YELLOW:
                return "黄灯";
            case ST_ABOUT_RED:
                return "即将红灯";
            default:
                return "无信号灯";
        }
    }

    public static int statusColor(int status) {
        switch (status) {
            case ST_ABOUT_GREEN:
                return COLOR_RED;
            case ST_GREEN:
            case ST_ABOUT_RED:
                return COLOR_GREEN;
            case ST_YELLOW:
                return COLOR_YELLOW;
            default:
                return COLOR_NONE;
        }
    }

    public static String dirText(int d) {
        switch (d) {
            case DIR_LEFT:
                return "左转";
            case DIR_RIGHT:
                return "右转";
            case DIR_UTURN:
                return "掉头";
            case DIR_STRAIGHT:
                return "直行";
            default:
                return "";
        }
    }

    public String toDebugString() {
        StringBuilder sb = new StringBuilder();
        sb.append("lights=[");
        boolean first = true;
        for (DirectionLight l : freshLights()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(l.toString());
        }
        sb.append(']');
        sb.append(" countdown=").append(countdown)
                .append(" status=").append(status).append('(').append(statusText()).append(')')
                .append(" dir=").append(dir).append('(').append(dirText(dir)).append(')')
                .append(" routeLightNum=").append(routeLightNum)
                .append(" speed=").append(speed)
                .append(" road=").append(roadName)
                .append(" src=").append(source);
        return sb.toString();
    }
}
