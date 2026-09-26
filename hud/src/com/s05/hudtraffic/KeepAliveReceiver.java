package com.s05.hudtraffic;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 保活心跳接收器：被 {@link AlarmScheduler} 每分钟唤醒一次，
 * 负责检查并把 {@link HudTrafficService} 拉起来，然后续订下一次心跳。
 *
 * <p>因为闹钟由 system_server 持有，即便本应用进程已被彻底杀掉，这条广播依然会送达，
 * 从而把服务重新拉起。</p>
 */
public class KeepAliveReceiver extends BroadcastReceiver {

    public static final String ACTION_KEEPALIVE = "com.s05.hudtraffic.KEEPALIVE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_KEEPALIVE.equals(intent.getAction())) {
            return;
        }
        Context app = context.getApplicationContext();
        boolean wanted = Prefs.isHudEnabled(app) || Prefs.isSimulate(app);
        AppLog.i("KeepAlive", "心跳触发（需要常驻=" + wanted + "）");

        if (wanted) {
            HudTrafficService.ensureAlive(app);
            AlarmScheduler.schedule(app);
        } else {
            AppLog.i("KeepAlive", "HUD 未启用，停止心跳");
        }
    }
}
