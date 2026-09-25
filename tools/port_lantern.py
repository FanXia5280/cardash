#!/usr/bin/env python3
"""氛围灯源码"去资源表"改写（对 CarDash/lantern/ 下的副本执行）。

把
    R.drawable.xxx                  -> Res.xxx
    v.setBackgroundResource(x)      -> Res.bg(v, x)
    iv.setImageResource(x)          -> Res.setIcon(iv, x)
    tv.setCompoundDrawables...      -> Res.compoundStart(tv, x, 18)
不含 R.layout / R.id（那部分在手工重构 MainActivity -> LanternPanel 时处理）。

可重复执行（幂等：已经是 Res. 的不会再被匹配）。
"""

import os
import re
import sys

DIR = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    'lantern', 'src', 'com', 'magiclantern', 'rgb')

COUNT = {}


def port(text):
    def bump(k):
        COUNT[k] = COUNT.get(k, 0) + 1

    def sub(pattern, repl, s, key):
        s2, n = re.subn(pattern, repl, s)
        if n:
            COUNT[key] = COUNT.get(key, 0) + n
        return s2

    # 1) R.drawable.X -> Res.X
    text = sub(r'R\.drawable\.(\w+)', r'Res.\1', text, 'drawable')

    # 2) v.setBackgroundResource(x);  ->  Res.bg(v, x);
    text = sub(r'([A-Za-z_]\w*)\.setBackgroundResource\(([^;]*?)\);',
               r'Res.bg(\1, \2);', text, 'setBackgroundResource')

    # 3) iv.setImageResource(x);  ->  Res.setIcon(iv, x);
    text = sub(r'([A-Za-z_]\w*)\.setImageResource\(([^;]*?)\);',
               r'Res.setIcon(\1, \2);', text, 'setImageResource')

    # 4) tv.setCompoundDrawablesWithIntrinsicBounds(x, 0, 0, 0);  ->  Res.compoundStart(tv, x, 18);
    text = sub(r'([A-Za-z_]\w*)\.setCompoundDrawablesWithIntrinsicBounds\('
               r'([^,;]+),\s*0,\s*0,\s*0\);',
               r'Res.compoundStart(\1, \2, 18);', text, 'setCompoundDrawables')

    return text


def main():
    total = 0
    for name in sorted(os.listdir(DIR)):
        if not name.endswith('.java') or name == 'Res.java':
            continue
        path = os.path.join(DIR, name)
        with open(path, encoding='utf-8') as f:
            src = f.read()
        out = port(src)
        if out != src:
            with open(path, 'w', encoding='utf-8', newline='\n') as f:
                f.write(out)
            total += 1
            print('  改写 %s' % name)
    print('OK  %d 个文件；替换统计: %s' % (total, COUNT))

    # 报告剩余的资源引用（R.layout / R.id 需要手工处理）
    left = {}
    for name in sorted(os.listdir(DIR)):
        if not name.endswith('.java'):
            continue
        with open(os.path.join(DIR, name), encoding='utf-8') as f:
            for m in re.finditer(r'R\.(layout|id)\.(\w+)', f.read()):
                left.setdefault(name, []).append(m.group(0))
    print('\n剩下待手工处理（R.layout / R.id）:')
    for name, items in left.items():
        print('  %-22s %s' % (name, ', '.join(sorted(set(items)))))


if __name__ == '__main__':
    main()
