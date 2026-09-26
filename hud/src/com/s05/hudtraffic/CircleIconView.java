package com.s05.hudtraffic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * 圆形图标：不管原图是正方形还是长方形，都裁成圆形显示（居中裁剪、不变形）。
 *
 * <p>悬浮球要的就是这个效果 —— 用户上传的图不一定带圆形透明底，
 * 直接贴上去会是方的，跟 HUD 风格不搭。</p>
 */
public class CircleIconView extends View {

    private Drawable icon;
    private final Path circle = new Path();

    public CircleIconView(Context context) {
        super(context);
    }

    public void setIconDrawable(Drawable d) {
        icon = d;
        invalidate();
    }

    public Drawable getIconDrawable() {
        return icon;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float cx = w / 2f;
        float cy = h / 2f;
        float r = Math.min(w, h) / 2f;

        circle.reset();
        circle.addCircle(cx, cy, r, Path.Direction.CW);

        int saved = canvas.save();
        canvas.clipPath(circle);
        if (icon != null) {
            // CENTER_CROP：等比放大到铺满整个圆（长图会被裁两边），不会拉伸变形
            float iw = icon.getIntrinsicWidth();
            float ih = icon.getIntrinsicHeight();
            if (iw > 0 && ih > 0) {
                float scale = Math.max(w / iw, h / ih);
                float dw = iw * scale;
                float dh = ih * scale;
                int l = Math.round((w - dw) / 2f);
                int t = Math.round((h - dh) / 2f);
                icon.setBounds(l, t, l + Math.round(dw), t + Math.round(dh));
            } else {
                icon.setBounds(0, 0, w, h);
            }
            icon.draw(canvas);
        }
        canvas.restoreToCount(saved);
    }
}
