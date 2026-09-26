package com.s05.hudtraffic;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * HUD 唤醒 / 开屏辅助。
 *
 * <p>完整移植自 D 应用的 {@code com.deepalhome.launcher.hud.s05utils.S05HudWakeHelper}
 * 以及它延迟 1 秒执行的那段「HUD 开屏指令」（原代码在
 * {@code AppUtil$$ExternalSyntheticLambda1} 的 case 15）：</p>
 *
 * <ol>
 *   <li>{@link #enableVehicleHudSetting()}：反射 {@code VehicleSettingManager}
 *       写入 {@code setHudSwitchStatus(true)} + {@code setHudDisplayNav(true)}；</li>
 *   <li>1 秒后通过 {@code com.incall.interactive.service.proxy.IDoubleInteractiveProxy}
 *       依次 transact <b>51</b>、10、9 —— 其中 51 就是 D 的
 *       {@code HudStartupVideoManager#setHudVideoAreaEnabled(true)}，
 *       用来让车机把 HUD 切到显示这块副屏；</li>
 *   <li>再发一条高德广播 {@code KEY_TYPE=10019 / EXTRA_STATE=8}
 *       （D 注释为“模拟导航开始广播”），让车机认为导航已开始从而点亮 HUD 导航显示。</li>
 * </ol>
 *
 * <p>注意：步骤 2/3 需要访问系统服务，D 应用是 {@code sharedUserId=android.uid.system}
 * 的系统应用所以能拿到；普通三方应用可能被 SELinux / 权限拦下。
 * 因此每一步的结果都会写进日志（{@link AppLog}），失败时也不会崩，
 * 车主可以在车机自带的 HUD 设置里手动打开「HUD 开关 / 显示导航」作为退路。</p>
 */
public final class S05HudWakeHelper {

    private static final String TAG = "HudWakeHelper";
    private static final String VEHICLE_SETTING_MANAGER =
            "com.openos.settings.vehiclesettings.VehicleSettingManager";
    private static final String DIP_BINDER =
            "com.incall.interactive.service.proxy.IDoubleInteractiveProxy";
    private static final String AMAP_ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND";

    /** 我们自己伪造的开屏广播带这个 extra，{@link AmapTrafficReceiver} 收到会忽略。 */
    public static final String FROM_WAKE_EXTRA = "from_s05_hud_wake";

    /** 与 D 应用一致的 HUD 开屏 transact 码顺序 */
    private static final int[] DIP_WAKE_CODES = {51, 10, 9};

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile long lastWakeAttemptAt = 0L;
    private static volatile String lastWakeSummary = "尚未执行";
    private static volatile Context appContext;

    private static final Runnable delayedWake = new Runnable() {
        @Override
        public void run() {
            StringBuilder sb = new StringBuilder();
            for (int code : DIP_WAKE_CODES) {
                String r = sendDipStatus(code);
                AppLog.i(TAG, "sendDipStatus(" + code + ") -> " + r);
                sb.append(code).append(':').append(r).append("  ");
            }
            String amap = sendAmapHudStart();
            AppLog.i(TAG, "高德 HUD 开屏广播(KEY_TYPE=10019, EXTRA_STATE=8) -> " + amap);
            sb.append("amap:").append(amap);
            lastWakeSummary = sb.toString();
            AppLog.i(TAG, "HUD 开屏指令发送完毕");
        }
    };

    private S05HudWakeHelper() {
    }

    public static void setAppContext(Context ctx) {
        if (ctx != null) {
            appContext = ctx.getApplicationContext();
        }
    }

    public static String getLastWakeSummary() {
        return lastWakeSummary;
    }

    /* ------------------------------------------------------------------ */

    /** 写入车辆 HUD 设置：HUD 开关 + HUD 导航显示，与 D 应用一致。 */
    public static boolean enableVehicleHudSetting() {
        if (!allowWriteSoft()) return false;    // 只读：不改车机 HUD 设置
        boolean ok = false;
        try {
            Class<?> cls = Class.forName(VEHICLE_SETTING_MANAGER);
            Object instance = cls.getMethod("getInstance").invoke(null);
            if (instance == null) {
                AppLog.i(TAG, "VehicleSettingManager.getInstance() 返回 null");
                return false;
            }
            try {
                Method m = cls.getMethod("setHudSwitchStatus", boolean.class);
                m.invoke(instance, Boolean.TRUE);
                ok = true;
            } catch (Throwable t) {
                AppLog.i(TAG, "setHudSwitchStatus 不可用: " + t);
            }
            try {
                Method m = cls.getMethod("setHudDisplayNav", boolean.class);
                m.invoke(instance, Boolean.TRUE);
                ok = true;
            } catch (Throwable t) {
                AppLog.i(TAG, "setHudDisplayNav 不可用: " + t);
            }
        } catch (Throwable t) {
            AppLog.i(TAG, "VehicleSettingManager 不可用（非系统应用 / 模拟器属正常）: " + t);
        }
        return ok;
    }

    private static IBinder getDipBinder() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method getService = sm.getMethod("getService", String.class);
            Object o = getService.invoke(null, DIP_BINDER);
            if (o instanceof IBinder) {
                IBinder b = (IBinder) o;
                if (b.isBinderAlive()) {
                    return b;
                }
            }
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "getService(" + DIP_BINDER + ") 失败: " + t);
            return null;
        }
    }

    /**
     * 通过 IDoubleInteractiveProxy 给车机发一条双屏交互指令。
     * 与 D 应用 {@code sendDipStatus(int)} 完全一致：先 writeInterfaceToken，
     * 再 writeInt(1)，然后 transact(code)。
     *
     * @return 结果描述，便于在界面/日志里判断哪一步失败
     */
    public static String sendDipStatus(int code) {
        if (!allowWriteSoft()) return "read-only";   // 只读：不发 DIP 指令
        IBinder binder = getDipBinder();
        if (binder == null) {
            return "跳过(未取到 IDoubleInteractiveProxy)";
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DIP_BINDER);
            data.writeInt(1);
            binder.transact(code, data, reply, 0);
            reply.readException();
            return "成功";
        } catch (Throwable t) {
            return "失败: " + t.getClass().getSimpleName() + " " + t.getMessage();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** 发高德 {@code KEY_TYPE=10019 / EXTRA_STATE=8} 广播，与 D 应用一致。 */
    public static String sendAmapHudStart() {
        if (!allowWriteSoft()) return "read-only";   // 只读：不发广播唤 HUD
        Context ctx = appContext;
        if (ctx == null) {
            return "跳过(无 Context)";
        }
        try {
            Intent intent = new Intent(AMAP_ACTION);
            intent.putExtra("KEY_TYPE", 10019);
            intent.putExtra("EXTRA_STATE", 8);
            // 标记这是我们自己的开屏指令：AmapTrafficReceiver 收到要忽略，
            // 否则会被当成"高德真的开始导航"，把悬浮球藏起来 / 清掉数据。
            intent.putExtra(FROM_WAKE_EXTRA, true);
            ctx.sendBroadcast(intent);
            return "成功";
        } catch (Throwable t) {
            return "失败: " + t;
        }
    }

    /**
     * 完整的 HUD 开屏流程（与 D 应用 wakeHud 一致）。
     * 500ms 内不重复触发。
     */
    public static void fullWakeHud(Context ctx) {
        if (!allowWriteSoft()) return;          // 只读：不唤醒 HUD
        if (ctx != null) {
            setAppContext(ctx);
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastWakeAttemptAt < 500) {
            return;
        }
        lastWakeAttemptAt = now;

        AppLog.i(TAG, "开始 HUD 开屏：写入车辆设置 → 1 秒后发送双屏指令 "
                + java.util.Arrays.toString(DIP_WAKE_CODES) + " + 高德开屏广播");
        boolean settingOk = enableVehicleHudSetting();
        AppLog.i(TAG, "车辆 HUD 设置写入: " + (settingOk ? "成功" : "失败（普通应用通常拿不到，可手动在车机 HUD 设置里打开）"));
        lastWakeSummary = "vehicleSetting=" + (settingOk ? "ok" : "fail") + " …";

        MAIN.removeCallbacks(delayedWake);
        MAIN.postDelayed(delayedWake, 1000L);
    }

    /** 兼容旧调用点。 */
    public static void wakeHud() {
        if (!allowWriteSoft()) return;          // 只读：不唤醒 HUD
        fullWakeHud(appContext);
    }

    /**
     * 是否允许向车机下发指令。软引用桥接的只读开关（com.cardash.inject.ReadOnly）：
     * 独立 App 里没有那个类 ⇒ catch 分支，保持原行为（允许）。
     */
    private static boolean allowWriteSoft() {
        try {
            Class<?> c = Class.forName("com.cardash.inject.ReadOnly");
            Object v = c.getField("enabled").get(null);
            return !Boolean.TRUE.equals(v);
        } catch (Throwable ignored) {
            return true;
        }
    }

}

