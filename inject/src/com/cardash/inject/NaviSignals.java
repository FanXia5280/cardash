package com.cardash.inject;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 导航信息解析。两条来源：
 *   1. 通知栏（notify:）—— 结构化，优先
 *   2. 无障碍读屏（a11y:）—— 兜底，覆盖不写通知的导航应用
 */
public final class NaviSignals {

    private static final String[] NAV_PACKAGES = {
            // ↓ 深蓝这台车实测用的就是腾讯梧桐导航（从 D.apk 里挖出来的包名）
            "com.tinnove.wecarnavi",
            "com.tinnove.wecarspeech",
            "com.tinnove.mediacenter",
            "com.tinnove.renderserver",
            "com.tinnove.navi",
            // ↓ 其它常见车机导航
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

    /** 转向动作关键词，命中即认为这句是导航提示 */
    private static final String[] TURN_WORDS = {
            "左转", "右转", "直行", "掉头", "调头", "左前方", "右前方",
            "靠左", "靠右", "进入环岛", "出环岛", "驶出", "驶入",
            "到达目的地", "到达途经点", "进入主路", "进入辅路",
            "上匝道", "下匝道", "右转进入", "左转进入", "前方", "沿"
    };

    /** 道路名：中文/英文/数字 + 常见道路后缀 */
    private static final Pattern ROAD = Pattern.compile(
            "([\\u4e00-\\u9fa5A-Za-z0-9]{2,18}"
                    + "(?:路|街|大道|高速公路|高速|公路|大桥|隧道|立交桥|立交|环岛|出口|匝道|辅路|主路|路口))");

    /** 通知来源的数据在这个时间窗内优先，避免被读屏的低精度结果覆盖 */
    private static final long NOTIFY_PRIORITY_MS = 5000L;

    private NaviSignals() { }

    public static boolean isNavPackage(String pkg) {
        if (pkg == null) return false;
        for (String p : NAV_PACKAGES) {
            if (p.equals(pkg)) return true;
        }
        String lower = pkg.toLowerCase();
        return lower.contains("navi") || lower.contains("amap") || lower.contains("baidumap")
                || lower.contains("tinnove");
    }

    // ─────────────────────────────────────── 来源一：通知栏

    public static void onPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return;

        Bundle extras = sbn.getNotification().extras;
        if (extras == null) return;

        String pkg = sbn.getPackageName();
        String title = str(extras.getCharSequence(Notification.EXTRA_TITLE));
        String text = str(extras.getCharSequence(Notification.EXTRA_TEXT));
        String subText = str(extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
        String bigText = str(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        String infoText = str(extras.getCharSequence(Notification.EXTRA_INFO_TEXT));

        StateHub.get().navSeen++;

        if (!isNavPackage(pkg)) {
            // 包名不在白名单里。但内容要是像导航就记下来 —— /logcat 里能看到，
            // 好把车机真正的导航包名补进白名单，而不是靠猜。
            if (looksLikeNav(title, text, subText, bigText, infoText)) {
                recordCandidate(pkg, firstNonEmpty(title, bigText, text, infoText));
            }
            return;
        }

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
        hub.navSource = "notify:" + pkg;
    }

    // ─────────────────────────────────────── 无障碍包名统计

    private static final java.util.Map<String, Integer> A11Y_PKGS =
            new java.util.concurrent.ConcurrentHashMap<String, Integer>();

    /** 记录无障碍事件来自哪个包 —— 用来找出车机导航的真实包名，而不是靠猜。 */
    public static void noteA11yPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        Integer c = A11Y_PKGS.get(pkg);
        A11Y_PKGS.put(pkg, c == null ? 1 : c + 1);
    }

    public static String a11yPackageSummary() {
        java.util.List<java.util.Map.Entry<String, Integer>> list =
                new java.util.ArrayList<java.util.Map.Entry<String, Integer>>(A11Y_PKGS.entrySet());
        java.util.Collections.sort(list,
                new java.util.Comparator<java.util.Map.Entry<String, Integer>>() {
                    @Override public int compare(java.util.Map.Entry<String, Integer> a,
                                                 java.util.Map.Entry<String, Integer> b) {
                        return b.getValue() - a.getValue();
                    }
                });
        if (list.isEmpty()) {
            return "  （还没收到无障碍事件；若一直为空说明读屏服务没真正连上）\n";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size() && i < 30; i++) {
            String p = list.get(i).getKey();
            sb.append("  ").append(p).append("  x").append(list.get(i).getValue())
              .append(isNavPackage(p) ? "   ← 已在导航白名单里" : "").append('\n');
        }
        return sb.toString();
    }

    // ─────────────────────────────────────── 导航包名探测

    private static final java.util.Map<String, String> CANDIDATES =
            new java.util.LinkedHashMap<>();
    private static final int CAND_MAX = 15;

    private static boolean looksLikeNav(String... values) {
        for (String v : values) {
            if (v == null) continue;
            for (String w : TURN_WORDS) {
                if (v.contains(w)) return true;
            }
            if (DISTANCE.matcher(v).find() && v.length() <= 40) return true;
        }
        return false;
    }

    private static void recordCandidate(String pkg, String sample) {
        if (sample != null && sample.length() > 40) sample = sample.substring(0, 40);
        synchronized (CANDIDATES) {
            if (!CANDIDATES.containsKey(pkg) && CANDIDATES.size() >= CAND_MAX) return;
            CANDIDATES.put(pkg, sample == null ? "" : sample);
        }
    }

    /** 给 /logcat 用：内容像导航、但包名不在白名单里的通知来源。 */
    public static String candidateSummary() {
        synchronized (CANDIDATES) {
            if (CANDIDATES.isEmpty()) return "  （没发现像导航的通知）\n";
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<String, String> e : CANDIDATES.entrySet()) {
                sb.append("  ").append(e.getKey()).append("  ::  ").append(e.getValue()).append('\n');
            }
            return sb.toString();
        }
    }

    // ─────────────────────────────────────── 来源二：无障碍读屏

    public static void onScreenTexts(String pkg, List<String> texts) {
        if (texts == null || texts.isEmpty()) return;

        StateHub hub = StateHub.get();

        // 通知栏刚给过更准的结果就别覆盖
        if (hub.navSource != null && hub.navSource.startsWith("notify")
                && System.currentTimeMillis() - hub.navUpdatedAt < NOTIFY_PRIORITY_MS) {
            return;
        }

        String turn = null;
        String road = null;
        String distance = null;

        for (String raw : texts) {
            if (raw == null) continue;
            String s = raw.trim();
            if (s.isEmpty() || s.length() > 40) continue;

            if (distance == null) {
                Matcher m = DISTANCE.matcher(s);
                if (m.find()) distance = m.group(0);
            }
            if (turn == null) {
                for (String w : TURN_WORDS) {
                    if (s.contains(w)) {
                        turn = s;
                        break;
                    }
                }
            }
            if (road == null) {
                Matcher m = ROAD.matcher(s);
                if (m.find()) road = m.group(1);
            }
            if (turn != null && road != null && distance != null) break;
        }

        if (turn == null && road == null && distance == null) return;

        String title = turn != null ? turn : (road != null ? road : distance);
        String sub = null;
        if (road != null && !road.equals(title)) {
            sub = road;
        } else if (distance != null && !distance.equals(title)) {
            sub = distance;
        }

        hub.navTitle = title;
        hub.navSub = sub;
        hub.navDistance = distance;
        hub.navActive = true;
        hub.navUpdatedAt = System.currentTimeMillis();
        hub.navSource = "a11y:" + pkg;
    }

    // ─────────────────────────────────────── 工具

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
