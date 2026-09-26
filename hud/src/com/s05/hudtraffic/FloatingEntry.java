package com.s05.hudtraffic;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Point;
import java.io.BufferedReader;
import java.io.FileReader;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * 悬浮球入口：在高德界面上显示一个小圆点，点一下打开 HUD 红绿灯的设置界面。
 *
 * <p><b>为什么需要它</b>：高德的设置页是引擎（native）自绘的，没法往里插菜单，
 * 而车机桌面有时不显示新装的图标，所以给一个"一直看得见"的入口。</p>
 *
 * <p>行为：导航中（10019/EXTRA_STATE=8）自动隐藏，不挡地图；平时显示；
 * 可以拖动到顺手的位置（位置会记住）；在设置里可以整个关掉。</p>
 */
public final class FloatingEntry {

    private static final String TAG = "EntryBall";

    private static View ball;
    /** 悬浮球所在的 app context（换图标时要用来读资源/文件） */
    private static Context appCtx;
    private static WindowManager wm;
    private static WindowManager.LayoutParams lp;
    private static boolean navigating;
    private static boolean visible;

    private FloatingEntry() {
    }

    /** 显示悬浮球（幂等；没有悬浮窗权限就跳过）。 */
    public static synchronized void show(Context ctx) {
        if (ctx == null) {
            return;
        }
        // ⚠️ 高德是**多进程**应用（主进程 / :locationservice / 我们的 :daemon ...），
        // 每个进程都会跑一遍 Application.onCreate → 我们的启动 hook 会被执行多次。
        // 之前没有进程判断，于是每个进程各画一个悬浮球（用户看到 4 个球，拖动时其它几个不动）。
        // 现在只允许主进程画，其它进程直接返回。
        if (!isMainProcess(ctx)) {
            AppLog.i(TAG, "非主进程（" + currentProcessName() + "）不显示悬浮球，避免多进程各画一个");
            return;
        }
        // View 还挂在窗口上就说明已经有了 —— 重复调用（开机广播/服务/设置界面）不再新建
        if (ball != null && ball.isAttachedToWindow()) {
            return;
        }
        final Context app = ctx.getApplicationContext();
        if (!Settings.canDrawOverlays(app)) {
            AppLog.i(TAG, "没有悬浮窗权限，悬浮球不显示（可在设置里开启）");
            return;
        }
        final WindowManager manager = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        if (manager == null) {
            return;
        }

        final float d = app.getResources().getDisplayMetrics().density;
        int size = Math.round(Prefs.getEntrySize(app) * d);

        // 图标：优先用用户自定义的（相册选的），没有就用内置默认图；
        // 用 CircleIconView 裁成圆形（不管原图是方是长，显示出来都是圆的）
        CircleIconView iv = new CircleIconView(app);
        iv.setIconDrawable(EntryBallIcon.ballDrawable(app));
        iv.setAlpha(Prefs.getEntryAlpha(app) / 100f);
        ball = iv;
        appCtx = app;

        lp = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        // 默认位置用 gravity 相对定位（右侧垂直居中），不依赖屏幕尺寸 ——
        // getRealSize() 在部分设备返回的是物理方向尺寸：横屏显示时按它算出的 y 会超出屏幕，
        // 悬浮球就会被裁到屏幕外"消失"（MuMu 上实测踩过）。
        int savedX = Prefs.getEntryX(app);
        int savedY = Prefs.getEntryY(app);
        if (savedX >= 0) {
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = savedX;
            lp.y = savedY;
        } else {
            lp.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
            lp.x = Math.round(8 * d);
            lp.y = 0;
        }

        final int sizeFinal = size;
        ball.setOnTouchListener(new View.OnTouchListener() {
            private int startX;
            private int startY;
            private float downRawX;
            private float downRawY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        // 默认 gravity 是相对定位（右侧居中），拖动前先把当前位置
                        // 换算成屏幕绝对坐标，之后按 TOP|START 拖动，手感才正常
                        int[] loc = new int[2];
                        v.getLocationOnScreen(loc);
                        lp.gravity = Gravity.TOP | Gravity.START;
                        lp.x = loc[0];
                        lp.y = loc[1];
                        startX = lp.x;
                        startY = lp.y;
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        moved = false;
                        try {
                            wm.updateViewLayout(ball, lp);
                        } catch (Throwable ignored) {
                        }
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) (event.getRawX() - downRawX);
                        int dy = (int) (event.getRawY() - downRawY);
                        if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                            moved = true;
                        }
                        lp.x = startX + dx;
                        lp.y = startY + dy;
                        try {
                            wm.updateViewLayout(ball, lp);
                        } catch (Throwable ignored) {
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (moved) {
                            if (app != null) {
                                snapToEdge(app);   // 松手吸附到最近的左右屏幕边缘
                                Prefs.setEntryPos(app, lp.x, lp.y);
                                AppLog.i(TAG, "悬浮球位置已保存: " + lp.x + "," + lp.y);
                            }
                        } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                            openSettings(v.getContext());
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });

        try {
            manager.addView(ball, lp);
        } catch (Throwable t) {
            ball = null;
            AppLog.i(TAG, "添加悬浮球失败: " + t);
            return;
        }
        wm = manager;
        visible = true;
        ball.setVisibility(navigating ? View.GONE : View.VISIBLE);
        // 尺寸兜底（部分 ROM 会忽略 lp.width，用测量值再确认一次）
        if (sizeFinal > 0) {
            ball.post(new Runnable() {
                @Override
                public void run() {
                    if (ball != null && ball.getWidth() <= 0) {
                        ball.getLayoutParams().width = sizeFinal;
                        ball.getLayoutParams().height = sizeFinal;
                    }
                }
            });
        }
        AppLog.i(TAG, "悬浮球入口已显示（点击打开 HUD 设置）");
    }

    /** 移除悬浮球。 */
    public static synchronized void hide() {
        if (ball == null) {
            return;
        }
        try {
            if (wm != null) {
                wm.removeViewImmediate(ball);
            }
        } catch (Throwable t) {
            AppLog.i(TAG, "移除悬浮球失败: " + t);
        }
        ball = null;
        wm = null;
        appCtx = null;
        visible = false;
    }

    public static boolean isVisible() {
        return visible;
    }

    /** 拖动松手后把球吸附到最近的左右屏幕边缘（车机桌面常见交互）。 */
    private static void snapToEdge(Context app) {
        if (ball == null || wm == null || lp == null) {
            return;
        }
        int screenW = app.getResources().getDisplayMetrics().widthPixels;
        int bw = ball.getWidth() > 0 ? ball.getWidth() : lp.width;
        int cx = lp.x + bw / 2;
        lp.x = (cx < screenW / 2) ? 0 : Math.max(0, screenW - bw);
        try {
            wm.updateViewLayout(ball, lp);
        } catch (Throwable ignored) {
        }
    }

    /** 悬浮球左上角在屏幕上的绝对 X；没显示时返回 -1（悬浮面板用） */
    public static int getBallX() {
        if (ball == null) {
            return -1;
        }
        int[] loc = new int[2];
        ball.getLocationOnScreen(loc);
        return loc[0];
    }

    /** 悬浮球左上角在屏幕上的绝对 Y；没显示时返回 -1（悬浮面板用） */
    public static int getBallY() {
        if (ball == null) {
            return -1;
        }
        int[] loc = new int[2];
        ball.getLocationOnScreen(loc);
        return loc[1];
    }

    /**
     * 换图标后原地刷新（不重建窗口，避免闪一下）：
     * 有自定义图片就用自定义的，没有就用内置默认图。
     */
    public static synchronized void refreshIcon(Context ctx) {
        final View v = ball;
        if (ctx == null || !(v instanceof CircleIconView)) {
            return;
        }
        final android.graphics.drawable.Drawable dd =
                EntryBallIcon.ballDrawable(ctx.getApplicationContext());
        v.post(new Runnable() {
            @Override
            public void run() {
                ((CircleIconView) v).setIconDrawable(dd);
            }
        });
    }

    /**
     * 应用「大小 / 透明度」设置（拖 SeekBar 时实时调用，不重建窗口）。
     */
    public static synchronized void applyStyle(Context ctx) {
        final View v = ball;
        final Context app = ctx == null ? appCtx : ctx.getApplicationContext();
        if (v == null || app == null || wm == null || lp == null) {
            return;
        }
        float d = app.getResources().getDisplayMetrics().density;
        lp.width = Math.round(Prefs.getEntrySize(app) * d);
        lp.height = lp.width;
        v.setAlpha(Prefs.getEntryAlpha(app) / 100f);
        try {
            wm.updateViewLayout(v, lp);
        } catch (Throwable t) {
            AppLog.i(TAG, "更新悬浮球样式失败: " + t);
        }
    }

    /* ================= 进程判断 ================= */

    /**
     * 是否主进程。
     *
     * <p>高德是多进程应用（主进程、`:locationservice`、我们的 `:daemon` 等），
     * 每个进程都会执行一遍 Application.onCreate → 启动 hook 会被跑多次。
     * 悬浮球只允许主进程画一个。</p>
     */
    private static boolean isMainProcess(Context ctx) {
        String cur = currentProcessName();
        if (cur == null || cur.length() == 0) {
            return true;   // 读不到就按主进程处理（不至于让功能失效）
        }
        String pkg = ctx == null ? null : ctx.getPackageName();
        return pkg != null && pkg.equals(cur);
    }

    /** 当前进程名（读 /proc/self/cmdline，比遍历 ActivityManager 快且无 IPC）。 */
    private static String currentProcessName() {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/self/cmdline"));
            String line = r.readLine();
            return line == null ? null : line.trim();
        } catch (Throwable t) {
            return null;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 导航中隐藏悬浮球（不挡地图），空闲时恢复显示。
     * 由 {@link AmapTrafficReceiver} 在收到高德 10019 模式广播时调用。
     */
    public static void setNavigating(final boolean nav) {
        navigating = nav;
        final View v = ball;
        if (v == null) {
            return;
        }
        v.post(new Runnable() {
            @Override
            public void run() {
                v.setVisibility(nav ? View.GONE : View.VISIBLE);
            }
        });
    }

    /**
     * 点击悬浮球：显示/隐藏悬浮设置面板（overlay）。
     *
     * <p>刻意<b>不启动 Activity</b>：启动 Activity 会把高德界面切走、打断导航，
     * 那就变成"两个 App"了。用 overlay 面板才是在高德之上就地调设置。</p>
     */
    private static void openSettings(Context ctx) {
        try {
            if (HudSettingsPanel.isVisible()) {
                HudSettingsPanel.hide();
            } else {
                HudSettingsPanel.show(ctx);
            }
        } catch (Throwable t) {
            AppLog.i(TAG, "打开设置面板失败: " + t);
        }
    }
}
