package com.cardash.inject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Net {

    private Net() { }

    /** 列出所有可用的 IPv4 地址（车机热点通常是 192.168.x.1）。 */
    public static List<String> ipv4Addresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni == null || !ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName();
                if (name != null) {
                    String lower = name.toLowerCase();
                    if (lower.startsWith("rmnet") || lower.startsWith("dummy")
                            || lower.startsWith("sit") || lower.startsWith("p2p")) {
                        continue;
                    }
                }
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String host = addr.getHostAddress();
                        if (host != null && !out.contains(host)) {
                            out.add(host);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return out;
    }
}
