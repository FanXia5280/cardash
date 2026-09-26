package com.magiclantern.rgb;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 设备管理页：扫描 + 连接/断开 BLE 灯控设备 */
public class DevicePage extends Page {

    private TextView tvState;
    private TextView btnScan;
    private ListView listView;
    private DeviceAdapter adapter;

    public DevicePage(LanternPanel host) {
        super(host);
    }

    @Override
    public String getTitle() {
        return "蓝牙连接";
    }

    @Override
    public String getSubtitle() {
        return "扫描并连接您的氛围灯";
    }

    @Override
    public boolean showBack() {
        return true;
    }

    @Override
    protected View build(Context c) {
        LinearLayout root = Ui.column(c);
        root.setPadding(dp(18), dp(4), dp(18), dp(10));

        // 状态卡
        LinearLayout card = Ui.card(c);
        LinearLayout head = Ui.row(c);
        head.addView(Ui.iconCircle(c, Res.grad_blue, Res.ic_bt, 46, 12));
        LinearLayout info = Ui.column(c);
        info.setLayoutParams(Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        info.setPadding(dp(12), 0, dp(8), 0);
        info.addView(Ui.text(c, "蓝牙连接状态", 16, Ui.TEXT_PRIMARY, true));
        tvState = Ui.text(c, "未连接", 13, Ui.TEXT_SECONDARY, false);
        tvState.setPadding(0, dp(3), 0, 0);
        info.addView(tvState);
        head.addView(info);

        btnScan = Ui.text(c, "扫描", 14, Ui.TEXT_PRIMARY, true);
        btnScan.setGravity(Gravity.CENTER);
        Res.bg(btnScan, Res.bg_seg_selected);
        btnScan.setLayoutParams(Ui.lp(dp(92), dp(42)));
        btnScan.setClickable(true);
        btnScan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.toggleScan();
            }
        });
        head.addView(btnScan);
        card.addView(head);
        root.addView(card);

        // 设备列表
        LinearLayout card2 = Ui.card(c);
        LinearLayout.LayoutParams c2lp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        c2lp.topMargin = dp(12);
        card2.setLayoutParams(c2lp);
        card2.addView(Ui.text(c, "附近的设备", 15, Ui.TEXT_PRIMARY, true));

        adapter = new DeviceAdapter(c);
        listView = new ListView(c);
        listView.setDivider(null);
        listView.setDividerHeight(dp(8));
        listView.setSelector(new android.graphics.drawable.ColorDrawable(0x00000000));
        listView.setAdapter(adapter);
        LinearLayout.LayoutParams llp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        llp.topMargin = dp(8);
        listView.setLayoutParams(llp);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                BleController.Conn conn = adapter.getItem(position);
                if (conn == null) return;
                if (conn.state == BleController.STATE_CONNECTED) {
                    host.disconnect(conn);
                } else if (conn.state == BleController.STATE_IDLE) {
                    host.connect(conn);
                }
            }
        });
        card2.addView(listView);
        root.addView(card2);
        return root;
    }

    @Override
    public void onShow() {
        refresh();
    }

    @Override
    public void refresh() {
        if (tvState != null) tvState.setText(host.getConnectionSummary());
        if (btnScan != null) {
            btnScan.setText(BleController.get(activity).isScanning() ? "停止" : "扫描");
        }
        if (adapter != null) adapter.reload();
    }

    private class DeviceAdapter extends BaseAdapter {

        private final Context ctx;
        private final List<BleController.Conn> list = new ArrayList<BleController.Conn>();

        DeviceAdapter(Context c) {
            ctx = c;
        }

        void reload() {
            BleController ble = BleController.get(ctx);
            Map<String, BleController.Conn> map = new LinkedHashMap<String, BleController.Conn>();
            for (BleController.Conn c : ble.getFoundDevices()) map.put(c.address, c);
            for (BleController.Conn c : ble.getConnections()) map.put(c.address, c);
            list.clear();
            list.addAll(map.values());
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return list.size();
        }

        @Override
        public BleController.Conn getItem(int position) {
            return list.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
            } else {
                row = Ui.row(ctx);
                row.setPadding(dp(14), dp(14), dp(14), dp(14));
                row.addView(Ui.iconCircle(ctx, Res.grad_purple, Res.ic_speaker, 38, 9));

                LinearLayout mid = Ui.column(ctx);
                mid.setLayoutParams(Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                mid.setPadding(dp(12), 0, dp(8), 0);
                TextView name = Ui.text(ctx, "", 15, Ui.TEXT_PRIMARY, true);
                TextView addr = Ui.text(ctx, "", 12, Ui.TEXT_SECONDARY, false);
                addr.setPadding(0, dp(3), 0, 0);
                mid.addView(name);
                mid.addView(addr);
                row.addView(mid);

                TextView state = Ui.text(ctx, "", 12, Ui.TEXT_SECONDARY, false);
                state.setPadding(dp(8), dp(6), dp(8), dp(6));
                row.addView(state);
            }

            BleController.Conn conn = list.get(position);
            LinearLayout mid = (LinearLayout) row.getChildAt(1);
            ((TextView) mid.getChildAt(0)).setText(conn.name);
            ((TextView) mid.getChildAt(1)).setText(conn.address);
            TextView stateView = (TextView) row.getChildAt(2);

            String stateText;
            int color;
            if (conn.state == BleController.STATE_CONNECTED) {
                stateText = "已连接";
                color = 0xFF22C55E;
            } else if (conn.state == BleController.STATE_CONNECTING) {
                stateText = "连接中";
                color = 0xFF4A6CF7;
            } else {
                stateText = "连接";
                color = Ui.TEXT_SECONDARY;
            }
            stateView.setText(stateText);
            stateView.setTextColor(color);

            GradientDrawable bg = Ui.roundRect(ctx, Ui.CARD_INNER, 14,
                    conn.state == BleController.STATE_CONNECTED ? 0x6622C55E : 0x14FFFFFF);
            row.setBackground(bg);
            return row;
        }
    }
}
