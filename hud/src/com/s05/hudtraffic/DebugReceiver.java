package com.s05.hudtraffic;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 调试用接收器：无需高德即可在模拟器/车机上注入数据与触发 HUD 开屏。
 *
 * <pre>
 * # 直行绿灯 15 秒
 * adb shell am broadcast -a com.s05.hudtraffic.DEBUG --ei countdown 15 --ei status 2 --ei dir 4
 * # 左转红灯 8 秒（会和直行同时显示，模拟左转灯路口）
 * adb shell am broadcast -a com.s05.hudtraffic.DEBUG --ei countdown 8  --ei status 1 --ei dir 1
 * # 全部清除
 * adb shell am broadcast -a com.s05.hudtraffic.DEBUG --ez clear true
 *
 * # HUD 开关 / 模拟数据源 / 宽松副屏匹配（adb 直接改，不用点界面）
 * adb shell am broadcast -a com.s05.hudtraffic.DEBUG --ez hud true
 * adb shell am broadcast -a com.s05.hudtraffic.DEBUG --ez simulate true
 * adb shell am broadcast -a com.s05.hudtraffic.DEBUG --ez relaxed true
 * # 强制做一次 HUD 健康检查（验证看门狗）
 * adb shell am broadcast -a com.s05.hudtraffic.DEBUG --ez healthCheck true
 *
 * # 车机上重发一次完整的 HUD 开屏指令（设置 + 双屏指令 + 高德广播）
 * adb shell am broadcast -a com.s05.hudtraffic.DEBUG --ez wake true
 * # 单独发一条双屏交互指令，便于逐个试码
 * adb shell am broadcast -a com.s05.hudtraffic.DEBUG --ei dip 51
 * # 只发高德开屏广播
 * adb shell am broadcast -a com.s05.hudtraffic.DEBUG --ez amapWake true
 * </pre>
 */
public class DebugReceiver extends BroadcastReceiver {

    public static final String ACTION_DEBUG = "com.s05.hudtraffic.DEBUG";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_DEBUG.equals(intent.getAction())) {
            return;
        }

        // ---- 代理桌面：指定/清除要转发的桌面包名 ----
        if (intent.hasExtra("homePkg")) {
            String pkg = intent.getStringExtra("homePkg");
            Prefs.setHomeForwardPackage(context, pkg);
            AppLog.i("Debug", "代理桌面转发目标 -> " + pkg);
            return;
        }
        if (intent.getBooleanExtra("clearHomePkg", false)) {
            Prefs.setHomeForwardPackage(context, "");
            AppLog.i("Debug", "已清除代理桌面转发目标，下次会重新弹选择框");
            return;
        }

        // ---- 全部控件预览开关 ----
        if (intent.hasExtra("previewAll")) {
            boolean on = intent.getBooleanExtra("previewAll", true);
            Prefs.setPreviewAll(context, on);
            HudPanelRenderer.setPreviewAll(on);
            AppLog.i("Debug", "全部控件预览 -> " + on);
            HudTrafficService.refresh(context);
            return;
        }

        // ---- HUD 总开关 / 模拟数据源 / 宽松副屏匹配（adb 直接改，免得车机上点不到） ----
        if (intent.hasExtra("hud")) {
            boolean on = intent.getBooleanExtra("hud", true);
            Prefs.setHudEnabled(context, on);
            AppLog.i("Debug", "HUD 显示开关 -> " + on);
            if (on) {
                HudTrafficService.ensureAlive(context);
            }
            HudTrafficService.refresh(context);
            return;
        }
        if (intent.hasExtra("simulate")) {
            boolean on = intent.getBooleanExtra("simulate", true);
            Prefs.setSimulate(context, on);
            AppLog.i("Debug", "模拟数据源 -> " + on);
            HudTrafficService.ensureAlive(context);
            HudTrafficService.refresh(context);
            return;
        }
        if (intent.hasExtra("mirror")) {
            boolean on = intent.getBooleanExtra("mirror", true);
            Prefs.setMirrorMain(context, on);
            AppLog.i("Debug", "主屏镜像悬浮窗 -> " + on);
            HudTrafficService.refresh(context);
            return;
        }
        if (intent.hasExtra("relaxed")) {
            boolean on = intent.getBooleanExtra("relaxed", true);
            Prefs.setRelaxedDisplay(context, on);
            AppLog.i("Debug", "宽松副屏匹配 -> " + on);
            HudTrafficService.refresh(context);
            return;
        }

        // ---- 强制触发一次 HUD 健康检查（不需要改配置，用来验证看门狗） ----
        if (intent.getBooleanExtra("healthCheck", false)) {
            boolean dead = HudTrafficManager.get().needsRebuild();
            AppLog.i("Debug", "HUD 健康检查 -> needsRebuild=" + dead
                    + " showing=" + HudTrafficManager.get().isShowing()
                    + " | " + HudTrafficManager.get().getLastMessage());
            if (dead) {
                HudTrafficService.refresh(context);
            }
            return;
        }

        // ---- HUD 文字黑描边开关 ----
        if (intent.hasExtra("outline")) {
            boolean on = intent.getBooleanExtra("outline", false);
            Prefs.setTextOutline(context, on);
            HudPanelRenderer.setOutlineEnabled(on);
            AppLog.i("Debug", "HUD 文字黑描边 -> " + on);
            HudTrafficService.refresh(context);
            return;
        }

        // ---- 渲染方式切换（View / SurfaceView）----
        if (intent.hasExtra("surface")) {
            boolean on = intent.getBooleanExtra("surface", true);
            Prefs.setUseSurface(context, on);
            AppLog.i("Debug", "HUD 渲染方式 -> " + (on ? "SurfaceView" : "普通 View"));
            HudTrafficService.refresh(context);
            return;
        }

        // ---- HUD 开屏相关 ----
        if (intent.getBooleanExtra("wake", false)) {
            AppLog.i("Debug", "手动触发 HUD 开屏");
            S05HudWakeHelper.setAppContext(context);
            S05HudWakeHelper.fullWakeHud(context);
            return;
        }
        if (intent.hasExtra("dip")) {
            int code = intent.getIntExtra("dip", 51);
            S05HudWakeHelper.setAppContext(context);
            String r = S05HudWakeHelper.sendDipStatus(code);
            AppLog.i("Debug", "sendDipStatus(" + code + ") -> " + r);
            return;
        }
        if (intent.getBooleanExtra("amapWake", false)) {
            S05HudWakeHelper.setAppContext(context);
            String r = S05HudWakeHelper.sendAmapHudStart();
            AppLog.i("Debug", "高德 HUD 开屏广播 -> " + r);
            return;
        }

        // ---- 红绿灯数据注入 ----
        int status = intent.getIntExtra("status", TrafficLightState.ST_GREEN);
        int countdown = intent.getIntExtra("countdown", 15);
        int dir = intent.getIntExtra("dir", TrafficLightState.DIR_STRAIGHT);
        int waitRound = intent.getIntExtra("waitRound", 0);
        int greenLast = intent.getIntExtra("greenLightLastSecond", 0);
        int routeLightNum = intent.getIntExtra("routeLightNum", -1);
        boolean clearAll = intent.getBooleanExtra("clear", false);

        TrafficLightState s = TrafficLightBus.getLatest().copy();
        long now = System.currentTimeMillis();

        if (clearAll) {
            s.clearLights();
        } else if (countdown < 0) {
            s.removeLight(dir);
        } else {
            s.applyLight(dir, status, countdown, waitRound, greenLast, now);
        }

        if (routeLightNum >= 0) {
            s.routeLightNum = routeLightNum;
        }
        // 引导信息类字段（HUD 上的限速牌 / 距离 / 路名），用 --ei limit 60 --ei dist 6666 注入
        int limit = intent.getIntExtra("limit", Integer.MIN_VALUE);
        if (limit != Integer.MIN_VALUE) {
            s.limitedSpeed = limit;
        }
        int dist = intent.getIntExtra("dist", Integer.MIN_VALUE);
        if (dist != Integer.MIN_VALUE) {
            s.sapaDist = dist;
        }
        // 第二行：剩余距离 / 剩余时间 / 预计到达时间（--ei remainDist 2300 --ei remainTime 360 --es eta 22:46）
        int remainDist = intent.getIntExtra("remainDist", Integer.MIN_VALUE);
        if (remainDist != Integer.MIN_VALUE) {
            s.routeRemainDist = remainDist;
        }
        int remainTime = intent.getIntExtra("remainTime", Integer.MIN_VALUE);
        if (remainTime != Integer.MIN_VALUE) {
            s.routeRemainTime = remainTime;
        }
        String eta = intent.getStringExtra("eta");
        if (eta != null && eta.length() > 0) {
            s.etaText = eta;
        }
        String road = intent.getStringExtra("road");
        if (road != null && road.length() > 0) {
            s.roadName = road;
        }
        int speed = intent.getIntExtra("speed", Integer.MIN_VALUE);
        if (speed != Integer.MIN_VALUE) {
            s.speed = speed;
        }
        // 车道：--es lanes "1,2!,4"（数字是车道方向编码，加 ! 表示推荐车道）
        String lanes = intent.getStringExtra("lanes");
        if (lanes != null && lanes.length() > 0) {
            s.lanes.clear();
            for (String part : lanes.split(",")) {
                String p = part.trim();
                if (p.length() == 0) {
                    continue;
                }
                boolean advised = p.endsWith("!");
                if (advised) {
                    p = p.substring(0, p.length() - 1);
                }
                try {
                    s.lanes.add(new TrafficLightState.Lane(
                            Integer.parseInt(p.trim()), advised ? 1 : 0, ""));
                } catch (Throwable ignored) {
                }
            }
            s.laneEnabled = !s.lanes.isEmpty();
        }
        s.source = "debug";
        s.updateTime = now;
        TrafficLightBus.publish(s);
        AppLog.i("Debug", "注入测试数据 " + s.toDebugString());
    }
}
