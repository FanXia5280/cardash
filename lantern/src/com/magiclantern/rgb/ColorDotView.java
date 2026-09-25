package com.magiclantern.rgb;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** 圆形颜色圆点：宽度自适应布局下也能保持正圆 */
public class ColorDotView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean selected;
    private int color = 0xFFFFFFFF;

    public ColorDotView(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density * Ui.scale(c);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(d * 2f);
        ring.setColor(0xFFFFFFFF);
        fill.setColor(0xFFFFFFFF);
    }

    public void setColor(int color) {
        this.color = color;
        fill.setColor(color);
        invalidate();
    }

    public int getColor() {
        return color;
    }

    public void setSelectedDot(boolean s) {
        selected = s;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) / 2f
                - getResources().getDisplayMetrics().density * Ui.scale(getContext()) * 2f;
        if (r <= 0) return;
        canvas.drawCircle(cx, cy, r, fill);
        if (selected) {
            canvas.drawCircle(cx, cy, r - ring.getStrokeWidth() / 2f, ring);
        }
    }

}
