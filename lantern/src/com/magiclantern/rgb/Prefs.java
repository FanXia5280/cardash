package com.magiclantern.rgb;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** 轻量配置存储 */
public class Prefs {

    private static final String FILE = "magic_lantern";
    private static Prefs instance;
    private final SharedPreferences sp;

    private Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static Prefs get(Context c) {
        if (instance == null) instance = new Prefs(c);
        return instance;
    }

    public int getBrightness() {
        return sp.getInt("brightness", 100);
    }

    public void setBrightness(int v) {
        sp.edit().putInt("brightness", v).apply();
    }

    public int getSpeed() {
        return sp.getInt("speed", 60);
    }

    public void setSpeed(int v) {
        sp.edit().putInt("speed", v).apply();
    }

    public int getColor() {
        return sp.getInt("color", 0xFFFF3D00);
    }

    public void setColor(int c) {
        sp.edit().putInt("color", c).apply();
    }

    public int getSensitivity() {
        return sp.getInt("sensitivity", 50);
    }

    public void setSensitivity(int v) {
        sp.edit().putInt("sensitivity", v).apply();
    }

    public int getRhythmMode() {
        return sp.getInt("rhythm_mode", 0);
    }

    public void setRhythmMode(int v) {
        sp.edit().putInt("rhythm_mode", v).apply();
    }

    public int getLedCount() {
        return sp.getInt("led_count", 60);
    }

    public void setLedCount(int v) {
        sp.edit().putInt("led_count", v).apply();
    }

    public int getPinSequence() {
        return sp.getInt("pin_sequence", 0);
    }

    public void setPinSequence(int v) {
        sp.edit().putInt("pin_sequence", v).apply();
    }

    public boolean isAutoConnect() {
        return sp.getBoolean("auto_connect", true);
    }

    public void setAutoConnect(boolean v) {
        sp.edit().putBoolean("auto_connect", v).apply();
    }

    /** 扫描时是否只显示 MELK- 开头的设备 */
    public boolean isNameFilterEnabled() {
        return sp.getBoolean("name_filter", true);
    }

    public void setNameFilterEnabled(boolean v) {
        sp.edit().putBoolean("name_filter", v).apply();
    }

    public boolean isPowerOn() {
        return sp.getBoolean("power", true);
    }

    public void setPowerOn(boolean v) {
        sp.edit().putBoolean("power", v).apply();
    }

    /** 常用颜色（自定义色块） */
    public List<Integer> getCustomColors() {
        String raw = sp.getString("custom_colors", "");
        List<Integer> list = new ArrayList<Integer>();
        if (raw != null && raw.length() > 0) {
            String[] arr = raw.split(",");
            for (String s : arr) {
                try {
                    list.add(Integer.valueOf(Integer.parseInt(s)));
                } catch (Exception e) {
                    // ignore
                }
            }
        }
        while (list.size() < 12) {
            list.add(Integer.valueOf(0));
        }
        return list;
    }

    public void setCustomColors(List<Integer> colors) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < colors.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(colors.get(i).intValue());
        }
        sp.edit().putString("custom_colors", sb.toString()).apply();
    }

    // ---- 定时 ----
    public boolean isTimingOnEnabled() {
        return sp.getBoolean("timing_on_enabled", false);
    }

    public void setTimingOnEnabled(boolean v) {
        sp.edit().putBoolean("timing_on_enabled", v).apply();
    }

    public int getTimingOnHour() {
        return sp.getInt("timing_on_hour", 19);
    }

    public int getTimingOnMinute() {
        return sp.getInt("timing_on_minute", 0);
    }

    public void setTimingOn(int hour, int minute) {
        sp.edit().putInt("timing_on_hour", hour).putInt("timing_on_minute", minute).apply();
    }

    public boolean isTimingOffEnabled() {
        return sp.getBoolean("timing_off_enabled", false);
    }

    public void setTimingOffEnabled(boolean v) {
        sp.edit().putBoolean("timing_off_enabled", v).apply();
    }

    public int getTimingOffHour() {
        return sp.getInt("timing_off_hour", 23);
    }

    public int getTimingOffMinute() {
        return sp.getInt("timing_off_minute", 30);
    }

    public void setTimingOff(int hour, int minute) {
        sp.edit().putInt("timing_off_hour", hour).putInt("timing_off_minute", minute).apply();
    }

    /** 重复周期：bit0=周日 … bit6=周六，0 表示不重复（仅一次） */
    public int getTimingRepeat() {
        return sp.getInt("timing_repeat", 0);
    }

    public void setTimingRepeat(int v) {
        sp.edit().putInt("timing_repeat", v).apply();
    }

    // ---- 主页常用模式（"group,cmd"）----

    public List<String> getFavModes() {
        List<String> list = new ArrayList<String>();
        String raw = sp.getString("fav_modes", "");
        if (raw != null && raw.length() > 0) {
            String[] arr = raw.split(";");
            for (String s : arr) {
                if (s != null && s.length() > 0) list.add(s);
            }
        }
        if (list.isEmpty()) {
            list.add("0,201"); // 默认：红紫交替渐变
        }
        return list;
    }

    public void setFavModes(List<String> modes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < modes.size(); i++) {
            if (i > 0) sb.append(';');
            sb.append(modes.get(i));
        }
        sp.edit().putString("fav_modes", sb.toString()).apply();
    }

    public void addFavMode(int group, int cmd) {
        List<String> list = getFavModes();
        String item = group + "," + cmd;
        if (list.contains(item)) return;
        list.add(item);
        while (list.size() > 6) list.remove(0);
        setFavModes(list);
    }

    public void removeFavMode(int group, int cmd) {
        List<String> list = getFavModes();
        list.remove(group + "," + cmd);
        setFavModes(list);
    }

    // ---- 自定义渐变（场景模式 > 自定义）----

    public List<GradientItem> getGradients() {
        List<GradientItem> list = new ArrayList<GradientItem>();
        String raw = sp.getString("custom_gradients", "");
        if (raw != null && raw.length() > 0) {
            String[] arr = raw.split(";");
            for (String s : arr) {
                GradientItem g = GradientItem.parse(s);
                if (g != null) list.add(g);
            }
        }
        return list;
    }

    public void setGradients(List<GradientItem> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(';');
            sb.append(list.get(i).serialize());
        }
        sp.edit().putString("custom_gradients", sb.toString()).apply();
    }

    /** 保存渐变（oldName 为编辑前的名称，可为 null）；同名覆盖 */
    public void saveGradient(String oldName, GradientItem item) {
        List<GradientItem> list = getGradients();
        String key = GradientItem.clean(item.name);
        for (int i = list.size() - 1; i >= 0; i--) {
            String n = list.get(i).name;
            if (n.equals(key) || (oldName != null && n.equals(oldName))) list.remove(i);
        }
        list.add(item);
        setGradients(list);
    }

    public void removeGradient(String name) {
        List<GradientItem> list = getGradients();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).name.equals(name)) list.remove(i);
        }
        setGradients(list);
    }

    /** 协议探测记录（真机上试出的有效命令） */
    public String getProbeResult() {
        return sp.getString("probe_result", "");
    }

    public void setProbeResult(String v) {
        sp.edit().putString("probe_result", v).apply();
    }

    // ---- 保活：上次正在播放的自定义渐变名 ----

    public String getLastGradient() {
        return sp.getString("last_gradient", "");
    }

    public void setLastGradient(String name) {
        sp.edit().putString("last_gradient", name == null ? "" : name).apply();
    }

    public String getLastDevice() {
        return sp.getString("last_device", null);
    }

    public void setLastDevice(String address) {
        sp.edit().putString("last_device", address).apply();
    }
}
