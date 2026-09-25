package com.magiclantern.rgb;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** UI 构建工具：统一设计图风格（深色卡片 + 渐变圆图标 + 圆角） */
public class Ui {

    public static final int TEXT_PRIMARY = 0xFFFFFFFF;
    public static final int TEXT_SECONDARY = 0xFF8A93A6;
    public static final int TEXT_THIRD = 0xFF5C6577;
    public static final int CARD_INNER = 0xFF1E2331;

    private static float scaleCache = -1f;
    private static float scaleOverride = -1f;

    /**
     * UI 缩放系数：设计基准为 900dp × 470dp（模拟器实测）。
     * 逻辑尺寸更大的屏幕（如 2560×1440 @240dpi → 1707×960dp 的车机）整体等比放大，
     * 保证视觉比例与设计一致。
     */
    public static float scale(Context c) {
        if (scaleOverride > 0) return scaleOverride;
        if (scaleCache > 0) return scaleCache;
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        float w = dm.widthPixels / dm.density;
        float h = dm.heightPixels / dm.density;
        float s = Math.min(w / 900f, h / 470f);
        s = Math.max(0.85f, Math.min(s, 2.4f));
        scaleCache = s;
        return s;
    }

    /**
     * 嵌入到别的容器时用：按**容器尺寸**算缩放，而不是整块屏幕。
     * 车机桌面「桌面设置 → 氛围灯设置」右边那块区域只有全屏的一部分，
     * 不换算的话内容会溢出容器。
     */
    public static void setScaleForContainer(Context c, int widthPx, int heightPx) {
        if (c == null || widthPx <= 0 || heightPx <= 0) return;
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        float w = widthPx / dm.density;
        float h = heightPx / dm.density;
        float s = Math.min(w / 900f, h / 470f);
        scaleOverride = Math.max(0.55f, Math.min(s, 2.4f));
    }

    /** 取消容器缩放（回到按整屏算）。 */
    public static void clearScaleOverride() {
        scaleOverride = -1f;
    }

    /** 字号（sp）随屏幕等比缩放 */
    public static float sp(Context c, float v) {
        return v * scale(c);
    }

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density * scale(c) + 0.5f);
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Ui.sp(c, sp));
        tv.setTextColor(color);
        tv.setIncludeFontPadding(false);
        if (bold) tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        return tv;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(0x14FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 1));
        lp.topMargin = dp(c, 8);
        lp.bottomMargin = dp(c, 8);
        v.setLayoutParams(lp);
        return v;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    public static LinearLayout.LayoutParams lpm(int w, int h, int top, int bottom, int start, int end) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = top;
        p.bottomMargin = bottom;
        p.setMarginStart(start);
        p.setMarginEnd(end);
        return p;
    }

    /** 渐变圆形图标 */
    public static ImageView iconCircle(Context c, int bgRes, int iconRes, int sizeDp, int paddingDp) {
        ImageView iv = new ImageView(c);
        Res.bg(iv, bgRes);
        Res.setIcon(iv, iconRes);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int p = dp(c, paddingDp);
        iv.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp));
        iv.setLayoutParams(lp);
        return iv;
    }

    /** 卡片容器 */
    public static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        Res.bg(l, Res.bg_card);
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        return l;
    }

    public static GradientDrawable roundRect(Context c, int color, float radiusDp, Integer strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        if (strokeColor != null) d.setStroke(dp(c, 1), strokeColor.intValue());
        return d;
    }

    public static GradientDrawable circle(Context c, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    /** 分段按钮项 */
    public static TextView segment(Context c, String text) {
        TextView tv = text(c, text, 14, TEXT_PRIMARY, true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(c, 7), 0, dp(c, 7));
        Res.bg(tv, Res.bg_seg);
        return tv;
    }

    /** 图标 + 标题项（分段按钮样式，带图标） */
    public static TextView segmentWithIcon(Context c, String text, int iconRes) {
        TextView tv = segment(c, text);
        Res.compoundStart(tv, iconRes, 18);
        tv.setCompoundDrawablePadding(dp(c, 8));
        return tv;
    }

    /** 快捷功能卡片：渐变图标 + 标题 + 箭头 */
    public static LinearLayout quickCard(Context c, int bgRes, int iconRes, String title) {
        LinearLayout card = row(c);
        Res.bg(card, Res.bg_card);
        int p = dp(c, 14);
        card.setPadding(p, p, p, p);
        card.setClickable(true);
        card.setFocusable(true);
        card.addView(iconCircle(c, bgRes, iconRes, 38, 9));

        LinearLayout mid = column(c);
        mid.setLayoutParams(lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mid.setPadding(dp(c, 12), 0, dp(c, 4), 0);
        mid.addView(text(c, title, 15, TEXT_PRIMARY, false));
        card.addView(mid);

        card.addView(icon(c, Res.ic_arrow, 18, TEXT_THIRD));
        return card;
    }

    /**
     * 长按弹出的悬浮菜单（类似手机桌面长按菜单）
     *
     * @param title   菜单标题（可为 null）
     * @param items   菜单项文字
     * @param actions 与 items 一一对应的动作
     */
    public static void showPopupMenu(android.content.Context a, View anchor,
                                     String title, String[] items, Runnable[] actions) {
        showPopupMenu(a, anchor, title, items, actions, null);
    }

    public static void showPopupMenu(final android.content.Context a, final View anchor,
                                     String title, String[] items, final Runnable[] actions,
                                     final Runnable onDismiss) {
        final LinearLayout content = column(a);
        content.setBackground(roundRect(a, 0xFF1E2331, 16, 0x40FFFFFF));
        content.setPadding(dp(a, 18), dp(a, 14), dp(a, 18), dp(a, 14));

        if (title != null && title.length() > 0) {
            content.addView(text(a, title, 14, TEXT_PRIMARY, true));
            content.addView(divider(a));
        }

        final android.widget.PopupWindow[] holder = new android.widget.PopupWindow[1];

        for (int i = 0; i < items.length; i++) {
            final Runnable action = actions[i];
            TextView row = text(a, items[i], 14, TEXT_PRIMARY, false);
            row.setPadding(dp(a, 4), dp(a, 13), dp(a, 4), dp(a, 13));
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (holder[0] != null) holder[0].dismiss();
                    if (action != null) action.run();
                }
            });
            content.addView(row);
        }

        int menuWidth = dp(a, 240);
        final android.widget.PopupWindow popup = new android.widget.PopupWindow(content,
                menuWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
        popup.setOutsideTouchable(true);
        popup.setFocusable(true);
        if (android.os.Build.VERSION.SDK_INT >= 21) popup.setElevation(dp(a, 12));
        popup.setOnDismissListener(new android.widget.PopupWindow.OnDismissListener() {
            @Override
            public void onDismiss() {
                if (onDismiss != null) onDismiss.run();
            }
        });
        holder[0] = popup;

        int wSpec = View.MeasureSpec.makeMeasureSpec(menuWidth, View.MeasureSpec.EXACTLY);
        int hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        content.measure(wSpec, hSpec);
        int menuHeight = content.getMeasuredHeight();

        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int x = loc[0] + anchor.getWidth() / 2 - menuWidth / 2;
        int y = loc[1] - menuHeight - dp(a, 10);
        if (y < dp(a, 16)) {
            y = loc[1] + anchor.getHeight() + dp(a, 10);
        }

        content.setAlpha(0f);
        content.setScaleX(0.85f);
        content.setScaleY(0.85f);
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y);
        content.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start();
    }

    /** 按下时轻微缩放（松手恢复；处于长按激活态时不恢复） */
    public static void addPressEffect(final View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, android.view.MotionEvent event) {
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(120).start();
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        if (!view.isSelected()) {
                            view.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                        }
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
    }

    /**
     * 长按激活态：按钮放大并保持（不再抖动），直到菜单关闭才恢复。
     * 这样用户能明确看到自己长按的是哪一个按钮。
     */
    public static void setLongPressActive(final View v, boolean active) {
        v.setSelected(active);
        v.animate().cancel();
        v.animate().scaleX(active ? 1.06f : 1f).scaleY(active ? 1.06f : 1f)
                .setDuration(active ? 130 : 150).start();
    }

    /** 长按激活时的高亮背景（蓝紫描边） */
    public static GradientDrawable activeBackground(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(c, 12));
        d.setColor(0x552F6BFF);
        d.setStroke(dp(c, 2), 0xFF4A6CF7);
        return d;
    }

    /** 区块标题：固定高度，保证左右两栏内容严格对齐 */
    public static TextView sectionHeader(Context c, String text) {
        TextView tv = text(c, text, 15, TEXT_PRIMARY, true);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 30)));
        tv.setGravity(Gravity.CENTER_VERTICAL);
        return tv;
    }

    /** 设置项行：图标 + 标题 +（值/自定义右控件）+ 箭头 */
    public static LinearLayout settingRow(Context c, int bgRes, int iconRes, String title,
                                          String value, boolean arrow) {
        LinearLayout row = row(c);
        row.setPadding(0, dp(c, 10), 0, dp(c, 10));
        row.addView(iconCircle(c, bgRes, iconRes, 38, 9));

        LinearLayout mid = column(c);
        mid.setLayoutParams(lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mid.setPadding(dp(c, 12), 0, dp(c, 8), 0);
        mid.addView(text(c, title, 15, TEXT_PRIMARY, false));
        if (value != null && value.length() > 0) {
            TextView tv = text(c, value, 12, TEXT_SECONDARY, false);
            tv.setPadding(0, dp(c, 2), 0, 0);
            mid.addView(tv);
        }
        row.addView(mid);

        if (arrow) {
            ImageView iv = new ImageView(c);
            Res.setIcon(iv, Res.ic_arrow);
            iv.setColorFilter(TEXT_THIRD);
            iv.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 18), dp(c, 18)));
            row.addView(iv);
        }
        return row;
    }

    public static android.widget.Switch switchView(Context c) {
        android.widget.Switch sw = new android.widget.Switch(c);
        sw.setShowText(false);
        sw.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sw;
    }

    public static void tint(ImageView iv, int color) {
        iv.setColorFilter(color);
    }

    public static ImageView icon(Context c, int res, int sizeDp, int color) {
        ImageView iv = new ImageView(c);
        Res.setIcon(iv, res);
        if (color != 0) iv.setColorFilter(color);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return iv;
    }

    public static FrameLayout.LayoutParams flp(int w, int h) {
        return new FrameLayout.LayoutParams(w, h);
    }

    /** 统一风格的滑条（使用系统控件 + 自定义轨道/滑块，兼容性最好） */
    public static android.widget.SeekBar seekBar(Context c, int progress, int max) {
        android.widget.SeekBar sb = new android.widget.SeekBar(c);
        sb.setMax(max);
        sb.setProgress(progress);
        // 只用系统默认样式（主题 accent 为紫蓝，外观与设计一致）：
        // 自定义 drawable / tint 在部分车机与模拟器的软件绘制下会完全不渲染
        int pad = dp(c, 6);
        sb.setPadding(dp(c, 9), pad, dp(c, 9), pad);
        return sb;
    }

    /**
     * 把内容放进可滚动容器。
     * 注意：必须显式指定子视图高度为 WRAP_CONTENT，否则会被拉伸成满高、
     * 导致卡片内部内容（如亮度滑条）被裁切。
     */
    public static android.widget.ScrollView scrollWrap(Context c, View child) {
        android.widget.ScrollView sv = new android.widget.ScrollView(c);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sv.setVerticalScrollBarEnabled(false);
        // 防止点击内部控件时容器自动滚动，导致内容被推出可视区域
        sv.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        sv.addView(child, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }
}
