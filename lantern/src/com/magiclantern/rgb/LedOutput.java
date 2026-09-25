package com.magiclantern.rgb;

import android.content.Context;
import android.graphics.Color;

/** 灯光输出统一出口（指令格式与原版一致） */
public class LedOutput {

    /** 线序值：1=R 2=G 3=B，按 (ch1<<16)|(ch2<<8)|ch3 */
    private static final int[] PIN_SEQUENCE = {
            0x010203, // R G B
            0x010302, // R B G
            0x020103, // G R B
            0x020301, // G B R
            0x030102, // B R G
            0x030201  // B G R
    };

    private static int pinValue(Context c) {
        int i = Math.max(0, Math.min(5, Prefs.get(c).getPinSequence()));
        return PIN_SEQUENCE[i];
    }

    /**
     * 用户主动切走时：停掉渐变，并清除"保活恢复"记录。
     * （这样下次开机/重连时不会又自动把渐变跑起来）
     */
    private static void stopGradient(Context c) {
        GradientPlayer gp = GradientPlayer.get(c);
        gp.stop();
        Prefs.get(c).setLastGradient("");
    }

    /** 静态颜色（用户主动操作：会停止自定义渐变播放） */
    public static void sendColor(Context c, int color) {
        stopGradient(c);
        sendRawColor(c, color);
    }

    /** 直接发色（渐变播放器内部使用，不打断播放） */
    public static void sendRawColor(Context c, int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        int[] ch = new int[]{r, g, b};
        sendChannels(c, ch);
    }

    private static void sendChannels(Context c, int[] ch) {
        int v = pinValue(c);
        int map = v;
        int c1 = (map >> 16) & 0xFF; // 1=R 2=G 3=B
        int c2 = (map >> 8) & 0xFF;
        int c3 = map & 0xFF;
        BleController.get(c).send(LedCommand.color(ch[c1 - 1], ch[c2 - 1], ch[c3 - 1]));
    }

    /** 律动颜色（音乐/拾音，帧尾 0x20） */
    public static void sendRhythmColor(Context c, int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        int[] ch = new int[]{r, g, b};
        int v = pinValue(c);
        BleController.get(c).send(LedCommand.rhythmColor(
                ch[((v >> 16) & 0xFF) - 1], ch[((v >> 8) & 0xFF) - 1], ch[(v & 0xFF) - 1]));
    }

    /** 律动（HSV 直接下发，value 作为亮度叠加） */
    public static void sendHsv(Context c, float hue, float sat, float val) {
        float[] hsv = new float[]{hue, sat, 1f};
        int color = Color.HSVToColor(hsv);
        int brightness = (int) (Prefs.get(c).getBrightness() * val);
        int r = (int) (((color >> 16) & 0xFF) * brightness / 100f);
        int g = (int) (((color >> 8) & 0xFF) * brightness / 100f);
        int b = (int) ((color & 0xFF) * brightness / 100f);
        int[] ch = new int[]{Math.min(255, r), Math.min(255, g), Math.min(255, b)};
        int v = pinValue(c);
        BleController.get(c).send(LedCommand.rhythmColor(
                ch[((v >> 16) & 0xFF) - 1], ch[((v >> 8) & 0xFF) - 1], ch[(v & 0xFF) - 1]));
    }

    /** 亮度 0-100 */
    public static void setBrightness(Context c, int percent) {
        BleController.get(c).send(LedCommand.brightness(percent));
    }

    /** 灯效模式：第 9 组（场景）走 scene 帧，其余走 mode 帧 */
    public static void sendMode(Context c, int groupIndex, int modeValue, int speed) {
        stopGradient(c);
        BleController ble = BleController.get(c);
        if (groupIndex == 8) {
            ble.send(LedCommand.scene(modeValue));
        } else {
            ble.send(LedCommand.mode(modeValue));
        }
        ble.send(LedCommand.speed(speed));
    }

    public static void sendModeValue(Context c, int groupIndex, int modeValue) {
        stopGradient(c);
        if (groupIndex == 8) {
            BleController.get(c).send(LedCommand.scene(modeValue));
        } else {
            BleController.get(c).send(LedCommand.mode(modeValue));
        }
    }

    public static void sendSpeed(Context c, int speed) {
        BleController.get(c).send(LedCommand.speed(speed));
    }

    /** 开 / 关灯（关闭时同时清通道与亮度，确保设备真正熄灭） */
    /**
     * 开 / 关灯。
     *
     * 逆向原版（BluetoothLEService.lightOn + MainActivity.onDeviceLightOnClick）确认：
     * 原版开关只发这一条命令 —— 7E 04 04 v 00 v FF 00 EF，不跟任何其它命令。
     * 之前我们额外跟发"黑色 / 亮度0 / 通道关"，反而会把关灯状态顶回来
     * （表现为灯闪一下又亮），所以这里与原版保持一致。
     */
    public static void power(Context c, boolean on) {
        stopGradient(c);
        Prefs.get(c).setPowerOn(on);
        BleController.get(c).send(LedCommand.lightOn(on));
    }

    public static void sendPinSequence(Context c) {
        BleController.get(c).send(LedCommand.pinSequence(pinValue(c)));
    }

    public static void sendPixelCount(Context c, int count) {
        BleController.get(c).send(LedCommand.pixelCount(count));
    }

    /**
     * 连上灯之后**必须重放一遍"设备侧设置"**。
     *
     * ⚠️ 2026-09-25 实车踩到（用户报"调成红色显示成绿色、去设置里改线序也没用"）：
     * 线序（pinSequence）、灯带点数这些是**写在灯里**的；灯的固件掉电/重连后会回到
     * 出厂默认线序，而 App 只在「用户手动改设置那一刻」发过一次 ⇒ 之后每次重新连上
     * 颜色都不对，用户再去设置里看，设置显示的还是他改过的值（存的是 App 侧），
     * 于是变成"改了也没用"。**同理，之前那个"灯闪一下又亮"也是这类"只在连上时补发一半设置"的问题。**
     *
     * 所以这里按"用户手动设一遍"的顺序全部补发：线序 → 灯带点数 → 开关 → 亮度 → 颜色。
     * 发色用 Raw 版本（不打断可能正在播放的自定义渐变）。
     */
    public static void reapplyDeviceConfig(Context c) {
        if (c == null) return;
        Prefs p = Prefs.get(c);
        BleController ble = BleController.get(c);
        try {
            ble.send(LedCommand.pinSequence(pinValue(c)));
            ble.send(LedCommand.pixelCount(p.getLedCount()));
            ble.send(LedCommand.lightOn(p.isPowerOn()));
            ble.send(LedCommand.brightness(p.getBrightness()));
            sendRawColor(c, p.getColor());
        } catch (Throwable t) {
            // 单条失败不影响其它
        }
    }
}
