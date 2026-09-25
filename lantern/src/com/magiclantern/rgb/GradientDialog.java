package com.magiclantern.rgb;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** 自定义渐变编辑器：名称 + 起始色 / 结束色 + 速度，带实时预览 */
public class GradientDialog {

    public interface OnSaved {
        /** @param oldName 编辑前的名称（新建时为 null） */
        void onSaved(String oldName, GradientItem item);
    }

    public static void show(final Context activity, final GradientItem origin,
                            final OnSaved onSaved) {
        final Context c = activity;
        final boolean editing = origin != null;
        final GradientItem work = editing
                ? origin.copy()
                : new GradientItem("", 0xFFFF3B3B, 0xFF3B6BFF, Prefs.get(c).getSpeed());

        final Dialog dialog = new Dialog(c);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = Ui.column(c);
        root.setBackground(Ui.roundRect(c, 0xFF141926, 20, 0x40FFFFFF));
        root.setPadding(dp(c, 22), dp(c, 16), dp(c, 22), dp(c, 18));

        // ---------- 标题 ----------
        root.addView(Ui.text(c, editing ? "编辑渐变" : "新建渐变", 17, Ui.TEXT_PRIMARY, true));
        TextView hint = Ui.text(c, "选两个颜色，保存后立即下发到灯带", 12, Ui.TEXT_SECONDARY, false);
        hint.setPadding(0, dp(c, 4), 0, dp(c, 12));
        root.addView(hint);

        // ---------- 名称 ----------
        final EditText etName = new EditText(c);
        etName.setSingleLine(true);
        etName.setHint("渐变名称（如：晨曦）");
        etName.setText(work.name);
        etName.setTextColor(Ui.TEXT_PRIMARY);
        etName.setHintTextColor(Ui.TEXT_THIRD);
        etName.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.sp(c, 14));
        Res.bg(etName, Res.bg_card_inner);
        etName.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10));
        etName.setLayoutParams(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(etName);

        // ---------- 预览条 ----------
        final GradientPreviewView preview = new GradientPreviewView(c);
        preview.setColors(work.color1, work.color2);
        LinearLayout.LayoutParams plp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 44));
        plp.topMargin = dp(c, 12);
        preview.setLayoutParams(plp);
        root.addView(preview);

        // ---------- 色盘 + 参数 ----------
        LinearLayout bottom = Ui.row(c);
        bottom.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams blp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(c, 14);
        bottom.setLayoutParams(blp);

        final SquareColorView wheel = new SquareColorView(c);
        int wheelSize = dp(c, 176);
        wheel.setLayoutParams(new LinearLayout.LayoutParams(wheelSize, wheelSize));

        LinearLayout right = Ui.column(c);
        right.setLayoutParams(Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        right.setPadding(dp(c, 18), dp(c, 4), 0, 0);

        // 起始色 / 结束色
        LinearLayout swatchRow = Ui.row(c);
        swatchRow.setLayoutParams(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        final View swatchA = new View(c);
        final View swatchB = new View(c);
        swatchRow.addView(makeSwatch(c, swatchA, "起始色"), Ui.lp(0, dp(c, 64), 1f));
        swatchRow.addView(makeSwatch(c, swatchB, "结束色"), Ui.lp(0, dp(c, 64), 1f));
        right.addView(swatchRow);

        // 色相条
        final HueBarView hue = new HueBarView(c);
        LinearLayout.LayoutParams hlp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 22));
        hlp.topMargin = dp(c, 14);
        hue.setLayoutParams(hlp);
        right.addView(hue);

        // 速度
        LinearLayout speedRow = Ui.row(c);
        LinearLayout.LayoutParams srp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        srp.topMargin = dp(c, 12);
        speedRow.setLayoutParams(srp);
        speedRow.addView(Ui.text(c, "速度", 13, Ui.TEXT_SECONDARY, false));
        View sp = new View(c);
        sp.setLayoutParams(Ui.lp(0, 1, 1f));
        speedRow.addView(sp);
        final TextView tvSpeed = Ui.text(c, String.valueOf(work.speed), 13, Ui.TEXT_PRIMARY, true);
        speedRow.addView(tvSpeed);
        right.addView(speedRow);

        final SeekBar sbSpeed = Ui.seekBar(c, work.speed, 100);
        sbSpeed.setLayoutParams(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        sbSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvSpeed.setText(String.valueOf(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        right.addView(sbSpeed);

        bottom.addView(wheel);
        bottom.addView(right);
        root.addView(bottom);

        // ---------- 按钮 ----------
        LinearLayout btnRow = Ui.row(c);
        btnRow.setGravity(Gravity.END);
        LinearLayout.LayoutParams brlp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        brlp.topMargin = dp(c, 16);
        btnRow.setLayoutParams(brlp);

        TextView cancel = button(c, "取消", false);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        TextView save = button(c, "保存", true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(c, 12);
        save.setLayoutParams(slp);
        btnRow.addView(cancel);
        btnRow.addView(save);
        root.addView(btnRow);

        // ---------- 颜色联动 ----------
        final int[] target = new int[]{0}; // 0=起始色 1=结束色

        final Runnable syncSwatches = new Runnable() {
            @Override
            public void run() {
                swatchA.setBackground(swatchBg(c, work.color1, target[0] == 0));
                swatchB.setBackground(swatchBg(c, work.color2, target[0] == 1));
                preview.setColors(work.color1, work.color2);
            }
        };

        wheel.setListener(new SquareColorView.OnColorChanged() {
            @Override
            public void onColorChanged(int color) {
                if (target[0] == 0) {
                    work.color1 = color;
                } else {
                    work.color2 = color;
                }
                syncSwatches.run();
            }
        });

        hue.setListener(new HueBarView.OnHueChanged() {
            @Override
            public void onHueChanged(float h) {
                wheel.setHue(h);
                int color = wheel.getColor();
                if (target[0] == 0) {
                    work.color1 = color;
                } else {
                    work.color2 = color;
                }
                syncSwatches.run();
            }
        });

        View.OnClickListener selectTarget = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                target[0] = (v == swatchA) ? 0 : 1;
                int color = target[0] == 0 ? work.color1 : work.color2;
                wheel.setColor(color);
                float[] h = new float[3];
                Color.colorToHSV(color, h);
                hue.setHue(h[0]);
                syncSwatches.run();
            }
        };
        swatchA.setOnClickListener(selectTarget);
        swatchB.setOnClickListener(selectTarget);

        // 初始：编辑起始色
        wheel.setColor(work.color1);
        float[] initHsv = new float[3];
        Color.colorToHSV(work.color1, initHsv);
        hue.setHue(initHsv[0]);
        syncSwatches.run();

        // ---------- 保存 ----------
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String name = GradientItem.clean(etName.getText().toString());
                if (name.length() == 0) {
                    name = "渐变" + (Prefs.get(c).getGradients().size() + 1);
                }
                work.name = name;
                work.speed = sbSpeed.getProgress();
                if (onSaved != null) onSaved.onSaved(editing ? origin.name : null, work);
                dialog.dismiss();
            }
        });

        dialog.setContentView(root);
        dialog.show();

        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            int width = Math.min((int) (dm.widthPixels * 0.55f), Ui.dp(c, 680));
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    // ---------------- 小组件 ----------------

    private static LinearLayout makeSwatch(Context c, final View swatch, String label) {
        LinearLayout col = Ui.column(c);
        col.setPadding(dp(c, 4), 0, dp(c, 4), 0);
        swatch.setClickable(true);
        swatch.setFocusable(true);
        Ui.addPressEffect(swatch);
        swatch.setLayoutParams(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 40)));
        col.addView(swatch);
        TextView tv = Ui.text(c, label, 12, Ui.TEXT_SECONDARY, false);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(c, 6), 0, 0);
        col.addView(tv);
        return col;
    }

    private static GradientDrawable swatchBg(Context c, int color, boolean active) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(Ui.dp(c, 10));
        d.setColor(color);
        d.setStroke(Ui.dp(c, active ? 2 : 1), active ? 0xFFFFFFFF : 0x33FFFFFF);
        return d;
    }

    private static TextView button(Context c, String text, boolean primary) {
        TextView tv = Ui.text(c, text, 15, primary ? Color.WHITE : Ui.TEXT_SECONDARY, true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(c, 30), dp(c, 12), dp(c, 30), dp(c, 12));
        tv.setBackground(primary
                ? Ui.roundRect(c, 0xFF4A6CF7, 14, null)
                : Ui.roundRect(c, 0x1AFFFFFF, 14, null));
        tv.setClickable(true);
        tv.setFocusable(true);
        Ui.addPressEffect(tv);
        return tv;
    }

    private static int dp(Context c, float v) {
        return Ui.dp(c, v);
    }
}
