# -*- coding: utf-8 -*-
"""对比「新打的 HUD 红绿灯 apk」与「9/25 平台签名版 apk」的 dex 差异。

目的（2026-09-26 用户要求）：确认新包相对老包**没丢功能、没引入回退**。
只做静态对比：类清单 / 方法清单 / 共有方法的指令条数（实现是否变化）。

用法：
  python _tools/diff_hud_apk.py <新apk> <老apk>
"""
import sys
import zipfile

try:                       # androguard 4.x 用 loguru，默认 DEBUG 刷屏
    from loguru import logger
    logger.remove()
except Exception:
    pass
import logging
logging.disable(logging.DEBUG)

from androguard.core.dex import DEX

CORE = ('AmapTrafficReceiver', 'TrafficLightBus', 'HudTrafficManager',
        'TrafficLightPanel', 'TrafficLightState', 'DirectionLight',
        'HudTrafficService', 'Prefs', 'HudSettingsPanel', 'HudPreviewView',
        'HudDisplayHelper', 'S05HudWakeHelper', 'SpeedProvider', 'AppLog',
        'MainActivity', 'FloatingEntry', 'AmapHudBootstrap', 'App')


def load(apk, dex='classes2.dex'):
    data = zipfile.ZipFile(apk).read(dex)
    d = DEX(data)
    classes = {}
    for c in d.get_classes():
        name = c.get_name()          # 形如 Lcom/s05/hudtraffic/Prefs;
        methods = {}
        for m in c.get_methods():
            try:
                ins = m.get_instructions()
                n = len(list(ins))
            except Exception:
                n = -1
            methods[m.get_name() + ' ' + m.get_descriptor()] = n
        classes[name] = methods
    return classes


def short(n):
    return n.strip('L;').replace('/', '.')


def main():
    new_apk, old_apk = sys.argv[1], sys.argv[2]
    nw, od = load(new_apk), load(old_apk)

    print('类数量   新=%d  老=%d' % (len(nw), len(od)))
    only_new = sorted(set(nw) - set(od))
    only_old = sorted(set(od) - set(nw))
    print('\n--- 仅新包有的类 (%d) ---' % len(only_new))
    for c in only_new:
        print('  + ' + short(c))
    print('--- 仅老包有的类 (%d) ---' % len(only_old))
    for c in only_old:
        print('  - ' + short(c))

    common = sorted(set(nw) & set(od))
    print('\n--- 共有类的方法差异 ---')
    total_add = total_del = 0
    for c in common:
        mn, mo = nw[c], od[c]
        add = sorted(set(mn) - set(mo))
        dele = sorted(set(mo) - set(mn))
        changed = []
        for k in sorted(set(mn) & set(mo)):
            if mn[k] != mo[k]:
                changed.append((k, mo[k], mn[k]))
        if add or dele or changed:
            print('\n[%s]' % short(c))
            for k in add:
                print('   + 方法 %s (%d 条指令)' % (k, mn[k]))
            for k in dele:
                print('   - 方法 %s (老包 %d 条指令)' % (k, mo[k]))
            for k, a, b in changed:
                print('   ~ 方法 %s 指令数 %d -> %d' % (k, a, b))
            total_add += len(add)
            total_del += len(dele)
    print('\n合计：新增方法 %d，删除方法 %d' % (total_add, total_del))

    print('\n--- 核心类体检（指令总数对比）---')
    for want in CORE:
        hits = [c for c in common if short(c).endswith('.' + want)]
        for c in hits:
            s_n = sum(nw[c].values())
            s_o = sum(od[c].values())
            flag = 'OK ' if s_n == s_o else 'DIFF'
            print('  %s %-28s 老=%6d 新=%6d (差 %+d)' % (flag, want, s_o, s_n, s_n - s_o))


if __name__ == '__main__':
    main()
