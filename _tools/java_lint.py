import os as _os
_IPA = _os.path.dirname(_os.path.dirname(_os.path.abspath(__file__)))
"""用状态机跳过注释/字符/字符串字面量，检查 Java 括号配平。"""
import os
import sys

JAVA_DIR = _IPA + r'\CarDash\inject\src\com\cardash\inject'


def check(path):
    src = open(path, encoding='utf-8').read()
    stack = []
    pairs = {'}': '{', ')': '(', ']': '['}
    line = 1
    i = 0
    n = len(src)
    errors = []

    while i < n:
        c = src[i]

        if c == '\n':
            line += 1
            i += 1
            continue

        # 行注释
        if c == '/' and i + 1 < n and src[i + 1] == '/':
            while i < n and src[i] != '\n':
                i += 1
            continue

        # 块注释
        if c == '/' and i + 1 < n and src[i + 1] == '*':
            i += 2
            while i + 1 < n and not (src[i] == '*' and src[i + 1] == '/'):
                if src[i] == '\n':
                    line += 1
                i += 1
            i += 2
            continue

        # 字符字面量
        if c == "'":
            i += 1
            while i < n and src[i] != "'":
                if src[i] == '\\':
                    i += 1
                i += 1
            i += 1
            continue

        # 字符串字面量
        if c == '"':
            i += 1
            while i < n and src[i] != '"':
                if src[i] == '\\':
                    i += 1
                if src[i] == '\n':
                    line += 1
                i += 1
            i += 1
            continue

        if c in '{([':
            stack.append((c, line))
        elif c in '})]':
            if not stack:
                errors.append('第 %d 行多余的 %s' % (line, c))
            else:
                op, ol = stack.pop()
                if op != pairs[c]:
                    errors.append('第 %d 行的 %s 与第 %d 行的 %s 不匹配'
                                  % (line, c, ol, op))
        i += 1

    for op, ol in stack:
        errors.append('第 %d 行的 %s 没有闭合' % (ol, op))
    return errors


bad = 0
for name in sorted(os.listdir(JAVA_DIR)):
    if not name.endswith('.java'):
        continue
    errs = check(os.path.join(JAVA_DIR, name))
    if errs:
        bad += 1
        print('!! ' + name)
        for e in errs:
            print('     ' + e)
    else:
        print('OK  ' + name)

print()
print('结果:', '有 %d 个文件存在问题' % bad if bad else '全部配平')
sys.exit(1 if bad else 0)
