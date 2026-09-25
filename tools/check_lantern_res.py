#!/usr/bin/env python3
"""自检 Res.java：每段内联 drawable 的 XML 语法是否合法（运行时才解析，编译查不出来）。

用法: python tools/check_lantern_res.py
"""
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

RES_JAVA = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        'lantern', 'src', 'com', 'magiclantern', 'rgb', 'Res.java')

SHOW = ('bg_seg', 'bg_card', 'grad_blue', 'ic_bt')


def main():
    src = open(RES_JAVA, encoding='utf-8').read()
    items = re.findall(r'XML\[(\w+)\] = ("(?:[^"\\]|\\.)*");', src)
    print('内联 drawable 条目数: %d' % len(items))

    bad = []
    kinds = {}
    for name, literal in items:
        try:
            text = json.loads(literal)
        except Exception as e:
            bad.append('%s: json 解析失败 %s' % (name, e))
            continue
        try:
            root = ET.fromstring(text)
            tag = root.tag.split('}')[-1]
            kinds[tag] = kinds.get(tag, 0) + 1
        except Exception as e:
            bad.append('%s: XML 语法失败 %s' % (name, e))

    print('类型分布: %s' % kinds)
    if bad:
        print('!!! 有问题:')
        for b in bad:
            print('   ' + b)
        sys.exit(1)

    print('OK  全部内联 XML 语法合法')
    for name, literal in items:
        if name in SHOW:
            print('--- %s ---' % name)
            print(json.loads(literal)[:280])


if __name__ == '__main__':
    main()
