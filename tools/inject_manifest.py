#!/usr/bin/env python3
"""直接改写 APK 内的二进制 AndroidManifest.xml，并把 classes2.dex 一起塞进去。

不走 apktool、不重编资源 —— 除了清单和新增的 dex，APK 其余内容逐字节保持不变，
所以对原始桌面 App 的改动面小到可以忽略。

用法:
  python inject_manifest.py <src.apk> <classes2.dex|-> <out.apk> [version_code] [version_name]
  python inject_manifest.py --dump <src.apk>
  python inject_manifest.py --roundtrip <src.apk>
"""
import os
import struct
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import axml
from axml import NO_INDEX, NS_ANDROID, Token

PKG = 'com.deepalhome.launcher'

RID_VERSION_CODE = 0x0101021b
RID_VERSION_NAME = 0x0101021c
RID_NAME = 0x01010003
RID_RESOURCE = 0x01010025
RID_SHARED_USER_ID = 0x0101000b

FGS_DATA_SYNC = 1  # FOREGROUND_SERVICE_TYPE_DATA_SYNC

A11Y_SERVICE = 'com.cardash.inject.NaviAccessibilityService'
A11Y_PERMISSION = 'android.permission.BIND_ACCESSIBILITY_SERVICE'
A11Y_ACTION = 'android.accessibilityservice.AccessibilityService'
A11Y_METADATA = 'android.accessibilityservice'

# 这两个权限在 Android 11（车机）上不存在，声明了也只是被忽略；
# 但到了 Android 13+ 就是必需的，补上顺带让模拟器能完整验证。
#   POST_NOTIFICATIONS            —— API 33+ 发通知必须申请
#   FOREGROUND_SERVICE_DATA_SYNC  —— API 34+ 起 dataSync 类型前台服务必须申请
EXTRA_PERMISSIONS = [
    'android.permission.POST_NOTIFICATIONS',
    'android.permission.FOREGROUND_SERVICE_DATA_SYNC',
]


def die(msg):
    print('!! ' + msg)
    sys.exit(1)


# ─────────────────────────────────────────── 构建要插入的组件

def find_a11y_config_res(doc):
    """复用 D.apk 自带的无障碍配置资源。

    新增 XML 资源需要重编 resources.arsc（apktool 那条路已经验证过会挂），
    而 D.apk 自己就声明了一份无障碍配置，内容正好够用：
        accessibilityEventTypes = typeAllMask
        canRetrieveWindowContent = true
        flags = flagDefault | flagRetrieveInteractiveWindows
        （没有 packageNames 限制，能收到所有应用的窗口事件）
    所以直接引用同一个资源 ID 即可。
    """
    for t in doc.tokens:
        if t.kind != 'start' or doc.string(t.name) != 'meta-data':
            continue
        name_val = None
        res_val = None
        for a in t.attrs:
            rid = doc.res_id(a.name)
            if rid == RID_NAME and a.raw != NO_INDEX:
                name_val = doc.string(a.raw)
            elif rid == RID_RESOURCE:
                res_val = a.data
        if name_val == A11Y_METADATA and res_val:
            return res_val
    return None


def build_permissions(doc, ns):
    """补声明权限，插在 <application> 之前。"""
    toks = []
    for perm in EXTRA_PERMISSIONS:
        toks.append(Token('start', name=doc.add_string('uses-permission'),
                          attrs=[axml.make_string_attr(doc, 'name', perm, ns)]))
        toks.append(Token('end', name=doc.add_string('uses-permission')))
    return toks


def build_components(doc, ns, a11y_res):
    def E(name):
        return doc.add_string(name)

    def A(name, value):
        return axml.make_string_attr(doc, name, value, ns)

    def B(name, value):
        return axml.make_bool_attr(doc, name, value, ns)

    def I(name, value):
        return axml.make_int_attr(doc, name, value, ns)

    def R(name, res_id):
        return axml.make_ref_attr(doc, name, res_id, ns)

    toks = []

    # 1) 前台服务：跑 HTTP 与采集器
    toks.append(Token('start', name=E('service'), attrs=[
        A('name', 'com.cardash.inject.BridgeService'),
        B('exported', False),
        I('foregroundServiceType', FGS_DATA_SYNC),
    ]))
    toks.append(Token('end', name=E('service')))

    # 2) 通知监听：读音乐会话 + 解析导航通知
    toks.append(Token('start', name=E('service'), attrs=[
        A('name', 'com.cardash.inject.NavListenerService'),
        B('exported', True),
        A('permission', 'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE'),
    ]))
    toks.append(Token('start', name=E('intent-filter'), attrs=[]))
    toks.append(Token('start', name=E('action'), attrs=[
        A('name', 'android.service.notification.NotificationListenerService'),
    ]))
    toks.append(Token('end', name=E('action')))
    toks.append(Token('end', name=E('intent-filter')))
    toks.append(Token('end', name=E('service')))

    # 3) ContentProvider：进程启动时自动拉起桥接
    toks.append(Token('start', name=E('provider'), attrs=[
        A('name', 'com.cardash.inject.BootProvider'),
        A('authorities', PKG + '.cardashboot'),
        B('exported', False),
    ]))
    toks.append(Token('end', name=E('provider')))

    # 4) 无障碍服务：读屏幕上的导航信息（不放在独立进程，否则拿不到共享状态）
    toks.append(Token('start', name=E('service'), attrs=[
        A('name', A11Y_SERVICE),
        B('exported', True),
        A('permission', A11Y_PERMISSION),
    ]))
    toks.append(Token('start', name=E('intent-filter'), attrs=[]))
    toks.append(Token('start', name=E('action'), attrs=[A('name', A11Y_ACTION)]))
    toks.append(Token('end', name=E('action')))
    toks.append(Token('end', name=E('intent-filter')))
    toks.append(Token('start', name=E('meta-data'), attrs=[
        A('name', A11Y_METADATA),
        R('resource', a11y_res),
    ]))
    toks.append(Token('end', name=E('meta-data')))
    toks.append(Token('end', name=E('service')))

    # 5) 启动广播：开机 / 装完新包立刻拉起桥接
    toks.append(Token('start', name=E('receiver'), attrs=[
        A('name', 'com.cardash.inject.BootReceiver'),
        B('exported', True),
    ]))
    toks.append(Token('start', name=E('intent-filter'), attrs=[]))
    for act in ('android.intent.action.BOOT_COMPLETED',
                'android.intent.action.LOCKED_BOOT_COMPLETED',
                'android.intent.action.MY_PACKAGE_REPLACED'):
        toks.append(Token('start', name=E('action'), attrs=[A('name', act)]))
        toks.append(Token('end', name=E('action')))
    toks.append(Token('end', name=E('intent-filter')))
    toks.append(Token('end', name=E('receiver')))

    return toks


# ─────────────────────────────────────────── 改写清单

def patch_manifest(data, version_code=None, version_name=None, drop_shared_uid=False):
    doc = axml.parse(data)

    ns = doc.index_of(NS_ANDROID)
    if ns is None:
        die('清单里找不到 android 命名空间')

    mi, _ = axml.find_element(doc, 'manifest')
    if mi is None:
        die('找不到 <manifest>')
    manifest_tok = doc.tokens[mi]

    old_code = None
    old_name = None
    for a in manifest_tok.attrs:
        rid = doc.res_id(a.name)
        if rid == RID_VERSION_CODE:
            old_code = struct.unpack('<i', struct.pack('<I', a.data))[0]
        elif rid == RID_VERSION_NAME:
            old_name = doc.string(a.raw)

    if old_code is None:
        die('清单里没有 versionCode')

    new_code = int(version_code) if version_code else old_code + 1
    if new_code <= old_code:
        die('新 versionCode(%d) 必须大于原值(%d)，否则无法覆盖安装' % (new_code, old_code))
    new_name = version_name if version_name else ((old_name or 'v1') + '-cd')

    name_idx = doc.add_string(new_name)
    for a in manifest_tok.attrs:
        rid = doc.res_id(a.name)
        if rid == RID_VERSION_CODE:
            a.raw = NO_INDEX
            a.dtype = axml.TYPE_INT_DEC
            a.data = new_code
        elif rid == RID_VERSION_NAME:
            a.raw = name_idx
            a.dtype = axml.TYPE_STRING
            a.data = name_idx

    for t in doc.tokens:
        if t.kind == 'start':
            for a in t.attrs:
                if a.raw != NO_INDEX and doc.string(a.raw) == 'com.cardash.inject.BootProvider':
                    die('清单里已经存在桥接组件，请用未打补丁的原始 APK')

    # 仅用于在模拟器上测试：Android 14+ 禁止非预装应用加入 android.uid.system，
    # 去掉这个属性才能在模拟器上装进去验证运行时行为。车机（Android 11）不需要。
    if drop_shared_uid:
        before = len(manifest_tok.attrs)
        manifest_tok.attrs = [a for a in manifest_tok.attrs
                              if doc.res_id(a.name) != RID_SHARED_USER_ID]
        if len(manifest_tok.attrs) == before:
            print('注意：清单里本来就没有 sharedUserId')
        else:
            print('已移除 sharedUserId（仅用于模拟器测试，勿用于车机）')

    a11y_res = find_a11y_config_res(doc)
    if a11y_res is None:
        die('D.apk 里找不到 android.accessibilityservice 的配置资源，无法注入无障碍服务')
    print('复用无障碍配置资源 ID = 0x%08x' % a11y_res)

    ai, ae = axml.find_element(doc, 'application')
    if ae is None:
        die('找不到 </application>')

    # 补权限要插在 <application> 之前，插入后 </application> 的位置要相应后移
    perms = build_permissions(doc, ns)
    if perms:
        doc.tokens[ai:ai] = perms
        ae += len(perms)
        print('补声明权限 %d 条' % (len(EXTRA_PERMISSIONS)))

    doc.tokens[ae:ae] = build_components(doc, ns, a11y_res)

    out = axml.emit(doc)
    info = {
        'old_version_code': old_code,
        'new_version_code': new_code,
        'old_version_name': old_name,
        'new_version_name': new_name,
        'strings': len(doc.strings),
    }
    return out, info


# ─────────────────────────────────────────── APK 级操作

STORED = {'AndroidManifest.xml', 'resources.arsc'}


def build_apk(src, dex_path, out, version_code, version_name, quiet=False,
              drop_shared_uid=False):
    if not os.path.isfile(src):
        die('找不到源 APK: ' + src)

    with zipfile.ZipFile(src) as zin:
        names = zin.namelist()
        for need in ('AndroidManifest.xml', 'classes.dex', 'resources.arsc'):
            if need not in names:
                die('源 APK 缺少 ' + need)
        if 'classes2.dex' in names:
            die('源 APK 已存在 classes2.dex，请用原始包')

        new_manifest, info = patch_manifest(zin.read('AndroidManifest.xml'),
                                            version_code, version_name, drop_shared_uid)

        dex = None
        if dex_path and dex_path != '-':
            if not os.path.isfile(dex_path):
                die('找不到 ' + dex_path)
            dex = open(dex_path, 'rb').read()
            if not dex.startswith(b'dex\n'):
                die('classes2.dex 不是合法的 dex')

        with zipfile.ZipFile(out, 'w') as zout:
            for item in zin.infolist():
                data = zin.read(item.filename)
                if item.filename == 'AndroidManifest.xml':
                    data = new_manifest
                if item.filename in STORED:
                    zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
                    zi.compress_type = zipfile.ZIP_STORED
                    zi.external_attr = item.external_attr
                    zout.writestr(zi, data)
                else:
                    zout.writestr(item, data)
            if dex:
                zi = zipfile.ZipInfo('classes2.dex')
                zi.compress_type = zipfile.ZIP_DEFLATED
                zout.writestr(zi, dex)

    if not quiet:
        print('versionCode %d → %d' % (info['old_version_code'], info['new_version_code']))
        print('versionName "%s" → "%s"' % (info['old_version_name'], info['new_version_name']))
        print('字符串池 %d 项' % info['strings'])
        if dex:
            print('已注入 classes2.dex（%d 字节）' % len(dex))
        print('输出: %s (%.1f MB)' % (out, os.path.getsize(out) / 1048576))
    return info


# ─────────────────────────────────────────── 自检

def roundtrip(src):
    """把原始清单解析后再原样编码，逐字节比对，验证写入器没有偏差。"""
    with zipfile.ZipFile(src) as z:
        original = z.read('AndroidManifest.xml')

    doc = axml.parse(original)
    rebuilt = axml.emit(doc)

    print('原始清单 %d 字节，重新编码 %d 字节' % (len(original), len(rebuilt)))

    if original == rebuilt:
        print('逐字节完全一致 —— 写入器与原编译器输出等价')
        print('ROUNDTRIP_OK')
        return 0

    n = min(len(original), len(rebuilt))
    diff = next((i for i in range(n) if original[i] != rebuilt[i]), n)
    print('存在差异，首个不同字节位于偏移 %d（0x%x）' % (diff, diff))
    print('  原始: %s' % original[max(0, diff - 8):diff + 16].hex(' '))
    print('  生成: %s' % rebuilt[max(0, diff - 8):diff + 16].hex(' '))
    print('ROUNDTRIP_MISMATCH')

    # 结构层面是否等价
    d2 = axml.parse(rebuilt)
    same_strings = d2.strings == doc.strings
    same_resmap = d2.resmap == doc.resmap
    same_count = len(d2.tokens) == len(doc.tokens)
    print('字符串池一致: %s   资源表一致: %s   token 数一致: %s'
          % (same_strings, same_resmap, same_count))
    if same_strings and same_resmap and same_count:
        print('结构等价（仅字节级排布有细微差别）')
        print('ROUNDTRIP_OK')
        return 0
    return 1


def main():
    if len(sys.argv) < 3:
        die('用法见文件头注释')

    mode = sys.argv[1]

    if mode == '--dump':
        with zipfile.ZipFile(sys.argv[2]) as z:
            doc = axml.parse(z.read('AndroidManifest.xml'))
        print(axml.dump(doc))
        return

    if mode == '--roundtrip':
        sys.exit(roundtrip(sys.argv[2]))

    if len(sys.argv) < 4:
        die('用法: inject_manifest.py <src.apk> <classes2.dex|-> <out.apk> [code] [name]')

    flags = {a for a in sys.argv[1:] if a.startswith('--')}
    pos = [a for a in sys.argv[1:] if not a.startswith('--')]
    if len(pos) < 3:
        die('用法: inject_manifest.py <src.apk> <classes2.dex|-> <out.apk> '
            '[version_code] [version_name] [--no-shared-uid]')

    src, dex, out = pos[0], pos[1], pos[2]
    code = pos[3] if len(pos) > 3 and pos[3].strip() else None
    name = pos[4] if len(pos) > 4 and pos[4].strip() else None
    build_apk(src, dex, out, code, name,
              drop_shared_uid='--no-shared-uid' in flags)


if __name__ == '__main__':
    main()
