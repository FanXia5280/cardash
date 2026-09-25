#!/usr/bin/env python3
"""构建**独立包**（solo）—— 完全不依赖 D.apk 的独立 APK。

和 `build_local.py` 是两套东西，互不影响：
  * build_local.py ：把我们的代码**注入**进原车机包 → 仍需改包重签
  * build_solo.py  ：出一个**独立安装**的应用（自己的包名、自己的清单、自己签名）
                     → 这是"以后不再改桌面"那条路线的试验田

用法（在 CarDash 目录下）：
  python tools/build_solo.py ..\\_env                    # 平台签名 + sharedUserId=android.uid.system
  python tools/build_solo.py ..\\_env --no-uid           # 去掉 sharedUserId（对照包）
  python tools/build_solo.py ..\\_env --emu              # 模拟器：调试签名 + 去 sharedUserId

产物：
  solo/dist/CarDashSolo.apk          （默认：系统签名 + sharedUserId）
  solo/dist/CarDashSolo-nouid.apk    （--no-uid）
  solo/dist/CarDashSolo-emu.apk      （--emu）
"""
import glob
import os
import re
import shutil
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))          # CarDash/tools
REPO = os.path.dirname(HERE)                               # CarDash
SOLO = os.path.join(REPO, 'solo')
KEYS = os.path.join(REPO, 'keys')
BUILD = os.path.join(SOLO, 'build')
DIST = os.path.join(SOLO, 'dist')

# ⚠️ 中文 Windows 控制台默认 GBK，把 javac/aapt2 的报错原样打出来时会直接
#    UnicodeEncodeError 崩在 print 上（`check_swift.py` 栽过同一个坑）。
#    只放宽错误处理、不改编码，保证永远能打完。
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(errors='replace')
    except Exception:
        pass

FAILED = False


def say(msg):
    print(msg)
    sys.stdout.flush()


def run(step, cmd, cwd=None):
    """跑一步，失败就记下来（沿用 build_local.py 的风格）。"""
    global FAILED
    say('>>> %s' % step)
    try:
        r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True,
                           encoding='utf-8', errors='replace')
    except Exception as e:
        say('    起不来: %s' % e)
        FAILED = True
        return False, ''
    out = (r.stdout or '') + (r.stderr or '')
    if r.returncode != 0:
        say('    失败（退出码 %d）' % r.returncode)
        for line in out.strip().splitlines()[-25:]:
            say('    | %s' % line)
        FAILED = True
        return False, out
    return True, out


def pick(pattern):
    hit = sorted(glob.glob(pattern))
    return hit[-1] if hit else None


def main():
    global FAILED
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    flags = [a for a in sys.argv[1:] if a.startswith('--')]
    if not args:
        raise SystemExit(__doc__)

    env = os.path.abspath(args[0])
    no_uid = '--no-uid' in flags
    emu = '--emu' in flags
    if emu:
        no_uid = True

    java = pick(os.path.join(env, 'jdk', '*', 'bin', 'java.exe')) \
        or pick(os.path.join(env, 'jdk', '*', 'bin', 'java'))
    javac = pick(os.path.join(env, 'jdk', '*', 'bin', 'javac.exe')) \
        or pick(os.path.join(env, 'jdk', '*', 'bin', 'javac'))
    bt = pick(os.path.join(env, 'build-tools', '*'))
    android_jar = pick(os.path.join(env, 'platform', '*', 'android.jar'))
    aapt2 = os.path.join(bt, 'aapt2.exe' if os.name == 'nt' else 'aapt2')
    zipalign = os.path.join(bt, 'zipalign.exe' if os.name == 'nt' else 'zipalign')
    d8jar = os.path.join(bt, 'lib', 'd8.jar')
    apksigner = os.path.join(bt, 'lib', 'apksigner.jar')
    pk8 = os.path.join(KEYS, 'platform.pk8')
    pem = os.path.join(KEYS, 'platform.x509.pem')

    for name, path in (('java', java), ('javac', javac), ('build-tools', bt),
                       ('android.jar', android_jar), ('d8.jar', d8jar),
                       ('apksigner.jar', apksigner)):
        if not path or not os.path.exists(path):
            raise SystemExit('找不到 %s: %s' % (name, path))
    if not emu and not (os.path.isfile(pk8) and os.path.isfile(pem)):
        raise SystemExit('找不到平台签名密钥（%s）—— 它必须跟着文件夹走' % KEYS)

    say('====== 构建独立包（solo）======')
    say('  源目录   : %s' % SOLO)
    say('  工具链   : %s' % bt)
    say('  签名     : %s' % ('模拟器调试密钥' if emu else '平台密钥（车机同款）'))
    say('  sharedUserId : %s' % ('去掉（--no-uid/--emu）' if no_uid else 'android.uid.system'))

    if os.path.isdir(BUILD):
        shutil.rmtree(BUILD)
    os.makedirs(BUILD)
    os.makedirs(DIST, exist_ok=True)

    # ── 1. 清单：需要时去掉 sharedUserId
    manifest_src = os.path.join(SOLO, 'AndroidManifest.xml')
    manifest = os.path.join(BUILD, 'AndroidManifest.xml')
    with open(manifest_src, encoding='utf-8') as f:
        xml = f.read()
    if no_uid:
        # ⚠️ 只在 <manifest ...> 标签**里面**替换：
        #    以前写的是全局 count=1，结果先命中了注释里那句解释文字，
        #    真属性反倒留着 —— 两个包做出来一模一样（踩过）。
        xml2 = re.sub(r'(<manifest\b[^>]*?)\s+android:sharedUserId="[^"]*"',
                      r'\1', xml, count=1, flags=re.S)
        if xml2 == xml:
            say('  ⚠️ 没能去掉 sharedUserId（清单里可能本来就没有），检查一下')
        else:
            say('  已从 <manifest> 标签里去掉 sharedUserId')
        xml = xml2
    with open(manifest, 'w', encoding='utf-8') as f:
        f.write(xml)

    # ── 2. javac
    classes = os.path.join(BUILD, 'classes')
    os.makedirs(classes)
    srcs = []
    for root, _dirs, files in os.walk(os.path.join(SOLO, 'src')):
        for fn in files:
            if fn.endswith('.java'):
                srcs.append(os.path.join(root, fn))
    if not srcs:
        raise SystemExit('solo/src 下没有 .java')
    # javac 参数照抄 build_local.py 里**已经验证能编译成功**的那套
    ok, _ = run('javac 编译（%d 个源文件）' % len(srcs),
                [javac, '--release', '11', '-encoding', 'UTF-8',
                 '-classpath', android_jar, '-d', classes] + srcs)
    if not ok:
        FAILED = False
        ok, _ = run('javac 编译（回退到默认 release）',
                    [javac, '-encoding', 'UTF-8', '-classpath', android_jar,
                     '-d', classes] + srcs)
    if FAILED:
        return

    # ── 3. d8 → classes.dex
    dex_dir = os.path.join(BUILD, 'dex')
    os.makedirs(dex_dir)
    objs = []
    for root, _dirs, files in os.walk(classes):
        for fn in files:
            if fn.endswith('.class'):
                objs.append(os.path.join(root, fn))
    ok, _ = run('d8 转 dex', [java, '-cp', d8jar, 'com.android.tools.r8.D8',
                              '--release', '--min-api', '30',
                              '--lib', android_jar, '--output', dex_dir] + objs)
    if FAILED:
        return

    # ── 4. 资源 + 清单 → 基础 apk
    res_zip = os.path.join(BUILD, 'res.zip')
    ok, _ = run('aapt2 compile 资源',
                [aapt2, 'compile', '--dir', os.path.join(SOLO, 'res'), '-o', res_zip])
    if FAILED:
        return
    base_apk = os.path.join(BUILD, 'base.apk')
    ok, out = run('aapt2 link', [aapt2, 'link', '-o', base_apk,
                                 '-I', android_jar, '--manifest', manifest,
                                 '--min-sdk-version', '30',
                                 '--target-sdk-version', '34',
                                 res_zip])
    if FAILED:
        return

    # ── 5. 把 classes.dex 塞进去
    withdex = os.path.join(BUILD, 'withdex.apk')
    shutil.copy(base_apk, withdex)
    dex = os.path.join(dex_dir, 'classes.dex')
    if not os.path.isfile(dex):
        say('    d8 没有产出 classes.dex')
        return
    with zipfile.ZipFile(withdex, 'a', zipfile.ZIP_DEFLATED) as z:
        z.write(dex, 'classes.dex')
    say('>>> 打包 classes.dex  (%.1f KB)' % (os.path.getsize(dex) / 1024.0))

    # ── 6. 对齐
    aligned = os.path.join(BUILD, 'aligned.apk')
    ok, _ = run('zipalign', [zipalign, '-f', '-p', '4', withdex, aligned])
    if FAILED:
        return

    # ── 7. 签名
    if emu:
        out_apk = os.path.join(DIST, 'CarDashSolo-emu.apk')
        ks = os.path.join(env, 'emu-debug.jks')
        if not os.path.isfile(ks):
            keytool = pick(os.path.join(env, 'jdk', '*', 'bin', 'keytool.exe')) \
                or pick(os.path.join(env, 'jdk', '*', 'bin', 'keytool'))
            ok, _ = run('生成调试密钥', [keytool, '-genkeypair', '-keystore', ks,
                                        '-alias', 'emu', '-storepass', 'android',
                                        '-keypass', 'android', '-keyalg', 'RSA',
                                        '-keysize', '2048', '-validity', '10000',
                                        '-dname', 'CN=Emu Debug,O=CarDash,C=US'])
            if FAILED:
                return
        ok, _ = run('apksigner 签名（调试密钥）',
                    [java, '-jar', apksigner, 'sign', '--ks', ks,
                     '--ks-key-alias', 'emu', '--ks-pass', 'pass:android',
                     '--key-pass', 'pass:android', '--min-sdk-version', '30',
                     '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true',
                     '--v3-signing-enabled', 'true', '--out', out_apk, aligned])
    else:
        out_apk = os.path.join(DIST, 'CarDashSolo-nouid.apk' if no_uid
                               else 'CarDashSolo.apk')
        ok, _ = run('apksigner 签名（平台密钥）',
                    [java, '-jar', apksigner, 'sign', '--key', pk8, '--cert', pem,
                     '--min-sdk-version', '30',
                     '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true',
                     '--v3-signing-enabled', 'true', '--out', out_apk, aligned])
    if FAILED:
        return

    # ── 8. 校验
    ok, out = run('校验签名证书', [java, '-jar', apksigner, 'verify', '--print-certs',
                                   out_apk])
    m = re.search(r'certificate SHA-256 digest:\s*([0-9a-f]+)', out)
    if m:
        got = m.group(1)
        say('   证书 SHA-256 = %s' % got)
        if not emu:
            import hashlib
            import base64
            with open(pem, 'rb') as f:
                der = base64.b64decode(b''.join(
                    l for l in f.read().splitlines() if b'-----' not in l))
            want = hashlib.sha256(der).hexdigest()
            if got == want:
                say('   OK  与车机系统签名一致')
            else:
                say('   !! 与车机系统签名不一致（want %s）' % want)
                FAILED = True

    ok, out = run('aapt2 解析产物清单', [aapt2, 'dump', 'badging', out_apk])
    for line in out.splitlines():
        if line.startswith('package:'):
            say('   %s' % line.split('platformBuildVersion')[0].strip())

    if FAILED:
        say('\n构建失败')
        sys.exit(1)
    say('\n构建成功: %s  (%.1f MB)' % (out_apk, os.path.getsize(out_apk) / 1048576.0))
    say('装法：把这个 APK 拷到车机上直接安装（和装别的 App 一样）')
    say('装完打开它 → 「复制全部」→ 发回来；车机上也可以浏览器开 http://<车机IP>:8766/solo')


if __name__ == '__main__':
    main()
