package com.magiclantern.rgb;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/** 灯光控制页：彩光开关 + 亮度 + 方形色板 + 色相条 + 预设颜色 */
public class LightPage extends Page {

    private SquareColorView square;
    private HueBarView hueBar;
    private Switch swLight;
    private TextView tvOnOff;
    private TextView tvBright;
    private TextView tvRgb;
    private SeekBar sbBright;
    private android.widget.ScrollView leftScroll;
    private View colorPreview;
    private int currentColor;

    // 指令节流：拖动过程中按 120ms 合并发送，避免 BLE 通道拥塞、界面卡顿
    private final android.os.Handler sendHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable sendColorTask = new Runnable() {
        @Override
        public void run() {
            LedOutput.sendColor(activity, currentColor);
        }
    };
    private final Runnable sendBrightTask = new Runnable() {
        @Override
        public void run() {
            LedOutput.setBrightness(activity, Prefs.get(activity).getBrightness());
        }
    };

    private void scheduleSend(Runnable task) {
        sendHandler.removeCallbacks(task);
        sendHandler.postDelayed(task, 120L);
    }

    private static final int[] PRESET = {
            0xFFFF0000, 0xFFFF7F00, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF,
            0xFF8A2BE2, 0xFFFF1493, 0xFFFFFFFF, 0xFFFFC0CB, 0xFFFFD700, 0xFF00FA9A
    };

    public LightPage(LanternPanel host) {
        super(host);
    }

    @Override
    public String getTitle() {
        return "灯光控制";
    }

    @Override
    public String getSubtitle() {
        return "自定义您的灯光效果";
    }

    @Override
    public boolean showBack() {
        return true;
    }

    @Override
    protected View build(Context c) {
        currentColor = Prefs.get(c).getColor();

        LinearLayout root = Ui.row(c);
        root.setPadding(dp(18), dp(4), dp(18), dp(10));
        root.setGravity(Gravity.TOP);

        // ---------------- 左栏：彩光控制 ----------------
        LinearLayout left = Ui.column(c);
        left.setLayoutParams(Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        left.setPadding(0, 0, dp(9), 0);
        left.addView(Ui.sectionHeader(c, "灯光设置"));

        LinearLayout cardHolder = Ui.column(c);
        cardHolder.setLayoutParams(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        left.addView(cardHolder);

        LinearLayout card = Ui.card(c);
        LinearLayout head = Ui.row(c);
        head.addView(Ui.iconCircle(c, Res.grad_pink, Res.ic_palette, 46, 12));
        LinearLayout info = Ui.column(c);
        info.setLayoutParams(Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        info.setPadding(dp(12), 0, dp(8), 0);
        info.addView(Ui.text(c, "彩光控制", 16, Ui.TEXT_PRIMARY, true));
        tvOnOff = Ui.text(c, "已开启", 13, Ui.TEXT_SECONDARY, false);
        tvOnOff.setPadding(0, dp(3), 0, 0);
        info.addView(tvOnOff);
        head.addView(info);
        swLight = Ui.switchView(c);
        swLight.setChecked(Prefs.get(c).isPowerOn());
        tvOnOff.setText(Prefs.get(c).isPowerOn() ? "已开启" : "已关闭");
        swLight.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Prefs.get(activity).setPowerOn(isChecked);
                LedOutput.power(activity, isChecked);
                tvOnOff.setText(isChecked ? "已开启" : "已关闭");
                buttonView.invalidate();
                host.refreshPages();
            }
        });
        head.addView(swLight);
        card.addView(head);

        card.addView(Ui.divider(c));

        LinearLayout brow = Ui.row(c);
        brow.addView(Ui.text(c, "彩光亮度", 13, Ui.TEXT_SECONDARY, false));
        View spacer = new View(c);
        spacer.setLayoutParams(Ui.lp(0, 1, 1f));
        brow.addView(spacer);
        tvBright = Ui.text(c, "100%", 14, Ui.TEXT_PRIMARY, true);
        brow.addView(tvBright);
        card.addView(brow);

        sbBright = Ui.seekBar(c, Prefs.get(c).getBrightness(), 100);
        tvBright.setText(Prefs.get(c).getBrightness() + "%");
        sbBright.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvBright.setText(progress + "%");
                if (fromUser) {
                    Prefs.get(activity).setBrightness(progress);
                    scheduleSend(sendBrightTask); // 拖动过程中动态调节亮度
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
        card.addView(sbBright);
        cardHolder.addView(card);

        // 当前颜色 / 手调
        LinearLayout card2 = Ui.card(c);
        LinearLayout.LayoutParams c2lp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        c2lp.topMargin = dp(12);
        card2.setLayoutParams(c2lp);
        LinearLayout rgbRow = Ui.row(c);
        colorPreview = new View(c);
        colorPreview.setLayoutParams(Ui.lp(dp(46), dp(38)));
        colorPreview.setBackground(Ui.roundRect(c, currentColor, 10, 0x33FFFFFF));
        rgbRow.addView(colorPreview);
        tvRgb = Ui.text(c, "R 255  G 61  B 0", 15, Ui.TEXT_PRIMARY, true);
        tvRgb.setPadding(dp(14), 0, 0, 0);
        rgbRow.addView(tvRgb);
        card2.addView(rgbRow);

        TextView btnManual = Ui.text(c, "RGB 手调", 14, Ui.TEXT_PRIMARY, true);
        btnManual.setGravity(Gravity.CENTER);
        Res.bg(btnManual, Res.bg_seg_normal);
        LinearLayout.LayoutParams bmlp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        bmlp.topMargin = dp(12);
        btnManual.setLayoutParams(bmlp);
        btnManual.setClickable(true);
        btnManual.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.showRgbDialog(currentColor, new LanternPanel.OnColorPicked() {
                    @Override
                    public void onPicked(int color) {
                        applyColor(color);
                    }
                });
            }
        });
        card2.addView(btnManual);
        cardHolder.addView(card2);

        // ---------------- 右栏：颜色选择 ----------------
        LinearLayout right = Ui.column(c);
        right.setLayoutParams(Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.05f));
        right.setPadding(dp(9), 0, 0, 0);
        right.addView(Ui.sectionHeader(c, "颜色选择"));

        square = new SquareColorView(c);
        square.setColor(currentColor);
        // 不加额外上边距：与左栏卡片顶部严格对齐
        square.setLayoutParams(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        square.setListener(new SquareColorView.OnColorChanged() {
            @Override
            public void onColorChanged(int color) {
                currentColor = color;
                Prefs.get(activity).setColor(color);
                if (hueBar != null) hueBar.setHue(square.getHsv()[0]);
                updateRgbText();
                scheduleSend(sendColorTask);
            }
        });
        right.addView(square);

        hueBar = new HueBarView(c);
        LinearLayout.LayoutParams hlp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(20));
        hlp.topMargin = dp(8);
        hueBar.setLayoutParams(hlp);
        hueBar.setHue(square.getHsv()[0]);
        hueBar.setListener(new HueBarView.OnHueChanged() {
            @Override
            public void onHueChanged(float hue) {
                square.setHue(hue);
                currentColor = square.getColor();
                Prefs.get(activity).setColor(currentColor);
                updateRgbText();
                scheduleSend(sendColorTask);
            }
        });
        right.addView(hueBar);

        // 预设颜色
        LinearLayout presetWrap = Ui.column(c);
        LinearLayout.LayoutParams pwp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        pwp.topMargin = dp(14);
        presetWrap.setLayoutParams(pwp);
        for (int r = 0; r < 1; r++) {
            LinearLayout line = Ui.row(c);
            for (int i = 0; i < 12; i++) {
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
                        applyColor(color);
                    }
                });
                line.addView(dot);
            }
            presetWrap.addView(line);
        }
        right.addView(presetWrap);

        root.addView(left);
        root.addView(right);
        updateRgbText();
        return root;
    }

    private void applyColor(int color) {
        currentColor = color;
        Prefs.get(activity).setColor(color);
        square.setColor(color);
        hueBar.setHue(square.getHsv()[0]);
        updateRgbText();
        LedOutput.sendColor(activity, color);
    }

    private void updateRgbText() {
        int r = (currentColor >> 16) & 0xFF;
        int g = (currentColor >> 8) & 0xFF;
        int b = currentColor & 0xFF;
        if (tvRgb != null) tvRgb.setText("R " + r + "  G " + g + "  B " + b);
        // 同步左侧颜色预览块
        if (colorPreview != null) {
            colorPreview.setBackground(Ui.roundRect(activity, currentColor, 10, 0x33FFFFFF));
            colorPreview.invalidate();
        }
    }

    @Override
    public void onShow() {
        refresh();
        if (leftScroll != null) leftScroll.scrollTo(0, 0);
    }

    @Override
    public void onHide() {
        sendHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public void refresh() {
        if (swLight == null) return;
        boolean on = Prefs.get(activity).isPowerOn();
        if (swLight.isChecked() != on) swLight.setChecked(on);
        tvOnOff.setText(on ? "已开启" : "已关闭");
        int b = Prefs.get(activity).getBrightness();
        if (sbBright != null && sbBright.getProgress() != b) {
            sbBright.setProgress(b);
            tvBright.setText(b + "%");
        }
    }

    /** 供外部（如色盘取色）调用 */
    public void setColorFromOutside(int color) {
        if (square == null) return;
        applyColor(color);
    }

    /** 预览色块 */
    public static int alphaColor(int color) {
        return Color.argb(255, Color.red(color), Color.green(color), Color.blue(color));
    }
}
