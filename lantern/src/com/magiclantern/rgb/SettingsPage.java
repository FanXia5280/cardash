package com.magiclantern.rgb;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** 设置页：常规设置 / 设备管理 / 关于 */
public class SettingsPage extends Page {

    private TextView tvBtValue;
    private TextView tvCountValue;
    private TextView tvSeqValue;
    private Switch swAuto;

    private static final String[] SEQ_NAMES = {"R G B", "R B G", "G R B", "G B R", "B R G", "B G R"};

    public SettingsPage(LanternPanel host) {
        super(host);
    }

    @Override
    public String getTitle() {
        return "设置";
    }

    @Override
    public boolean showBack() {
        return true;
    }

    @Override
    protected View build(Context c) {
        LinearLayout root = Ui.row(c);
        root.setPadding(dp(18), dp(4), dp(18), dp(10));
        root.setGravity(Gravity.TOP);

        // 左栏：头部信息
        LinearLayout left = Ui.column(c);
        left.setLayoutParams(Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.85f));
        left.setPadding(0, 0, dp(9), 0);
        left.setGravity(Gravity.CENTER_HORIZONTAL);

        View topSpace = new View(c);
        topSpace.setLayoutParams(Ui.lp(1, 0, 0.6f));
        left.addView(topSpace);

        left.addView(Ui.iconCircle(c, Res.grad_purple, Res.ic_gear, 86, 22));

        TextView tvTitle = Ui.text(c, "设置", 24, Ui.TEXT_PRIMARY, true);
        LinearLayout.LayoutParams tlp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(14);
        tvTitle.setLayoutParams(tlp);
        left.addView(tvTitle);

        TextView tvSub = Ui.text(c, "管理您的应用偏好", 13, Ui.TEXT_SECONDARY, false);
        LinearLayout.LayoutParams slp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(6);
        tvSub.setLayoutParams(slp);
        left.addView(tvSub);

        // 右栏：设置项
        LinearLayout right = Ui.column(c);
        right.setLayoutParams(Ui.lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.15f));
        right.setPadding(dp(9), 0, 0, 0);

        LinearLayout holder = Ui.column(c);
        holder.setPadding(0, 0, dp(2), 0);

        // ---- 常规设置 ----
        holder.addView(sectionTitle(c, "常规设置"));
        LinearLayout card1 = Ui.card(c);

        LinearLayout rowCount = Ui.settingRow(c, Res.grad_cyan, Res.ic_palette,
                "灯带总点数", Prefs.get(c).getLedCount() + " 点", true);
        tvCountValue = (TextView) ((LinearLayout) rowCount.getChildAt(1)).getChildAt(1);
        rowCount.setClickable(true);
        rowCount.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCountDialog();
            }
        });
        card1.addView(rowCount);
        card1.addView(Ui.divider(c));

        LinearLayout rowAuto = Ui.row(c);
        rowAuto.setPadding(0, dp(10), 0, dp(10));
        rowAuto.addView(Ui.iconCircle(c, Res.grad_green, Res.ic_bt, 38, 9));
        LinearLayout autoInfo = Ui.column(c);
        autoInfo.setLayoutParams(Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        autoInfo.setPadding(dp(12), 0, dp(8), 0);
        autoInfo.addView(Ui.text(c, "自动连接设备", 15, Ui.TEXT_PRIMARY, false));
        autoInfo.addView(Ui.text(c, "启动后自动连接上次设备", 12, Ui.TEXT_SECONDARY, false));
        rowAuto.addView(autoInfo);
        swAuto = Ui.switchView(c);
        swAuto.setChecked(Prefs.get(c).isAutoConnect());
        swAuto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Prefs.get(activity).setAutoConnect(isChecked);
            }
        });
        rowAuto.addView(swAuto);
        card1.addView(rowAuto);
        card1.addView(Ui.divider(c));

        LinearLayout rowFilter = Ui.row(c);
        rowFilter.setPadding(0, dp(10), 0, dp(10));
        rowFilter.addView(Ui.iconCircle(c, Res.grad_blue, Res.ic_speaker, 38, 9));
        LinearLayout filterInfo = Ui.column(c);
        filterInfo.setLayoutParams(Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        filterInfo.setPadding(dp(12), 0, dp(8), 0);
        filterInfo.addView(Ui.text(c, "仅显示灯具设备", 15, Ui.TEXT_PRIMARY, false));
        filterInfo.addView(Ui.text(c, "只列出 MELK- 开头的蓝牙设备", 12, Ui.TEXT_SECONDARY, false));
        rowFilter.addView(filterInfo);
        Switch swFilter = Ui.switchView(c);
        swFilter.setChecked(Prefs.get(c).isNameFilterEnabled());
        swFilter.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Prefs.get(activity).setNameFilterEnabled(isChecked);
            }
        });
        rowFilter.addView(swFilter);
        card1.addView(rowFilter);
        holder.addView(card1);

        // ---- 设备管理 ----
        holder.addView(sectionTitle(c, "设备管理"));
        LinearLayout card2 = Ui.card(c);
        LinearLayout rowBt = Ui.settingRow(c, Res.grad_blue, Res.ic_bt,
                "蓝牙连接", "未连接", true);
        tvBtValue = (TextView) ((LinearLayout) rowBt.getChildAt(1)).getChildAt(1);
        rowBt.setClickable(true);
        rowBt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.openSub(new DevicePage(host));
            }
        });
        card2.addView(rowBt);
        card2.addView(Ui.divider(c));

        LinearLayout rowSeq = Ui.settingRow(c, Res.grad_orange, Res.ic_light,
                "调整线序", SEQ_NAMES[Prefs.get(c).getPinSequence()], true);
        tvSeqValue = (TextView) ((LinearLayout) rowSeq.getChildAt(1)).getChildAt(1);
        rowSeq.setClickable(true);
        rowSeq.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSequenceDialog();
            }
        });
        card2.addView(rowSeq);
        card2.addView(Ui.divider(c));

        LinearLayout rowProbe = Ui.settingRow(c, Res.grad_green, Res.ic_scene,
                "协议探测（进阶）", "寻找设备端自定义渐变命令", true);
        rowProbe.setClickable(true);
        rowProbe.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.openSub(new ProbePage(host));
            }
        });
        card2.addView(rowProbe);
        holder.addView(card2);

        // ---- 关于 ----
        holder.addView(sectionTitle(c, "关于"));
        LinearLayout card3 = Ui.card(c);
        LinearLayout rowAbout = Ui.settingRow(c, Res.grad_purple, Res.ic_gear,
                "关于本应用", "版本 1.0.0", true);
        rowAbout.setClickable(true);
        rowAbout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(activity)
                        .setTitle("氛围灯控制")
                        .setMessage("版本 1.0.0\n\n支持 BLE 蓝牙灯控：调色、灯效模式、亮度、定时。")
                        .setPositiveButton("确定", null)
                        .show();
            }
        });
        card3.addView(rowAbout);
        holder.addView(card3);

        right.addView(Ui.scrollWrap(c, holder));

        root.addView(left);
        root.addView(right);
        return root;
    }

    private TextView sectionTitle(Context c, String text) {
        TextView tv = Ui.text(c, text, 13, Ui.TEXT_SECONDARY, false);
        tv.setPadding(dp(4), dp(10), 0, dp(8));
        return tv;
    }

    private void showCountDialog() {
        final EditText et = new EditText(activity);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(Prefs.get(activity).getLedCount()));
        et.setTextColor(0xFFFFFFFF);
        new AlertDialog.Builder(activity)
                .setTitle("灯带总点数")
                .setView(et)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        int count;
                        try {
                            count = Integer.parseInt(et.getText().toString());
                        } catch (Exception e) {
                            count = 60;
                        }
                        Prefs.get(activity).setLedCount(count);
                        tvCountValue.setText(count + " 点");
                        LedOutput.sendPixelCount(activity, count);
                        Toast.makeText(activity, "已保存", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSequenceDialog() {
        new AlertDialog.Builder(activity)
                .setTitle("调整线序")
                .setSingleChoiceItems(SEQ_NAMES, Prefs.get(activity).getPinSequence(),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Prefs.get(activity).setPinSequence(which);
                                tvSeqValue.setText(SEQ_NAMES[which]);
                                LedOutput.sendPinSequence(activity);
                                dialog.dismiss();
                            }
                        })
                .show();
    }

    @Override
    public void onShow() {
        refresh();
    }

    @Override
    public void refresh() {
        if (tvBtValue != null) tvBtValue.setText(host.getConnectionSummary());
        if (swAuto != null) {
            boolean auto = Prefs.get(activity).isAutoConnect();
            if (swAuto.isChecked() != auto) swAuto.setChecked(auto);
        }
        if (tvCountValue != null) {
            tvCountValue.setText(Prefs.get(activity).getLedCount() + " 点");
        }
        if (tvSeqValue != null) {
            tvSeqValue.setText(SEQ_NAMES[Prefs.get(activity).getPinSequence()]);
        }
    }
}
