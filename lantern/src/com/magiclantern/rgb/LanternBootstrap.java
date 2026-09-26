package com.magiclantern.rgb;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

/**
 * 内置到别的 App 时的启动入口（由宿主进程启动时调用，例：
 * 车机桌面的 BootProvider、地图 App 的 Application.onCreate）。
 *
 * 作用：让氛围灯逻辑跟着宿主进程常驻 —— 即使不打开界面，
 * 也会自动重连灯具、继续播放自定义渐变、并执行定时开/关灯（这就是"保活"）。
 *
 * 独立安装运行时本类不会被调用，不影响原有行为。
 */
public final class LanternBootstrap {

    private static boolean started;
    private static BroadcastReceiver timingReceiver;

    private LanternBootstrap() {
    }

    /** 宿主 Application 启动时调用（容错：任何异常都不影响宿主本身） */
    public static void onAmapStart(Application app) {
        if (started || app == null) return;
        started = true;
        try {
            start(app);
        } catch (Throwable t) {
            // 忽略：保活失败不能影响宿主
        }
    }

    /**
     * 立即保活：重连灯具 + 恢复渐变。可重复调用
     * （宿主通知栏的"保活"按钮通过反射调这个）。
     */
    public static void wake(Context c) {
        if (c == null) return;
        try {
            start(c.getApplicationContext());
        } catch (Throwable t) {
            // 忽略
        }
    }

    private static void start(final Context app) {
        final BleController ble = BleController.get(app);
        final String last = Prefs.get(app).getLastDevice();

        // addListener 而不是 setListener：界面（LanternPanel）会另挂一份监听，
        // 两者互不覆盖。
        ble.addListener(new BleController.Listener() {
            @Override
            public void onDevicesChanged() {
                if (last == null || last.length() == 0) return;
                if (ble.isConnected()) return;
                for (BleController.Conn c : ble.getFoundDevices()) {
                    if (last.equalsIgnoreCase(c.address)) {
                        ble.stopScan();
                        ble.connect(c);
                        break;
                    }
                }
            }

            @Override
            public void onStateChanged(BleController.Conn conn) {
                if (conn != null && conn.state == BleController.STATE_CONNECTED) {
                    // 线序/点数等设备侧设置是写在灯里的、掉电会复位 ⇒ 每次连上都补发一遍
                    LedOutput.reapplyDeviceConfig(app);
                    restoreGradient(app);
                }
            }

            @Override
            public void onMessage(String msg) {
            }
        });

        // 有历史设备才扫描重连
        if (last != null && last.length() > 0) {
            ble.startScan();
        }

        // 渐变恢复（2026-09-26）：
        // 以前只挂在 onStateChanged（蓝牙"刚连上"那一瞬）—— 桌面进程起来时蓝牙
        // 要是已经连着，这个回调不会再触发一次 ⇒ 上车永远不自动恢复渐变。
        // 这里无条件先补一次；连上事件里也会再调一次（幂等）。
        restoreGradient(app);
        // 兜底：连上后 LedOutput.reapplyDeviceConfig 会补发设备侧配置，可能把颜色
        // 覆盖成静态色 ⇒ 稍后再补一次渐变。
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                new Runnable() {
                    @Override
                    public void run() {
                        restoreGradient(app);
                    }
                }, 2000L);

        ensureTimingReceiver(app);
        try {
            // 重新排一遍每天的定时闹钟：宿主进程（桌面）每次起来都补一次，防止漏掉
            TimingReceiver.scheduleAll(app);
        } catch (Throwable t) {
            // 忽略
        }
    }

    /**
     * 定时开/关灯：内置版的清单里没有 TimingReceiver（我们的构建流程不加组件），
     * 所以由这里动态注册一个常驻接收者接住 AlarmManager 的投递。
     */
    private static void ensureTimingReceiver(final Context app) {
        if (timingReceiver != null) return;
        timingReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    boolean on = intent.getBooleanExtra(TimingReceiver.EXTRA_ON, true);
                    Prefs.get(context).setPowerOn(on);
                    BleController ble = BleController.get(context);
                    if (ble.isConnected()) {
                        LedOutput.power(context, on);
                    }
                } catch (Throwable t) {
                    // 忽略
                }
            }
        };
        IntentFilter filter = new IntentFilter(TimingReceiver.ACTION_TIMING);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(timingReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(timingReceiver, filter);
            }
        } catch (Throwable t) {
            timingReceiver = null;
        }
    }

    /** 恢复上次在跑的自定义渐变（灯继续按设置渐变）。幂等，可反复调用。 */
    private static void restoreGradient(Context c) {
        GradientPlayer gp = GradientPlayer.get(c);
        if (gp.isPlaying()) return;      // 心跳还新鲜 ⇒ 真在播，别打断
        String name = Prefs.get(c).getLastGradient();
        if (name == null || name.length() == 0) return;
        for (GradientItem g : Prefs.get(c).getGradients()) {
            if (name.equals(g.name)) {
                gp.play(g);
                return;
            }
        }
    }
}
