package com.s05.hudtraffic;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机 / 应用更新后自动把 HUD 服务拉起来。
 *
 * <p>BOOT_COMPLETED 属于 Android 12+ 后台启动前台服务的豁免广播，所以这里直接
 * {@code startForegroundService} 是允许的。</p>
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        AppLog.i("Boot", "收到开机/更新广播: " + action);
        Context app = context.getApplicationContext();
        // ⚠️ 关键：无条件把常驻服务拉起来，不再等"用户先开启 HUD"。
        // 服务起来后通知栏常驻（点它打开设置）；HUD 窗口仍然只在开关打开后才显示。
        HudTrafficService.start(app);
    }
}
