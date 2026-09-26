package com.s05.hudtraffic;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/**
 * 高德路线图投屏：MediaProjection 抓主屏 → 裁剪导航路线图卡片 → HUD/预览绘制。
 * 高德不广播路线点，自绘做不出真地图；抓屏裁剪投屏效果 1:1。
 */
public final class ScreenMapCapture {

    private static MediaProjection projection;
    private static VirtualDisplay vdisplay;
    private static ImageReader reader;
    private static int srcW;
    private static int srcH;
    private static volatile boolean running;
    private static volatile Bitmap latest;
    private static long lastHandledAt;
    private static MediaProjection.Callback stopCb;
    private static OnStateChanged stateListener;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 裁剪区域（源画面归一化 0..1）：对准高德导航左侧的路线图卡片 */
    public static volatile float cropX = 0.02f;
    public static volatile float cropY = 0.04f;
    public static volatile float cropW = 0.29f;
    public static volatile float cropH = 0.92f;

    public interface OnStateChanged {
        void onChanged(boolean running, String msg);
    }

    private ScreenMapCapture() {
    }

    public static boolean isRunning() {
        return running;
    }

    public static Bitmap latestFrame() {
        return latest;
    }

    public static void setOnStateChanged(OnStateChanged l) {
        stateListener = l;
    }

    private static void notifyState(final boolean ok, final String msg) {
        AppLog.i("MapCap", msg);
        OnStateChanged l = stateListener;
        if (l != null) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    l.onChanged(ok, msg);
                }
            });
        }
    }

    /** 由 MapCaptureService 在 startForeground(MEDIA_PROJECTION) 之后调用。 */
    public static boolean start(Context ctx, int resultCode, Intent data) {
        try {
            stop();
            MediaProjectionManager pm = (MediaProjectionManager)
                    ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = pm.getMediaProjection(resultCode, data);
            if (projection == null) {
                notifyState(false, "投影获取失败（授权无效）");
                return false;
            }
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            srcW = dm.widthPixels;
            srcH = dm.heightPixels;

            reader = ImageReader.newInstance(srcW, srcH, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader r) {
                    handleFrame(r);
                }
            }, MAIN);

            vdisplay = projection.createVirtualDisplay("hud-map-capture",
                    srcW, srcH, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, MAIN);

            stopCb = new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    running = false;
                    notifyState(false, "投屏已停止（授权被撤销）");
                }
            };
            projection.registerCallback(stopCb, MAIN);
            running = true;
            notifyState(true, "屏幕捕获已启动 " + srcW + "x" + srcH + "（约3fps）");
            return true;
        } catch (Throwable t) {
            notifyState(false, "启动投屏失败: " + t);
            return false;
        }
    }

    public static void stop() {
        running = false;
        try {
            if (reader != null) {
                reader.close();
            }
        } catch (Throwable ignored) {
        }
        reader = null;
        try {
            if (vdisplay != null) {
                vdisplay.release();
            }
        } catch (Throwable ignored) {
        }
        vdisplay = null;
        try {
            if (projection != null && stopCb != null) {
                projection.unregisterCallback(stopCb);
                projection.stop();
            }
        } catch (Throwable ignored) {
        }
        projection = null;
        latest = null;
    }

    /** 取最新帧转 Bitmap；节流约 3fps（路线图够用，省 CPU）。 */
    private static void handleFrame(ImageReader r) {
        long now = android.os.SystemClock.elapsedRealtime();
        boolean due = now - lastHandledAt >= 300L;
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null || !due) {
                return;   // 不处理也要取出丢弃，否则 ImageReader 卡住
            }
            lastHandledAt = now;
            Image.Plane pl = img.getPlanes()[0];
            ByteBuffer buf = pl.getBuffer();
            int rowStride = pl.getRowStride();
            int pixelStride = pl.getPixelStride();
            Bitmap bmp = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888);
            if (rowStride == pixelStride * srcW) {
                buf.rewind();
                bmp.copyPixelsFromBuffer(buf);
            } else {
                // 行尾有填充：逐行拷贝
                int[] row = new int[srcW];
                for (int y = 0; y < srcH; y++) {
                    buf.position(y * rowStride);
                    IntBufferWrap.read(buf, row);
                    bmp.setPixels(row, 0, srcW, 0, y, srcW, 1);
                }
            }
            latest = bmp;
        } catch (Throwable t) {
            AppLog.i("MapCap", "取帧失败: " + t);
        } finally {
            if (img != null) {
                img.close();
            }
        }
    }

    /** RGBA_8888 行缓冲 → ARGB int 行（little endian 正好对应）。 */
    private static final class IntBufferWrap {
        static void read(ByteBuffer buf, int[] row) {
            java.nio.IntBuffer ib = ((java.nio.ByteBuffer) buf).asIntBuffer();
            ib.get(row, 0, row.length);
        }
    }
}
