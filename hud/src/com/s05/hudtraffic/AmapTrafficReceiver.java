package com.s05.hudtraffic;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

/**
 * 监听高德车机版（深蓝专版包名 com.wt.mahjong）主动发出的隐式广播
 * {@code AUTONAVI_STANDARD_BROADCAST_SEND}，从中解析红绿灯倒计时。
 *
 * <pre>
 * KEY_TYPE = 60073 红绿灯倒计时：
 *   trafficLightStatus        1 即将绿灯 / 2 绿灯 / 3 黄灯 / 4 即将红灯
 *   redLightCountDownSeconds  倒计时秒数
 *   dir                       1 左转 / 2 右转 / 3 掉头 / 4 直行
 *   waitRound                 等待轮次
 *   greenLightLastSecond      绿灯剩余秒数（部分版本）
 *
 * KEY_TYPE = 10001 引导信息：
 *   TRAFFIC_LIGHT_NUM         导航路线上信号灯总数
 *   CUR_SPEED / CUR_ROAD_NAME / NEXT_ROAD_NAME ...
 * </pre>
 *
 * 注意：该广播由高德 {@code sendBroadcast(Intent)} 隐式发出，未指定包名，
 * 任何应用都可以动态注册接收（Android 8+ 禁止清单静态注册接收隐式广播）。
 */
public class AmapTrafficReceiver {

    private static final String TAG = "Amap";

    public static final String ACTION_SEND = "AUTONAVI_STANDARD_BROADCAST_SEND";

    /** 高德相关包名（车机是深蓝定制版 com.wt.mahjong；保险起见把通用包名也算上） */
    private static final String[] AMAP_PACKAGES = {
            "com.wt.mahjong",          // 深蓝定制版高德（本项目目标）
            "com.autonavi.amapauto",   // 通用高德车机版
            "com.autonavi.amap",       // 高德手机版
    };

    /**
     * 判断某个包名是不是高德。
     *
     * <p>无障碍 / 通知监听两个保活服务用它来做"跟随高德启动"：
     * 发现高德在前台活动就把我们自己的服务拉起来 —— 效果等于"挂在高德身上活"，
     * 但不需要把代码注入高德（那要重签名，签名不同无法覆盖安装）。</p>
     */
    public static boolean isAmapPackage(String pkg) {
        if (pkg == null) {
            return false;
        }
        for (String p : AMAP_PACKAGES) {
            if (p.equals(pkg)) {
                return true;
            }
        }
        return false;
    }
    public static final int TYPE_GUIDE_INFO = 10001;
    public static final int TYPE_TRAFFIC_LIGHT = 60073;
    /** 车道信息（EXTRA_DRIVE_WAY 是一段 JSON） */
    public static final int TYPE_DRIVE_WAY = 13012;
    /** 模式广播：EXTRA_STATE=8 导航中；9/24/25 巡航中；其它视为空闲 */
    public static final int TYPE_NAVI_STATE = 10019;

    /** 当前驾驶模式：0 空闲 / 1 导航 / 2 巡航（导航和巡航都会发 10001，所以巡航也照常收数据） */
    private int drivingMode = 0;
    /** 同上，但全局可读（保活看门狗用它判断要不要补发 HUD 开屏指令）。 */
    public static volatile int currentMode = 0;
    /** 注册时保存的 app context（退出导航时刷新 HUD 窗口用） */
    private Context appContext;

    private final BroadcastReceiver inner = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null) {
                handle(intent);
            }
        }
    };

    private boolean registered;

    public static IntentFilter buildFilter() {
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_SEND);
        return f;
    }

    /** Android 13+ 动态注册非系统广播必须显式声明导出标志。 */
    public static void registerReceiverCompat(Context ctx, BroadcastReceiver r, IntentFilter f) {
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        } else {
            ctx.registerReceiver(r, f);
        }
    }

    public void register(Context ctx) {
        if (registered) {
            return;
        }
        // ⚠️ 这里必须保存 appContext：退出导航时要用它来 refresh / 自动开关 HUD。
        // 之前只声明没赋值，永远是 null，导致"结束导航后自动恢复 HUD"整条链路从未执行过。
        appContext = ctx.getApplicationContext();
        registerReceiverCompat(appContext, inner, buildFilter());
        registered = true;
        AppLog.i(TAG, "已注册监听广播 " + ACTION_SEND);
    }

    public void unregister(Context ctx) {
        if (!registered) {
            return;
        }
        try {
            ctx.getApplicationContext().unregisterReceiver(inner);
        } catch (Throwable t) {
            AppLog.i(TAG, "反注册失败: " + t);
        }
        registered = false;
    }

    private void handle(Intent intent) {
        final int keyType = intent.getIntExtra("KEY_TYPE", -1);
        if (keyType == TYPE_TRAFFIC_LIGHT) {
            int status = intent.getIntExtra("trafficLightStatus", 0);
            int dir = intent.getIntExtra("dir", 0);
            if (dir <= 0) {
                // 变灯瞬间高德会发一条 dir=0 / status=-1 的空广播（真机日志里可见），
                // 它不对应任何方向，也不该触发 HUD 重绘，直接忽略
                AppLog.i(TAG, "红绿灯广播 dir 无效(" + dir + ")，忽略 status=" + status);
                return;
            }
            TrafficLightState s = TrafficLightBus.getLatest().copy();
            int countdown = intent.getIntExtra("redLightCountDownSeconds", -1);
            int waitRound = intent.getIntExtra("waitRound", 0);
            int greenLast = intent.getIntExtra("greenLightLastSecond", 0);
            long now = System.currentTimeMillis();

            if (countdown < 0 || status <= 0) {
                // 倒计时为负、或高德没给灯态（status=0）→ 视为该方向信号灯不存在，
                // 否则 HUD 上会出现一个灰色的空箭头（之前踩过这个坑）
                s.removeLight(dir);
            } else {
                // 按方向分别保存：左转灯路口会有 dir=4 直行 与 dir=1 左转 两条广播
                s.applyLight(dir, status, countdown, waitRound, greenLast, now);
            }
            s.source = "amap/60073";
            s.updateTime = now;
            TrafficLightBus.publish(s);
            AppLog.i(TAG, "红绿灯广播 dir=" + dir + "(" + TrafficLightState.dirText(dir)
                    + ") status=" + status + " countdown=" + countdown + " | " + s.toDebugString());
        } else if (keyType == TYPE_GUIDE_INFO) {
            TrafficLightState s = TrafficLightBus.getLatest().copy();
            // 换路线检测要用的"变化前"的值
            int prevRemain = s.remainLightNum;
            int prevRouteDist = s.routeRemainDist;
            String prevRoad = s.roadName;

            s.routeLightNum = intent.getIntExtra("TRAFFIC_LIGHT_NUM", s.routeLightNum);
            s.speed = intent.getIntExtra("CUR_SPEED", s.speed);
            s.limitedSpeed = intent.getIntExtra("LIMITED_SPEED", s.limitedSpeed);
            s.sapaType = intent.getIntExtra("SAPA_TYPE", s.sapaType);

            // 到下一个转向点的距离突然变大 → 说明刚过了上一个路口，
            // 把上一个路段的红绿灯清掉，避免"别的路段的灯显示在当前路段上"
            int prevDist = s.sapaDist;
            int newDist = intent.getIntExtra("SAPA_DIST", s.sapaDist);
            if (prevDist >= 0 && newDist >= 0 && newDist > prevDist + 50) {
                s.clearLights();
                AppLog.i(TAG, "已过路口（转向点距离 " + prevDist + "→" + newDist + "m），清空红绿灯缓存");
            }
            s.sapaDist = newDist;
            s.remainLightNum = intent.getIntExtra("routeRemainTrafficLightNum", s.remainLightNum);
            // 终点信息（HUD 第二行：剩余距离 / 剩余时间 / 预计到达时间）
            s.routeRemainDist = intent.getIntExtra("ROUTE_REMAIN_DIS", s.routeRemainDist);
            s.routeRemainTime = intent.getIntExtra("ROUTE_REMAIN_TIME", s.routeRemainTime);
            String eta = intent.getStringExtra("ETA_TEXT");
            if (eta != null && eta.length() > 0) {
                s.etaText = eta;
            }
            // 电子眼（CAMERA_DIST/TYPE/SPEED；ICON 暂存，真机日志确认是不是资源 ID）
            s.cameraDist = intent.getIntExtra("CAMERA_DIST", s.cameraDist);
            s.cameraType = intent.getIntExtra("CAMERA_TYPE", s.cameraType);
            s.cameraSpeed = intent.getIntExtra("CAMERA_SPEED", s.cameraSpeed);
            s.cameraIcon = intent.getIntExtra("ICON", s.cameraIcon);

            String cur = intent.getStringExtra("CUR_ROAD_NAME");
            if (cur != null && cur.length() > 0) {
                s.roadName = cur;
            }
            String next = intent.getStringExtra("NEXT_ROAD_NAME");
            if (next != null && next.length() > 0) {
                s.nextRoadName = next;
            }

            // 换路线 / 进入新路段 → 立即清掉旧路线的红绿灯，
            // 否则切换路线后会"短暂停留在上一条路线的倒计时"（用户反馈，高德重规划后旧数据还能存活 10 秒）。
            // 真机上 SAPA_DIST 常为 -1（转向点检测不生效），所以用三个更可靠的信号：
            boolean routeSwitched =
                    (prevRouteDist >= 0 && s.routeRemainDist >= 0
                            && Math.abs(s.routeRemainDist - prevRouteDist) > 300)   // 剩余里程突变（换路线最典型：多/少 1km+）
                    || (prevRemain >= 0 && s.remainLightNum >= prevRemain + 3)       // 红绿灯数量暴增（换路线：多 8 个）
                    || (!prevRoad.isEmpty() && cur != null && !cur.isEmpty()
                            && !cur.equals(prevRoad));                               // 路名变化（已进入新路段）
            if (routeSwitched && !s.lights.isEmpty()) {
                s.clearLights();
                AppLog.i(TAG, "路线/路段变化（里程 " + prevRouteDist + "→" + s.routeRemainDist
                        + " 红绿灯 " + prevRemain + "→" + s.remainLightNum
                        + " 路名 " + prevRoad + "→" + cur + "），清空旧红绿灯缓存");
            }

            // 去重：高德每秒会把同一条引导信息重复发好几遍（真机日志里可见每秒 3 条），
            // 内容完全没变时就不用再触发一次 HUD 重绘（车机性能弱，能省则省）
            TrafficLightState prev = TrafficLightBus.getLatest();
            boolean changed = prev.routeLightNum != s.routeLightNum
                    || prev.remainLightNum != s.remainLightNum
                    || prev.limitedSpeed != s.limitedSpeed
                    || prev.sapaDist != s.sapaDist
                    || prev.sapaType != s.sapaType
                    || prev.speed != s.speed
                    || prev.routeRemainDist != s.routeRemainDist
                    || prev.routeRemainTime != s.routeRemainTime
                    || prev.lights.size() != s.lights.size()
                    || !prev.roadName.equals(s.roadName)
                    || !prev.nextRoadName.equals(s.nextRoadName)
                    || !prev.etaText.equals(s.etaText);
            s.source = "amap/10001";
            s.updateTime = System.currentTimeMillis();
            if (changed) {
                TrafficLightBus.publish(s);
            }
            // 日志照打（排查问题要用，量也不大）
            AppLog.i(TAG, "引导信息 限速=" + s.limitedSpeed + " 距离=" + s.sapaDist
                    + "米 路名=" + s.roadName + " 下一路=" + s.nextRoadName
                    + " 剩余红绿灯=" + s.remainLightNum + " 路线红绿灯=" + s.routeLightNum
                    + " 剩余路程=" + s.routeRemainDist + "米/" + s.routeRemainTime
                    + "秒 ETA=" + s.etaText
                    + " 电子眼 dist=" + s.cameraDist + " type=" + s.cameraType
                    + " speed=" + s.cameraSpeed + " icon=" + s.cameraIcon);
        } else if (keyType == TYPE_NAVI_STATE) {
            // 我们自己的 HUD 开屏指令也用 10019/EXTRA_STATE=8（学 D 的做法），必须忽略，
            // 否则会被当成"高德真的开始导航"：悬浮球被藏起来、数据被当成导航态。
            if (intent.getBooleanExtra(S05HudWakeHelper.FROM_WAKE_EXTRA, false)) {
                AppLog.i(TAG, "收到自己的 HUD 开屏广播，忽略");
                return;
            }
            // 高德用 10019/EXTRA_STATE 宣告当前模式：8=导航，9/24/25=巡航，其它=空闲
            int st = intent.getIntExtra("EXTRA_STATE", -1);
            int mode = (st == 8) ? 1 : ((st == 9 || st == 24 || st == 25) ? 2 : 0);
            if (mode != drivingMode) {
                drivingMode = mode;
                currentMode = mode;
                AppLog.i(TAG, "驾驶模式变化 -> "
                        + (mode == 1 ? "导航中" : mode == 2 ? "巡航中" : "空闲") + " (EXTRA_STATE=" + st + ")");
                if (mode == 0) {
                    // 导航和巡航都退出了：清掉所有"实时"数据，避免 HUD 上残留旧倒计时/里程。
                    // 但 **路名保留**（用户要求）：HUD 面板是常驻的，退出导航后时间和当前路段
                    // 还要继续显示，只有没有广播内容的控件才空着（位置不变、只是没有数值）。
                    TrafficLightState s = TrafficLightBus.getLatest().copy();
                    s.clearLights();
                    s.lanes.clear();
                    s.laneEnabled = false;
                    s.limitedSpeed = -1;
                    s.sapaDist = -1;
                    s.speed = -1;
                    s.routeLightNum = -1;
                    s.remainLightNum = -1;
                    s.routeRemainDist = -1;
                    s.routeRemainTime = -1;
                    s.etaText = "";
                    s.cameraDist = -1;
                    s.cameraType = -1;
                    s.cameraSpeed = -1;
                    s.cameraIcon = -1;
                    s.source = "amap/10019";
                    s.updateTime = System.currentTimeMillis();
                    TrafficLightBus.publish(s);
                    AppLog.i(TAG, "已清空 HUD 数据（导航/巡航已退出）");
                }
                // 模式切换（进/退导航）后强制刷新一次 HUD 窗口：
                // 退出导航时副屏窗口可能被系统回收，不刷新就会出现
                // "退出导航后 HUD 空白，要重启地图 / 开关 HUD 才恢复"（用户反馈）。
                if (appContext != null) {
                    HudTrafficService.refresh(appContext);
                    // 车机在退出导航时会把 HUD 投影关掉（高德不再点亮它），我们这边
                    // 窗口还在、数据也在，但 HUD 上什么都看不见 —— 所以要补发一次
                    // 开屏指令把 HUD 重新点亮（日志里车机发的是 EXTRA_STATE=316）。
                    if (mode == 0) {
                        // 完全退出导航：车机会把 HUD 熄掉，后台自动开关一次 HUD 开关
                        //（用户实测手动这么做有效）
                        HudTrafficService.bounceHudSwitch(appContext);
                    } else if (mode == 1) {
                        // 开始导航：把自己顶回最上层（D 桌面的歌词窗口可能盖住我们）
                        HudTrafficService.wakeHudLater(appContext, 0L);
                        HudTrafficService.bringFrontLater(appContext, 1200L);
                    } else {
                        HudTrafficService.wakeHudLater(appContext, 1500L);
                    }
                }
            }
        } else if (keyType == 13012) {
            // 车道信息：EXTRA_DRIVE_WAY 是一段 JSON（drive_way_enabled / drive_way_size / drive_way_info[]）
            TrafficLightState s = TrafficLightBus.getLatest().copy();
            String json = intent.getStringExtra("EXTRA_DRIVE_WAY");
            applyDriveWay(s, json);
            s.source = "amap/13012";
            s.updateTime = System.currentTimeMillis();
            TrafficLightBus.publish(s);
            // 原始 JSON 打日志，方便用真实数据校准车道方向的取值
            AppLog.i(TAG, "车道广播 车道数=" + s.lanes.size() + " 原始JSON=" + json);
        } else {
            AppLog.i(TAG, "其它广播 KEY_TYPE=" + keyType);
        }
    }

    /** 解析 EXTRA_DRIVE_WAY 的 JSON，填充 state.lanes。 */
    private void applyDriveWay(TrafficLightState s, String json) {
        s.lanes.clear();
        s.laneEnabled = false;
        if (json == null || json.length() == 0) {
            return;
        }
        try {
            org.json.JSONObject root = new org.json.JSONObject(json);
            s.laneEnabled = root.optBoolean("drive_way_enabled", false);
            org.json.JSONArray arr = root.optJSONArray("drive_way_info");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject o = arr.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    s.lanes.add(new TrafficLightState.Lane(
                            o.optInt("trafficLaneType", 0),
                            o.optInt("trafficLaneAdvised", 0),
                            o.optString("drive_way_lane_Back_icon", "")));
                }
            }
        } catch (Throwable t) {
            AppLog.i(TAG, "解析车道 JSON 失败: " + t);
        }
    }
}
