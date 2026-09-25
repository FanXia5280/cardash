package com.magiclantern.rgb;

import android.content.Context;
import android.view.View;

/**
 * 内容页基类（视图懒加载）。
 *
 * 同一个页面既能跑在独立 APK（MainActivity 承载），也能嵌进车机桌面的
 * 「桌面设置 → 氛围灯设置」（由 LanternPanel 承载）：
 *   - host     : 宿主面板，负责导航（开子页 / 回主页 / 刷新）与连接状态；
 *   - activity : 宿主 Context，Prefs / LedOutput / Ui 等直接用这个（沿用旧名，
 *                这样各页面里原有的 activity 用法一行都不用改）。
 */
public abstract class Page {

    protected final LanternPanel host;
    protected final Context activity;

    private View root;

    public Page(LanternPanel host) {
        this.host = host;
        this.activity = host.getContext();
    }

    public View getView() {
        if (root == null) {
            root = build(activity);
            // 每个页面都使用不透明底色，避免切换时残留上一页内容
            root.setBackgroundColor(0xFF0A0D14);
        }
        return root;
    }

    protected abstract View build(Context c);

    /** 顶部标题 */
    public String getTitle() {
        return "氛围灯控制";
    }

    /** 顶部副标题（为空时显示蓝牙连接状态） */
    public String getSubtitle() {
        return null;
    }

    /** 是否显示返回按钮 */
    public boolean showBack() {
        return false;
    }

    public void onShow() {
    }

    public void onHide() {
    }

    /** 外部状态（连接/开关）变化时的刷新 */
    public void refresh() {
    }

    protected int dp(float v) {
        return Ui.dp(activity, v);
    }
}
