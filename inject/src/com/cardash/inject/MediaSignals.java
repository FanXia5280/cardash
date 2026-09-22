package com.cardash.inject;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * 读取车机上正在播放的媒体。
 *
 * getActiveSessions 要求调用方是「已启用的通知监听器」，所以这里依次尝试：
 *   1. 我们自己注入的 NavListenerService
 *   2. D.apk 原有的 MusicService（用户很可能早就给它开过通知使用权）
 */
public final class MediaSignals {

    private Context ctx;
    private MediaSessionManager msm;
    private HandlerThread thread;
    private Handler handler;
    private ComponentName[] candidates;
    private volatile boolean running;
    private volatile ComponentName working;
    /** 上一次计算封面用的曲目标识，用来避免每 600ms 重新压一次图 */
    private String lastCoverKey;
    /** 上一次查歌词的曲目标识 */
    private String lastLyricKey;
    /** 这一首歌有没有已经发起过联网兜底（避免反复请求） */
    private boolean lyricFetchStarted;

    public void start(Context context) {
        if (running) return;
        running = true;

        ctx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);

        String pkg = ctx.getPackageName();
        candidates = new ComponentName[] {
                new ComponentName(pkg, "com.cardash.inject.NavListenerService"),
                new ComponentName(pkg, "com.deepalhome.launcher.music.MusicService")
        };

        thread = new HandlerThread("cardash-media");
        thread.start();
        handler = new Handler(thread.getLooper());

        Runnable poller = new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                try {
                    poll();
                } catch (Throwable ignored) {
                    // 忽略
                }
                if (running && handler != null) {
                    handler.postDelayed(this, 600L);
                }
            }
        };
        handler.post(poller);
    }

    public void stop() {
        running = false;
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
    }

    private void poll() {
        StateHub hub = StateHub.get();
        if (msm == null) {
            hub.mediaError = "设备无 MediaSessionManager";
            return;
        }

        List<MediaController> sessions = null;
        ComponentName lastError = null;

        if (working != null) {
            sessions = trySessions(working);
        }
        if (sessions == null) {
            for (ComponentName cn : candidates) {
                sessions = trySessions(cn);
                if (sessions != null) {
                    working = cn;
                    break;
                }
                lastError = cn;
            }
        }

        if (sessions == null) {
            hub.mediaError = "请在设置里开启「通知使用权」（" + lastError + "）";
            return;
        }
        hub.mediaError = null;

        MediaController best = null;
        for (MediaController c : sessions) {
            PlaybackState ps = c.getPlaybackState();
            if (ps == null) continue;
            if (ps.getState() == PlaybackState.STATE_PLAYING) {
                best = c;
                break;
            }
            if (best == null) best = c;
        }

        if (best == null) {
            hub.clearMusic();
            return;
        }

        MediaMetadata md = best.getMetadata();
        hub.mTitle = md == null ? null : first(str(md.getText(MediaMetadata.METADATA_KEY_TITLE)),
                str(md.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)));
        hub.mArtist = md == null ? null : first(str(md.getText(MediaMetadata.METADATA_KEY_ARTIST)),
                str(md.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)),
                str(md.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)));
        hub.mAlbum = md == null ? null : str(md.getText(MediaMetadata.METADATA_KEY_ALBUM));

        PlaybackState ps = best.getPlaybackState();
        if (ps != null) {
            hub.mPlaying = ps.getState() == PlaybackState.STATE_PLAYING;
            long pos = ps.getPosition();
            hub.mPosition = pos >= 0 ? pos / 1000.0 : null;
        } else {
            hub.mPlaying = null;
            hub.mPosition = null;
        }

        if (md != null && md.getLong(MediaMetadata.METADATA_KEY_DURATION) > 0) {
            hub.mDuration = md.getLong(MediaMetadata.METADATA_KEY_DURATION) / 1000.0;
        } else {
            hub.mDuration = null;
        }

        // 记下到底是我们注入的监听器还是 D.apk 原有的那个在起作用，方便排查
        hub.setSource("music", "mediasession:"
                + (working == null ? "?" : working.getShortClassName()));
        updateCover(hub, md);
        updateLyrics(hub);
    }

    /**
     * 专辑封面。
     *
     * MediaMetadata 里封面是个 Bitmap，不能直接进 JSON，所以缩到 240px 再压成
     * JPEG base64。iPhone 每 250ms 轮询一次 /state，封面必须足够小；而且只在
     * 曲目变化时重算，平时复用同一个字符串。
     */
    private void updateCover(StateHub hub, MediaMetadata md) {
        String key = hub.mTitle == null ? null
                : (hub.mTitle + '|' + hub.mArtist + '|' + hub.mDuration);
        if (key != null && key.equals(lastCoverKey)) return;
        lastCoverKey = key;

        hub.mCover = null;
        if (md == null || key == null) return;

        Bitmap src = null;
        String[] keys = {
                MediaMetadata.METADATA_KEY_ALBUM_ART,
                MediaMetadata.METADATA_KEY_ART,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON,
        };
        for (String k : keys) {
            try {
                src = md.getBitmap(k);
            } catch (Throwable ignored) {
                src = null;
            }
            if (src != null) break;
        }
        if (src == null || src.isRecycled()) return;

        try {
            int w = src.getWidth(), h = src.getHeight();
            if (w <= 0 || h <= 0) return;

            int max = 240;
            Bitmap out = src;
            if (w > max || h > max) {
                float s = Math.min((float) max / w, (float) max / h);
                out = Bitmap.createScaledBitmap(src,
                        Math.max(1, Math.round(w * s)),
                        Math.max(1, Math.round(h * s)), true);
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream(24 * 1024);
            out.compress(Bitmap.CompressFormat.JPEG, 70, bos);
            if (out != src) out.recycle();

            byte[] bytes = bos.toByteArray();
            // 太大就放弃：宁可没封面，也不能把 /state 撑爆
            if (bytes.length > 48 * 1024) {
                hub.setSource("cover", "too-big:" + bytes.length);
                return;
            }
            hub.mCover = Base64.encodeToString(bytes, Base64.NO_WRAP);
            hub.setSource("cover", bytes.length + "B");
        } catch (Throwable t) {
            hub.mCover = null;
            hub.setSource("cover", "err:" + t.getClass().getSimpleName());
        }
    }

    /**
     * 歌词。
     *
     * 优先读 **D.apk 自己已经解析好的时间线** —— 它拉歌词、解析 LRC/TTML、
     * 简繁转换全都做完了，我们同进程直接反射读那个 List 即可，
     * 既不联网也保证和车机上显示的歌词一致。
     *
     * D.apk 那边是异步的：切歌后要过一两秒 timeline 才出现，所以这里每一轮
     * 轮询都重试一次（反射读很便宜），读到就停。
     *
     * 实在读不到（比如它的歌词模块没启用）才自己去 lrclib 拉一次兜底。
     */
    private void updateLyrics(StateHub hub) {
        String title = hub.mTitle;
        String key = title + '|' + hub.mArtist;

        if (!key.equals(lastLyricKey)) {
            lastLyricKey = key;
            lyricFetchStarted = false;
            hub.mLrc = null;
        }
        if (title == null || hub.mLrc != null) return;

        // 1) 读 D.apk 的时间线
        String fromLauncher = LauncherLyrics.read();
        if (fromLauncher != null) {
            hub.mLrc = fromLauncher;
            hub.setSource("lyrics", "launcher:" + fromLauncher.length());
            return;
        }

        // 2) 兜底：自己去 lrclib 拉一次（网络请求，只能走独立线程）
        if (lyricFetchStarted) return;
        lyricFetchStarted = true;

        final String t = title;
        final String a = hub.mArtist;
        Thread th = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String lrc = Lyrics.fetch(t, a);
                    StateHub now = StateHub.get();
                    // 拉回来先确认还没切歌，免得把上一首的歌词贴到新歌上
                    if (lrc != null && t.equals(now.mTitle)) {
                        now.mLrc = lrc;
                    }
                } catch (Throwable ignored) {
                    // 没网 / 查不到就是没歌词，不影响其它
                }
            }
        }, "cardash-lyric");
        th.setDaemon(true);
        th.start();
    }

    private List<MediaController> trySessions(ComponentName cn) {
        try {
            return msm.getActiveSessions(cn);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String first(String... values) {
        for (String v : values) {
            if (v != null) return v;
        }
        return null;
    }

    private static String str(CharSequence cs) {
        if (cs == null) return null;
        String s = cs.toString().trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;
        return s;
    }
}
