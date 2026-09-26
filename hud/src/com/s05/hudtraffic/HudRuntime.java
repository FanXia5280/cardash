package com.s05.hudtraffic;

import android.app.Application;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;

/**
 * 「内嵌进 D 桌面」的运行时：替代原工程里 {@code HudTrafficService}（前台服务）那一层。
 *
 * <p>为什么可以不要服务：原来那个服务存在的意义是「独立 APK 挂后台容易被杀」，
 * 五层保活全是为了把进程留住。而我们现在跑在**车机桌面自己的进程里** ——
 * 桌面（HOME）进程是全机优先级最高、永远不会被杀的进程，保活三件套
 * （闹钟心跳 / 守护进程 / 无障碍）不但没用，反而会往清单里塞组件、
 * 往通知栏塞通知。所以这里只保留服务 onCreate 里**真正干活的那几行**：</p>
 *
 * <ol>
 *   <li>给 {@link HudTrafficManager} / {@link S05HudWakeHelper} /
 *       {@link HudPanelRenderer} 塞 appContext；</li>
 *   <li>动态注册 {@link AmapTrafficReceiver}（高德红绿灯广播是隐式广播，只能动态收）；</li>
 *   <li>{@code TrafficLightBus} 监听 → HUD 重绘；</li>
 *   <li>副屏插拔监听（HUD 掉了自动重建）；</li>
 *   <li>轻量看门狗：窗口失效自愈 + 非导航状态每 60 秒补一次 HUD 开屏。</li>
 * </ol>
 *
 * <p>⚠️ 由 {@code com.cardash.inject.LanternEntry.install()} 在桌面进程启动时调用一次，
 * 全程 try/catch —— 绝不能影响桌面启动。</p>
 */
public final class HudRuntime {

    private static final String TAG = "HudRuntime";

    /** HUD 自愈看门狗的间隔（原服务是 15 秒看门狗 + 60 秒补开屏，这里合并成一个） */
    private static final long WATCHDOG_MS = 15000L;
    /** 非导航状态补发 HUD 开屏指令的最小间隔 */
    private static final long AUTO_WAKE_MIN_MS = 60000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile boolean bootstrapped;
    private static AmapTrafficReceiver receiver;
    private static DisplayManager displayManager;
    private static volatile long lastAutoWakeAt;

    private HudRuntime() {
    }

    /**
     * 确保运行时已经起来（面板里 HUD 开关 / 模拟 / 镜像开关变化时调，重复调用无害）。
     *
     * 真正的启动入口是 {@link #bootstrap(Application)}（由 LanternEntry/HudEntry 在
     * 桌面进程启动时调一次，内部有 bootstrapped 去重）；面板手里只有普通 Context，
     * 所以这里兜一层：取 applicationContext 再 bootstrap 一次 + 立刻刷新数据。
     */
    public static void ensureAlive(Context ctx) {
        try {
            Context app = ctx.getApplicationContext();
            if (app instanceof Application) {
                bootstrap((Application) app);
            }
            refresh(ctx);
        } catch (Throwable t) {
            AppLog.i(TAG, "ensureAlive 失败: " + t);
        }
    }

    /** 桌面进程启动时调用一次（幂等）。 */
    public static void bootstrap(Application app) {
        if (app == null || bootstrapped) {
            return;
        }
        bootstrapped = true;

        // ① 各单例要的 appContext（原服务 onCreate 的 3 行）
        HudTrafficManager.get().setAppContext(app);
        S05HudWakeHelper.setAppContext(app);
        HudPanelRenderer.setAppContext(app);
        // 文字黑描边 / 全控件预览：读一次配置
        HudPanelRenderer.setOutlineEnabled(Prefs.isTextOutline(app));
        HudPanelRenderer.setPreviewAll(Prefs.isPreviewAll(app));

        // ② 车速兜底（无定位权限时内部静默跳过）
        SpeedProvider.start(app);

        // ③ 高德红绿灯广播（隐式广播，只能动态注册）
        receiver = new AmapTrafficReceiver();
        receiver.register(app);

        // ④ 数据 → HUD 重绘
        TrafficLightBus.addListener(new TrafficLightBus.Listener() {
            @Override
            public void onTrafficLightChanged(TrafficLightState state) {
                HudTrafficManager.get().render(state);
            }
        });

        // ⑤ 副屏插拔：HUD 掉了/接上了自动重建窗口
        try {
            displayManager = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
            if (displayManager != null) {
                displayManager.registerDisplayListener(new DisplayManager.DisplayListener() {
                    @Override
                    public void onDisplayAdded(int displayId) {
                        AppLog.i(TAG, "副屏接入 displayId=" + displayId);
                        refresh(app);
                    }

                    @Override
                    public void onDisplayRemoved(int displayId) {
                        AppLog.i(TAG, "副屏移除 displayId=" + displayId);
                        refresh(app);
                    }

                    @Override
                    public void onDisplayChanged(int displayId) {
                        refresh(app);
                    }
                }, MAIN);
            }
        } catch (Throwable t) {
            AppLog.i(TAG, "注册副屏监听失败: " + t);
        }

        // ⑥ 轻量看门狗：窗口被系统回收后自愈 + 非导航补开屏
        MAIN.postDelayed(watchdog(app), WATCHDOG_MS);

        // ⑦ 桌面进程启动时如果用户开着 HUD，直接恢复显示
        if (Prefs.isHudEnabled(app)) {
            refresh(app);
        }
        AppLog.i(TAG, "内嵌运行时已挂上（跟随桌面进程）");
    }

    private static Runnable watchdog(final Context app) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    if (Prefs.isHudEnabled(app)) {
                        // 窗口还在但 surface 没了 / view 被摘了 → 重建
                        if (HudTrafficManager.get().isShowing()
                                && HudTrafficManager.get().needsRebuild()) {
                            AppLog.i(TAG, "看门狗：HUD 窗口失效，重建");
                            refresh(app);
                        }
                        maybeAutoWake(app);
                    }
                } catch (Throwable ignored) {
                }
                MAIN.postDelayed(this, WATCHDOG_MS);
            }
        };
    }

    /** 非导航状态下每 60 秒补一次 HUD 开屏（原服务 maybeAutoWake 的等价物）。 */
    private static void maybeAutoWake(Context app) {
        if (AmapTrafficReceiver.currentMode == 1) {
            return;   // 导航中高德自己会点亮 HUD
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastAutoWakeAt < AUTO_WAKE_MIN_MS) {
            return;
        }
        lastAutoWakeAt = now;
        AppLog.i(TAG, "非导航状态补发 HUD 开屏指令");
        S05HudWakeHelper.setAppContext(app);
        S05HudWakeHelper.fullWakeHud(app);
    }

    /**
     * 按 Prefs 里的开关重建/关闭 HUD 窗口 —— 原 {@code HudTrafficService.refreshHud()}
     * 的等价物（那边靠 startService 触发，这边直接调）。
     */
    public static void refresh(Context ctx) {
        try {
            final Context app = ctx.getApplicationContext();
            boolean hudEnabled = Prefs.isHudEnabled(app);
            boolean mirrorEnabled = Prefs.isMirrorMain(app);
            boolean relaxed = Prefs.isRelaxedDisplay(app);
            HudTrafficManager.get().setScale(Prefs.getScale(app) / 100f);

            if (!hudEnabled && !mirrorEnabled) {
                HudTrafficManager.get().hide();
                return;
            }
            if (!hudEnabled) {
                HudTrafficManager.get().ensureShown(app, null);
                return;
            }
            Display hud = HudDisplayHelper.findHudDisplay(app, relaxed);
            if (hud == null) {
                boolean fallback = Prefs.isAutoFallback(app);
                HudTrafficManager.get().ensureShown(app, null, fallback);
                AppLog.i(TAG, "未找到 HUD 副屏"
                        + (fallback ? "，已自动退回主屏悬浮窗" : ""));
                return;
            }
            HudTrafficManager.get().ensureShown(app, hud);
        } catch (Throwable t) {
            AppLog.i(TAG, "refresh 失败: " + t);
        }
    }

    /** 延迟补发一次 HUD 开屏指令（给车机一点时间处理退出导航的收尾）。 */
    public static void wakeHudLater(Context ctx, long delayMs) {
        if (!allowWriteSoft()) {
            AppLog.i(TAG, "只读模式：跳过补发 HUD 开屏");
            return;
        }
        final Context app = ctx.getApplicationContext();
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                S05HudWakeHelper.setAppContext(app);
                S05HudWakeHelper.fullWakeHud(app);
            }
        }, delayMs);
    }

    /** 延迟把 HUD 叠加窗重新置顶（D 桌面的歌词窗口可能盖住我们）。 */
    public static void bringFrontLater(Context ctx, long delayMs) {
        if (!allowWriteSoft()) {
            AppLog.i(TAG, "只读模式：跳过把 HUD 窗口置顶");
            return;
        }
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                HudTrafficManager.get().bringToFront();
            }
        }, delayMs);
    }

    /** 防止 bounceHudSwitch 重入。 */
    private static volatile boolean bouncingHud = false;
    private static volatile int bouncePhase = 0;

    /**
     * 结束导航后 HUD 上不显示内容 → 自动后台「关一下再开」HUD 显示开关
     * （原服务 bounceHudSwitch/chainBounce 的等价物，去掉了 startService）。
     */
    public static void bounceHudSwitch(final Context ctx) {
        if (!allowWriteSoft()) {
            AppLog.i(TAG, "只读模式：跳过开关一次 HUD");
            return;
        }
        final Context app = ctx.getApplicationContext();
        if (!Prefs.isHudEnabled(app)) {
            return;
        }
        if (bouncingHud) {
            return;
        }
        bouncingHud = true;
        bouncePhase = 1;
        AppLog.i(TAG, "结束导航：自动后台关-开 HUD（两轮，2.5 秒内完成）");
        chainBounce(app, 200L);
    }

    private static void chainBounce(final Context app, long delay) {
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (bouncePhase == 1 || bouncePhase == 3) {
                        Prefs.setHudEnabled(app, false);
                        HudTrafficManager.get().hide();
                    } else {
                        Prefs.setHudEnabled(app, true);
                        refresh(app);
                    }
                } catch (Throwable ignored) {
                }
                bouncePhase++;
                long next;
                if (bouncePhase == 2) {
                    next = 300L;
                } else if (bouncePhase == 3) {
                    next = 1500L;
                } else if (bouncePhase == 4) {
                    next = 300L;
                } else {
                    bouncePhase = 0;
                    bouncingHud = false;
                    AppLog.i(TAG, "HUD 自动开关完成（共 2 轮）");
                    return;
                }
                chainBounce(app, next);
            }
        }, delay);
    }

    /**
     * 是否允许向车机下发指令。软引用桥接的只读开关（{@code com.cardash.inject.ReadOnly}）：
     * 独立 App 里没有那个类 ⇒ 走 catch 分支，保持原行为（允许），
     * 这样同一份源码给公司内置时不受影响。
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
