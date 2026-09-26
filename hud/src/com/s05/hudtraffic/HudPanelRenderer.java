package com.s05.hudtraffic;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * HUD 画面绘制实现 —— <b>HUD 副屏（SurfaceView）和主界面预览（View）共用这一份代码</b>。
 *
 * <p>画面构成（<b>每个控件都是固定尺寸的格子，数据变化不会引起错位</b>）：</p>
 * <pre>
 *   ┌────────┐ ┌────────┐ ┌────────┐
 *   │ ●↑ 165 │ │ ●← 20  │ │ ●→  8  │      ← 三个固定格子：直行 / 左转 / 右转
 *   └────────┘ └────────┘ └────────┘
 *   顺源路                       18:02:05
 * </pre>
 *
 * <p>要点：</p>
 * <ul>
 *   <li>倒计时一律按 <b>3 位数字</b>预留宽度，数字从 8 变成 165 也不会让别的控件挪位；</li>
 *   <li>三个方向各占一个固定格子，<b>没有数据的方向留空但仍占位</b>，所以布局恒定；</li>
 *   <li>格子横排放不下时自动改成竖排（HUD 可见区域较窄时）；</li>
 *   <li>背景完全透明，文字默认只填充（无黑描边，避免锯齿毛边）。</li>
 * </ul>
 */
public final class HudPanelRenderer {

    /** 三个固定格子的方向：左转 / 直行 / 右转 —— 直行放中间（掉头很少见，不单独占格） */
    private static final int[] SLOT_DIRS = {
            TrafficLightState.DIR_LEFT,
            TrafficLightState.DIR_STRAIGHT,
            TrafficLightState.DIR_RIGHT,
    };

    /* ---- 格子尺寸（dp）：固定值，不随数据变化 ---- */
    private static final float CELL_W = 88f;
    private static final float CELL_H = 46f;
    private static final float CELL_GAP = 10f;
    private static final float CIRCLE_D = 34f;
    private static final float COUNT_SIZE = 28f;
    private static final float FOOTER_SIZE = 16f;
    /** 顶部「当前时间」的字号（比信息行大一点，用户调过一次：22 → 24） */
    private static final float TIME_SIZE = 24f;
    /**
     * 数字的 7 段编码：bit0=a(上) bit1=b(右上) bit2=c(右下) bit3=d(下)
     * bit4=e(左下) bit5=f(左上) bit6=g(中)。
     *
     * <p>hong.apk 里的 LED 数字是它**自绘**的（它自带的 font.ttf 渲染出来是普通细线字体，
     * 已实测确认），所以时间也自绘 —— 不依赖字体文件，HUD 上线宽也可控。</p>
     */
    private static final int[] LED_SEGMENTS = {
            0b0111111, // 0: a b c d e f
            0b0000110, // 1: b c
            0b1011011, // 2: a b d e g
            0b1001111, // 3: a b c d g
            0b1100110, // 4: b c f g
            0b1101101, // 5: a c d f g
            0b1111101, // 6: a c d e f g
            0b0000111, // 7: a b c
            0b1111111, // 8: 全亮
            0b1101111, // 9: a b c d f g
    };
    /** LED 数字比例（都相对数字高度）：数字宽 / 冒号宽 / 字符间距 / 笔画粗细 / 冒号点半径 */
    private static final float DIGIT_W_RATIO = 0.56f;
    private static final float COLON_W_RATIO = 0.20f;
    private static final float LED_GAP_RATIO = 0.16f;
    private static final float SEG_THICK_RATIO = 0.105f;
    private static final float COLON_DOT_RATIO = 0.075f;
    /* ---- 图标信息行：第一行=下个红绿灯距离/红绿灯数，第二行=终点距离/用时 ---- */
    private static final float INFO1_SIZE = 20f;
    private static final float INFO1_ICON = 22f;
    private static final float INFO2_SIZE = 18f;
    private static final float INFO2_ICON = 20f;
    /** 图标与其后数字之间的间距 */
    private static final float ICON_TEXT_GAP = 5f;
    /** 相邻两组「图标+数字」之间的间距 */
    private static final float SEG_GAP = 12f;
    private static final float ROW_GAP = 8f;

    /**
     * 「注意」窗口：该方向倒计时只剩这么多秒（即将变灯）时，把秒数换成"注意"两个字。
     *
     * <p>真机日志确认高德**不会广播黄灯**（trafficLightStatus 只见过 1/2/4），
     * 所以黄灯语义只能自己按"倒计时快结束"推导；该条件同时也覆盖真正的 status=3。</p>
     */
    private static final int CAUTION_SECONDS = 3;

    /** 倒计时最大位数（用于预留宽度） */
    private static final String WIDTH_SAMPLE = "888";

    private static final int TYPE_LIGHT = 3;
    private static final int TYPE_HINT = 4;

    private static final SimpleDateFormat BEIJING_TIME =
            new SimpleDateFormat("HH:mm", Locale.CHINA);

    static {
        BEIJING_TIME.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
    }

    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);

    /**
     * 文字黑描边开关。<b>默认关闭</b>：粗黑描边在 800x480 的 HUD 表面上会有明显锯齿毛边。
     * 运行时切换：{@code adb shell am broadcast -a com.s05.hudtraffic.DEBUG --ez outline true}
     */
    private static volatile boolean outlineEnabled = false;

    /**
     * 全部控件预览：三个格子都用示例数据（888）画出来，
     * 方便在不确定数据的情况下调整位置和缩放。
     */
    private static volatile boolean previewAll = false;

    /** 全局 context（读取"控件独立控制"配置用） */
    private static volatile android.content.Context appCtx;

    /* ---- 控件独立控制：每个控件可单独 开/关、平移、缩放 ---- */
    public static final int W_TIME = 0;
    public static final int W_LIGHTS = 1;
    public static final int W_INFO1 = 2;
    public static final int W_INFO2 = 3;
    public static final int W_ETA = 4;
    public static final int W_ROAD = 5;
    public static final int W_COUNT = 6;
    public static final String[] W_NAMES = {
            "当前时间", "红绿灯灯组", "红绿灯数量/电子眼", "终点距离/用时", "预计到达", "当前路名"};

    /** 每个控件的实际矩形（布局坐标）：{cx, cy, halfW, halfH}，draw() 时更新。 */
    private static final float[][] LAST_RECTS = new float[W_COUNT][4];

    public static void setAppContext(android.content.Context c) {
        appCtx = c == null ? null : c.getApplicationContext();
    }

    /** 控件的绘制参数：{dx, dy, scale, enabled}。 */
    private static float[] widgetTransform(int id, float maxWidth, float maxHeight) {
        float[] out = {0f, 0f, 1f, 1f};
        if (appCtx == null) {
            return out;
        }
        out[3] = Prefs.isWidgetEnabled(appCtx, id) ? 1f : 0f;
        // 0.1% 精度（500 = 不偏移）
        out[0] = (Prefs.getWidgetX10(appCtx, id) - 500) / 1000f * maxWidth;
        out[1] = (Prefs.getWidgetY10(appCtx, id) - 500) / 1000f * maxHeight;
        out[2] = Prefs.getWidgetScale(appCtx, id) / 100f;
        return out;
    }

    /**
     * 计算每个控件的实际矩形（布局坐标）：{cx, cy, halfW, halfH}。
     *
     * <p>与 {@link #draw} 的排布<b>完全一致</b>（含面板中心夹取、控件独立平移/缩放）。
     * 每次按传入的画布参数现算 —— <b>不要</b>用 draw() 里缓存的那份：HUD 副屏、
     * 主屏镜像、演示窗口的画布尺寸不同，共用会串（表现为"点不中/拖不动"）。</p>
     */
    public static float[][] widgetRects(float d, TrafficLightState state,
                                        float cx, float cy, float scale,
                                        float maxWidth, float maxHeight) {
        Layout lay = layout(d, state, maxWidth);
        cx = clampCenterX(cx, lay.panelW * scale, d, maxWidth);
        cy = clampCenterY(cy, lay.panelH * scale, d, maxHeight);
        float left = cx - lay.panelW / 2f;
        float top = cy - lay.panelH / 2f;
        float panelCx = left + lay.panelW / 2f;
        float rowY = top;
        float cellW = CELL_W * d;
        float cellsRowW = cellW * SLOT_DIRS.length + CELL_GAP * d * (SLOT_DIRS.length - 1);
        float[][] out = new float[W_COUNT][4];

        float[] t = widgetTransform(W_TIME, maxWidth, maxHeight);
        out[W_TIME][0] = panelCx + t[0];
        out[W_TIME][1] = rowY + lay.timeH / 2f + t[1];
        out[W_TIME][2] = (lay.timeW / 2f + 8 * d) * t[2];
        out[W_TIME][3] = lay.timeH / 2f * t[2];
        rowY += lay.timeH + ROW_GAP * d;

        float[] l = widgetTransform(W_LIGHTS, maxWidth, maxHeight);
        out[W_LIGHTS][0] = panelCx + l[0];
        out[W_LIGHTS][1] = rowY + lay.cellsH / 2f + l[1];
        out[W_LIGHTS][2] = (cellsRowW / 2f + 6 * d) * l[2];
        out[W_LIGHTS][3] = lay.cellsH / 2f * l[2];
        rowY += lay.cellsH + ROW_GAP * d;

        float[] a = widgetTransform(W_INFO1, maxWidth, maxHeight);
        out[W_INFO1][0] = panelCx + a[0];
        out[W_INFO1][1] = rowY + lay.info1H / 2f + a[1];
        out[W_INFO1][2] = (lay.info1W / 2f + 6 * d) * a[2];
        out[W_INFO1][3] = lay.info1H / 2f * a[2];
        rowY += lay.info1H + ROW_GAP * d;

        float[] b = widgetTransform(W_INFO2, maxWidth, maxHeight);
        out[W_INFO2][0] = panelCx + b[0];
        out[W_INFO2][1] = rowY + lay.info2H / 2f + b[1];
        out[W_INFO2][2] = (lay.info2W / 2f + 6 * d) * b[2];
        out[W_INFO2][3] = lay.info2H / 2f * b[2];
        rowY += lay.info2H + ROW_GAP * d;

        float[] c = widgetTransform(W_ETA, maxWidth, maxHeight);
        out[W_ETA][0] = panelCx + c[0];
        out[W_ETA][1] = rowY + lay.etaH / 2f + c[1];
        out[W_ETA][2] = (lay.etaW / 2f + 6 * d) * c[2];
        out[W_ETA][3] = lay.etaH / 2f * c[2];
        rowY += lay.etaH + ROW_GAP * d;

        float[] e = widgetTransform(W_ROAD, maxWidth, maxHeight);
        out[W_ROAD][0] = panelCx + e[0];
        out[W_ROAD][1] = rowY + lay.roadH / 2f + e[1];
        out[W_ROAD][2] = (lay.roadW / 2f + 6 * d) * e[2];
        out[W_ROAD][3] = lay.roadH / 2f * e[2];
        return out;
    }

    /** 某个控件的实际矩形 {cx, cy, halfW, halfH}（布局坐标），draw() 后有效。 */
    public static float[] widgetRect(int id) {
        return LAST_RECTS[id];
    }

    /** 点选命中：返回包含 (px,py) 的控件 id，没有则 -1。（px/py = 布局坐标） */
    public static int widgetAt(float px, float py) {
        for (int i = W_COUNT - 1; i >= 0; i--) {
            float[] r = LAST_RECTS[i];
            if (r[2] <= 0f) {
                continue;
            }
            if (Math.abs(px - r[0]) <= r[2] && Math.abs(py - r[1]) <= r[3]) {
                return i;
            }
        }
        return -1;
    }

    public static String widgetName(int id) {
        return (id >= 0 && id < W_COUNT) ? W_NAMES[id] : "未选择";
    }

    private HudPanelRenderer() {
    }

    public static void setOutlineEnabled(boolean on) {
        outlineEnabled = on;
    }

    public static void setPreviewAll(boolean on) {
        previewAll = on;
    }

    /* ================= 度量 ================= */

    public static final class Metrics {
        public float width;
        public float height;
    }

    private static final class Item {
        int type;
        float w;
        float h;
        int intVal = -1;
        int dir;
        int status;
        String text;
    }

    public static Metrics measure(float d, TrafficLightState state, float maxWidth) {
        Metrics m = new Metrics();
        Layout lay = layout(d, state, maxWidth);
        m.width = lay.panelW;
        m.height = lay.panelH;
        return m;
    }

    /* ================= 绘制 ================= */

    /**
     * 把面板中心横向夹在屏内（HUD 上防止面板被画到屏幕外）。
     *
     * <p><b>注意</b>：面板和画布一样大时必须直接居中 —— 此时"夹取区间"本身是倒置的
     * （下限 &gt; 上限），老写法会永远取到 {@code halfW + 4d}，让整个面板向右偏 4dp
     * （预览/镜像里表现为"没有居中"）。</p>
     */
    public static float clampCenterX(float cx, float panelW, float d, float maxWidth) {
        // 车机 HUD 的可见区域是**偏右的一块**，所以允许面板/控件压到屏幕边缘
        // （之前按"整块面板必须留在屏内"夹取，横向最多只能到 ~85%，用户反馈"挪不动"）。
        float min = 4 * d;
        float max = maxWidth - 4 * d;
        if (max < min) {
            return maxWidth / 2f;
        }
        return Math.max(min, Math.min(cx, max));
    }

    /** 纵向版本，见 {@link #clampCenterX}。 */
    public static float clampCenterY(float cy, float panelH, float d, float maxHeight) {
        // 同上：纵向也允许到边缘
        float min = 4 * d;
        float max = maxHeight - 4 * d;
        if (max < min) {
            return maxHeight / 2f;
        }
        return Math.max(min, Math.min(cy, max));
    }

    public static void draw(Canvas canvas, float d, TrafficLightState state,
                            float centerX, float centerY, float scale,
                            float maxWidth, float maxHeight) {
        if (canvas == null || maxWidth <= 0 || maxHeight <= 0) {
            return;
        }
        Layout lay = layout(d, state, maxWidth);

        float cx = centerX >= 0 ? centerX : maxWidth / 2f;
        float cy = centerY >= 0 ? centerY : maxHeight / 2f;
        cx = clampCenterX(cx, lay.panelW * scale, d, maxWidth);
        cy = clampCenterY(cy, lay.panelH * scale, d, maxHeight);

        canvas.save();
        canvas.scale(scale, scale, cx, cy);

        float left = cx - lay.panelW / 2f;
        float top = cy - lay.panelH / 2f;
        float panelCx = left + lay.panelW / 2f;

        float rowY = top;

        // 三个固定格子的尺寸（画灯组用）
        float cellW = CELL_W * d;
        float cellH = CELL_H * d;
        float cellsRowW = cellW * SLOT_DIRS.length + CELL_GAP * d * (SLOT_DIRS.length - 1);

        // ---- 控件独立控制：每个控件可单独 开/关、平移、缩放 ----
        // LAST_RECTS 记录实际矩形（布局坐标），供预览做"点选控件"命中判定

        // 1) 当前时间
        float[] wt = widgetTransform(W_TIME, maxWidth, maxHeight);
        LAST_RECTS[W_TIME][0] = panelCx + wt[0];
        LAST_RECTS[W_TIME][1] = rowY + lay.timeH / 2f + wt[1];
        LAST_RECTS[W_TIME][2] = (lay.timeW / 2f + 8 * d) * wt[2];
        LAST_RECTS[W_TIME][3] = lay.timeH / 2f * wt[2];
        if (wt[3] > 0f) {
            canvas.save();
            canvas.translate(wt[0], wt[1]);
            if (wt[2] != 1f) {
                canvas.scale(wt[2], wt[2], panelCx, rowY + lay.timeH / 2f);
            }
            drawText(canvas, lay.time, panelCx, rowY + lay.timeH * 0.78f,
                    TIME_SIZE * d, 0xFFFFFFFF, Paint.Align.CENTER);
            canvas.restore();
        }
        rowY += lay.timeH + ROW_GAP * d;

        // 2) 灯组三格（整行居中于面板）
        float lightsCx = panelCx;
        float lightsCy = rowY + lay.cellsH / 2f;
        float[] wl = widgetTransform(W_LIGHTS, maxWidth, maxHeight);
        LAST_RECTS[W_LIGHTS][0] = lightsCx + wl[0];
        LAST_RECTS[W_LIGHTS][1] = lightsCy + wl[1];
        LAST_RECTS[W_LIGHTS][2] = (cellsRowW / 2f + 6 * d) * wl[2];
        LAST_RECTS[W_LIGHTS][3] = lay.cellsH / 2f * wl[2];
        if (wl[3] > 0f) {
            canvas.save();
            canvas.translate(wl[0], wl[1]);
            if (wl[2] != 1f) {
                canvas.scale(wl[2], wl[2], lightsCx, lightsCy);
            }
            for (int i = 0; i < lay.cells.size(); i++) {
                Item it = lay.cells.get(i);
                float x = lay.vertical
                        ? left + (lay.panelW - cellW) / 2f
                        : left + (lay.panelW - cellsRowW) / 2f + i * (cellW + CELL_GAP * d);
                float y = lay.vertical
                        ? rowY + i * (cellH + CELL_GAP * d)
                        : rowY;
                drawLightCell(canvas, it, x, y, d);
            }
            canvas.restore();
        }
        rowY += lay.cellsH + ROW_GAP * d;

        // 3) 红绿灯数量 / 电子眼
        float[] w1 = widgetTransform(W_INFO1, maxWidth, maxHeight);
        LAST_RECTS[W_INFO1][0] = panelCx + w1[0];
        LAST_RECTS[W_INFO1][1] = rowY + lay.info1H / 2f + w1[1];
        LAST_RECTS[W_INFO1][2] = (lay.info1W / 2f + 6 * d) * w1[2];
        LAST_RECTS[W_INFO1][3] = lay.info1H / 2f * w1[2];
        if (w1[3] > 0f) {
            canvas.save();
            canvas.translate(w1[0], w1[1]);
            if (w1[2] != 1f) {
                canvas.scale(w1[2], w1[2], panelCx, rowY + lay.info1H / 2f);
            }
            drawInfoRow(canvas, lay.info1, panelCx, rowY, lay.info1H,
                    INFO1_SIZE * d, INFO1_ICON * d, d, true);
            canvas.restore();
        }
        rowY += lay.info1H + ROW_GAP * d;

        // 4) 终点距离 / 用时
        float[] w2 = widgetTransform(W_INFO2, maxWidth, maxHeight);
        LAST_RECTS[W_INFO2][0] = panelCx + w2[0];
        LAST_RECTS[W_INFO2][1] = rowY + lay.info2H / 2f + w2[1];
        LAST_RECTS[W_INFO2][2] = (lay.info2W / 2f + 6 * d) * w2[2];
        LAST_RECTS[W_INFO2][3] = lay.info2H / 2f * w2[2];
        if (w2[3] > 0f) {
            canvas.save();
            canvas.translate(w2[0], w2[1]);
            if (w2[2] != 1f) {
                canvas.scale(w2[2], w2[2], panelCx, rowY + lay.info2H / 2f);
            }
            drawInfoRow(canvas, lay.info2, panelCx, rowY, lay.info2H,
                    INFO2_SIZE * d, INFO2_ICON * d, d, false);
            canvas.restore();
        }
        rowY += lay.info2H + ROW_GAP * d;

        // 5) 预计到达时间
        float[] w3 = widgetTransform(W_ETA, maxWidth, maxHeight);
        LAST_RECTS[W_ETA][0] = panelCx + w3[0];
        LAST_RECTS[W_ETA][1] = rowY + lay.etaH / 2f + w3[1];
        LAST_RECTS[W_ETA][2] = (lay.etaW / 2f + 6 * d) * w3[2];
        LAST_RECTS[W_ETA][3] = lay.etaH / 2f * w3[2];
        if (w3[3] > 0f) {
            canvas.save();
            canvas.translate(w3[0], w3[1]);
            if (w3[2] != 1f) {
                canvas.scale(w3[2], w3[2], panelCx, rowY + lay.etaH / 2f);
            }
            drawInfoRow(canvas, lay.eta, panelCx, rowY, lay.etaH,
                    FOOTER_SIZE * d, INFO2_ICON * d, d, false);
            canvas.restore();
        }
        rowY += lay.etaH + ROW_GAP * d;

        // 6) 路名（居中）
        float[] w4 = widgetTransform(W_ROAD, maxWidth, maxHeight);
        LAST_RECTS[W_ROAD][0] = panelCx + w4[0];
        LAST_RECTS[W_ROAD][1] = rowY + lay.roadH / 2f + w4[1];
        LAST_RECTS[W_ROAD][2] = (lay.roadW / 2f + 6 * d) * w4[2];
        LAST_RECTS[W_ROAD][3] = lay.roadH / 2f * w4[2];
        if (w4[3] > 0f && lay.road.length() > 0) {
            canvas.save();
            canvas.translate(w4[0], w4[1]);
            if (w4[2] != 1f) {
                canvas.scale(w4[2], w4[2], panelCx, rowY + lay.roadH / 2f);
            }
            drawText(canvas, lay.road, panelCx, rowY + lay.roadH * 0.78f,
                    FOOTER_SIZE * d, 0xFFFFFFFF, Paint.Align.CENTER);
            canvas.restore();
        }
        canvas.restore();
    }

    /** 探针（定位测试图案）。 */
    public static void drawProbe(Canvas canvas, float d, int w, int h, int probeIndex, int probeTick) {
        if (canvas == null || w <= 0 || h <= 0) {
            return;
        }
        float[][] points = {
                {0.2f, 0.2f}, {0.5f, 0.2f}, {0.8f, 0.2f},
                {0.2f, 0.5f}, {0.5f, 0.5f}, {0.8f, 0.5f},
                {0.2f, 0.8f}, {0.5f, 0.8f}, {0.8f, 0.8f},
        };
        if (probeIndex < 0 || probeIndex >= points.length) {
            probeIndex = 0;
        }

        canvas.drawColor(0xCC000000);

        PAINT.setStyle(Paint.Style.STROKE);
        PAINT.setStrokeWidth(Math.max(3f, 5 * d));
        PAINT.setColor(0xFF00FF66);
        canvas.drawRect(5 * d, 5 * d, w - 5 * d, h - 5 * d, PAINT);

        PAINT.setStyle(Paint.Style.FILL);
        PAINT.setTextAlign(Paint.Align.CENTER);
        PAINT.setTypeface(Typeface.DEFAULT_BOLD);
        PAINT.setTextSize(13 * d);
        for (int i = 0; i < points.length; i++) {
            float px = w * points[i][0];
            float py = h * points[i][1];
            boolean active = i == probeIndex;
            PAINT.setColor(active ? 0xFFFFFFFF : 0x66FFFFFF);
            canvas.drawCircle(px, py, (active ? 7 : 4) * d, PAINT);
            PAINT.setColor(active ? 0xFFFFFFFF : 0x88FFFFFF);
            canvas.drawText((i + 1) + " (" + Math.round(px) + "," + Math.round(py) + ")",
                    px, py + 22 * d, PAINT);
        }

        float px = w * points[probeIndex][0];
        float py = h * points[probeIndex][1];
        PAINT.setStyle(Paint.Style.FILL);
        PAINT.setColor(Color.WHITE);
        float arm = Math.min(w, h) * 0.18f;
        float thick = Math.max(4f, 6 * d);
        canvas.drawRect(px - thick, py - arm, px + thick, py + arm, PAINT);
        canvas.drawRect(px - arm, py - thick, px + arm, py + thick, PAINT);

        PAINT.setColor(0xFFFFEB3B);
        PAINT.setTextSize(22 * d);
        canvas.drawText("HUD 探针  秒表=" + probeTick + "s   当前点 " + (probeIndex + 1) + "/9  ("
                + Math.round(px) + "," + Math.round(py) + ")", w / 2f, 30 * d, PAINT);
        PAINT.setTextSize(15 * d);
        PAINT.setColor(0xFF00FF66);
        canvas.drawText("窗口 " + w + "x" + h, w / 2f, h - 16 * d, PAINT);
    }

    /* ================= 内部：布局 ================= */

    private static final class Layout {
        final java.util.List<Item> cells = new java.util.ArrayList<>();
        boolean vertical;
        float cellsH;
        float panelW;
        float panelH;
        /** 顶部「当前时间」行的固定高度 */
        float timeH;
        /** 第一行（下个红绿灯距离 / 红绿灯数）的高度：固定占位 */
        float info1H;
        /** 第二行（终点距离 / 用时）的高度：固定占位 */
        float info2H;
        /** 「预计到达」行的固定高度 */
        float etaH;
        /** 路名行的固定高度 */
        float roadH;
        Seg[] info1 = new Seg[0];
        Seg[] info2 = new Seg[0];
        Seg[] eta = new Seg[0];
        String road = "";
        String time = "";
        float timeW;
        float info1W;
        float info2W;
        float etaW;
        float roadW;
    }

    /** 信息行里的一组「图标 + 数字」。 */
    private static final class Seg {
        static final int ICON_LIGHT = 1;
        static final int ICON_FLAG = 2;
        static final int ICON_CLOCK = 3;
        static final int ICON_ETA = 4;
        static final int ICON_CAMERA = 5;

        final int icon;
        final String text;

        Seg(int icon, String text) {
            this.icon = icon;
            this.text = text;
        }
    }

    private static Layout layout(float d, TrafficLightState state, float maxWidth) {
        Layout lay = new Layout();
        float maxW = Math.max(90 * d, maxWidth);
        float cellW = CELL_W * d;
        float cellH = CELL_H * d;
        float cellGap = CELL_GAP * d;

        /* ---- 三个固定格子（没有数据也占位，保证布局恒定） ---- */
        for (int dir : SLOT_DIRS) {
            Item it = new Item();
            it.type = TYPE_LIGHT;
            it.dir = dir;
            it.w = cellW;
            it.h = cellH;
            DirectionLight l = findLight(state, dir);
            if (l != null) {
                it.intVal = l.countdown;
                it.status = l.status;
            }
            if (previewAll) {
                // 预览模式：所有格子都用示例数据
                it.intVal = 888;
                it.status = TrafficLightState.ST_GREEN;
            }
            lay.cells.add(it);
        }

        boolean vertical = (cellW * SLOT_DIRS.length + cellGap * (SLOT_DIRS.length - 1)) > maxW;
        lay.vertical = vertical;
        float cellsW = vertical
                ? cellW
                : cellW * SLOT_DIRS.length + cellGap * (SLOT_DIRS.length - 1);
        lay.cellsH = vertical
                ? cellH * SLOT_DIRS.length + cellGap * (SLOT_DIRS.length - 1)
                : cellH;

        PAINT.setTypeface(Typeface.DEFAULT_BOLD);

        /* ---- 顶部：当前时间（单独一行、居中、固定占位，字号比信息行大） ---- */
        lay.time = BEIJING_TIME.format(new Date());
        lay.timeH = TIME_SIZE * d * 1.4f;
        PAINT.setTextSize(TIME_SIZE * d);
        lay.timeW = PAINT.measureText(lay.time);
        float timeW = lay.timeW;

        /* ---- 第一行：红绿灯数（固定高度占位） ---- */
        java.util.List<Seg> row1 = new java.util.ArrayList<>();
        if (state != null) {
            // "还有几个红绿灯"：真机日志确认 TRAFFIC_LIGHT_NUM 在导航中恒为 0（无效），
            // routeRemainTrafficLightNum 才是真实数据（例如 6），所以优先用剩余数。
            // 注：原来这里还显示"到下个红绿灯的距离"，但真机 SAPA_DIST 恒为 -1（没有数据），
            // 用户要求去掉这一项。
            int lightCount = state.remainLightNum >= 0 ? state.remainLightNum : state.routeLightNum;
            if (lightCount >= 0) {
                row1.add(new Seg(Seg.ICON_LIGHT, String.valueOf(lightCount)));
            }
            // 电子眼：跟着红绿灯数量一起显示（"前方 xx米 什么抓拍"）
            if (state.cameraDist >= 0 && state.cameraType >= 0) {
                row1.add(new Seg(Seg.ICON_CAMERA, cameraText(state)));
            }
        }
        lay.info1 = row1.toArray(new Seg[0]);
        lay.info1H = INFO1_SIZE * d * 1.4f;
        lay.info1W = segsWidth(d, lay.info1, INFO1_SIZE * d, INFO1_ICON * d);
        float info1W = lay.info1W;

        /* ---- 第二行：终点距离 + 用时（固定高度占位） ---- */
        java.util.List<Seg> row2 = new java.util.ArrayList<>();
        if (state != null && state.routeRemainDist >= 0) {
            row2.add(new Seg(Seg.ICON_FLAG, formatDistance(state.routeRemainDist)));
        }
        if (state != null && state.routeRemainTime >= 0) {
            row2.add(new Seg(Seg.ICON_CLOCK, formatDuration(state.routeRemainTime)));
        }
        lay.info2 = row2.toArray(new Seg[0]);
        lay.info2H = INFO2_SIZE * d * 1.4f;
        lay.info2W = segsWidth(d, lay.info2, INFO2_SIZE * d, INFO2_ICON * d);
        float info2W = lay.info2W;

        /* ---- 预计到达时间（单独一行、固定占位，避免和别的挤一行超宽） ---- */
        java.util.List<Seg> row3 = new java.util.ArrayList<>();
        if (state != null && state.etaText != null && state.etaText.length() > 0) {
            row3.add(new Seg(Seg.ICON_ETA, etaLabel(state.etaText)));
        }
        lay.eta = row3.toArray(new Seg[0]);
        lay.etaH = FOOTER_SIZE * d * 1.4f;
        lay.etaW = segsWidth(d, lay.eta, FOOTER_SIZE * d, INFO2_ICON * d);
        float etaW = lay.etaW;

        /* ---- 路名（单独一行、居中、固定占位） ---- */
        lay.road = state != null && state.roadName != null ? state.roadName : "";
        lay.roadH = FOOTER_SIZE * d * 1.4f;
        PAINT.setTextSize(FOOTER_SIZE * d);
        lay.roadW = lay.road.length() > 0 ? PAINT.measureText(lay.road) : 0f;
        float roadW = lay.roadW;

        /* ---- 面板尺寸：宽度取各行最大值（每行都保证落在 maxW 内） ---- */
        lay.panelW = Math.min(maxW, Math.max(cellsW, Math.max(
                Math.max(info1W, info2W), Math.max(etaW, Math.max(timeW, roadW)))));
        lay.panelH = lay.timeH + ROW_GAP * d + lay.cellsH
                + ROW_GAP * d + lay.info1H
                + ROW_GAP * d + lay.info2H
                + ROW_GAP * d + lay.etaH
                + ROW_GAP * d + lay.roadH;
        return lay;
    }

    /* ================= 信息行：图标 + 数字 ================= */

    /** 电子眼文案：例如 "300m测速60" / "1.2km电子眼"。 */
    private static String cameraText(TrafficLightState s) {
        String dist = formatDistance(s.cameraDist);
        String name = cameraTypeName(s.cameraType);
        if (s.cameraSpeed > 0 && (s.cameraType == 1 || s.cameraType == 6)) {
            return dist + name + s.cameraSpeed;
        }
        return dist + name;
    }

    /** 电子眼类型文案（CAMERA_TYPE 取值以真机日志为准，这里先给常见映射 + 兜底）。 */
    private static String cameraTypeName(int type) {
        switch (type) {
            case 1: return "测速";
            case 2: return "闯红灯";
            case 3: return "违停";
            case 4: return "公交车道";
            case 5: return "应急车道";
            case 6: return "区间测速";
            case 7: return "逆行";
            case 8: return "压线";
            default: return "电子眼";
        }
    }

    /** 距离文本：<1000 米用 m，否则用 km（参考图：80m / 2.3km）。 */
    private static String formatDistance(int meters) {
        if (meters < 1000) {
            return meters + "m";
        }
        return String.format(Locale.US, "%.1fkm", meters / 1000f);
    }

    /** 用时文本：<60 分钟用 min，超过用 h/min（参考图：6min）。 */
    private static String formatDuration(int seconds) {
        if (seconds < 60) {
            return seconds + "s";
        }
        int min = seconds / 60;
        if (min < 60) {
            return min + "min";
        }
        int h = min / 60;
        int m = min % 60;
        return m == 0 ? h + "h" : h + "h" + m + "min";
    }

    /**
     * 到达时间文本：统一补成「预计 HH:mm 到达」的完整文案。
     *
     * <p>高德的 ETA_TEXT 有时是完整文案（"预计09:02到达"），有时只有时间（"17:30"），
     * 这里按已有成分补齐，不重复、不丢字。</p>
     */
    private static String etaLabel(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.length() == 0) {
            return t;
        }
        boolean hasEta = t.contains("预计");
        boolean hasDao = t.contains("到");
        if (hasEta && hasDao) {
            return t;                     // "预计09:02到达" → 原样
        }
        if (hasDao) {
            return "预计" + t;             // "17:30到" → "预计17:30到"
        }
        if (hasEta) {
            return t + "到达";             // "预计17:30" → "预计17:30到达"
        }
        return "预计" + t + "到达";        // "17:30" → "预计17:30到达"
    }

    /** 一行「图标+数字」的总宽度（必须与 {@link #drawInfoRow} 的排布规则一致）。 */
    private static float segsWidth(float d, Seg[] segs, float size, float iconSize) {
        if (segs.length == 0) {
            return 0f;
        }
        PAINT.setTypeface(Typeface.DEFAULT_BOLD);
        PAINT.setTextSize(size);
        float w = 0f;
        for (int i = 0; i < segs.length; i++) {
            if (i > 0) {
                w += SEG_GAP * d;
            }
            w += iconSize + ICON_TEXT_GAP * d + PAINT.measureText(segs[i].text);
        }
        return w;
    }

    /**
     * 画一行「图标 + 数字」，整行以 centerX 居中。
     *
     * @param dotSep 组与组之间是否画一个分隔小圆点（参考图第一行是 "80m · 7"）
     */
    private static void drawInfoRow(Canvas canvas, Seg[] segs, float centerX, float rowTop,
                                    float rowH, float size, float iconSize, float d,
                                    boolean dotSep) {
        if (segs.length == 0) {
            return;
        }
        float x = centerX - segsWidth(d, segs, size, iconSize) / 2f;
        float baseline = rowTop + rowH * 0.74f;
        float cy = rowTop + rowH * 0.5f;
        for (int i = 0; i < segs.length; i++) {
            if (i > 0) {
                if (dotSep) {
                    PAINT.setStyle(Paint.Style.FILL);
                    PAINT.setColor(0x99FFFFFF);
                    canvas.drawCircle(x + SEG_GAP * d / 2f, cy, 2f * d, PAINT);
                }
                x += SEG_GAP * d;
            }
            drawIcon(canvas, segs[i].icon, x + iconSize / 2f, cy, iconSize);
            x += iconSize + ICON_TEXT_GAP * d;
            drawText(canvas, segs[i].text, x, baseline, size, 0xFFFFFFFF, Paint.Align.LEFT);
            x += PAINT.measureText(segs[i].text);
        }
    }

    /** 画一个小图标（全矢量、白色 —— HUD 是投影发光，白色最清楚）：红绿灯 / 终点旗 / 时钟 / 到达。 */
    private static void drawIcon(Canvas canvas, int type, float cx, float cy, float size) {
        float stroke = Math.max(1.6f, size * 0.095f);
        PAINT.setColor(0xFFFFFFFF);
        PAINT.setStrokeCap(Paint.Cap.ROUND);
        switch (type) {
            case Seg.ICON_LIGHT: {
                float w = size * 0.50f;
                float h = size * 0.86f;
                PAINT.setStyle(Paint.Style.STROKE);
                PAINT.setStrokeWidth(stroke);
                float l = cx - w / 2f;
                float t = cy - h / 2f;
                canvas.drawRoundRect(l, t, l + w, t + h, w * 0.42f, w * 0.42f, PAINT);
                PAINT.setStyle(Paint.Style.FILL);
                float dotR = Math.max(1f, w * 0.16f);
                float step = h * 0.28f;
                canvas.drawCircle(cx, cy - step, dotR, PAINT);
                canvas.drawCircle(cx, cy, dotR, PAINT);
                canvas.drawCircle(cx, cy + step, dotR, PAINT);
                break;
            }
            case Seg.ICON_FLAG: {
                float poleX = cx - size * 0.22f;
                PAINT.setStyle(Paint.Style.STROKE);
                PAINT.setStrokeWidth(stroke);
                canvas.drawLine(poleX, cy - size * 0.42f, poleX, cy + size * 0.42f, PAINT);
                PAINT.setStyle(Paint.Style.FILL);
                Path flag = new Path();
                flag.moveTo(poleX, cy - size * 0.42f);
                flag.lineTo(poleX + size * 0.44f, cy - size * 0.24f);
                flag.lineTo(poleX, cy - size * 0.06f);
                flag.close();
                canvas.drawPath(flag, PAINT);
                break;
            }
            case Seg.ICON_CLOCK: {
                float r = size * 0.42f;
                PAINT.setStyle(Paint.Style.STROKE);
                PAINT.setStrokeWidth(stroke);
                canvas.drawCircle(cx, cy, r, PAINT);
                canvas.drawLine(cx, cy, cx, cy - r * 0.55f, PAINT);
                canvas.drawLine(cx, cy, cx + r * 0.45f, cy, PAINT);
                break;
            }
            case Seg.ICON_CAMERA: {
                // 摄像头：机身 + 镜头 + 顶部闪光灯（电子眼）
                float w = size * 0.62f;
                float h = size * 0.46f;
                PAINT.setStyle(Paint.Style.STROKE);
                PAINT.setStrokeWidth(stroke);
                float l = cx - w / 2f;
                float t = cy - h / 2f + size * 0.12f;
                canvas.drawRoundRect(l, t, l + w, t + h, w * 0.18f, w * 0.18f, PAINT);
                PAINT.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, t + h / 2f, h * 0.34f, PAINT);
                canvas.drawRoundRect(cx - w * 0.16f, t - size * 0.18f,
                        cx + w * 0.16f, t - size * 0.04f, w * 0.12f, w * 0.12f, PAINT);
                break;
            }
            case Seg.ICON_ETA:
            default: {
                // 外环 + 中心点 = "到达"
                PAINT.setStyle(Paint.Style.STROKE);
                PAINT.setStrokeWidth(stroke);
                canvas.drawCircle(cx, cy, size * 0.40f, PAINT);
                PAINT.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, cy, size * 0.13f, PAINT);
                break;
            }
        }
    }

    private static DirectionLight findLight(TrafficLightState state, int dir) {
        if (state == null) {
            return null;
        }
        for (DirectionLight l : state.freshLights()) {
            if (l.dir == dir) {
                return l;
            }
        }
        return null;
    }

    /* ================= 内部：画一个灯格 ================= */

    private static void drawLightCell(Canvas canvas, Item it, float x, float y, float d) {
        // ⚠️ countdown = 0 是合法值（黄灯最后一秒 / 变灯瞬间），只能用 < 0 表示"没有数据"。
        // 老代码写成 intVal <= 0，会把黄灯尾段和变灯瞬间整格吞掉（用户反馈"不显示黄灯"的根因之一）。
        if (it.intVal < 0 || it.status <= 0) {
            // 该方向没有灯：留空但占位（布局不受影响）
            return;
        }
        float circleR = CIRCLE_D * d / 2f;
        float ccx = x + circleR;
        float ccy = y + it.h / 2f;

        int color = TrafficLightState.statusColor(it.status);
        // 黑色外圈，保证在亮/暗背景下都能分辨边界
        PAINT.setStyle(Paint.Style.FILL);
        PAINT.setColor(0xFF000000);
        canvas.drawCircle(ccx, ccy, circleR + 2 * d, PAINT);
        PAINT.setColor(color);
        canvas.drawCircle(ccx, ccy, circleR, PAINT);
        drawDirectionArrow(canvas, ccx, ccy, circleR * 1.5f, it.dir, 0xFFFFFFFF);

        // 即将变灯（真黄灯 status=3，或倒计时只剩 CAUTION_SECONDS 秒）时，
        // 跟高德 APP 一样显示"注意"两个字；其它情况显示倒计时。
        // 真机日志确认高德不会广播 status=3，所以黄灯语义主要靠"倒计时快结束"推导。
        boolean caution = it.status == TrafficLightState.ST_YELLOW || it.intVal <= CAUTION_SECONDS;
        String label = caution ? "注意" : String.valueOf(it.intVal);
        float size = (caution ? COUNT_SIZE * 0.78f : COUNT_SIZE) * d;
        drawText(canvas, label, x + circleR * 2f + 5 * d, y + it.h * 0.78f,
                size, 0xFFFFFFFF, Paint.Align.LEFT);
    }

    /* ================= 7 段 LED 时间 ================= */

    /** LED 时间的总宽度（layout 算面板宽度要用，必须和 drawLedTime 的排布一致）。 */
    private static float ledTimeWidth(String text, float digitH) {
        if (text == null || text.length() == 0) {
            return 0f;
        }
        float digitW = digitH * DIGIT_W_RATIO;
        float colonW = digitH * COLON_W_RATIO;
        float gap = digitH * LED_GAP_RATIO;
        float total = 0f;
        for (int i = 0; i < text.length(); i++) {
            total += text.charAt(i) == ':' ? colonW : digitW;
            if (i < text.length() - 1) {
                total += gap;
            }
        }
        return total;
    }

    /**
     * 用 7 段数码管样式画时间（LED 风格，纯 Canvas 自绘，不依赖字体文件）。
     *
     * <p>参考 hong.apk 的时间样式：细线段 + 端点留缝、数字中空。只支持 0-9 和 ':'
     * （时间字符串只有这两种字符），其它字符按空白跳过。</p>
     *
     * @param topY   数字顶部 y（不是基线）
     * @param digitH 数字高度
     */
    private static void drawLedTime(Canvas canvas, String text, float centerX, float topY,
                                    float digitH, int color) {
        if (text == null || text.length() == 0) {
            return;
        }
        final float digitW = digitH * DIGIT_W_RATIO;
        final float colonW = digitH * COLON_W_RATIO;
        final float gap = digitH * LED_GAP_RATIO;
        final float t = Math.max(1.4f, digitH * SEG_THICK_RATIO);
        final float inset = t * 0.72f;   // 段与段之间的缝，端点不粘连

        float x = centerX - ledTimeWidth(text, digitH) / 2f;

        PAINT.setColor(color);
        PAINT.setStrokeWidth(t);
        PAINT.setStrokeCap(Paint.Cap.BUTT);
        PAINT.setStyle(Paint.Style.STROKE);

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ':') {
                PAINT.setStyle(Paint.Style.FILL);
                float r = Math.max(1f, digitH * COLON_DOT_RATIO);
                canvas.drawCircle(x + colonW / 2f, topY + digitH * 0.30f, r, PAINT);
                canvas.drawCircle(x + colonW / 2f, topY + digitH * 0.70f, r, PAINT);
                PAINT.setStyle(Paint.Style.STROKE);
                x += colonW + gap;
                continue;
            }
            int seg = (c >= '0' && c <= '9') ? LED_SEGMENTS[c - '0'] : 0;
            float y0 = topY;
            float y1 = topY + digitH / 2f;
            float y2 = topY + digitH;
            float xl = x;
            float xr = x + digitW;
            if ((seg & 1) != 0) {
                canvas.drawLine(xl + inset, y0, xr - inset, y0, PAINT);          // a 上
            }
            if ((seg & 2) != 0) {
                canvas.drawLine(xr, y0 + inset, xr, y1 - inset, PAINT);           // b 右上
            }
            if ((seg & 4) != 0) {
                canvas.drawLine(xr, y1 + inset, xr, y2 - inset, PAINT);           // c 右下
            }
            if ((seg & 8) != 0) {
                canvas.drawLine(xl + inset, y2, xr - inset, y2, PAINT);           // d 下
            }
            if ((seg & 16) != 0) {
                canvas.drawLine(xl, y1 + inset, xl, y2 - inset, PAINT);           // e 左下
            }
            if ((seg & 32) != 0) {
                canvas.drawLine(xl, y0 + inset, xl, y1 - inset, PAINT);           // f 左上
            }
            if ((seg & 64) != 0) {
                canvas.drawLine(xl + inset, y1, xr - inset, y1, PAINT);           // g 中
            }
            x += digitW + gap;
        }
    }

    /** 白字（默认只填充；打开描边开关时额外加一圈黑边）。 */
    private static void drawText(Canvas canvas, String text, float x, float y, float size,
                                 int fillColor, Paint.Align align) {
        if (text == null || text.length() == 0) {
            return;
        }
        PAINT.setTypeface(Typeface.DEFAULT_BOLD);
        PAINT.setTextAlign(align);
        PAINT.setTextSize(size);
        PAINT.clearShadowLayer();
        if (outlineEnabled) {
            PAINT.setStyle(Paint.Style.STROKE);
            PAINT.setStrokeWidth(Math.max(2f, size * 0.14f));
            PAINT.setColor(0xFF000000);
            canvas.drawText(text, x, y, PAINT);
        }
        PAINT.setStyle(Paint.Style.FILL);
        PAINT.setColor(fillColor);
        canvas.drawText(text, x, y, PAINT);
    }

    /** 方向箭头：直行 ↑ / 左转 ← / 右转 → / 掉头 U。 */
    private static void drawDirectionArrow(Canvas canvas, float cx, float cy, float size, int dir, int color) {
        if (dir == TrafficLightState.DIR_UTURN) {
            PAINT.setStyle(Paint.Style.STROKE);
            PAINT.setStrokeWidth(Math.max(2f, 0.15f * size));
            PAINT.setStrokeCap(Paint.Cap.ROUND);
            PAINT.setColor(color);
            Path p = new Path();
            p.moveTo(-0.24f * size, 0.34f * size);
            p.lineTo(-0.24f * size, -0.08f * size);
            p.quadTo(-0.24f * size, -0.30f * size, 0f, -0.30f * size);
            p.quadTo(0.24f * size, -0.30f * size, 0.24f * size, -0.08f * size);
            p.lineTo(0.24f * size, 0.16f * size);
            canvas.save();
            canvas.translate(cx, cy);
            canvas.drawPath(p, PAINT);
            PAINT.setStyle(Paint.Style.FILL);
            Path head = new Path();
            head.moveTo(0.24f * size, 0.38f * size);
            head.lineTo(0.06f * size, 0.16f * size);
            head.lineTo(0.42f * size, 0.16f * size);
            head.close();
            canvas.drawPath(head, PAINT);
            canvas.restore();
            return;
        }

        float rot = 0f;
        if (dir == TrafficLightState.DIR_LEFT) {
            rot = -90f;
        } else if (dir == TrafficLightState.DIR_RIGHT) {
            rot = 90f;
        }
        PAINT.setStyle(Paint.Style.FILL);
        PAINT.setColor(color);
        canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate(rot);
        float half = 0.10f * size;
        canvas.drawRect(-half, -0.06f * size, half, 0.38f * size, PAINT);
        Path head = new Path();
        head.moveTo(0f, -0.42f * size);
        head.lineTo(-0.28f * size, -0.04f * size);
        head.lineTo(0.28f * size, -0.04f * size);
        head.close();
        canvas.drawPath(head, PAINT);
        canvas.restore();
    }
}
