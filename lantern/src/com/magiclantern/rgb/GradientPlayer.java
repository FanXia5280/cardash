package com.magiclantern.rgb;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * 自定义渐变播放器：每隔约 70ms 下发一帧插值颜色，
 * 在 color1 → color2 → color1 之间往复平滑过渡。
 *
 * 任何"静态颜色 / 灯效模式 / 开关灯"操作都会自动停止播放。
 */
public class GradientPlayer {

    private static final long INTERVAL = 70L;

    private static GradientPlayer instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Context app;

    private GradientItem item;
    private float phase;
    private int dir = 1;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            GradientItem g = item;
            if (g == null) return;
            LedOutput.sendRawColor(app, lerp(g.color1, g.color2, phase));
            float step = 0.010f + g.speed / 100f * 0.045f;
            phase += dir * step;
            if (phase >= 1f) {
                phase = 1f;
                dir = -1;
            } else if (phase <= 0f) {
                phase = 0f;
                dir = 1;
            }
            handler.postDelayed(this, INTERVAL);
        }
    };

    private GradientPlayer(Context c) {
        app = c.getApplicationContext();
    }

    public static GradientPlayer get(Context c) {
        if (instance == null) instance = new GradientPlayer(c);
        return instance;
    }

    public void play(GradientItem g) {
        if (g == null) return;
        item = g;
        phase = 0f;
        dir = 1;
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    public void stop() {
        item = null;
        handler.removeCallbacks(tick);
    }

    public boolean isPlaying() {
        return item != null;
    }

    public String playingName() {
        return item == null ? null : item.name;
    }

    /** 正在播放时调整速度（即时生效） */
    public void applySpeed(GradientItem g) {
        if (item != null && item.name.equals(g.name)) item.speed = g.speed;
    }

    /** 编辑保存后同步（名称变了也能接力播放） */
    public void replace(String oldName, GradientItem g) {
        if (item != null && item.name.equals(oldName)) {
            item = g;
        }
    }

    private static int lerp(int c1, int c2, float t) {
        int r = (int) (((c1 >> 16) & 0xFF) * (1 - t) + ((c2 >> 16) & 0xFF) * t + 0.5f);
        int g = (int) (((c1 >> 8) & 0xFF) * (1 - t) + ((c2 >> 8) & 0xFF) * t + 0.5f);
        int b = (int) ((c1 & 0xFF) * (1 - t) + (c2 & 0xFF) * t + 0.5f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
