package com.cardash.inject;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 无障碍服务：抓导航界面上的文字。
 *
 * 定位是「通知解析」的兜底 —— 有些车机导航不往通知栏写转向信息，只画在界面上。
 *
 * 注意：这个服务必须跑在主进程里（不能像 D.apk 那样用 android:process=":accessibility"），
 * 否则读不到主进程里的 StateHub 静态状态。
 */
public class NaviAccessibilityService extends AccessibilityService {

    private static final int MAX_TEXT = 240;
    private static final int MAX_DEPTH = 26;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        StateHub.get().setSource("a11y", "connected");

        // 在运行时把服务配置放宽，不完全依赖清单里的资源
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
                info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
                info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                        | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
                info.notificationTimeout = 150;
                if (info.packageNames != null && info.packageNames.length > 0) {
                    info.packageNames = null;   // 不限制来源应用
                }
                setServiceInfo(info);
            }
        } catch (Throwable ignored) {
            // 改不动就用清单里的配置，够用
        }
    }

    /** 白名单外包的最短间隔，避免读屏开销过大 */
    private static final long FOREIGN_INTERVAL_MS = 1500L;
    private long lastForeignScan;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            if (event == null) return;
            CharSequence pkg = event.getPackageName();
            if (pkg == null) return;
            String p = pkg.toString();

            // 先记包名 —— 车机导航的真实包名往往和我们猜的不一样，
            // 统计出来才能补进白名单
            NaviSignals.noteA11yPackage(p);

            boolean known = NaviSignals.isNavPackage(p);
            long now = System.currentTimeMillis();
            if (!known) {
                // 白名单外的应用也偶尔看一眼：车机导航可能根本不是安卓应用，
                // 也可能是厂商自研的包名。限流一下，别把读屏拖垮。
                if (now - lastForeignScan < FOREIGN_INTERVAL_MS) return;
                lastForeignScan = now;
            }

            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;

            List<String> texts = new ArrayList<String>(64);
            collect(root, texts, 0);
            if (!texts.isEmpty()) {
                NaviSignals.onScreenTexts(p, texts);
            }
        } catch (Throwable ignored) {
            // 无障碍事件频率很高，任何异常都不能往外抛
        }
    }

    @Override
    public void onInterrupt() {
        // 必须实现，无实际逻辑
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        StateHub.get().setSource("a11y", "unbound");
        return super.onUnbind(intent);
    }

    private static void collect(AccessibilityNodeInfo node, List<String> out, int depth) {
        if (node == null || depth > MAX_DEPTH || out.size() >= MAX_TEXT) return;

        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            out.add(text.toString());
        }
        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.length() > 0
                && (text == null || !desc.toString().contentEquals(text))) {
            out.add(desc.toString());
        }

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            if (out.size() >= MAX_TEXT) return;
            collect(node.getChild(i), out, depth + 1);
        }
    }
}
