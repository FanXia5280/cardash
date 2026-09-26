package com.s05.hudtraffic;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.cardash.inject.PanelTheme;

/**
 * 「桌面设置 → HUD 红绿灯」的内嵌设置面板（塞在设置页右侧容器里的那块 View）。
 *
 * <p>对应独立版里 {@code MainActivity} 的「控制 + 预览」部分，砍掉了：
 * 运行状态大卡（面向开发者）、日志卡、权限卡（桌面是系统应用，悬浮窗天然可用）、
 * 保活四件套（桌面进程本身不会死）、悬浮球（入口就是设置页自己）。</p>
 *
 * <p>数据链路不在本类里 —— 广播注册 / HUD 窗口生命周期都在
 * {@link HudRuntime}（跟随桌面进程），本面板只负责「看 + 调」：
 * 面板关掉了 HUD 显示也不停（那是给驾驶员用的）。</p>
 *
 * <p>配色走 {@link PanelTheme}：底色 = 宿主（桌面设置页）的背景色，
 * 亮/暗主题自动跟随 —— 与氛围灯面板同一套规则。
 * 中间那块「实时预览」故意保持黑底：它是 HUD 玻璃上实际观感的等比预览。</p>
 */
public class HudPanel extends FrameLayout implements TrafficLightBus.Listener {

    private final Context c;
    private final float d;                  // density（不乘全局缩放，HUD 预览要所见即所得）

    private TrafficLightPanel preview;
    private TextView tvStatus;
    private TextView tvPosLabel;
    private Switch swHud;
    private Switch swTestPattern;
    private SeekBar sbX;
    private SeekBar sbY;
    private SeekBar sbScale;
    private boolean updatingUi;             // 程序化 setProgress 时别反向触发保存
    private long lastStatusAt;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable statusTicker = new Runnable() {
        @Override
        public void run() {
            refreshStatusLine();
            handler.postDelayed(this, 1000L);
        }
    };

    public HudPanel(Context context) {
        super(context);
        c = context;
        d = context.getResources().getDisplayMetrics().density;
        build();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        TrafficLightBus.addListener(this);
        preview.render(TrafficLightBus.getLatest());
        handler.removeCallbacks(statusTicker);
        handler.post(statusTicker);
        refreshStatusLine();
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(statusTicker);
        TrafficLightBus.removeListener(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void onTrafficLightChanged(TrafficLightState state) {
        if (preview != null) {
            preview.render(state);
        }
        refreshStatusLine();
    }

    // ---------------- 界面构建 ----------------

    private int dp(float v) {
        return Math.round(v * d);
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        if (bold) {
            tv.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return tv;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PanelTheme.cardBg());
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), PanelTheme.cardStroke());
        card.setBackground(bg);
        int p = dp(14);
        card.setPadding(p, p, p, p);
        return card;
    }

    private void add(LinearLayout parent, View v, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        parent.addView(v, lp);
    }

    private LinearLayout hrow() {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    /** 往水平行里放一个按钮（平分宽度，第二个起留出左边距）。 */
    private TextView rowButton(LinearLayout row, String label, View.OnClickListener l, int leftDp) {
        TextView b = text(label, 13, 0xFFFFFFFF, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PanelTheme.brandBlue());
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        b.setOnClickListener(l);
        b.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (row.getChildCount() > 0) {
            lp.leftMargin = dp(leftDp);
        }
        row.addView(b, lp);
        return b;
    }

    private TextView sliderLabel(String s) {
        return text(s, 12, PanelTheme.textSecondary(), false);
    }

    private SeekBar slider(int progress, SeekBar.OnSeekBarChangeListener l) {
        SeekBar sb = new SeekBar(c);
        sb.setMax(100);
        sb.setProgress(Math.max(0, Math.min(progress, 100)));
        sb.setOnSeekBarChangeListener(l);
        return sb;
    }

    private void build() {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ── 标题 ──
        LinearLayout titleRow = hrow();
        TextView title = text("HUD 红绿灯", 22, PanelTheme.textPrimary(), true);
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView tag = text("高德倒计时 → 车机 HUD", 12, PanelTheme.brandBlue(), true);
        titleRow.addView(tag);
        root.addView(titleRow);

        tvStatus = text("状态：-", 12, PanelTheme.textSecondary(), false);
        add(root, tvStatus, 6);

        // ── 实时预览（与 HUD 副屏同一套渲染代码，所见即所得） ──
        LinearLayout previewCard = card();
        previewCard.addView(text("实时预览（黑底 = HUD 玻璃上的实际观感）",
                13, PanelTheme.textPrimary(), true));
        FrameLayout previewBox = new FrameLayout(c);
        previewBox.setBackgroundColor(0xFF0E1116);
        previewBox.setPadding(dp(10), dp(10), dp(10), dp(10));
        previewBox.setClipChildren(false);
        previewBox.setClipToPadding(false);
        preview = new TrafficLightPanel(c);
        previewBox.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        add(previewCard, previewBox, 10);
        add(root, previewCard, 12);

        // ── HUD 显示控制 ──
        LinearLayout ctl = card();
        ctl.addView(text("HUD 显示", 13, PanelTheme.brandBlue(), true));

        swHud = new Switch(c);
        swHud.setText("把红绿灯倒计时显示到 HUD 副屏");
        swHud.setTextColor(PanelTheme.textPrimary());
        swHud.setChecked(Prefs.isHudEnabled(c));
        swHud.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                Prefs.setHudEnabled(c, on);
                HudRuntime.refresh(c);
                refreshStatusLine();
                toast(on ? "已开启 HUD 显示" : "已关闭 HUD 显示");
            }
        });
        add(ctl, swHud, 8);

        tvPosLabel = sliderLabel(posText());
        add(ctl, tvPosLabel, 10);

        add(ctl, sliderLabel("横向位置（0 = 最左，100 = 最右）"), 6);
        sbX = slider(Prefs.getHudXPercent(c), new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser || updatingUi) {
                    return;
                }
                tvPosLabel.setText(posText(p, sbY.getProgress()));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                Prefs.setHudXPercent(c, s.getProgress());
                HudRuntime.refresh(c);
            }
        });
        add(ctl, sbX, 2);

        add(ctl, sliderLabel("纵向位置（0 = 最上，100 = 最下）"), 8);
        sbY = slider(Prefs.getHudYPercent(c), new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser || updatingUi) {
                    return;
                }
                tvPosLabel.setText(posText(sbX.getProgress(), p));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                Prefs.setHudYPercent(c, s.getProgress());
                HudRuntime.refresh(c);
            }
        });
        add(ctl, sbY, 2);

        add(ctl, sliderLabel("HUD 缩放（50% ~ 150%）"), 8);
        sbScale = slider(Math.max(0, Prefs.getScale(c) - 50), new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                Prefs.setScale(c, s.getProgress() + 50);
                HudRuntime.refresh(c);
            }
        });
        add(ctl, sbScale, 2);

        swTestPattern = new Switch(c);
        swTestPattern.setText("定位测试图案（HUD 上画大十字，对完位置记得关）");
        swTestPattern.setTextColor(PanelTheme.textPrimary());
        swTestPattern.setChecked(Prefs.isTestPattern(c));
        swTestPattern.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                Prefs.setTestPattern(c, on);
                HudRuntime.refresh(c);
            }
        });
        add(ctl, swTestPattern, 10);

        LinearLayout wakeRow = hrow();
        rowButton(wakeRow, "点亮 HUD 屏幕", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 车机在退出导航后会把 HUD 熄掉：手动补一次完整开屏指令
                S05HudWakeHelper.setAppContext(c.getApplicationContext());
                S05HudWakeHelper.fullWakeHud(c.getApplicationContext());
                HudRuntime.refresh(c);
                toast("已发送 HUD 开屏指令");
            }
        }, 0);
        rowButton(wakeRow, "恢复默认位置", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs.resetHudDefaults(c);
                syncFromPrefs();
                HudRuntime.refresh(c);
                toast("已恢复默认设置");
            }
        }, 8);
        add(ctl, wakeRow, 12);
        add(root, ctl, 12);

        // ── 测试注入（不用真的导航也能看效果） ──
        LinearLayout test = card();
        test.addView(text("测试注入（没在导航时验证显示用）", 13, PanelTheme.brandBlue(), true));
        LinearLayout testRow1 = hrow();
        rowButton(testRow1, "直行绿灯 12s", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                inject(TrafficLightState.DIR_STRAIGHT, TrafficLightState.ST_GREEN, 12);
            }
        }, 0);
        rowButton(testRow1, "左转红灯 8s", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                inject(TrafficLightState.DIR_LEFT, TrafficLightState.ST_ABOUT_RED, 8);
            }
        }, 8);
        add(test, testRow1, 8);
        LinearLayout testRow2 = hrow();
        rowButton(testRow2, "直行+左转 双灯", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                injectBoth();
            }
        }, 0);
        rowButton(testRow2, "清除", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TrafficLightState s = TrafficLightBus.getLatest().copy();
                s.clearLights();
                s.source = "panel";
                s.updateTime = System.currentTimeMillis();
                TrafficLightBus.publish(s);
            }
        }, 8);
        add(test, testRow2, 8);
        add(root, test, 12);

        TextView tip = text("红绿灯数据来自高德导航/巡航广播（KEY_TYPE=60073）；"
                + "没在导航时预览是空的属正常。",
                11, PanelTheme.textThird(), false);
        add(root, tip, 4);
    }

    private String posText() {
        return posText(Prefs.getHudXPercent(c), Prefs.getHudYPercent(c));
    }

    private String posText(int x, int y) {
        return "HUD 位置：" + x + "% / " + y + "%   →   " + HudDisplayHelper.HUD_WIDTH
                + "x" + HudDisplayHelper.HUD_HEIGHT + " 副屏上的 ("
                + Math.round(x / 100f * HudDisplayHelper.HUD_WIDTH) + ", "
                + Math.round(y / 100f * HudDisplayHelper.HUD_HEIGHT) + ")";
    }

    private void toast(String s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }

    // ---------------- 测试注入 ----------------

    private void inject(int dir, int status, int countdown) {
        TrafficLightState s = TrafficLightBus.getLatest().copy();
        long now = System.currentTimeMillis();
        s.applyLight(dir, status, countdown, 0, 0, now);
        s.routeLightNum = 3;
        s.source = "panel-test";
        s.updateTime = now;
        TrafficLightBus.publish(s);
    }

    private void injectBoth() {
        TrafficLightState s = TrafficLightBus.getLatest().copy();
        long now = System.currentTimeMillis();
        s.applyLight(TrafficLightState.DIR_STRAIGHT, TrafficLightState.ST_GREEN, 12, 0, 0, now);
        s.applyLight(TrafficLightState.DIR_LEFT, TrafficLightState.ST_ABOUT_GREEN, 8, 0, 0, now);
        s.routeLightNum = 3;
        s.source = "panel-test";
        s.updateTime = now;
        TrafficLightBus.publish(s);
    }

    // ---------------- 状态行 ----------------

    private void refreshStatusLine() {
        if (tvStatus == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastStatusAt < 500L) {
            return;   // 广播风暴时别每条都刷 UI
        }
        lastStatusAt = now;
        boolean showing = HudTrafficManager.get().isShowing();
        TrafficLightState s = TrafficLightBus.getLatest();
        String data;
        if (s == null || s.updateTime == 0) {
            data = "还没有收到红绿灯数据";
        } else {
            long age = (System.currentTimeMillis() - s.updateTime) / 1000L;
            data = age <= 2 ? "数据正常更新中" : "数据 " + age + " 秒前更新过";
        }
        String msg = HudTrafficManager.get().getLastMessage();
        tvStatus.setText("状态：" + (showing ? "HUD 显示中" : "HUD 未显示")
                + "（" + msg + "）\n" + data);
    }

    /** 读一遍 Prefs，把滑块/开关刷成当前配置（恢复默认后用）。 */
    public void syncFromPrefs() {
        updatingUi = true;
        try {
            if (swHud != null) {
                swHud.setChecked(Prefs.isHudEnabled(c));
            }
            if (swTestPattern != null) {
                swTestPattern.setChecked(Prefs.isTestPattern(c));
            }
            if (sbX != null) {
                sbX.setProgress(Prefs.getHudXPercent(c));
            }
            if (sbY != null) {
                sbY.setProgress(Prefs.getHudYPercent(c));
            }
            if (sbScale != null) {
                sbScale.setProgress(Math.max(0, Prefs.getScale(c) - 50));
            }
            if (tvPosLabel != null) {
                tvPosLabel.setText(posText());
            }
        } finally {
            updatingUi = false;
        }
    }
}
