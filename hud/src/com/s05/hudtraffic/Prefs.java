package com.s05.hudtraffic;

import android.content.Context;
import android.content.SharedPreferences;

/** 应用配置。 */
public final class Prefs {

    private static final String NAME = "hud_traffic_prefs";

    public static final String KEY_HUD_ENABLED = "hud_enabled";
    public static final String KEY_RELAXED_DISPLAY = "relaxed_display";
    public static final String KEY_SIMULATE = "simulate";
    public static final String KEY_SCALE = "hud_scale";
    public static final String KEY_MIRROR_MAIN = "mirror_main";
    public static final String KEY_HUD_X_PERCENT = "hud_x_percent";
    public static final String KEY_HUD_Y_PERCENT = "hud_y_percent";
    public static final String KEY_TEST_PATTERN = "test_pattern";
    /** HUD 用 SurfaceView 渲染（实测普通 View 在车机 HUD 上不可见，默认开） */
    public static final String KEY_USE_SURFACE = "use_surface";

    /**
     * 默认落点：按 D 应用 SR 的真实几何算出来的位置。
     *
     * <p>D 的代码是 {@code FrameLayout.LayoutParams(683, 846, gravity=17)} 再
     * {@code setTranslationX(hudSrX - 341.5f)}；但 gravity=17 会把 683x846 的子视图
     * 居中（左边缘 58.5、上边缘 -183），所以真实中心是
     * {@code (400+620-341.5, 240+450-423) = (678.5, 267)}，
     * 缩放 35% 后 SR 正好占据 x 559~798 / y 119~415 —— 即照片里的黄框。
     * 换算成百分比约 85% / 55%。</p>
     */
    public static final int DEFAULT_X_PERCENT = 88;
    public static final int DEFAULT_Y_PERCENT = 53;

    private Prefs() {
    }

    public static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static boolean isHudEnabled(Context ctx) {
        return sp(ctx).getBoolean(KEY_HUD_ENABLED, false);
    }

    public static void setHudEnabled(Context ctx, boolean v) {
        sp(ctx).edit().putBoolean(KEY_HUD_ENABLED, v).apply();
    }

    public static boolean isRelaxedDisplay(Context ctx) {
        return sp(ctx).getBoolean(KEY_RELAXED_DISPLAY, false);
    }

    public static void setRelaxedDisplay(Context ctx, boolean v) {
        sp(ctx).edit().putBoolean(KEY_RELAXED_DISPLAY, v).apply();
    }

    public static boolean isSimulate(Context ctx) {
        return sp(ctx).getBoolean(KEY_SIMULATE, false);
    }

    public static void setSimulate(Context ctx, boolean v) {
        sp(ctx).edit().putBoolean(KEY_SIMULATE, v).apply();
    }

    public static int getScale(Context ctx) {
        // 默认 85%：用户实测 HUD 上 100% 偏大（黄框区域放不下）
        return sp(ctx).getInt(KEY_SCALE, 79);
    }

    public static void setScale(Context ctx, int v) {
        sp(ctx).edit().putInt(KEY_SCALE, v).apply();
    }

    /** 同时在主屏（桌面）也显示一个悬浮窗，用于验证/调试。 */
    public static boolean isMirrorMain(Context ctx) {
        return sp(ctx).getBoolean(KEY_MIRROR_MAIN, false);
    }

    public static void setMirrorMain(Context ctx, boolean v) {
        sp(ctx).edit().putBoolean(KEY_MIRROR_MAIN, v).apply();
    }

    /** HUD 上面板中心点的横向位置，百分比（0~100）。 */
    public static int getHudXPercent(Context ctx) {
        return sp(ctx).getInt(KEY_HUD_X_PERCENT, DEFAULT_X_PERCENT);
    }

    public static void setHudXPercent(Context ctx, int v) {
        sp(ctx).edit().putInt(KEY_HUD_X_PERCENT, clamp(v)).apply();
    }

    /** HUD 上面板中心点的纵向位置，百分比（0~100）。 */
    public static int getHudYPercent(Context ctx) {
        return sp(ctx).getInt(KEY_HUD_Y_PERCENT, DEFAULT_Y_PERCENT);
    }

    public static void setHudYPercent(Context ctx, int v) {
        sp(ctx).edit().putInt(KEY_HUD_Y_PERCENT, clamp(v)).apply();
    }

    /** HUD 是否用 SurfaceView 渲染。实测车机 HUD 上普通 View 完全不可见，默认用 surface 层。 */
    public static boolean isUseSurface(Context ctx) {
        return sp(ctx).getBoolean(KEY_USE_SURFACE, true);
    }

    public static void setUseSurface(Context ctx, boolean v) {
        sp(ctx).edit().putBoolean(KEY_USE_SURFACE, v).apply();
    }

    /** 定位测试图案：在 HUD 上画一个高对比度大十字，便于找到可见区域。 */
    public static boolean isTestPattern(Context ctx) {
        return sp(ctx).getBoolean(KEY_TEST_PATTERN, false);
    }

    public static void setTestPattern(Context ctx, boolean v) {
        sp(ctx).edit().putBoolean(KEY_TEST_PATTERN, v).apply();
    }

    /* ---- 控件独立控制（点 HUD 预览里的控件选中，滑块绑定它） ---- */

    public static boolean isWidgetEnabled(Context ctx, int id) {
        return sp(ctx).getBoolean("w" + id + "_en", true);
    }

    public static void setWidgetEnabled(Context ctx, int id, boolean v) {
        sp(ctx).edit().putBoolean("w" + id + "_en", v).apply();
    }

    /**
     * 控件位置的存储单位 = <b>0.1%</b>（0~1000，500 = 不偏移、跟随面板堆叠位置）。
     *
     * 之前存整数百分比：1% 在 800px 宽上是 8px 的吸附网格，拖动松手后控件会"跳"一格，
     * 看起来就是和手指错位。改成 0.1%（0.8px）后基本看不出来。
     */
    public static int getWidgetX10(Context ctx, int id) {
        return sp(ctx).getInt("w" + id + "_x10", 500);
    }

    public static int getWidgetY10(Context ctx, int id) {
        return sp(ctx).getInt("w" + id + "_y10", 500);
    }

    public static void setWidgetX10(Context ctx, int id, int v) {
        sp(ctx).edit().putInt("w" + id + "_x10", Math.max(0, Math.min(v, 1000))).apply();
    }

    public static void setWidgetY10(Context ctx, int id, int v) {
        sp(ctx).edit().putInt("w" + id + "_y10", Math.max(0, Math.min(v, 1000))).apply();
    }

    /** 整数百分比（滑块用） */
    public static int getWidgetX(Context ctx, int id) {
        return getWidgetX10(ctx, id) / 10;
    }

    public static int getWidgetY(Context ctx, int id) {
        return getWidgetY10(ctx, id) / 10;
    }

    public static void setWidgetX(Context ctx, int id, int v) {
        setWidgetX10(ctx, id, v * 10);
    }

    public static void setWidgetY(Context ctx, int id, int v) {
        setWidgetY10(ctx, id, v * 10);
    }

    public static int getWidgetScale(Context ctx, int id) {
        return sp(ctx).getInt("w" + id + "_s", 100);
    }

    public static void setWidgetScale(Context ctx, int id, int v) {
        sp(ctx).edit().putInt("w" + id + "_s", Math.max(30, Math.min(v, 200))).apply();
    }

    /** 整体拖动：true = 拖动/缩放整块面板；false = 单独选中某个控件调整 */
    public static final String KEY_DRAG_WHOLE = "drag_whole";

    public static boolean isDragWhole(Context ctx) {
        return sp(ctx).getBoolean(KEY_DRAG_WHOLE, true);
    }

    public static void setDragWhole(Context ctx, boolean v) {
        sp(ctx).edit().putBoolean(KEY_DRAG_WHOLE, v).apply();
    }

    /** 恢复默认设置：面板位置/缩放 + 所有控件的独立设置全部回到出厂值 */
    public static void resetHudDefaults(Context ctx) {
        android.content.SharedPreferences.Editor e = sp(ctx).edit();
        e.putInt(KEY_HUD_X_PERCENT, DEFAULT_X_PERCENT);
        e.putInt(KEY_HUD_Y_PERCENT, DEFAULT_Y_PERCENT);
        e.putInt(KEY_SCALE, 79);
        e.putBoolean(KEY_DRAG_WHOLE, true);
        for (int i = 0; i < HudPanelRenderer.W_COUNT; i++) {
            e.putBoolean("w" + i + "_en", true);
            e.putInt("w" + i + "_x10", 500);
            e.putInt("w" + i + "_y10", 500);
            e.putInt("w" + i + "_s", 100);
        }
        e.apply();
    }

    private static int clamp(int v) {
        if (v < 0) {
            return 0;
        }
        return Math.min(v, 100);
    }

    /* ---------------- 代理桌面（HOME 身份保活） ---------------- */

    /** 代理桌面要转发到的真正的桌面包名 */
    public static final String KEY_HOME_FORWARD_PACKAGE = "home_forward_package";

    public static String getHomeForwardPackage(Context ctx) {
        return sp(ctx).getString(KEY_HOME_FORWARD_PACKAGE, "");
    }

    public static void setHomeForwardPackage(Context ctx, String pkg) {
        sp(ctx).edit().putString(KEY_HOME_FORWARD_PACKAGE, pkg == null ? "" : pkg).apply();
    }

    /* ---------------- 主屏悬浮窗（可拖动）的位置 ---------------- */

    public static final String KEY_MIRROR_X = "mirror_x";
    public static final String KEY_MIRROR_Y = "mirror_y";

    /** 悬浮窗左上角坐标；-1 表示还没拖动过，用默认位置 */
    public static int getMirrorX(Context ctx) {
        return sp(ctx).getInt(KEY_MIRROR_X, -1);
    }

    public static int getMirrorY(Context ctx) {
        return sp(ctx).getInt(KEY_MIRROR_Y, -1);
    }

    public static void setMirrorPos(Context ctx, int x, int y) {
        sp(ctx).edit().putInt(KEY_MIRROR_X, x).putInt(KEY_MIRROR_Y, y).apply();
    }

    /* ---------------- 找不到 HUD 副屏时自动退回主屏悬浮窗 ---------------- */

    public static final String KEY_AUTO_FALLBACK = "auto_fallback_main";

    /**
     * 默认开启：只要没找到 HUD 副屏（模拟器、HUD 未接入等），
     * 就自动在主屏显示一个可拖动的悬浮窗，保证至少能看到内容。
     */
    public static boolean isAutoFallback(Context ctx) {
        return sp(ctx).getBoolean(KEY_AUTO_FALLBACK, true);
    }

    public static void setAutoFallback(Context ctx, boolean v) {
        sp(ctx).edit().putBoolean(KEY_AUTO_FALLBACK, v).apply();
    }

    /* ---------------- HUD 文字黑描边 ---------------- */

    public static final String KEY_TEXT_OUTLINE = "text_outline";

    /**
     * 是否需要给 HUD 文字加黑色粗描边。
     * <p><b>默认关闭</b>：粗描边在 HUD 上会有明显锯齿毛边（用户反馈），
     * 只填充反而更干净。白天看不清时可以打开。</p>
     */
    public static boolean isTextOutline(Context ctx) {
        return sp(ctx).getBoolean(KEY_TEXT_OUTLINE, false);
    }

    public static void setTextOutline(Context ctx, boolean v) {
        sp(ctx).edit().putBoolean(KEY_TEXT_OUTLINE, v).apply();
    }

    /* ---------------- 全部控件预览 ---------------- */

    public static final String KEY_PREVIEW_ALL = "preview_all";

    /**
     * 全部控件预览：三个方向格子都用示例数据（888）画出来，
     * 方便在还没拿到真实数据时调整位置和缩放，确认「数据变化不会改变布局」。
     */
    public static boolean isPreviewAll(Context ctx) {
        return sp(ctx).getBoolean(KEY_PREVIEW_ALL, false);
    }

    public static void setPreviewAll(Context ctx, boolean v) {
        sp(ctx).edit().putBoolean(KEY_PREVIEW_ALL, v).apply();
    }

    /* ---------------- 预览数据模式（示例 / 真实） ---------------- */

    public static final String KEY_PREVIEW_DEMO = "preview_demo";

    /**
     * 主界面预览是否用示例数据（默认开）。
     *
     * <p>开 = 始终把全部控件用示例数据画出来，方便在没有真实数据时调位置和大小；
     * 关 = 和 HUD 一样显示真实数据（打开「模拟数据源」时会自动关掉它）。</p>
     */
    public static boolean isPreviewDemo(Context ctx) {
        return sp(ctx).getBoolean(KEY_PREVIEW_DEMO, true);
    }

    public static void setPreviewDemo(Context ctx, boolean v) {
        sp(ctx).edit().putBoolean(KEY_PREVIEW_DEMO, v).apply();
    }

    /* ---------------- 悬浮球入口 ---------------- */

    public static final String KEY_ENTRY_BALL = "entry_ball";
    public static final String KEY_ENTRY_X = "entry_x";
    public static final String KEY_ENTRY_Y = "entry_y";
    /** 悬浮球直径（dp），默认 46 */
    public static final String KEY_ENTRY_SIZE = "entry_size";
    /** 悬浮球不透明度（百分比 20~100），默认 100 */
    public static final String KEY_ENTRY_ALPHA = "entry_alpha";

    /**
     * 是否在高德界面上显示"悬浮球入口"（默认开）。
     *
     * <p>高德的设置页是引擎自绘的、插不了菜单，所以用悬浮球当入口：
     * 点一下打开 HUD 设置；导航中会自动隐藏，不挡地图；可以拖动到顺手的位置。</p>
     */
    public static boolean isEntryBall(Context ctx) {
        return sp(ctx).getBoolean(KEY_ENTRY_BALL, true);
    }

    public static void setEntryBall(Context ctx, boolean v) {
        sp(ctx).edit().putBoolean(KEY_ENTRY_BALL, v).apply();
    }

    /** 悬浮球位置（左上角坐标）；-1 表示还没拖过，用默认位置（屏幕右侧中部） */
    public static int getEntryX(Context ctx) {
        return sp(ctx).getInt(KEY_ENTRY_X, -1);
    }

    public static int getEntryY(Context ctx) {
        return sp(ctx).getInt(KEY_ENTRY_Y, -1);
    }

    public static void setEntryPos(Context ctx, int x, int y) {
        sp(ctx).edit().putInt(KEY_ENTRY_X, x).putInt(KEY_ENTRY_Y, y).apply();
    }

    /** 悬浮球直径（dp）：范围 24~96，默认 46。 */
    public static int getEntrySize(Context ctx) {
        int v = sp(ctx).getInt(KEY_ENTRY_SIZE, 46);
        return Math.max(24, Math.min(v, 96));
    }

    public static void setEntrySize(Context ctx, int dp) {
        sp(ctx).edit().putInt(KEY_ENTRY_SIZE, Math.max(24, Math.min(dp, 96))).apply();
    }

    /** 悬浮球不透明度（%）：范围 20~100，默认 100（完全不透明）。 */
    public static int getEntryAlpha(Context ctx) {
        int v = sp(ctx).getInt(KEY_ENTRY_ALPHA, 100);
        return Math.max(20, Math.min(v, 100));
    }

    public static void setEntryAlpha(Context ctx, int percent) {
        sp(ctx).edit().putInt(KEY_ENTRY_ALPHA, Math.max(20, Math.min(percent, 100))).apply();
    }

}
