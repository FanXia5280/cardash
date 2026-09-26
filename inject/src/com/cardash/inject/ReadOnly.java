package com.cardash.inject;

/**
 * **只读总开关**（2026-09-26 起默认开）。
 *
 * <h3>为什么要有这个开关</h3>
 *
 * 用户实测发现：车机语音退出导航后，深蓝定制版高德会隔几十秒**自动重新开始导航
 * 上次的目的地**（239 / 241 两个版本都复现，原车导航不受影响）。排查下来，咱们
 * 桥接里凡是"往车机写点什么 / 调车机一个方法"的动作，都可能被车机那边理解成
 * 「有人又要导航了」。
 *
 * 用户的要求很明确：**D 桌面这边只做采集（只读），不要下发任何命令**。
 *
 * <h3>这个开关管什么、不管什么</h3>
 *
 * 开（默认）—— 以下动作一律不做：
 * <ul>
 *   <li>往 {@code Settings.Secure/Global/System} 写东西（自启用无障碍、通知使用权）；</li>
 *   <li>通过 IPC 去问「当前路线终点」（{@code S05Navi.probeDestination}）；</li>
 *   <li>补发 HUD 开屏 / 开关 HUD（{@code HudRuntime.bounceHudSwitch/wakeHudLater/…}）；</li>
 *   <li>反射调用 HUD 唤醒接口、转发广播。</li>
 * </ul>
 *
 * 不管 —— 这些是纯采集，开着也安全：
 * 读车机缓存字段、注册监听器（厂商通道 / 通知监听 / 无障碍读屏）、
 * 起我们自己的桥接服务、HTTP 接口（{@code /state}、{@code /diag}）、
 * 以及**用户在界面上主动点的操作**（氛围灯调色、设置页开关）。
 *
 * <h3>怎么临时打开</h3>
 *
 * {@code /setwrite?on=1}（重启桌面自动回到默认只读）。只在排查问题时用，
 * 用完记得 {@code /setwrite?on=0}。
 */
public final class ReadOnly {

    /** true = 只读（默认）：只采集，不向车机下发任何指令。 */
    public static volatile boolean enabled = true;

    private ReadOnly() {
    }

    /** 是否允许"写车机/下发指令"这一类动作。只读模式下返回 false。 */
    public static boolean allowWrite() {
        return !enabled;
    }
}
