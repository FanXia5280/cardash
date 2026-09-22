package com.cardash.inject;

/** 给车机浏览器用的自检页面：打开 http://<车机IP>:8765/ 即可看到实时数据。 */
public final class StatusPage {

    private StatusPage() { }

    public static String render() {
        StringBuilder ips = new StringBuilder();
        for (String ip : Net.ipv4Addresses()) {
            if (ips.length() > 0) ips.append('、');
            ips.append("http://").append(ip).append(':').append(BridgeRuntime.PORT)
               .append("/state");
        }
        if (ips.length() == 0) ips.append("未检测到局域网地址");

        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>CarDash 桥接</title><style>"
                + "body{margin:0;background:#111318;color:#D7DEE8;font-family:-apple-system,"
                + "'PingFang SC','Microsoft YaHei',sans-serif;padding:24px}"
                + "h1{font-size:20px;margin:0 0 6px}p.tip{color:#8A93A0;font-size:13px;margin:0 0 18px}"
                + "table{border-collapse:collapse;width:100%;max-width:720px}"
                + "td{padding:8px 10px;border-bottom:1px solid #232833;font-size:15px}"
                + "td.k{color:#8A93A0;width:150px}"
                + "pre{background:#161a21;padding:14px;border-radius:8px;overflow:auto;"
                + "font-size:12px;color:#9AA3AF;max-width:720px}"
                + "</style></head><body><h1>CarDash 车机桥接</h1>"
                + "<p class=\"tip\">iPhone 端填这个地址即可：" + ips + "</p>"
                + "<table id=\"t\"></table><pre id=\"raw\"></pre><script>"
                + "var rows=[['车速','speed',' km/h'],['档位','gear',''],['电量','soc',' %'],"
                + "['续航','range',' km'],['总里程','odometer',' km'],['音乐','music',''],"
                + "['导航','nav','']];"
                + "async function tick(){try{"
                + "var r=await fetch('/state',{cache:'no-store'});var j=await r.json();var h='';"
                + "for(var i=0;i<rows.length;i++){var k=rows[i][0],key=rows[i][1],u=rows[i][2];"
                + "var v=j[key];"
                + "if(v&&typeof v==='object'){v=(key==='music')?((v.title||'--')+' / '+(v.artist||''))"
                + ":((v.title||'--')+(v.distance?' '+v.distance:''));}"
                + "h+='<tr><td class=k>'+k+'</td><td>'+(v===null||v===undefined?'--':v+u)+'</td></tr>';}"
                + "document.getElementById('t').innerHTML=h;"
                + "document.getElementById('raw').textContent=JSON.stringify(j,null,2);"
                + "}catch(e){document.getElementById('raw').textContent='连接失败: '+e;}}"
                + "tick();setInterval(tick,500);</script></body></html>";
    }
}
