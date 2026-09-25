package com.cardash.solo;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.Process;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 独立包的「能不能行」探测器。
 *
 * <p>它**不读任何车身数据**，只回答三个问题（这三条决定了我们能不能彻底不靠 D.apk）：
 * <ol>
 *   <li><b>身份</b>：我们装上之后是什么身份 —— uid 是不是 1000（system）、
 *       有没有拿到 sharedUserId、是不是系统应用、能读 logcat 吗。</li>
 *   <li><b>目标</b>：车机上到底有哪些厂商服务（tinnove / openos / virtualcar / polymeric），
 *       它们叫什么、exported 没有、要什么权限 —— 这就是"我们自己绑厂商 SDK"要绑的目标。</li>
 *   <li><b>能不能绑上</b>：对找到的服务逐个 bindService，看能不能拿到 binder、
 *       binder 的接口描述符是什么（能拿到就说明这条路通）。</li>
 * </ol>
 *
 * <p>结论全部拼成一段纯文本，界面上显示、HTTP 上也能取（`/solo`），照旧"复制发回来"。
 */
public final class SoloProbe {

    /** 关心的厂商包名/服务名关键词（大小写不敏感） */
    private static final String[] KW = {
            "tinnove", "openos", "virtualcar", "virtual_car", "polymeric",
            "deepal", "skill", "wecar",
    };

    /** bindService 探测的结果，异步回调往里写 */
    private static final List<String> BIND_LOG =
            Collections.synchronizedList(new ArrayList<String>());

    private SoloProbe() { }

    public static String report(Context ctx) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("CarDash 独立包探测器 (solo)\n");
        sb.append("时间: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                Locale.US).format(new java.util.Date())).append('\n');

        try {
            identity(ctx, sb);
        } catch (Throwable t) {
            sb.append("身份段异常: ").append(t).append('\n');
        }
        try {
            environment(ctx, sb);
        } catch (Throwable t) {
            sb.append("环境段异常: ").append(t).append('\n');
        }
        try {
            vendorPackages(ctx, sb);
        } catch (Throwable t) {
            sb.append("包扫描段异常: ").append(t).append('\n');
        }
        try {
            bindAll(ctx, sb);
        } catch (Throwable t) {
            sb.append("绑定段异常: ").append(t).append('\n');
        }
        try {
            logcat(ctx, sb);
        } catch (Throwable t) {
            sb.append("logcat 段异常: ").append(t).append('\n');
        }
        return sb.toString();
    }

    // ─────────────────────────────── 1. 身份

    private static void identity(Context ctx, StringBuilder sb) {
        String pkg = ctx.getPackageName();
        sb.append("\n【一、我们自己的身份】\n");
        sb.append("  包名       = ").append(pkg).append('\n');
        sb.append("  myUid()    = ").append(Process.myUid())
          .append(Process.myUid() == 1000 ? "   ← system（和桌面同 uid，成了）"
                                          : "   ← 普通应用 uid（没进 system）").append('\n');
        sb.append("  myPid      = ").append(Process.myPid()).append('\n');
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(pkg, 0);
            ApplicationInfo ai = pi.applicationInfo;
            sb.append("  sharedUserId = ").append(pi.sharedUserId == null ? "（无）" : pi.sharedUserId)
              .append('\n');
            sb.append("  versionName  = ").append(pi.versionName).append('\n');
            if (ai != null) {
                sb.append("  FLAG_SYSTEM  = ").append((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0)
                  .append("    FLAG_UPDATED_SYSTEM_APP = ")
                  .append((ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0).append('\n');
                sb.append("  dataDir      = ").append(ai.dataDir).append('\n');
            }
        } catch (Throwable t) {
            sb.append("  取包信息失败: ").append(t).append('\n');
        }
        // 几个关键权限：普通应用拿不到 READ_LOGS，system uid 可以
        String[] perms = {
                android.Manifest.permission.READ_LOGS,
                android.Manifest.permission.INTERNET,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
        };
        for (String p : perms) {
            try {
                sb.append("  权限 ").append(p).append(" = ")
                  .append(ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
                          ? "有" : "没有").append('\n');
            } catch (Throwable t) {
                sb.append("  权限 ").append(p).append(" = 查询失败(")
                  .append(t.getClass().getSimpleName()).append(")\n");
            }
        }
    }

    // ─────────────────────────────── 2. 环境

    private static void environment(Context ctx, StringBuilder sb) {
        sb.append("\n【二、环境探测】\n");
        String[] classes = {
                "android.car.Car",
                "android.car.hardware.CarPropertyValue",
                "android.car.hardware.property.CarPropertyValue",
                "com.openos.virtualcar.VirtualCar",
                "com.openos.virtualcar.VirtualCarPropertyManager",
                "com.tinnove.comlib.virtual.VirtualCarManagerImpl",
                "com.wt.tinnovecoreservice.virtualcar.policy.property.VirtualCarPropertyServiceAdapterPolicy",
        };
        for (String c : classes) {
            try {
                Class.forName(c);
                sb.append("  类 ").append(c).append(" = 有\n");
            } catch (Throwable t) {
                sb.append("  类 ").append(c).append(" = 没有\n");
            }
        }
        // 直接看 ServiceManager 里注册了哪些名字（需要权限，拿不到就记一句）
        String[] names = {"virtualcar", "virtual_car", "virtualcar_property_service",
                "polymeric", "polymeric_service", "car_service", "vehicle"};
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method get = sm.getMethod("getService", String.class);
            for (String n : names) {
                Object b = get.invoke(null, n);
                sb.append("  getService(\"").append(n).append("\") = ")
                  .append(b == null ? "null" : "有 binder").append('\n');
            }
        } catch (Throwable t) {
            sb.append("  ServiceManager 查不了: ").append(t.getClass().getSimpleName()).append('\n');
        }
    }

    // ─────────────────────────────── 3. 厂商服务清单

    private static void vendorPackages(Context ctx, StringBuilder sb) {
        PackageManager pm = ctx.getPackageManager();
        sb.append("\n【三、厂商服务清单（要绑的目标就在这里面）】\n");
        List<PackageInfo> all;
        try {
            all = pm.getInstalledPackages(PackageManager.GET_SERVICES);
        } catch (Throwable t) {
            sb.append("  列包失败: ").append(t).append('\n');
            return;
        }
        int hit = 0;
        for (PackageInfo pi : all) {
            String pkg = pi.packageName == null ? "" : pi.packageName.toLowerCase(Locale.ROOT);
            if (!matches(pkg)) continue;
            hit++;
            sb.append("\n  ◆ ").append(pi.packageName)
              .append("  v").append(pi.versionName).append('\n');
            ApplicationInfo ai = pi.applicationInfo;
            if (ai != null) {
                boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                sb.append("      uid=").append(ai.uid);
                if (ai.uid == 1000) sb.append("(system)");
                sb.append(sys ? "  系统应用" : "  普通应用").append('\n');
            }
            ServiceInfo[] svcs = pi.services;
            if (svcs == null || svcs.length == 0) {
                sb.append("      （没有 <service>）\n");
                continue;
            }
            for (ServiceInfo s : svcs) {
                sb.append("      service ").append(s.name)
                  .append(s.exported ? "  [exported]" : "  [不导出]");
                if (s.permission != null) sb.append("  需要权限=").append(s.permission);
                sb.append("   process=").append(s.processName);
                sb.append('\n');
            }
        }
        if (hit == 0) sb.append("  （一个都没匹配到 —— 关键词: ")
                .append(join(KW)).append("）\n");
    }

    private static boolean matches(String pkg) {
        for (String k : KW) {
            if (pkg.contains(k)) return true;
        }
        return false;
    }

    // ─────────────────────────────── 4. 试着绑上去

    private static void bindAll(Context ctx, StringBuilder sb) {
        PackageManager pm = ctx.getPackageManager();
        sb.append("\n【四、绑定探测（能拿到 binder = 这条路通）】\n");
        List<PackageInfo> all;
        try {
            all = pm.getInstalledPackages(PackageManager.GET_SERVICES);
        } catch (Throwable t) {
            sb.append("  列包失败: ").append(t).append('\n');
            return;
        }
        List<ComponentName> targets = new ArrayList<>();
        for (PackageInfo pi : all) {
            if (pi.packageName == null) continue;
            String low = pi.packageName.toLowerCase(Locale.ROOT);
            if (!matches(low)) continue;
            if (pi.services == null) continue;
            for (ServiceInfo s : pi.services) {
                if (s == null || s.name == null) continue;
                // 只看名字像"车辆数据服务"的，别去绑媒体/语音那些无关服务
                String n = s.name.toLowerCase(Locale.ROOT);
                if (n.contains("virtual") || n.contains("polymeric")
                        || n.contains("vehicle") || n.contains("vcar")
                        || n.contains("skill")) {
                    targets.add(new ComponentName(pi.packageName, s.name));
                }
            }
        }
        if (targets.isEmpty()) {
            sb.append("  （没有可绑的目标 —— 大概率是厂商服务不在 <service> 里，\n");
            sb.append("    而是靠 ServiceManager 注册的系统服务，那就得走别的路）\n");
            return;
        }
        sb.append("  准备尝试 ").append(targets.size()).append(" 个服务\n");
        BIND_LOG.clear();
        int i = 0;
        for (ComponentName cn : targets) {
            if (i++ >= 12) {
                sb.append("  （只试前 12 个）\n");
                break;
            }
            try {
                Intent it = new Intent();
                it.setComponent(cn);
                boolean ok = ctx.bindService(it, new ProbeConn(cn), Context.BIND_AUTO_CREATE);
                sb.append("  bind ").append(cn.flattenToShortString())
                  .append(" -> ").append(ok ? "发起成功" : "发起失败").append('\n');
            } catch (Throwable t) {
                sb.append("  bind ").append(cn.flattenToShortString())
                  .append(" -> 抛异常 ").append(t.getClass().getSimpleName()).append('\n');
            }
        }
        // 回调是异步的，这次报告里可能还没回来 —— 报告末尾会再列一次
        try {
            Thread.sleep(1200);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        sb.append("  ---- 绑定回调（异步，可能要再点一次刷新）----\n");
        synchronized (BIND_LOG) {
            if (BIND_LOG.isEmpty()) {
                sb.append("    （还没有回调 —— 多半是被权限/导出限制挡了，看上面那几行）\n");
            } else {
                for (String s : BIND_LOG) sb.append("    ").append(s).append('\n');
            }
        }
    }

    /** 绑定回调：只记录"拿到 binder 了没、接口描述符是什么" */
    private static final class ProbeConn implements ServiceConnection {
        private final ComponentName cn;
        ProbeConn(ComponentName cn) { this.cn = cn; }

        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            String desc;
            try {
                desc = binder == null ? "binder=null" : binder.getInterfaceDescriptor();
            } catch (Throwable t) {
                desc = "取描述符失败 " + t.getClass().getSimpleName();
            }
            BIND_LOG.add(cn.flattenToShortString() + "  连上了！  " + desc);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            BIND_LOG.add(cn.flattenToShortString() + "  断开");
        }
    }

    // ─────────────────────────────── 5. logcat

    private static void logcat(Context ctx, StringBuilder sb) {
        sb.append("\n【五、能不能读 logcat（决定语音那条兜底链路还在不在）】\n");
        // ⚠️ 写全 java.lang.Process：本文件 import 了 android.os.Process（要 myUid），
        //    简单名 Process 会解析成它，跟跑外部命令的 java.lang.Process 冲突（编译不过）。
        java.lang.Process p = null;
        try {
            p = new ProcessBuilder("logcat", "-d", "-t", "3").redirectErrorStream(true).start();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder out = new StringBuilder();
            String line;
            int n = 0;
            while ((line = r.readLine()) != null && n++ < 5) {
                out.append("      ").append(line).append('\n');
            }
            int code = p.waitFor();
            sb.append("  logcat -d -t 3 退出码 = ").append(code).append('\n');
            sb.append(out.length() == 0 ? "      （没有输出 = 没权限）\n" : out.toString());
        } catch (Throwable t) {
            sb.append("  跑 logcat 失败: ").append(t.getClass().getSimpleName()).append('\n');
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) { }
            }
        }
    }

    private static String join(String[] a) {
        StringBuilder b = new StringBuilder();
        for (String s : a) {
            if (b.length() > 0) b.append(',');
            b.append(s);
        }
        return b.toString();
    }
}
