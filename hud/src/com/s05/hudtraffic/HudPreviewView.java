package com.s05.hudtraffic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/**
 * 主界面里的「HUD 预览」—— 按副屏（默认 800x480）等比缩放显示，<b>所见即所得</b>。
 *
 * <p>和 HUD 副屏共用同一套坐标逻辑（{@link HudPanelRenderer} 的 measure/draw/clamp），
 * 所以预览里面板出现在哪个位置，副屏上就是哪个位置（包括贴边时的夹取效果）。</p>
 *
 * <p><b>拖动即可调位置</b>：手指按住面板拖动，实时更新预览；松手后由
 * {@link Listener#onPreviewPanelPos(int, int, boolean)} 写配置并刷新 HUD
 * （拖动过程中不重建 HUD 窗口，否则每秒几十次 removeView/addView 会卡）。</p>
 *
 * <p>预览永远用示例数据把<b>所有控件</b>画出来（左转/直行/右转三个灯格、
 * 下个红绿灯距离、路名、时间），这样没有真实数据时也能把大小和位置调好。</p>
 */
public class HudPreviewView extends View {

    public interface Listener {
        /**
         * 拖动调整了面板中心（百分比）。
         *
         * @param finished true = 手指松开，此时应保存配置并刷新 HUD
         */
        void onPreviewPanelPos(int xPercent, int yPercent, boolean finished);

        /** 点选了某个控件（-1 = 取消选择） */
        void onWidgetSelected(int id);

        /** 拖动了某个控件（位置已写入配置） */
        void onWidgetMoved(int id, int xPercent, int yPercent, boolean finished);
    }

    /** 找不到真实副屏时的参考尺寸 */
    private static final int FALLBACK_W = 800;
    private static final int FALLBACK_H = 480;

    /** 车机 HUD 的黄框可见区域（用户实拍确认），画出来当拖动参考（仅 800x480 副屏） */
    private static final float YELLOW_X1 = 545f;
    private static final float YELLOW_Y1 = 119f;
    private static final float YELLOW_X2 = 800f;
    private static final float YELLOW_Y2 = 415f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int virtualW = FALLBACK_W;
    private int virtualH = FALLBACK_H;
    /** 副屏的 density（必须和副屏一致，否则预览里控件大小对不上） */
    private float hudDensity = 1f;

    private float scale = 1f;
    private int xPercent = Prefs.DEFAULT_X_PERCENT;
    private int yPercent = Prefs.DEFAULT_Y_PERCENT;

    private final TrafficLightState demoState;
    /** 真实/模拟数据（示例模式下不用） */
    private TrafficLightState liveState;
    /** true = 用示例数据把全部控件画出来（便于调位置/大小）；false = 跟 HUD 一样显示真实数据 */
    private boolean demoMode = true;
    private Listener listener;

    /** 当前选中的控件（-1 = 没选中，滑块控制整个面板） */
    private int selectedWidget = -1;
    /** 正在拖动的控件（-1 = 拖的是整个面板） */
    private int dragWidget = -1;
    private float downLx;
    private float downLy;
    private float downWCx;
    private float downWCy;
    /** 按下时面板中心的布局坐标（控件的存储位置是"相对面板中心的偏移"） */
    private float downBaseCx;
    private float downBaseCy;
    /** 按下时控件的存储位置（0.1% 单位） */
    private int downX10;
    private int downY10;
    /** true = 拖动/缩放整块面板；false = 单独选中控件 */
    private boolean dragWhole = true;

    public void setDragWhole(boolean v) {
        dragWhole = v;
        if (v) {
            selectedWidget = -1;
        }
        invalidate();
    }

    public boolean isDragWhole() {
        return dragWhole;
    }

    public int getSelectedWidget() {
        return selectedWidget;
    }

    public void setSelectedWidget(int id) {
        selectedWidget = id;
        invalidate();
    }

    /** 触点（View 坐标）-> 面板布局坐标（逆掉预览缩放 + 面板缩放；面板中心用夹取后的） */
    private float[] toLayout(float vx, float vy) {
        float fs = fitScale();
        float offX = (getWidth() - virtualW * fs) / 2f;
        float offY = (getHeight() - virtualH * fs) / 2f;
        float[] pc = shownCenter();
        float lx = (vx - offX) / fs;
        float ly = (vy - offY) / fs;
        return new float[]{pc[0] + (lx - pc[0]) / scale, pc[1] + (ly - pc[1]) / scale};
    }

    /** 布局坐标 -> View 坐标（画选中框用） */
    private float[] toView(float lx, float ly) {
        float fs = fitScale();
        float offX = (getWidth() - virtualW * fs) / 2f;
        float offY = (getHeight() - virtualH * fs) / 2f;
        float[] pc = shownCenter();
        float sx = pc[0] + (lx - pc[0]) * scale;
        float sy = pc[1] + (ly - pc[1]) * scale;
        return new float[]{offX + sx * fs, offY + sy * fs};
    }

    private static int widgetHit(float[][] r, float px, float py) {
        for (int i = r.length - 1; i >= 0; i--) {
            if (r[i][2] <= 0f) {
                continue;
            }
            if (Math.abs(px - r[i][0]) <= r[i][2] && Math.abs(py - r[i][1]) <= r[i][3]) {
                return i;
            }
        }
        return -1;
    }

    /** 当前画布参数下每个控件的矩形（布局坐标） */
    private float[][] widgetRects() {
        float[] pc = shownCenter();
        return HudPanelRenderer.widgetRects(hudDensity, currentState(),
                pc[0], pc[1], scale, virtualW, virtualH);
    }

    // ---- 拖动状态 ----
    /** 预览嵌在滚动容器里时，按住这么久才进入拖动（防误拖，直接滑动就是滚页面） */
    private static final long DRAG_HOLD_MS = 220L;

    private boolean pressed;
    private boolean dragging;
    private float downX;
    private float downY;
    /** 最近一次触点（长按激活时用它当拖动基准） */
    private float lastX;
    private float lastY;
    /** 按下时面板的"显示中心"（HUD 坐标，已夹取） */
    private float downShownCx;
    private float downShownCy;

    private final Runnable dragActivate = new Runnable() {
        @Override
        public void run() {
            if (!pressed) {
                return;
            }
            dragging = true;
            // 长按激活时以当前手指位置为基准（长按期间的微小移动不计入）
            downX = lastX;
            downY = lastY;
            float[] dl = toLayout(lastX, lastY);
            downLx = dl[0];
            downLy = dl[1];
            // 基准也要重新取：否则长按那 220ms 里手指移动的那一段会被吃掉，
            // 表现就是"跟手但总差一点点"
            float[] pcNow = shownCenter();
            downBaseCx = pcNow[0];
            downBaseCy = pcNow[1];
            if (dragWidget >= 0) {
                float[][] rr = widgetRects();
                if (rr != null && dragWidget < rr.length) {
                    downWCx = rr[dragWidget][0];
                    downWCy = rr[dragWidget][1];
                }
                android.content.Context c = getContext();
                downX10 = Prefs.getWidgetX10(c, dragWidget);
                downY10 = Prefs.getWidgetY10(c, dragWidget);
            }
            float[] shown = shownCenter();
            downShownCx = shown[0];
            downShownCy = shown[1];
            android.view.ViewParent parent = getParent();
            if (parent != null) {
                parent.requestDisallowInterceptTouchEvent(true);
            }
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        }
    };

    public HudPreviewView(Context context) {
        super(context);
        demoState = buildDemoState();
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /**
     * 预览数据模式。
     *
     * <p>true（默认）= 用示例数据把全部控件都显示出来，方便调位置和大小；
     * false = 跟 HUD 完全一样显示真实数据（打开「模拟数据源」时自动切到这个模式）。</p>
     */
    public void setDemoMode(boolean demo) {
        if (demoMode == demo) {
            return;
        }
        demoMode = demo;
        invalidate();
    }

    public boolean isDemoMode() {
        return demoMode;
    }

    /** 推送真实/模拟数据（主界面每收到一条广播就调一次）。 */
    public void setState(TrafficLightState state) {
        liveState = state;
        if (!demoMode) {
            invalidate();
        }
    }

    /** 当前该画的数据：示例 或 真实。 */
    private TrafficLightState currentState() {
        return demoMode ? demoState : liveState;
    }

    /** 同步副屏几何信息（主界面在"重信息"节流里调用；不做 IPC）。 */
    public void setHudGeometry(int width, int height, float density) {
        if (width <= 0 || height <= 0) {
            width = FALLBACK_W;
            height = FALLBACK_H;
        }
        float dens = density > 0f ? density : 1f;
        if (width == virtualW && height == virtualH && dens == hudDensity) {
            return;
        }
        virtualW = width;
        virtualH = height;
        hudDensity = dens;
        requestLayout();
        invalidate();
    }

    public void setScale(float s) {
        scale = s <= 0f ? 1f : s;
        invalidate();
    }

    public void setPosition(int x, int y) {
        xPercent = clampPercent(x);
        yPercent = clampPercent(y);
        invalidate();
    }

    public int getXPercent() {
        return xPercent;
    }

    public int getYPercent() {
        return yPercent;
    }

    /* ================= 测量 ================= */

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        if (w <= 0) {
            w = Math.round(320 * getResources().getDisplayMetrics().density);
        }
        int h = (int) Math.ceil(w * (virtualH / (float) virtualW));
        setMeasuredDimension(w, resolveSize(h, heightSpec));
    }

    /* ================= 绘制 ================= */

    /**
     * 每秒重绘一次：画面顶部显示的是"当前时间"，必须持续刷新。
     * 之前只在数据变化（setState / 拖动 / 开关）时才 invalidate，
     * 于是"关掉模拟数据源 / 还没开始导航"这种没有数据的情况下，画面停在最后一帧 —— 时间就不走了。
     */
    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            invalidate();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(ticker);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        refreshDemoTimes();

        float s = fitScale();
        if (s <= 0f) {
            return;
        }
        float offX = (getWidth() - virtualW * s) / 2f;
        float offY = (getHeight() - virtualH * s) / 2f;
        float screenR = offX + virtualW * s;
        float screenB = offY + virtualH * s;

        // 虚拟副屏底 + 边框
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF0A0E14);
        canvas.drawRect(offX, offY, screenR, screenB, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f);
        paint.setColor(0xFF2A3340);
        canvas.drawRect(offX, offY, screenR, screenB, paint);

        // 黄框：HUD 上真正可见的区域（只在 800x480 时坐标才有意义）
        if (virtualW == FALLBACK_W && virtualH == FALLBACK_H) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f);
            paint.setColor(0x66FFE082);
            canvas.drawRect(offX + YELLOW_X1 * s, offY + YELLOW_Y1 * s,
                    offX + YELLOW_X2 * s, offY + YELLOW_Y2 * s, paint);
        }

        // 面板：与 HUD 完全同一套坐标 + 同一个 clamp
        float[] pc = shownCenter();
        canvas.save();
        canvas.translate(offX, offY);
        canvas.scale(s, s);
        HudPanelRenderer.draw(canvas, hudDensity, currentState(),
                pc[0], pc[1], scale, virtualW, virtualH);
        canvas.restore();

        // 每个控件都画细框（方便看清各自的范围）：默认蓝框，选中后变红框。
        // 只在预览里画 —— HUD 上不画（HudSurfaceView 不调用这段）。
        float[][] rects = HudPanelRenderer.widgetRects(hudDensity, currentState(),
                pc[0], pc[1], scale, virtualW, virtualH);
        float boxD = getResources().getDisplayMetrics().density;
        float pad = 3f * boxD;
        for (int i = 0; i < rects.length; i++) {
            float[] r = rects[i];
            if (r[2] <= 0f) {
                continue;
            }
            float[] v1 = toView(r[0] - r[2] - pad, r[1] - r[3] - pad);
            float[] v2 = toView(r[0] + r[2] + pad, r[1] + r[3] + pad);
            boolean sel = !dragWhole && i == selectedWidget;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(sel ? 2.5f * boxD : 1f * boxD);
            paint.setColor(sel ? 0xFFFF3B30 : 0x552196F3);
            canvas.drawRect(v1[0], v1[1], v2[0], v2[1], paint);
        }

        // 操作提示
        float vd = getResources().getDisplayMetrics().density;
        float sp = getResources().getDisplayMetrics().scaledDensity;
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTextSize(10f * sp);
        paint.setColor(0x99FFFFFF);
        String hint = scrollableAncestor() != null
                ? "长按拖动可调位置（松手同步到 HUD）· 黄框=HUD 可见区"
                : "拖动即可调位置（松手同步到 HUD）· 黄框=HUD 可见区";
        canvas.drawText(hint, 6 * vd, getHeight() - 6 * vd, paint);
    }

    /* ================= 触摸拖动 ================= */

    /** 命中判定时面板四周多给一点余量（手指稍微按偏一点也算按在面板上） */
    private static final float PANEL_HIT_PADDING = 16f;

    /** 找到能垂直滚动的祖先（竖屏预览在 ScrollView 里；横屏右侧预览没有滚动容器 → null）。 */
    private android.view.ViewParent scrollableAncestor() {
        android.view.ViewParent p = getParent();
        while (p != null) {
            if (p instanceof android.widget.ScrollView) {
                return p;
            }
            if (p instanceof View) {
                p = ((View) p).getParent();
            } else {
                return null;
            }
        }
        return null;
    }

    /** 触点（View 坐标）是否落在面板矩形内（含缩放与一点余量）。 */
    private boolean isTouchOnPanel(float vx, float vy) {
        float s = fitScale();
        if (s <= 0f) {
            return false;
        }
        float offX = (getWidth() - virtualW * s) / 2f;
        float offY = (getHeight() - virtualH * s) / 2f;
        float[] shown = shownCenter();
        HudPanelRenderer.Metrics m = HudPanelRenderer.measure(hudDensity, currentState(), virtualW);
        float pad = PANEL_HIT_PADDING * getResources().getDisplayMetrics().density;
        float halfW = m.width * scale * s / 2f + pad;
        float halfH = m.height * scale * s / 2f + pad;
        float cx = offX + shown[0] * s;
        float cy = offY + shown[1] * s;
        return Math.abs(vx - cx) <= halfW && Math.abs(vy - cy) <= halfH;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                android.view.ViewParent scrollParent = scrollableAncestor();
                // 先做"点选控件"命中：点到某个控件就选中它，之后拖动的是这个控件
                float[] l0 = toLayout(event.getX(), event.getY());
                float[] pc = shownCenter();
                int hit = -1;
                float[][] rects = null;
                if (!dragWhole) {
                    // 单独控件模式：先做点选命中
                    rects = widgetRects();
                    hit = widgetHit(rects, l0[0], l0[1]);
                }
                selectedWidget = hit;
                dragWidget = hit;
                if (listener != null) {
                    listener.onWidgetSelected(hit);
                }
                downLx = l0[0];
                downLy = l0[1];
                downBaseCx = pc[0];
                downBaseCy = pc[1];
                if (hit >= 0) {
                    downWCx = rects[hit][0];
                    downWCy = rects[hit][1];
                    android.content.Context c = getContext();
                    downX10 = Prefs.getWidgetX10(c, hit);
                    downY10 = Prefs.getWidgetY10(c, hit);
                }
                // 预览嵌在 ScrollView 里时（竖屏）：只有按在面板/控件上才算"想拖"，
                // 按在空白处把事件让给父容器（否则页面滑不动）
                if (scrollParent != null && hit < 0
                        && (dragWhole ? !isTouchOnPanel(event.getX(), event.getY()) : true)) {
                    return false;
                }
                pressed = true;
                dragging = false;
                downX = event.getX();
                downY = event.getY();
                lastX = downX;
                lastY = downY;
                float[] shown = shownCenter();
                downShownCx = shown[0];
                downShownCy = shown[1];
                if (scrollParent == null) {
                    // 横屏：没有滚动冲突 ——
                    // 整体拖动开：按在面板/控件上就能拖整块面板；
                    // 整体拖动关：**只有点中某个控件才拖它**，按空白处一律不动任何东西。
                    dragging = dragWhole
                            ? (hit >= 0 || isTouchOnPanel(event.getX(), event.getY()))
                            : (hit >= 0);
                } else {
                    // 竖屏：按住面板 220ms 才进入拖动；期间手指一滑就当成滚动页面
                    removeCallbacks(dragActivate);
                    postDelayed(dragActivate, DRAG_HOLD_MS);
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                lastX = event.getX();
                lastY = event.getY();
                if (dragging && dragWidget >= 0) {
                    // 拖动单个控件：位置实时写入配置并刷新 HUD
                    // 直接用"手指在屏幕上移动的像素"换算，不再做坐标逆变换：
                    //   控件中心在屏幕上的位移 = fs * 面板缩放 * dx
                    //   => dx = 屏幕位移 / (fs * 面板缩放)，再换算成 0.1% 单位
                    float fs = fitScale();
                    float gain = fs * scale;
                    int nx10 = downX10;
                    int ny10 = downY10;
                    if (gain > 0f) {
                        nx10 = downX10 + Math.round((event.getX() - downX) / gain * 1000f / virtualW);
                        ny10 = downY10 + Math.round((event.getY() - downY) / gain * 1000f / virtualH);
                    }
                    nx10 = Math.max(0, Math.min(nx10, 1000));
                    ny10 = Math.max(0, Math.min(ny10, 1000));
                    android.content.Context c = getContext();
                    Prefs.setWidgetX10(c, dragWidget, nx10);
                    Prefs.setWidgetY10(c, dragWidget, ny10);
                    if (listener != null) {
                        listener.onWidgetMoved(dragWidget, nx10 / 10, ny10 / 10, false);
                    }
                    HudTrafficManager.get().render(TrafficLightBus.getLatest());
                    invalidate();
                    return true;
                }
                if (!dragging) {
                    if (!pressed) {
                        return false;
                    }
                    // 还没进入拖动：移动超过 touch slop 说明用户是想滑页面 → 撤销，让父容器接管
                    float slop = android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
                    if (Math.abs(lastX - downX) > slop || Math.abs(lastY - downY) > slop) {
                        pressed = false;
                        removeCallbacks(dragActivate);
                        return false;
                    }
                    return true;
                }
                float s = fitScale();
                if (s <= 0f) {
                    return true;
                }
                if (!dragWhole) {
                    return true;   // 单独控件模式：不拖整块面板
                }
                float dx = (lastX - downX) / s;
                float dy = (lastY - downY) / s;
                int nx = centerToPercent(downShownCx + dx, virtualW);
                int ny = centerToPercent(downShownCy + dy, virtualH);
                if (nx != xPercent || ny != yPercent) {
                    xPercent = nx;
                    yPercent = ny;
                    invalidate();
                    if (listener != null) {
                        listener.onPreviewPanelPos(xPercent, yPercent, false);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                pressed = false;
                removeCallbacks(dragActivate);
                if (!dragging) {
                    return true;
                }
                dragging = false;
                android.view.ViewParent parent = getParent();
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(false);
                }
                if (dragWidget >= 0) {
                    android.content.Context c = getContext();
                    if (listener != null) {
                        listener.onWidgetMoved(dragWidget,
                                Prefs.getWidgetX(c, dragWidget),
                                Prefs.getWidgetY(c, dragWidget), true);
                    }
                    HudTrafficManager.get().render(TrafficLightBus.getLatest());
                    dragWidget = -1;
                    return true;
                }
                if (listener != null) {
                    listener.onPreviewPanelPos(xPercent, yPercent, true);
                }
                return true;
            }
            default:
                return super.onTouchEvent(event);
        }
    }

    /* ================= 内部 ================= */

    /** 预览画布相对虚拟副屏的缩放系数。 */
    private float fitScale() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || virtualW <= 0 || virtualH <= 0) {
            return 0f;
        }
        return Math.min(w / (float) virtualW, h / (float) virtualH);
    }

    /** 当前百分比对应的面板"显示中心"（HUD 坐标，已按渲染器同样的规则夹取）。 */
    private float[] shownCenter() {
        HudPanelRenderer.Metrics m = HudPanelRenderer.measure(hudDensity, currentState(), virtualW);
        float rawCx = virtualW / 2f + (xPercent - 50) / 100f * virtualW;
        float rawCy = virtualH / 2f + (yPercent - 50) / 100f * virtualH;
        float cx = HudPanelRenderer.clampCenterX(rawCx, m.width, hudDensity, virtualW);
        float cy = HudPanelRenderer.clampCenterY(rawCy, m.height, hudDensity, virtualH);
        return new float[]{cx, cy};
    }

    /** 偏移量（布局坐标 px）-> 百分比（50 = 不偏移，跟随面板堆叠位置）。 */
    private static int offsetToPercent(float offset, int total) {
        if (total <= 0) {
            return 50;
        }
        return clampPercent(Math.round(50f + offset / total * 100f));
    }

    private static int centerToPercent(float center, int total) {
        if (total <= 0) {
            return 50;
        }
        float pct = 50f + (center - total / 2f) / total * 100f;
        return clampPercent(Math.round(pct));
    }

    private static int clampPercent(int v) {
        if (v < 0) {
            return 0;
        }
        return Math.min(v, 100);
    }

    /**
     * 预览用的示例数据 —— <b>把所有控件都撑出来</b>（取值照抄真机日志，尺寸才准）：
     * 左转"注意" / 直行 76s / 右转 20s、红绿灯 6、终点 2.7km · 7min、预计09:02到达、路名 天慧路。
     */
    private static TrafficLightState buildDemoState() {
        TrafficLightState s = new TrafficLightState();
        long now = System.currentTimeMillis();
        // 左转故意用"只剩 2 秒"，方便在预览里直接看到"注意"两个字
        s.applyLight(TrafficLightState.DIR_LEFT, TrafficLightState.ST_YELLOW, 2, 0, 0, now);
        s.applyLight(TrafficLightState.DIR_STRAIGHT, TrafficLightState.ST_GREEN, 76, 0, 0, now);
        s.applyLight(TrafficLightState.DIR_RIGHT, TrafficLightState.ST_GREEN, 20, 0, 0, now);
        s.roadName = "天慧路";
        s.nextRoadName = "大观北路";
        s.remainLightNum = 6;
        s.routeRemainDist = 2664;
        s.routeRemainTime = 421;
        s.etaText = "预计09:02到达";
        // 电子眼示例：前方 300m 测速 60
        s.cameraDist = 300;
        s.cameraType = 1;
        s.cameraSpeed = 60;
        s.source = "preview";
        s.updateTime = now;
        s.lightUpdateTime = now;
        return s;
    }

    /** 示例数据也得保持"新鲜"，否则 10 秒后会被当成过期数据而全部消失。 */
    private void refreshDemoTimes() {
        long now = System.currentTimeMillis();
        for (DirectionLight l : demoState.lights.values()) {
            l.time = now;
        }
        demoState.updateTime = now;
        demoState.lightUpdateTime = now;
    }
}
