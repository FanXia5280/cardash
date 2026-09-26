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
    /** 超过这么久没 tick 过，就认为循环已经死了（会被恢复逻辑重播） */
    private static final long STALE_MS = 3000L;

    private static GradientPlayer instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Context app;

    private GradientItem item;
    private float phase;
    private int dir = 1;
    /** 上一次真正下发成功的时间戳（心跳） */
    private volatile long lastTickAt;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                GradientItem g = item;
                if (g == null) return;
                LedOutput.sendRawColor(app, lerp(g.color1, g.color2, phase));
                // ⚠️ 这里以前没有 try：sendRawColor 一旦抛异常（蓝牙断连 / 写失败，
                // 车上跑十几二十分钟总会碰上一次），下面的 postDelayed 就不会执行，
                // 循环彻底停摆 ⇒ 灯停在最后一帧变成一个固定颜色。
                // 现在用 finally 保证下一帧一定排上。
                float step = 0.010f + g.speed / 100f * 0.045f;
                phase += dir * step;
                if (phase >= 1f) {
                    phase = 1f;
                    dir = -1;
                } else if (phase <= 0f) {
                    phase = 0f;
                    dir = 1;
                }
                lastTickAt = System.currentTimeMillis();
            } catch (Throwable t) {
                // 单帧下发失败不算数，下一帧继续（心跳不刷新，看门狗会发现）
            } finally {
                if (item != null) handler.postDelayed(this, INTERVAL);
            }
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
        lastTickAt = System.currentTimeMillis();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    public void stop() {
        item = null;
        handler.removeCallbacks(tick);
    }

    /**
     * 真的还在播才算 true —— 光看 item != null 不够：循环一旦死了 item 还在，
     * 恢复逻辑（LanternBootstrap.restoreGradient）就会以为"还在播"而永远不重播。
     */
    public boolean isPlaying() {
        return item != null
                && System.currentTimeMillis() - lastTickAt < STALE_MS;
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
