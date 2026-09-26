package com.cardash.inject;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.s05.hudtraffic.HudMainView;
import com.s05.hudtraffic.HudRuntime;

/**
 * 往车机桌面的「桌面设置」里挂一项「HUD 红绿灯」（在氛围灯下面，纯运行时挂载）。
 *
 * <p>做法与 {@link LanternEntry} 完全一致：跟着桌面进程启动（{@code BootProvider} →
 * {@code install}），监听设置页 {@code SettingActivity} 打开，往左边的 RadioGroup 末尾
 * 插一项，点它就把 {@link HudMainView} 放进右边的内容容器 {@code settingsViewContainer}；
 * 点回原厂项时原厂自己 removeAllViews 把面板摘掉。</p>
 *
 * <p>同时启动 {@link HudRuntime} —— HUD 的红绿灯接收 / 副屏叠加 / 看门狗跟着桌面进程常驻，
 * 不用单独装 apk，只要 D+ 桌面在运行，HUD 显示就在运行（这就是用户要的"保活"）。</p>
 */
public final class HudEntry {

    private static final String SETTING_ACTIVITY =
            "com.deepalhome.launcher.settings.SettingActivity";

    /** 设置页左侧单选组的 id（settings_view 里是 id/radioGroup） */
    private static final int ID_RADIO_GROUP_FALLBACK = 0x7f0b0551;
    /** 设置页右侧内容容器（id/settingsViewContainer，FrameLayout） */
    private static final int ID_CONTENT_FALLBACK = 0x7f0b05f2;

    private static final String TAG_ITEM = "cardash_hud_entry_item";
    private static final String TAG_PANEL = "cardash_hud_entry_panel";

    /** 自留 id 段（氛围灯用 0x0C4D0001，这里用下一个） */
    private static final int ID_HUD = 0x0C4D0002;

    private static volatile boolean installed;

    private HudEntry() {
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

        // ① 保活：HUD 运行时跟着桌面进程常驻（收高德广播 + 画 HUD 副屏 + 看门狗）
        try {
            HudRuntime.bootstrap(app);
            Diagnostics.log("HUD 红绿灯保活已挂上（跟随桌面进程）");
        } catch (Throwable t) {
            Diagnostics.log("HUD 红绿灯保活启动失败: " + t);
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
                        Diagnostics.log("HUD 桌面设置入口挂载失败: " + t);
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
            Diagnostics.log("HUD 桌面设置入口已注册");
        } catch (Throwable t) {
            Diagnostics.log("HUD 桌面设置入口注册失败: " + t);
        }
    }

    // ─────────────────────────────── 设置页挂载

    private static void attach(Activity a) {
        RadioGroup group = (RadioGroup) a.findViewById(
                id(a, "radioGroup", ID_RADIO_GROUP_FALLBACK));
        ViewGroup container = (ViewGroup) a.findViewById(
                id(a, "settingsViewContainer", ID_CONTENT_FALLBACK));
        if (group == null || container == null) return;

        if (group.findViewWithTag(TAG_ITEM) != null) return;

        group.addView(makeItem(a, group, container));
        Diagnostics.log("HUD 红绿灯入口已插入（" + group.getChildCount() + " 项）");
    }

    private static RadioButton makeItem(Context c, RadioGroup group, final ViewGroup container) {
        final RadioButton item = buildItem(c, group, "HUD 红绿灯");
        item.setId(ID_HUD);
        item.setTag(TAG_ITEM);
        item.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (!isChecked) return;
                showPanel(buttonView.getContext(), container);
            }
        });
        return item;
    }

    /** 复刻旁边原厂项的外观，只换文字和图标。 */
    private static RadioButton buildItem(Context c, RadioGroup group, String label) {
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
        item.setButtonDrawable(null);
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
            item.setCompoundDrawables(hudIcon(c, tmpl), null, null, null);
        }

        if (template != null && template.getLayoutParams() != null) {
            item.setLayoutParams(new LinearLayout.LayoutParams(template.getLayoutParams()));
        } else {
            item.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return item;
    }

    /** 「HUD 红绿灯」前面的图标：代码画的红绿灯（竖排三灯），尺寸跟着原厂项来。 */
    private static Drawable hudIcon(Context c, TextView template) {
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

        // 圆角矩形底（红绿灯灯箱）
        GradientDrawable box = new GradientDrawable();
        box.setShape(GradientDrawable.RECTANGLE);
        box.setCornerRadius(Math.max(1, w * 0.22f));
        box.setColor(0xFF1B222A);
        box.setStroke(Math.max(1, (int) (1 * c.getResources().getDisplayMetrics().density)),
                0x33FFFFFF);

        // 三灯（红/黄/绿）竖排，每个占约 1/3
        int lamp = Math.round(h / 3f);
        GradientDrawable red = oval(0xFFE53935);
        GradientDrawable yellow = oval(0xFFFDD835);
        GradientDrawable green = oval(0xFF43A047);

        LayerDrawable layer = new LayerDrawable(new Drawable[]{box, red, yellow, green});
        layer.setLayerInset(0, 0, 0, 0, 0);                       // 灯箱铺满
        layer.setLayerInset(1, w / 4, 1, w / 4, h - lamp - 1);   // 红在顶
        layer.setLayerInset(2, w / 4, (h - lamp) / 2, w / 4, (h - lamp) / 2); // 黄居中
        layer.setLayerInset(3, w / 4, h - lamp - 1, w / 4, 1);   // 绿在底

        layer.setBounds(0, 0, w, h);
        return layer;
    }

    private static GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    /** 把 HUD 面板放进设置页右侧容器（原厂容器是 FrameLayout）。 */
    private static void showPanel(Context c, ViewGroup container) {
        if (container.findViewWithTag(TAG_PANEL) != null) return;

        Activity host = activityOf(c);
        if (host == null) {
            Diagnostics.log("HUD 红绿灯面板：找不到宿主 Activity，放弃显示");
            return;
        }

        try {
            HudMainView panel = new HudMainView(host);
            panel.setTag(TAG_PANEL);
            container.removeAllViews();
            container.addView(panel, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            Diagnostics.log("HUD 红绿灯面板已打开");
        } catch (Throwable t) {
            Diagnostics.log("HUD 红绿灯面板打开失败: " + t);
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
