package com.s05.hudtraffic;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 悬浮设置面板：直接盖在高德界面之上，**不启动 Activity**，所以不会打断导航。
 *
 * <p>这是"HUD 红绿灯"与高德融为一体的关键：点击悬浮球 → 弹出这个面板（高德仍在后面正常导航），
 * 调完关掉即可。之前是启动 MainActivity，会切走高德界面、打断导航。</p>
 *
 * <p>面板本身可拖动（拖标题栏），所有控件无资源依赖（纯代码构建）。</p>
 */
public final class HudSettingsPanel {

    private static final String TAG = "HudPanel";

    private static final int C_CARD = 0xFF19202A;
    private static final int C_BRAND = 0xFF00B0FF;
    private static final int C_TEXT = 0xFFECEFF1;
    private static final int C_DIM = 0xFF8FA3B0;

    private static View panelView;
    private static WindowManager wm;
    private static WindowManager.LayoutParams lp;
    private static TextView tvStatus;
    private static Switch swHud;
    private static Switch swDemo;
    private static SeekBar sbX;
    private static SeekBar sbY;
    private static SeekBar sbScale;
    private static TextView tvPos;

    private HudSettingsPanel() {
    }

    public static synchronized void show(Context ctx) {
        if (panelView != null || ctx == null) {
            return;
        }
        final Context app = ctx.getApplicationContext();
        if (!Settings.canDrawOverlays(app)) {
            AppLog.i(TAG, "没有悬浮窗权限，设置面板不显示");
            return;
        }
        final WindowManager manager = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        if (manager == null) {
            return;
        }
        final float d = app.getResources().getDisplayMetrics().density;
        final Context t = new ContextThemeWrapper(app, android.R.style.Theme_DeviceDefault);
        final int p = Math.round(12 * d);

        /* ---- 根卡片 ---- */
        LinearLayout root = new LinearLayout(t);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(C_CARD);
        gd.setCornerRadius(14 * d);
        gd.setStroke(Math.max(1, Math.round(1 * d)), 0xFF2A3542);
        root.setBackground(gd);
        root.setPadding(p, p, p, p);

        /* ---- 标题行（可拖动） ---- */
        LinearLayout titleRow = new LinearLayout(t);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = new TextView(t);
        title.setText("HUD 红绿灯");
        title.setTextColor(C_BRAND);
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button btnClose = new Button(t);
        btnClose.setText("关闭");
        btnClose.setTextSize(12);
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hide();
            }
        });
        titleRow.addView(btnClose, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        // 主界面放在"关闭"旁边，留点间距
        Button btnMainTop = new Button(t);
        btnMainTop.setText("主界面");
        btnMainTop.setTextSize(12);
        LinearLayout.LayoutParams mainLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mainLp.leftMargin = Math.round(10 * d);
        titleRow.addView(btnMainTop, mainLp);
        btnMainTop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openMainActivity(t);
            }
        });
        root.addView(titleRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        /* ---- 状态 ---- */
        tvStatus = new TextView(t);
        tvStatus.setTextColor(C_TEXT);
        tvStatus.setTextSize(11);
        tvStatus.setTypeface(Typeface.MONOSPACE);
        root.addView(tvStatus, lpTop(Math.round(10 * d)));

        /* ---- 开关 ---- */
        swHud = newSwitch(t, "开启 HUD 显示");
        root.addView(swHud, lpTop(Math.round(10 * d)));
        swDemo = newSwitch(t, "预览用示例数据（关闭 = 真实数据）");
        root.addView(swDemo, lpTop(Math.round(2 * d)));

        /* ---- 位置 / 缩放 ---- */
        tvPos = new TextView(t);
        tvPos.setTextColor(C_DIM);
        tvPos.setTextSize(11);
        root.addView(tvPos, lpTop(Math.round(10 * d)));

        sbX = newSeek(t, 100);
        root.addView(sbX, lpTop(Math.round(4 * d)));
        sbY = newSeek(t, 100);
        root.addView(sbY, lpTop(Math.round(2 * d)));
        sbScale = newSeek(t, 150);
        root.addView(sbScale, lpTop(Math.round(2 * d)));
        // 面向客户端发布：预览示例数据 / 位置缩放滑块不对外展示（代码保留）
        swDemo.setVisibility(View.GONE);
        tvPos.setVisibility(View.GONE);
        sbX.setVisibility(View.GONE);
        sbY.setVisibility(View.GONE);
        sbScale.setVisibility(View.GONE);

        /* ---- 按钮 ---- */
        LinearLayout btnRow = new LinearLayout(t);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        Button btnMain = new Button(t);
        btnMain.setText("主界面");
        btnMain.setTextSize(12);
        btnMain.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openMainActivity(v.getContext());
            }
        });
        btnMain.setVisibility(View.GONE);
        btnRow.addView(btnMain, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button btnCopy = new Button(t);
        btnCopy.setText("复制日志");
        btnCopy.setTextSize(12);
        btnCopy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyLog(v.getContext());
            }
        });
        btnRow.addView(btnCopy, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button btnClear = new Button(t);
        btnClear.setText("清空日志");
        btnClear.setTextSize(12);
        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AppLog.clear();
                Toast.makeText(v.getContext(), "日志已清空", Toast.LENGTH_SHORT).show();
            }
        });
        btnRow.addView(btnClear, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(btnRow, lpTop(Math.round(10 * d)));

        /* ---- 窗口参数：面板贴着悬浮球显示（球在左→面板在右；球在右→面板在左） ---- */
        int panelW = Math.round(330 * d);
        lp = new WindowManager.LayoutParams(
                panelW,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);

        int ballX = FloatingEntry.getBallX();
        int ballY = FloatingEntry.getBallY();
        int screenW = app.getResources().getDisplayMetrics().widthPixels;
        int screenH = app.getResources().getDisplayMetrics().heightPixels;
        int ballSize = Math.round(Prefs.getEntrySize(app) * d);
        if (ballX >= 0 && ballY >= 0) {
            if (ballX + ballSize / 2 < screenW / 2) {
                // 球在左半屏 → 面板显示在球右侧
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.x = ballX + ballSize + Math.round(6 * d);
            } else {
                // 球在右半屏 → 面板显示在球左侧（END 下 x 是距右边缘的距离）
                lp.gravity = Gravity.TOP | Gravity.END;
                lp.x = screenW - ballX + Math.round(6 * d);
            }
            // 垂直：面板顶与球顶大致对齐，并夹在屏内（面板高约 400dp，先按估算 clamp）
            int estH = Math.round(400 * d);
            lp.y = ballY - Math.round(10 * d);
            if (lp.y < 0) {
                lp.y = 0;
            }
            if (lp.y + estH > screenH) {
                lp.y = screenH - estH;
            }
        } else {
            // 悬浮球没显示（罕见）：退回右侧居中
            lp.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
            lp.x = Math.round(8 * d);
            lp.y = 0;
        }

        /* 标题行拖动 */
        titleRow.setOnTouchListener(new View.OnTouchListener() {
            private int startX;
            private int startY;
            private float downRawX;
            private float downRawY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        int[] loc = new int[2];
                        panelView.getLocationOnScreen(loc);
                        lp.gravity = Gravity.TOP | Gravity.START;
                        lp.x = loc[0];
                        lp.y = loc[1];
                        startX = lp.x;
                        startY = lp.y;
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        moved = false;
                        try {
                            wm.updateViewLayout(panelView, lp);
                        } catch (Throwable ignored) {
                        }
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        int dx = (int) (event.getRawX() - downRawX);
                        int dy = (int) (event.getRawY() - downRawY);
                        if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                            moved = true;
                        }
                        lp.x = startX + dx;
                        lp.y = startY + dy;
                        try {
                            wm.updateViewLayout(panelView, lp);
                        } catch (Throwable ignored) {
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return moved;
                    default:
                        return false;
                }
            }
        });

        try {
            manager.addView(root, lp);
        } catch (Throwable th) {
            AppLog.i(TAG, "添加设置面板失败: " + th);
            return;
        }
        panelView = root;
        wm = manager;

        bindValues(app);
        AppLog.i(TAG, "悬浮设置面板已显示（不打断导航）");
    }

    public static synchronized void hide() {
        if (panelView == null) {
            return;
        }
        try {
            if (wm != null) {
                wm.removeViewImmediate(panelView);
            }
        } catch (Throwable t) {
            AppLog.i(TAG, "移除设置面板失败: " + t);
        }
        panelView = null;
        wm = null;
        tvStatus = null;
        swHud = null;
        swDemo = null;
        sbX = null;
        sbY = null;
        sbScale = null;
        tvPos = null;
    }

    public static boolean isVisible() {
        return panelView != null;
    }

    /** 由定时刷新调用：更新面板里的状态文字 */
    public static void refreshStatus() {
        final TextView v = tvStatus;
        if (v == null) {
            return;
        }
        v.post(new Runnable() {
            @Override
            public void run() {
                if (tvStatus == null) {
                    return;
                }
                TrafficLightState s = TrafficLightBus.getLatest();
                tvStatus.setText("HUD：" + (HudTrafficManager.get().isShowing() ? "显示中" : "未显示")
                        + " | 灯：" + s.freshLights().size()
                        + " | 路：" + (s.roadName == null || s.roadName.length() == 0 ? "-" : s.roadName));
            }
        });
    }

    /* ================= 内部 ================= */

    private static void bindValues(final Context app) {
        if (swHud == null) {
            return;
        }
        swHud.setChecked(Prefs.isHudEnabled(app));
        swDemo.setChecked(Prefs.isPreviewDemo(app));
        int x = Prefs.getHudXPercent(app);
        int y = Prefs.getHudYPercent(app);
        int scale = Prefs.getScale(app);
        sbX.setProgress(x);
        sbY.setProgress(y);
        sbScale.setProgress(scale - 50);
        updatePosText();

        swHud.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                Prefs.setHudEnabled(app, isChecked);
                AppLog.i("UI", "HUD 显示开关 -> " + isChecked);
                HudTrafficService.refresh(app);
                // 保险：切开关会触发窗口重建 + 补发开屏指令，别把悬浮球弄丢
                if (Prefs.isEntryBall(app)) {
                    FloatingEntry.show(app);
                }
            }
        });
        swDemo.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                Prefs.setPreviewDemo(app, isChecked);
                AppLog.i("UI", "预览示例数据 -> " + isChecked);
                HudTrafficService.refresh(app);
            }
        });
        SeekBar.OnSeekBarChangeListener posListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updatePosText();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                Prefs.setHudXPercent(app, sbX.getProgress());
                Prefs.setHudYPercent(app, sbY.getProgress());
                Prefs.setScale(app, sbScale.getProgress() + 50);
                HudTrafficService.refresh(app);
            }
        };
        sbX.setOnSeekBarChangeListener(posListener);
        sbY.setOnSeekBarChangeListener(posListener);
        sbScale.setOnSeekBarChangeListener(posListener);
        refreshStatus();
    }

    private static void updatePosText() {
        if (tvPos == null || sbX == null || sbY == null || sbScale == null) {
            return;
        }
        tvPos.setText("位置 " + sbX.getProgress() + "% / " + sbY.getProgress()
                + "%   缩放 " + (sbScale.getProgress() + 50) + "%");
    }

    /** 打开完整设置界面（会切走高德，用户主动点"主界面"才发生；面板先收起）。 */
    private static void openMainActivity(Context ctx) {
        try {
            Context app = ctx.getApplicationContext();
            android.content.Intent it = new android.content.Intent(app, MainActivity.class);
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(it);
            hide();
        } catch (Throwable t) {
            Toast.makeText(ctx, "打不开主界面: " + t, Toast.LENGTH_SHORT).show();
        }
    }

    private static void copyLog(Context ctx) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                return;
            }
            cm.setPrimaryClip(android.content.ClipData.newPlainText("hud-log", AppLog.dump()));
            Toast.makeText(ctx, "日志已复制", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(ctx, "复制失败: " + t, Toast.LENGTH_SHORT).show();
        }
    }

    private static Switch newSwitch(Context t, String text) {
        Switch s = new Switch(t);
        s.setText(text);
        s.setTextColor(C_TEXT);
        s.setTextSize(13);
        return s;
    }

    private static SeekBar newSeek(Context t, int max) {
        SeekBar b = new SeekBar(t);
        b.setMax(max);
        return b;
    }

    private static LinearLayout.LayoutParams lpTop(int topPx) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        l.topMargin = topPx;
        return l;
    }
}
