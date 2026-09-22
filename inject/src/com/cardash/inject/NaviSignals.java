package com.cardash.inject;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从导航类应用的通知里提取「下一转向 / 道路 / 距离」。 */
public final class NaviSignals {

    private static final String[] NAV_PACKAGES = {
            "com.autonavi.amapauto",
            "com.autonavi.amap",
            "com.autonavi.auto",
            "com.baidu.naviauto",
            "com.baidu.BaiduMap",
            "com.baidu.carlife",
            "com.tencent.map",
            "com.google.android.apps.maps",
            "com.deepal.map",
            "com.changan.navi",
            "com.ecarx.map"
    };

    private static final Pattern DISTANCE = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(公里|千米|米|km|m)");

    private static final Pattern BRACKET = Pattern.compile("^[\\[(（【]([^\\])）】]+)[\\])）】]\\s*");

    private NaviSignals() { }

    public static boolean isNavPackage(String pkg) {
        if (pkg == null) return false;
        for (String p : NAV_PACKAGES) {
            if (p.equals(pkg)) return true;
        }
        String lower = pkg.toLowerCase();
        return lower.contains("navi") || lower.contains("amap") || lower.contains("baidumap");
    }

    public static void onPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return;
        if (!isNavPackage(sbn.getPackageName())) return;

        Bundle extras = sbn.getNotification().extras;
        if (extras == null) return;

        String title = str(extras.getCharSequence(Notification.EXTRA_TITLE));
        String text = str(extras.getCharSequence(Notification.EXTRA_TEXT));
        String subText = str(extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
        String bigText = str(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        String infoText = str(extras.getCharSequence(Notification.EXTRA_INFO_TEXT));

        String head = firstNonEmpty(title, bigText, infoText);
        String sub = firstNonEmpty(text, subText, infoText);

        // 部分车机把转向写在 "[右转]" 里，剥掉括号更清爽
        if (head != null) {
            head = BRACKET.matcher(head).replaceFirst("").trim();
            if (head.isEmpty()) head = null;
        }

        if (head == null && sub == null) return;

        StateHub hub = StateHub.get();
        hub.navTitle = head != null ? head : sub;
        hub.navSub = (sub != null && !sub.equals(hub.navTitle)) ? sub : null;
        hub.navDistance = extractDistance(title, text, subText, bigText);
        hub.navActive = true;
        hub.navUpdatedAt = System.currentTimeMillis();
        hub.setSource("nav", sbn.getPackageName());
    }

    private static String extractDistance(String... candidates) {
        for (String c : candidates) {
            if (c == null) continue;
            Matcher m = DISTANCE.matcher(c);
            if (m.find()) return m.group(0);
        }
        return null;
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    private static String str(CharSequence cs) {
        if (cs == null) return null;
        String s = cs.toString().trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;
        return s;
    }
}
