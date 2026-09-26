package com.s05.hudtraffic;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

/**
 * 车速来源兜底。
 *
 * <p>优先级：</p>
 * <ol>
 *   <li><b>高德广播的 {@code CUR_SPEED}</b> —— 导航/巡航时由高德提供，最贴合地图数据；</li>
 *   <li><b>本车 GPS 速度</b> —— 高德没给时用 {@link LocationManager} 的
 *       {@code Location#getSpeed()} 兜底（只需普通定位权限，不需要任何车机特权）。</li>
 * </ol>
 *
 * <p>D 应用读车速用的是 {@code android.car.CarPropertyManager} 或车机自家的
 * {@code com.openos.virtualcar.VirtualCarPropertyManager}，两者都需要
 * {@code android.car.permission.CAR_SPEED} 这类特权权限（系统应用才有），
 * 普通三方应用拿不到，所以这里用 GPS 速度替代。</p>
 */
public final class SpeedProvider {

    private static final String TAG = "Speed";
    private static final long MIN_INTERVAL_MS = 1000L;
    /** 超过这个时间没有新定位就认为车速无效（避免停车后残留旧值） */
    private static final long VALID_MS = 5000L;

    private static volatile int speedKmh = -1;
    private static volatile long lastFixAt = 0L;
    private static volatile boolean started = false;

    private SpeedProvider() {
    }

    /** 当前 GPS 车速（km/h），无效返回 -1。 */
    public static int getSpeedKmh() {
        if (System.currentTimeMillis() - lastFixAt > VALID_MS) {
            return -1;
        }
        return speedKmh;
    }

    public static boolean isRunning() {
        return started;
    }

    public static void start(Context ctx) {
        if (started || ctx == null) {
            return;
        }
        Context app = ctx.getApplicationContext();
        if (app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            AppLog.i(TAG, "没有定位权限，车速只能用高德广播的 CUR_SPEED");
            return;
        }
        started = true;
        try {
            LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) {
                started = false;
                return;
            }
            LocationListener listener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    if (location == null || !location.hasSpeed()) {
                        return;
                    }
                    speedKmh = Math.round(location.getSpeed() * 3.6f);
                    lastFixAt = System.currentTimeMillis();
                }

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {
                }

                @Override
                public void onProviderEnabled(String provider) {
                }

                @Override
                public void onProviderDisabled(String provider) {
                }
            };
            boolean any = false;
            String[] providers = {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER};
            for (String p : providers) {
                try {
                    if (lm.isProviderEnabled(p)) {
                        lm.requestLocationUpdates(p, MIN_INTERVAL_MS, 0f, listener,
                                Looper.getMainLooper());
                        any = true;
                    }
                } catch (Throwable ignored) {
                }
            }
            AppLog.i(TAG, any ? "GPS 车速兜底已启动" : "没有可用的定位源");
        } catch (Throwable t) {
            started = false;
            AppLog.i(TAG, "启动 GPS 车速失败: " + t);
        }
    }
}
