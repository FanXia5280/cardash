package com.magiclantern.rgb;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/** 彩虹色相条 */
public class HueBarView extends View {

    public interface OnHueChanged {
        void onHueChanged(float hue);
    }

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint indicator = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint indicatorShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float hue = 0f;
    private OnHueChanged listener;

    public HueBarView(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density * Ui.scale(c);
        indicator.setColor(Color.WHITE);
        indicatorShadow.setColor(0x66000000);
        corner = d * 13f;
    }

    private final float corner;

    public void setListener(OnHueChanged l) {
        listener = l;
    }

    public float getHue() {
        return hue;
    }

    public void setHue(float h) {
        hue = h;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        rect.set(0, 0, w, h);
        int[] colors = new int[]{0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF,
                0xFF0000FF, 0xFFFF00FF, 0xFFFF0000};
        barPaint.setShader(new LinearGradient(0, 0, w, 0, colors, null,
                Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRoundRect(rect, corner, corner, barPaint);

        float x = hue / 360f * getWidth();
        float d = getResources().getDisplayMetrics().density * Ui.scale(getContext());
        float halfW = d * 3.5f;
        float top = d * -3f;
        float bottom = getHeight() + d * 3f;
        canvas.drawRoundRect(new RectF(x - halfW, top, x + halfW, bottom), halfW, halfW,
                indicatorShadow);
        canvas.drawRoundRect(new RectF(x - halfW + d, top + d, x + halfW - d, bottom - d),
                halfW, halfW, indicator);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                float x = Math.max(0, Math.min(getWidth(), event.getX()));
                hue = getWidth() == 0 ? 0 : x / getWidth() * 360f;
                invalidate();
                if (listener != null) listener.onHueChanged(hue);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }
}
