package com.s05.hudtraffic;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * HUD 显示管理器，移植自 D 应用「把 SR 显示到 HUD 副屏」的实现。
 *
 * <p>窗口参数与 D 完全一致：{@code type=2038}、{@code flags=280}、{@code TRANSLUCENT}、
 * 窗口铺满整块副屏且不做偏移；位置靠内容自身坐标控制。</p>
 *
 * <p><b>两条渲染路径</b>（实测车机 HUD 上普通 View 的主绘制层不可见，
 * 而 D 在 HUD 上生效的功能用的都是 surface 层，所以默认走 surface）：</p>
 * <ul>
 *   <li>{@code Prefs.isUseSurface()==true}：用 {@link HudSurfaceView}（SurfaceView）绘制，
 *       内容通过 canvas 坐标定位；</li>
 *   <li>{@code false}：用原来的 {@link TrafficLightPanel}（普通 View）+ 视图 translation。</li>
 * </ul>
 */
public final class HudTrafficManager {

    public interface Listener {
        void onHudStateChanged(boolean showing, String message);
    }

    private static final String TAG = "HudTraffic";

    private static final HudTrafficManager INSTANCE = new HudTrafficManager();

    public static HudTrafficManager get() {
        return INSTANCE;
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 一块叠加窗（HUD 副屏 或 主屏镜像）。panel 与 surface 只会有一个非空。 */
    private static final class Overlay {
        final String title;
        final Display display;
        final WindowManager wm;
        final FrameLayout root;
        final TrafficLightPanel panel;
        final HudSurfaceView surface;
        /** 创建时刻（uptimeMillis）。surface/attach 都是异步到位的，健康检查要给它宽限期。 */
        final long createdAt;

        Overlay(String title, Display display, WindowManager wm, FrameLayout root,
                TrafficLightPanel panel, HudSurfaceView surface) {
            this.title = title;
            this.display = display;
            this.wm = wm;
            this.root = root;
            this.panel = panel;
            this.surface = surface;
            this.createdAt = android.os.SystemClock.uptimeMillis();
        }
    }

    /**
     * 新建窗口后多久才开始信任"surface 没准备好 / 视图没挂上"这两个信号。
     *
     * <p>{@code addView} 之后 {@code surfaceCreated} 和第一次 traversal 都是异步的，
     * 立刻判断会把刚建好的窗口误判成坏的，于是销毁重建、无限循环。</p>
     */
    private static final long HEALTH_GRACE_MS = 3000L;

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private Context appContext;
    private Overlay hudOverlay;
    private Overlay mirrorOverlay;
    private HudTrafficPresentation presentation;
    private Display hudDisplay;

    /** 最近一次"重新 addView 置顶"的时刻（健康检查的宽限期要从它重新算）。 */
    private volatile long lastReattachAt = 0L;

    private boolean showing;
    private String lastMessage = "未启动";
    private float scale = 1.0f;

    private int appliedXPercent = Integer.MIN_VALUE;
    private int appliedYPercent = Integer.MIN_VALUE;
    private boolean appliedTestPattern = false;
    private boolean appliedUseSurface = true;

    private HudTrafficManager() {
    }

    public void setAppContext(Context ctx) {
        appContext = ctx.getApplicationContext();
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public boolean isShowing() {
        return showing;
    }

    public boolean isMirrorShowing() {
        return mirrorOverlay != null;
    }

    public String getLastMessage() {
        return lastMessage;
    }

    /**
     * 叠加窗是不是"看着还在、其实已经画不出内容了"（HUD 常驻功能的自愈判据）。
     *
     * <p>两种失效：</p>
     * <ol>
     *   <li>{@link HudSurfaceView} 收到过 {@code surfaceDestroyed} 却再没收到
     *       {@code surfaceCreated} —— 车机副屏重建 / 退出导航时很常见，
     *       此时 surface 永远画不出东西（ticker 也停了）；</li>
     *   <li>窗口被系统摘掉，根 View 已经不在窗口树上。</li>
     * </ol>
     *
     * <p>没建过叠加窗（{@code hudOverlay == null}）时返回 false —— 那种情况交给正常的
     * 刷新路径处理，看门狗绝不能自己制造重建循环。</p>
     *
     * <p>另外有 {@link #HEALTH_GRACE_MS} 宽限期：刚 addView 完 surface/traversal 还没到位，
     * 立即判断会把好窗口误判成坏的。</p>
     */
    public boolean needsRebuild() {
        final Overlay o = hudOverlay;
        if (o == null) {
            // 走的是 Presentation 兜底路径：它被系统 dismiss 掉之后 isShowing() 会是 false，
            // 也要能自愈；两者都没有时返回 false（交给正常刷新路径，别自己造重建循环）。
            return presentation != null && !presentation.isShowing();
        }
        long mark = Math.max(o.createdAt, lastReattachAt);
        if (android.os.SystemClock.uptimeMillis() - mark < HEALTH_GRACE_MS) {
            return false;
        }
        if (o.surface != null && !o.surface.isReady()) {
            return true;
        }
        try {
            return !o.root.isAttachedToWindow();
        } catch (Throwable t) {
            return true;
        }
    }

    public float getScale() {
        return scale;
    }

    public void setScale(float s) {
        scale = s;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (hudOverlay != null) {
                    if (hudOverlay.panel != null) {
                        hudOverlay.panel.setPanelScale(scale);
                        autoFitPanel(hudOverlay.panel, hudOverlay.display);
                    }
                    if (hudOverlay.surface != null) {
                        hudOverlay.surface.setSurfaceScale(scale);
                    }
                }
                if (mirrorOverlay != null) {
                    // 主屏悬浮窗是小窗口（WRAP_CONTENT），内容放大反而会被窗口裁掉，所以固定 100%
                    if (mirrorOverlay.panel != null) {
                        mirrorOverlay.panel.setPanelScale(1f);
                    }
                }
                if (presentation != null) {
                    presentation.setPanelScale(scale);
                }
            }
        });
    }

    /** 位置、缩放、测试图案、镜像开关、渲染方式任一变化都会触发重建。 */
    public void ensureShown(Context ctx, Display target) {
        ensureShown(ctx, target, false);
    }

    /**
     * @param forceMirror true 时强制显示主屏悬浮窗，用于「没有 HUD 副屏就退回主屏」的场景
     */
    public void ensureShown(Context ctx, Display target, final boolean forceMirror) {
        if (ctx != null) {
            appContext = ctx.getApplicationContext();
        }
        final Display d = target;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (appContext == null) {
                    notifyState(false, "应用上下文为空");
                    return;
                }
                boolean wantMirror = forceMirror || Prefs.isMirrorMain(appContext);
                int xPct = Prefs.getHudXPercent(appContext);
                int yPct = Prefs.getHudYPercent(appContext);
                boolean test = Prefs.isTestPattern(appContext);
                boolean useSurface = Prefs.isUseSurface(appContext);

                boolean hudActive = hudOverlay != null || presentation != null;
                boolean sameHud;
                if (d == null) {
                    sameHud = !hudActive;
                } else {
                    sameHud = hudActive && hudDisplay != null
                            && hudDisplay.getDisplayId() == d.getDisplayId();
                }
                boolean sameMirror = wantMirror == (mirrorOverlay != null);
                boolean sameConfig = xPct == appliedXPercent
                        && yPct == appliedYPercent
                        && test == appliedTestPattern
                        && useSurface == appliedUseSurface;

                // 除了"配置没变"，还要确认叠加窗本身还活着。
                // 退出导航 / 副屏重建时系统会把窗口或 surface 回收掉，此时 hudOverlay 这个
                // Java 对象仍然在（hudActive=true、sameHud=true），但画面已经画不出来了；
                // 只比配置的话会永远 early-return，表现就是"HUD 空白，必须手动关一次
                // 再打开 HUD 开关才恢复"（用户反馈的那个 bug）。
                // 健康时顺手重画一遍，保证窗口刚恢复就有内容。
                if (sameHud && sameMirror && sameConfig && !needsRebuild()) {
                    render(TrafficLightBus.getLatest());
                    return;
                }

                destroyAll();

                String hudMsg = "未找到 HUD 副屏";
                boolean hudOk = false;

                if (d != null) {
                    S05HudWakeHelper.fullWakeHud(appContext);

                    String overlayError = tryCreateHudOverlay(appContext, d);
                    if (overlayError == null) {
                        hudOk = true;
                        hudMsg = "悬浮窗已显示在 " + HudDisplayHelper.describe(d, appContext)
                                + " 渲染=" + (useSurface ? "SurfaceView" : "View")
                                + " 位置=" + xPct + "%/" + yPct + "%"
                                + (test ? " 探针开" : "");
                        AppLog.i(TAG, "HUD 悬浮窗已添加 displayId=" + d.getDisplayId()
                                + " 渲染=" + (useSurface ? "SurfaceView" : "View"));
                    } else {
                        AppLog.i(TAG, "悬浮窗方式失败(" + overlayError + ")，改用 Presentation 兜底");
                        String presError = tryShowPresentation(appContext, d);
                        if (presError == null) {
                            hudOk = true;
                            hudMsg = "Presentation 已显示在 " + HudDisplayHelper.describe(d, appContext);
                        } else {
                            hudMsg = "HUD 显示失败: " + overlayError + " / " + presError;
                        }
                    }
                }

                boolean mirrorOk = false;
                String mirrorMsg = "";
                if (wantMirror) {
                    String mirrorError = tryCreateMirrorOverlay(appContext);
                    if (mirrorError == null) {
                        mirrorOk = true;
                    } else {
                        AppLog.i(TAG, "主屏镜像悬浮窗失败: " + mirrorError);
                        mirrorMsg = "；主屏镜像失败: " + mirrorError;
                    }
                }

                if (hudOk) {
                    hudDisplay = d;
                    appliedXPercent = xPct;
                    appliedYPercent = yPct;
                    appliedTestPattern = test;
                    appliedUseSurface = useSurface;
                } else {
                    hudDisplay = null;
                }
                showing = hudOk;
                render(TrafficLightBus.getLatest());

                StringBuilder sb = new StringBuilder();
                sb.append(hudOk ? "HUD：显示中" : "HUD：未显示");
                sb.append("（").append(hudMsg).append("）");
                if (mirrorOk) {
                    sb.append("；主屏镜像：显示中");
                }
                sb.append(mirrorMsg);
                notifyState(hudOk || mirrorOk, sb.toString());
            }
        });
    }

    public void hide() {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                boolean wasActive = hudOverlay != null || mirrorOverlay != null || presentation != null;
                destroyAll();
                if (wasActive) {
                    notifyState(false, "已关闭 HUD 显示");
                }
            }
        });
    }

    /**
     * 把 HUD 叠加窗重新 addView 一次，让它回到同层级窗口的最上面。
     *
     * 同层级（TYPE_APPLICATION_OVERLAY）窗口谁后添加谁在上面：车机上 D 桌面的
     * 歌词/SR 窗口可能比我们晚加，就把我们盖住了；重新 addView 一次即可夺回。
     */
    public void bringToFront() {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                final Overlay o = hudOverlay;
                if (o == null) {
                    return;
                }
                try {
                    o.wm.removeViewImmediate(o.root);
                    o.wm.addView(o.root, (WindowManager.LayoutParams) o.root.getLayoutParams());
                    if (o.surface != null) {
                        o.surface.drawAll();
                    }
                    lastReattachAt = android.os.SystemClock.uptimeMillis();
                    AppLog.i(TAG, "HUD 叠加窗已重新置顶（重新 addView）");
                } catch (Throwable t) {
                    AppLog.i(TAG, "HUD 置顶失败: " + t);
                }
            }
        });
    }

    public void render(final TrafficLightState state) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (hudOverlay != null) {
                    if (hudOverlay.panel != null) {
                        hudOverlay.panel.render(state);
                    }
                    if (hudOverlay.surface != null) {
                        hudOverlay.surface.setState(state);
                    }
                }
                if (mirrorOverlay != null) {
                    if (mirrorOverlay.panel != null) {
                        mirrorOverlay.panel.render(state);
                    }
                    if (mirrorOverlay.surface != null) {
                        mirrorOverlay.surface.setState(state);
                    }
                }
                if (presentation != null) {
                    presentation.render(state);
                }
            }
        });
    }

    // ------------------------------------------------------------------

    private String tryCreateHudOverlay(Context ctx, Display d) {
        try {
            hudOverlay = createOverlay(ctx, d, "hud_traffic_overlay", true);
            return null;
        } catch (Throwable t) {
            hudOverlay = null;
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    private String tryCreateMirrorOverlay(Context ctx) {
        try {
            Overlay o = createOverlay(ctx, null, "hud_traffic_mirror", false);
            mirrorOverlay = o;
            return null;
        } catch (Throwable t) {
            mirrorOverlay = null;
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    private Overlay createOverlay(Context ctx, Display d, String title, boolean isHud) throws Exception {
        Context displayContext = d == null ? ctx : ctx.createDisplayContext(d);
        boolean testPattern = Prefs.isTestPattern(ctx);
        // HUD 侧默认走 SurfaceView；镜像留在主屏，用普通 View 就够
        boolean useSurface = isHud && Prefs.isUseSurface(ctx);
        Point size = overlaySize(d, displayContext);

        FrameLayout root = new FrameLayout(displayContext);
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setClipChildren(false);

        TrafficLightPanel panel = null;
        HudSurfaceView surface = null;

        if (useSurface) {
            surface = new HudSurfaceView(displayContext);
            root.addView(surface, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            surface.setSurfaceScale(scale);
            surface.setProbe(testPattern);
            if (!testPattern) {
                int xPct = Prefs.getHudXPercent(ctx);
                int yPct = Prefs.getHudYPercent(ctx);
                surface.setPanelCenter(
                        Math.round(size.x / 2f + (xPct - 50) / 100f * size.x),
                        Math.round(size.y / 2f + (yPct - 50) / 100f * size.y));
            }
        } else {
            panel = new TrafficLightPanel(displayContext);
            panel.setTestPattern(testPattern);
            FrameLayout.LayoutParams panelLp = testPattern
                    ? new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER)
                    : new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            root.addView(panel, panelLp);
            panel.setPanelScale(isHud && !testPattern ? scale : 1f);

            if (testPattern) {
                panel.setTranslationX(0f);
                panel.setTranslationY(0f);
            } else if (isHud) {
                int xPct = Prefs.getHudXPercent(ctx);
                int yPct = Prefs.getHudYPercent(ctx);
                if (size.x > 0) {
                    panel.setTranslationX((xPct - 50) / 100f * size.x);
                }
                if (size.y > 0) {
                    panel.setTranslationY((yPct - 50) / 100f * size.y);
                }
            }
            // 主屏悬浮窗不在这里定位：它是小窗口，靠窗口自己的 lp.x/lp.y 定位并且可拖动
        }

        WindowManager wm = (WindowManager) displayContext.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            throw new IllegalStateException("WindowManager 不可用");
        }

        // 窗口参数：
        // - HUD 副屏：与 D 应用一致（type=2038 / flags=280 / TRANSLUCENT，铺满且不偏移，位置靠内容 translation）
        // - 主屏悬浮窗：小窗口（WRAP_CONTENT）+ 可触摸，这样才能用手指拖动，也不会挡住桌面其它区域
        WindowManager.LayoutParams lp;
        if (isHud) {
            lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.CENTER;
        } else {
            lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            float dens = displayContext.getResources().getDisplayMetrics().density;
            int savedX = Prefs.getMirrorX(ctx);
            int savedY = Prefs.getMirrorY(ctx);
            lp.x = savedX >= 0 ? savedX : (int) (24 * dens);
            lp.y = savedY >= 0 ? savedY : (int) (160 * dens);
        }
        lp.setTitle(title);
        wm.addView(root, lp);

        Overlay overlay = new Overlay(title, d, wm, root, panel, surface);
        if (!isHud) {
            attachDrag(overlay);
        }
        final TrafficLightPanel fitPanel = panel;
        final HudSurfaceView fitSurface = surface;
        final Display fitDisplay = d;
        root.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (fitSurface != null) {
                    fitSurface.drawAll();
                }
                if (fitPanel != null) {
                    autoFitPanel(fitPanel, fitDisplay);
                }
            }
        }, 300L);
        return overlay;
    }

    /** 主屏悬浮窗拖动：按下记位置，移动改 lp.x/lp.y，松手把位置存进 Prefs。 */
    private void attachDrag(final Overlay overlay) {
        overlay.root.setOnTouchListener(new android.view.View.OnTouchListener() {
            private int startX;
            private int startY;
            private float downRawX;
            private float downRawY;
            private boolean moved;

            @Override
            public boolean onTouch(android.view.View v, android.view.MotionEvent event) {
                WindowManager.LayoutParams lp;
                try {
                    lp = (WindowManager.LayoutParams) overlay.root.getLayoutParams();
                } catch (Throwable t) {
                    return false;
                }
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        startX = lp.x;
                        startY = lp.y;
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        moved = false;
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        int dx = (int) (event.getRawX() - downRawX);
                        int dy = (int) (event.getRawY() - downRawY);
                        if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                            moved = true;
                        }
                        lp.x = startX + dx;
                        lp.y = startY + dy;
                        try {
                            overlay.wm.updateViewLayout(overlay.root, lp);
                        } catch (Throwable ignored) {
                        }
                        return true;
                    }
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        if (moved && appContext != null) {
                            Prefs.setMirrorPos(appContext, lp.x, lp.y);
                            AppLog.i(TAG, "悬浮窗位置已保存: " + lp.x + "," + lp.y);
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private Point overlaySize(Display d, Context displayContext) {
        Point size = new Point();
        try {
            if (d != null) {
                d.getRealSize(size);
            } else {
                android.util.DisplayMetrics dm = displayContext.getResources().getDisplayMetrics();
                size.set(dm.widthPixels, dm.heightPixels);
            }
        } catch (Throwable ignored) {
        }
        if (size.x <= 0 || size.y <= 0) {
            android.util.DisplayMetrics dm = displayContext.getResources().getDisplayMetrics();
            size.set(dm.widthPixels, dm.heightPixels);
        }
        return size;
    }

    private void autoFitPanel(TrafficLightPanel panel, Display d) {
        if (panel == null || panel.isTestPattern()) {
            return;
        }
        try {
            Point size = overlaySize(d, appContext);
            if (size.x <= 0 || size.y <= 0) {
                return;
            }
            int w = panel.getMeasuredWidth();
            int h = panel.getMeasuredHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            float fit = 1f;
            float maxW = size.x * 0.94f;
            float maxH = size.y * 0.94f;
            if (w > maxW) {
                fit = Math.min(fit, maxW / w);
            }
            if (h > maxH) {
                fit = Math.min(fit, maxH / h);
            }
            float effective = scale * fit;
            if (fit < 0.999f) {
                AppLog.i(TAG, "面板 " + w + "x" + h + " 超出屏幕 " + size.x + "x" + size.y
                        + "，自动缩放至 " + String.format(java.util.Locale.US, "%.2f", effective));
            }
            panel.setPanelScale(effective);
        } catch (Throwable t) {
            AppLog.i(TAG, "autoFitPanel 失败: " + t);
        }
    }

    private String tryShowPresentation(Context ctx, Display d) {
        try {
            HudTrafficPresentation p = new HudTrafficPresentation(ctx, d);
            p.setPanelScale(scale);
            p.setTestPattern(Prefs.isTestPattern(ctx));
            p.show();
            presentation = p;
            return null;
        } catch (Throwable t) {
            presentation = null;
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    private void destroyAll() {
        destroyOverlay(hudOverlay);
        hudOverlay = null;
        destroyOverlay(mirrorOverlay);
        mirrorOverlay = null;
        if (presentation != null) {
            try {
                presentation.dismiss();
            } catch (Throwable t) {
                AppLog.i(TAG, "关闭 Presentation 失败: " + t);
            }
            presentation = null;
        }
        showing = false;
        hudDisplay = null;
    }

    private void destroyOverlay(Overlay overlay) {
        if (overlay == null) {
            return;
        }
        try {
            overlay.wm.removeViewImmediate(overlay.root);
        } catch (Throwable t) {
            AppLog.i(TAG, "移除悬浮窗 " + overlay.title + " 失败: " + t);
        }
    }

    private void notifyState(boolean isShowing, String message) {
        boolean changed = message != null && !message.equals(lastMessage);
        lastMessage = message;
        if (changed) {
            AppLog.i(TAG, message);
        }
        for (Listener l : listeners) {
            try {
                l.onHudStateChanged(isShowing, message);
            } catch (Throwable ignored) {
            }
        }
    }
}
