package com.magiclantern.rgb;

import android.app.TimePickerDialog;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

/** 闹钟管理：定时开灯 / 定时关灯 / 重复周期 */
public class AlarmPage extends Page {

    private Switch swOn;
    private Switch swOff;
    private TextView tvTimeOn;
    private TextView tvTimeOff;
    private final TextView[] days = new TextView[7];
    private static final String[] DAY_LABEL = {"日", "一", "二", "三", "四", "五", "六"};

    public AlarmPage(LanternPanel host) {
        super(host);
    }

    @Override
    public String getTitle() {
        return "闹钟管理";
    }

    @Override
    public String getSubtitle() {
        return "定时开启或关闭灯光";
    }

    @Override
    public boolean showBack() {
        return true;
    }

    @Override
    protected View build(Context c) {
        final Prefs prefs = Prefs.get(c);

        LinearLayout root = Ui.column(c);
        root.setPadding(dp(18), dp(4), dp(18), dp(10));

        // 定时开灯
        LinearLayout card1 = Ui.card(c);
        LinearLayout head1 = Ui.row(c);
        head1.addView(Ui.iconCircle(c, Res.grad_green, Res.ic_clock, 44, 11));
        LinearLayout info1 = Ui.column(c);
        info1.setLayoutParams(Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        info1.setPadding(dp(12), 0, dp(8), 0);
        info1.addView(Ui.text(c, "定时开灯", 16, Ui.TEXT_PRIMARY, true));
        info1.addView(Ui.text(c, "到点自动开灯", 12, Ui.TEXT_SECONDARY, false));
        head1.addView(info1);
        swOn = Ui.switchView(c);
        swOn.setChecked(prefs.isTimingOnEnabled());
        head1.addView(swOn);
        card1.addView(head1);

        tvTimeOn = Ui.text(c, timeText(prefs.getTimingOnHour(), prefs.getTimingOnMinute()),
                30, Ui.TEXT_PRIMARY, true);
        tvTimeOn.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams t1lp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(70));
        t1lp.topMargin = dp(10);
        tvTimeOn.setLayoutParams(t1lp);
        Res.bg(tvTimeOn, Res.bg_card_inner);
        tvTimeOn.setClickable(true);
        tvTimeOn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickTime(true);
            }
        });
        card1.addView(tvTimeOn);
        root.addView(card1);

        // 定时关灯
        LinearLayout card2 = Ui.card(c);
        LinearLayout.LayoutParams c2lp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        c2lp.topMargin = dp(12);
        card2.setLayoutParams(c2lp);
        LinearLayout head2 = Ui.row(c);
        head2.addView(Ui.iconCircle(c, Res.grad_orange, Res.ic_moon, 44, 11));
        LinearLayout info2 = Ui.column(c);
        info2.setLayoutParams(Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        info2.setPadding(dp(12), 0, dp(8), 0);
        info2.addView(Ui.text(c, "定时关灯", 16, Ui.TEXT_PRIMARY, true));
        info2.addView(Ui.text(c, "到点自动关灯", 12, Ui.TEXT_SECONDARY, false));
        head2.addView(info2);
        swOff = Ui.switchView(c);
        swOff.setChecked(prefs.isTimingOffEnabled());
        head2.addView(swOff);
        card2.addView(head2);

        tvTimeOff = Ui.text(c, timeText(prefs.getTimingOffHour(), prefs.getTimingOffMinute()),
                30, Ui.TEXT_PRIMARY, true);
        tvTimeOff.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams t2lp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(70));
        t2lp.topMargin = dp(10);
        tvTimeOff.setLayoutParams(t2lp);
        Res.bg(tvTimeOff, Res.bg_card_inner);
        tvTimeOff.setClickable(true);
        tvTimeOff.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickTime(false);
            }
        });
        card2.addView(tvTimeOff);
        root.addView(card2);

        // 重复周期
        LinearLayout card3 = Ui.card(c);
        LinearLayout.LayoutParams c3lp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        c3lp.topMargin = dp(12);
        card3.setLayoutParams(c3lp);
        card3.addView(Ui.text(c, "重复", 16, Ui.TEXT_PRIMARY, true));
        card3.addView(Ui.text(c, "不选择表示仅执行一次", 12, Ui.TEXT_SECONDARY, false));

        LinearLayout dayRow = Ui.row(c);
        LinearLayout.LayoutParams drlp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        drlp.topMargin = dp(12);
        dayRow.setLayoutParams(drlp);
        int repeat = prefs.getTimingRepeat();
        for (int i = 0; i < 7; i++) {
            final int bit = i;
            TextView day = Ui.text(c, DAY_LABEL[i], 14, Ui.TEXT_SECONDARY, false);
            day.setGravity(Gravity.CENTER);
            Res.bg(day, Res.bg_chip);
            day.setSelected((repeat & (1 << i)) != 0);
            if (day.isSelected()) day.setTextColor(0xFF7B5CFF);
            LinearLayout.LayoutParams dlp = Ui.lp(0, dp(40), 1f);
            dlp.setMargins(dp(3), 0, dp(3), 0);
            day.setLayoutParams(dlp);
            day.setClickable(true);
            day.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean selected = !v.isSelected();
                    v.setSelected(selected);
                    ((TextView) v).setTextColor(selected ? 0xFF7B5CFF : Ui.TEXT_SECONDARY);
                }
            });
            days[i] = day;
            dayRow.addView(day);
        }
        card3.addView(dayRow);
        root.addView(card3);

        // 保存
        TextView btnSave = Ui.text(c, "保存定时设置", 15, Ui.TEXT_PRIMARY, true);
        btnSave.setGravity(Gravity.CENTER);
        Res.bg(btnSave, Res.bg_seg_selected);
        LinearLayout.LayoutParams blp = Ui.lp(dp(200), dp(48));
        blp.topMargin = dp(18);
        btnSave.setLayoutParams(blp);
        btnSave.setClickable(true);
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        root.addView(btnSave);

        return Ui.scrollWrap(c, root);
    }

    private void pickTime(final boolean isOn) {
        final Prefs prefs = Prefs.get(activity);
        int hour = isOn ? prefs.getTimingOnHour() : prefs.getTimingOffHour();
        int minute = isOn ? prefs.getTimingOnMinute() : prefs.getTimingOffMinute();
        TimePickerDialog dialog = new TimePickerDialog(activity,
                new TimePickerDialog.OnTimeSetListener() {
                    @Override
                    public void onTimeSet(TimePicker view, int hourOfDay, int minute) {
                        if (isOn) {
                            prefs.setTimingOn(hourOfDay, minute);
                            tvTimeOn.setText(timeText(hourOfDay, minute));
                        } else {
                            prefs.setTimingOff(hourOfDay, minute);
                            tvTimeOff.setText(timeText(hourOfDay, minute));
                        }
                    }
                }, hour, minute, true);
        dialog.show();
    }

    private static String timeText(int hour, int minute) {
        return (hour < 10 ? "0" : "") + hour + ":" + (minute < 10 ? "0" : "") + minute;
    }

    private void save() {
        Prefs prefs = Prefs.get(activity);
        int repeat = 0;
        for (int i = 0; i < 7; i++) {
            if (days[i] != null && days[i].isSelected()) repeat |= (1 << i);
        }
        prefs.setTimingOnEnabled(swOn.isChecked());
        prefs.setTimingOffEnabled(swOff.isChecked());
        prefs.setTimingRepeat(repeat);
        TimingReceiver.scheduleAll(activity);
        Toast.makeText(activity, "已保存定时设置", Toast.LENGTH_SHORT).show();
    }
}
