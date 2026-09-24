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
            // ↓ 后装高德车机版。包名是 com.wt.mahjong（不是 amapauto！），
            //   光看名字绝对猜不到，是从用户提供的安装包清单里读出来的
            "com.wt.mahjong",
            "com.autonavi.amapauto",
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

    /** 剩余时间：「48 分钟」「1 小时 20 分钟」 */
    private static final Pattern ETA_MIN = Pattern.compile("(\\d+)\\s*(?:分钟|分)");
    private static final Pattern ETA_HOUR = Pattern.compile("(\\d+)\\s*(?:小时|时|h)");

    /** 剩余总里程：优先「还有 / 剩余 / 全程」后面跟的距离 */
    private static final Pattern REMAIN = Pattern.compile(
            "(?:还有|剩余|距离目的地|全程|距终点)\\s*(\\d+(?:\\.\\d+)?\\s*(?:公里|千米|米|km|m))");

    /** 从提示文字里推断转向类型，iPhone 拿它画箭头图标 */
    public static String turnOfText(String s) {
        return turnOf(s);
    }

    private static String turnOf(String s) {
        if (s == null) return null;
        if (s.contains("掉头") || s.contains("调头")) return "uturn";
        if (s.contains("环岛")) return "round";
        if (s.contains("左前方") || s.contains("左前")) return "slightLeft";
        if (s.contains("右前方") || s.contains("右前")) return "slightRight";
        if (s.contains("左转") || s.contains("靠左") || s.contains("向左")) return "left";
        if (s.contains("右转") || s.contains("靠右") || s.contains("向右")) return "right";
        if (s.contains("直行") || s.contains("进入主路") || s.startsWith("沿")) return "straight";
        if (s.contains("匝道") || s.contains("汇入")) return "merge";
        if (s.contains("到达")) return "arrive";
        return null;
    }

    /** 「48 分钟」「1 小时」这类剩余时间 */
    private static String etaOf(String... texts) {
        for (String t : texts) {
            if (t == null) continue;
            Matcher h = ETA_HOUR.matcher(t);
            Matcher m = ETA_MIN.matcher(t);
            boolean hasH = h.find();
            boolean hasM = m.find();
            if (hasH && hasM) return h.group(1) + " 小时 " + m.group(1) + " 分钟";
            if (hasH) return h.group(1) + " 小时";
            if (hasM) return m.group(1) + " 分钟";
        }
        return null;
    }

    /** 剩余总里程 */
    private static String remainOf(String... texts) {
        for (String t : texts) {
            if (t == null) continue;
            Matcher m = REMAIN.matcher(t);
            if (m.find()) return m.group(1).replaceAll("\\s+", " ");
        }
        return null;
    }

    /** 一行文字里出现的所有距离，按出现顺序 */
    private static java.util.List<String> distancesOf(String... texts) {
        java.util.List<String> out = new java.util.ArrayList<String>(4);
        for (String t : texts) {
            if (t == null) continue;
            Matcher m = DISTANCE.matcher(t);
            while (m.find()) {
                String d = m.group(0).replaceAll("\\s+", " ");
                if (!out.contains(d)) out.add(d);
            }
        }
        return out;
    }

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

    // 来源优先级/让位窗口统一在 StateHub.navClaim 里（2026-09-24 起），
    // 这里不再各管一段 —— 以前只有"通知栏压读屏"这一条规则，
    // 原厂导航 + 第三方高德同时开时压不住。

    private NaviSignals() { }

    // ─────────────────────────────────────── 运行时可追加的导航包名

    private static final String PREF = "cardash";
    private static final String KEY_NAV = "extraNavPkgs";
    private static volatile android.content.SharedPreferences prefs;
    private static final java.util.Set<String> EXTRA_PKGS =
            new java.util.concurrent.CopyOnWriteArraySet<String>();

    public static void initPrefs(android.content.Context ctx) {
        try {
            prefs = ctx.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE);
            String saved = prefs.getString(KEY_NAV, "");
            if (saved != null && !saved.isEmpty()) {
                for (String p : saved.split(",")) {
                    if (!p.trim().isEmpty()) EXTRA_PKGS.add(p.trim());
                }
            }
        } catch (Throwable ignored) {
            // 读不到就没有附加包
        }
    }

    /** 车机上不方便改代码但知道包名时，用 /setnav?pkg=com.xxx 直接加，立刻生效并持久化。 */
    public static String addPackage(String query) {
        StringBuilder sb = new StringBuilder();
        sb.append("导航包名白名单\n");
        sb.append("  内置/已加: ").append(EXTRA_PKGS.isEmpty() ? "（无附加）" : EXTRA_PKGS.toString()).append('\n');

        String pkg = null;
        if (query != null) {
            for (String kv : query.split("&")) {
                int eq = kv.indexOf('=');
                if (eq <= 0) continue;
                if ("pkg".equals(kv.substring(0, eq).trim())) {
                    pkg = kv.substring(eq + 1).trim();
                }
            }
        }
        if (pkg == null || pkg.isEmpty()) {
            sb.append("\n  改法: ").append(BridgeRuntime.primaryUrl()).append("/setnav?pkg=com.xxx\n");
            return sb.toString();
        }

        EXTRA_PKGS.add(pkg);
        try {
            android.content.SharedPreferences p = prefs;
            if (p != null) {
                StringBuilder all = new StringBuilder();
                for (String s : EXTRA_PKGS) {
                    if (all.length() > 0) all.append(',');
                    all.append(s);
                }
                p.edit().putString(KEY_NAV, all.toString()).commit();
            }
        } catch (Throwable ignored) {
            // 持久化失败不影响本次生效
        }
        sb.append("\n  已加入: ").append(pkg).append('\n');
        sb.append("  现在去车机导航界面上划一下，再回 /logcat 看「导航」段落。\n");
        return sb.toString();
    }

    public static boolean isNavPackage(String pkg) {
        if (pkg == null) return false;
        if (EXTRA_PKGS.contains(pkg)) return true;
        for (String p : NAV_PACKAGES) {
            if (p.equals(pkg)) return true;
        }
        // 注意别用 contains("map") 这种宽条件 —— 会把一堆不相干的包也当成导航
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

        // 来源仲裁：高德广播正在推（每秒一条）时，通知栏这条会被压住 ——
        // 用户习惯原厂导航和第三方高德同时开，不仲裁的话顶栏数字会在两个 App
        // 之间来回跳（2026-09-24）。高德没在导航时这条 5 秒后自然接手。
        if (!hub.navClaim("notify:" + pkg)) return;

        hub.navTitle = head != null ? head : sub;
        hub.navSub = (sub != null && !sub.equals(hub.navTitle)) ? sub : null;

        // 顶栏中间那两格 + 右侧卡片的第二个距离
        java.util.List<String> ds = distancesOf(title, bigText, text, subText, infoText);
        hub.navDistance = ds.isEmpty() ? null : ds.get(0);
        hub.navAfter = ds.size() > 1 ? ds.get(1) : null;
        hub.navEta = etaOf(title, bigText, text, subText, infoText);
        hub.navRemain = remainOf(title, bigText, text, subText, infoText);
        hub.navTurn = turnOf(firstNonEmpty(head, sub, subText, title, text));

        hub.navActive = true;
        hub.navUpdatedAt = System.currentTimeMillis();
        // navSource 已经由上面的 navClaim 记成 "notify:<包名>"，这里不用再赋一次
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

        // 来源仲裁（读屏是精度最低的一路，优先级也最低）：
        // 高德广播在推就让它、通知栏刚给过也让它 —— 别把顶栏数字搅乱。
        // 统一走 StateHub.navClaim，不再各自定规则（以前这里只压"通知栏"一路）。
        if (!hub.navClaim("a11y:" + pkg)) return;

        hub.navTitle = title;
        hub.navSub = sub;
        hub.navDistance = distance;
        hub.navTurn = turnOf(title);

        // 读屏能拿到整个界面的文字，顺带把顶栏的剩余时间和剩余里程也抠出来
        StringBuilder all = new StringBuilder(256);
        for (String t : texts) {
            if (t == null) continue;
            if (all.length() > 0) all.append(' ');
            all.append(t);
        }
        hub.navEta = etaOf(all.toString());
        hub.navRemain = remainOf(all.toString());
        java.util.List<String> ds = distancesOf(all.toString());
        if (ds.size() > 1) hub.navAfter = ds.get(1);

        hub.navActive = true;
        hub.navUpdatedAt = System.currentTimeMillis();
        // navSource 已经由上面的 navClaim 记成 "a11y:<包名>"，这里不用再赋一次
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
