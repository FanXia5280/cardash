package com.magiclantern.rgb;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 氛围灯控制面板 —— 纯代码构建，**不依赖 res/ 布局与资源表**。
 *
 * 两种承载方式共用同一份界面逻辑：
 *   1. 独立 APK：MainActivity 直接 {@code setContentView(new LanternPanel(this))}；
 *   2. 车机桌面：「桌面设置 → 氛围灯设置」右侧容器里 addView 进来（embedded=true），
 *      这时底部返回键交给宿主处理，底栈时不再 finish()。
 *
 * 保活不在这里 —— 见 LanternBootstrap：它挂在宿主进程上（车机桌面 / 地图），
 * 只要进程活着就保持 BLE 连接、并按最后一次设置继续播放渐变。
 */
public class LanternPanel extends FrameLayout implements BleController.Listener {

    public interface OnColorPicked {
        void onPicked(int color);
    }

    private static final int REQ_PERMISSION = 1001;
    private static final int REQ_ENABLE_BT = 1002;

    /** 宿主 Activity：独立运行时是 MainActivity，嵌入时是车机桌面的设置页 */
    private final Activity host;
    /** 嵌入模式（右侧容器里的一块 View，而不是整屏 Activity） */
    private final boolean embedded;

    private FrameLayout content;
    private TextView topTitle;
    private TextView topSubtitle;
    private ImageView topBack;
    private ImageView topBtIcon;
    private ImageView btnPower;

    private Page[] tabPages;
    private final List<Page> stack = new ArrayList<Page>();
    private Page lastShown;

    private final BleController ble;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean autoConnectPending;
    private boolean timingRegistered;
    private boolean permissionPending;

    private final BroadcastReceiver timingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean on = intent.getBooleanExtra(TimingReceiver.EXTRA_ON, true);
            if (ble.isConnected()) {
                LedOutput.power(getContext(), on);
                toast(on ? "定时开灯" : "定时关灯");
                refreshPages();
            }
        }
    };

    public LanternPanel(Activity host) {
        this(host, false);
    }

    public LanternPanel(Activity host, boolean embedded) {
        super(host);
        this.host = host;
        this.embedded = embedded;
        this.ble = BleController.get(host);
        // 底色 = 宿主（桌面设置页）背景色，由 PanelTheme 打开面板前采样并套用主题
        setBackgroundColor(Ui.hostColor());

        buildUi();

        ble.addListener(this);
        tabPages = new Page[]{
                new HomePage(this), new LightPage(this), new ScenePage(this), new SettingsPage(this)
        };
        stack.add(tabPages[0]);
        showStackTop();
        registerTiming();
        startBluetooth();
    }

    // ---------------- 视图构建（原来的 activity_main.xml 全搬到这里） ----------------

    private void buildUi() {
        final Context c = getContext();

        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.hostColor());
        addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 顶部栏 ----
        LinearLayout bar = Ui.row(c);
        int padH = Ui.dp(c, 22);
        bar.setPadding(padH, Ui.dp(c, 10), padH, Ui.dp(c, 6));
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        topBack = new ImageView(c);
        Res.setIcon(topBack, Res.ic_back);
        Res.bg(topBack, Res.bg_icon_circle_dark);
        int backSize = Ui.dp(c, 34);
        int backPad = Ui.dp(c, 5);
        topBack.setPadding(backPad, backPad, backPad, backPad);
        topBack.setVisibility(View.GONE);
        topBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goBack();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(backSize, backSize);
        blp.setMarginEnd(Ui.dp(c, 8));
        bar.addView(topBack, blp);

        LinearLayout titles = Ui.column(c);
        titles.setLayoutParams(Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        topTitle = Ui.text(c, "智能控制", 22, 0xFFFFFFFF, true);
        titles.addView(topTitle);

        LinearLayout btRow = Ui.row(c);
        LinearLayout.LayoutParams brlp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        brlp.topMargin = Ui.dp(c, 3);
        topBtIcon = new ImageView(c);
        Res.setIcon(topBtIcon, Res.ic_bt);
        topBtIcon.setColorFilter(Ui.TEXT_THIRD);
        int bt = Ui.dp(c, 13);
        btRow.addView(topBtIcon, new LinearLayout.LayoutParams(bt, bt));
        topSubtitle = Ui.text(c, "未连接", 12, Ui.TEXT_SECONDARY, false);
        LinearLayout.LayoutParams slp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMarginStart(Ui.dp(c, 5));
        btRow.addView(topSubtitle, slp);
        titles.addView(btRow, brlp);
        bar.addView(titles);

        int bsize = Ui.dp(c, 42);
        int bpad = Ui.dp(c, 10);

        ImageView btnDevices = new ImageView(c);
        Res.setIcon(btnDevices, Res.ic_speaker);
        Res.bg(btnDevices, Res.bg_icon_circle_dark);
        btnDevices.setPadding(bpad, bpad, bpad, bpad);
        btnDevices.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSub(new DevicePage(LanternPanel.this));
            }
        });
        bar.addView(btnDevices, new LinearLayout.LayoutParams(bsize, bsize));

        btnPower = new ImageView(c);
        Res.setIcon(btnPower, Res.ic_power);
        Res.bg(btnPower, Res.grad_blue);
        btnPower.setPadding(bpad, bpad, bpad, bpad);
        btnPower.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePower();
            }
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(bsize, bsize);
        plp.setMarginStart(Ui.dp(c, 10));
        bar.addView(btnPower, plp);

        // ---- 内容区 ----
        content = new FrameLayout(c);
        content.setBackgroundColor(Ui.hostColor());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        clp.topMargin = Ui.dp(c, 4);
        clp.bottomMargin = Ui.dp(c, 10);
        root.addView(content, clp);
    }

    // ---------------- 导航 ----------------

    /** 打开指定功能页（带返回按钮的子页方式） */
    public void switchTab(int index) {
        if (index <= 0 || index >= tabPages.length) {
            goHome();
            return;
        }
        openSub(tabPages[index]);
    }

    public void goHome() {
        while (stack.size() > 1) {
            Page p = stack.remove(stack.size() - 1);
            p.onHide();
        }
        showStackTop();
    }

    public void openSub(Page page) {
        stack.add(page);
        showStackTop();
    }

    public void goBack() {
        if (stack.size() <= 1) {
            onExitRequested();
            return;
        }
        Page p = stack.remove(stack.size() - 1);
        p.onHide();
        showStackTop();
    }

    /** 返回键：消费掉了返回 true（独立壳与嵌入宿主都可以调）。 */
    public boolean handleBack() {
        if (stack.size() > 1) {
            goBack();
            return true;
        }
        if (!embedded) {
            onExitRequested();
        }
        return false;
    }

    /** 底栈时再返回：独立运行 = 退出界面；嵌入模式什么都不做（由设置页自己处理）。 */
    private void onExitRequested() {
        if (embedded) return;
        try {
            host.finish();
        } catch (Throwable t) {
            // 忽略
        }
    }

    private void showStackTop() {
        Page page = stack.get(stack.size() - 1);
        if (lastShown != null && lastShown != page) {
            lastShown.onHide();
            View old = lastShown.getView();
            if (old != null) old.setVisibility(View.GONE);
        }
        lastShown = page;

        // 清掉上一页，避免合成层残留（容器有不透明背景，重绘时先铺底）
        content.removeAllViews();
        content.setBackgroundColor(Ui.hostColor());
        content.invalidate();
        final View next = page.getView();
        next.setVisibility(View.VISIBLE);
        next.invalidate();
        content.addView(next, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.post(new Runnable() {
            @Override
            public void run() {
                content.invalidate();
                next.invalidate();
            }
        });

        topBack.setVisibility(page.showBack() ? View.VISIBLE : View.GONE);
        topTitle.setText(page.getTitle());
        updateTopState();

        page.onShow();
    }

    private void updateTopState() {
        if (stack.isEmpty()) return;
        Page page = stack.get(stack.size() - 1);
        String sub = page.getSubtitle();
        topSubtitle.setText(sub != null && sub.length() > 0 ? sub : getConnectionSummary());
        boolean connected = ble.getConnectedCount() > 0;
        topBtIcon.setColorFilter(connected ? 0xFF4A6CF7 : Ui.TEXT_THIRD);
        topSubtitle.setTextColor(connected ? 0xFF8FB0FF : Ui.TEXT_SECONDARY);

        // 右上角电源按钮：开灯时高亮，关灯时熄灭
        boolean powerOn = Prefs.get(getContext()).isPowerOn();
        Res.bg(btnPower, powerOn ? Res.grad_blue : Res.bg_icon_circle_dark);
        btnPower.setColorFilter(powerOn ? 0xFFFFFFFF : Ui.TEXT_THIRD);
    }

    public void refreshPages() {
        for (Page p : stack) {
            if (p != null) p.refresh();
        }
        updateTopState();
        // 主动重绘，保证开关等控件状态立即生效（部分车机/模拟器不做脏区重绘）
        if (lastShown != null) {
            View v = lastShown.getView();
            if (v != null) {
                v.invalidate();
                if (v instanceof ViewGroup) ((ViewGroup) v).invalidate();
            }
        }
        content.invalidate();
    }

    public String getConnectionSummary() {
        List<String> names = new ArrayList<String>();
        for (BleController.Conn c : ble.getConnections()) {
            if (c.state == BleController.STATE_CONNECTED) names.add(c.name);
        }
        if (names.isEmpty()) return "未连接";
        if (names.size() == 1) return names.get(0);
        return "已连接 " + names.size() + " 台设备";
    }

    private void togglePower() {
        boolean next = !Prefs.get(getContext()).isPowerOn();
        Prefs.get(getContext()).setPowerOn(next);
        LedOutput.power(getContext(), next);
        refreshPages();
    }

    // ---------------- 蓝牙 ----------------

    private void startBluetooth() {
        if (!ble.isBleSupported()) {
            toast("当前设备不支持 BLE 蓝牙");
            return;
        }
        if (!hasPermissions()) {
            requestPermissions();
        } else {
            prepareBluetooth();
        }
    }

    public void toggleScan() {
        if (!hasPermissions()) {
            requestPermissions();
            return;
        }
        if (!ble.isBluetoothEnabled()) {
            prepareBluetooth();
            return;
        }
        if (ble.isScanning()) {
            ble.stopScan();
        } else {
            autoConnectPending = false;
            ble.clearFound();
            ble.startScan();
        }
        refreshPages();
    }

    public void connect(BleController.Conn conn) {
        if (!hasPermissions()) {
            requestPermissions();
            return;
        }
        autoConnectPending = false;
        ble.connect(conn);
        Prefs.get(getContext()).setLastDevice(conn.address);
    }

    public void disconnect(BleController.Conn conn) {
        ble.disconnect(conn);
        refreshPages();
    }

    private void prepareBluetooth() {
        if (!ble.isBluetoothEnabled()) {
            try {
                host.startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                        REQ_ENABLE_BT);
            } catch (Exception e) {
                toast("请开启蓝牙");
            }
            return;
        }
        autoConnectPending = Prefs.get(getContext()).isAutoConnect()
                && Prefs.get(getContext()).getLastDevice() != null;
        ble.startScan();
    }

    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean hasPermissions() {
        for (String p : requiredPermissions()) {
            if (host.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private void requestPermissions() {
        permissionPending = true;
        try {
            host.requestPermissions(requiredPermissions(), REQ_PERMISSION);
        } catch (Throwable t) {
            permissionPending = false;
            return;
        }
        // 嵌入到别人的界面里时收不到 onRequestPermissionsResult，
        // 所以再兜一层轮询：授权了就继续。
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (permissionPending && hasPermissions()) {
                    permissionPending = false;
                    prepareBluetooth();
                }
            }
        }, 1500L);
    }

    /** 独立壳的 MainActivity 转发（嵌入场景依靠上面的轮询）。 */
    public void onPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQ_PERMISSION) return;
        permissionPending = false;
        boolean ok = true;
        if (grantResults != null) {
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) ok = false;
            }
        }
        if (ok) {
            prepareBluetooth();
        } else {
            toast("需要蓝牙/定位权限才能控制灯具");
        }
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_ENABLE_BT && ble.isBluetoothEnabled()) {
            prepareBluetooth();
        }
    }

    // ---------------- BLE 回调 ----------------

    @Override
    public void onDevicesChanged() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (autoConnectPending) {
                    String last = Prefs.get(getContext()).getLastDevice();
                    if (last != null) {
                        BleController.Conn conn = ble.findConn(last);
                        if (conn != null && conn.state == BleController.STATE_IDLE) {
                            autoConnectPending = false;
                            ble.connect(conn);
                        }
                    }
                }
                refreshPages();
            }
        });
    }

    @Override
    public void onStateChanged(BleController.Conn conn) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                refreshPages();
                updateTopState();
            }
        });
        if (conn != null && conn.state == BleController.STATE_CONNECTED) {
            // 连上就把设备侧设置重放一遍（线序 → 点数 → 开关 → 亮度 → 颜色）。
            // ⚠️ 2026-09-25 修：以前只补了"开关/亮度/颜色"，**漏了线序** ——
            // 灯固件掉电会回到默认线序，于是"调成红色显示成绿色、改设置也没用"。
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    LedOutput.reapplyDeviceConfig(getContext());
                }
            }, 200L);
        }
    }

    @Override
    public void onMessage(final String msg) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                updateTopState();
            }
        });
    }

    // ---------------- RGB 手调弹窗 ----------------

    public void showRgbDialog(int color, final OnColorPicked callback) {
        final Context c = getContext();
        final int[] rgb = new int[]{(color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF};
        LinearLayout layout = Ui.column(c);
        layout.setPadding(Ui.dp(c, 20), Ui.dp(c, 16), Ui.dp(c, 20), Ui.dp(c, 6));

        final EditText[] edits = new EditText[3];
        String[] labels = {"R", "G", "B"};
        int[] labelColor = {0xFFE5484D, 0xFF22C55E, 0xFF4A6CF7};
        for (int i = 0; i < 3; i++) {
            LinearLayout row = Ui.row(c);
            LinearLayout.LayoutParams rlp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) rlp.topMargin = Ui.dp(c, 12);
            row.setLayoutParams(rlp);
            TextView tv = Ui.text(c, labels[i], 16, labelColor[i], true);
            tv.setLayoutParams(Ui.lp(Ui.dp(c, 30), ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(tv);
            EditText et = new EditText(c);
            et.setInputType(InputType.TYPE_CLASS_NUMBER);
            et.setText(String.valueOf(rgb[i]));
            et.setTextColor(Color.WHITE);
            et.setGravity(Gravity.CENTER);
            Res.bg(et, Res.bg_card_inner);
            et.setLayoutParams(Ui.lp(0, Ui.dp(c, 46), 1f));
            row.addView(et);
            edits[i] = et;
            layout.addView(row);
        }

        final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(host)
                .setTitle("RGB 手调")
                .setView(layout)
                .setPositiveButton("确定", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                int[] val = new int[3];
                                for (int i = 0; i < 3; i++) {
                                    try {
                                        val[i] = Integer.parseInt(edits[i].getText().toString());
                                    } catch (Exception e) {
                                        val[i] = 0;
                                    }
                                    val[i] = Math.max(0, Math.min(255, val[i]));
                                }
                                if (callback != null) {
                                    callback.onPicked(Color.rgb(val[0], val[1], val[2]));
                                }
                                dialog.dismiss();
                            }
                        });
            }
        });
        try {
            dialog.show();
        } catch (Throwable t) {
            toast("当前环境无法弹出输入框");
        }
    }

    // ---------------- 生命周期 ----------------

    private void registerTiming() {
        if (timingRegistered) return;
        try {
            IntentFilter f = new IntentFilter(TimingReceiver.ACTION_TIMING);
            if (Build.VERSION.SDK_INT >= 33) {
                host.registerReceiver(timingReceiver, f, Context.RECEIVER_EXPORTED);
            } else {
                host.registerReceiver(timingReceiver, f);
            }
            timingRegistered = true;
        } catch (Throwable t) {
            timingRegistered = false;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // 界面被移除（用户切到别的设置项）：只解绑监听与广播。
        // 蓝牙连接、渐变播放都不动 —— 保活由 LanternBootstrap 负责。
        try {
            if (timingRegistered) host.unregisterReceiver(timingReceiver);
        } catch (Throwable t) {
            // 忽略
        }
        timingRegistered = false;
        ble.removeListener(this);
        for (Page p : stack) {
            if (p != null) p.onHide();
        }
        Ui.clearScaleOverride();
    }

    private void toast(String msg) {
        try {
            Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            // 忽略
        }
    }
}
