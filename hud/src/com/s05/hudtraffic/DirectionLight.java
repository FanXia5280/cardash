package com.s05.hudtraffic;

/**
 * 单个方向（左转 / 直行 / 右转 / 掉头）的信号灯倒计时。
 *
 * <p>高德在有左转灯的路口会对同一个路口分别广播多条
 * {@code KEY_TYPE = 60073}：直行一条（dir=4）、左转一条（dir=1），
 * 靠 {@code dir} 字段区分，所以需要按方向分别保存。</p>
 */
public class DirectionLight {

    /** 方向：1 左转 / 2 右转 / 3 掉头 / 4 直行 */
    public final int dir;
    /** 1 即将绿灯 / 2 绿灯 / 3 黄灯 / 4 即将红灯 */
    public volatile int status;
    /** 倒计时秒数 */
    public volatile int countdown = -1;
    public volatile int waitRound;
    public volatile int greenLightLastSecond;
    /** 该方向最后一次更新时刻 */
    public volatile long time;

    public DirectionLight(int dir) {
        this.dir = dir;
    }

    public DirectionLight copy() {
        DirectionLight l = new DirectionLight(dir);
        l.status = status;
        l.countdown = countdown;
        l.waitRound = waitRound;
        l.greenLightLastSecond = greenLightLastSecond;
        l.time = time;
        return l;
    }

    public boolean isFresh(long maxAgeMs) {
        return time > 0L && System.currentTimeMillis() - time <= maxAgeMs;
    }

    public String dirText() {
        return TrafficLightState.dirText(dir);
    }

    public String statusText() {
        return TrafficLightState.statusText(status);
    }

    public int color() {
        return TrafficLightState.statusColor(status);
    }

    @Override
    public String toString() {
        return dirText() + "(" + dir + "): " + statusText() + " " + countdown + "s";
    }
}
