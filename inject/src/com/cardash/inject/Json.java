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

    public static String bool(Boolean b) {
        return b == null ? "null" : (b ? "true" : "false");
    }
}
