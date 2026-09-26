package com.s05.hudtraffic;

import android.content.Context;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.view.Display;

import java.util.ArrayList;
import java.util.List;

/**
 * HUD 副屏查找。
 *
 * <p>移植自 D 应用 {@code com.deepalhome.launcher.hud.HudDisplayHelper#findHudDisplay(Context)}：
 * 遍历所有非主屏显示器，命中分辨率为 <b>800x480</b> 或 <b>480x800</b> 的即认为是车机 HUD 副屏。</p>
 */
public final class HudDisplayHelper {

    public static final int HUD_WIDTH = 800;
    public static final int HUD_HEIGHT = 480;

    private HudDisplayHelper() {
    }

    /**
     * @param allowAnySecondary true 时若找不到标准尺寸副屏，则退回“任意一个副屏”，
     *                          方便模拟器上用非标准尺寸做验证。
     */
    public static Display findHudDisplay(Context context, boolean allowAnySecondary) {
        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) {
            return null;
        }
        Point p = new Point();
        Display fallback = null;
        Display[] displays = dm.getDisplays();
        if (displays == null) {
            return null;
        }
        for (Display display : displays) {
            if (display == null || display.getDisplayId() == Display.DEFAULT_DISPLAY) {
                continue;
            }
            int w;
            int h;
            try {
                display.getRealSize(p);
            } catch (Throwable t) {
                try {
                    display.getSize(p);
                } catch (Throwable t2) {
                    continue;
                }
            }
            w = p.x;
            h = p.y;
            if ((w == HUD_WIDTH && h == HUD_HEIGHT) || (w == HUD_HEIGHT && h == HUD_WIDTH)) {
                return display;
            }
            if (allowAnySecondary && fallback == null) {
                fallback = display;
            }
        }
        return fallback;
    }

    /** 列出所有副屏，供界面排查使用。 */
    public static List<Display> listSecondaryDisplays(Context context) {
        List<Display> result = new ArrayList<>();
        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) {
            return result;
        }
        Display[] displays = dm.getDisplays();
        if (displays == null) {
            return result;
        }
        for (Display d : displays) {
            if (d != null && d.getDisplayId() != Display.DEFAULT_DISPLAY) {
                result.add(d);
            }
        }
        return result;
    }

    public static String describe(Display d, Context context) {
        if (d == null) {
            return "无";
        }
        Point p = new Point();
        try {
            d.getRealSize(p);
        } catch (Throwable t) {
            try {
                d.getSize(p);
            } catch (Throwable t2) {
                return "displayId=" + d.getDisplayId();
            }
        }
        String name = d.getName() != null ? d.getName() : "?";
        return "displayId=" + d.getDisplayId() + " " + p.x + "x" + p.y
                + " [" + name + "] 状态=" + stateText(d.getState());
    }

    public static String stateText(int state) {
        switch (state) {
            case Display.STATE_OFF:
                return "OFF";
            case Display.STATE_ON:
                return "ON";
            case Display.STATE_DOZE:
                return "DOZE";
            case Display.STATE_DOZE_SUSPEND:
                return "DOZE_SUSPEND";
            case Display.STATE_ON_SUSPEND:
                return "ON_SUSPEND";
            case Display.STATE_UNKNOWN:
                return "UNKNOWN";
            case Display.STATE_VR:
                return "VR";
            default:
                return String.valueOf(state);
        }
    }
}
