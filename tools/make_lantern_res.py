#!/usr/bin/env python3
"""把氛围灯的 drawable 资源"去资源表化"，生成零依赖的 Res.java。

为什么需要：
  内置进车机桌面（com.deepalhome.launcher）时，我们的构建流程只做两件事 ——
  改写 AndroidManifest.xml（AXML）+ 追加一个 classesN.dex。**不能改
  resources.arsc / res/**，所以氛围灯原来那 31 个 drawable 不能走资源表。

做法：
  读原始 res/drawable/*.xml，把 @color / @drawable 引用全部内联（selector
  里引用的 drawable 展开成内嵌节点），压掉多余空白，作为 Java 字符串数组
  生成；运行时用系统公开 API Drawable.createFromXml 解析，仍按原样渲染
  （shape / gradient / selector / vector 全部支持）。
"""

import json
import os
import re
import sys

def default_src_res():
    """Res.java 的生成源，按"能不能跟着文件夹走"排序：
    ① <IPA>\\_lantern_src\\MagicLantern\\res  ← 已归档进项目（换电脑也能重跑）
    ② D:\\Desktop\\huancai\\MagicLantern\\res ← 原始工程（本机有就用它）
    """
    ipa = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))   # <IPA>\\CarDash -> <IPA>
    ipa = os.path.dirname(ipa)
    for cand in (os.path.join(ipa, '_lantern_src', 'MagicLantern', 'res'),
                 r'D:\Desktop\huancai\MagicLantern\res'):
        if os.path.isdir(cand):
            return cand
    return os.path.join(ipa, '_lantern_src', 'MagicLantern', 'res')


SRC_RES = sys.argv[1] if len(sys.argv) > 1 else default_src_res()
OUT_JAVA = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    'lantern', 'src', 'com', 'magiclantern', 'rgb', 'Res.java')


def read(path):
    with open(path, encoding='utf-8-sig', errors='replace') as f:
        return f.read()


def load_colors(res_dir):
    """values/colors.xml -> {'bgRoot': '#FF0A0D14', ...}"""
    out = {}
    path = os.path.join(res_dir, 'values', 'colors.xml')
    if not os.path.isfile(path):
        return out
    for m in re.finditer(r'<color\s+name="([^"]+)"\s*>\s*([^<\s]+)\s*</color>', read(path)):
        out[m.group(1)] = m.group(2)
    return out


def squeeze(xml):
    """压掉标签之间的空白（属性值内部不动），减小体积。"""
    xml = re.sub(r'<\?xml[^>]*\?>', '', xml)
    xml = re.sub(r'<!--.*?-->', '', xml, flags=re.S)
    xml = re.sub(r'>\s+<', '><', xml)
    xml = re.sub(r'\s+', ' ', xml)
    return xml.strip()


def main():
    colors = load_colors(SRC_RES)
    ddir = os.path.join(SRC_RES, 'drawable')
    names = sorted(f[:-4] for f in os.listdir(ddir) if f.endswith('.xml'))

    raw = {}
    for n in names:
        raw[n] = read(os.path.join(ddir, n + '.xml'))

    def render(name, stack=()):
        """把单个 drawable 渲染成可独立解析的 XML（引用全部内联）。"""
        if name in stack:
            raise SystemExit('drawable 循环引用: %s' % ' -> '.join(stack + (name,)))
        xml = raw[name]

        # 1) selector 里 <item android:drawable="@drawable/X"/> -> <item ...><X 的内容></item>
        def item_repl(m):
            attrs, ref = m.group(1), m.group(2)
            attrs = re.sub(r'\s*android:drawable="@drawable/[^"]+"', '', attrs).strip()
            inner = render(ref, stack + (name,))
            inner = re.sub(r'^<(\w+)[^>]*>', lambda mm: '<%s>' % mm.group(1), inner)
            return '<item%s>%s</item>' % ((' ' + attrs) if attrs else '', inner)

        xml = re.sub(r'<item([^>]*?)android:drawable="@drawable/(\w+)"\s*/>', item_repl, xml)

        # 2) @color/xxx -> 字面量
        def color_repl(m):
            key = m.group(1)
            if key not in colors:
                raise SystemExit('找不到颜色 @color/%s（在 %s 里）' % (key, name))
            return colors[key]

        xml = re.sub(r'@color/(\w+)', color_repl, xml)

        left = re.findall(r'@(\w+)/', xml)
        bad = [x for x in left if x not in ('android',)]
        if bad:
            raise SystemExit('%s 里还有非内联资源引用: %s' % (name, set(bad)))
        return xml

    rendered = {n: squeeze(render(n)) for n in names}

    lines = []
    w = lines.append
    w('package com.magiclantern.rgb;')
    w('')
    w('import android.content.Context;')
    w('import android.graphics.drawable.Drawable;')
    w('import android.graphics.drawable.Drawable.ConstantState;')
    w('import android.util.SparseArray;')
    w('import android.util.Xml;')
    w('import android.view.View;')
    w('import android.widget.ImageView;')
    w('import android.widget.TextView;')
    w('import java.io.StringReader;')
    w('import org.xmlpull.v1.XmlPullParser;')
    w('')
    w('/**')
    w(' * 内置版资源层：不依赖 APK 资源表（res/ + resources.arsc）。')
    w(' *')
    w(' * 本文件由 tools/make_lantern_res.py 自动生成，不要手改 ——')
    w(' * 要改外观请改 MagicLantern/res/drawable/*.xml 后重新生成。')
    w(' *')
    w(' * 使用方式与原 R.drawable 保持一致：')
    w(' *   Res.bg(view, Res.bg_card)       // 原 setBackgroundResource')
    w(' *   Res.setIcon(iv, Res.ic_arrow)   // 原 setImageResource')
    w(' *   Res.get(ctx, Res.grad_blue)     // 取 Drawable 实例（每次都是新副本）')
    w(' */')
    w('public final class Res {')
    w('')
    for i, n in enumerate(names):
        w('    public static final int %s = %d;' % (n, i))
    w('')
    w('    public static final int COUNT = %d;' % len(names))
    w('')
    w('    private static final String[] XML = new String[COUNT];')
    w('    private static final SparseArray<ConstantState> STATES = new SparseArray<ConstantState>();')
    w('')
    w('    private Res() {')
    w('    }')
    w('')
    w('    static {')
    for n in names:
        w('        XML[%s] = %s;' % (n, json.dumps(rendered[n])))
    w('    }')
    w('')
    w('    /** 取一份可安全修改（tint/alpha）的 Drawable 副本；失败返回 null。 */')
    w('    public static Drawable get(Context c, int key) {')
    w('        if (c == null || key < 0 || key >= COUNT) return null;')
    w('        synchronized (Res.class) {')
    w('            ConstantState st = STATES.get(key);')
    w('            if (st != null) return st.newDrawable(c.getResources());')
    w('            try {')
    w('                XmlPullParser p = Xml.newPullParser();')
    w('                p.setInput(new StringReader(XML[key]));')
    w('                int ev;')
    w('                do {')
    w('                    ev = p.next();')
    w('                } while (ev != XmlPullParser.START_TAG && ev != XmlPullParser.END_DOCUMENT);')
    w('                if (ev != XmlPullParser.START_TAG) return null;')
    w('                Drawable d = Drawable.createFromXml(c.getResources(), p);')
    w('                if (d == null) return null;')
    w('                ConstantState cs = d.getConstantState();')
    w('                if (cs != null) {')
    w('                    STATES.put(key, cs);')
    w('                    return cs.newDrawable(c.getResources());')
    w('                }')
    w('                return d;')
    w('            } catch (Throwable t) {')
    w('                return null;')
    w('            }')
    w('        }')
    w('    }')
    w('')
    w('    /** 原 setBackgroundResource 的替代。 */')
    w('    public static void bg(View v, int key) {')
    w('        if (v == null) return;')
    w('        Drawable d = get(v.getContext(), key);')
    w('        if (d != null) v.setBackground(d);')
    w('    }')
    w('')
    w('    /** 原 setImageResource 的替代（ImageView）。 */')
    w('    public static void setIcon(ImageView iv, int key) {')
    w('        if (iv == null) return;')
    w('        Drawable d = get(iv.getContext(), key);')
    w('        if (d != null) iv.setImageDrawable(d);')
    w('    }')
    w('')
    w('    /** 文本左侧图标（原 setCompoundDrawablesWithIntrinsicBounds(res, 0, 0, 0)）。 */')
    w('    public static void compoundStart(TextView tv, int key, int sizeDp) {')
    w('        if (tv == null) return;')
    w('        Drawable d = get(tv.getContext(), key);')
    w('        if (d == null) return;')
    w('        int px = (int) (sizeDp * tv.getContext().getResources().getDisplayMetrics().density'
          ' * Ui.scale(tv.getContext()) + 0.5f);')
    w('        d.setBounds(0, 0, px, px);')
    w('        tv.setCompoundDrawables(d, null, null, null);')
    w('        tv.setCompoundDrawablePadding(Ui.dp(tv.getContext(), 8));')
    w('    }')
    w('}')
    w('')

    os.makedirs(os.path.dirname(OUT_JAVA), exist_ok=True)
    with open(OUT_JAVA, 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(lines))

    total = sum(len(x) for x in rendered.values())
    print('OK  %d 个 drawable -> %s' % (len(names), OUT_JAVA))
    print('    XML 共 %d 字节，Java 源 %d 字节' % (total, os.path.getsize(OUT_JAVA)))
    print('    %s' % ', '.join(names))


if __name__ == '__main__':
    main()
