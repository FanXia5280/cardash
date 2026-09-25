#!/usr/bin/env python3
"""氛围灯「面板化」重构（第二批，对 CarDash/lantern/ 下的副本执行）。

做四件事：
  1. BleController 的单一 listener 改成多监听 —— 界面和保活要同时听；
  2. Page 子类的构造参数 MainActivity -> LanternPanel（字段名 host）；
  3. 宿主方法调用（switchTab/openSub/... 8 个）由 activity. 改到 host.；
  4. GradientDialog 的宿主参数降级成 Context（它本来就只当 Context 用）。
"""

import os
import re
import sys

DIR = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    'lantern', 'src', 'com', 'magiclantern', 'rgb')

HOST_METHODS = ('switchTab', 'openSub', 'refreshPages', 'getConnectionSummary',
                'showRgbDialog', 'disconnect', 'connect', 'toggleScan')

STAT = {}


def bump(k, n=1):
    STAT[k] = STAT.get(k, 0) + n


def port(name, text):
    if name == 'BleController.java':
        text, n = re.subn(r'    private Listener listener;',
                          '    /** 可以同时挂多个监听者：界面面板 + 保活（LanternBootstrap） */\n'
                          '    private final java.util.concurrent.CopyOnWriteArrayList<Listener> listeners =\n'
                          '            new java.util.concurrent.CopyOnWriteArrayList<Listener>();',
                          text)
        bump('BleController.listeners', n)

        text, n = re.subn(r'    public void setListener\(Listener l\) \{\n'
                          r'        listener = l;\n'
                          r'    \}',
                          '    /** 替换全部监听者（旧用法）。 */\n'
                          '    public void setListener(Listener l) {\n'
                          '        listeners.clear();\n'
                          '        if (l != null) listeners.add(l);\n'
                          '    }\n'
                          '\n'
                          '    /** 追加一个监听者（界面与保活互不覆盖）。 */\n'
                          '    public void addListener(Listener l) {\n'
                          '        if (l != null && !listeners.contains(l)) listeners.add(l);\n'
                          '    }\n'
                          '\n'
                          '    public void removeListener(Listener l) {\n'
                          '        if (l != null) listeners.remove(l);\n'
                          '    }',
                          text)
        bump('BleController.setListener', n)

        for m in ('Devices', 'State', 'Message'):
            text, n = re.subn(r'if \(listener != null\) listener\.on%s\(([^)]*)\);' % m,
                              'for (Listener l : listeners) {\n'
                              '            try {\n'
                              '                l.on%s(\\1);\n'
                              '            } catch (Throwable t) {\n'
                              '                // 单个监听者出错不能影响蓝牙主流程\n'
                              '            }\n'
                              '        }' % m,
                              text)
            bump('BleController.notify' + m, n)

    if name not in ('Page.java', 'MainActivity.java', 'Res.java', 'BleController.java'):
        # 2) 构造参数
        text, n = re.subn(r'\(MainActivity activity\)', '(LanternPanel host)', text)
        bump('ctor', n)
        text, n = re.subn(r'\bsuper\(activity\);', 'super(host);', text)
        bump('super', n)

        # 3) 宿主方法调用
        text, n = re.subn(r'\bactivity\.(%s)\(' % '|'.join(HOST_METHODS), r'host.\1(', text)
        bump('hostcall', n)

        # 5) LightPage 里的内部接口引用
        text, n = re.subn(r'MainActivity\.OnColorPicked', 'LanternPanel.OnColorPicked', text)
        bump('OnColorPicked', n)

    if name == 'GradientDialog.java':
        text, n = re.subn(r'public static void show\(final MainActivity activity,',
                          'public static void show(final Context activity,', text)
        bump('GradientDialog', n)

    return text


def main():
    for name in sorted(os.listdir(DIR)):
        if not name.endswith('.java') or name == 'Res.java':
            continue
        path = os.path.join(DIR, name)
        with open(path, encoding='utf-8') as f:
            src = f.read()
        out = port(name, src)
        if out != src:
            with open(path, 'w', encoding='utf-8', newline='\n') as f:
                f.write(out)
            print('  重构 %s' % name)
    print('OK  %s' % STAT)

    left = []
    for name in sorted(os.listdir(DIR)):
        if not name.endswith('.java'):
            continue
        with open(os.path.join(DIR, name), encoding='utf-8') as f:
            for i, line in enumerate(f, 1):
                if re.search(r'\bMainActivity\b', line) and name != 'MainActivity.java':
                    left.append('%s:%d: %s' % (name, i, line.strip()))
    print('\n仍引用 MainActivity 的地方（应为空）:')
    for x in left:
        print('  ' + x)


if __name__ == '__main__':
    main()
