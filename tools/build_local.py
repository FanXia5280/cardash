#!/usr/bin/env python3
"""本地一键构建：编译桥接代码 → 直接改写 APK 清单并注入 dex → 用平台密钥签名。

与 GitHub Actions 里的步骤完全等价，方便先在本地验证再推仓库。

用法:
  python tools/build_local.py <D.apk> <_env 目录> [version_code] [version_name]
"""
import builtins
import os
import re
import shutil
import subprocess
import sys
import zipfile

_real_print = builtins.print


def _safe_print(*args, **kwargs):
    kwargs.setdefault('flush', True)
    try:
        _real_print(*args, **kwargs)
    except UnicodeEncodeError:
        _real_print(*(str(a).encode('ascii', 'replace').decode('ascii')
                      for a in args), **kwargs)


builtins.print = _safe_print

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE_APK = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, 'D.apk')
ENV = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
    os.path.dirname(ROOT), '_env')

# 平台签名证书的 SHA-256（与车机系统签名一致，用于自检）
EXPECTED_CERT_SHA256 = 'c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8'

FAILED = False
LOG = []


def say(text):
    LOG.append(text)
    print(text)


def find_jdk(env):
    base = os.path.join(env, 'jdk')
    for d in sorted(os.listdir(base)) if os.path.isdir(base) else []:
        cand = os.path.join(base, d)
        if os.path.isfile(os.path.join(cand, 'bin', 'javac.exe')) or \
           os.path.isfile(os.path.join(cand, 'bin', 'javac')):
            return cand
    return None


def run(title, cmd, allow_fail=False, tail=8):
    global FAILED
    say('\n' + '=' * 62)
    say('>>> ' + title)
    say('=' * 62)
    env = dict(os.environ)
    env['PYTHONIOENCODING'] = 'utf-8'
    r = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8',
                       errors='replace', env=env, cwd=ROOT)
    out = ((r.stdout or '') + (r.stderr or '')).rstrip()
    if r.returncode == 0 and tail:
        lines = [l for l in out.splitlines() if l.strip()]
        for l in lines[-tail:]:
            say('   ' + l)
    elif out:
        say(out)
    if r.returncode != 0:
        if allow_fail:
            say('   （该步失败，允许继续）')
        else:
            FAILED = True
            say('!!! 失败，退出码 %d' % r.returncode)
    return r.returncode == 0, out


def main():
    global FAILED

    # 模拟器版：去掉 sharedUserId（android.uid.system）+ 改用调试签名，
    # 这样普通模拟器（MuMu 等）才能装进去。车机版**不要**带这个标志。
    emu = '--emu' in sys.argv

    jdk = find_jdk(ENV)
    if not jdk:
        raise SystemExit('在 %s 下找不到 JDK' % ENV)

    java = os.path.join(jdk, 'bin', 'java.exe' if os.name == 'nt' else 'java')
    javac = os.path.join(jdk, 'bin', 'javac.exe' if os.name == 'nt' else 'javac')

    bt_root = os.path.join(ENV, 'build-tools')
    bt = None
    for d in sorted(os.listdir(bt_root)) if os.path.isdir(bt_root) else []:
        cand = os.path.join(bt_root, d)
        if os.path.isfile(os.path.join(cand, 'lib', 'd8.jar')):
            bt = cand
            break
    if not bt:
        raise SystemExit('找不到 build-tools')

    ajar = None
    plat_root = os.path.join(ENV, 'platform')
    for d in sorted(os.listdir(plat_root)) if os.path.isdir(plat_root) else []:
        cand = os.path.join(plat_root, d, 'android.jar')
        if os.path.isfile(cand):
            ajar = cand
            break
    if not ajar:
        raise SystemExit('找不到 android.jar')

    keys = os.path.join(ROOT, 'keys')
    pk8 = os.path.join(keys, 'platform.pk8')
    pem = os.path.join(keys, 'platform.x509.pem')
    if not (os.path.isfile(pk8) and os.path.isfile(pem)):
        for alt in (os.path.join(os.path.dirname(ENV), '_tools'),
                    os.path.dirname(ENV)):
            if os.path.isfile(os.path.join(alt, 'platform.pk8')):
                keys = alt
                pk8 = os.path.join(keys, 'platform.pk8')
                pem = os.path.join(keys, 'platform.x509.pem')
                break
    if not os.path.isfile(pk8):
        raise SystemExit('找不到 platform.pk8 / platform.x509.pem')

    build = os.path.join(ROOT, 'build')
    dist = os.path.join(ROOT, 'dist')
    for d in ('classes', 'dex'):
        shutil.rmtree(os.path.join(build, d), ignore_errors=True)
    os.makedirs(os.path.join(build, 'classes'), exist_ok=True)
    os.makedirs(os.path.join(build, 'dex'), exist_ok=True)
    os.makedirs(dist, exist_ok=True)

    say('JDK       : %s' % jdk)
    say('android.jar: %s' % ajar)
    say('源 APK    : %s' % SOURCE_APK)
    say('签名密钥  : %s' % pk8)

    if not os.path.isfile(SOURCE_APK):
        raise SystemExit('找不到源 APK')

    # ── 0. AXML 写入器自检：原样重编必须逐字节一致
    ok, out = run('AXML 写入器自检', [sys.executable,
                                     os.path.join(ROOT, 'tools', 'inject_manifest.py'),
                                     '--roundtrip', SOURCE_APK], tail=None)
    if not ok or 'ROUNDTRIP_OK' not in out:
        say('!!! AXML 写入器自检未通过，中止构建')
        sys.exit(1)

    # ── 1. javac
    srcs = []
    # inject = 桥接本体；lantern = 内置的氛围灯（MagicLantern，已去掉 res 依赖）
    for tree in (('inject', 'src'), ('lantern', 'src')):
        for base, _, files in os.walk(os.path.join(ROOT, *tree)):
            for f in files:
                if f.endswith('.java'):
                    srcs.append(os.path.join(base, f))
    say('\n源文件 %d 个（桥接 + 内置氛围灯）' % len(srcs))

    ok, _ = run('javac 编译', [javac, '--release', '11', '-encoding', 'UTF-8',
                              '-classpath', ajar, '-d', os.path.join(build, 'classes')] + srcs)
    if not ok:
        run('javac 编译（回退到默认 release）',
            [javac, '-encoding', 'UTF-8', '-classpath', ajar,
             '-d', os.path.join(build, 'classes')] + srcs)
    if FAILED:
        return

    classes = []
    for base, _, files in os.walk(os.path.join(build, 'classes')):
        for f in files:
            if f.endswith('.class'):
                classes.append(os.path.join(base, f))

    # ── 2. d8
    ok, _ = run('d8 转 dex', [java, '-cp', os.path.join(bt, 'lib', 'd8.jar'),
                             'com.android.tools.r8.D8', '--release', '--min-api', '30',
                             '--lib', ajar, '--output', os.path.join(build, 'dex')] + classes)
    if FAILED:
        return
    injected = os.path.join(build, 'injected.dex')
    os.replace(os.path.join(build, 'dex', 'classes.dex'), injected)
    say('   注入 dex = %d 字节（inject_manifest 会自动挑下一个 classesN.dex 槽位）'
        % os.path.getsize(injected))

    # ── 3. 改写清单 + 注入 dex
    patched = os.path.join(build, 'patched.apk')
    cmd = [sys.executable, os.path.join(ROOT, 'tools', 'inject_manifest.py'),
           SOURCE_APK, injected, patched]
    if len(sys.argv) > 3 and sys.argv[3].strip():
        cmd.append(sys.argv[3])
        if len(sys.argv) > 4 and sys.argv[4].strip():
            cmd.append(sys.argv[4])
    if '--no-shared-uid' in sys.argv or emu:
        cmd.append('--no-shared-uid')
    ok, _ = run('改写清单并注入 dex', cmd, tail=None)
    if FAILED:
        return

    # ── 4. 对齐
    zipalign = os.path.join(bt, 'zipalign.exe' if os.name == 'nt' else 'zipalign')
    aligned = os.path.join(build, 'aligned.apk')
    ok, _ = run('zipalign', [zipalign, '-f', '-p', '4', patched, aligned])
    if FAILED:
        return

    # ── 5. 签名
    out_apk = os.path.join(dist, 'Deepal-CarDash.apk')
    apksigner = os.path.join(bt, 'lib', 'apksigner.jar')
    if emu:
        # 模拟器用调试密钥（普通 key）。第一次跑会自动生成一个。
        ks = os.path.join(ENV, 'emu-debug.jks')
        if not os.path.isfile(ks):
            keytool = os.path.join(jdk, 'bin', 'keytool.exe' if os.name == 'nt' else 'keytool')
            ok, _ = run('生成模拟器调试密钥', [keytool, '-genkeypair', '-v',
                         '-keystore', ks, '-alias', 'emu',
                         '-storepass', 'android', '-keypass', 'android',
                         '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000',
                         '-dname', 'CN=Emu Debug,O=CarDash,C=US'], tail=None)
            if FAILED:
                return
        ok, _ = run('apksigner 签名（模拟器调试密钥）', [java, '-jar', apksigner, 'sign',
                       '--ks', ks, '--ks-key-alias', 'emu',
                       '--ks-pass', 'pass:android', '--key-pass', 'pass:android',
                       '--min-sdk-version', '30',
                       '--v1-signing-enabled', 'true',
                       '--v2-signing-enabled', 'true',
                       '--v3-signing-enabled', 'true',
                       '--out', out_apk, aligned])
        if FAILED:
            return
    else:
        ok, _ = run('apksigner 签名', [java, '-jar', apksigner, 'sign',
                                       '--key', pk8, '--cert', pem,
                                       '--min-sdk-version', '30',
                                       '--v1-signing-enabled', 'true',
                                       '--v2-signing-enabled', 'true',
                                       '--v3-signing-enabled', 'true',
                                       '--out', out_apk, aligned])
        if FAILED:
            return

    # ── 5b. 用系统同款解析器校验清单（aapt2 的 ResXMLTree 与框架一致）
    aapt2 = os.path.join(bt, 'aapt2.exe' if os.name == 'nt' else 'aapt2')
    if os.path.isfile(aapt2):
        ok, out = run('aapt2 解析清单', [aapt2, 'dump', 'badging', out_apk], tail=None)
        bad = ('error:' in out.lower()) or ('is not on an integer boundary' in out)
        if not ok or bad:
            say('!!! 系统解析器无法解析该清单，装到车机上会报「APK解析器异常」，中止')
            sys.exit(1)
        say('   OK  aapt2 能正常解析，与框架一致')
    else:
        say('   （找不到 aapt2，跳过解析校验）')

    # ── 6. 校验签名证书
    ok, out = run('校验签名证书', [java, '-jar', apksigner, 'verify',
                                   '--print-certs', out_apk], tail=None)
    m = re.search(r'certificate SHA-256 digest:\s*([0-9a-f]+)', out)
    if m:
        got = m.group(1)
        say('   证书 SHA-256 = %s' % got)
        if emu:
            say('   模拟器包（--emu：已去 sharedUserId + 调试签名），不校验车机系统签名')
        elif got != EXPECTED_CERT_SHA256:
            say('!!! 证书与车机系统签名不一致，安装会失败')
            sys.exit(1)
        else:
            say('   ✅ 与车机系统签名一致')
    else:
        say('!!! 没能读出证书指纹')
        sys.exit(1)

    # ── 7. 差异自检
    with zipfile.ZipFile(SOURCE_APK) as a, zipfile.ZipFile(out_apk) as b:
        na, nb = set(a.namelist()), set(b.namelist())
        say('\n   新增条目: %s' % sorted(nb - na))
        say('   删除条目: %s' % sorted(na - nb))

    with open(os.path.join(build, 'build.log'), 'w', encoding='utf-8') as f:
        f.write('\n'.join(LOG))

    print()
    print('=' * 62)
    print('构建成功: %s  (%.1f MB)' % (out_apk, os.path.getsize(out_apk) / 1048576))


if __name__ == '__main__':
    main()
