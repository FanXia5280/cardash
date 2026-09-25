package com.magiclantern.rgb;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * 独立安装时的入口壳。
 *
 * 全部界面与蓝牙逻辑都在 {@link LanternPanel} 里 —— 内置进车机桌面
 * （「桌面设置 → 氛围灯设置」）时用的是同一个类，只是嵌在别人的容器里，
 * 所以这里只做"把它铺满整屏"这一件事。
 */
public class MainActivity extends Activity {

    private LanternPanel panel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        panel = new LanternPanel(this);
        setContentView(panel);
    }

    @Override
    public void onBackPressed() {
        if (panel != null && panel.handleBack()) return;
        super.onBackPressed();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (panel != null) panel.onPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (panel != null) panel.onActivityResult(requestCode, resultCode, data);
    }
}
