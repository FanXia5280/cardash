package com.s05.hudtraffic;

import android.app.Activity;
import android.os.Bundle;

/**
 * 内置版保留的空壳 Activity。
 *
 * <p>源码里有「打开主界面」的入口（悬浮面板的「主界面」按钮、通知点击）都引用
 * {@code MainActivity.class}，为了忠实移植保留这个类。内置版**不把它声明进 manifest**
 * （主界面就是桌面设置里的 {@link HudMainView} 面板），所以点「主界面」会走到调用处的
 * catch（toast 提示），不影响任何功能。</p>
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(new HudMainView(this));
    }
}
