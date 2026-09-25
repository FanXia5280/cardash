package com.magiclantern.rgb;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 主页：设备电源 + 控制模式 + 亮度 + 快捷功能
 * 左右两栏使用等高标题，保证在任何屏幕尺寸下都严格对齐。
 */
public class HomePage extends Page {

    private Switch swPower;
    private TextView tvPowerState;
    private TextView segLight;
    private TextView segScene;
    private SeekBar sbBrightness;
    private TextView tvBright;
    private LinearLayout favModeHolder;
    private String lastFavSignature = "";

    /** 主页固定预设色 */
    private static final int[] PRESET = {
            0xFFFF0000, 0xFFFF7F00, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF,
            0xFF8A2BE2, 0xFFFF1493, 0xFFFFFFFF, 0xFFFFC0CB, 0xFFFFD700, 0xFF00FA9A
    };

    private final android.os.Handler sendHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable sendBrightTask = new Runnable() {
        @Override
        public void run() {
            LedOutput.setBrightness(activity, Prefs.get(activity).getBrightness());
        }
    };

    public HomePage(LanternPanel host) {
        super(host);
    }

    @Override
    public String getTitle() {
        return "智能控制";
    }

    @Override
    protected View build(Context c) {
        LinearLayout root = Ui.row(c);
        root.setPadding(dp(18), dp(2), dp(18), dp(8));
        root.setGravity(android.view.Gravity.TOP);

        // ---------------- 左栏：设备控制 ----------------
        LinearLayout left = Ui.column(c);
        left.setLayoutParams(Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f));
        left.setPadding(0, 0, dp(9), 0);
        left.addView(Ui.sectionHeader(c, "设备控制"));

        LinearLayout card = Ui.card(c);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));

        LinearLayout head = Ui.row(c);
        head.addView(Ui.iconCircle(c, Res.grad_blue, Res.ic_power, 40, 10));
        LinearLayout info = Ui.column(c);
        info.setLayoutParams(Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        info.setPadding(dp(14), 0, dp(8), 0);
        info.addView(Ui.text(c, "设备电源", 17, Ui.TEXT_PRIMARY, true));
        tvPowerState = Ui.text(c, "运行中", 13, Ui.TEXT_SECONDARY, false);
        tvPowerState.setPadding(0, dp(3), 0, 0);
        info.addView(tvPowerState);
        head.addView(info);

        swPower = Ui.switchView(c);
        swPower.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Prefs.get(activity).setPowerOn(isChecked);
                LedOutput.power(activity, isChecked);
                tvPowerState.setText(isChecked ? "运行中" : "已关闭");
                buttonView.invalidate();
                host.refreshPages();
            }
        });
        head.addView(swPower);
        card.addView(head);

        card.addView(Ui.divider(c));

        card.addView(Ui.text(c, "控制模式", 13, Ui.TEXT_SECONDARY, false));

        LinearLayout seg = Ui.row(c);
        seg.setPadding(0, dp(6), 0, 0);
        segLight = Ui.segmentWithIcon(c, "灯光模式", Res.ic_light);
        segScene = Ui.segmentWithIcon(c, "场景模式", Res.ic_scene);
        LinearLayout.LayoutParams slp = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        segLight.setLayoutParams(slp);
        LinearLayout.LayoutParams s2lp = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        s2lp.setMarginStart(dp(10));
        segScene.setLayoutParams(s2lp);
        segLight.setSelected(true);
        segLight.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.switchTab(1);
            }
        });
        segScene.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.switchTab(2);
            }
        });
        seg.addView(segLight);
        seg.addView(segScene);
        card.addView(seg);

        LinearLayout brow = Ui.row(c);
        brow.setPadding(0, dp(12), 0, 0);
        brow.addView(Ui.text(c, "亮度", 13, Ui.TEXT_SECONDARY, false));
        View spacer = new View(c);
        spacer.setLayoutParams(Ui.lp(0, 1, 1f));
        brow.addView(spacer);
        tvBright = Ui.text(c, "100%", 14, Ui.TEXT_PRIMARY, true);
        brow.addView(tvBright);
        card.addView(brow);

        sbBrightness = Ui.seekBar(c, Prefs.get(c).getBrightness(), 100);
        tvBright.setText(Prefs.get(c).getBrightness() + "%");
        sbBrightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvBright.setText(progress + "%");
                if (fromUser) {
                    Prefs.get(activity).setBrightness(progress);
                    sendHandler.removeCallbacks(sendBrightTask);
                    sendHandler.postDelayed(sendBrightTask, 120L);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                Prefs.get(activity).setBrightness(seekBar.getProgress());
                sendHandler.removeCallbacks(sendBrightTask);
                LedOutput.setBrightness(activity, seekBar.getProgress());
            }
        });
        card.addView(sbBrightness);

        // 常用模式（可在场景页长按模式项添加 / 长按此处移除）
        card.addView(Ui.divider(c));
        LinearLayout favTitleRow = Ui.row(c);
        favTitleRow.addView(Ui.text(c, "常用模式", 13, Ui.TEXT_SECONDARY, false));
        View favSpacer = new View(c);
        favSpacer.setLayoutParams(Ui.lp(0, 1, 1f));
        favTitleRow.addView(favSpacer);
        favTitleRow.addView(Ui.text(c, "场景页长按添加", 11, Ui.TEXT_THIRD, false));
        card.addView(favTitleRow);

        favModeHolder = Ui.column(c);
        LinearLayout.LayoutParams fmLp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        fmLp.topMargin = dp(8);
        favModeHolder.setLayoutParams(fmLp);
        card.addView(favModeHolder);
        rebuildFavModes();

        LinearLayout.LayoutParams cardLp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        card.setLayoutParams(cardLp);
        left.addView(card);

        // ---------------- 常用色 ----------------
        LinearLayout favCard = Ui.card(c);
        LinearLayout.LayoutParams favLp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        favLp.topMargin = dp(12);
        favCard.setLayoutParams(favLp);
        favCard.setPadding(dp(12), dp(10), dp(12), dp(10));
        favCard.addView(Ui.text(c, "常用色", 13, Ui.TEXT_SECONDARY, false));

        LinearLayout dots = Ui.row(c);
        LinearLayout.LayoutParams dotsLp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(34));
        dotsLp.topMargin = dp(8);
        dots.setLayoutParams(dotsLp);
        for (int i = 0; i < PRESET.length; i++) {
            final int color = PRESET[i];
            ColorDotView dot = new ColorDotView(c);
            dot.setColor(color);
            LinearLayout.LayoutParams dlp = Ui.lp(0, dp(32), 1f);
            dlp.setMargins(dp(4), 0, dp(4), 0);
            dot.setLayoutParams(dlp);
            dot.setClickable(true);
            dot.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    applyPresetColor(color);
                }
            });
            dots.addView(dot);
        }
        favCard.addView(dots);
        left.addView(favCard);

        // ---------------- 右栏：快捷功能 ----------------
        LinearLayout right = Ui.column(c);
        right.setLayoutParams(Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        right.setPadding(dp(9), 0, 0, 0);
        right.addView(Ui.sectionHeader(c, "快捷功能"));

        LinearLayout grid = Ui.column(c);
        grid.setLayoutParams(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout row1 = Ui.row(c);
        row1.setLayoutParams(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        row1.addView(quickCard(c, Res.grad_blue, Res.ic_palette, "灯光控制",
                new Runnable() {
                    @Override
                    public void run() {
                        host.switchTab(1);
                    }
                }), cardLp(dp(7), false));
        row1.addView(quickCard(c, Res.grad_purple, Res.ic_scene, "场景模式",
                new Runnable() {
                    @Override
                    public void run() {
                        host.switchTab(2);
                    }
                }), cardLp(dp(7), true));

        LinearLayout row2 = Ui.row(c);
        LinearLayout.LayoutParams r2lp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        r2lp.topMargin = dp(12);
        row2.setLayoutParams(r2lp);
        row2.addView(quickCard(c, Res.grad_green, Res.ic_clock, "闹钟管理",
                new Runnable() {
                    @Override
                    public void run() {
                        host.openSub(new AlarmPage(host));
                    }
                }), cardLp(dp(7), false));
        row2.addView(quickCard(c, Res.grad_orange, Res.ic_speaker, "设备管理",
                new Runnable() {
                    @Override
                    public void run() {
                        host.openSub(new DevicePage(host));
                    }
                }), cardLp(dp(7), true));

        LinearLayout row3 = Ui.row(c);
        LinearLayout.LayoutParams r3lp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        r3lp.topMargin = dp(12);
        row3.setLayoutParams(r3lp);
        row3.addView(quickCard(c, Res.grad_cyan, Res.ic_gear, "设置",
                new Runnable() {
                    @Override
                    public void run() {
                        host.switchTab(3);
                    }
                }), cardLp(dp(7), false));
        row3.addView(quickCard(c, Res.grad_pink, Res.ic_light, "关于",
                new Runnable() {
                    @Override
                    public void run() {
                        showAbout();
                    }
                }), cardLp(dp(7), true));

        grid.addView(row1);
        grid.addView(row2);
        grid.addView(row3);
        right.addView(grid);

        root.addView(left);
        root.addView(right);
        return root;
    }

    private LinearLayout.LayoutParams cardLp(int marginDp, boolean start) {
        LinearLayout.LayoutParams lp = Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        if (start) {
            lp.setMarginStart(dp(marginDp));
        } else {
            lp.setMarginEnd(dp(marginDp));
        }
        return lp;
    }

    private LinearLayout quickCard(Context c, int bgRes, int iconRes, String title,
                                   final Runnable action) {
        LinearLayout card = Ui.quickCard(c, bgRes, iconRes, title);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        return card;
    }

    /** 重建主页常用模式按钮（每行 3 个，长按移除） */
    private void rebuildFavModes() {
        if (favModeHolder == null) return;
        favModeHolder.removeAllViews();
        java.util.List<String> favs = Prefs.get(activity).getFavModes();
        lastFavSignature = favs.toString();

        LinearLayout line = null;
        for (int i = 0; i < favs.size(); i++) {
            if (i % 3 == 0) {
                line = Ui.row(activity);
                LinearLayout.LayoutParams llp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) llp.topMargin = dp(8);
                line.setLayoutParams(llp);
                favModeHolder.addView(line);
            }
            String[] parts = favs.get(i).split(",");
            final int g;
            final int cmd;
            try {
                g = Integer.parseInt(parts[0]);
                cmd = Integer.parseInt(parts[1]);
            } catch (Exception e) {
                continue;
            }
            TextView btn = Ui.text(activity, favNameOf(g, cmd), 12, Ui.TEXT_PRIMARY, false);
            btn.setGravity(android.view.Gravity.CENTER);
            Res.bg(btn, Res.bg_seg_normal);
            btn.setPadding(dp(4), dp(10), dp(4), dp(10));
            LinearLayout.LayoutParams blp = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            blp.setMargins(dp(4), 0, dp(4), 0);
            btn.setLayoutParams(blp);
            btn.setClickable(true);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    LedOutput.sendMode(activity, g, cmd, Prefs.get(activity).getSpeed());
                }
            });
            Ui.addPressEffect(btn);
            btn.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(final View v) {
                    v.setBackground(Ui.activeBackground(activity));
                    Ui.setLongPressActive(v, true);
                    Runnable[] menuActions = new Runnable[]{
                            new Runnable() {
                                @Override
                                public void run() {
                                    LedOutput.sendMode(activity, g, cmd,
                                            Prefs.get(activity).getSpeed());
                                }
                            },
                            new Runnable() {
                                @Override
                                public void run() {
                                    Prefs.get(activity).removeFavMode(g, cmd);
                                    rebuildFavModes();
                                    android.widget.Toast.makeText(activity, "已从主页移除",
                                            android.widget.Toast.LENGTH_SHORT).show();
                                }
                            }
                    };
                    Ui.showPopupMenu(activity, v, favNameOf(g, cmd),
                            new String[]{"立即应用", "从主页移除"}, menuActions,
                            new Runnable() {
                                @Override
                                public void run() {
                                    Ui.setLongPressActive(v, false);
                                    Res.bg(v, Res.bg_seg_normal);
                                }
                            });
                    return true;
                }
            });
            if (line != null) line.addView(btn);
        }
        if (line != null) {
            int filled = favs.size() % 3;
            if (filled != 0) {
                for (int i = filled; i < 3; i++) {
                    View ghost = new View(activity);
                    LinearLayout.LayoutParams glp = Ui.lp(0, 1, 1f);
                    glp.setMargins(dp(4), 0, dp(4), 0);
                    ghost.setLayoutParams(glp);
                    line.addView(ghost);
                }
            }
        }
    }

    /** 常用模式名（兼容双色流动 / 固件渐变的命令码） */
    private static String favNameOf(int group, int cmd) {
        String n = ModeData.nameOf(group, cmd);
        if (!"模式".equals(n)) return n;
        for (FlowData.Group fg : FlowData.GROUPS) {
            for (int i = 0; i < fg.cmds.length && i < fg.names.length; i++) {
                if (fg.cmds[i] == cmd) return fg.names[i];
            }
        }
        return n;
    }

    /** 应用预设颜色 */
    private void applyPresetColor(int color) {
        Prefs.get(activity).setColor(color);
        LedOutput.sendColor(activity, color);
    }

    private void showAbout() {
        new AlertDialog.Builder(activity)
                .setTitle("氛围灯控制")
                .setMessage("版本 1.0.0\n\n"
                        + "支持 BLE 蓝牙灯控：调色、灯效模式、亮度、定时。\n"
                        + "协议与原版一致（服务 fff0 / 特征 fff3，设备名前缀 MELK-）。")
                .setPositiveButton("确定", null)
                .show();
    }

    @Override
    public void onShow() {
        refresh();
    }

    @Override
    public void onHide() {
        sendHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public void refresh() {
        if (swPower == null) return;
        boolean on = Prefs.get(activity).isPowerOn();
        if (swPower.isChecked() != on) swPower.setChecked(on);
        tvPowerState.setText(on ? "运行中" : "已关闭");
        int b = Prefs.get(activity).getBrightness();
        if (sbBrightness != null && sbBrightness.getProgress() != b) {
            sbBrightness.setProgress(b);
            tvBright.setText(b + "%");
        }
        String sig = Prefs.get(activity).getFavModes().toString();
        if (!sig.equals(lastFavSignature)) {
            rebuildFavModes();
        }
    }
}
