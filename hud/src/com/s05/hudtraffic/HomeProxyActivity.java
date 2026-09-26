package com.s05.hudtraffic;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 「代理桌面」——把本应用伪装成车机桌面来保活（免 ROOT）。
 *
 * <p><b>原理</b>：Android 里桌面（HOME）进程是系统一直保留的，
 * 任何"清理后台"都不会去杀桌面；D 应用就是靠这个身份活着的。
 * 本 Activity 声明了 {@code category.HOME}，只要在车机设置里把默认桌面
 * 换成「HUD红绿灯」，我们的进程就变成了桌面进程。</p>
 *
 * <p><b>但用户还要看到原来的车机桌面</b>，所以这里在启动的瞬间把真正的桌面
 * （系统桌面 / D 桌面，可在首次启动时选择）拉起来，然后自己立刻 finish：
 * 用户看到的还是原来的桌面，而我们的进程已经拿到了桌面身份。</p>
 *
 * <p>设置默认桌面（任选其一）：</p>
 * <pre>
 * adb shell cmd package set-home-activity com.s05.hudtraffic/.HomeProxyActivity
 * # 车机上：设置 → 默认应用 / 桌面 → 选「HUD红绿灯」
 * </pre>
 */
public class HomeProxyActivity extends Activity {

    private static final String TAG = "HomeProxy";
    /** 防止 HOME 键连按导致反复转发 */
    private static final long MIN_LAUNCH_INTERVAL_MS = 600L;
    private static volatile long lastForwardAt = 0L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLog.i(TAG, "被系统作为桌面启动 —— 本进程已获得桌面身份（不会被清理后台）");

        // 成为桌面的这一刻，把常驻服务全部拉起来
        HudTrafficService.ensureAlive(this);

        // 防止 HOME 连按
        long now = SystemClock.elapsedRealtime();
        if (now - lastForwardAt < MIN_LAUNCH_INTERVAL_MS) {
            finishNoAnim();
            return;
        }

        String target = Prefs.getHomeForwardPackage(this);
        if (target == null || target.length() == 0) {
            List<String> candidates = otherHomePackages();
            if (candidates.size() == 1) {
                target = candidates.get(0);
                Prefs.setHomeForwardPackage(this, target);
                AppLog.i(TAG, "自动选定要保留的桌面: " + target);
            } else if (candidates.isEmpty()) {
                AppLog.i(TAG, "没找到其它桌面，保持空白");
                finishNoAnim();
                return;
            } else {
                showPicker(candidates);
                return;
            }
        }
        forwardTo(target);
    }

    /** 找出除自己以外的所有桌面应用。 */
    private List<String> otherHomePackages() {
        List<String> out = new ArrayList<>();
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            PackageManager pm = getPackageManager();
            List<ResolveInfo> list = pm.queryIntentActivities(intent, 0);
            if (list != null) {
                for (ResolveInfo ri : list) {
                    if (ri == null || ri.activityInfo == null) {
                        continue;
                    }
                    String pkg = ri.activityInfo.packageName;
                    if (pkg == null || pkg.equals(getPackageName())) {
                        continue;
                    }
                    if (!out.contains(pkg)) {
                        out.add(pkg);
                    }
                }
            }
        } catch (Throwable t) {
            AppLog.i(TAG, "枚举桌面失败: " + t);
        }
        return out;
    }

    private void showPicker(final List<String> pkgs) {
        final PackageManager pm = getPackageManager();
        List<String> labels = new ArrayList<>();
        for (String p : pkgs) {
            String label = p;
            try {
                label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(p, 0)));
            } catch (Throwable ignored) {
            }
            labels.add(label + "\n" + p);
        }
        // 作为桌面启动时窗口类型特殊，这里确保能显示对话框
        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Throwable ignored) {
        }
        new AlertDialog.Builder(this)
                .setTitle("选择要保留的车机桌面（本应用会立刻转发过去）")
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String pkg = pkgs.get(which);
                        Prefs.setHomeForwardPackage(HomeProxyActivity.this, pkg);
                        forwardTo(pkg);
                    }
                })
                .setNegativeButton("不转发（保持空白桌面）", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finishNoAnim();
                    }
                })
                .setCancelable(false)
                .show();
    }

    private void forwardTo(String pkg) {
        lastForwardAt = SystemClock.elapsedRealtime();
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null) {
                launch = new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .setPackage(pkg);
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(launch);
            AppLog.i(TAG, "已转发到桌面: " + pkg);
        } catch (Throwable t) {
            AppLog.i(TAG, "转发到桌面失败: " + t);
        }
        finishNoAnim();
    }

    private void finishNoAnim() {
        finish();
        try {
            overridePendingTransition(0, 0);
        } catch (Throwable ignored) {
        }
    }
}
