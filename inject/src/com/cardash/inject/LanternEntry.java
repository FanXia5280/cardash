package com.cardash.inject;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.magiclantern.rgb.LanternPanel;
import com.magiclantern.rgb.Res;
import com.magiclantern.rgb.Ui;
import com.s05.hudtraffic.HudPanel;

/**
 * 往车机桌面的「桌面设置」里挂我们的两项入口（不改原包任何 smali / 资源，纯运行时挂载）：
 * <b>「HUD 红绿灯」在上、「氛围灯设置」在下</b>；同时按用户要求把原厂的
 * 「蓝牙设备」「关于我们」两项从侧栏移除，我们的项自然补位到列表尾部。
 *
 * <p>做法：跟着桌面进程启动（{@code BootProvider} → {@code install}），
 * 监听设置页 {@code SettingActivity} 打开，往左边的 RadioGroup 末尾插两项
 * （**外观照着旁边原厂项复刻**：字号/颜色/内边距/背景/图标尺寸全部照抄），
 * 点哪项就把对应面板（{@link HudPanel} / {@link LanternPanel}）放进
 * 右边的内容容器 {@code settingsViewContainer}；
 * 点回原厂其它项时原厂自己 removeAllViews 会把我们的面板摘掉，
 * 面板的 onDetachedFromWindow 负责解绑监听与广播
 * （氛围灯的蓝牙连接与渐变播放不动 —— 那是保活的活；
 *   HUD 的广播接收在 {@code HudRuntime}，也跟桌面进程走，与面板显隐无关）。
 *
 * <p>为什么敢往 RadioGroup 里插：原厂的 OnCheckedChangeListener 是一条
 * if-else 链、末尾没有 else 兜底（已反编译确认）—— 收到不认识的 id 什么都不做，
 * 所以插进去不会打断它的分支逻辑，单选也仍由 RadioGroup 自己维护。
 *
 * <p>⚠️ 2026-09-25 晚：曾经还挂过「应用小窗」（把别的 App 嵌进卡片，含
 * {@code CardAppEntry} / {@code AppWindowTest} 与三条签名权限）—— 用户决定不做，
 * **已全部移除**。相关做法留在文档 §16 里备查。
 */
public final class LanternEntry {

    private static final String SETTING_ACTIVITY =
            "com.deepalhome.launcher.settings.SettingActivity";

    /** 设置页左侧单选组的 id（settings_view 里是 id/radioGroup） */
    private static final int ID_RADIO_GROUP_FALLBACK = 0x7f0b0551;
    /** 设置页右侧内容容器（id/settingsViewContainer，FrameLayout） */
    private static final int ID_CONTENT_FALLBACK = 0x7f0b05f2;

    private static final String TAG_ITEM = "cardash_entry_item";
    private static final String TAG_PANEL = "cardash_entry_panel";

    /** 自留 id 段，避开原厂资源 id */
    private static final int ID_HUD = 0x0C4D0001;
    private static final int ID_LANTERN = 0x0C4D0002;

    /** 面板种类（showPanel 用） */
    private static final int KIND_HUD = 1;
    private static final int KIND_LANTERN = 2;

    /** 用户要求移除的原厂侧栏项（按文字匹配；基座改了文字就自动跳过，不会出错） */
    private static final String[] REMOVE_ORIGINAL = {"蓝牙设备", "关于我们"};

    private static volatile boolean installed;

    private LanternEntry() {
    }

    /** 桌面进程启动时调用一次（BootProvider → 这里）。任何异常都不影响原车功能。 */
    public static void install(Context context) {
        if (context == null || installed) return;
        Application app;
        try {
            app = (Application) context.getApplicationContext();
        } catch (Throwable t) {
            return;
        }
        if (app == null) return;
        installed = true;

        // ① 保活：氛围灯跟着桌面进程常驻（进程活着就保持 BLE 连接与渐变播放）
        try {
            com.magiclantern.rgb.LanternBootstrap.onAmapStart(app);
            Diagnostics.log("氛围灯保活已挂上（跟随桌面进程）");
        } catch (Throwable t) {
            Diagnostics.log("氛围灯保活启动失败: " + t);
        }

        // ①b HUD 红绿灯运行时：注册高德红绿灯广播 + HUD 副屏窗口管理（同样跟桌面进程）
        try {
            com.s05.hudtraffic.HudRuntime.bootstrap(app);
            Diagnostics.log("HUD 红绿灯运行时已挂上（跟随桌面进程）");
        } catch (Throwable t) {
            Diagnostics.log("HUD 红绿灯运行时启动失败: " + t);
        }

        // ② 设置页入口
        try {
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityResumed(Activity activity) {
                    try {
                        if (SETTING_ACTIVITY.equals(activity.getClass().getName())) {
                            attach(activity);
                        }
                    } catch (Throwable t) {
                        Diagnostics.log("桌面设置入口挂载失败: " + t);
                    }
                }

                @Override
                public void onActivityCreated(Activity activity, Bundle b) {
                }

                @Override
                public void onActivityStarted(Activity activity) {
                }

                @Override
                public void onActivityPaused(Activity activity) {
                }

                @Override
                public void onActivityStopped(Activity activity) {
                }

                @Override
                public void onActivitySaveInstanceState(Activity activity, Bundle b) {
                }

                @Override
                public void onActivityDestroyed(Activity activity) {
                }
            });
            Diagnostics.log("桌面设置入口已注册");
        } catch (Throwable t) {
            Diagnostics.log("桌面设置入口注册失败: " + t);
        }
    }

    // ─────────────────────────────── 设置页挂载

    private static void attach(Activity a) {
        RadioGroup group = (RadioGroup) a.findViewById(
                id(a, "radioGroup", ID_RADIO_GROUP_FALLBACK));
        ViewGroup container = (ViewGroup) a.findViewById(
                id(a, "settingsViewContainer", ID_CONTENT_FALLBACK));
        if (group == null || container == null) return;

        // 已经插过（同一份视图只插一次）
        if (group.findViewWithTag(TAG_ITEM) != null) return;

        // 用户要求：先移除「蓝牙设备」「关于我们」，我们的两项自然补位到列表尾部
        removeOriginalItems(group);

        // 顺序即侧栏顺序：HUD 红绿灯在上，氛围灯设置在下
        group.addView(makeItem(a, group, container, "HUD 红绿灯", ID_HUD, KIND_HUD,
                Res.ic_light));
        group.addView(makeItem(a, group, container, "氛围灯设置", ID_LANTERN, KIND_LANTERN,
                Res.ic_palette));
        Diagnostics.log("桌面设置入口已插入（" + group.getChildCount() + " 项，已移除原厂 "
                + REMOVE_ORIGINAL.length + " 项中的匹配项）");
    }

    /** 把用户点名的两个原厂项从侧栏摘掉（按文字匹配；找不到就跳过，绝不出错）。 */
    private static void removeOriginalItems(RadioGroup group) {
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View v = group.getChildAt(i);
            if (!(v instanceof TextView)) continue;
            CharSequence t = ((TextView) v).getText();
            if (t == null) continue;
            for (String name : REMOVE_ORIGINAL) {
                if (name.contentEquals(t)) {
                    group.removeViewAt(i);
                    Diagnostics.log("已从桌面设置侧栏移除原厂项：" + name);
                    break;
                }
            }
        }
    }

    private static RadioButton makeItem(Context c, RadioGroup group, final ViewGroup container,
                                        String label, int id, final int kind, int iconKey) {
        final RadioButton item = buildItem(c, group, label, iconKey);
        item.setId(id);
        item.setTag(TAG_ITEM);
        item.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (!isChecked) return;
                showPanel(buttonView.getContext(), container, kind);
            }
        });
        return item;
    }

    /** 复刻旁边原厂项的外观，只换文字和图标 —— 看起来就是原生的一项。 */
    private static RadioButton buildItem(Context c, RadioGroup group, String label, int iconKey) {
        View template = null;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View v = group.getChildAt(i);
            if (v instanceof CompoundButton) {
                template = v;
                break;
            }
        }
        TextView tmpl = template instanceof TextView ? (TextView) template : null;

        RadioButton item = new RadioButton(c);
        item.setText(label);
        item.setButtonDrawable(null);       // 原厂这几项也没有左边的圆点
        item.setSingleLine(true);

        if (tmpl != null) {
            item.setTextSize(TypedValue.COMPLEX_UNIT_PX, tmpl.getTextSize());
            item.setTextColor(tmpl.getTextColors());
            item.setTypeface(tmpl.getTypeface());
            item.setGravity(tmpl.getGravity());
            item.setPadding(tmpl.getPaddingLeft(), tmpl.getPaddingTop(),
                    tmpl.getPaddingRight(), tmpl.getPaddingBottom());
            item.setCompoundDrawablePadding(tmpl.getCompoundDrawablePadding());

            Drawable bg = tmpl.getBackground();
            if (bg != null) {
                Drawable.ConstantState cs = bg.getConstantState();
                item.setBackground(cs != null ? cs.newDrawable() : bg);
            }
            // 左侧图标：尺寸完全跟着原厂项来（用户反馈"只有文字、看着突兀"就是这里）
            item.setCompoundDrawables(entryIcon(c, tmpl, iconKey), null, null, null);
        }

        if (template != null && template.getLayoutParams() != null) {
            item.setLayoutParams(new LinearLayout.LayoutParams(template.getLayoutParams()));
        } else {
            item.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return item;
    }

    /**
     * 入口项前面的图标（氛围灯 = 调色盘，HUD 红绿灯 = 灯泡）。
     *
     * <p>两点讲究：
     * <ol>
     *   <li>**尺寸必须跟原厂项一致** —— 直接量旁边那一项左侧图标的 intrinsic 尺寸，
     *       按同样的 bounds 贴上去；量不到就按 24dp 兜底。</li>
     *   <li>**必须保证有图标** —— 万一内置的 vector 解析失败（`Res.get` 返回 null，
     *       比如某台车机的框架不支持），就用代码画一个彩色圆点顶上。
     *       只有文字没有图标，跟旁边那几项一比就很突兀。</li>
     * </ol>
     */
    private static Drawable entryIcon(Context c, TextView template, int iconKey) {
        Drawable d = Res.get(c, iconKey);
        if (d != null) {
            // vector 是纯色路径，跟文字同色才和原厂图标观感一致
            d.setTintList(template.getTextColors());
        } else {
            // 兜底：代码画的紫色渐变圆点（和"灯光"这个意象对得上）
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(0xFF7B5CFF);
            g.setStroke(Math.max(1, (int) (1 * c.getResources().getDisplayMetrics().density)),
                    0x66FFFFFF);
            d = g;
        }

        int w = 0;
        int h = 0;
        if (template != null) {
            Drawable[] ds = template.getCompoundDrawables();
            if (ds != null && ds[0] != null) {
                w = ds[0].getIntrinsicWidth();
                h = ds[0].getIntrinsicHeight();
            }
        }
        if (w <= 0 || h <= 0) {
            int px = (int) (24 * c.getResources().getDisplayMetrics().density + 0.5f);
            w = h = px;
        }
        d.setBounds(0, 0, w, h);
        return d;
    }

    /**
     * 把对应面板放进设置页右侧容器（原厂容器是 FrameLayout）。
     *
     * @param kind {@link #KIND_HUD} 或 {@link #KIND_LANTERN}
     */
    private static void showPanel(Context c, ViewGroup container, int kind) {
        // 已经是同一种面板就别重建；不是的话把旧的摘掉再放新的（两个面板可以互相切）
        View existing = container.findViewWithTag(TAG_PANEL);
        if (existing != null) {
            boolean same = kind == KIND_HUD ? (existing instanceof HudPanel)
                    : (existing instanceof LanternPanel);
            if (same) return;
            container.removeView(existing);
        }

        Activity host = activityOf(c);
        if (host == null) {
            Diagnostics.log("面板：找不到宿主 Activity，放弃显示");
            return;
        }

        // 打开面板前先看一眼宿主背景：亮/暗主题 + 底色都从这里来
        //（面板底色 = 采到的宿主背景色，保证「和 D 桌面背景一样」）
        PanelTheme.resolve(container);

        int w = container.getWidth();
        int h = container.getHeight();
        // 右边这块只有整屏的一部分，按容器尺寸算缩放（按整屏算会溢出）
        Ui.setScaleForContainer(c, w, h);

        try {
            View panel;
            if (kind == KIND_HUD) {
                HudPanel hud = new HudPanel(host);
                panel = hud;
                container.removeAllViews();
                container.addView(panel, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                Diagnostics.log("HUD 红绿灯面板已打开（容器 " + w + "x" + h + "px）");
            } else {
                LanternPanel lantern = new LanternPanel(host, true);
                panel = lantern;
                container.removeAllViews();
                container.addView(panel, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                Diagnostics.log("氛围灯面板已打开（容器 " + w + "x" + h + "px）");
            }
            panel.setTag(TAG_PANEL);
        } catch (Throwable t) {
            Ui.clearScaleOverride();
            Diagnostics.log("面板打开失败(kind=" + kind + "): " + t);
        }
    }

    private static Activity activityOf(Context c) {
        Activity a = null;
        try {
            if (c instanceof Activity) {
                a = (Activity) c;
            } else if (c instanceof android.content.ContextWrapper) {
                Context base = ((android.content.ContextWrapper) c).getBaseContext();
                if (base instanceof Activity) a = (Activity) base;
            }
        } catch (Throwable t) {
            // 忽略
        }
        // LanternPanel 需要 Activity（申请权限 / 弹对话框），找不到就宁可不显示。
        return a;
    }

    /** 先按资源名取（跨版本最稳），取不到再用当前基座的资源 id 兜底。 */
    private static int id(Context c, String name, int fallback) {
        try {
            int v = c.getResources().getIdentifier(name, "id", c.getPackageName());
            if (v != 0) return v;
        } catch (Throwable t) {
            // 忽略
        }
        return fallback;
    }
}
