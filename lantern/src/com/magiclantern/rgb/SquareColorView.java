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

/** 方形 HSV 色板：横向 = 饱和度，纵向 = 明度，色相由外部色相条决定 */
public class SquareColorView extends View {

    public interface OnColorChanged {
        void onColorChanged(int color);
    }

    private final float[] hsv = new float[]{0f, 1f, 1f};
    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float corner;
    private OnColorChanged listener;

    public SquareColorView(Context c) {
        super(c);
        float dens = c.getResources().getDisplayMetrics().density * Ui.scale(c);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dens * 2.2f);
        ringPaint.setColor(Color.WHITE);
        ringShadow.setStyle(Paint.Style.STROKE);
        ringShadow.setStrokeWidth(dens * 4.5f);
        ringShadow.setColor(0x66000000);
        corner = dens * 16f;
    }

    public void setListener(OnColorChanged l) {
        listener = l;
    }

    public float[] getHsv() {
        return hsv;
    }

    public int getColor() {
        return Color.HSVToColor(hsv);
    }

    public void setHue(float hue) {
        hsv[0] = hue;
        updateShaders();
        invalidate();
    }

    public void setColor(int color) {
        Color.colorToHSV(color, hsv);
        updateShaders();
        invalidate();
    }

    public void setSaturationValue(float s, float v) {
        hsv[1] = s;
        hsv[2] = v;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        if (w <= 0) w = Ui.dp(getContext(), 200);
        if (h <= 0) h = Ui.dp(getContext(), 140);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        rect.set(0, 0, w, h);
        updateShaders();
    }

    private void updateShaders() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        int hueColor = Color.HSVToColor(new float[]{hsv[0], 1f, 1f});
        basePaint.setShader(new LinearGradient(0, 0, w, 0, Color.WHITE, hueColor,
                Shader.TileMode.CLAMP));
        shadePaint.setShader(new LinearGradient(0, 0, 0, h, 0x00000000, 0xFF000000,
                Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRoundRect(rect, corner, corner, basePaint);
        canvas.drawRoundRect(rect, corner, corner, shadePaint);

        float x = hsv[1] * getWidth();
        float y = (1f - hsv[2]) * getHeight();
        float r = getResources().getDisplayMetrics().density * Ui.scale(getContext()) * 9f;
        canvas.drawCircle(x, y, r, ringShadow);
        canvas.drawCircle(x, y, r, ringPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                float x = Math.max(0, Math.min(getWidth(), event.getX()));
                float y = Math.max(0, Math.min(getHeight(), event.getY()));
                hsv[1] = getWidth() == 0 ? 0 : x / getWidth();
                hsv[2] = getHeight() == 0 ? 1 : 1f - y / getHeight();
                invalidate();
                if (listener != null) listener.onColorChanged(getColor());
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }
}
