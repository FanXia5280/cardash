package com.cardash.solo;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 独立包（solo）的主界面。
 *
 * <p>界面上就一段文本 + 两个按钮（刷新 / 复制），另外起一个只读 HTTP 服务：
 * 手机浏览器打开 `http://<车机IP>:8766/solo` 就能拿到同一段文本（照旧"复制发回来"）。
 *
 * <p>⚠️ 端口用 **8766**，和注入包那套（8765）隔开，两个可以同时在车上跑、互不干扰。
 */
public class SoloActivity extends Activity {

    private static final int PORT = 8766;

    private TextView tv;
    private volatile String text = "正在探测…";
    private volatile boolean serverStarted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tv = new TextView(this);
        tv.setTextSize(11f);
        tv.setTextColor(Color.parseColor("#D7DEE8"));
        tv.setBackgroundColor(Color.parseColor("#111318"));
        tv.setPadding(24, 24, 24, 24);
        tv.setMovementMethod(new ScrollingMovementMethod());
        tv.setTextIsSelectable(true);

        Button refresh = new Button(this);
        refresh.setText("重新探测");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { run(); }
        });

        Button copy = new Button(this);
        copy.setText("复制全部");
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { copyAll(); }
        });

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(refresh, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(copy, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#111318"));
        root.addView(row);
        root.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        startServer();
        run();
    }

    private void run() {
        tv.setText("正在探测…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String s = SoloProbe.report(SoloActivity.this);
                text = s + "\n\n（HTTP: http://<本机IP>:" + PORT + "/solo）\n";
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() { tv.setText(text); }
                });
            }
        }, "solo-probe").start();
    }

    private void copyAll() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("CarDashSolo", text));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败: " + t, Toast.LENGTH_LONG).show();
        }
    }

    // ─────────────────────────────── 只读 HTTP（给手机浏览器看）

    private void startServer() {
        if (serverStarted) return;
        serverStarted = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                ServerSocket ss = null;
                try {
                    ss = new ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new InetSocketAddress(PORT), 8);
                } catch (Throwable e) {
                    return;
                }
                while (true) {
                    Socket sock = null;
                    try {
                        sock = ss.accept();
                        sock.setSoTimeout(3000);
                        BufferedReader r = new BufferedReader(
                                new InputStreamReader(sock.getInputStream(),
                                        StandardCharsets.US_ASCII));
                        String line = r.readLine();
                        String path = "/";
                        if (line != null) {
                            String[] parts = line.split(" ");
                            if (parts.length > 1) path = parts[1];
                        }
                        String body = path.startsWith("/solo") ? text : "ok";
                        byte[] data = body.getBytes(StandardCharsets.UTF_8);
                        String head = "HTTP/1.1 200 OK\r\n"
                                + "Content-Type: text/plain; charset=utf-8\r\n"
                                + "Content-Length: " + data.length + "\r\n"
                                + "Cache-Control: no-store\r\n"
                                + "Connection: close\r\n\r\n";
                        OutputStream out = sock.getOutputStream();
                        out.write(head.getBytes(StandardCharsets.US_ASCII));
                        out.write(data);
                        out.flush();
                    } catch (Throwable ignored) {
                        // 单个连接出错不影响服务
                    } finally {
                        if (sock != null) {
                            try { sock.close(); } catch (Throwable ignored) { }
                        }
                    }
                }
            }
        }, "solo-http");
        t.setDaemon(true);
        t.start();
    }

    @SuppressWarnings("unused")
    private static String addrHint() {
        return String.format(Locale.US, "%d", PORT);
    }
}
