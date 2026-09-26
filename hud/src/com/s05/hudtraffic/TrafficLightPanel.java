package com.s05.hudtraffic;

import android.content.Context;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

/**
 * 普通 View 版的 HUD 面板（主界面「HUD 预览」、主屏镜像、Presentation 兜底都用它）。
 *
 * <p>绘制内容与尺寸全部来自 {@link HudPanelRenderer}，与 HUD 副屏上的
 * {@link HudSurfaceView} 共用同一份实现 —— 所以<b>预览和副屏完全一致</b>。</p>
 *
 * <p>注意：车机 HUD 上普通 View 不可见（那片区域只合成 surface 层），
 * 所以 HUD 副屏走 SurfaceView，这个类只用于主屏内的显示。</p>
 */
public class TrafficLightPanel extends View {

    /** 红绿灯数据超过该时长未更新即视为过期 */
    public static final long STALE_MS = TrafficLightState.STALE_MS;

    private static final int PROBE_STEPS_PER_POSITION = 3;

    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TrafficLightState state;
    private boolean testPattern = false;
    private float lastW = -1f;
    private float lastH = -1f;

    private int probeIndex = 0;
    private int probeTick = 0;

    private final Runnable probeTicker = new Runnable() {
        @Override
        public void run() {
            if (!testPattern) {
                return;
            }
            probeTick++;
            if (probeTick % PROBE_STEPS_PER_POSITION == 0) {
                probeIndex = (probeIndex + 1) % 9;
            }
            invalidate();
            handler.postDelayed(this, 1000L);
        }
    };

    /**
     * 每秒重绘一次：画面里显示的"当前时间"必须持续刷新。
     * 之前只在数据变化时 invalidate —— 没有数据（未导航/无广播）时不重绘，
     * 主屏镜像/Presentation 上的时间就会停在最后一帧（实测踩过）。
     */
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

    public TrafficLightPanel(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
    }

    /** 面板缩放（HUD 上按比例放大/缩小）。 */
    public void setPanelScale(float scale) {
        setScaleX(scale);
        setScaleY(scale);
    }

    /** 定位测试图案。 */
    public void setTestPattern(boolean on) {
        if (testPattern == on) {
            return;
        }
        testPattern = on;
        probeIndex = 0;
        probeTick = 0;
        handler.removeCallbacks(probeTicker);
        if (on) {
            handler.postDelayed(probeTicker, 1000L);
        }
        requestLayout();
        invalidate();
    }

    public boolean isTestPattern() {
        return testPattern;
    }

    public void render(TrafficLightState s) {
        state = s;
        if (testPattern) {
            invalidate();
            return;
        }
        float maxW = getWidth() > 0 ? getWidth() : 400 * density;
        HudPanelRenderer.Metrics m = HudPanelRenderer.measure(density, s, maxW);
        // 只有版面尺寸真的变了才重新布局，避免每秒 measure/layout（预览在 ScrollView 里）
        if (Math.abs(m.width - lastW) > 1f || Math.abs(m.height - lastH) > 1f) {
            lastW = m.width;
            lastH = m.height;
            requestLayout();
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        if (testPattern) {
            setMeasuredDimension(
                    resolveSize((int) (300 * density), widthSpec),
                    resolveSize((int) (200 * density), heightSpec));
            return;
        }
        int available = MeasureSpec.getSize(widthSpec);
        float maxW = available > 0 ? available : 400 * density;
        HudPanelRenderer.Metrics m = HudPanelRenderer.measure(density, state, maxW);
        int w = Math.max(1, (int) Math.ceil(m.width));
        int h = Math.max(1, (int) Math.ceil(m.height));
        setMeasuredDimension(resolveSize(w, widthSpec), resolveSize(h, heightSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (testPattern) {
            HudPanelRenderer.drawProbe(canvas, density, getWidth(), getHeight(), probeIndex, probeTick);
            return;
        }
        HudPanelRenderer.draw(canvas, density, state, -1f, -1f, 1f, getWidth(), getHeight());
    }
}
