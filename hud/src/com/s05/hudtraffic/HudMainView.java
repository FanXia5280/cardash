package com.s05.hudtraffic;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 主界面：显示运行状态、HUD 预览，并提供位置调节、测试与保活入口。
 *
 * <p><b>性能</b>：红绿灯数据每秒更新一次，如果每次更新都做“全量刷新”
 * （副屏枚举 + 悬浮窗权限 + 电池白名单 + 无障碍状态，共 3 次 Binder IPC，
 * 并重建整个界面文字），主界面在 ScrollView 里会明显卡顿。
 * 所以这里把刷新拆成两级：数据行每次更新（廉价），其余信息最多 2 秒一次。</p>
 */
public class HudMainView extends FrameLayout
        implements TrafficLightBus.Listener, HudTrafficManager.Listener, AppLog.Sink {

    /** 较重的信息（含 IPC）最短刷新间隔 */
    private static final long HEAVY_REFRESH_MS = 2000L;
    /** 日志文本框超过这么多字符就裁掉前半部分，避免越滚越慢 */
    private static final int LOG_TEXT_MAX_CHARS = 15000;
    /** 日志自动滚动的最小间隔 */
    private static final long LOG_SCROLL_MIN_MS = 400L;

    private static final String ADB_COMMANDS =
            "# 1) 模拟器上创建一个 800x480 的副屏，模拟车机 HUD\n"
                    + "adb shell settings put global overlay_display_devices \"800x480/160\"\n"
                    + "adb shell settings put global overlay_display_devices \"\"   # 关闭\n\n"
                    + "# 2) 回放高德的红绿灯倒计时广播（KEY_TYPE=60073，dir：1左转 2右转 3掉头 4直行）\n"
                    + "adb shell am broadcast -a AUTONAVI_STANDARD_BROADCAST_SEND "
                    + "--ei KEY_TYPE 60073 --ei trafficLightStatus 2 "
                    + "--ei redLightCountDownSeconds 12 --ei dir 4\n"
                    + "adb shell am broadcast -a AUTONAVI_STANDARD_BROADCAST_SEND "
                    + "--ei KEY_TYPE 60073 --ei trafficLightStatus 1 "
                    + "--ei redLightCountDownSeconds 8 --ei dir 1\n\n"
                    + "# 3) 直接给本应用注入测试数据\n"
                    + "adb shell am broadcast -a com.s05.hudtraffic.DEBUG --ei countdown 15 --ei status 2 --ei dir 4\n"
                    + "adb shell am broadcast -a com.s05.hudtraffic.DEBUG --ei countdown 8  --ei status 1 --ei dir 1\n"
                    + "adb shell am broadcast -a com.s05.hudtraffic.DEBUG --ez clear true\n\n"
                    + "# 4) HUD 开屏相关（车机上调这个）\n"
                    + "adb shell am broadcast -a com.s05.hudtraffic.DEBUG --ez wake true\n"
                    + "adb shell am broadcast -a com.s05.hudtraffic.DEBUG --ei dip 51\n"
                    + "adb shell am broadcast -a com.s05.hudtraffic.DEBUG --ez amapWake true\n\n"
                    + "# 5) 抓闪退堆栈\n"
                    + "adb logcat -b crash -d\n";

    private TextView tvHudStatus;
    private TextView tvDisplays;
    private TextView tvData;
    private TextView tvLog;
    private TextView tvScaleLabel;
    private TextView tvWidgetSel;
    private Switch swWidgetShow;
    private Switch swDragWhole;
    private TextView tvPosLabel;
    private FrameLayout previewBox;
    private Switch swHud;
    private Switch swRelaxed;
    private Switch swSimulate;
    private Switch swMirror;
    private SeekBar sbScale;
    private SeekBar sbPosX;
    private SeekBar sbPosY;
    private ScrollView svLog;
    private HudPreviewView previewView;
    private Switch swPreviewDemo;

    /** HUD 副屏实际尺寸/密度缓存：预览按它等比缩放（避免滑块高频回调里反复做 Binder IPC） */
    private int hudSizeX = 800;
    private int hudSizeY = 480;
    private float hudDensity = 1f;

    /** 防止程序化 setChecked 反过来触发监听 */
    private boolean updatingKeepAliveSwitch = false;
    private long lastHeavyRefreshAt = 0L;
    private long lastLogScrollAt = 0L;

    /** 宿主 Context（本类不再是 Activity，所有需要 Context 的地方用它） */
    private final Context mCtx;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable periodic = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, 1000L);
        }
    };

    public HudMainView(Context c) {
        super(c);
        mCtx = c.getApplicationContext();
        // 内置进高德时我们的 App（Application 子类）不会被使用，这里补装崩溃捕获
        CrashHandler.install(mCtx);
        // 控件独立控制要读配置
        HudPanelRenderer.setAppContext(mCtx);
        addView(buildContentView());

        bindViews();
        buildPreviewPanel();
        setupListeners();

        AppLog.addSink(this);
        TrafficLightBus.addListener(this);
        HudTrafficManager.get().setAppContext(mCtx);
        HudTrafficManager.get().addListener(this);
        S05HudWakeHelper.setAppContext(mCtx);

        tvLog.setText(AppLog.dump());
        AppLog.i("UI", "主界面已启动");

        showCrashIfAny();
        // 通知/定位权限由宿主 Activity 申请，这里不再自己 requestPermissions
        // 无条件拉起常驻服务（此处是前台启动，不会被 Android 12+ 的后台限制拦下）
        HudTrafficService.start(mCtx);
    }

    /* ================= 界面（纯代码构建，不依赖 XML 资源） =================
     * 这样同一份代码既能打独立 APK，也能作为 classes3.dex 合并进高德 APK
     * （高德的资源表里没有我们的布局/样式，用 R.layout 会取到错误的资源）。
     * 各控件用 setId(R.id.xxx) 设置 id —— R.id 是编译期内联的 int，不依赖资源表，
     * 所以下面 bindViews()/setupListeners() 的 findViewById 逻辑一行都不用改。
     */

    /* 底色适配（2026-09-27）：嵌入 D+ 桌面设置后宿主是浅色主题，
     * 原来写死的深色（C_BG=0xFF0E1216 / C_CARD=0xFF19202A）在白色设置页里是一块块黑卡，
     * 全部换成浅色系；只有 HUD 预览盒和日志框保留深色（它们模拟的是 HUD 黑底屏）。 */
    private static final int C_BG = 0xFFF3F5F8;
    private static final int C_CARD = 0xFFFFFFFF;
    private static final int C_BRAND = 0xFF0A7AFF;
    private static final int C_TEXT = 0xFF1B1F24;
    private static final int C_TEXT_DIM = 0xFF66707A;

    private int dp(float v) {
        return Math.round(v * mCtx.getResources().getDisplayMetrics().density);
    }

    private TextView mkText(int id, String text, int color, float sizeSp, boolean bold) {
        TextView tv = new TextView(mCtx);
        if (id != 0) {
            tv.setId(id);
        }
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(sizeSp);
        tv.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.MONOSPACE);
        return tv;
    }

    private TextView mkTitle(String text) {
        return mkText(0, text, C_BRAND, 13, true);
    }

    private TextView mkBody(String text, int id) {
        TextView tv = new TextView(mCtx);
        tv.setId(id);
        tv.setText(text);
        tv.setTextColor(C_TEXT);
        tv.setTextSize(13);
        tv.setTypeface(Typeface.MONOSPACE);
        return tv;
    }

    private Switch mkSwitch(int id, String text) {
        Switch s = new Switch(mCtx);
        s.setId(id);
        s.setText(text);
        s.setTextColor(C_TEXT);
        s.setTextSize(13);
        return s;
    }

    private Button mkButton(int id, String text) {
        Button b = new Button(mCtx);
        b.setId(id);
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

    /** 按当前方向选布局：横屏 = 左 1/3 功能 + 右 2/3 预览（车机样式），竖屏 = 竖向堆叠。 */
    private View buildContentView() {
        boolean landscape = mCtx.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        return landscape ? buildLandscapeContent() : buildPortraitContent();
    }

    private View buildPortraitContent() {
        ScrollView root = new ScrollView(mCtx);
        root.setFillViewport(true);
        root.setBackgroundColor(C_BG);

        LinearLayout col = new LinearLayout(mCtx);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        col.setPadding(p, p, p, p);
        root.addView(col, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        col.addView(mkText(0, "HUD 红绿灯倒计时", C_TEXT, 22, true));
        addCard(col, buildStatusCard());
        addCard(col, buildPreviewCard(false));
        addCard(col, buildControlCard());
        addCard(col, buildLogCard());
        addCard(col, buildAboutCard());
        return root;
    }

    private View buildLandscapeContent() {
        LinearLayout root = new LinearLayout(mCtx);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(C_BG);
        int p = dp(10);
        root.setPadding(p, p, p, p);

        // 左 1/3：功能列（可滚动）
        ScrollView left = new ScrollView(mCtx);
        LinearLayout leftCol = new LinearLayout(mCtx);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        left.addView(leftCol, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        leftCol.addView(mkText(0, "HUD 红绿灯倒计时", C_TEXT, 18, true));
        addCard(leftCol, buildStatusCard());
        addCard(leftCol, buildControlCard());
        addCard(leftCol, buildLogCard());
        addCard(leftCol, buildAboutCard());
        root.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        // 右 2/3：预览铺满
        LinearLayout rightCol = new LinearLayout(mCtx);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 2f);
        rightLp.leftMargin = dp(6);
        root.addView(rightCol, rightLp);
        LinearLayout previewCard = buildPreviewCard(true);
        rightCol.addView(previewCard, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        return root;
    }

    private LinearLayout buildStatusCard() {
        LinearLayout c1 = mkCard();
        // 面向客户端发布：运行状态不对外展示
        c1.setVisibility(View.GONE);
        c1.addView(mkTitle("运行状态"));
        tvHudStatus = mkBody("HUD：未启动", R.id.tvHudStatus);
        addTo(c1, tvHudStatus, 6);
        tvDisplays = mkBody("副屏：-", R.id.tvDisplays);
        addTo(c1, tvDisplays, 4);
        tvData = mkBody("数据：-", R.id.tvData);
        addTo(c1, tvData, 4);
        return c1;
    }

    /** @param fill true = previewBox 用 weight 填满卡片（横屏右侧用） */
    private LinearLayout buildPreviewCard(boolean fill) {
        LinearLayout c2 = mkCard();
        c2.addView(mkTitle("HUD 预览（按副屏等比缩放，所见即所得）"));
        addTo(c2, mkText(0, "拖动即可调位置，松手同步到 HUD；黄框 = HUD 可见区", C_TEXT_DIM, 11, false), 2);
        swPreviewDemo = mkSwitch(R.id.swPreviewDemo, "预览用示例数据（关闭 = 显示真实/模拟数据）");
        addTo(c2, swPreviewDemo, 4);
        previewBox = new FrameLayout(mCtx);
        previewBox.setId(R.id.previewBox);
        previewBox.setBackgroundColor(0xFF0E1116);
        previewBox.setClipChildren(false);
        previewBox.setClipToPadding(false);
        if (fill) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
            lp.topMargin = dp(8);
            c2.addView(previewBox, lp);
        } else {
            addTo(c2, previewBox, 8);
        }
        return c2;
    }

    private LinearLayout buildControlCard() {
        LinearLayout c3 = mkCard();
        c3.addView(mkTitle("控制"));
        swHud = mkSwitch(R.id.swHud, "开启 HUD 显示");
        addTo(c3, swHud, 6);
        swRelaxed = mkSwitch(R.id.swRelaxed, "宽松副屏匹配（接受任意副屏尺寸，便于模拟器调试）");
        swRelaxed.setVisibility(View.GONE);
        addTo(c3, swRelaxed, 0);
        swSimulate = mkSwitch(R.id.swSimulate, "模拟数据源（模拟器无高德时演示用）");
        swSimulate.setVisibility(View.GONE);
        addTo(c3, swSimulate, 0);
        swMirror = mkSwitch(R.id.swMirror, "同时在主屏显示悬浮窗（桌面可见，调试/验证用）");
        swMirror.setVisibility(View.GONE);
        addTo(c3, swMirror, 0);


        tvPosLabel = mkText(R.id.tvPosLabel, "HUD 位置：77% / 87%", C_TEXT_DIM, 12, false);
        tvPosLabel.setVisibility(View.GONE);
        addTo(c3, tvPosLabel, 8);
        addTo(c3, mkText(0, "横向位置", C_TEXT_DIM, 11, false), 4);
        sbPosX = new SeekBar(mCtx);
        sbPosX.setId(R.id.sbPosX);
        sbPosX.setMax(100);
        addTo(c3, sbPosX, 0);
        addTo(c3, mkText(0, "纵向位置", C_TEXT_DIM, 11, false), 0);
        sbPosY = new SeekBar(mCtx);
        sbPosY.setId(R.id.sbPosY);
        sbPosY.setMax(100);
        addTo(c3, sbPosY, 0);
        tvScaleLabel = mkText(R.id.tvScaleLabel, "HUD 缩放：85%", C_TEXT_DIM, 12, false);
        addTo(c3, tvScaleLabel, 8);
        sbScale = new SeekBar(mCtx);
        sbScale.setId(R.id.sbScale);
        sbScale.setMax(150);
        addTo(c3, sbScale, 0);

        // ---- 控件独立控制：在预览里点一个控件，下面的滑块就绑定它 ----
        tvWidgetSel = mkText(0, "已选中：未选择（点预览里的控件）", C_TEXT_DIM, 12, false);
        addTo(c3, tvWidgetSel, 8);
        swWidgetShow = mkSwitch(0, "隐藏选中控件");
        swWidgetShow.setEnabled(false);
        addTo(c3, swWidgetShow, 0);
        swWidgetShow.setOnCheckedChangeListener((buttonView, isChecked) -> {
            int id = selectedWidget();
            if (id >= 0) {
                // 勾选 = 隐藏该控件
                Prefs.setWidgetEnabled(mCtx, id, !isChecked);
                HudTrafficManager.get().render(TrafficLightBus.getLatest());
            }
        });

        swDragWhole = mkSwitch(0, "整体拖动（关闭 = 点选单个控件单独调整）");
        addTo(c3, swDragWhole, 4);

        // 恢复默认设置：面板位置/缩放 + 所有控件回到出厂值
        Button reset = mkButton(0, "恢复默认设置");
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetHudDefaults();
            }
        });
        addTo(c3, reset, 8);
        return c3;
    }

    /** 关于：圆形头像 + 作者 QQ + 鸣谢（每个模型单独一行） */
    private LinearLayout buildAboutCard() {
        LinearLayout c6 = mkCard();
        c6.addView(mkTitle("关于"));
        LinearLayout row = new LinearLayout(mCtx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.graphics.Bitmap bm = loadAssetBitmap("about_avatar.jpg");
        if (bm != null) {
            // 用 CircleIconView 裁成圆形（不管原图是方是长都不会变形）
            CircleIconView iv = new CircleIconView(mCtx);
            iv.setIconDrawable(new android.graphics.drawable.BitmapDrawable(mCtx.getResources(), bm));
            row.addView(iv, new LinearLayout.LayoutParams(dp(44), dp(44)));
        }
        TextView tv = mkText(0, "作者 QQ：570155402", C_TEXT_DIM, 13, false);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(10);
        row.addView(tv, tlp);
        addTo(c6, row, 8);
        addTo(c6, mkText(0, "鸣谢：", C_TEXT_DIM, 12, false), 6);
        String[] thanks = {"Hy4 preview 模型", "DeepseekV4.1 模型", "GLM5.3 模型"};
        for (String name : thanks) {
            addTo(c6, mkText(0, "  · " + name, C_TEXT_DIM, 12, false), 2);
        }
        addTo(c6, mkText(0, "本软件完全免费，如果你是花钱买的，那么你被骗了",
                C_TEXT_DIM, 12, false), 8);
        return c6;
    }

    /** 从 assets 读图片（会自动降采样，防大图 OOM）；读不到返回 null。 */
    private android.graphics.Bitmap loadAssetBitmap(String name) {
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            java.io.InputStream in = mCtx.getAssets().open(name);
            android.graphics.BitmapFactory.decodeStream(in, null, o);
            in.close();
            int sample = 1;
            int max = Math.max(o.outWidth, o.outHeight);
            while (max / (sample * 2) >= 256) {
                sample *= 2;
            }
            android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
            o2.inSampleSize = sample;
            in = mCtx.getAssets().open(name);
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeStream(in, null, o2);
            in.close();
            return bm;
        } catch (Throwable t) {
            AppLog.i("UI", "读取头像失败: " + t);
            return null;
        }
    }

    private LinearLayout buildLogCard() {
        LinearLayout c5 = mkCard();
        LinearLayout logRow = new LinearLayout(mCtx);
        logRow.setOrientation(LinearLayout.HORIZONTAL);
        logRow.addView(mkTitle("运行日志"), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        logRow.addView(mkButton(R.id.btnCopyLog, "复制日志"));
        logRow.addView(mkButton(R.id.btnClearLog, "清空"));
        c5.addView(logRow);
        svLog = new ScrollView(mCtx);
        svLog.setId(R.id.svLog);
        svLog.setBackgroundColor(0xFF05080C);
        tvLog = new TextView(mCtx);
        tvLog.setId(R.id.tvLog);
        // 日志框底是深色（0xFF05080C），文字用浅灰保证可读（底色适配后 C_TEXT_DIM 变成了深灰）
        tvLog.setTextColor(0xFF9FB0BE);
        tvLog.setTextSize(11);
        tvLog.setPadding(dp(8), dp(8), dp(8), dp(8));
        svLog.addView(tvLog, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(180));
        slp.topMargin = dp(6);
        c5.addView(svLog, slp);
        c5.setVisibility(View.GONE);
        return c5;
    }

    /**
     * 找不到控件时立刻抛出带名字的异常（会被 CrashHandler 捕获并展示），
     * 避免出现只有一行 NullPointerException 的难查崩溃。
     */
    @SuppressWarnings("unchecked")
    private <T extends View> T find(int id, String name) {
        View v = findViewById(id);
        if (v == null) {
            throw new IllegalStateException("布局缺少控件: " + name + " (id=" + id + ")");
        }
        return (T) v;
    }

    private void bindViews() {
        tvHudStatus = find(R.id.tvHudStatus, "tvHudStatus");
        tvDisplays = find(R.id.tvDisplays, "tvDisplays");
        tvData = find(R.id.tvData, "tvData");
        tvLog = find(R.id.tvLog, "tvLog");
        tvScaleLabel = find(R.id.tvScaleLabel, "tvScaleLabel");
        tvPosLabel = find(R.id.tvPosLabel, "tvPosLabel");
        previewBox = find(R.id.previewBox, "previewBox");
        swHud = find(R.id.swHud, "swHud");
        swRelaxed = find(R.id.swRelaxed, "swRelaxed");
        swSimulate = find(R.id.swSimulate, "swSimulate");
        swMirror = find(R.id.swMirror, "swMirror");
        swPreviewDemo = find(R.id.swPreviewDemo, "swPreviewDemo");
        sbScale = find(R.id.sbScale, "sbScale");
        sbPosX = find(R.id.sbPosX, "sbPosX");
        sbPosY = find(R.id.sbPosY, "sbPosY");
        svLog = find(R.id.svLog, "svLog");
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
        // 竖屏：previewBox 是 wrap_content（预览按 5:3 自适应高度）；
        // 横屏：previewBox 是 0dp+weight（铺满右侧 2/3），这里用 MATCH_PARENT 两种都能填满
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        previewBox.addView(previewView, lp);
        previewView.setHudGeometry(hudSizeX, hudSizeY, hudDensity);
        previewView.setScale(Prefs.getScale(mCtx) / 100f);
        previewView.setPosition(Prefs.getHudXPercent(mCtx), Prefs.getHudYPercent(mCtx));
        previewView.setState(TrafficLightBus.getLatest());
    }

    /**
     * 预览里拖动面板：拖动中只更新预览与滑块（便宜），
     * <b>松手才写配置并刷新 HUD</b>（刷新会重建窗口，跟手做会卡）。
     */
    private void onPreviewDragged(int xPercent, int yPercent, boolean finished) {
        sbPosX.setProgress(xPercent);
        sbPosY.setProgress(yPercent);
        updatePosLabel(xPercent, yPercent);
        if (finished) {
            Prefs.setHudXPercent(mCtx, xPercent);
            Prefs.setHudYPercent(mCtx, yPercent);
            HudTrafficService.refresh(mCtx);
            AppLog.i("UI", "预览拖动 -> HUD 位置 " + xPercent + "% / " + yPercent + "%");
        }
    }

    private void setupListeners() {
        swHud.setChecked(Prefs.isHudEnabled(mCtx));
        swRelaxed.setChecked(Prefs.isRelaxedDisplay(mCtx));
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
                HudTrafficService.ensureAlive(mCtx);
                HudTrafficService.refresh(mCtx);
            } else {
                HudTrafficManager.get().hide();
            }
            refreshStatus();
        });

        swRelaxed.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Prefs.setRelaxedDisplay(mCtx, isChecked);
            AppLog.i("UI", "宽松副屏匹配 -> " + isChecked);
            HudTrafficService.refresh(mCtx);
            refreshStatus();
        });

        // 预览数据模式：默认示例数据（全部控件都在，便于调位置/大小）
        swPreviewDemo.setChecked(Prefs.isPreviewDemo(mCtx));
        previewView.setDemoMode(swPreviewDemo.isChecked());
        swPreviewDemo.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Prefs.setPreviewDemo(mCtx, isChecked);
            previewView.setDemoMode(isChecked);
            AppLog.i("UI", "预览示例数据 -> " + isChecked
                    + (isChecked ? "（全部控件都会画出来）" : "（显示真实/模拟数据）"));
        });

        swSimulate.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Prefs.setSimulate(mCtx, isChecked);
            AppLog.i("UI", "模拟数据源 -> " + isChecked);
            // 打开模拟数据源就是要看数据效果：自动把预览从"示例数据"切成真实数据，
            // 否则预览一直显示示例数据，会以为模拟源没生效
            if (isChecked && swPreviewDemo.isChecked()) {
                swPreviewDemo.setChecked(false);   // 会触发上面的监听（写 Prefs + setDemoMode）
                AppLog.i("UI", "预览已自动切换为真实数据（显示模拟源）");
            }
            HudTrafficService.ensureAlive(mCtx);
        });

        swMirror.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Prefs.setMirrorMain(mCtx, isChecked);
            AppLog.i("UI", "主屏镜像悬浮窗 -> " + isChecked);
            HudTrafficService.ensureAlive(mCtx);
            HudTrafficService.refresh(mCtx);
        });
        // （对齐 D 的 SR 位置 / 重发 HUD 开屏指令 两个按钮已按需求移除）

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
                    return;   // 控件缩放已在拖动时写入配置
                }
                Prefs.setScale(mCtx, seekBar.getProgress() + 50);
                HudTrafficService.refresh(mCtx);
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
                HudTrafficService.refresh(mCtx);
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
                HudTrafficService.refresh(mCtx);
            }
        });

        // 整体拖动开关（放在这里而不是创建控件的地方：那时 previewView 还没建好）
        swDragWhole.setChecked(Prefs.isDragWhole(mCtx));
        previewView.setDragWhole(Prefs.isDragWhole(mCtx));
        swDragWhole.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Prefs.setDragWhole(mCtx, isChecked);
            previewView.setDragWhole(isChecked);
            if (isChecked) {
                previewView.setSelectedWidget(-1);
            }
            onWidgetSelected(-1);
            AppLog.i("UI", "整体拖动 -> " + isChecked);
        });

        Button btnClearLog = find(R.id.btnClearLog, "btnClearLog");
        btnClearLog.setOnClickListener(v -> {
            AppLog.clear();
            tvLog.setText("");
        });

        // 复制当前日志（排查真机问题时直接把 [Amap] 行粘出来）
        find(R.id.btnCopyLog, "btnCopyLog").setOnClickListener(v -> {
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

    }

    /** SeekBar 监听器的空实现基类，省掉一堆空方法。 */
    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }
    }

    /**
     * 刷新 HUD 副屏几何缓存（尺寸 + density）并同步给预览。
     *
     * <p>这里面有 Binder IPC，所以只在 {@link #refreshStatus()} 的"重信息"节流里调用；
     * 滑块拖动 / 预览拖动这些高频路径只读缓存。</p>
     */
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
        // 用缓存的副屏尺寸换算像素坐标（这里不做 IPC，滑块高频回调也不怕）
        int cx = Math.round((xPct - 50) / 100f * hudSizeX + hudSizeX / 2f);
        int cy = Math.round((yPct - 50) / 100f * hudSizeY + hudSizeY / 2f);
        setTextIfChanged(tvPosLabel, "HUD 位置：" + xPct + "% / " + yPct + "%"
                + "  →  " + hudSizeX + "x" + hudSizeY + " 上的 (" + cx + ", " + cy + ")"
                + "\n  黄框大致 x 545~800, y 119~415（中心 ~680,264）→ 用 85% / 55%"
                + "\n  预览里可以直接拖动面板；松手后同步到 HUD");
    }

    /** 恢复默认设置：面板位置/缩放 + 6 个控件的独立设置全部回出厂值，并立刻刷新 */
    private void resetHudDefaults() {
        Prefs.resetHudDefaults(mCtx);
        if (previewView != null) {
            previewView.setDragWhole(Prefs.isDragWhole(mCtx));
            previewView.setSelectedWidget(-1);
            previewView.setScale(Prefs.getScale(mCtx) / 100f);
            previewView.setPosition(Prefs.getHudXPercent(mCtx), Prefs.getHudYPercent(mCtx));
        }
        onWidgetSelected(-1);
        HudTrafficService.refresh(mCtx);
        toast("已恢复默认设置");
        AppLog.i("UI", "已恢复默认设置（面板位置/缩放 + 全部控件）");
    }

    /** 当前点选的控件（-1 = 没选，滑块控制整个面板） */
    private int selectedWidget() {
        return previewView != null ? previewView.getSelectedWidget() : -1;
    }

    /** 点选了某个控件（或取消选择）：滑块立刻绑定到它 */
    private void onWidgetSelected(int id) {
        if (tvWidgetSel != null) {
            setTextIfChanged(tvWidgetSel, id >= 0
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

    /** 预览里拖动控件后，把滑块同步到它的最新位置 */
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

    /** 选中控件的缩放（拖滑块实时生效） */
    private void applyWidgetScale(int id, int percent) {
        Prefs.setWidgetScale(mCtx, id, percent);
        if (tvScaleLabel != null) {
            setTextIfChanged(tvScaleLabel, "控件缩放：" + percent + "%");
        }
        HudTrafficManager.get().render(TrafficLightBus.getLatest());
    }

    private void applyScale(int percent) {
        HudTrafficManager.get().setScale(percent / 100f);
        if (tvScaleLabel != null) {
            setTextIfChanged(tvScaleLabel, "HUD 缩放：" + percent + "%（预览同步显示）");
        }
        if (previewView != null) {
            previewView.setScale(percent / 100f);
        }
    }

    /** 注入单个方向的信号灯。 */
    private void inject(int dir, int status, int countdown) {
        TrafficLightState s = TrafficLightBus.getLatest().copy();
        long now = System.currentTimeMillis();
        if (countdown < 0) {
            s.removeLight(dir);
        } else {
            s.applyLight(dir, status, countdown, 0, 0, now);
            s.routeLightNum = 3;
        }
        s.source = "ui-test";
        s.updateTime = now;
        TrafficLightBus.publish(s);
        AppLog.i("UI", "测试注入 dir=" + dir + "(" + TrafficLightState.dirText(dir) + ") "
                + "status=" + status + " countdown=" + countdown);
    }

    /** 同时注入直行 + 左转，模拟有左转灯的十字路口。 */
    private void injectBoth() {
        TrafficLightState s = TrafficLightBus.getLatest().copy();
        long now = System.currentTimeMillis();
        s.applyLight(TrafficLightState.DIR_STRAIGHT, TrafficLightState.ST_GREEN, 12, 0, 0, now);
        s.applyLight(TrafficLightState.DIR_LEFT, TrafficLightState.ST_ABOUT_GREEN, 8, 0, 0, now);
        s.routeLightNum = 3;
        s.source = "ui-test";
        s.updateTime = now;
        TrafficLightBus.publish(s);
        AppLog.i("UI", "测试注入 直行+左转 双灯");
    }

    private void clearLights() {
        TrafficLightState s = TrafficLightBus.getLatest().copy();
        s.clearLights();
        s.source = "ui-test";
        s.updateTime = System.currentTimeMillis();
        TrafficLightBus.publish(s);
        AppLog.i("UI", "已清除红绿灯");
    }

    private void copyCommands() {
        if (copyText("hud-debug", ADB_COMMANDS)) {
            toast("调试命令已复制到剪贴板");
        }
    }

    private boolean copyText(String label, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) mCtx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                return false;
            }
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            return true;
        } catch (Throwable t) {
            toast("复制失败：" + t);
            return false;
        }
    }

    /** 上次进程崩溃的话，进界面就弹出来，方便直接复制堆栈。 */
    private void showCrashIfAny() {
        String crash = CrashHandler.getLastCrash();
        if (crash == null || crash.length() == 0) {
            return;
        }
        showCrashDialog();
    }

    private void showCrashDialog() {
        String crash = CrashHandler.getLastCrash();
        if (crash == null || crash.length() == 0) {
            crash = "本次运行没有捕获到崩溃。\n\n如果依然闪退，可用 adb 抓取：\n"
                    + "adb logcat -b crash -d\n\n"
                    + "或到 Android/data/com.s05.hudtraffic/files/crash/ 目录查看。";
        }
        final String text = crash;
        new AlertDialog.Builder(mCtx)
                .setTitle("崩溃日志")
                .setMessage(text)
                .setPositiveButton("复制", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (copyText("hud-crash", text)) {
                            toast("崩溃日志已复制");
                        }
                    }
                })
                .setNeutralButton("清空", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        CrashHandler.clearLastCrash();
                        toast("已清空");
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }



    /**
     * 两级刷新：
     * <ul>
     *   <li>数据行：每次都更新（只是拼个字符串 + setText 判重，很便宜）；</li>
     *   <li>其余信息：包含 {@code Settings.canDrawOverlays}、
     *       {@code Settings.Secure.getString}、{@code PowerManager.isIgnoringBatteryOptimizations}
     *       三次 Binder IPC，限制在 {@link #HEAVY_REFRESH_MS} 一次。</li>
     * </ul>
     */
    private void refreshStatus() {
        TrafficLightState s = TrafficLightBus.getLatest();
        setTextIfChanged(tvData, "数据：\n  " + s.toDebugString());

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
        setTextIfChanged(tvHudStatus, hudLine.toString());

        List<Display> secondary = HudDisplayHelper.listSecondaryDisplays(mCtx);
        if (secondary.isEmpty()) {
            setTextIfChanged(tvDisplays, "副屏：未检测到副屏\n  （模拟器可执行：adb shell settings put global overlay_display_devices \"800x480/160\"）");
        } else {
            StringBuilder sb = new StringBuilder("副屏：");
            for (Display d : secondary) {
                sb.append("\n  ").append(HudDisplayHelper.describe(d, mCtx));
            }
            Display found = HudDisplayHelper.findHudDisplay(mCtx, Prefs.isRelaxedDisplay(mCtx));
            sb.append("\n  命中 HUD：").append(found == null ? "否" : ("displayId=" + found.getDisplayId()));
            setTextIfChanged(tvDisplays, sb.toString());
        }

        updateHudGeometryCache();
        // 权限/保活状态行已按需求移除（2026-09-27）：D+ 桌面常驻 ⇒ 我们跟着常驻，
        // 不再展示/引导悬浮窗权限、电池白名单、无障碍保活这些入口
    }

    private static void setTextIfChanged(TextView tv, String text) {
        if (tv == null) {
            return;
        }
        CharSequence cur = tv.getText();
        if (cur == null || !cur.equals(text)) {
            tv.setText(text);
        }
    }

    // ---------------- 回调 ----------------

    @Override
    public void onTrafficLightChanged(TrafficLightState state) {
        // 预览在"真实数据"模式下跟随数据（示例模式下 setState 不会触发重绘）；
        // 其余只走轻量刷新，重的部分由 refreshStatus 内部节流
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
        // 日志框无限增长会让 TextView 越来越慢，超过阈值就裁掉前半部分
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
        Toast.makeText(mCtx, msg, Toast.LENGTH_SHORT).show();
    }

    // ---------------- 生命周期 ----------------







    /** 宿主不再用这个视图时调（清理监听，避免泄漏）。 */
    public void detach() {
        handler.removeCallbacks(periodic);
        AppLog.removeSink(this);
        TrafficLightBus.removeListener(this);
        HudTrafficManager.get().removeListener(this);
    }
}
