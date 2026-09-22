"""二进制 AndroidManifest.xml（AXML）的极简读写器。

只支持清单会用到的部分：字符串池、资源映射表、命名空间、元素、属性。
解析成 token 列表后可以任意增删元素，再整体重新编码 —— 因为我们只往字符串池
尾部追加（索引不变），所以所有已有的属性引用都保持有效。

关键约定（已用 D.apk 实测确认）：
  · 属性的 name 字段是「字符串池索引」
  · resourceMap[字符串池索引] 才是框架属性资源 ID
  · 所以新增属性名必须复用字符串池里已有的名字，否则 resmap 对不上
"""
import struct

CHUNK_STRING_POOL = 0x0001
CHUNK_XML = 0x0003
CHUNK_RESOURCE_MAP = 0x0180
CHUNK_START_NS = 0x0100
CHUNK_END_NS = 0x0101
CHUNK_START_ELEMENT = 0x0102
CHUNK_END_ELEMENT = 0x0103
CHUNK_CDATA = 0x0104

TYPE_REFERENCE = 0x01
TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_BOOLEAN = 0x12

NO_INDEX = 0xFFFFFFFF

NS_ANDROID = 'http://schemas.android.com/apk/res/android'


class AxmlError(Exception):
    pass


class Attr(object):
    __slots__ = ('ns', 'name', 'raw', 'dtype', 'data')

    def __init__(self, ns, name, raw, dtype, data):
        self.ns = ns          # 命名空间 URI 的字符串索引，或 NO_INDEX
        self.name = name      # 属性名字符串索引
        self.raw = raw        # 原始字符串值索引，或 NO_INDEX
        self.dtype = dtype
        self.data = data

    def __repr__(self):
        return 'Attr(ns=%s, name=%s, raw=%s, type=0x%02x, data=%s)' % (
            self.ns, self.name, self.raw, self.dtype, self.data)


class Token(object):
    """统一表示一个节点：kind 为 start / end / ns_start / ns_end / cdata"""
    __slots__ = ('kind', 'name', 'attrs', 'line', 'prefix', 'uri', 'raw_bytes',
                 'attr_start', 'attr_size', 'id_index', 'class_index', 'style_index')

    def __init__(self, kind, name=None, attrs=None, line=0,
                 prefix=NO_INDEX, uri=NO_INDEX, raw_bytes=b''):
        self.kind = kind
        self.name = name
        self.attrs = attrs or []
        self.line = line
        self.prefix = prefix
        self.uri = uri
        self.raw_bytes = raw_bytes
        # 以下字段保持与原始文件一致，避免引入无谓差异
        self.attr_start = 20
        self.attr_size = 20
        self.id_index = 0
        self.class_index = 0
        self.style_index = 0

    def __repr__(self):
        return '<%s %s attrs=%d>' % (self.kind, self.name, len(self.attrs))


class Document(object):
    def __init__(self, strings, resmap, tokens, utf8):
        self.strings = strings
        self.resmap = resmap
        self.tokens = tokens
        self.utf8 = utf8

    # ── 字符串池操作 ────────────────────────────────────────────

    def index_of(self, s):
        try:
            return self.strings.index(s)
        except ValueError:
            return None

    def add_string(self, s):
        """追加字符串并返回索引；已存在则直接复用。"""
        i = self.index_of(s)
        if i is not None:
            return i
        self.strings.append(s)
        return len(self.strings) - 1

    def string(self, idx):
        if idx is None or idx >= len(self.strings) or idx < 0:
            return None
        return self.strings[idx]

    def res_id(self, name_idx):
        if name_idx is None or name_idx >= len(self.resmap):
            return None
        return self.resmap[name_idx]

    def attr_name_index(self, name):
        """找一个可用于写入的属性名索引（必须同时存在于字符串池和资源映射表）。"""
        idx = self.index_of(name)
        if idx is None:
            raise AxmlError('字符串池里没有属性名 %r，为避免破坏资源映射表，拒绝新增' % name)
        if idx >= len(self.resmap):
            raise AxmlError('属性名 %r 在字符串池索引 %d，超出资源映射表长度 %d'
                            % (name, idx, len(self.resmap)))
        return idx


# ─────────────────────────────────────────── 解析


def _u16(d, o):
    return struct.unpack_from('<H', d, o)[0]


def _u32(d, o):
    return struct.unpack_from('<I', d, o)[0]


def _parse_pool(d, p):
    hs = _u16(d, p + 2)
    cnt, styc, flags, sstart, _ = struct.unpack_from('<IIIII', d, p + 8)
    utf8 = bool(flags & 0x100)
    strings = []
    for i in range(cnt):
        o = _u32(d, p + hs + 4 * i)
        q = p + sstart + o
        if utf8:
            n = d[q]
            q += 1
            if n & 0x80:
                n = ((n & 0x7f) << 8) | d[q]
                q += 1
            strings.append(d[q:q + n].decode('utf-8', 'replace'))
        else:
            n = _u16(d, q)
            q += 2
            if n & 0x8000:
                n = ((n & 0x7fff) << 16) | _u16(d, q)
                q += 2
            strings.append(d[q:q + 2 * n].decode('utf-16-le', 'replace'))
    if styc:
        raise AxmlError('字符串池带样式数据（styleCount=%d），暂不支持' % styc)
    return strings, utf8


def parse(data):
    if _u16(data, 0) != CHUNK_XML:
        raise AxmlError('不是 AXML 文件（magic=0x%04x）' % _u16(data, 0))
    total = _u32(data, 4)

    strings, utf8, resmap, tokens = None, False, [], []
    p = 8
    while p < total:
        ctype = _u16(data, p)
        hs = _u16(data, p + 2)
        cs = _u32(data, p + 4)

        if ctype == CHUNK_STRING_POOL:
            strings, utf8 = _parse_pool(data, p)

        elif ctype == CHUNK_RESOURCE_MAP:
            n = (cs - hs) // 4
            resmap = [_u32(data, p + hs + 4 * i) for i in range(n)]

        elif ctype == CHUNK_START_NS or ctype == CHUNK_END_NS:
            prefix = _u32(data, p + 16)
            uri = _u32(data, p + 20)
            tokens.append(Token('ns_start' if ctype == CHUNK_START_NS else 'ns_end',
                                line=_u32(data, p + 8), prefix=prefix, uri=uri))

        elif ctype == CHUNK_START_ELEMENT:
            ns, name, a_start, a_size, a_cnt = struct.unpack_from('<IIHHH', data, p + 16)
            attrs = []
            for i in range(a_cnt):
                a = p + 16 + a_start + i * a_size
                ans, an, raw = struct.unpack_from('<III', data, a)
                attrs.append(Attr(ans, an, raw, data[a + 15], _u32(data, a + 16)))
            tokens.append(Token('start', name=name, attrs=attrs, line=_u32(data, p + 8)))

        elif ctype == CHUNK_END_ELEMENT:
            ns, name = struct.unpack_from('<II', data, p + 16)
            tokens.append(Token('end', name=name, line=_u32(data, p + 8)))

        elif ctype == CHUNK_CDATA:
            tokens.append(Token('cdata', raw_bytes=data[p:p + cs]))

        p += cs

    if strings is None:
        raise AxmlError('没有找到字符串池')
    return Document(strings, resmap, tokens, utf8)


# ─────────────────────────────────────────── 编码


def _build_pool(strings):
    """按 UTF-16 重新编码字符串池，索引与传入顺序严格一致。"""
    header_size = 28
    offsets = []
    blob = bytearray()
    for s in strings:
        offsets.append(len(blob))
        raw = s.encode('utf-16-le')
        n = len(s)
        if n > 0x7FFF:
            # 超长字符串用双字节长度前缀
            blob += struct.pack('<H', ((n >> 16) & 0x7FFF) | 0x8000)
            blob += struct.pack('<H', n & 0xFFFF)
        else:
            blob += struct.pack('<H', n)
        blob += raw
        blob += struct.pack('<H', 0)

    strings_start = header_size + 4 * len(strings)
    size = strings_start + len(blob)
    out = struct.pack('<HHI', CHUNK_STRING_POOL, header_size, size)
    out += struct.pack('<IIIII', len(strings), 0, 0, strings_start, 0)
    for o in offsets:
        out += struct.pack('<I', o)
    return out + bytes(blob)


def _build_resmap(resmap):
    size = 8 + 4 * len(resmap)
    return struct.pack('<HHI', CHUNK_RESOURCE_MAP, 8, size) + \
        b''.join(struct.pack('<I', r) for r in resmap)


def _chunk(ctype, header_size, body):
    """body 是「8 字节 chunk 头之后」的全部内容，size 字段按总长度计算。"""
    return struct.pack('<HHI', ctype, header_size, 8 + len(body)) + body


def _emit_token(t):
    if t.kind == 'start':
        attrs = bytearray()
        for a in t.attrs:
            attrs += struct.pack('<III', a.ns, a.name, a.raw)
            attrs += struct.pack('<HBB', 8, 0, a.dtype)
            attrs += struct.pack('<I', a.data)
        ext = struct.pack('<IIHHHHHH', NO_INDEX, t.name, t.attr_start, t.attr_size,
                          len(t.attrs), t.id_index, t.class_index, t.style_index)
        body = struct.pack('<II', t.line, NO_INDEX) + ext + bytes(attrs)
        return _chunk(CHUNK_START_ELEMENT, 16, body)

    if t.kind == 'end':
        body = struct.pack('<II', t.line, NO_INDEX) + struct.pack('<II', NO_INDEX, t.name)
        return _chunk(CHUNK_END_ELEMENT, 16, body)

    if t.kind in ('ns_start', 'ns_end'):
        ctype = CHUNK_START_NS if t.kind == 'ns_start' else CHUNK_END_NS
        body = struct.pack('<II', t.line, NO_INDEX) + struct.pack('<II', t.prefix, t.uri)
        return _chunk(ctype, 16, body)

    if t.kind == 'cdata':
        return t.raw_bytes

    raise AxmlError('未知 token: %r' % t)


def emit(doc):
    body = bytearray()
    body += _build_pool(doc.strings)
    body += _build_resmap(doc.resmap)
    for t in doc.tokens:
        body += _emit_token(t)
    return _chunk(CHUNK_XML, 8, bytes(body))


# ─────────────────────────────────────────── 高层辅助


def make_string_attr(doc, attr_name, value, ns=NO_INDEX):
    idx = doc.add_string(value)
    return Attr(ns, doc.attr_name_index(attr_name), idx, TYPE_STRING, idx)


def make_int_attr(doc, attr_name, value, ns=NO_INDEX):
    return Attr(ns, doc.attr_name_index(attr_name), NO_INDEX, TYPE_INT_DEC, value & 0xFFFFFFFF)


def make_bool_attr(doc, attr_name, value, ns=NO_INDEX):
    return Attr(ns, doc.attr_name_index(attr_name), NO_INDEX, TYPE_INT_BOOLEAN, 1 if value else 0)


def make_ref_attr(doc, attr_name, res_id, ns=NO_INDEX):
    """资源引用属性，例如 android:resource="@xml/xxx"。"""
    return Attr(ns, doc.attr_name_index(attr_name), NO_INDEX,
                TYPE_REFERENCE, res_id & 0xFFFFFFFF)


def find_element(doc, name, occurrence=0):
    """按出现顺序返回第 occurrence 个 <name> 的 (start_index, end_index)。"""
    count = 0
    for i, t in enumerate(doc.tokens):
        if t.kind == 'start' and doc.string(t.name) == name:
            if count == occurrence:
                return i, _matching_end(doc, i)
            count += 1
    return None, None


def _matching_end(doc, start_idx):
    depth = 0
    for i in range(start_idx, len(doc.tokens)):
        t = doc.tokens[i]
        if t.kind == 'start':
            depth += 1
        elif t.kind == 'end':
            depth -= 1
            if depth == 0:
                return i
    return None


def dump(doc):
    depth = 0
    lines = []
    for t in doc.tokens:
        if t.kind == 'start':
            lines.append('  ' * depth + '<' + str(doc.string(t.name)))
            for a in t.attrs:
                nm = doc.string(a.name)
                rid = doc.res_id(a.name)
                if a.raw != NO_INDEX:
                    val = repr(doc.string(a.raw))
                elif a.dtype == TYPE_INT_BOOLEAN:
                    val = str(bool(a.data))
                elif a.dtype == TYPE_INT_DEC:
                    val = str(struct.unpack('<i', struct.pack('<I', a.data))[0])
                else:
                    val = 'type=0x%02x data=0x%x' % (a.dtype, a.data)
                lines.append('  ' * (depth + 1) + '%s (0x%08x) = %s'
                             % (nm, rid if rid else 0, val))
            depth += 1
        elif t.kind == 'end':
            depth -= 1
            lines.append('  ' * depth + '</' + str(doc.string(t.name)) + '>')
    return '\n'.join(lines)
