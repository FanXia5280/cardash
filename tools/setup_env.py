"""下载并解压构建工具链：JDK 17 + Android build-tools + platform + 平台签名密钥。"""
import base64
import os
import sys
import time
import urllib.request
import zipfile

ENV = r'd:\Desktop\IPA\_env'
DL = os.path.join(ENV, 'dl')

FILES = [
    ('jdk.zip',
     'https://aka.ms/download-jdk/microsoft-jdk-17.0.13-windows-x64.zip'),
    ('build-tools.zip',
     'https://dl.google.com/android/repository/build-tools_r34-windows.zip'),
    ('platform-34.zip',
     'https://dl.google.com/android/repository/platform-34-ext12_r01.zip'),
]

# 车机平台签名密钥（AOSP 公开的 platform 密钥，与车机系统签名一致）
AOSP_KEY_BASE = ('https://android.googlesource.com/platform/build/+/refs/heads/main'
                 '/target/product/security')
KEY_FILES = ['platform.pk8', 'platform.x509.pem']

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KEYS_DIR = os.path.join(REPO_ROOT, 'keys')

EXPECTED_CERT_MD5 = '8ddb342f2da5408402d7568af21e29f9'


def fetch(name, url):
    path = os.path.join(DL, name)
    if os.path.isfile(path) and os.path.getsize(path) > 1024:
        print('  已存在 %s (%.1f MB)' % (name, os.path.getsize(path) / 1048576))
        return path

    last = None
    for attempt in range(3):
        try:
            req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
            r = urllib.request.urlopen(req, timeout=60)
            total = int(r.headers.get('Content-Length') or 0)
            got = 0
            t0 = time.time()
            with open(path, 'wb') as f:
                while True:
                    chunk = r.read(262144)
                    if not chunk:
                        break
                    f.write(chunk)
                    got += len(chunk)
                    if total:
                        pct = 100.0 * got / total
                        sys.stdout.write('\r  %-18s %5.1f%%  %.1f/%.1f MB  %.1f MB/s   '
                                         % (name, pct, got / 1048576, total / 1048576,
                                            got / 1048576 / max(time.time() - t0, 0.01)))
                        sys.stdout.flush()
            print()
            return path
        except Exception as e:
            last = e
            print('\n  第 %d 次失败: %s: %s' % (attempt + 1, type(e).__name__, e))
            if os.path.isfile(path):
                os.remove(path)
            time.sleep(3)
    raise SystemExit('下载 %s 失败: %s' % (name, last))


def unzip(src, dest, only=None):
    os.makedirs(dest, exist_ok=True)
    with zipfile.ZipFile(src) as z:
        names = z.namelist()
        if only:
            names = [n for n in names if only(n)]
        z.extractall(dest, names)
    return len(names)


def fetch_b64(name, url, dest_dir):
    """AOSP 的源码文件是 base64 编码返回的。"""
    path = os.path.join(dest_dir, name)
    if os.path.isfile(path) and os.path.getsize(path) > 256:
        print('  已存在 keys/%s (%d 字节)' % (name, os.path.getsize(path)))
        return path
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    raw = urllib.request.urlopen(req, timeout=40).read()
    data = base64.b64decode(raw)
    os.makedirs(dest_dir, exist_ok=True)
    with open(path, 'wb') as f:
        f.write(data)
    print('  下载 keys/%s (%d 字节)' % (name, len(data)))
    return path


def fetch_keys():
    os.makedirs(KEYS_DIR, exist_ok=True)
    for name in KEY_FILES:
        fetch_b64(name, '%s/%s?format=TEXT' % (AOSP_KEY_BASE, name), KEYS_DIR)

    try:
        import hashlib
        from cryptography import x509
        from cryptography.hazmat.primitives.serialization import Encoding
        cert = x509.load_pem_x509_certificate(
            open(os.path.join(KEYS_DIR, 'platform.x509.pem'), 'rb').read())
        md5 = hashlib.md5(cert.public_bytes(Encoding.DER)).hexdigest()
        print('  平台证书 md5 = %s' % md5)
        if md5 != EXPECTED_CERT_MD5:
            print('  !! 与车机系统签名不一致，构建会失败')
        else:
            print('  OK 与车机系统签名一致')
    except ImportError:
        print('  （未安装 cryptography，跳过证书校验）')


os.makedirs(DL, exist_ok=True)

print('=== 下载工具链 ===')
for name, url in FILES:
    fetch(name, url)

print('=== 下载平台签名密钥 ===')
fetch_keys()

print('=== 解压 ===')
for name, dest, only in [
    ('jdk.zip', 'jdk', None),
    ('build-tools.zip', 'build-tools', None),
    ('platform-34.zip', 'platform', None),
]:
    p = os.path.join(DL, name)
    if not os.path.isfile(p):
        continue
    d = os.path.join(ENV, dest)
    if os.path.isdir(d) and os.listdir(d):
        print('  已解压 %s' % dest)
        continue
    n = unzip(p, d)
    print('  解压 %s → %s (%d 项)' % (name, dest, n))

print()
print('=== 结果 ===')
for base, dirs, files in os.walk(ENV):
    for f in files:
        if f in ('java.exe', 'javac.exe', 'd8.bat', 'zipalign.exe',
                 'apksigner.bat', 'aapt2.exe', 'android.jar', 'apktool.jar'):
            print('  %s' % os.path.join(base, f))
