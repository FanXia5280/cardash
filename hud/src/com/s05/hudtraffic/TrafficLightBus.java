package com.s05.hudtraffic;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 进程内红绿灯状态总线：广播接收器发布，HUD 与主界面订阅。
 */
public final class TrafficLightBus {

    public interface Listener {
        void onTrafficLightChanged(TrafficLightState state);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile TrafficLightState latest = new TrafficLightState();

    private TrafficLightBus() {
    }

    public static void addListener(Listener l) {
        if (l != null && !LISTENERS.contains(l)) {
            LISTENERS.add(l);
        }
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    public static TrafficLightState getLatest() {
        return latest;
    }

    public static void publish(final TrafficLightState state) {
        if (state == null) {
            return;
        }
        latest = state;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : LISTENERS) {
                    try {
                        l.onTrafficLightChanged(state);
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }
}
