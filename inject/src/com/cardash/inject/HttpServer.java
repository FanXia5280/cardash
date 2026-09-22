package com.cardash.inject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** 极简 HTTP 服务器：只处理 GET，短连接。 */
public final class HttpServer {

    public interface Handler {
        /**
         * 返回响应体；返回 null 表示 404。
         *
         * @param path  去掉查询串的路径，例如 /scan
         * @param query 查询串，没有则为空串，例如 all=1&amp;km=500
         */
        String handle(String path, String query);
    }

    private final int port;
    private final Handler handler;

    private volatile boolean running;
    private volatile ServerSocket server;
    private volatile String lastError;
    private Thread acceptThread;

    public HttpServer(int port, Handler handler) {
        this.port = port;
        this.handler = handler;
    }

    public boolean isRunning() {
        return running && server != null && !server.isClosed();
    }

    public String lastError() { return lastError; }

    public void start() {
        if (running) return;
        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() { acceptLoop(); }
        }, "cardash-http");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void stop() {
        running = false;
        closeQuietly();
    }

    private void closeQuietly() {
        ServerSocket s = server;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(port), 32);
                server = ss;
                lastError = null;

                while (running) {
                    final Socket sock = ss.accept();
                    Thread t = new Thread(new Runnable() {
                        @Override
                        public void run() { serve(sock); }
                    }, "cardash-http-conn");
                    t.setDaemon(true);
                    t.start();
                }
            } catch (IOException e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            } finally {
                closeQuietly();
            }

            if (!running) return;
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void serve(Socket sock) {
        try (Socket s = sock) {
            s.setSoTimeout(4000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));

            String requestLine = reader.readLine();
            if (requestLine == null) return;

            int guard = 0;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty() && guard++ < 64) {
                // 丢弃请求头
            }

            String[] parts = requestLine.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            String query = "";
            int q = path.indexOf('?');
            if (q >= 0) {
                query = path.substring(q + 1);
                path = path.substring(0, q);
            }

            String body = handler.handle(path, query);
            if (body == null) {
                respond(s, 404, "text/plain; charset=utf-8", "not found");
            } else if ("/health".equals(path)) {
                respond(s, 200, "text/plain; charset=utf-8", body);
            } else if ("/".equals(path) || "/index.html".equals(path)) {
                respond(s, 200, "text/html; charset=utf-8", body);
            } else {
                respond(s, 200, "application/json; charset=utf-8", body);
            }
        } catch (Exception ignored) {
            // 单个连接出错不影响服务
        }
    }

    private void respond(Socket sock, int code, String contentType, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "Not Found") + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        OutputStream out = sock.getOutputStream();
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(data);
        out.flush();
    }
}
