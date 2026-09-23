package com.cardash.inject;

/** 给车机浏览器用的自检页面：打开 http://<车机IP>:8765/ 即可看到实时数据。 */
public final class StatusPage {

    private StatusPage() { }

    /** 纯文本诊断，方便手机浏览器直接看，也方便复制发出来 */
    public static String diag() {
        StringBuilder sb = new StringBuilder();
        sb.append(BridgeRuntime.heartbeatText());
        sb.append("\n---- 网络接口 ----\n");
        for (Net.Iface f : Net.interfaces()) {
            sb.append("  ").append(f.name).append(" = ").append(f.ip).append('\n');
        }
        HttpServer s = BridgeRuntime.httpServer();
        sb.append("\n---- HTTP ----\n");
        sb.append("  running = ").append(s != null && s.isRunning()).append('\n');
        sb.append("  lastError = ").append(s == null ? "-" : String.valueOf(s.lastError())).append('\n');
        return sb.toString();
    }

    public static String render() {
        StringBuilder ips = new StringBuilder();
        for (String ip : Net.ipv4Addresses()) {
            if (ips.length() > 0) ips.append('、');
            ips.append("http://").append(ip).append(':').append(BridgeRuntime.PORT);
        }
        if (ips.length() == 0) ips.append("未检测到局域网地址");

        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>CarDash 桥接</title><style>"
                + "body{margin:0;background:#111318;color:#D7DEE8;font-family:-apple-system,"
                + "'PingFang SC','Microsoft YaHei',sans-serif;padding:24px}"
                + "h1{font-size:20px;margin:0 0 6px}h2{font-size:15px;color:#8A93A0;margin:22px 0 8px}"
                + "p.tip{color:#8A93A0;font-size:13px;margin:0 0 18px}"
                + "table{border-collapse:collapse;width:100%;max-width:760px}"
                + "td{padding:8px 10px;border-bottom:1px solid #232833;font-size:15px}"
                + "td.k{color:#8A93A0;width:170px}"
                + "pre{background:#161a21;padding:14px;border-radius:8px;overflow:auto;"
                + "font-size:12px;color:#9AA3AF;max-width:760px;white-space:pre-wrap}"
                + "a{color:#7CE0A0}"
                + "</style></head><body><h1>CarDash 车机桥接</h1>"
                + "<p class=\"tip\">iPhone 端填其中一个地址即可：" + ips + "</p>"
                + "<h2>实时数据</h2><table id=\"t\"></table>"
                + "<h2>原始 JSON（出问题就把这段发出来）</h2><pre id=\"raw\"></pre>"
                + "<h2>诊断</h2><pre id=\"diag\"></pre><script>"
                + "var rows=[['车速','speed',' km/h'],['档位','gear',''],['电量','soc',' %'],"
                + "['导航','nav',''],['导航来源','navfrom','']];"
                + "async function tick(){try{"
                + "var r=await fetch('/state',{cache:'no-store'});var j=await r.json();var h='';"
                + "for(var i=0;i<rows.length;i++){var k=rows[i][0],key=rows[i][1],u=rows[i][2];"
                + "var v=j[key];"
                + "if(key==='navfrom'&&j.nav){v=j.nav.from;}"
                + ":((v.title||'--')+(v.distance?' '+v.distance:'')+(v.from?'  ['+v.from+']':''));}"
                + "h+='<tr><td class=k>'+k+'</td><td>'+(v===null||v===undefined?'--':v+u)+'</td></tr>';}"
                + "document.getElementById('t').innerHTML=h;"
                + "document.getElementById('raw').textContent=JSON.stringify(j,null,2);"
                + "}catch(e){document.getElementById('raw').textContent='连接失败: '+e;}}"
                + "async function diag(){try{var r=await fetch('/diag',{cache:'no-store'});"
                + "document.getElementById('diag').textContent=await r.text();}"
                + "catch(e){document.getElementById('diag').textContent=''+e;}}"
                + "tick();diag();setInterval(tick,500);setInterval(diag,5000);"
                + "</script></body></html>";
    }
}
