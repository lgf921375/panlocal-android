package com.panlocal.tool;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.content.pm.PackageManager;
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
 * Single-user no-server OAuth compatibility experiment.
 * Own-device app key and SecretKey can be saved with Android Keystore AES-GCM.
 * No real SecretKey is compiled into the APK or uploaded to public GitHub.
 * The one-time code and access token are not persisted in this Activity.
 * Do not use for third-party shared accounts or public distributions.
 */
public final class LocalDriveActivity extends Activity {
    private static final int NAVY=0xff14264c;
    private static final String DEFAULT_APP_KEY="cFo2NH2NGbaYBI9m2GC95HW4rppJMbHv"; // OAuth public client identifier
    private EditText appKey,tokenEntry,query,authCodeEntry,secretEntry,dlinkInput;
    private LinearLayout setupArea,authorizationArea,advancedArea;
    private TextView stateText;
    private LinearLayout layout,files;
    private TextView downloadInfo;
    private Button downloadControl;
    private final Handler progressHandler=new Handler(Looper.getMainLooper());
    private boolean watchingProgress=false;
    private static final int SAVE_DOWNLOADED_FILE=6127, NOTIFY_PERMISSION=6128;
    private String pendingFileId,pendingFileName,pendingAccessToken;
    private long pendingFileBytes;
    private Uri pendingSaveUri;
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
        layout.setPadding(dp(18),dp(18),dp(18),dp(30));
        layout.setBackgroundColor(0xfff3f6fa);
        sc.addView(layout);setContentView(sc);
        layout.addView(label("PanLocal V1.5 · 我的网盘",22),space(0));
        layout.addView(label("首次保存开发者密钥并授权一次。以后自动恢复账号、搜索文件，无需每次输入密钥或授权码（仅当百度授权失效时需要重新授权）。",13),space(8));
        stateText=label("正在检查本机已保存的授权…",13);
        layout.addView(stateText,space(12));

        setupArea=new LinearLayout(this);
        setupArea.setOrientation(LinearLayout.VERTICAL);
        layout.addView(setupArea,space(15));
        setupArea.addView(label("首次设置（仅一次）",17),space(0));
        appKey=field("百度开发者 App Key",setupArea,false);
        String key=LocalCredentialVault.savedAppKey(this);
        appKey.setText(key.isEmpty()?DEFAULT_APP_KEY:key);
        secretEntry=field("新的 SecretKey（本机加密保存）",setupArea,true);
        Button save=button("保存开发者密钥",setupArea);
        save.setOnClickListener(v->saveOwnerCredentials());
        if(LocalCredentialVault.hasSavedSecret(this))setupArea.setVisibility(android.view.View.GONE);
        Button settings=button("修改 / 删除本机密钥",layout);
        settings.setOnClickListener(v->{
            setupArea.setVisibility(setupArea.getVisibility()==android.view.View.VISIBLE?
                android.view.View.GONE:android.view.View.VISIBLE);
        });

        authorizationArea=new LinearLayout(this);
        authorizationArea.setOrientation(LinearLayout.VERTICAL);
        layout.addView(authorizationArea,space(15));
        authorizationArea.addView(label("登录（仅首次或授权失效时）",17),space(0));
        Button authorize=button("① 打开百度官方授权",authorizationArea);
        authorize.setOnClickListener(v->openCodeAuthorization());
        authCodeEntry=field("粘贴一次性授权码 code",authorizationArea,true);
        Button paste=button("② 粘贴 code 并登录",authorizationArea);
        paste.setOnClickListener(v->pasteCodeAndLogin());

        layout.addView(label("搜索我的网盘文件",17),space(18));
        query=field("输入视频名称关键词",layout,false);
        query.setText("传奇");
        Button search=button("搜索文件",layout);
        search.setOnClickListener(v->search());
        files=new LinearLayout(this);files.setOrientation(LinearLayout.VERTICAL);
        layout.addView(files,space(8));
        layout.addView(label("下载到手机",17),space(18));
        downloadInfo=label("选择文件后点“下载到手机”，即可在通知栏查看进度。大文件建议预留至少 3 GiB 空间。",13);
        layout.addView(downloadInfo,space(7));
        downloadControl=button("暂停下载",layout);
        downloadControl.setVisibility(android.view.View.GONE);
        downloadControl.setOnClickListener(v->{
            String action="paused".equals(BaiduDownloadService.state)?
                BaiduDownloadService.ACTION_RESUME:BaiduDownloadService.ACTION_PAUSE;
            startService(new Intent(this,BaiduDownloadService.class).setAction(action));
        });

        Button options=button("高级功能 / 诊断 ▼",layout);
        advancedArea=new LinearLayout(this);
        advancedArea.setOrientation(LinearLayout.VERTICAL);
        advancedArea.setVisibility(android.view.View.GONE);
        layout.addView(advancedArea,space(10));
        options.setOnClickListener(v->{
            boolean show=advancedArea.getVisibility()!=android.view.View.VISIBLE;
            advancedArea.setVisibility(show?android.view.View.VISIBLE:android.view.View.GONE);
            options.setText(show?"收起高级功能 ▲":"高级功能 / 诊断 ▼");
        });
        advancedArea.addView(label("已有下载链接：只验证首字节，不下载整个文件",13),space(3));
        dlinkInput=field("本机粘贴临时 dlink",advancedArea,true);
        Button direct=button("检测这个下载地址",advancedArea);
        direct.setOnClickListener(v->{
            String value=dlinkInput.getText().toString().trim();dlinkInput.setText("");
            checkDlink(value);
        });
        Button logout=button("退出网盘账号（清除已保存的授权）",advancedArea);
        logout.setOnClickListener(v->{
            token="";verified=false;
            LocalCredentialVault.clearOAuth(this);
            authorizationArea.setVisibility(android.view.View.VISIBLE);
            files.removeAllViews();
            stateText.setText("账号授权已清除，开发者密钥仍保存在本机。");
            DiagnosticLog.event("LOCALDRIVE","savedOAuthCleared=true");
        });
        Button delete=button("删除这台手机保存的开发者密钥",advancedArea);
        delete.setOnClickListener(v->clearOwnerCredentials());

        // Historical implicit-grant controls kept here only for old compatibility testing.
        Button oldAuthorize=button("旧版 OAuth 兼容测试（不推荐）",advancedArea);
        oldAuthorize.setOnClickListener(v->openAuthorization());
        tokenEntry=field("旧版授权返回的 Token",advancedArea,true);
        Button oldToken=button("验证旧版 Token",advancedArea);
        oldToken.setOnClickListener(v->verify());

        layout.addView(label("下载检测会自动附带 OAuth 令牌和 User-Agent。原始 dlink 在普通浏览器可能无法直接下载；令牌不会出现在复制链接和日志中。",12),space(15));
        if(!LocalCredentialVault.hasSavedSecret(this)){
            stateText.setText("首次使用：填入 SecretKey 点“保存”，再授权一次。App Key 已预填，可核对是否正确。");
        }else{
            stateText.setText("已加密保存开发者密钥；正在检查能否自动登录…");
            restoreLogin();
        }
    }
    private void pasteCodeAndLogin(){
        if(authCodeEntry.getText().length()==0){
            ClipboardManager cm=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
            if(cm!=null&&cm.hasPrimaryClip()){
                ClipData clip=cm.getPrimaryClip();
                if(clip!=null&&clip.getItemCount()>0){
                    CharSequence text=clip.getItemAt(0).coerceToText(this);
                    if(text!=null)authCodeEntry.setText(text.toString().trim());
                }
            }
        }
        exchangeCode();
    }
    private JSONObject refreshOAuth(String app,String oldRefresh) throws Exception{
        String secret=LocalCredentialVault.secretFor(this,app);
        if(secret.isEmpty()||oldRefresh==null||oldRefresh.isEmpty())throw new Exception("missing_refresh_credentials");
        URL address=new URL("https://openapi.baidu.com/oauth/2.0/token?grant_type=refresh_token"
            +"&client_id="+encode(app)+"&client_secret="+encode(secret)
            +"&refresh_token="+encode(oldRefresh));
        HttpURLConnection c=(HttpURLConnection)address.openConnection();
        c.setInstanceFollowRedirects(false);c.setConnectTimeout(12000);c.setReadTimeout(18000);
        try{
            int status=c.getResponseCode();
            if(status!=200){
                DiagnosticLog.event("LOCALDRIVE","refreshHttp="+status);
                throw new Exception("refresh_http_"+status);
            }
            try(InputStream in=c.getInputStream();ByteArrayOutputStream data=new ByteArrayOutputStream()){
                byte[] buf=new byte[4096];int n;
                while((n=in.read(buf))!=-1){
                    if(data.size()+n>32768)throw new Exception("refresh_too_big");
                    data.write(buf,0,n);
                }
                JSONObject res=new JSONObject(data.toString("UTF-8"));
                String access=res.optString("access_token","");
                if(access.isEmpty())throw new Exception("refresh_rejected");
                String renew=res.optString("refresh_token",oldRefresh);
                long exp=System.currentTimeMillis()+Math.max(60L,res.optLong("expires_in",3600L)-180L)*1000L;
                LocalCredentialVault.saveOAuth(this,app,access,renew,exp);
                DiagnosticLog.event("LOCALDRIVE","refreshSucceeded=true");
                return res;
            }
        }finally{c.disconnect();}
    }
    private void restoreLogin(){
        final String app=appKey.getText().toString().trim();
        work("自动恢复登录",()->{
            JSONObject stored=LocalCredentialVault.loadOAuth(this,app);
            if(stored==null||stored.optString("access","").isEmpty()){
                message("开发者密钥已保存。首次请点击“打开百度官方授权”，完成一次授权。");
                return;
            }
            String access=stored.optString("access","");
            String refresh=stored.optString("refresh","");
            long expiry=stored.optLong("expiry",0);
            if(expiry<System.currentTimeMillis()+60000L){
                if(refresh.isEmpty()){
                    LocalCredentialVault.clearOAuth(this);
                    message("授权已过期，需要再登录一次。开发者密钥无需重填。");
                    return;
                }
                try{
                    JSONObject renewed=refreshOAuth(app,refresh);
                    access=renewed.optString("access_token","");
                }catch(Exception e){
                    DiagnosticLog.event("LOCALDRIVE","autoRefreshFailed="+e.getClass().getSimpleName());
                    LocalCredentialVault.clearOAuth(this);
                    message("自动续期失败，需要重新授权一次；开发者密钥仍保存在手机。");
                    return;
                }
            }
            token=access;
            JSONObject account=api("uinfo");
            int errno=account.optInt("errno",-999);
            if(errno!=0&&!refresh.isEmpty()){
                try{
                    JSONObject renewed=refreshOAuth(app,refresh);
                    token=renewed.optString("access_token","");
                    account=api("uinfo");
                    errno=account.optInt("errno",-999);
                }catch(Exception e){
                    DiagnosticLog.event("LOCALDRIVE","retryRefreshFailed="+e.getClass().getSimpleName());
                }
            }
            verified=errno==0;
            DiagnosticLog.event("LOCALDRIVE","restoreAuthorized="+verified+" errno="+errno);
            if(verified){
                runOnUiThread(()->authorizationArea.setVisibility(android.view.View.GONE));
                message("已自动恢复百度网盘登录。可以直接搜索文件。");
            }else{
                token="";
                LocalCredentialVault.clearOAuth(this);
                message("旧授权不可用，请重新授权一次；开发者密钥无需重填。");
            }
        });
    }
    private void saveOwnerCredentials(){
        final String key=appKey.getText().toString().trim();
        final String secret=secretEntry.getText().toString().trim();
        secretEntry.setText("");
        try{
            LocalCredentialVault.save(this,key,secret);
            DiagnosticLog.event("LOCALDRIVE","developerCredentialsSavedLocally=true (no values)");
            setupArea.setVisibility(android.view.View.GONE);
            authorizationArea.setVisibility(android.view.View.VISIBLE);
            stateText.setText("开发者密钥已保存。首次请点击“打开百度官方授权”，后续会自动恢复登录。");
        }catch(Exception e){
            DiagnosticLog.event("LOCALDRIVE","developerCredentialsSaveFailed="+e.getClass().getSimpleName());
            stateText.setText("本机保存失败，请检查输入格式或设备密钥库。不会保存明文；错误类型："+e.getClass().getSimpleName());
        }
    }
    private void clearOwnerCredentials(){
        try{
            LocalCredentialVault.clear(this);
            token="";verified=false;
            appKey.setText(DEFAULT_APP_KEY);
            secretEntry.setText("");
            setupArea.setVisibility(android.view.View.VISIBLE);
            authorizationArea.setVisibility(android.view.View.VISIBLE);
            DiagnosticLog.event("LOCALDRIVE","developerCredentialsCleared=true");
            files.removeAllViews();
            stateText.setText("本机已清除所有已保存的开发者密钥和网盘授权。");
        }catch(Exception e){
            DiagnosticLog.event("LOCALDRIVE","developerCredentialsClearFailed="+e.getClass().getSimpleName());
            stateText.setText("本机密钥清除失败，请尝试清除 APP 数据；错误类型："+e.getClass().getSimpleName());
        }
    }
    private void openCodeAuthorization(){
        String key=appKey.getText().toString().trim();
        if(!key.matches("[A-Za-z0-9_-]{8,128}")){stateText.setText("请先填入正确的 App Key，而不是 SecretKey。");return;}
        csrf=UUID.randomUUID().toString();
        try{
            String auth="https://openapi.baidu.com/oauth/2.0/authorize?response_type=code&client_id="
                +encode(key)+"&redirect_uri=oob&scope="+encode("basic,netdisk")
                +"&display=mobile&state="+encode(csrf);
            DiagnosticLog.event("LOCALDRIVE","百度官方 code 授权启动, redirect=oob, without-secret=true");
            stateText.setText("已打开官方授权页。授权成功后复制 code 回来；若出现错误，只反馈错误名称，不提供 code 或 SecretKey。");
            startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(auth)));
        }catch(Exception e){stateText.setText("不能打开百度官方授权页面");}
    }
    private void exchangeCode(){
        final String key=appKey.getText().toString().trim();
        final String code=authCodeEntry.getText().toString().trim();
        String suppliedSecret=secretEntry.getText().toString().trim();
        authCodeEntry.setText("");
        secretEntry.setText("");
        if(suppliedSecret.isEmpty()){
            try{
                suppliedSecret=LocalCredentialVault.secretFor(this,key);
            }catch(Exception e){
                DiagnosticLog.event("LOCALDRIVE","developerCredentialDecryptFailed="+e.getClass().getSimpleName());
                stateText.setText("本机密钥不可解密。请删除保存的密钥，重新输入已轮换的 SecretKey。");
                return;
            }
        }
        final String secret=suppliedSecret;
        if(!key.matches("[A-Za-z0-9_-]{8,128}")||!code.matches("[A-Za-z0-9._-]{8,512}")||!secret.matches("[A-Za-z0-9._~-]{12,512}")){
            stateText.setText("App Key、code 或 SecretKey 格式错误。请确认是百度官方签发的授权码以及已轮换过的密钥。");return;
        }
        token="";verified=false;
        work("官方授权码兑换",()->{
            String url="https://openapi.baidu.com/oauth/2.0/token?grant_type=authorization_code"
                +"&client_id="+encode(key)+"&client_secret="+encode(secret)+"&redirect_uri=oob&code="+encode(code);
            HttpURLConnection con=(HttpURLConnection)new URL(url).openConnection();
            con.setInstanceFollowRedirects(false);
            con.setConnectTimeout(12000);con.setReadTimeout(18000);
            con.setRequestProperty("Accept","application/json");
            try{
                int http=con.getResponseCode();
                InputStream in=http==200?con.getInputStream():con.getErrorStream();
                if(in==null){DiagnosticLog.event("LOCALDRIVE","code-exchange http="+http+" no-body");message("百度令牌接口 HTTP "+http+"；未取得 Token。");return;}
                ByteArrayOutputStream out=new ByteArrayOutputStream();
                try(InputStream stream=in){
                    byte[] buf=new byte[4096];int n;
                    while((n=stream.read(buf))!=-1){
                        if(out.size()+n>32768)throw new Exception("response_too_big");
                        out.write(buf,0,n);
                    }
                }
                JSONObject response=new JSONObject(out.toString("UTF-8"));
                String error=response.optString("error","");
                String received=response.optString("access_token","");
                DiagnosticLog.event("LOCALDRIVE","code-exchange http="+http+" tokenPresent="+!received.isEmpty()+" errorType="+safeOAuthError(error));
                if(http!=200||received.isEmpty()){
                    message("百度拒绝授权码交换，HTTP "+http+"；错误："+safeOAuthError(error)+"。请检查授权码时效、回调配置及应用权限。");return;
                }
                token=received;
                JSONObject account=api("uinfo");
                int errno=account.optInt("errno",-999);
                verified=errno==0;
                DiagnosticLog.event("LOCALDRIVE","code-uinfo errno="+errno+" verified="+verified);
                if(verified){
                    if(LocalCredentialVault.hasSavedSecret(this)){
                        try{
                            String refresh=response.optString("refresh_token","");
                            long expires=System.currentTimeMillis()+Math.max(60L,response.optLong("expires_in",3600L)-180L)*1000L;
                            LocalCredentialVault.saveOAuth(this,key,received,refresh,expires);
                            DiagnosticLog.event("LOCALDRIVE","oauthSessionSavedLocally=true refreshPresent="+!refresh.isEmpty());
                        }catch(Exception e){
                            DiagnosticLog.event("LOCALDRIVE","oauthSaveFailed="+e.getClass().getSimpleName());
                        }
                    }
                    runOnUiThread(()->authorizationArea.setVisibility(android.view.View.GONE));
                    message("登录成功。已在本机加密保存授权，重开 APP 将尝试自动恢复登录。现在可以搜索文件。");
                }else{token="";message("已取得 Token，但个人网盘接口验证失败 errno="+errno+"；检查网盘 scope。");}
            }finally{con.disconnect();}
        });
    }
    private static String safeOAuthError(String raw){
        switch(raw){
            case "invalid_grant":case "invalid_client":case "invalid_scope":
            case "unauthorized_client":case "unsupported_grant_type":
            case "redirect_uri_mismatch":case "invalid_request":
                return raw;
            default:return raw.isEmpty()?"未给出错误码":"其他错误";
        }
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
                        Button save=button("下载到手机",files);
                        save.setOnClickListener(v->beginDocumentDownload(id,filename,size));
                        Button get=button("检查下载权限（只读首字节）",files);
                        get.setOnClickListener(v->requestDlink(id));
                    }
                }
            });
        });
    }
    private void beginDocumentDownload(String fileId,String name,long bytes){
        if(!verified||token.isEmpty()){
            message("需要先登录百度网盘后下载。");return;
        }
        String existing=BaiduDownloadService.state;
        if("running".equals(existing)||"pausing".equals(existing)||"paused".equals(existing)){
            message("已有下载任务。请先在下载状态处暂停、继续或停止。");return;
        }
        pendingFileId=fileId;pendingFileName=name;pendingFileBytes=bytes;
        pendingAccessToken=token;
        Intent picker=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType(name.toLowerCase(Locale.ROOT).endsWith(".mp4")?"video/mp4":"application/octet-stream");
        picker.putExtra(Intent.EXTRA_TITLE,name);
        try{startActivityForResult(picker,SAVE_DOWNLOADED_FILE);}
        catch(Exception e){
            pendingFileId=null;pendingAccessToken=null;
            message("无法打开安卓文件保存界面，请检查是否安装系统文件管理器。");
        }
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=SAVE_DOWNLOADED_FILE)return;
        if(resultCode!=RESULT_OK||data==null||data.getData()==null){
            pendingFileId=null;pendingAccessToken=null;
            message("已取消保存文件");return;
        }
        pendingSaveUri=data.getData();
        try{
            getContentResolver().takePersistableUriPermission(pendingSaveUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        }catch(Exception ignored){}
        if(Build.VERSION.SDK_INT>=33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},NOTIFY_PERMISSION);
        }else startDownloadService();
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(requestCode,permissions,grants);
        if(requestCode==NOTIFY_PERMISSION)startDownloadService();
    }
    private void startDownloadService(){
        if(pendingFileId==null||pendingSaveUri==null||pendingAccessToken==null)return;
        Intent work=new Intent(this,BaiduDownloadService.class).setAction(BaiduDownloadService.ACTION_START);
        work.putExtra("fsid",pendingFileId);
        work.putExtra("filename",pendingFileName);
        work.putExtra("size",pendingFileBytes);
        work.putExtra("uri",pendingSaveUri.toString());
        work.putExtra("token",pendingAccessToken);
        pendingFileId=null;pendingFileName=null;pendingAccessToken=null;pendingSaveUri=null;
        try{
            if(Build.VERSION.SDK_INT>=26)startForegroundService(work);
            else startService(work);
            stateText.setText("下载已启动，请查看下方下载进度或手机通知栏。");
            updateDownloadProgress();
        }catch(Exception e){
            stateText.setText("无法启动后台下载服务："+e.getClass().getSimpleName());
        }
    }
    private void updateDownloadProgress(){
        if(downloadInfo==null)return;
        String st=BaiduDownloadService.state;
        String detail=BaiduDownloadService.status;
        long got=BaiduDownloadService.downloaded,needed=BaiduDownloadService.total;
        if("idle".equals(st))return;
        String percentage=needed>0?"（"+Math.min(100,(got*100/needed))+"%）":"";
        downloadInfo.setText(detail+percentage+"\n已接收 "+
            String.format(Locale.US,"%.1f / %.1f MiB",got/1048576d,needed/1048576d));
        boolean paused="paused".equals(st);
        downloadControl.setVisibility(("running".equals(st)||"pausing".equals(st)||paused)?
            android.view.View.VISIBLE:android.view.View.GONE);
        downloadControl.setText(paused?"继续下载":"暂停下载");
        downloadControl.setEnabled(!"pausing".equals(st));
    }
    private final Runnable progressPoll=new Runnable(){
        @Override public void run(){
            if(!watchingProgress)return;
            updateDownloadProgress();
            progressHandler.postDelayed(this,1100);
        }
    };
    @Override protected void onResume(){
        super.onResume();
        watchingProgress=true;
        progressHandler.removeCallbacks(progressPoll);
        progressHandler.post(progressPoll);
    }
    @Override protected void onPause(){
        watchingProgress=false;
        progressHandler.removeCallbacks(progressPoll);
        super.onPause();
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
                stateText.setText("已取得 dlink，正在用 OAuth 令牌和百度指定请求头检测下载权限…");
                Button validate=button("应用内验证直链首字节（推荐）",files);
                validate.setOnClickListener(v->checkDlink(link));
                Button copy=button("复制原始 dlink（浏览器可能不能直接下载）",files);
                copy.setOnClickListener(v->{
                    ClipboardManager clip=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                    if(clip!=null)clip.setPrimaryClip(ClipData.newPlainText("个人网盘临时地址",link));
                    Toast.makeText(this,"已复制，链接可能很快过期",Toast.LENGTH_SHORT).show();
                });
                // One button: request fresh dlink, then automatically probe with OAuth.
                new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(()->checkDlink(link),150L);
            });
        });
    }
    // Only Baidu's official PCS download hosts; do not follow arbitrary redirects.
    private static boolean trustedDownloadHost(URL url){
        if(!"https".equalsIgnoreCase(url.getProtocol())||url.getUserInfo()!=null||
            (url.getPort()!=-1&&url.getPort()!=443))return false;
        String h=url.getHost().toLowerCase(Locale.ROOT);
        return h.equals("d.pcs.baidu.com")||h.endsWith(".pcs.baidu.com")||
            h.equals("baidupcs.com")||h.endsWith(".baidupcs.com");
    }
    private static String[] serverError(InputStream in){
        String[] result={"-999","-999"};
        if(in==null)return result;
        try(InputStream input=in){
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            byte[] b=new byte[1024];int n;
            while((n=input.read(b))!=-1){
                if(out.size()+n>4096)break;
                out.write(b,0,n);
            }
            JSONObject error=new JSONObject(out.toString("UTF-8"));
            result[0]=String.valueOf(error.optInt("error_code",-999));
            java.util.regex.Matcher hit=java.util.regex.Pattern.compile("hitcode:(\\d+)").matcher(error.optString("error_msg",""));
            if(hit.find())result[1]=hit.group(1);
        }catch(Exception ignored){}
        return result;
    }
    private void checkDlink(String raw){
        final URL start;
        try{
            if(raw==null||raw.length()<35||raw.length()>12000)throw new Exception("invalid");
            start=new URL(raw);
            if(!trustedDownloadHost(start))throw new Exception("host");
        }catch(Exception e){message("只接受百度 PCS 官方 HTTPS dlink，不接受其他网址。");return;}
        if(!verified||token.isEmpty()){
            message("请先完成本人网盘授权，获取有效的 OAuth 令牌，再在 APP 内进行下载检测。");
            return;
        }
        if(start.getRef()!=null || (start.getQuery()!=null &&
            java.util.regex.Pattern.compile("(^|&)access_token=",java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(start.getQuery()).find())){
            message("粘贴的链接包含额外授权参数或片段，请从网盘文件列表重新取得原始 dlink。");
            return;
        }
        final String access=token;
        work("直链权限检查",()->{
            // Baidu requires both OAuth access_token and its specified User-Agent.
            // The augmented URL is kept only in memory, never shown or logged.
            String base=start.toExternalForm();
            URL url=new URL(base+(start.getQuery()==null?"?":"&")+"access_token="+encode(access));
            DiagnosticLog.event("DLCHECK","oauthTokenAttached=true");
            for(int hop=0;hop<5;hop++){
                if(!trustedDownloadHost(url)){
                    DiagnosticLog.event("DLCHECK","untrustedRedirect=true");
                    message("已阻止向不受信任的站点跳转。");return;
                }
                HttpURLConnection c=(HttpURLConnection)url.openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(12000);c.setReadTimeout(15000);
                c.setRequestMethod("GET");
                c.setRequestProperty("User-Agent","pan.baidu.com");
                c.setRequestProperty("Range","bytes=0-0");
                c.setRequestProperty("Accept","*/*");
                try{
                    int status=c.getResponseCode();
                    String ct=c.getContentType();
                    String type=ct==null?"unknown":ct.split(";",2)[0].toLowerCase(Locale.ROOT).trim();
                    DiagnosticLog.event("DLCHECK","hop="+hop+" http="+status+
                        " contentType="+(type.matches("[a-z0-9+.-]+/[a-z0-9+.-]+")?type:"unknown")+
                        " userAgentSet=true range0to0=true oauthTokenAttached=true");
                    if(status==301||status==302||status==303||status==307||status==308){
                        String location=c.getHeaderField("Location");
                        if(location==null){message("下载服务器返回跳转，但缺少目标地址。");return;}
                        URL dest=new URL(url,location);
                        if(!trustedDownloadHost(dest)){
                            DiagnosticLog.event("DLCHECK","blockedRedirect=true");
                            message("目标跳转不在百度已允许的 PCS 下载域名内，已停止。");return;
                        }
                        url=dest;continue;
                    }
                    if(status==200||status==206){
                        if(type.contains("json")||type.contains("html")||type.startsWith("text/")){
                            String[] err=serverError(c.getInputStream());
                            DiagnosticLog.event("DLCHECK","textReply=true errorCode="+err[0]+" hitcode="+err[1]);
                            message("收到错误文本而非文件：HTTP "+status+", error_code="+err[0]+", hitcode="+err[1]);return;
                        }
                        boolean byteOk;
                        try(InputStream in=c.getInputStream()){byteOk=in.read()!=-1;}
                        DiagnosticLog.event("DLCHECK","fileByte="+byteOk+" http="+status);
                        message(byteOk?
                            "成功收到文件首字节！HTTP "+status+"。下载权限初步通过；整文件下载、断点续传和速度尚未验证。":
                            "HTTP "+status+"，但没有读到文件数据。");
                        return;
                    }
                    String[] err=serverError(c.getErrorStream());
                    DiagnosticLog.event("DLCHECK","rejected http="+status+" errorCode="+err[0]+" hitcode="+err[1]);
                    message("百度拒绝读取文件：HTTP "+status+", error_code="+err[0]+", hitcode="+err[1]+"。");
                    return;
                }finally{c.disconnect();}
            }
            message("下载地址重定向次数过多，已停止。");
        });
    }
    @Override protected void onDestroy(){
        watchingProgress=false;progressHandler.removeCallbacks(progressPoll);
        token="";verified=false;
        if(secretEntry!=null)secretEntry.setText("");
        if(authCodeEntry!=null)authCodeEntry.setText("");
        if(dlinkInput!=null)dlinkInput.setText("");
        super.onDestroy();
    }
}
