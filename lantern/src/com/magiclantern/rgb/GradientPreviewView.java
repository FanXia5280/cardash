package com.magiclantern.rgb;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

/** 渐变预览条：两个颜色之间往复流动，用于编辑时预览效果 */
public class GradientPreviewView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Matrix matrix = new Matrix();
    private final float corner;

    private int color1 = 0xFFFF0000;
    private int color2 = 0xFF0000FF;
    private float phase;
    private int dir = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            // 往复相位：0 → 1 → 0，两次端点颜色连续，不会跳变
            phase += dir * 0.012f;
            if (phase >= 1f) {
                phase = 1f;
                dir = -1;
            } else if (phase <= 0f) {
                phase = 0f;
                dir = 1;
            }
            invalidate();
            handler.postDelayed(this, 40L);
        }
    };

    public GradientPreviewView(Context c) {
        super(c);
        corner = Ui.dp(c, 12);
        handler.post(tick);
    }

    public void setColors(int c1, int c2) {
        color1 = c1;
        color2 = c2;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacks(tick);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        rect.set(0, 0, w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        if (w <= 0) return;
        // 区间宽 2w，颜色数组 {c1, c2, c1}，随相位平移
        float shift = w * (1f + phase);
        LinearGradient lg = new LinearGradient(shift - 2 * w, 0, shift, 0,
                new int[]{color1, color2, color1}, null, Shader.TileMode.CLAMP);
        matrix.reset();
        lg.setLocalMatrix(matrix);
        paint.setShader(lg);
        canvas.drawRoundRect(rect, corner, corner, paint);
    }
}
