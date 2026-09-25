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

/**
 * 往车机桌面的「桌面设置」里挂一项「氛围灯设置」（不改原包任何 smali / 资源，纯运行时挂载）。
 *
 * <p>做法：跟着桌面进程启动（{@code BootProvider} → {@code install}），
 * 监听设置页 {@code SettingActivity} 打开，往左边的 RadioGroup 末尾插一项
 * （**外观照着旁边原厂项复刻**：字号/颜色/内边距/背景/图标尺寸全部照抄），
 * 点它就把 {@link LanternPanel} 放进右边的内容容器 {@code settingsViewContainer}；
 * 点回原厂其它项时原厂自己 removeAllViews 会把我们的面板摘掉，
 * 面板的 onDetachedFromWindow 负责解绑监听与广播（蓝牙连接与渐变播放不动 —— 那是保活的活）。
 *
 * <p>为什么敢往 RadioGroup 里插：原厂的 OnCheckedChangeListener 是一条
 * if-else 链、末尾没有 else 兜底（已反编译确认）—— 收到不认识的 id 什么都不做，
 * 所以插进去不会打断它的分支逻辑，单选也仍由 RadioGroup 自己维护。
 *
 * <p>⚠️ 2026-09-25 晚：曾经还挂过「应用小窗」（把别的 App 嵌进卡片，含
 * {@code CardAppEntry} / {@code AppWindowTest} 与三条签名权限）—— 用户决定不做，
 * **已全部移除**，这里回到只挂氛围灯一项。相关做法留在文档 §16 里备查。
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
    private static final int ID_LANTERN = 0x0C4D0001;

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

        group.addView(makeItem(a, group, container));
        Diagnostics.log("桌面设置入口已插入（" + group.getChildCount() + " 项）");
    }

    private static RadioButton makeItem(Context c, RadioGroup group, final ViewGroup container) {
        final RadioButton item = buildItem(c, group, "氛围灯设置");
        item.setId(ID_LANTERN);
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

    /** 复刻旁边原厂项的外观，只换文字和图标 —— 看起来就是原生的一项。 */
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
            item.setCompoundDrawables(lanternIcon(c, tmpl), null, null, null);
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
     * 「氛围灯设置」前面那个图标。
     *
     * <p>两点讲究：
     * <ol>
     *   <li>**尺寸必须跟原厂项一致** —— 直接量旁边那一项左侧图标的 intrinsic 尺寸，
     *       按同样的 bounds 贴上去；量不到就按 24dp 兜底。</li>
     *   <li>**必须保证有图标** —— 万一内置的 vector 解析失败（`Res.get` 返回 null，
     *       比如某台车机的框架不支持），就用代码画一个紫色渐变圆点顶上。
     *       只有文字没有图标，跟旁边那几项一比就很突兀。</li>
     * </ol>
     */
    private static Drawable lanternIcon(Context c, TextView template) {
        Drawable d = Res.get(c, Res.ic_palette);
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

    /** 把氛围灯面板放进设置页右侧容器（原厂容器是 FrameLayout）。 */
    private static void showPanel(Context c, ViewGroup container) {
        if (container.findViewWithTag(TAG_PANEL) != null) return;

        Activity host = activityOf(c);
        if (host == null) {
            Diagnostics.log("氛围灯面板：找不到宿主 Activity，放弃显示");
            return;
        }

        int w = container.getWidth();
        int h = container.getHeight();
        // 右边这块只有整屏的一部分，按容器尺寸算缩放（按整屏算会溢出）
        Ui.setScaleForContainer(c, w, h);

        try {
            LanternPanel panel = new LanternPanel(host, true);
            panel.setTag(TAG_PANEL);
            container.removeAllViews();
            container.addView(panel, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            Diagnostics.log("氛围灯面板已打开（容器 " + w + "x" + h + "px）");
        } catch (Throwable t) {
            Ui.clearScaleOverride();
            Diagnostics.log("氛围灯面板打开失败: " + t);
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
