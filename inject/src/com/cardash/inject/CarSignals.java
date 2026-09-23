package com.cardash.inject;

/**
 * 备选取数通道：直接反射调用 CarPropertyManager（VHAL）。
 *
 * 注入进 D.apk 后我们持有 system uid，但 android.car.permission.* 属于运行时权限，
 * 未必直接可用，所以这里只作为 logcat 之外的补充；拿不到就静默放弃。
 */
public final class CarSignals {

    private static final int PERF_VEHICLE_SPEED         = 0x11600207;
    private static final int PERF_VEHICLE_SPEED_DISPLAY = 0x11600208;
    private static final int PERF_ODOMETER              = 0x11600204;
    private static final int GEAR_SELECTION             = 0x11400400;
    private static final int CURRENT_GEAR               = 0x11400401;
    private static final int EV_BATTERY_LEVEL           = 0x11600305;
    private static final int EV_RANGE                   = 0x11600309;
    private static final int FUEL_LEVEL                 = 0x11600307;
    private static final int RANGE_REMAINING            = 0x11600308;

    private static final int[] ALL_IDS = {
            PERF_VEHICLE_SPEED, PERF_VEHICLE_SPEED_DISPLAY, GEAR_SELECTION, CURRENT_GEAR,
            PERF_ODOMETER, EV_BATTERY_LEVEL, EV_RANGE, RANGE_REMAINING, FUEL_LEVEL
    };

    private android.os.HandlerThread thread;
    private android.os.Handler handler;

    private Object propManager;
    private Class<?> cpvClass;
    private java.lang.reflect.Method getProperty;
    private volatile boolean running;

    public void start(android.content.Context context) {
        if (running) return;
        running = true;
        thread = new android.os.HandlerThread("cardash-car");
        thread.start();
        handler = new android.os.Handler(thread.getLooper());
        final android.content.Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        handler.post(new Runnable() {
            @Override public void run() { connect(app); }
        });
    }

    public void stop() {
        running = false;
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
    }

    private void connect(android.content.Context ctx) {
        StateHub hub = StateHub.get();
        try {
            Class<?> carClass = Class.forName("android.car.Car");
            Object car = carClass.getMethod("createCar", android.content.Context.class,
                    android.os.Handler.class).invoke(null, ctx, handler);
            if (car == null) {
                hub.carError = "Car.createCar 返回 null";
                return;
            }
            Object mgr = carClass.getMethod("getCarManager", String.class).invoke(car, "property");
            if (mgr == null) {
                hub.carError = "车辆属性服务不可用";
                return;
            }
            propManager = mgr;
            cpvClass = Class.forName("android.car.hardware.property.CarPropertyValue");
            getProperty = mgr.getClass().getMethod("getProperty", int.class);
            hub.carError = null;
            hub.setSource("vhal", "connected");
            startPolling();
        } catch (Throwable t) {
            hub.carError = t.getClass().getSimpleName() + ": " + t.getMessage();
            hub.setSource("vhal", "unavailable");
            running = false;
        }
    }

    private void startPolling() {
        Runnable poller = new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                for (int id : ALL_IDS) {
                    try {
                        Object value = getProperty.invoke(propManager, id);
                        if (value != null) {
                            apply(id, cpvClass.getMethod("getValue").invoke(value));
                        }
                    } catch (Throwable ignored) {
                        // 无权限或车辆不支持，跳过
                    }
                }
                if (running && handler != null) {
                    handler.postDelayed(this, 1000L);
                }
            }
        };
        handler.post(poller);
    }

    private void apply(int id, Object v) {
        StateHub hub = StateHub.get();
        Double d = toDouble(v);
        if (d == null) return;

        switch (id) {
            case PERF_VEHICLE_SPEED:
            case PERF_VEHICLE_SPEED_DISPLAY:
                if (d >= 0) {
                    hub.carSpeedKmh = d * 3.6;
                    hub.setSource("speed", "vhal");
                }
                break;
            case GEAR_SELECTION:
            case CURRENT_GEAR: {
                String g = LogcatSignals.gearName(d.intValue());
                if (g != null) {
                    hub.gear = g;
                    hub.setSource("gear", "vhal");
                }
                break;
            }
            case PERF_ODOMETER:
                if (d > 0) {
                    hub.odometerKm = d / 1000.0;
                    hub.setSource("odometer", "vhal");
                }
                break;
            case EV_BATTERY_LEVEL:
            case FUEL_LEVEL:
                if (d >= 0) {
                    hub.soc = d <= 1.0 ? d * 100.0 : d;
                    hub.setSource("soc", "vhal");
                }
                break;
            case EV_RANGE:
            case RANGE_REMAINING:
                if (d >= 0) {
                    hub.rangeKm = d;
                    hub.setSource("range", "vhal");
                }
                break;
            default:
                break;
        }
    }

    private static Double toDouble(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof Number[]) {
            Number[] a = (Number[]) v;
            return a.length > 0 && a[0] != null ? a[0].doubleValue() : null;
        }
        if (v instanceof float[]) {
            float[] a = (float[]) v;
            return a.length > 0 ? (double) a[0] : null;
        }
        if (v instanceof int[]) {
            int[] a = (int[]) v;
            return a.length > 0 ? (double) a[0] : null;
        }
        return null;
    }
}
