package com.cardash.inject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 歌词：按「歌名 + 歌手」去 lrclib.net 拉**同步歌词**（LRC 格式）。
 *
 * 车机是挂在手机热点上的，所以有外网。lrclib 免费、不用 key。
 *
 * 拿到之后压成紧凑格式：
 *     12.34|第一句歌词
 *     15.70|第二句歌词
 * 只在切歌时下发一次，iPhone 按播放位置自己切行 —— 比每 200ms 推一次
 * 「当前歌词」省得多，而且能跟到几十毫秒的精度。
 *
 * 车机和 iPhone 都可能没网，所以整条链路失败只是「没有歌词」，
 * 不影响音乐卡片本身的显示。
 */
public final class Lyrics {

    private static final Pattern TAG =
            Pattern.compile("\\[(\\d{1,2}):(\\d{1,2}(?:\\.\\d+)?)\\]");

    /** 同一首歌只查一次 */
    private static String lastKey;
    private static String lastResult;

    private Lyrics() { }

    /**
     * 查歌词。
     *
     * @param title  歌名
     * @param artist 歌手
     * @return 紧凑 LRC；查不到返回 null
     */
    public static synchronized String fetch(String title, String artist) {
        if (title == null || title.trim().isEmpty()) return null;
        String key = title + '|' + (artist == null ? "" : artist);
        if (key.equals(lastKey)) return lastResult;

        String result = null;
        try {
            result = parse(get(url(
                    "https://lrclib.net/api/get?track_name=%s&artist_name=%s",
                    title, artist == null ? "" : artist)));
        } catch (Throwable ignored) {
            // 精确查失败就走搜索
        }
        if (result == null) {
            try {
                result = search(title, artist);
            } catch (Throwable ignored) {
                // 查不到就算了
            }
        }

        lastKey = key;
        lastResult = result;
        StateHub.get().setSource("lyrics",
                result == null ? "none" : (result.length() + " 字符"));
        return result;
    }

    /** /search 返回数组，挑第一个带同步歌词的 */
    private static String search(String title, String artist) throws Exception {
        String q = title + (artist == null || artist.isEmpty() ? "" : (" " + artist));
        String body = get(url("https://lrclib.net/api/search?q=%s", q));
        if (body == null) return null;

        int idx = 0;
        while (true) {
            int s = body.indexOf("\"syncedLyrics\":", idx);
            if (s < 0) return null;
            int p = body.indexOf('"', s + 15);
            if (p < 0) return null;
            StringBuilder sb = new StringBuilder();
            for (int i = p + 1; i < body.length(); i++) {
                char c = body.charAt(i);
                if (c == '\\') {
                    i++;
                    if (i >= body.length()) break;
                    char e = body.charAt(i);
                    if (e == 'n') sb.append('\n');
                    else if (e == 'r') sb.append('\r');
                    else if (e == 't') sb.append('\t');
                    else sb.append(e);
                    continue;
                }
                if (c == '"') break;
                sb.append(c);
            }
            String parsed = parse(sb.toString());
            if (parsed != null) return parsed;
            idx = p + 1;
        }
    }

    private static String url(String fmt, String title, String artist) throws Exception {
        return String.format(fmt,
                URLEncoder.encode(title, "UTF-8"),
                URLEncoder.encode(artist, "UTF-8"));
    }

    private static String url(String fmt, String q) throws Exception {
        return String.format(fmt, URLEncoder.encode(q, "UTF-8"));
    }

    private static String get(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "CarDash/1.0 (car dashboard bridge)");
        try {
            int code = c.getResponseCode();
            if (code != 200) return null;
            InputStream in = c.getInputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder(16 * 1024);
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
                if (sb.length() > 256 * 1024) break;   // 防爆
            }
            r.close();
            return sb.toString();
        } finally {
            try { c.disconnect(); } catch (Throwable ignored) { }
        }
    }

    /** 从 /api/get 的 JSON 里抠出 syncedLyrics，再转成紧凑格式 */
    private static String parse(String json) {
        if (json == null) return null;
        int s = json.indexOf("\"syncedLyrics\":");
        if (s < 0) return null;
        int p = json.indexOf('"', s + 15);
        if (p < 0) return null;

        StringBuilder raw = new StringBuilder();
        for (int i = p + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\') {
                i++;
                if (i >= json.length()) break;
                char e = json.charAt(i);
                if (e == 'n') raw.append('\n');
                else if (e == 'r') raw.append('\r');
                else if (e == 't') raw.append('\t');
                else raw.append(e);
                continue;
            }
            if (c == '"') break;
            raw.append(c);
        }
        return compact(raw.toString());
    }

    /** LRC 原文 -> 「秒|歌词」，丢掉空行和时间戳没解析出来的行 */
    private static String compact(String lrc) {
        if (lrc == null || lrc.isEmpty()) return null;
        StringBuilder out = new StringBuilder(lrc.length());
        int count = 0;

        for (String line : lrc.split("\n")) {
            if (line == null) continue;
            Matcher m = TAG.matcher(line);
            if (!m.find()) continue;
            String text = line.substring(m.end()).trim();
            if (text.isEmpty()) continue;               // 纯时间戳行（间奏）跳过
            if (text.startsWith("作词") || text.startsWith("作曲")
                    || text.startsWith("编曲") || text.startsWith("混音")) {
                continue;
            }
            int min = Integer.parseInt(m.group(1));
            double sec = min * 60.0 + Double.parseDouble(m.group(2));
            if (count++ > 0) out.append('\n');
            out.append(Math.round(sec * 100.0) / 100.0).append('|').append(text);
            if (out.length() > 32 * 1024) break;        // 超长截断
        }
        return count == 0 ? null : out.toString();
    }
}
