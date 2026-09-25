package com.magiclantern.rgb;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Drawable.ConstantState;
import android.util.SparseArray;
import android.util.Xml;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.StringReader;
import org.xmlpull.v1.XmlPullParser;

/**
 * 内置版资源层：不依赖 APK 资源表（res/ + resources.arsc）。
 *
 * 本文件由 tools/make_lantern_res.py 自动生成，不要手改 ——
 * 要改外观请改 MagicLantern/res/drawable/*.xml 后重新生成。
 *
 * 使用方式与原 R.drawable 保持一致：
 *   Res.bg(view, Res.bg_card)       // 原 setBackgroundResource
 *   Res.setIcon(iv, Res.ic_arrow)   // 原 setImageResource
 *   Res.get(ctx, Res.grad_blue)     // 取 Drawable 实例（每次都是新副本）
 */
public final class Res {

    public static final int bg_card = 0;
    public static final int bg_card_inner = 1;
    public static final int bg_chip = 2;
    public static final int bg_icon_circle_dark = 3;
    public static final int bg_pill = 4;
    public static final int bg_seg = 5;
    public static final int bg_seg_normal = 6;
    public static final int bg_seg_selected = 7;
    public static final int grad_blue = 8;
    public static final int grad_cyan = 9;
    public static final int grad_green = 10;
    public static final int grad_orange = 11;
    public static final int grad_pink = 12;
    public static final int grad_purple = 13;
    public static final int ic_arrow = 14;
    public static final int ic_back = 15;
    public static final int ic_bell = 16;
    public static final int ic_bt = 17;
    public static final int ic_check = 18;
    public static final int ic_clock = 19;
    public static final int ic_gear = 20;
    public static final int ic_home = 21;
    public static final int ic_language = 22;
    public static final int ic_light = 23;
    public static final int ic_moon = 24;
    public static final int ic_palette = 25;
    public static final int ic_power = 26;
    public static final int ic_scene = 27;
    public static final int ic_speaker = 28;
    public static final int seek_thumb = 29;
    public static final int seek_track = 30;

    public static final int COUNT = 31;

    private static final String[] XML = new String[COUNT];
    private static final SparseArray<ConstantState> STATES = new SparseArray<ConstantState>();

    private Res() {
    }

    static {
        XML[bg_card] = "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" android:shape=\"rectangle\"><solid android:color=\"#FF161A25\" /><corners android:radius=\"18dp\" /><stroke android:width=\"1dp\" android:color=\"#1FFFFFFF\" /></shape>";
        XML[bg_card_inner] = "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" android:shape=\"rectangle\"><solid android:color=\"#FF1E2331\" /><corners android:radius=\"14dp\" /></shape>";
        XML[bg_chip] = "<selector xmlns:android=\"http://schemas.android.com/apk/res/android\"><item android:state_selected=\"true\"><shape android:shape=\"rectangle\"><solid android:color=\"#332F6BFF\" /><corners android:radius=\"20dp\" /><stroke android:width=\"1dp\" android:color=\"#804A6CF7\" /></shape></item><item><shape android:shape=\"rectangle\"><solid android:color=\"#FF1E2331\" /><corners android:radius=\"20dp\" /></shape></item></selector>";
        XML[bg_icon_circle_dark] = "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" android:shape=\"oval\"><solid android:color=\"#FF1B2130\" /><stroke android:width=\"1dp\" android:color=\"#26FFFFFF\" /></shape>";
        XML[bg_pill] = "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" android:shape=\"rectangle\"><solid android:color=\"#33000000\" /><corners android:radius=\"20dp\" /></shape>";
        XML[bg_seg] = "<selector xmlns:android=\"http://schemas.android.com/apk/res/android\"><item android:state_selected=\"true\"><shape xmlns:android=\"http://schemas.android.com/apk/res/android\" android:shape=\"rectangle\"><gradient android:startColor=\"#FF4A6CF7\" android:endColor=\"#FF7B5CFF\" android:angle=\"0\" /><corners android:radius=\"12dp\" /></shape></item><item><shape xmlns:android=\"http://schemas.android.com/apk/res/android\" android:shape=\"rectangle\"><solid android:color=\"#FF1E2331\" /><corners android:radius=\"12dp\" /></shape></item></selector>";
        XML[bg_seg_normal] = "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" android:shape=\"rectangle\"><solid android:color=\"#FF1E2331\" /><corners android:radius=\"12dp\" /></shape>";
        XML[bg_seg_selected] = "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" android:shape=\"rectangle\"><gradient android:startColor=\"#FF4A6CF7\" android:endColor=\"#FF7B5CFF\" android:angle=\"0\" /><corners android:radius=\"12dp\" /></shape>";
        XML[grad_blue] = "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" android:shape=\"oval\"><gradient android:startColor=\"#FF4A6CF7\" android:endColor=\"#FF7B5CFF\" android:angle=\"315\" /></shape>";
        XML[grad_cyan] = "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" android:shape=\"oval\"><gradient android:startColor=\"#FF06B6D4\" android:endColor=\"#FF3B82F6\" android:angle=\"315\" /></shape>";
        XML[grad_green] = "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" android:shape=\"oval\"><gradient android:startColor=\"#FF22C55E\" android:endColor=\"#FF14B8A6\" android:angle=\"315\" /></shape>";
        XML[grad_orange] = "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" android:shape=\"oval\"><gradient android:startColor=\"#FFFF6B35\" android:endColor=\"#FFFF2D55\" android:angle=\"315\" /></shape>";
        XML[grad_pink] = "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" android:shape=\"oval\"><gradient android:startColor=\"#FFFF2D9B\" android:endColor=\"#FFB02DFF\" android:angle=\"315\" /></shape>";
        XML[grad_purple] = "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" android:shape=\"oval\"><gradient android:startColor=\"#FFA855F7\" android:endColor=\"#FF6366F1\" android:angle=\"315\" /></shape>";
        XML[ic_arrow] = "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\" android:width=\"24dp\" android:height=\"24dp\" android:viewportWidth=\"24\" android:viewportHeight=\"24\"><path android:pathData=\"M9.5,5.5 L16,12 L9.5,18.5\" android:fillColor=\"#00000000\" android:strokeColor=\"#FFFFFFFF\" android:strokeWidth=\"1.8\" android:strokeLineCap=\"round\" android:strokeLineJoin=\"round\" /></vector>";
        XML[ic_back] = "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\" android:width=\"24dp\" android:height=\"24dp\" android:viewportWidth=\"24\" android:viewportHeight=\"24\"><path android:pathData=\"M14.5,5.5 L8,12 L14.5,18.5\" android:fillColor=\"#00000000\" android:strokeColor=\"#FFFFFFFF\" android:strokeWidth=\"1.8\" android:strokeLineCap=\"round\" android:strokeLineJoin=\"round\" /></vector>";
        XML[ic_bell] = "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\" android:width=\"24dp\" android:height=\"24dp\" android:viewportWidth=\"24\" android:viewportHeight=\"24\"><path android:pathData=\"M6.5,17.2 L17.5,17.2 L16.2,14.6 L16.2,10.2 A4.2,4.2 0 0 0 7.8,10.2 L7.8,14.6 Z\" android:fillColor=\"#00000000\" android:strokeColor=\"#FFFFFFFF\" android:strokeWidth=\"1.8\" android:strokeLineJoin=\"round\" /><path android:pathData=\"M10,19.6 L14,19.6\" android:fillColor=\"#00000000\" android:strokeColor=\"#FFFFFFFF\" android:strokeWidth=\"1.8\" android:strokeLineCap=\"round\" /></vector>";
        XML[ic_bt] = "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\" android:width=\"24dp\" android:height=\"24dp\" android:viewportWidth=\"24\" android:viewportHeight=\"24\"><path android:pathData=\"M8,6.8 L16,17.2 L12,21.2 L12,2.8 L16,6.8 L8,17.2\" android:fillColor=\"#00000000\" android:strokeColor=\"#FFFFFFFF\" android:strokeWidth=\"1.8\" android:strokeLineCap=\"round\" android:strokeLineJoin=\"round\" /></vector>";
        XML[ic_check] = "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\" android:width=\"24dp\" android:height=\"24dp\" android:viewportWidth=\"24\" android:viewportHeight=\"24\"><path android:pathData=\"M5,12.5 L10,17.5 L19,7\" android:fillColor=\"#00000000\" android:strokeColor=\"#FFFFFFFF\" android:strokeWidth=\"2\" android:strokeLineCap=\"round\" android:strokeLineJoin=\"round\" /></vector>";
        XML[ic_clock] = "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\" android:width=\"24dp\" android:height=\"24dp\" android:viewportWidth=\"24\" android:viewportHeight=\"24\"><path android:pathData=\"M12,3.2 A8.8,8.8 0 1 0 12,20.8 A8.8,8.8 0 1 0 12,3.2\" android:fillColor=\"#00000000\" android:strokeColor=\"#FFFFFFFF\" android:strokeWidth=\"1.8\" /><path android:pathData=\"M12,7.4 L12,12.3 L15.9,14.4\" android:fillColor=\"#00000000\" android:strokeColor=\"#FFFFFFFF\" android:strokeWidth=\"1.8\" android:strokeLineCap=\"round\" /></vector>";
        XML[ic_gear] = "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\" android:width=\"24dp\" android:height=\"24dp\" android:viewportWidth=\"24\" android:viewportHeight=\"24\"><path android:pathData=\"M12,8.6 A3.4,3.4 0 1 0 12,15.4 A3.4,3.4 0 1 0 12,8.6\" android:fillColor=\"#00000000\" android:strokeColor=\"#FFFFFFFF\" android:strokeWidth=\"1.8\" /><path android:pathData=\"M12,2.4 L12,5 M12,19 L12,21.6 M2.4,12 L5,12 M19,12 L21.6,12 M5.2,5.2 L7,7 M17,17 L18.8,18.8 M18.8,5.2 L17,7 M7,17 L5.2,18.8\" android:fillColor=\"#00000000\" android:strokeColor=\"#FFFFFFFF\" android:strokeWidth=\"1.8\" android:strokeLineCap=\"round\" /></vector>";
        XML[ic_home] = "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\" android:width=\"24dp\" android:height=\"24dp\" android:viewportWidth=\"24\" android:viewportHeight=\"24\"><path android:pathData=\"M3.5,10.8 L12,3.5 L20.5,10.8 M6,9.2 L6,20 L18,20 L18,9.2\" android:fillColor=\"#00000000\" android:strokeColor=\"#FFFFFFFF\" android:strokeWidth=\"1.8\" android:strokeLineCap=\"round\" android:strokeLineJoin=\"round\" /></vector>";
        XML[ic_language] = "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\" android:width=\"24dp\" android:height=\"24dp\" android:viewportWidth=\"24\" android:viewportHeight=\"24\"><path android:pathData=\"M12,3.2 A8.8,8.8 0 1 0 12,20.8 A8.8,8.8 0 1 0 12,3.2\" android:fillColor=\"#00000000\" android:strokeColor=\"#FFFFFFFF\" android:strokeWidth=\"1.8\" /><path android:pathData=\"M3.2,12 L20.8,12\" android:fillColor=\"#00000000\" android:strokeColor=\"#FFFFFFFF\" android:strokeWidth=\"1.8\" /><path android:pathData=\"M12,3.2 A5.6,8.8 0 0 1 12,20.8 A5.6,8.8 0 0 0 12,3.2\" android:fillColor=\"#00000000\" android:strokeColor=\"#FFFFFFFF\" android:strokeWidth=\"1.8\" /></vector>";
        XML[ic_light] = "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\" android:width=\"24dp\" android:height=\"24dp\" android:viewportWidth=\"24\" android:viewportHeight=\"24\"><path android:pathData=\"M12,3.4 A6.2,6.2 0 0 1 15.4,14.6 L8.6,14.6 A6.2,6.2 0 0 1 12,3.4 Z\" android:fillColor=\"#00000000\" android:strokeColor=\"#FFFFFFFF\" android:strokeWidth=\"1.8\" android:strokeLineCap=\"round\" android:strokeLineJoin=\"round\" /><path android:pathData=\"M9.4,17.2 L14.6,17.2 M10.4,19.8 L13.6,19.8\" android:fillColor=\"#00000000\" android:strokeColor=\"#FFFFFFFF\" android:strokeWidth=\"1.8\" android:strokeLineCap=\"round\" /></vector>";
        XML[ic_moon] = "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\" android:width=\"24dp\" android:height=\"24dp\" android:viewportWidth=\"24\" android:viewportHeight=\"24\"><path android:pathData=\"M20.5,14.6 A8.8,8.8 0 1 1 9.4,3.5 A7.2,7.2 0 0 0 20.5,14.6 Z\" android:fillColor=\"#00000000\" android:strokeColor=\"#FFFFFFFF\" android:strokeWidth=\"1.8\" android:strokeLineJoin=\"round\" /></vector>";
        XML[ic_palette] = "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\" android:width=\"24dp\" android:height=\"24dp\" android:viewportWidth=\"24\" android:viewportHeight=\"24\"><path android:pathData=\"M12,3.4 A8.6,8.6 0 1 0 12,20.6 A8.6,8.6 0 1 0 12,3.4\" android:fillColor=\"#00000000\" android:strokeColor=\"#FFFFFFFF\" android:strokeWidth=\"1.8\" /><path android:pathData=\"M7.9,9.8 A1.35,1.35 0 1 0 7.9,12.5 A1.35,1.35 0 1 0 7.9,9.8\" android:fillColor=\"#FFFFFFFF\" /><path android:pathData=\"M12,6.6 A1.35,1.35 0 1 0 12,9.3 A1.35,1.35 0 1 0 12,6.6\" android:fillColor=\"#FFFFFFFF\" /><path android:pathData=\"M16.1,9.8 A1.35,1.35 0 1 0 16.1,12.5 A1.35,1.35 0 1 0 16.1,9.8\" android:fillColor=\"#FFFFFFFF\" /></vector>";
        XML[ic_power] = "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\" android:width=\"24dp\" android:height=\"24dp\" android:viewportWidth=\"24\" android:viewportHeight=\"24\"><path android:pathData=\"M12,3.2 L12,11.4\" android:fillColor=\"#00000000\" android:strokeColor=\"#FFFFFFFF\" android:strokeWidth=\"2\" android:strokeLineCap=\"round\" /><path android:pathData=\"M7.4,6.7 A7.1,7.1 0 1 0 16.6,6.7\" android:fillColor=\"#00000000\" android:strokeColor=\"#FFFFFFFF\" android:strokeWidth=\"2\" android:strokeLineCap=\"round\" /></vector>";
        XML[ic_scene] = "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\" android:width=\"24dp\" android:height=\"24dp\" android:viewportWidth=\"24\" android:viewportHeight=\"24\"><path android:pathData=\"M12,3 L13.9,10.1 L21,12 L13.9,13.9 L12,21 L10.1,13.9 L3,12 L10.1,10.1 Z\" android:fillColor=\"#FFFFFFFF\" /></vector>";
        XML[ic_speaker] = "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\" android:width=\"24dp\" android:height=\"24dp\" android:viewportWidth=\"24\" android:viewportHeight=\"24\"><path android:pathData=\"M7.5,3.5 L16.5,3.5 A2,2 0 0 1 18.5,5.5 L18.5,18.5 A2,2 0 0 1 16.5,20.5 L7.5,20.5 A2,2 0 0 1 5.5,18.5 L5.5,5.5 A2,2 0 0 1 7.5,3.5 Z\" android:fillColor=\"#00000000\" android:strokeColor=\"#FFFFFFFF\" android:strokeWidth=\"1.8\" android:strokeLineJoin=\"round\" /><path android:pathData=\"M12,9.6 A3,3 0 1 0 12,15.6 A3,3 0 1 0 12,9.6\" android:fillColor=\"#00000000\" android:strokeColor=\"#FFFFFFFF\" android:strokeWidth=\"1.8\" /></vector>";
        XML[seek_thumb] = "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" android:shape=\"oval\"><solid android:color=\"#FFFFFFFF\" /><stroke android:width=\"3dp\" android:color=\"#334A6CF7\" /><size android:width=\"18dp\" android:height=\"18dp\" /></shape>";
        XML[seek_track] = "<layer-list xmlns:android=\"http://schemas.android.com/apk/res/android\"><item android:id=\"@android:id/background\"><shape android:shape=\"rectangle\"><corners android:radius=\"3dp\" /><solid android:color=\"#FF2A3040\" /><size android:height=\"6dp\" /></shape></item><item android:id=\"@android:id/progress\"><clip><shape android:shape=\"rectangle\"><corners android:radius=\"3dp\" /><gradient android:startColor=\"#FF4A6CF7\" android:endColor=\"#FF7B5CFF\" android:angle=\"0\" /><size android:height=\"6dp\" /></shape></clip></item></layer-list>";
    }

    /** 取一份可安全修改（tint/alpha）的 Drawable 副本；失败返回 null。 */
    public static Drawable get(Context c, int key) {
        if (c == null || key < 0 || key >= COUNT) return null;
        synchronized (Res.class) {
            ConstantState st = STATES.get(key);
            if (st != null) return st.newDrawable(c.getResources());
            try {
                XmlPullParser p = Xml.newPullParser();
                p.setInput(new StringReader(XML[key]));
                int ev;
                do {
                    ev = p.next();
                } while (ev != XmlPullParser.START_TAG && ev != XmlPullParser.END_DOCUMENT);
                if (ev != XmlPullParser.START_TAG) return null;
                Drawable d = Drawable.createFromXml(c.getResources(), p);
                if (d == null) return null;
                ConstantState cs = d.getConstantState();
                if (cs != null) {
                    STATES.put(key, cs);
                    return cs.newDrawable(c.getResources());
                }
                return d;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /** 原 setBackgroundResource 的替代。 */
    public static void bg(View v, int key) {
        if (v == null) return;
        Drawable d = get(v.getContext(), key);
        if (d != null) v.setBackground(d);
    }

    /** 原 setImageResource 的替代（ImageView）。 */
    public static void setIcon(ImageView iv, int key) {
        if (iv == null) return;
        Drawable d = get(iv.getContext(), key);
        if (d != null) iv.setImageDrawable(d);
    }

    /** 文本左侧图标（原 setCompoundDrawablesWithIntrinsicBounds(res, 0, 0, 0)）。 */
    public static void compoundStart(TextView tv, int key, int sizeDp) {
        if (tv == null) return;
        Drawable d = get(tv.getContext(), key);
        if (d == null) return;
        int px = (int) (sizeDp * tv.getContext().getResources().getDisplayMetrics().density * Ui.scale(tv.getContext()) + 0.5f);
        d.setBounds(0, 0, px, px);
        tv.setCompoundDrawables(d, null, null, null);
        tv.setCompoundDrawablePadding(Ui.dp(tv.getContext(), 8));
    }
}
