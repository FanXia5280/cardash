#!/usr/bin/env python3
"""准备用这个文件夹里的便携 Git 推送仓库到 GitHub。

做三件事：
  1. 确保 `_env/git` 有 MinGit（含 ssh.exe），没有就下载；
  2. 把仓库 `CarDash/.git/config` 的 `core.sshCommand` 指向本文件夹里的
     `_env/ssh/cardash_ed25519`（绝对路径按当前位置算，所以换电脑后重跑一次即可）；
  3. 用 `git ls-remote` 实际验证一次能不能认证到 GitHub。

用法（在 IPA 根目录下）：
  python _tools/setup_git.py
"""
import json
import os
import subprocess
import sys
import time
import urllib.request
import zipfile

IPA = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENV = os.path.join(IPA, '_env')
GIT_DIR = os.path.join(ENV, 'git')
DL = os.path.join(ENV, 'dl')
REPO = os.path.join(IPA, 'CarDash')

SSH_EXE = os.path.join(GIT_DIR, 'usr', 'bin', 'ssh.exe')
SSH_KEY = os.path.join(ENV, 'ssh', 'cardash_ed25519')
KNOWN_HOSTS = os.path.join(ENV, 'ssh', 'known_hosts')

# GitHub 的 22 端口在有些网络里直接被封（2026-09-24 在这台机器上实测：22 一路
# `Connection timed out`，而 ssh.github.com:443 秒通）。所以固定走官方的 443 备用入口：
# 命令行主机名保持 github.com（remote URL 不用改），靠 Hostname/Port 转过去。
# ⚠️ 非 22 端口的 host key 在 known_hosts 里按 `[主机名]:端口` 记，所以下面要顺带
#    把 `[github.com]:443` 补上（和 github.com 是同一批服务器、同一把密钥）。
SSH_HOST = 'ssh.github.com'
SSH_PORT = '443'
PORT443_ALIAS = '[github.com]:443'

MIRRORS = [
    ('{base}/git/releases/download/{tag}/{asset}',
     ['https://github.com']),
    ('{base}/git-for-windows/{tag}/{asset}',
     ['https://mirrors.huaweicloud.com', 'https://registry.npmmirror.com/-/binary']),
]


def http_get(url, timeout=60):
    return urllib.request.urlopen(
        urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'}),
        timeout=timeout)


def latest_asset():
    """从 GitHub API 找最新的 MinGit 64 位 zip。"""
    try:
        d = json.load(http_get(
            'https://api.github.com/repos/git-for-windows/git/releases/latest', 30))
        tag = d['tag_name']
        for a in d['assets']:
            n = a['name']
            if n.startswith('MinGit-') and n.endswith('-64-bit.zip'):
                return tag, n
    except Exception as e:
        print('  GitHub API 不可用（%s），改用已知版本' % type(e).__name__)
    return None, None


def download(tag, asset):
    dest = os.path.join(DL, asset)
    if os.path.isfile(dest) and os.path.getsize(dest) > 1000000:
        print('  已存在 %s (%.1f MB)' % (asset, os.path.getsize(dest) / 1048576))
        return dest

    urls = []
    if tag:
        urls.append('https://github.com/git-for-windows/git/releases/download/%s/%s'
                    % (tag, asset))
    # 镜像兜底：用同一个文件名
    for host in ('https://mirrors.huaweicloud.com',
                 'https://registry.npmmirror.com/-/binary'):
        if tag:
            urls.append('%s/git-for-windows/%s/%s' % (host, tag, asset))

    last = None
    for u in urls:
        for attempt in range(2):
            try:
                print('  尝试 %s' % u)
                r = http_get(u, 60)
                total = int(r.headers.get('Content-Length') or 0)
                got = 0
                t0 = time.time()
                with open(dest, 'wb') as f:
                    while True:
                        c = r.read(262144)
                        if not c:
                            break
                        f.write(c)
                        got += len(c)
                        if total:
                            sys.stdout.write('\r    %5.1f%%  %.1f/%.1f MB  %.1f MB/s   '
                                             % (100.0 * got / total, got / 1048576,
                                                total / 1048576,
                                                got / 1048576 / max(time.time() - t0, .01)))
                            sys.stdout.flush()
                print()
                if os.path.getsize(dest) > 1000000:
                    return dest
            except Exception as e:
                last = e
                print('\n    失败: %s: %s' % (type(e).__name__, e))
                if os.path.isfile(dest):
                    os.remove(dest)
                time.sleep(2)
    raise SystemExit('下载 MinGit 失败: %s' % last)


def ensure_git():
    git_exe = os.path.join(GIT_DIR, 'cmd', 'git.exe')
    if os.path.isfile(git_exe):
        print('Git 已就绪: %s' % git_exe)
    else:
        tag, asset = latest_asset()
        print('目标版本: %s / %s' % (tag, asset))
        if not asset:
            raise SystemExit('没能确定 MinGit 版本，请手动下载')
        path = download(tag, asset)
        print('解压到 %s ...' % GIT_DIR)
        os.makedirs(GIT_DIR, exist_ok=True)
        with zipfile.ZipFile(path) as z:
            z.extractall(GIT_DIR)

    for rel in ('cmd/git.exe', 'usr/bin/ssh.exe', 'usr/bin/ssh-keygen.exe'):
        p = os.path.join(GIT_DIR, *rel.split('/'))
        print('  %-28s %s' % (rel, 'OK' if os.path.isfile(p) else '缺失'))
    return git_exe


def fwd(path):
    return path.replace('\\', '/')


def detect_system_proxy():
    """读 Windows 系统代理（浏览器用的那份，Clash 之类；注册表里的 `ProxyServer`）。

    2026-09-25 在这台机器上踩到：网络把到 GitHub 的 TCP **整个掐掉**（22 timeout、
    443 建连即 reset、HTTPS 也 reset），但浏览器开 Google 正常 —— 因为浏览器走系统代理
    `127.0.0.1:7897`。⇒ git 也得跟浏览器走同一条路，否则永远连不上。
    """
    try:
        import winreg
        k = winreg.OpenKey(winreg.HKEY_CURRENT_USER,
                           r'Software\Microsoft\Windows\CurrentVersion\Internet Settings')
        enable = winreg.QueryValueEx(k, 'ProxyEnable')[0]
        server = winreg.QueryValueEx(k, 'ProxyServer')[0]
    except Exception:
        return None
    if not enable or not server:
        return None
    server = str(server).strip()
    if '=' in server:                       # "http=127.0.0.1:7897;https=..." 这种写法
        for part in server.split(';'):
            if part.lower().startswith('http='):
                server = part.split('=', 1)[1]
                break
    if ':' not in server:
        return None
    host, _, port = server.rpartition(':')
    return '%s:%s' % (host, port)


def ensure_known_hosts_443():
    """把 `[github.com]:443` 补进 known_hosts（密钥抄 github.com 那条，不盲信首次连接）。

    走 443 之后 ssh 会按「命令行主机名 + 非默认端口」去查 `[github.com]:443`，
    没有这条就会退化成 `accept-new` 盲信。同一批服务器密钥相同，直接复制即可。
    """
    if not os.path.isfile(KNOWN_HOSTS):
        return
    try:
        with open(KNOWN_HOSTS, encoding='utf-8') as f:
            lines = f.read().splitlines()
    except OSError as e:
        print('  读 known_hosts 失败（%s），跳过' % e)
        return
    if any(l.split()[:1] == [PORT443_ALIAS] for l in lines if l.strip()):
        return
    key = None
    for l in lines:
        p = l.split()
        if len(p) >= 3 and p[0] in ('github.com', '[ssh.github.com]:443'):
            key = p[1:]
            break
    if not key:
        print('  known_hosts 里没有 github.com 的密钥，443 首连会走 accept-new')
        return
    with open(KNOWN_HOSTS, 'a', encoding='utf-8') as f:
        f.write('%s %s\n' % (PORT443_ALIAS, ' '.join(key)))
    print('known_hosts 已补 %s（GitHub 443 入口，密钥同 github.com）' % PORT443_ALIAS)


def configure_push(git_exe, proxy=None):
    """把仓库的 core.sshCommand 指向本文件夹里的便携 ssh + 私钥。

    存的是绝对路径 —— 所以**每换一台电脑/换一个存放位置都要重跑本脚本**。
    （历史上就是这里残留了旧机器的 `d:/Desktop/IPA/...`，导致 push 报
      `Permission denied (publickey)`。）

    连法二选一（2026-09-25 起）：
      * **有系统代理**（`detect_system_proxy()` 拿到地址，浏览器能开国外站就是它）：
        走 `-o ProxyCommand=python _tools/proxy_connect.py %h %p` —— 让 ssh 跟浏览器
        走同一条隧道，直连 `github.com:22`。这条最稳。
      * **没代理**：退回「443 备用入口」（2026-09-24 加的：这台机器的网络封了 22 端口，
        `git push` 一路 `connect to host github.com port 22: Connection timed out`。
        remote URL 保持 `git@github.com:...` 不动，只让 ssh 连到 GitHub 官方的 443 入口）。
    """
    if not os.path.isfile(SSH_KEY):
        raise SystemExit('找不到私钥 %s —— 确认 _env/ssh 跟着文件夹一起拷过来了' % SSH_KEY)

    base = '%s -i %s -o IdentitiesOnly=yes -o UserKnownHostsFile=%s ' \
           '-o StrictHostKeyChecking=accept-new' % (
               fwd(SSH_EXE), fwd(SSH_KEY), fwd(KNOWN_HOSTS))
    if proxy:
        script = os.path.join(IPA, '_tools', 'proxy_connect.py')
        if not os.path.isfile(script):
            print('  ⚠️ 找不到 %s，退回 443 入口' % script)
        else:
            # ⚠️ 隧道目标固定写 `ssh.github.com:443`，不写 `%h %p`：
            #   `%h` 是 remote URL 里的原始主机名（github.com:22），而实测 Clash 这类代理
            #   对 `CONNECT github.com:22` 根本不回话（ssh 报 "proxy CONNECT failed"），
            #   443 才放行。Hostname/Port 两条保证真正握手时用的也是 443 入口。
            cmd = '%s -o Hostname=%s -o Port=%s -o ProxyCommand="%s %s %s %s"' % (
                base, SSH_HOST, SSH_PORT, fwd(sys.executable), fwd(script),
                SSH_HOST, SSH_PORT)
            print('  走系统代理 %s（跟浏览器同一条隧道，直连 github.com:22）' % proxy)
            r = subprocess.run([git_exe, '-C', REPO, 'config', '--local',
                                'core.sshCommand', cmd],
                               capture_output=True, text=True,
                               encoding='utf-8', errors='replace')
            if r.returncode != 0:
                raise SystemExit('写入 core.sshCommand 失败: %s' % (r.stderr or r.stdout))
            print('core.sshCommand 已写入 %s\\.git\\config' % REPO)
            print('  %s' % cmd)
            return

    cmd = '%s -o Hostname=%s -o Port=%s' % (base, SSH_HOST, SSH_PORT)
    r = subprocess.run([git_exe, '-C', REPO, 'config', '--local',
                        'core.sshCommand', cmd],
                       capture_output=True, text=True, encoding='utf-8', errors='replace')
    if r.returncode != 0:
        raise SystemExit('写入 core.sshCommand 失败: %s' % (r.stderr or r.stdout))
    print('core.sshCommand 已写入 %s\\.git\\config' % REPO)
    print('  %s' % cmd)


def verify(git_exe):
    print('验证 GitHub 认证 ...')
    r = subprocess.run([git_exe, '-C', REPO, 'ls-remote', 'origin',
                        '-h', 'refs/heads/main'],
                       capture_output=True, text=True, encoding='utf-8', errors='replace')
    out = ((r.stdout or '') + (r.stderr or '')).strip()
    if r.returncode == 0 and 'refs/heads/main' in out:
        print('  OK  %s' % out)
        return True
    print('  失败（退出码 %d）：\n%s' % (r.returncode, out))
    print('  私钥要先在 GitHub 账号里登记过（Settings → SSH keys）；'
          '本机那份是 cardash_ed25519.pub')
    return False


def main():
    os.makedirs(DL, exist_ok=True)
    git_exe = ensure_git()
    proxy = detect_system_proxy()
    if proxy:
        print('检测到系统代理 %s —— ssh 会走它（跟浏览器同一条隧道）' % proxy)
    ensure_known_hosts_443()
    configure_push(git_exe, proxy)
    ok = verify(git_exe)
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
