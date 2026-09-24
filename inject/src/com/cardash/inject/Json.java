package com.cardash.inject;

/** 极简 JSON 输出工具，零第三方依赖。 */
public final class Json {

    private Json() { }

    public static String esc(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    public static String num(Double d) {
        if (d == null || d.isNaN() || d.isInfinite()) return "null";
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf(d.longValue());
        return String.valueOf(Math.round(d * 100.0) / 100.0);
    }

    /**
     * 坐标专用：6 位小数（≈0.1 米精度）。
     *
     * ⚠️ 2026-09-24 实车 bug：目的地坐标原来走 {@link #num}（2 位小数），
     * 30.759425 被舍成 30.76 —— **最大差出约 1.1 公里**，用户看到的就是
     * 「IPA 的导航终点和车机不一致，只是接近但不准」；
     * 而且 iPhone 拿这个坐标做去重 key，附近两个目的地舍完相同 ⇒
     * 切目的地也不重算路线。坐标一律走这个，别用 num。
     */
    public static String num6(Double d) {
        if (d == null || d.isNaN() || d.isInfinite()) return "null";
        return String.valueOf(Math.round(d * 1000000.0) / 1000000.0);
    }

    public static String bool(Boolean b) {
        return b == null ? "null" : (b ? "true" : "false");
    }
}
