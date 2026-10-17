package com.panlocal.tool;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Prototype for the user's own-drive mode.
 * App key and secret are configured only on the user's own server.
 * Baidu access/refresh tokens NEVER leave that server.
 * A random, per-login bridge session is kept in memory only.
 */
public final class MyDriveActivity extends Activity {
    private static final String ORIGIN="https://899yx.online";
    private static final String API="/api/panlocal/";
    private String sessionId="",sessionToken="";
    private LinearLayout root,files;
    private TextView status;
    private EditText query;
    private boolean waiting=false;
    private int dp(float n){return (int)(getResources().getDisplayMetrics().density*n+0.5f);}
    private LinearLayout.LayoutParams lp(int t){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(t);return p;
    }
    private TextView label(String text,int size){
        TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setTextColor(0xff14264c);return t;
    }
    private Button button(String s,LinearLayout parent){
        Button b=new Button(this);b.setAllCaps(false);b.setText(s);
        parent.addView(b,lp(8));return b;
    }
    private void show(String text){runOnUiThread(()->status.setText(text));}
    private void inform(String msg){runOnUiThread(()->Toast.makeText(this,msg,Toast.LENGTH_LONG).show());}
    private interface BackgroundJob { void execute() throws Exception; }
    private void run(String action,BackgroundJob task){
        if(waiting)return;
        waiting=true;
        new Thread(()->{
            try{task.execute();}
            catch(Exception e){
                DiagnosticLog.event("DRIVE",action+" fail="+e.getClass().getSimpleName());
                show(action+"失败："+(e instanceof java.io.IOException?"服务器或网络不可达，请先部署并检查OAuth服务":e.getMessage()));
            }finally{waiting=false;}
        },"panlocal-drive").start();
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        DiagnosticLog.init(getApplicationContext());
        getWindow().setStatusBarColor(0xff152442);
        getWindow().setNavigationBarColor(0xff152442);
        ScrollView scroll=new ScrollView(this);
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18),dp(18),dp(18),dp(26));
        root.setBackgroundColor(0xfff3f6fa);
        scroll.addView(root);setContentView(scroll);
        root.addView(label("PanLocal V0.8 · 我的网盘（测试入口）",20),lp(0));
        root.addView(label("此模式使用你的新加坡服务器完成百度 OAuth 授权；服务器未部署前不能登录。SecretKey 和百度访问令牌不会放进 APK。",13),lp(10));
        status=label("状态：未连接。先检查回调服务。",14);root.addView(status,lp(12));
        Button health=button("1. 检查 OAuth 服务是否在线",root);
        health.setOnClickListener(v->run("健康检查",()->{
            JSONObject res=request("health",null);
            DiagnosticLog.event("DRIVE","health ok="+res.optBoolean("ok",false));
            show(res.optBoolean("ok",false)?"授权服务在线（不等于百度账号已授权）":"服务器响应异常");
        }));
        Button authorize=button("2. 打开百度官方授权",root);
        authorize.setOnClickListener(v->run("启动授权",()->{
            JSONObject res=request("start",new JSONObject());
            String url=res.optString("authorize_url","");
            URL official=new URL(url);
            if(!"https".equals(official.getProtocol())||!"openapi.baidu.com".equalsIgnoreCase(official.getHost()))
                throw new Exception("授权地址校验失败");
            String id=res.optString("session_id"),token=res.optString("session_token");
            if(id.length()<20||token.length()<20)throw new Exception("服务器没有返回安全会话");
            sessionId=id;sessionToken=token;
            DiagnosticLog.event("DRIVE","session created=true oauthHost=openapi.baidu.com");
            runOnUiThread(()->{
                show("授权页面即将打开。完成百度授权后返回此 APP，点击第3步。");
                try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}
                catch(Exception e){show("手机没有可用浏览器");}
            });
        }));
        Button check=button("3. 返回后检查授权结果",root);
        check.setOnClickListener(v->run("授权检查",()->{
            JSONObject res=request("status",session());
            String phase=res.optString("status","unknown");
            DiagnosticLog.event("DRIVE","status="+(phase.equals("ready")?"ready":phase.equals("pending")?"pending":"other"));
            if(!phase.equals("ready")){show("授权状态："+phase+"。请完成官方网页的授权，或重新开始。");return;}
            JSONObject info=request("account",session());
            DiagnosticLog.event("DRIVE","account errno="+info.optInt("errno",-999)+" ok="+info.optBoolean("ok",false));
            show(info.optBoolean("ok",false)?"百度个人网盘授权已验证，可以搜索文件。":"令牌已取得，但个人网盘 API 无法验证；可能缺少权限。");
        }));
        root.addView(label("文件名关键词（只会发送到你自己的服务器及百度官方接口）",13),lp(18));
        query=new EditText(this);query.setSingleLine(true);query.setHint("例如 视频 或 MP4");
        root.addView(query,lp(7));
        Button search=button("4. 搜索我的网盘文件",root);
        search.setOnClickListener(v->search());
        files=new LinearLayout(this);files.setOrientation(LinearLayout.VERTICAL);
        root.addView(files,lp(8));
        Button logout=button("清除本机授权会话",root);
        logout.setOnClickListener(v->run("断开授权",()->{
            if(!sessionId.isEmpty())try{request("logout",session());}catch(Exception ignored){}
            sessionId="";sessionToken="";
            DiagnosticLog.event("DRIVE","local session cleared");
            runOnUiThread(()->{files.removeAllViews();show("本机会话已清除；若服务端不可达，可能需等待其自动过期。");});
        }));
        root.addView(label("状态：P1 接口集成中，尚未在服务器部署，也未验证个人文件 dlink。公开代码不含 SecretKey。返回首页可继续使用旧分享模式及 TXT 日志。",12),lp(14));
    }
    private JSONObject session()throws Exception{
        if(sessionId.isEmpty()||sessionToken.isEmpty())throw new Exception("请先点击“打开百度官方授权”");
        return new JSONObject().put("session_id",sessionId).put("session_token",sessionToken);
    }
    private JSONObject request(String route,JSONObject data)throws Exception{
        if(!route.matches("[a-z]+"))throw new Exception("非法接口名称");
        URL url=new URL(ORIGIN+API+route);
        HttpURLConnection c=(HttpURLConnection)url.openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(10000);c.setReadTimeout(15000);
        c.setRequestProperty("Accept","application/json");
        try{
            if(data!=null){
                c.setRequestMethod("POST");c.setDoOutput(true);
                c.setRequestProperty("Content-Type","application/json");
                try(OutputStream o=c.getOutputStream()){o.write(data.toString().getBytes(StandardCharsets.UTF_8));}
            }
            int statusCode=c.getResponseCode();
            DiagnosticLog.event("DRIVE","backend "+route+" http="+statusCode);
            if(statusCode!=200)throw new Exception("HTTP "+statusCode);
            try(InputStream input=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buf=new byte[8192];int n;
                while((n=input.read(buf))!=-1){
                    if(out.size()+n>200000)throw new Exception("响应数据过大");
                    out.write(buf,0,n);
                }
                return new JSONObject(out.toString("UTF-8"));
            }
        }finally{c.disconnect();}
    }
    private void search(){
        final String keyword=query.getText().toString().trim();
        if(keyword.isEmpty()){inform("请输入关键词");return;}
        run("搜索文件",()->{
            JSONObject req=session().put("key",keyword);
            JSONObject response=request("search",req);
            int errno=response.optInt("errno",-999);
            JSONArray list=response.optJSONArray("files");
            int n=list==null?0:list.length();
            DiagnosticLog.event("DRIVE","search errno="+errno+" count="+n);
            runOnUiThread(()->{
                files.removeAllViews();
                show(errno==0?"返回 "+n+" 项文件，请确认原始文件路径。":"个人文件搜索接口 errno="+errno);
                if(errno!=0||list==null)return;
                for(int i=0;i<list.length();i++){
                    JSONObject entry=list.optJSONObject(i);if(entry==null)continue;
                    String id=entry.optString("fs_id");
                    boolean dir=entry.optInt("isdir",0)!=0;
                    String name=entry.optString("name"),path=entry.optString("path");
                    long size=entry.optLong("size",0);
                    TextView view=label((dir?"目录：":"文件：")+name+"\n路径："+path+"\n大小："+String.format(Locale.US,"%.2f MiB",size/1048576d),13);
                    files.addView(view,lp(12));
                    if(dir)continue;
                    Button get=button("请求此文件的下载地址",files);
                    get.setOnClickListener(v->metas(id));
                }
            });
        });
    }
    private void metas(String id){
        run("获取文件元信息",()->{
            JSONObject info=request("filemetas",session().put("fs_id",id));
            int errno=info.optInt("errno",-999);
            String link=info.optString("dlink","");
            DiagnosticLog.event("DRIVE","filemetas errno="+errno+" dlinkPresent="+!link.isEmpty());
            if(errno!=0||link.isEmpty()){show("没有取得下载地址，errno="+errno+"；可导出TXT日志。");return;}
            runOnUiThread(()->{
                show("百度个人文件接口返回 HTTPS dlink，但尚未验证可下载性、限速及大文件断点续传。");
                Button copy=button("复制本次下载地址（请注意保密）",files);
                copy.setOnClickListener(v->{
                    ClipboardManager cb=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                    if(cb!=null)cb.setPrimaryClip(ClipData.newPlainText("PanLocal Download",link));
                    inform("已复制。链接可能含临时授权信息，请勿公开转发。");
                });
            });
        });
    }
}
