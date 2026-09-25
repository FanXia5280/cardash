package com.magiclantern.rgb;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/** 紧凑滑条（设计图风格）：细轨道 + 渐变进度 + 圆形滑块 */
public class CompactSeekBar extends View {

    public interface OnChanged {
        void onProgressChanged(int progress, boolean fromUser);

        void onStopTracking(int progress);
    }

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private int max = 100;
    private int progress = 100;
    private float trackH;
    private float thumbR;
    private OnChanged listener;

    public CompactSeekBar(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        trackH = d * 5f;
        thumbR = d * 8f;
        trackPaint.setColor(0xFF2A3040);
        thumbPaint.setColor(0xFFFFFFFF);
    }

    public void setMax(int m) {
        max = Math.max(1, m);
        invalidate();
    }

    public void setProgress(int p) {
        progress = Math.max(0, Math.min(max, p));
        invalidate();
    }

    public int getProgress() {
        return progress;
    }

    public void setListener(OnChanged l) {
        listener = l;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = (int) (thumbR * 3.2f);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        progressPaint.setShader(new LinearGradient(0, 0, w, 0, 0xFF4A6CF7, 0xFF7B5CFF,
                Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cy = getHeight() / 2f;
        rect.set(0, cy - trackH / 2, getWidth(), cy + trackH / 2);
        canvas.drawRoundRect(rect, trackH, trackH, trackPaint);

        float ratio = (float) progress / max;
        float x = Math.max(thumbR, Math.min(getWidth() - thumbR, ratio * getWidth()));
        rect.set(0, cy - trackH / 2, x, cy + trackH / 2);
        canvas.drawRoundRect(rect, trackH, trackH, progressPaint);

        canvas.drawCircle(x, cy, thumbR, thumbPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                float ratio = Math.max(0f, Math.min(1f, event.getX() / getWidth()));
                progress = Math.round(ratio * max);
                invalidate();
                if (listener != null) listener.onProgressChanged(progress, true);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                float ratio = Math.max(0f, Math.min(1f, event.getX() / getWidth()));
                progress = Math.round(ratio * max);
                invalidate();
                if (listener != null) {
                    listener.onProgressChanged(progress, true);
                    listener.onStopTracking(progress);
                }
                return true;
            }
            default:
                return super.onTouchEvent(event);
        }
    }
}
