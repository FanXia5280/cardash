package com.s05.hudtraffic;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * HUD 显示兜底方案：用 {@link Presentation} 在副屏上直接开一个窗口。
 *
 * <p>与 D 应用 {@code com.deepalhome.launcher.hud.s05utils.HudPresentation} 思路一致，
 * 区别是这里承载的是红绿灯面板而非启动视频。当悬浮窗方式（TYPE_APPLICATION_OVERLAY）
 * 在部分系统上被拒绝时使用。</p>
 */
public class HudTrafficPresentation extends Presentation {

    private final Context displayContext;
    private TrafficLightPanel panel;

    public HudTrafficPresentation(Context outerContext, Display display) {
        super(outerContext, display);
        this.displayContext = outerContext.getApplicationContext().createDisplayContext(display);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        if (window != null) {
            // Presentation 内部已经把窗口类型设为 TYPE_PRESENTATION，这里只调标志位
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0f);
            window.setFormat(PixelFormat.TRANSLUCENT);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.gravity = Gravity.CENTER;
            window.setAttributes(lp);
        }

        FrameLayout root = new FrameLayout(displayContext);
        root.setBackgroundColor(Color.TRANSPARENT);
        panel = new TrafficLightPanel(displayContext);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        root.addView(panel, lp);
        setContentView(root);
        render(TrafficLightBus.getLatest());
    }

    public void setPanelScale(float scale) {
        if (panel != null) {
            panel.setPanelScale(scale);
        }
    }

    public void setTestPattern(boolean on) {
        if (panel != null) {
            panel.setTestPattern(on);
        }
    }

    public void render(TrafficLightState state) {
        if (panel != null) {
            panel.render(state);
        }
    }
}
