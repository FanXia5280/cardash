package com.s05.hudtraffic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * HUD 副屏用的 SurfaceView。
 *
 * <p>实测车机 HUD 只合成 surface 层，普通 View（窗口主绘制层）在 HUD 上完全不可见，
 * 所以 HUD 画面必须画到 surface 上。</p>
 *
 * <p>绘制实现与主界面预览共用 {@link HudPanelRenderer}，两边永远一致。</p>
 */
public class HudSurfaceView extends SurfaceView implements SurfaceHolder.Callback {

    private static final String TAG = "HudSurface";

    private static final int PROBE_STEPS_PER_POSITION = 3;

    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean ready = false;
    private boolean probe = false;
    private int probeIndex = 0;
    private int probeTick = 0;

    /** 面板中心（surface 坐标），-1 表示居中 */
    private int centerX = -1;
    private int centerY = -1;
    private float scale = 1f;

    private TrafficLightState state;

    /** 每秒重绘一次：北京时间要每秒刷新，探针也靠它跳点 */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (probe) {
                probeTick++;
                if (probeTick % PROBE_STEPS_PER_POSITION == 0) {
                    probeIndex = (probeIndex + 1) % 9;
                }
            }
            drawAll();
            handler.postDelayed(this, 1000L);
        }
    };

    public HudSurfaceView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setZOrderOnTop(true);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        getHolder().addCallback(this);
    }

    /* ---------------- 外部接口 ---------------- */

    public void setProbe(boolean on) {
        if (probe == on) {
            return;
        }
        probe = on;
        probeTick = 0;
        probeIndex = 0;
        drawAll();
    }

    public void setState(TrafficLightState s) {
        state = s;
        if (!probe) {
            drawAll();
        }
    }

    /** 面板中心点（surface 坐标）。 */
    public void setPanelCenter(int x, int y) {
        centerX = x;
        centerY = y;
        if (!probe) {
            drawAll();
        }
    }

    public void setSurfaceScale(float s) {
        scale = s;
        if (!probe) {
            drawAll();
        }
    }

    public boolean isReady() {
        return ready;
    }

    /* ---------------- SurfaceHolder ---------------- */

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        ready = true;
        AppLog.i(TAG, "HUD SurfaceView 已创建 " + getWidth() + "x" + getHeight());
        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, 1000L);
        drawAll();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        ready = true;
        drawAll();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        ready = false;
        handler.removeCallbacks(ticker);
        AppLog.i(TAG, "HUD SurfaceView 已销毁");
    }

    /* ---------------- 绘制 ---------------- */

    public void drawAll() {
        if (!ready) {
            return;
        }
        SurfaceHolder holder = getHolder();
        Canvas canvas = null;
        try {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            // 离屏整帧：先把一帧完整画进缓冲 Bitmap，再一次性贴到 surface。
            // 之前是 lockCanvas 后先 CLEAR 全屏再逐个画控件，HUD 的合成器拿到
            // "清完屏还没画完"的半成品帧，表现为面板每秒闪一下。
            if (frameBuf == null || frameBuf.getWidth() != w || frameBuf.getHeight() != h) {
                frameBuf = android.graphics.Bitmap.createBitmap(
                        w, h, android.graphics.Bitmap.Config.ARGB_8888);
                frameCanvas = new android.graphics.Canvas(frameBuf);
            }
            frameCanvas.drawColor(0x00000000, PorterDuff.Mode.CLEAR);
            if (probe) {
                HudPanelRenderer.drawProbe(frameCanvas, density, w, h, probeIndex, probeTick);
            } else {
                HudPanelRenderer.draw(frameCanvas, density, state, centerX, centerY, scale, w, h);
            }
            canvas = holder.lockCanvas();
            if (canvas == null) {
                return;
            }
            canvas.drawBitmap(frameBuf, 0, 0, null);
        } catch (Throwable t) {
            Log.w(TAG, "drawAll 失败: " + t);
        } finally {
            if (canvas != null) {
                try {
                    holder.unlockCanvasAndPost(canvas);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 离屏帧缓冲（尺寸变化才重建，避免每秒分配 800x480 位图） */
    private android.graphics.Bitmap frameBuf;
    private android.graphics.Canvas frameCanvas;
}
