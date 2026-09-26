package com.s05.hudtraffic;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 悬浮球的图标：默认用内置图片，用户可以从相册换一张自己喜欢的。
 *
 * <p>自定义图片存在<b>内部存储</b>（{@code files/entry_ball.png}），
 * 这样不依赖外部存储权限；删掉这个文件就等于"恢复默认"。</p>
 */
public final class EntryBallIcon {

    private static final String TAG = "BallIcon";
    private static final String FILE = "entry_ball.png";
    /** 内置默认图（assets 里，合并进高德时由组装脚本一起塞进 APK） */
    private static final String ASSET = "hud_entry_ball.png";
    /** 自定义图片最长边压到这个尺寸（悬浮球只有 46dp，再大纯浪费内存） */
    private static final int MAX = 256;

    private EntryBallIcon() {
    }

    public static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE);
    }

    public static boolean hasCustom(Context ctx) {
        return file(ctx).exists();
    }

    /**
     * 当前该显示的图标：有自定义就用自定义的，否则用内置默认图。
     *
     * <p><b>默认图走 assets（{@code hud_entry_ball.png}），不用 R.drawable</b>：
     * 我们合并进高德时只塞了 dex，resources.arsc 还是高德的，引用 R.drawable 会找不到资源。
     * assets 是纯文件、不依赖资源表，两种运行环境（独立 APK / 内置高德）都能读到。</p>
     */
    public static Drawable ballDrawable(Context ctx) {
        Bitmap bm = load(ctx);
        if (bm == null) {
            bm = fromAssets(ctx);
        }
        return bm == null ? null : new BitmapDrawable(ctx.getResources(), bm);
    }

    /** 内置默认图（放在 assets 里，合并进高德时由组装脚本塞进 APK）。 */
    private static Bitmap fromAssets(Context ctx) {
        InputStream in = null;
        try {
            in = ctx.getAssets().open(ASSET);
            return BitmapFactory.decodeStream(in);
        } catch (Throwable t) {
            AppLog.i(TAG, "读取内置悬浮球图片失败: " + t);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 读取自定义图片（没设置返回 null）。 */
    public static Bitmap load(Context ctx) {
        File f = file(ctx);
        if (!f.exists()) {
            return null;
        }
        try {
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Throwable t) {
            AppLog.i(TAG, "读取自定义悬浮球图片失败: " + t);
            return null;
        }
    }

    /** 从相册返回的 Uri 解码、缩放并保存成功返回缩略图，失败返回 null。 */
    public static Bitmap saveFromUri(Context ctx, Uri uri) {
        Bitmap src = decode(ctx, uri);
        if (src == null) {
            return null;
        }
        Bitmap scaled = scale(src);
        try {
            FileOutputStream out = new FileOutputStream(file(ctx));
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.close();
            AppLog.i(TAG, "自定义悬浮球图片已保存 " + scaled.getWidth() + "x" + scaled.getHeight());
            return scaled;
        } catch (Throwable t) {
            AppLog.i(TAG, "保存自定义悬浮球图片失败: " + t);
            return null;
        }
    }

    /** 恢复默认图片（删掉自定义文件）。 */
    public static void clear(Context ctx) {
        try {
            file(ctx).delete();
        } catch (Throwable ignored) {
        }
    }

    private static Bitmap decode(Context ctx, Uri uri) {
        if (uri == null) {
            return null;
        }
        InputStream in = null;
        try {
            // 先采样解码（相册里的照片动辄几千万像素，直接全解会 OOM）
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream probe = ctx.getContentResolver().openInputStream(uri);
            if (probe == null) {
                return null;
            }
            BitmapFactory.decodeStream(probe, null, bounds);
            probe.close();

            int max = Math.max(bounds.outWidth, bounds.outHeight);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (max / opts.inSampleSize > MAX * 2) {
                opts.inSampleSize *= 2;
            }
            in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) {
                return null;
            }
            return BitmapFactory.decodeStream(in, null, opts);
        } catch (Throwable t) {
            AppLog.i(TAG, "解码图片失败: " + t);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static Bitmap scale(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        float r = MAX / (float) Math.max(w, h);
        if (r >= 1f) {
            return src;
        }
        return Bitmap.createScaledBitmap(src, Math.round(w * r), Math.round(h * r), true);
    }
}
