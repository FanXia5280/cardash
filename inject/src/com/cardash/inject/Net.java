package com.cardash.inject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class Net {

    private Net() { }

    public static final class Iface {
        public final String name;
        public final String ip;

        Iface(String name, String ip) {
            this.name = name;
            this.ip = ip;
        }

        @Override
        public String toString() {
            return name + "=" + ip;
        }
    }

    /**
     * 列出所有 IPv4 地址，并给出「像局域网」的优先级。
     *
     * 车机会同时挂着蜂窝网（rmnet/ccmni）和 WiFi（wlan0），蜂窝网的地址
     * 往往是列表里第一个 —— 之前就直接把那个当成监听地址报出去了，所以
     * 才会「IP 地址不对」。这里改成按接口类型打分排序。
     */
    public static List<Iface> interfaces() {
        List<Iface> out = new ArrayList<Iface>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni == null || !ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName();
                if (name == null) name = "?";
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) continue;
                    String ip = addr.getHostAddress();
                    if (ip == null || ip.startsWith("169.254.")) continue;
                    boolean dup = false;
                    for (Iface f : out) {
                        if (f.ip.equals(ip)) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) out.add(new Iface(name, ip));
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }

        Collections.sort(out, new Comparator<Iface>() {
            @Override
            public int compare(Iface a, Iface b) {
                int sa = score(a), sb = score(b);
                if (sa != sb) return sb - sa;
                return a.name.compareTo(b.name);
            }
        });
        return out;
    }

    private static int score(Iface f) {
        String n = f.name.toLowerCase();
        int s = 50;
        if (n.startsWith("wlan") || n.startsWith("ap") || n.contains("wifi")
                || n.startsWith("softap")) {
            s = 100;
        } else if (n.startsWith("eth") || n.startsWith("usb") || n.startsWith("rndis")) {
            s = 80;
        } else if (n.startsWith("bridge")) {
            s = 70;
        } else if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp")
                || n.startsWith("wwan") || n.startsWith("clat") || n.startsWith("bt-pan")) {
            s = 5;
        }
        // 私网地址再抬一档，172.20.10.x 是 iPhone 热点的固定网段
        String ip = f.ip;
        if (ip.startsWith("192.168.") || ip.startsWith("10.")) {
            s += 20;
        } else if (ip.startsWith("172.20.10.") || ip.startsWith("172.16.")
                || ip.startsWith("172.2") || ip.startsWith("172.3")) {
            s += 25;
        } else if (ip.startsWith("172.")) {
            s += 10;
        }
        return s;
    }

    /** 按优先级排序的地址列表 */
    public static List<String> ipv4Addresses() {
        List<String> out = new ArrayList<String>();
        for (Iface f : interfaces()) {
            out.add(f.ip);
        }
        return out;
    }

    public static String primary() {
        List<Iface> list = interfaces();
        return list.isEmpty() ? "127.0.0.1" : list.get(0).ip;
    }

    /** "wlan0=192.168.1.5, rmnet0=10.1.2.3" 这样的可读串，写日志用 */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        for (Iface f : interfaces()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(f.toString());
        }
        return sb.length() == 0 ? "（无）" : sb.toString();
    }
}
