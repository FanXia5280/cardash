package com.s05.hudtraffic;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * HUD 红绿灯的内置版主界面（嵌在桌面设置右侧容器里的面板，不是 Activity）。
 *
 * <p>从独立版的 {@link MainActivity} 精简而来：去掉悬浮球、关于、代理桌面保活、
 * 「返回高德地图」这些只有独立 App 才需要的东西；保留 HUD 预览、位置/缩放调节、
 * 模拟数据源、运行状态与日志。界面纯代码构建，不依赖任何 res / assets。</p>
 *
 * <p>核心链路与独立版共用：{@link AmapTrafficReceiver} → {@link TrafficLightBus} →
 * {@link HudTrafficManager}（画 HUD 副屏），运行时由 {@link HudRuntime} 常驻驱动。</p>
 */
public class HudMainView extends FrameLayout
        implements TrafficLightBus.Listener, HudTrafficManager.Listener, AppLog.Sink {

    private static final long HEAVY_REFRESH_MS = 2000L;
    private static final int LOG_TEXT_MAX_CHARS = 15000;
    private static final long LOG_SCROLL_MIN_MS = 400L;

    private static final int C_BG = 0xFF0E1216;
    private static final int C_CARD = 0xFF19202A;
    private static final int C_BRAND = 0xFF00B0FF;
    private static final int C_TEXT = 0xFFECEFF1;
    private static final int C_TEXT_DIM = 0xFF8FA3B0;

    private final Context mCtx;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView tvHudStatus;
    private TextView tvDisplays;
    private TextView tvPerm;
    private TextView tvData;
    private TextView tvLog;
    private TextView tvScaleLabel;
    private TextView tvPosLabel;
    private TextView tvWidgetSel;
    private Switch swWidgetShow;
    private Switch swDragWhole;
    private FrameLayout previewBox;
    private Switch swHud;
    private Switch swSimulate;
    private Switch swMirror;
    private SeekBar sbScale;
    private SeekBar sbPosX;
    private SeekBar sbPosY;
    private ScrollView svLog;
    private HudPreviewView previewView;
    private Switch swPreviewDemo;

    private int hudSizeX = 800;
    private int hudSizeY = 480;
    private float hudDensity = 1f;

    private long lastHeavyRefreshAt = 0L;
    private long lastLogScrollAt = 0L;

    private final Runnable periodic = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, 1000L);
        }
    };

    public HudMainView(Context context) {
        super(context);
        mCtx = context;
        setBackgroundColor(C_BG);
        addView(buildContentView(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildPreviewPanel();
        setupListeners();
        tvLog.setText(AppLog.dump());
        AppLog.i("UI", "HUD 面板已打开（内置版）");
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        AppLog.addSink(this);
        TrafficLightBus.addListener(this);
        HudTrafficManager.get().setAppContext(mCtx);
        HudTrafficManager.get().addListener(this);
        S05HudWakeHelper.setAppContext(mCtx);
        lastHeavyRefreshAt = 0L;
        refreshStatus();
        handler.removeCallbacks(periodic);
        handler.post(periodic);
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(periodic);
        AppLog.removeSink(this);
        TrafficLightBus.removeListener(this);
        HudTrafficManager.get().removeListener(this);
        super.onDetachedFromWindow();
    }

    /* ================= 界面构建 ================= */

    private int dp(float v) {
        return Math.round(v * mCtx.getResources().getDisplayMetrics().density);
    }

    private TextView mkText(String text, int color, float sizeSp, boolean bold) {
        TextView tv = new TextView(mCtx);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(sizeSp);
        tv.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.MONOSPACE);
        return tv;
    }

    private TextView mkTitle(String text) {
        return mkText(text, C_BRAND, 13, true);
    }

    private Switch mkSwitch(String text) {
        Switch s = new Switch(mCtx);
        s.setText(text);
        s.setTextColor(C_TEXT);
        s.setTextSize(13);
        return s;
    }

    private Button mkButton(String text) {
        Button b = new Button(mCtx);
        b.setText(text);
        b.setTextSize(13);
        return b;
    }

    private LinearLayout mkCard() {
        LinearLayout card = new LinearLayout(mCtx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(C_CARD);
        int p = dp(12);
        card.setPadding(p, p, p, p);
        return card;
    }

    private void addTo(LinearLayout parent, View child, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        parent.addView(child, lp);
    }

    private void addCard(LinearLayout col, LinearLayout card) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        col.addView(card, lp);
    }

    private View buildContentView() {
        ScrollView root = new ScrollView(mCtx);
        root.setFillViewport(true);
        root.setBackgroundColor(C_BG);

        LinearLayout col = new LinearLayout(mCtx);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        col.setPadding(p, p, p, p);
        root.addView(col, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        col.addView(mkText("HUD 红绿灯倒计时", C_TEXT, 20, true));
        addCard(col, buildPreviewCard());
        addCard(col, buildControlCard());
        addCard(col, buildStatusCard());
        addCard(col, buildLogCard());
        return root;
    }

    private LinearLayout buildPreviewCard() {
        LinearLayout c2 = mkCard();
        c2.addView(mkTitle("HUD 预览（按副屏等比缩放，所见即所得）"));
        addTo(c2, mkText("拖动面板调位置，松手同步到 HUD；黄框 = HUD 可见区", C_TEXT_DIM, 11, false), 2);
        swPreviewDemo = mkSwitch("预览用示例数据（关闭 = 显示真实/模拟数据）");
        addTo(c2, swPreviewDemo, 4);
        previewBox = new FrameLayout(mCtx);
        previewBox.setBackgroundColor(0xFF0E1116);
        previewBox.setClipChildren(false);
        previewBox.setClipToPadding(false);
        addTo(c2, previewBox, 8);
        return c2;
    }

    private LinearLayout buildControlCard() {
        LinearLayout c3 = mkCard();
        c3.addView(mkTitle("控制"));
        swHud = mkSwitch("开启 HUD 显示");
        addTo(c3, swHud, 6);
        swSimulate = mkSwitch("模拟数据源（模拟器无高德时演示用）");
        addTo(c3, swSimulate, 0);
        swMirror = mkSwitch("同时在主屏显示悬浮窗（调试/验证用）");
        addTo(c3, swMirror, 0);

        tvPosLabel = mkText("HUD 位置：88% / 53%", C_TEXT_DIM, 12, false);
        addTo(c3, tvPosLabel, 8);
        addTo(c3, mkText("横向位置", C_TEXT_DIM, 11, false), 4);
        sbPosX = new SeekBar(mCtx);
        sbPosX.setMax(100);
        addTo(c3, sbPosX, 0);
        addTo(c3, mkText("纵向位置", C_TEXT_DIM, 11, false), 0);
        sbPosY = new SeekBar(mCtx);
        sbPosY.setMax(100);
        addTo(c3, sbPosY, 0);
        tvScaleLabel = mkText("HUD 缩放：79%", C_TEXT_DIM, 12, false);
        addTo(c3, tvScaleLabel, 8);
        sbScale = new SeekBar(mCtx);
        sbScale.setMax(150);
        addTo(c3, sbScale, 0);

        tvWidgetSel = mkText("已选中：未选择（点预览里的控件）", C_TEXT_DIM, 12, false);
        addTo(c3, tvWidgetSel, 8);
        swWidgetShow = mkSwitch("隐藏选中控件");
        swWidgetShow.setEnabled(false);
        addTo(c3, swWidgetShow, 0);
        swDragWhole = mkSwitch("整体拖动（关闭 = 点选单个控件单独调整）");
        addTo(c3, swDragWhole, 4);

        Button reset = mkButton("恢复默认设置");
        addTo(c3, reset, 8);
        reset.setOnClickListener(v -> resetHudDefaults());
        return c3;
    }

    private LinearLayout buildStatusCard() {
        LinearLayout c1 = mkCard();
        c1.addView(mkTitle("运行状态"));
        tvHudStatus = mkText("HUD：未启动", C_TEXT, 13, false);
        addTo(c1, tvHudStatus, 6);
        tvDisplays = mkText("副屏：-", C_TEXT, 13, false);
        addTo(c1, tvDisplays, 4);
        tvPerm = mkText("悬浮窗权限：-", C_TEXT, 13, false);
        addTo(c1, tvPerm, 4);
        tvData = mkText("数据：-", C_TEXT, 13, false);
        addTo(c1, tvData, 4);
        return c1;
    }

    private LinearLayout buildLogCard() {
        LinearLayout c5 = mkCard();
        LinearLayout logRow = new LinearLayout(mCtx);
        logRow.setOrientation(LinearLayout.HORIZONTAL);
        logRow.addView(mkTitle("运行日志"), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button copy = mkButton("复制日志");
        Button clear = mkButton("清空");
        logRow.addView(copy);
        logRow.addView(clear);
        c5.addView(logRow);
        svLog = new ScrollView(mCtx);
        svLog.setBackgroundColor(0xFF05080C);
        tvLog = new TextView(mCtx);
        tvLog.setTextColor(C_TEXT_DIM);
        tvLog.setTextSize(11);
        tvLog.setPadding(dp(8), dp(8), dp(8), dp(8));
        svLog.addView(tvLog, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(160));
        slp.topMargin = dp(6);
        c5.addView(svLog, slp);

        clear.setOnClickListener(v -> {
            AppLog.clear();
            tvLog.setText("");
        });
        copy.setOnClickListener(v -> {
            CharSequence cs = tvLog.getText();
            String text = cs == null ? "" : cs.toString();
            if (text.length() == 0) {
                toast("日志为空");
                return;
            }
            if (copyText("hud-log", text)) {
                toast("日志已复制（" + text.length() + " 字）");
            }
        });
        return c5;
    }

    private void buildPreviewPanel() {
        previewView = new HudPreviewView(mCtx);
        previewView.setListener(new HudPreviewView.Listener() {
            @Override
            public void onPreviewPanelPos(int xPercent, int yPercent, boolean finished) {
                onPreviewDragged(xPercent, yPercent, finished);
            }

            @Override
            public void onWidgetSelected(int id) {
                HudMainView.this.onWidgetSelected(id);
            }

            @Override
            public void onWidgetMoved(int id, int xPercent, int yPercent, boolean finished) {
                syncWidgetSliders(id);
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        previewBox.addView(previewView, lp);
        previewView.setHudGeometry(hudSizeX, hudSizeY, hudDensity);
        previewView.setScale(Prefs.getScale(mCtx) / 100f);
        previewView.setPosition(Prefs.getHudXPercent(mCtx), Prefs.getHudYPercent(mCtx));
        previewView.setState(TrafficLightBus.getLatest());
    }

    private void setupListeners() {
        swHud.setChecked(Prefs.isHudEnabled(mCtx));
        swSimulate.setChecked(Prefs.isSimulate(mCtx));
        swMirror.setChecked(Prefs.isMirrorMain(mCtx));

        int percent = Prefs.getScale(mCtx);
        sbScale.setProgress(percent - 50);
        applyScale(percent);

        int xPct = Prefs.getHudXPercent(mCtx);
        int yPct = Prefs.getHudYPercent(mCtx);
        sbPosX.setProgress(xPct);
        sbPosY.setProgress(yPct);
        updatePosLabel(xPct, yPct);

        swHud.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Prefs.setHudEnabled(mCtx, isChecked);
            AppLog.i("UI", "HUD 显示开关 -> " + isChecked);
            if (isChecked) {
                HudRuntime.ensureAlive(mCtx);
                HudRuntime.refresh(mCtx);
            } else {
                HudTrafficManager.get().hide();
            }
            refreshStatus();
        });

        swPreviewDemo.setChecked(Prefs.isPreviewDemo(mCtx));
        previewView.setDemoMode(swPreviewDemo.isChecked());
        swPreviewDemo.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Prefs.setPreviewDemo(mCtx, isChecked);
            previewView.setDemoMode(isChecked);
        });

        swSimulate.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Prefs.setSimulate(mCtx, isChecked);
            AppLog.i("UI", "模拟数据源 -> " + isChecked);
            if (isChecked && swPreviewDemo.isChecked()) {
                swPreviewDemo.setChecked(false);
            }
            HudRuntime.ensureAlive(mCtx);
        });

        swMirror.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Prefs.setMirrorMain(mCtx, isChecked);
            AppLog.i("UI", "主屏镜像悬浮窗 -> " + isChecked);
            HudRuntime.ensureAlive(mCtx);
            HudRuntime.refresh(mCtx);
        });

        sbScale.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (selectedWidget() >= 0) {
                    applyWidgetScale(selectedWidget(), progress + 50);
                } else {
                    applyScale(progress + 50);
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (selectedWidget() >= 0) {
                    return;
                }
                Prefs.setScale(mCtx, seekBar.getProgress() + 50);
                HudRuntime.refresh(mCtx);
            }
        });

        sbPosX.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (selectedWidget() >= 0) {
                    if (fromUser) {
                        Prefs.setWidgetX(mCtx, selectedWidget(), progress);
                        HudTrafficManager.get().render(TrafficLightBus.getLatest());
                    }
                    return;
                }
                updatePosLabel(progress, sbPosY.getProgress());
                if (previewView != null) {
                    previewView.setPosition(progress, sbPosY.getProgress());
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (selectedWidget() >= 0) {
                    return;
                }
                Prefs.setHudXPercent(mCtx, seekBar.getProgress());
                HudRuntime.refresh(mCtx);
            }
        });

        sbPosY.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (selectedWidget() >= 0) {
                    if (fromUser) {
                        Prefs.setWidgetY(mCtx, selectedWidget(), progress);
                        HudTrafficManager.get().render(TrafficLightBus.getLatest());
                    }
                    return;
                }
                updatePosLabel(sbPosX.getProgress(), progress);
                if (previewView != null) {
                    previewView.setPosition(sbPosX.getProgress(), progress);
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (selectedWidget() >= 0) {
                    return;
                }
                Prefs.setHudYPercent(mCtx, seekBar.getProgress());
                HudRuntime.refresh(mCtx);
            }
        });

        swDragWhole.setChecked(Prefs.isDragWhole(mCtx));
        previewView.setDragWhole(Prefs.isDragWhole(mCtx));
        swDragWhole.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Prefs.setDragWhole(mCtx, isChecked);
            previewView.setDragWhole(isChecked);
            if (isChecked) {
                previewView.setSelectedWidget(-1);
            }
            onWidgetSelected(-1);
        });

        swWidgetShow.setOnCheckedChangeListener((buttonView, isChecked) -> {
            int id = selectedWidget();
            if (id >= 0) {
                Prefs.setWidgetEnabled(mCtx, id, !isChecked);
                HudTrafficManager.get().render(TrafficLightBus.getLatest());
            }
        });
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }
    }

    private void onPreviewDragged(int xPercent, int yPercent, boolean finished) {
        sbPosX.setProgress(xPercent);
        sbPosY.setProgress(yPercent);
        updatePosLabel(xPercent, yPercent);
        if (finished) {
            Prefs.setHudXPercent(mCtx, xPercent);
            Prefs.setHudYPercent(mCtx, yPercent);
            HudRuntime.refresh(mCtx);
        }
    }

    private int selectedWidget() {
        return previewView != null ? previewView.getSelectedWidget() : -1;
    }

    private void onWidgetSelected(int id) {
        if (tvWidgetSel != null) {
            tvWidgetSel.setText(id >= 0
                    ? "已选中：" + HudPanelRenderer.widgetName(id) + "（拖它可挪位置）"
                    : "未选中（点预览里的控件可单独调）");
        }
        if (swWidgetShow != null) {
            swWidgetShow.setEnabled(id >= 0);
            swWidgetShow.setChecked(id >= 0 && !Prefs.isWidgetEnabled(mCtx, id));
        }
        int x;
        int y;
        int sc;
        if (id >= 0) {
            x = Prefs.getWidgetX(mCtx, id);
            y = Prefs.getWidgetY(mCtx, id);
            sc = Prefs.getWidgetScale(mCtx, id);
            if (x < 0) {
                x = 50;
            }
            if (y < 0) {
                y = 50;
            }
        } else {
            x = Prefs.getHudXPercent(mCtx);
            y = Prefs.getHudYPercent(mCtx);
            sc = Prefs.getScale(mCtx);
        }
        if (sbPosX != null) {
            sbPosX.setProgress(x);
        }
        if (sbPosY != null) {
            sbPosY.setProgress(y);
        }
        if (sbScale != null) {
            sbScale.setProgress(sc - 50);
        }
    }

    private void syncWidgetSliders(int id) {
        int x = Prefs.getWidgetX(mCtx, id);
        int y = Prefs.getWidgetY(mCtx, id);
        if (sbPosX != null) {
            sbPosX.setProgress(x < 0 ? 50 : x);
        }
        if (sbPosY != null) {
            sbPosY.setProgress(y < 0 ? 50 : y);
        }
    }

    private void applyWidgetScale(int id, int percent) {
        Prefs.setWidgetScale(mCtx, id, percent);
        if (tvScaleLabel != null) {
            tvScaleLabel.setText("控件缩放：" + percent + "%");
        }
        HudTrafficManager.get().render(TrafficLightBus.getLatest());
    }

    private void applyScale(int percent) {
        HudTrafficManager.get().setScale(percent / 100f);
        if (tvScaleLabel != null) {
            tvScaleLabel.setText("HUD 缩放：" + percent + "%（预览同步显示）");
        }
        if (previewView != null) {
            previewView.setScale(percent / 100f);
        }
    }

    private void resetHudDefaults() {
        Prefs.resetHudDefaults(mCtx);
        if (previewView != null) {
            previewView.setDragWhole(Prefs.isDragWhole(mCtx));
            previewView.setSelectedWidget(-1);
            previewView.setScale(Prefs.getScale(mCtx) / 100f);
            previewView.setPosition(Prefs.getHudXPercent(mCtx), Prefs.getHudYPercent(mCtx));
        }
        onWidgetSelected(-1);
        HudRuntime.refresh(mCtx);
        toast("已恢复默认设置");
        AppLog.i("UI", "已恢复默认设置");
    }

    private void updateHudGeometryCache() {
        Display d = HudDisplayHelper.findHudDisplay(mCtx, Prefs.isRelaxedDisplay(mCtx));
        if (d == null) {
            d = HudDisplayHelper.findHudDisplay(mCtx, true);
        }
        if (d == null) {
            hudSizeX = 800;
            hudSizeY = 480;
            hudDensity = 1f;
        } else {
            android.graphics.Point p = new android.graphics.Point();
            try {
                d.getRealSize(p);
            } catch (Throwable ignored) {
            }
            if (p.x > 0 && p.y > 0) {
                hudSizeX = p.x;
                hudSizeY = p.y;
            }
            try {
                hudDensity = mCtx.createDisplayContext(d).getResources().getDisplayMetrics().density;
            } catch (Throwable ignored) {
            }
        }
        if (previewView != null) {
            previewView.setHudGeometry(hudSizeX, hudSizeY, hudDensity);
        }
    }

    private void updatePosLabel(int xPct, int yPct) {
        int cx = Math.round((xPct - 50) / 100f * hudSizeX + hudSizeX / 2f);
        int cy = Math.round((yPct - 50) / 100f * hudSizeY + hudSizeY / 2f);
        tvPosLabel.setText("HUD 位置：" + xPct + "% / " + yPct + "%"
                + "  →  (" + cx + ", " + cy + ")"
                + "\n  黄框大致 x 545~800, y 119~415 → 用 88% / 53%");
    }

    /* ================= 状态刷新 ================= */

    private void refreshStatus() {
        TrafficLightState s = TrafficLightBus.getLatest();
        tvData.setText("数据：\n  " + s.toDebugString());

        long now = SystemClock.elapsedRealtime();
        if (now - lastHeavyRefreshAt < HEAVY_REFRESH_MS) {
            return;
        }
        lastHeavyRefreshAt = now;

        boolean hud = HudTrafficManager.get().isShowing();
        boolean mirror = HudTrafficManager.get().isMirrorShowing();
        StringBuilder hudLine = new StringBuilder();
        hudLine.append("HUD：").append(hud ? "显示中 ✔" : "未显示");
        if (mirror) {
            hudLine.append("   |   主屏镜像：显示中 ✔");
        }
        hudLine.append("\n  ").append(HudTrafficManager.get().getLastMessage());
        hudLine.append("\n  HUD开屏: ").append(S05HudWakeHelper.getLastWakeSummary());
        tvHudStatus.setText(hudLine.toString());

        List<Display> secondary = HudDisplayHelper.listSecondaryDisplays(mCtx);
        if (secondary.isEmpty()) {
            tvDisplays.setText("副屏：未检测到副屏");
        } else {
            StringBuilder sb = new StringBuilder("副屏：");
            for (Display d : secondary) {
                sb.append("\n  ").append(HudDisplayHelper.describe(d, mCtx));
            }
            Display found = HudDisplayHelper.findHudDisplay(mCtx, Prefs.isRelaxedDisplay(mCtx));
            sb.append("\n  命中 HUD：").append(found == null ? "否" : ("displayId=" + found.getDisplayId()));
            tvDisplays.setText(sb.toString());
        }

        updateHudGeometryCache();

        boolean canOverlay = Settings.canDrawOverlays(mCtx);
        tvPerm.setText("悬浮窗权限：" + (canOverlay ? "已授权 ✔" : "未授权 ✘"));
    }

    /* ================= 回调 ================= */

    @Override
    public void onTrafficLightChanged(TrafficLightState state) {
        if (previewView != null) {
            previewView.setState(state);
        }
        refreshStatus();
    }

    @Override
    public void onHudStateChanged(boolean showing, String message) {
        lastHeavyRefreshAt = 0L;
        refreshStatus();
    }

    @Override
    public void onLog(String line) {
        appendLog(line);
    }

    private void appendLog(String line) {
        if (tvLog == null) {
            return;
        }
        CharSequence cur = tvLog.getText();
        if (cur != null && cur.length() > LOG_TEXT_MAX_CHARS) {
            String all = cur.toString();
            int cut = all.indexOf('\n', all.length() - LOG_TEXT_MAX_CHARS / 2);
            tvLog.setText(cut > 0 ? all.substring(cut + 1) : all);
        }
        tvLog.append(line);
        tvLog.append("\n");

        long now = SystemClock.elapsedRealtime();
        if (svLog != null && now - lastLogScrollAt > LOG_SCROLL_MIN_MS) {
            lastLogScrollAt = now;
            svLog.post(new Runnable() {
                @Override
                public void run() {
                    svLog.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    }

    private void toast(String msg) {
        try {
            Toast.makeText(mCtx, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private boolean copyText(String label, String text) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) mCtx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                return false;
            }
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
