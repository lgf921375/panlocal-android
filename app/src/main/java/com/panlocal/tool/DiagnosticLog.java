package com.panlocal.tool;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Local, opt-in diagnostic trace. No network upload, no response bodies, no
 * cookies, passwords, auth tokens, share URLs, file names or download URLs.
 * Only call with controlled stage names, flags, counts, and HTTP status codes.
 */
public final class DiagnosticLog {
    private static final ArrayDeque<String> lines=new ArrayDeque<>();
    private static boolean recording=false;
    private static int seq=0;
    private DiagnosticLog(){}
    public static synchronized void start(){
        recording=true;
        lines.clear();
        seq=0;
        write("诊断","PanLocal V0.6 开始记录；日志仅在本机内存，主动分享才会发送");
    }
    public static synchronized void stop(){
        write("诊断","用户停止记录");
        recording=false;
    }
    public static synchronized boolean isRecording(){return recording;}
    private static String scrub(String s){
        if(s==null)return "";
        String r=s.replaceAll("(?i)https?://[^\\s]+","[URL已省略]");
        r=r.replaceAll("(?i)(cookie|token|sign|sekey|pwd|password|bduss|stoken)\\s*[:=]\\s*[^\\s;]+","$1=[敏感值已省略]");
        r=r.replace('\n',' ').replace('\r',' ');
        return r.length()>220?r.substring(0,220)+"…":r;
    }
    private static void write(String stage,String info){
        String time=new SimpleDateFormat("HH:mm:ss.SSS",Locale.US).format(new Date());
        lines.addLast(String.format(Locale.US,"%03d %s [%s] %s",++seq,time,scrub(stage),scrub(info)));
        while(lines.size()>320)lines.removeFirst();
    }
    public static synchronized void event(String stage,String info){
        if(recording)write(stage,info);
    }
    public static synchronized String all(){
        StringBuilder b=new StringBuilder();
        b.append("PanLocal 本地诊断记录\n");
        b.append("隐私：不包含登录凭据、文件名、完整分享链接或下载地址。\n");
        b.append("备注：只能跟踪本程序核心请求与官方页面主框架，不能看到所有第三方资源请求。\n\n");
        if(lines.isEmpty())b.append("尚无诊断记录。请先点击开始诊断。\n");
        for(String line:lines)b.append(line).append('\n');
        return b.toString();
    }
    public static synchronized String tail(int count){
        if(lines.isEmpty())return "请先点击“开始诊断”，然后按顺序完成解析、官方登录及直链请求。";
        int skip=Math.max(0,lines.size()-count),i=0;
        StringBuilder b=new StringBuilder();
        if(skip>0)b.append("（以下为最后 ").append(count).append(" 条，复制/分享可得到全部记录）\n");
        for(String line:lines)if(i++>=skip)b.append(line).append('\n');
        return b.toString();
    }
    public static String endpoint(java.net.URL url){
        String p=url.getPath();
        if(p.startsWith("/s/"))return "share-page";
        if(p.equals("/share/verify"))return "share-verify";
        if(p.equals("/share/list"))return "share-list";
        if(p.equals("/share/tplconfig"))return "share-tplconfig";
        if(p.equals("/api/sharedownload"))return "sharedownload";
        return "other-baidu-api";
    }
    public static String listType(Object o){
        if(o==null||o==org.json.JSONObject.NULL)return "none";
        if(o instanceof org.json.JSONArray)return "array";
        if(o instanceof org.json.JSONObject)return "object";
        if(o instanceof String)return "string";
        return "other";
    }
    public static String webHost(android.net.Uri uri){
        if(uri==null)return "unknown";
        String host=uri.getHost();
        if(host==null)return "unknown";
        return host.endsWith(".baidu.com")||host.equals("baidu.com")?host:"other-host";
    }
}
