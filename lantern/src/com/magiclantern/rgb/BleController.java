package com.magiclantern.rgb;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BLE 设备管理：扫描、连接（支持多灯同时连接）、指令下发。
 * 下发做了节流与去重，保证律动类高频指令不会把 BLE 通道堵死。
 */
public class BleController {

    public static final int STATE_IDLE = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_CONNECTED = 2;

    public interface Listener {
        void onDevicesChanged();

        void onStateChanged(Conn conn);

        void onMessage(String msg);
    }

    public static class Conn {
        public final String address;
        public final String name;
        public BluetoothDevice device;
        public BluetoothGatt gatt;
        public BluetoothGattCharacteristic writeChar;
        public int state = STATE_IDLE;

        Conn(BluetoothDevice d) {
            device = d;
            address = d.getAddress();
            name = d.getName() == null ? d.getAddress() : d.getName();
        }
    }

    private static BleController instance;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;

    private final LinkedHashMap<String, Conn> found = new LinkedHashMap<String, Conn>();
    private final LinkedHashMap<String, Conn> connected = new LinkedHashMap<String, Conn>();
    /** 可以同时挂多个监听者：界面面板 + 保活（LanternBootstrap） */
    private final java.util.concurrent.CopyOnWriteArrayList<Listener> listeners =
            new java.util.concurrent.CopyOnWriteArrayList<Listener>();

    private boolean scanning = false;

    private final LinkedList<byte[]> queue = new LinkedList<byte[]>();
    private boolean writing = false;
    private long lastWriteTime = 0L;
    private static final long MIN_INTERVAL = 25L;
    private static final long WRITE_TIMEOUT = 400L;

    public static BleController get(Context c) {
        if (instance == null) instance = new BleController(c.getApplicationContext());
        return instance;
    }

    private BleController(Context c) {
        context = c;
        BluetoothManager bm = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) adapter = bm.getAdapter();
        if (adapter == null) adapter = BluetoothAdapter.getDefaultAdapter();
    }

    /** 替换全部监听者（旧用法）。 */
    public void setListener(Listener l) {
        listeners.clear();
        if (l != null) listeners.add(l);
    }

    /** 追加一个监听者（界面与保活互不覆盖）。 */
    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        if (l != null) listeners.remove(l);
    }

    public boolean isBleSupported() {
        return adapter != null && context.getPackageManager()
                .hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE);
    }

    public boolean isBluetoothEnabled() {
        return adapter != null && adapter.isEnabled();
    }

    public List<Conn> getFoundDevices() {
        return new ArrayList<Conn>(found.values());
    }

    public List<Conn> getConnections() {
        return new ArrayList<Conn>(connected.values());
    }

    public int getConnectedCount() {
        int n = 0;
        for (Conn c : connected.values()) {
            if (c.state == STATE_CONNECTED) n++;
        }
        return n;
    }

    public boolean isConnected() {
        return getConnectedCount() > 0;
    }

    public void clearFound() {
        found.clear();
        notifyDevices();
    }

    // ---------------- 扫描 ----------------

    @SuppressLint("MissingPermission")
    public void startScan() {
        if (adapter == null || !adapter.isEnabled()) {
            notifyMessage("蓝牙未开启");
            return;
        }
        if (scanner == null) scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            notifyMessage("无法启动 BLE 扫描");
            return;
        }
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        scanning = true;
        scanner.startScan(null, settings, scanCallback);
        handler.postDelayed(stopScanTask, 15000L);
        notifyMessage("正在扫描设备…");
    }

    private final Runnable stopScanTask = new Runnable() {
        @Override
        public void run() {
            stopScan();
        }
    };

    @SuppressLint("MissingPermission")
    public void stopScan() {
        handler.removeCallbacks(stopScanTask);
        if (scanner != null && adapter != null && adapter.isEnabled()) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception e) {
                // ignore
            }
        }
        scanning = false;
    }

    public boolean isScanning() {
        return scanning;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            if (d == null) return;
            if (!accept(d.getName())) return;
            String addr = d.getAddress();
            if (!found.containsKey(addr)) {
                found.put(addr, new Conn(d));
                notifyDevices();
            } else {
                found.get(addr).device = d;
            }
        }

        private boolean accept(String name) {
            if (!Prefs.get(context).isNameFilterEnabled()) return true;
            if (name == null) return false;
            String n = name.toUpperCase();
            return n.startsWith(LedCommand.NAME_FILTER);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            if (results == null) return;
            for (ScanResult r : results) {
                if (r == null || r.getDevice() == null) continue;
                String addr = r.getDevice().getAddress();
                if (!found.containsKey(addr)) found.put(addr, new Conn(r.getDevice()));
            }
            notifyDevices();
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            notifyMessage("扫描失败 code=" + errorCode);
        }
    };

    // ---------------- 连接 ----------------

    @SuppressLint("MissingPermission")
    public void connect(Conn conn) {
        if (conn == null || conn.device == null) return;
        if (conn.state == STATE_CONNECTED || conn.state == STATE_CONNECTING) return;
        conn.state = STATE_CONNECTING;
        connected.put(conn.address, conn);
        notifyState(conn);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            conn.gatt = conn.device.connectGatt(context, false, gattCallback,
                    BluetoothDevice.TRANSPORT_LE);
        } else {
            conn.gatt = conn.device.connectGatt(context, false, gattCallback);
        }
    }

    @SuppressLint("MissingPermission")
    public void disconnect(Conn conn) {
        if (conn == null) return;
        if (conn.gatt != null) {
            try {
                conn.gatt.disconnect();
                conn.gatt.close();
            } catch (Exception e) {
                // ignore
            }
        }
        conn.gatt = null;
        conn.writeChar = null;
        conn.state = STATE_IDLE;
        connected.remove(conn.address);
        notifyState(conn);
        notifyDevices();
    }

    public void disconnectAll() {
        List<Conn> list = getConnections();
        for (Conn c : list) disconnect(c);
    }

    public Conn findConn(String address) {
        if (address == null) return null;
        Conn c = connected.get(address);
        if (c != null) return c;
        return found.get(address);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            final Conn conn = findConn(gatt.getDevice().getAddress());
            if (conn == null) return;
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED
                    && status == BluetoothGatt.GATT_SUCCESS) {
                conn.state = STATE_CONNECTING;
                notifyState(conn);
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (conn.gatt != null) conn.gatt.discoverServices();
                    }
                }, 300L);
            } else {
                conn.state = STATE_IDLE;
                conn.gatt = null;
                conn.writeChar = null;
                connected.remove(conn.address);
                notifyState(conn);
                notifyDevices();
                notifyMessage("连接断开：" + conn.name);
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Conn conn = findConn(gatt.getDevice().getAddress());
            if (conn == null) return;
            if (status != BluetoothGatt.GATT_SUCCESS) {
                conn.state = STATE_IDLE;
                notifyState(conn);
                return;
            }
            conn.writeChar = pickWriteCharacteristic(gatt);
            if (conn.writeChar == null) {
                conn.state = STATE_IDLE;
                notifyState(conn);
                notifyMessage("未找到可用的写入特征：" + conn.name);
                return;
            }
            conn.state = STATE_CONNECTED;
            notifyState(conn);
            notifyDevices();
            notifyMessage("已连接：" + conn.name);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic
                characteristic, int status) {
            writing = false;
            handler.removeCallbacks(writeTimeoutTask);
            pump();
        }
    };

    private BluetoothGattCharacteristic pickWriteCharacteristic(BluetoothGatt gatt) {
        List<BluetoothGattService> services = gatt.getServices();
        // 1) 精确匹配 (服务, 特征) 组合：原版为 fff0 / fff3
        for (UUID[] pair : LedCommand.PREFERRED) {
            for (BluetoothGattService s : services) {
                if (!pair[0].equals(s.getUuid())) continue;
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    if (pair[1].equals(c.getUuid()) && isWritable(c)) return c;
                }
            }
        }
        // 2) 退化为任意可写特征
        for (BluetoothGattService s : services) {
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                if (isWritable(c)) return c;
            }
        }
        return null;
    }

    private static boolean isWritable(BluetoothGattCharacteristic c) {
        int p = c.getProperties();
        return (p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                || (p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0;
    }

    // ---------------- 指令下发 ----------------

    public void send(byte[] data) {
        if (data == null) return;
        handler.post(new Runnable() {
            @Override
            public void run() {
                enqueue(data);
            }
        });
    }

    private void enqueue(byte[] data) {
        // 高频指令只保留最新的几条，避免堆积
        while (queue.size() > 4) queue.removeFirst();
        queue.addLast(data);
        pump();
    }

    private void pump() {
        if (writing) return;
        if (queue.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastWriteTime < MIN_INTERVAL) {
            handler.postDelayed(pumpTask, MIN_INTERVAL);
            return;
        }
        byte[] data = queue.removeFirst();
        boolean sent = false;
        for (Map.Entry<String, Conn> e : connected.entrySet()) {
            Conn c = e.getValue();
            if (c.state != STATE_CONNECTED || c.gatt == null || c.writeChar == null) continue;
            try {
                BluetoothGattCharacteristic ch = c.writeChar;
                ch.setValue(data);
                ch.setWriteType((ch.getProperties()
                        & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                        ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                if (c.gatt.writeCharacteristic(ch)) sent = true;
            } catch (Exception ex) {
                // ignore
            }
        }
        lastWriteTime = System.currentTimeMillis();
        if (sent) {
            writing = true;
            handler.postDelayed(writeTimeoutTask, WRITE_TIMEOUT);
        } else {
            pump();
        }
    }

    private final Runnable pumpTask = new Runnable() {
        @Override
        public void run() {
            pump();
        }
    };

    private final Runnable writeTimeoutTask = new Runnable() {
        @Override
        public void run() {
            writing = false;
            pump();
        }
    };

    // ---------------- 通知 ----------------

    private void notifyDevices() {
        for (Listener l : listeners) {
            try {
                l.onDevicesChanged();
            } catch (Throwable t) {
                // 单个监听者出错不能影响蓝牙主流程
            }
        }
    }

    private void notifyState(Conn c) {
        for (Listener l : listeners) {
            try {
                l.onStateChanged(c);
            } catch (Throwable t) {
                // 单个监听者出错不能影响蓝牙主流程
            }
        }
    }

    private void notifyMessage(String msg) {
        for (Listener l : listeners) {
            try {
                l.onMessage(msg);
            } catch (Throwable t) {
                // 单个监听者出错不能影响蓝牙主流程
            }
        }
    }
}
