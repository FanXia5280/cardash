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
    public volatile Double speedKmh;
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

    // ── 音乐 ──
    public volatile String mTitle;
    public volatile String mArtist;
    public volatile String mAlbum;
    public volatile Boolean mPlaying;
    public volatile Double mPosition;
    public volatile Double mDuration;

    // ── 导航 ──
    public volatile boolean navActive;
    public volatile String navTitle;
    public volatile String navSub;
    public volatile String navDistance;
    public volatile long navUpdatedAt;
    /** 数据来源：notify:<包名> 或 a11y:<包名> */
    public volatile String navSource;

    // ── 诊断 ──
    public final Map<String, String> src = new LinkedHashMap<>();
    public volatile String logcatError;
    public volatile String carError;
    public volatile String mediaError;

    /** 收到过多少条车辆信号日志，用于判断链路是否活着 */
    public volatile long logcatLines;
    public volatile long logcatUpdatedAt;

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
        b.append(",\"speed\":").append(Json.num(speedKmh));
        b.append(",\"gear\":").append(Json.esc(gear));
        b.append(",\"soc\":").append(Json.num(soc));
        b.append(",\"range\":").append(Json.num(rangeKm));
        b.append(",\"odometer\":").append(Json.num(odometerKm));
        b.append(",\"altitude\":").append(Json.num(altitudeM));

        b.append(",\"music\":");
        if (mTitle != null || mArtist != null) {
            b.append('{')
             .append("\"title\":").append(Json.esc(mTitle))
             .append(",\"artist\":").append(Json.esc(mArtist))
             .append(",\"album\":").append(Json.esc(mAlbum))
             .append(",\"playing\":").append(Json.bool(mPlaying))
             .append(",\"position\":").append(Json.num(mPosition))
             .append(",\"duration\":").append(Json.num(mDuration))
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
