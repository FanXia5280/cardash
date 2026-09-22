package com.cardash.inject;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 第二个启动钩子。
 *
 * 尤其重要的是 MY_PACKAGE_REPLACED —— 装完新包系统会立刻发这个广播，
 * 所以「刚安装完没反应」这种情况不会发生，不用等重启车机。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "-" : intent.getAction();
        Diagnostics.init(context);
        Diagnostics.log("BootReceiver 收到 " + action);

        try {
            BridgeRuntime.tryForegroundService(context);
        } catch (Throwable ignored) {
            // 忽略
        }
        try {
            BridgeRuntime.start(context);
        } catch (Throwable t) {
            Diagnostics.log("BridgeRuntime.start 抛异常: " + t);
        }
    }
}
