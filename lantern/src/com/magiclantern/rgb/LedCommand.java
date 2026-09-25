package com.magiclantern.rgb;

import java.util.UUID;

/**
 * 氛围灯 BLE 指令集（与原版 Magic Lantern 完全一致）。
 *
 * 通信：服务 0000fff0-0000-1000-8000-00805f9b34fb
 *       写特征 0000fff3-0000-1000-8000-00805f9b34fb
 * 帧格式：9 字节，固定头 0x7E，固定尾 0xEF
 *
 * 设备广播名前缀：MELK-
 */
public class LedCommand {

    public static final UUID SVC_MAIN = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    public static final UUID CHR_WRITE = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb");

    /** 兼容其它方案的兜底 UUID（原版只用 fff0/fff3） */
    public static final UUID SVC_TRIONES = UUID.fromString("0000ffb0-0000-1000-8000-00805f9b34fb");
    public static final UUID CHR_TRIONES = UUID.fromString("0000ffb1-0000-1000-8000-00805f9b34fb");
    public static final UUID SVC_JDY = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    public static final UUID CHR_JDY = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    public static final UUID SVC_NORDIC = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID CHR_NORDIC = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");

    public static final UUID[][] PREFERRED = {
            {SVC_MAIN, CHR_WRITE},
            {SVC_TRIONES, CHR_TRIONES},
            {SVC_JDY, CHR_JDY},
            {SVC_NORDIC, CHR_NORDIC},
    };

    /** 设备广播名前缀过滤 */
    public static final String NAME_FILTER = "MELK-";

    private static final byte HEAD = 0x7E;
    private static final byte TAIL = (byte) 0xEF;
    private static final byte FILL = (byte) 0xFF;

    private static byte[] frame(int len, int cmd, int... data) {
        byte[] b = new byte[9];
        b[0] = HEAD;
        b[1] = (byte) len;
        b[2] = (byte) cmd;
        for (int i = 0; i < 7 && i < data.length; i++) {
            b[3 + i] = (byte) data[i];
        }
        b[8] = TAIL;
        return b;
    }

    /** 开 / 关灯：7E 04 04 on 00 on FF 00 EF */
    public static byte[] lightOn(boolean on) {
        byte v = (byte) (on ? 1 : 0);
        return frame(4, 4, v, 0, v, FILL, 0);
    }

    /** 亮度 0-100：7E 04 01 b FF FF FF 00 EF */
    public static byte[] brightness(int percent) {
        int b = Math.max(0, Math.min(100, percent));
        return frame(4, 1, b, FILL, FILL, FILL, 0);
    }

    /** 亮度（带灯模式，原版 lightMode 默认 255） */
    public static byte[] brightness(int percent, int lightMode) {
        int b = Math.max(0, Math.min(100, percent));
        return frame(4, 1, b, lightMode & 0xFF, FILL, FILL, 0);
    }

    /** 静态颜色：7E 07 05 03 R G B 10 EF */
    public static byte[] color(int r, int g, int b) {
        return frame(7, 5, 3, r & 0xFF, g & 0xFF, b & 0xFF, 0x10);
    }

    /** 音乐/拾音律动用颜色：7E 07 05 03 R G B 20 EF */
    public static byte[] rhythmColor(int r, int g, int b) {
        return frame(7, 5, 3, r & 0xFF, g & 0xFF, b & 0xFF, 0x20);
    }

    /** 灯效模式（基本/开合/过渡/流水/流动/拖尾/正跑/反跑）：7E 05 03 mode 06 FF FF 00 EF */
    public static byte[] mode(int mode) {
        return frame(5, 3, mode & 0xFF, 6, FILL, FILL, 0);
    }

    /** 模式速度 0-100：7E 04 02 speed FF FF FF 00 EF */
    public static byte[] speed(int speed) {
        return frame(4, 2, Math.max(0, Math.min(100, speed)), FILL, FILL, FILL, 0);
    }

    /** 场景（第 9 组）：7E 05 31 scene 07 FF FF 01 EF */
    public static byte[] scene(int scene) {
        return frame(5, 0x31, scene & 0xFF, 7, FILL, FILL, 1);
    }

    /**
     * RGBW / CCT 通道开关：7E 04 04 mask lightMode ch FF 00 EF
     * mask: RGB 开 = 0xE0，W 开 = 0x10；全关 = 0x00
     * ch: 对应模式的通道开关值（0/1）
     */
    public static byte[] rgbwStatus(boolean rgbOn, boolean wOn, boolean cctOn, int lightMode, int ch) {
        int mask = 0;
        if (rgbOn) mask |= 0xE0;
        if (wOn) mask |= 0x10;
        return frame(4, 4, mask, lightMode & 0xFF, ch & 0xFF, FILL, 0);
    }

    /** 冷暖白（色温）：7E 06 05 02 warm cold FF 08 EF */
    public static byte[] colorTemperature(int warm, int cold) {
        return frame(6, 5, 2, warm & 0xFF, cold & 0xFF, FILL, 0x08);
    }

    /** 线序：7E 06 81 b2 b1 b0 FF 00 EF（默认 0x010203 = 66051） */
    public static byte[] pinSequence(int sequence) {
        return frame(6, 0x81, (sequence >> 16) & 0xFF, (sequence >> 8) & 0xFF,
                sequence & 0xFF, FILL, 0);
    }

    /** 灯带点数：7E 07 21 lo hi 00 FF 00 EF */
    public static byte[] pixelCount(int count) {
        return frame(7, 0x21, count & 0xFF, (count >> 8) & 0xFF, 0, FILL, 0);
    }

    /** 外置麦克风开关：7E 04 07 on FF FF FF 00 EF */
    public static byte[] externalMic(boolean on) {
        return frame(4, 7, on ? 1 : 0, FILL, FILL, FILL, 0);
    }

    /** 外置麦克风灵敏度：7E 04 06 sens FF FF FF 00 EF */
    public static byte[] externalMicSensitive(int sensitive) {
        return frame(4, 6, sensitive & 0xFF, FILL, FILL, FILL, 0);
    }

    /** 外置麦克风律动模式：7E 07 03 (mode+128) 04 FF FF 00 EF */
    public static byte[] externalMicEqMode(int mode) {
        return frame(7, 3, (mode + 128) & 0xFF, 4, FILL, FILL, 0);
    }
}
