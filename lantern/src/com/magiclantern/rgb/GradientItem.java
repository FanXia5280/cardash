package com.magiclantern.rgb;

/**
 * 自定义渐变：两个颜色之间往复平滑过渡。
 *
 * 固件里自带的"红紫交替渐变"等是固定色对（模式码 199-204，颜色写死在设备固件里，
 * 无法修改），所以自定义渐变由 App 端按固定节拍下发插值颜色实现。
 */
public class GradientItem {

    public String name;
    public int color1;
    public int color2;
    public int speed; // 0-100，越大越快

    public GradientItem(String name, int color1, int color2, int speed) {
        this.name = name;
        this.color1 = color1;
        this.color2 = color2;
        this.speed = speed;
    }

    public String serialize() {
        return clean(name) + "|" + color1 + "|" + color2 + "|" + speed;
    }

    public static GradientItem parse(String s) {
        try {
            String[] p = s.split("\\|");
            if (p.length < 4) return null;
            return new GradientItem(p[0], (int) Long.parseLong(p[1]),
                    (int) Long.parseLong(p[2]), Integer.parseInt(p[3]));
        } catch (Exception e) {
            return null;
        }
    }

    /** 去掉分隔符，避免破坏持久化格式 */
    public static String clean(String s) {
        if (s == null) return "";
        return s.replace("|", "").replace(";", "").trim();
    }

    public GradientItem copy() {
        return new GradientItem(name, color1, color2, speed);
    }
}
