package com.cardash.inject;

import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;

/**
 * 内嵌面板的主题层：让「氛围灯设置」「HUD 红绿灯」两块面板的配色跟着
 * D 桌面「桌面设置」页走，而不是固定自己那套深色。
 *
 * <p>用户反馈：桌面设置页是浅色（白底黑字），面板自己画一整块
 * {@code 0xFF0A0D14} 的深色底，看起来很突兀。</p>
 *
 * <p>做法：<b>打开设置页时从右侧容器一路向上找第一层实色背景</b>（ColorDrawable），
 * 拿到它的颜色 —— 这样原厂白天/夜间主题换色我们自动跟上；
 * 再按亮度决定面板内容用浅色配色还是深色配色：</p>
 * <ul>
 *   <li>宿主背景亮 → 卡片白底 + 深色文字（和设置页融为一体）；</li>
 *   <li>宿主背景暗 → 维持原来的深色卡片 + 白字。</li>
 * </ul>
 *
 * <p>采样不到（容器整条链都没有实色背景）时按浅色处理 —— 与实车截图
 * （白天浅色主题）一致。面板根布局直接铺采到的宿主背景色，
 * 保证「和 D 桌面那个背景色一样」。</p>
 *
 * <p>配色真正的落地在两处：{@link Ui#applyTheme}（氛围灯的文字/卡片色板）与
 * {@code com.magiclantern.rgb.Res#applyTheme}（氛围灯那 8 个代码内联的背景 drawable）。
 * HUD 面板直接读这里的颜色。</p>
 */
public final class PanelTheme {

    /** 采样不到宿主背景时的兜底：浅灰白（与实车设置页观感一致） */
    private static final int FALLBACK_BG = 0xFFF5F6F8;

    private static volatile boolean light = true;
    private static volatile int hostBg = FALLBACK_BG;

    private PanelTheme() {
    }

    /** 打开面板前调一次：从容器向上采样宿主背景并套用整套配色。 */
    public static void resolve(ViewGroup container) {
        int bg = findSolidBackground(container, 8);
        if (bg == 0) {
            bg = FALLBACK_BG;
        }
        hostBg = bg;
        light = luminance(bg) >= 140;
        try {
            com.magiclantern.rgb.Ui.applyTheme(light, bg);
            com.magiclantern.rgb.Res.applyTheme(light);
        } catch (Throwable ignored) {
            // 主题套用失败不影响面板显示（ lantern 独立构建里可能没有这些类）
        }
    }

    /** 宿主是不是浅色主题（true = 白底黑字，false = 深底白字）。 */
    public static boolean isLight() {
        return light;
    }

    /** 面板根布局/页面底色 = 采到的宿主背景色（即「和 D 桌面背景一样」）。 */
    public static int pageBg() {
        return hostBg;
    }

    /* ---- HUD 面板用的整套颜色（氛围灯走 Ui/Res 那边） ---- */

    public static int textPrimary() {
        return light ? 0xFF171C26 : 0xFFFFFFFF;
    }

    public static int textSecondary() {
        return light ? 0xFF6A7484 : 0xFF8A93A6;
    }

    public static int textThird() {
        return light ? 0xFF9AA3B2 : 0xFF5C6577;
    }

    public static int cardBg() {
        return light ? 0xFFFFFFFF : 0xFF161A25;
    }

    public static int cardInnerBg() {
        return light ? 0xFFF2F4F8 : 0xFF1E2331;
    }

    public static int cardStroke() {
        return light ? 0x14000000 : 0x1FFFFFFF;
    }

    public static int divider() {
        return light ? 0x12000000 : 0x14FFFFFF;
    }

    public static int brandBlue() {
        return 0xFF4A6CF7;
    }

    /* ------------------------------------------------------------------ */

    /** 从 view 一路向上找第一层 ColorDrawable 实色背景；找不到返回 0。 */
    private static int findSolidBackground(View v, int maxHops) {
        View cur = v;
        for (int i = 0; cur != null && i < maxHops; i++, cur = cur.getParent() instanceof View
                ? (View) cur.getParent() : null) {
            try {
                if (cur.getBackground() instanceof ColorDrawable) {
                    return ((ColorDrawable) cur.getBackground()).getColor();
                }
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    /** ITU-R 601 亮度（0~255）。 */
    private static int luminance(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return (int) (0.299 * r + 0.587 * g + 0.114 * b);
    }
}
