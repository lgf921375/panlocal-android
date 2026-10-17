package com.panlocal.tool;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URL;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.Locale;
import java.util.UUID;

/**
 * No-server / no-client-secret experiment. Does not register callbacks,
 * read Baidu-app cookies, or capture other apps' credentials.
 * User must explicitly paste the official OAuth result locally.
 * Access token exists only in the Activity instance's memory.
 */
public final class LocalDriveActivity extends Activity {
    private static final int NAVY=0xff14264c;
    private EditText appKey,tokenEntry,query;
    private TextView stateText;
    private LinearLayout layout,files;
    private String token="",csrf="";
    private boolean busy=false,verified=false;
    private interface Work{void run() throws Exception;}
    private int dp(int v){return (int)(getResources().getDisplayMetrics().density*v+.5f);}
    private LinearLayout.LayoutParams space(int top){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);return p;
    }
    private TextView label(String s,int size){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(NAVY);return t;
    }
    private Button button(String text,LinearLayout parent){
        Button b=new Button(this);b.setText(text);b.setAllCaps(false);
        parent.addView(b,space(10));return b;
    }
    private EditText field(String hint,LinearLayout parent,boolean secret){
        EditText e=new EditText(this);e.setTextSize(15);e.setSingleLine(true);e.setHint(hint);
        if(secret)e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        parent.addView(e,space(6));return e;
    }
    private void message(String text){runOnUiThread(()->stateText.setText(text));}
    private void work(String step,Work job){
        if(busy)return;busy=true;
        new Thread(()->{
            try{job.run();}
            catch(Exception e){
                DiagnosticLog.event("LOCALDRIVE",step+" fail="+e.getClass().getSimpleName());
                message(step+"失败；检查网盘授权或网络。错误类型："+e.getClass().getSimpleName());
            }finally{busy=false;}
        },"panlocal-no-server").start();
    }
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        DiagnosticLog.init(getApplicationContext());
        getWindow().setStatusBarColor(0xff152442);
        getWindow().setNavigationBarColor(0xff152442);
        ScrollView sc=new ScrollView(this);
        layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(18),dp(18),dp(18),dp(32));
        layout.setBackgroundColor(0xfff3f6fa);
        sc.addView(layout);setContentView(sc);
        layout.addView(label("PanLocal V0.9 · 无服务器模式（实验）",20),space(0));
        layout.addView(label("旧版百度资料曾提供免 SecretKey 的 response_type=token 授权；2026 年是否支持尚未确认。如果官方不提供令牌，这条路线暂不可用。原 V0.8 功能不受影响。",13),space(10));
        layout.addView(label("第一步：输入你自己的 App Key（不是 SecretKey）",14),space(15));
        appKey=field("从百度开放平台复制 App Key",layout,false);
        Button open=button("1. 打开百度官方免服务器授权页",layout);
        open.setOnClickListener(v->openAuthorization());
        layout.addView(label("第二步：如官方页面提供 Access Token 或包含 access_token 的地址，只粘贴到此 APP。不要发到聊天，也不要截屏发送。若仅显示 code，则当前模式无法使用。",13),space(13));
        tokenEntry=field("粘贴官方 Access Token 或完整授权结果 URL",layout,true);
        Button paste=button("2. 从手机剪贴板粘贴到输入框",layout);
        paste.setOnClickListener(v->pasteResult());
        Button verify=button("3. 验证个人网盘授权",layout);
        verify.setOnClickListener(v->verify());
        stateText=label("状态：未授权；此模式不依赖新加坡服务器。",14);
        layout.addView(stateText,space(14));
        layout.addView(label("第三步：已验证授权后搜索本人网盘文件",14),space(18));
        query=field("文件名关键词，例如 传奇",layout,false);
        Button search=button("4. 搜索我的网盘文件",layout);
        search.setOnClickListener(v->search());
        files=new LinearLayout(this);files.setOrientation(LinearLayout.VERTICAL);
        layout.addView(files,space(10));
        Button clear=button("清除本机临时授权",layout);
        clear.setOnClickListener(v->{
            token="";verified=false;csrf="";
            tokenEntry.setText("");files.removeAllViews();
            stateText.setText("已清除本机临时令牌；没有将其保存到文件。");
            DiagnosticLog.event("LOCALDRIVE","用户清除临时令牌");
        });
        layout.addView(label("隐私：只使用百度官方授权域名和网盘接口；不会读取百度 APP 登录 Cookie。Access Token 仅保留在当前界面内存，离开界面即失效。TXT 日志不记录密钥、令牌和下载地址。",12),space(17));
    }
    private static String encode(String value)throws Exception{return URLEncoder.encode(value,"UTF-8");}
    private void openAuthorization(){
        final String key=appKey.getText().toString().trim();
        if(!key.matches("[A-Za-z0-9_-]{8,128}")){stateText.setText("App Key 格式不正确；请不要填写 SecretKey。");return;}
        csrf=UUID.randomUUID().toString();
        try{
            String auth="https://openapi.baidu.com/oauth/2.0/authorize?response_type=token&client_id="
                +encode(key)+"&redirect_uri=oob&scope="+encode("basic,netdisk")
                +"&display=mobile&state="+encode(csrf);
            DiagnosticLog.event("LOCALDRIVE","发起百度官方 implicit 授权测试; 不包含 SecretKey");
            stateText.setText("已打开官方授权页。如显示 unsupported_response_type / redirect_uri_mismatch，说明此模式不受支持或不符合当前应用配置。");
            startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(auth)));
        }catch(Exception e){stateText.setText("手机没有可用的官方授权浏览器。");}
    }
    private void pasteResult(){
        ClipboardManager cm=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        if(cm==null||!cm.hasPrimaryClip()){stateText.setText("剪贴板没有内容。");return;}
        ClipData c=cm.getPrimaryClip();
        if(c!=null&&c.getItemCount()>0){
            CharSequence value=c.getItemAt(0).coerceToText(this);
            if(value!=null)tokenEntry.setText(value.toString().trim());
        }
    }
    private String parseToken(String raw)throws Exception{
        String input=raw.trim();
        if(input.length()>4096)throw new Exception("too_large");
        if(input.startsWith("https://")){
            Uri u=Uri.parse(input);
            // An oob link should be on an official Baidu host, never accept a
            // token transported by an unrelated HTTP(S) host.
            String h=u.getHost();
            if(h==null||!(h.equals("baidu.com")||h.endsWith(".baidu.com")))throw new Exception("foreign_host");
            String fragment=u.getEncodedFragment();
            String combined=(fragment==null?"":fragment)+"&"+(u.getEncodedQuery()==null?"":u.getEncodedQuery());
            String found="",receivedState="";
            for(String item:combined.split("&")){
                int at=item.indexOf('=');if(at<=0)continue;
                String k=URLDecoder.decode(item.substring(0,at),"UTF-8");
                String v=URLDecoder.decode(item.substring(at+1),"UTF-8");
                if("access_token".equals(k))found=v;
                if("state".equals(k))receivedState=v;
            }
            if(!receivedState.isEmpty()&&!receivedState.equals(csrf))throw new Exception("state_mismatch");
            input=found;
        }
        if(!input.matches("[A-Za-z0-9._~-]{10,2048}"))throw new Exception("no_valid_access_token");
        return input;
    }
    private JSONObject api(String endpoint,String... params)throws Exception{
        if(token.isEmpty())throw new Exception("missing_token");
        String target;
        if("uinfo".equals(endpoint))target="https://pan.baidu.com/rest/2.0/xpan/nas?method=uinfo";
        else if("search".equals(endpoint))target="https://pan.baidu.com/rest/2.0/xpan/file?method=search";
        else if("filemetas".equals(endpoint))target="https://pan.baidu.com/rest/2.0/xpan/multimedia?method=filemetas";
        else throw new Exception("invalid_endpoint");
        StringBuilder url=new StringBuilder(target).append("&access_token=").append(encode(token));
        for(int i=0;i+1<params.length;i+=2)url.append('&').append(encode(params[i])).append('=').append(encode(params[i+1]));
        HttpURLConnection c=(HttpURLConnection)new URL(url.toString()).openConnection();
        c.setInstanceFollowRedirects(false);c.setConnectTimeout(12000);c.setReadTimeout(18000);
        c.setRequestProperty("Accept","application/json");
        try{
            int status=c.getResponseCode();
            DiagnosticLog.event("LOCALDRIVE",endpoint+" http="+status);
            if(status!=200)throw new Exception("http_error_"+status);
            try(InputStream in=c.getInputStream();ByteArrayOutputStream b=new ByteArrayOutputStream()){
                byte[] buf=new byte[4096];int n;
                while((n=in.read(buf))!=-1){
                    if(b.size()+n>300000)throw new Exception("response_too_big");
                    b.write(buf,0,n);
                }
                return new JSONObject(b.toString("UTF-8"));
            }
        }finally{c.disconnect();}
    }
    private void verify(){
        String input=tokenEntry.getText().toString();
        String candidate;
        try{candidate=parseToken(input);}
        catch(Exception e){stateText.setText("没有识别到 Access Token。若百度仅返回 code，本模式不可用；不能在 APK 内嵌 SecretKey 来兑换。");return;}
        tokenEntry.setText("");
        token=candidate;verified=false;
        work("检查授权",()->{
            JSONObject info=api("uinfo");
            int errno=info.optInt("errno",-999);
            verified=errno==0;
            DiagnosticLog.event("LOCALDRIVE","uinfo errno="+errno+" verified="+verified);
            if(verified)message("个人网盘授权验证成功。令牌只在当前页面内存，现可搜索文件。");
            else{token="";message("百度 uinfo 未通过，errno="+errno+"。可能是令牌失效或没有网盘权限。");}
        });
    }
    private void search(){
        if(!verified){stateText.setText("请先验证授权。");return;}
        String name=query.getText().toString().trim();
        if(name.isEmpty()){stateText.setText("请输入搜索关键词。");return;}
        work("文件搜索",()->{
            JSONObject response=api("search","key",name,"recursion","1","num","30");
            JSONArray list=response.optJSONArray("list");
            int errno=response.optInt("errno",-999);
            DiagnosticLog.event("LOCALDRIVE","search errno="+errno+" count="+(list==null?0:list.length()));
            runOnUiThread(()->{
                files.removeAllViews();
                if(errno!=0){stateText.setText("个人文件搜索失败 errno="+errno);return;}
                if(list==null){stateText.setText("搜索完成，但没有返回文件列表。");return;}
                stateText.setText("返回 "+list.length()+" 项，找到原始文件后可请求 dlink。");
                for(int i=0;i<list.length();i++){
                    JSONObject row=list.optJSONObject(i);if(row==null)continue;
                    String id=row.optString("fs_id"),path=row.optString("path","");
                    String filename=row.optString("server_filename","");
                    long size=row.optLong("size",0);
                    int isdir=row.optInt("isdir",0);
                    TextView info=label(filename+"\n路径："+path+"\n大小："+String.format(Locale.US,"%.2f MiB",size/1048576d),13);
                    files.addView(info,space(13));
                    if(isdir==0&&id.matches("[0-9]{1,22}")){
                        Button get=button("请求此文件的 dlink",files);
                        get.setOnClickListener(v->requestDlink(id));
                    }
                }
            });
        });
    }
    private void requestDlink(String id){
        if(!verified){message("令牌尚未验证。");return;}
        work("文件元信息",()->{
            JSONObject response=api("filemetas","fsids","["+id+"]","dlink","1");
            int errno=response.optInt("errno",-999);
            JSONArray list=response.optJSONArray("list");
            JSONObject first=list!=null&&list.length()>0?list.optJSONObject(0):null;
            String link=first==null?"":first.optString("dlink","");
            DiagnosticLog.event("LOCALDRIVE","filemetas errno="+errno+" dlinkPresent="+link.startsWith("https://"));
            if(errno!=0||!link.startsWith("https://")){
                message("未能取得 HTTPS 下载地址：errno="+errno+"。可以保存 TXT 诊断日志。");return;
            }
            runOnUiThread(()->{
                stateText.setText("文件 API 返回 dlink，尚未验证实际下载是否成功。");
                Button copy=button("复制下载地址（敏感，勿公开分享）",files);
                copy.setOnClickListener(v->{
                    ClipboardManager clip=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                    if(clip!=null)clip.setPrimaryClip(ClipData.newPlainText("个人网盘临时地址",link));
                    Toast.makeText(this,"已复制，链接可能很快过期",Toast.LENGTH_SHORT).show();
                });
            });
        });
    }
    @Override protected void onDestroy(){
        token="";verified=false;
        super.onDestroy();
    }
}
