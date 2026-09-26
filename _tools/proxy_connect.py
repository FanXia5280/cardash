#!/usr/bin/env python3
"""给 OpenSSH 当 ProxyCommand 用：通过本机 HTTP 代理建 CONNECT 隧道。

为什么需要它：这台机器（以及之前几台）的网络会**直接掐掉到 GitHub 的 TCP**
（22 端口 timeout、443 建连即 reset、HTTPS 同样 reset），但系统代理
（Clash 一类，通常是 `127.0.0.1:7897`）是通的 —— 浏览器能开 Google 就是这个代理。
MinGit 里没有 `connect.exe` / `nc.exe`，所以用 Python 自己搭隧道。

用法（由 `core.sshCommand` 里的 `-o ProxyCommand=` 调用，不用手动跑）：
    python proxy_connect.py <host> <port>

代理地址来自环境变量 `CD_PROXY`（缺省读注册表里的系统代理，再缺省 127.0.0.1:7897）。
"""
import os
import socket
import sys
import threading

DEFAULT_PROXY = '127.0.0.1:7897'


def read_system_proxy():
    """读 Windows 的系统代理（浏览器用的那份）。"""
    try:
        import winreg
        k = winreg.OpenKey(winreg.HKEY_CURRENT_USER,
                           r'Software\Microsoft\Windows\CurrentVersion\Internet Settings')
        enable = winreg.QueryValueEx(k, 'ProxyEnable')[0]
        server = winreg.QueryValueEx(k, 'ProxyServer')[0]
    except Exception:
        return None, None
    if not enable or not server:
        return None, None
    server = str(server).strip()
    if '=' in server:                      # "http=127.0.0.1:7897;https=..." 这种写法
        for part in server.split(';'):
            if part.lower().startswith('http='):
                server = part.split('=', 1)[1]
                break
    if ':' not in server:
        return None, None
    host, _, port = server.rpartition(':')
    return host, port


def connect_via_proxy(host, port, proxy_host, proxy_port):
    s = socket.create_connection((proxy_host, int(proxy_port)), timeout=30)
    s.sendall(('CONNECT %s:%s HTTP/1.1\r\nHost: %s:%s\r\n\r\n'
               % (host, port, host, port)).encode('ascii'))
    buf = b''
    while b'\r\n\r\n' not in buf and len(buf) < 65536:
        d = s.recv(4096)
        if not d:
            break
        buf += d
    status = buf.split(b'\r\n', 1)[0].decode('latin-1', 'replace')
    if ' 200 ' not in status:
        sys.stderr.write('proxy CONNECT failed: %s\n' % status)
        s.close()
        sys.exit(1)
    return s


def copy(src, dst, is_sock_src):
    try:
        while True:
            d = src.recv(65536) if is_sock_src else src.read(65536)
            if not d:
                break
            if is_sock_src:
                dst.write(d)
                dst.flush()
            else:
                dst.sendall(d)
    except Exception:
        pass
    try:
        dst.close()
    except Exception:
        pass


def main():
    if len(sys.argv) < 3:
        sys.stderr.write('usage: proxy_connect.py <host> <port>\n')
        return 2
    host, port = sys.argv[1], sys.argv[2]

    proxy = os.environ.get('CD_PROXY') or ''
    if proxy:
        ph, _, pp = proxy.rpartition(':')
    else:
        ph, pp = read_system_proxy()
        if not ph:
            ph, _, pp = DEFAULT_PROXY.rpartition(':')

    sock = connect_via_proxy(host, port, ph, pp)
    t = threading.Thread(target=copy, args=(sock, sys.stdout.buffer, True), daemon=True)
    t.start()
    copy(sys.stdin.buffer, sock, False)
    return 0


if __name__ == '__main__':
    sys.exit(main())
