import os as _os
_IPA = _os.path.dirname(_os.path.dirname(_os.path.abspath(__file__)))
"""粗校验 Swift 文件（Windows 上没有 Swift 编译器，push 之前先机械过一遍）：

1. 花括号 / 圆括号配平
2. **形参遮蔽属性**：形参名和同类型里的存储属性同名 —— 危险写法，提示一下
3. **非可选形参被 if/guard/while let 绑定** —— 这是**编译错误**，必报

第 3 条是 2026-09-23 CI 挂掉的原因，一定要留住：
    类里有   private weak var view: AMapNaviDriveView?
    方法写成 func update(view: AMapNaviDriveView, ...)   ← 形参把属性盖住了
    函数体里 if let v = view { ... }                       ← view 是非可选，编不过：
    error: initializer for conditional binding must have Optional type
（本地没法编 Swift，这种错只能靠静态检查在 push 前拦住，别白烧一轮 CI。）
"""
import os
import re
import sys

# 中文 Windows 控制台默认 GBK，输出里出现 GBK 编不了的符号（如 `⇒`）会直接
# UnicodeEncodeError 崩掉 —— 而崩的位置正好在第 4 项（ObjC→Swift 命名）检查的标题上，
# 等于那项检查静默失效。这里只放宽错误处理，不改编码，保证检查永远跑完。
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(errors='replace')
    except Exception:
        pass

ROOT = sys.argv[1] if len(sys.argv) > 1 else _IPA + r'\CarDash\ios\CarDash'

MODIFIERS = {'private', 'fileprivate', 'internal', 'public', 'open', 'final',
             'static', 'class', 'weak', 'unowned', 'lazy', 'override', 'mutating',
             'nonmutating', 'dynamic', 'indirect', 'required', 'convenience',
             'prefix', 'postfix', 'inout', 'isolated', 'nonisolated', 'unsafe',
             'unowned', 'some', 'any'}


def strip_noise(text):
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        # 行注释
        if text.startswith('//', i):
            j = text.find('\n', i)
            i = n if j < 0 else j
            continue
        # 块注释
        if text.startswith('/*', i):
            j = text.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        # 字符串（含多行 """）
        if text.startswith('"""', i):
            j = text.find('"""', i + 3)
            i = n if j < 0 else j + 3
            out.append('""')
            continue
        if c == '"':
            j = i + 1
            while j < n:
                if text[j] == '\\':
                    j += 2
                    continue
                if text[j] == '"':
                    break
                j += 1
            i = j + 1
            out.append('""')
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def match_bracket(text, i):
    """i 指向 '(' 或 '{'，返回配对的 ')' / '}' 下标（找不到返回 -1）。"""
    open_ch = text[i]
    close_ch = ')' if open_ch == '(' else '}'
    depth = 0
    while i < len(text):
        if text[i] == open_ch:
            depth += 1
        elif text[i] == close_ch:
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def type_ranges(s):
    """每个类型/扩展的 (名字, 体起点(= '{' 位置), 体终点)。"""
    pat = (r'^[ \t]*(?:@\w+[^\n]*\n[ \t]*)?'
           r'(?:(?:final|open|public|internal|private|fileprivate)\s+)*'
           r'(?:class|struct|enum|extension|actor)\s+(\w+)[^\n{]*\{')
    out = []
    for m in re.finditer(pat, s, re.M):
        b = s.rindex('{', m.start(), m.end())
        e = match_bracket(s, b)
        if e > 0:
            out.append((m.group(1), b, e))
    return out


def stored_props(s, b, e):
    """类型体**第一层**的存储属性名（粗略：行首 var/let 声明）。"""
    props = set()
    depth = 0
    for line in s[b:e].split('\n'):
        if depth == 1:
            mm = re.match(r'(?:\w+\s+)*(?:var|let)\s+(\w+)\s*[:=]', line.strip())
            if mm:
                props.add(mm.group(1))
        depth += line.count('{') - line.count('}')
    return props


def funcs(s, b, e):
    """类型体里的 (函数头, 括号内签名, 函数体)。"""
    out = []
    for m in re.finditer(r'func\s+(\w+)\s*\(', s):
        if not (b <= m.start() < e):
            continue
        op = s.index('(', m.start())
        cp = match_bracket(s, op)
        if cp < 0:
            continue
        bb = s.find('{', cp)
        if bb < 0 or bb > e:
            continue                      # 只有声明没有实现（协议要求等）
        be = match_bracket(s, bb)
        if be < 0:
            continue
        out.append((s[m.start():bb], s[op + 1:cp], s[bb:be],
                    s.count('\n', 0, m.start()) + 1))
    return out


def params(sig):
    """→ [(内部名, 是否可选类型)]。跳过泛型/闭包里的逗号。"""
    parts = []
    depth = 0
    cur = ''
    for ch in sig:
        if ch in '(<[':
            depth += 1
        elif ch in ')>]':
            depth -= 1
        if ch == ',' and depth == 0:
            parts.append(cur)
            cur = ''
        else:
            cur += ch
    parts.append(cur)

    out = []
    for p in parts:
        p = p.strip()
        if not p or ':' not in p:
            continue
        left, right = p.split(':', 1)
        right = right.split('=')[0]                   # 去掉默认值
        words = [w for w in re.findall(r'[A-Za-z_]\w*', left) if w not in MODIFIERS]
        if not words:
            continue
        name = words[-1]                              # 外部标签在前，内部名在后
        typ = right.strip()
        opt = typ.endswith('?') or typ.endswith('!')
        out.append((name, opt))
    return out


bad = 0
shadow_hits = []
bind_hits = []
name_hits = []

# ── 检查 4：ObjC → Swift 会"省掉与参数类型重复的词" ──
# 例：SDK 的 `- (void)driveManager:(…)driveManager showCrossImage:(UIImage *)image;`
#     在 Swift 里叫 `driveManager(_:showCross:)` —— 参数类型本身就是 UIImage，
#     所以 "Image" 被省掉。照着头文件写 `showCrossImage:` 会直接编译失败：
#       error: 'driveManager(_:showCrossImage:)' has been renamed to 'driveManager(_:showCross:)'
# 2026-09-24 为此挂了 CI。规则：**外部标签**以「参数类型去掉模块前缀后的名字」结尾 ⇒ 提示。
SDK_IMPORT = re.compile(r'^\s*import\s+(AMap\w*|MAMap\w*|MapKit)\s*$', re.M)
FUNC_DECL = re.compile(r'func\s+(\w+)\s*(?:<[^>]*>)?\(([^)]*)\)')
TWO_NAME_PARAM = re.compile(r'(?<![\w.])([A-Za-z_]\w*)\s+([A-Za-z_]\w*)\s*:\s*([^,()]+)')


def type_word(typ):
    """UIImage? -> image / MAAnnotationView -> annotationview（去掉模块前缀，小写比较）

    ⚠️ 只剥**模块前缀**，别把类型名本身剥掉：Swift 省的是与类型名重复的那个词，
    所以 UIImage 要留 "image"（`showCrossImage` 结尾正好是它 ⇒ 会被省成 showCross）。
    """
    t = re.split(r'[?!\[\]<>,]', typ.strip())[0].strip()
    t = t.split('.')[-1]
    for pre in ('AMapNavi', 'AMap', 'MAMap', 'MA', 'MK', 'NS', 'UI'):
        if t.startswith(pre) and len(t) > len(pre):
            t = t[len(pre):]
            break
    return t.lower()

for root, _, files in os.walk(ROOT):
    for f in sorted(files):
        if not f.endswith('.swift'):
            continue
        p = os.path.join(root, f)
        raw = open(p, encoding='utf-8').read()
        s = strip_noise(raw)
        rel = os.path.relpath(p, ROOT)

        ob, cb = s.count('{'), s.count('}')
        op, cp = s.count('('), s.count(')')
        ok = (ob == cb and op == cp)
        if not ok:
            bad += 1

        # ── 形参遮蔽属性 / 非可选条件绑定 ──
        this_file_err = 0
        for tname, b, e in type_ranges(s):
            props = stored_props(s, b, e)
            for _head, sig, body, line in funcs(s, b, e):
                for pname, opt in params(sig):
                    if pname in props:
                        shadow_hits.append((rel, line, tname, pname))
                    if opt:
                        continue
                    # 关键：无论绑定名是不是同一个，**右边**出现非可选形参就是错：
                    #   if let v = view {      ← 这里 view 非可选 ⇒ 编译错误（踩过）
                    #   if let view {          ← 简写形式，同样错
                    #   if let x = view.foo    ← 合法（右边是可选属性），不能报
                    pat = (r'(?:if|guard|while)\s+let\s+(?:\w+\s*=\s*)?' +
                           re.escape(pname) + r'\b\s*[,{)]')
                    if re.search(pat, body):
                        bind_hits.append((rel, line, tname, pname))
                        this_file_err += 1
        if this_file_err:
            bad += 1

        # ── 形参外部标签与参数类型重复（ObjC→Swift 会省略，照头文件写就编译不过）──
        if SDK_IMPORT.search(s):
            for m in FUNC_DECL.finditer(s):
                sig = m.group(2)
                for pm in TWO_NAME_PARAM.finditer(sig):
                    label, name, typ = pm.group(1), pm.group(2), pm.group(3)
                    w = type_word(typ)
                    if not w or label == '_' or label == name:
                        continue          # 没有外部标签 / 就是参数名本身：Swift 不会改名
                    # 标签**以**类型名结尾、且前面还有别的词才算（`view v: UIView` 这种
                    # 正好等于类型名的不算 —— Swift 不会把它省略掉，报了是噪声）
                    if label.lower().endswith(w) and len(label) > len(w):
                        line = s.count('\n', 0, m.start() + pm.start(1)) + 1
                        name_hits.append((rel, line, label, typ.strip(), label[:-len(w)]))

        decls = len(re.findall(r'^\s*(?:struct|class|enum|extension)\s+\w+', raw, re.M))
        print('%-40s { %-3d } %-3d  ( %-3d ) %-3d  decls=%-3d %s'
              % (rel, ob, cb, op, cp, decls, 'OK' if ok else '<<< MISMATCH'))

print()
print('=== 非可选形参被 if/guard let 绑定（这是编译错误，必须改） ===')
if bind_hits:
    for rel, line, tname, pname in bind_hits:
        print('  %s:%d  %s 的形参 %s 是非可选类型，却又 if/guard let 绑定了它'
              % (rel, line, tname, pname))
else:
    print('  （无）')

print()
print('=== 形参遮蔽同名属性（提示，写法危险但不一定报错） ===')
if shadow_hits:
    for rel, line, tname, pname in shadow_hits:
        print('  %s:%d  %s 的形参 %s 盖住了同名属性 —— 函数体里别忘了这点'
              % (rel, line, tname, pname))
else:
    print('  （无）')

print()
print('=== SDK 方法名提示（外部标签与参数类型重复 ⇒ ObjC→Swift 会省掉那个词）===')
if name_hits:
    for rel, line, label, typ, short in name_hits:
        print('  %s:%d  %s（参数 %s）—— Swift 里这个名字多半是 `%s`，'
              '照着 ObjC 头文件写会编译不过' % (rel, line, label, typ, short))
else:
    print('  （无）')

print()
print('检查了完成，问题文件数 =', bad)
