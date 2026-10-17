package com.panlocal.tool;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

/** Official Baidu login in an isolated app WebView. Credentials are never read by this app. */
public final class BaiduLoginActivity extends Activity {
    private WebView web;
    private String shareUrl;
    private boolean allowed(Uri u){
        if(u==null||!"https".equalsIgnoreCase(u.getScheme()))return false;
        String h=u.getHost();
        if(h==null)return false;
        h=h.toLowerCase(java.util.Locale.ROOT);
        return h.equals("baidu.com")||h.endsWith(".baidu.com");
    }
    private Button button(String label,LinearLayout line){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);
        line.addView(b,new LinearLayout.LayoutParams(0,-2,1));
        return b;
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        DiagnosticLog.init(getApplicationContext());
        String requested=getIntent().getStringExtra("official_url");
        Uri initial=null;
        try{initial=Uri.parse(requested);}catch(Exception ignored){}
        shareUrl=allowed(initial)?requested:"https://pan.baidu.com/";
        LinearLayout outer=new LinearLayout(this);outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(Color.WHITE);
        LinearLayout buttons=new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        outer.addView(buttons,new LinearLayout.LayoutParams(-1,-2));
        Button home=button("返回分享页",buttons);
        Button complete=button("同步当前会话并返回",buttons);
        web=new WebView(this);
        outer.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(outer);
        WebSettings settings=web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        DiagnosticLog.event("WEBVIEW","打开官方页面，等待主框架加载");
        web.setWebViewClient(new WebViewClient(){
            @Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap favicon){
                DiagnosticLog.event("WEBVIEW","页面开始加载 host="+DiagnosticLog.webHost(Uri.parse(url)));
            }
            @Override public void onPageFinished(WebView view,String url){
                DiagnosticLog.event("WEBVIEW","页面加载完成 host="+DiagnosticLog.webHost(Uri.parse(url)));
            }
            @Override public void onReceivedError(WebView view,WebResourceRequest req,android.webkit.WebResourceError error){
                if(req.isForMainFrame())DiagnosticLog.event("WEBVIEW","主框架网络错误 code="+error.getErrorCode()+" host="+DiagnosticLog.webHost(req.getUrl()));
            }
            @Override public void onReceivedHttpError(WebView view,WebResourceRequest req,android.webkit.WebResourceResponse response){
                if(req.isForMainFrame())DiagnosticLog.event("WEBVIEW","主框架 HTTP="+response.getStatusCode()+" host="+DiagnosticLog.webHost(req.getUrl()));
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest req){
                if(allowed(req.getUrl()))return false;
                DiagnosticLog.event("WEBVIEW","拦截非允许域名的主框架跳转");
                Toast.makeText(BaiduLoginActivity.this,"仅允许访问百度官方 HTTPS 网站",Toast.LENGTH_LONG).show();
                return true;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view,String url){
                if(allowed(Uri.parse(url)))return false;
                return true;
            }
        });
        home.setOnClickListener(v->web.loadUrl(shareUrl));
        complete.setOnClickListener(v->finishLogin());
        web.loadUrl(shareUrl);
    }
    private void finishLogin(){
        String page=web.getUrl();
        Uri uri;
        try{uri=Uri.parse(page);}catch(Exception ex){uri=null;}
        DiagnosticLog.event("AUTH","用户尝试同步会话, currentHost="+DiagnosticLog.webHost(uri));
        if(uri==null||!"pan.baidu.com".equalsIgnoreCase(uri.getHost())){
            Toast.makeText(this,"请先返回百度网盘分享页，再点击同步会话",Toast.LENGTH_LONG).show();
            return;
        }
        String cookie=CookieManager.getInstance().getCookie("https://pan.baidu.com");
        if(cookie==null)cookie="";
        final String cookies=cookie;
        String js="(function(){try{var d=window.locals&&window.locals.dump?window.locals.dump():{};return JSON.stringify({bdstoken:(d.bdstoken&&d.bdstoken.value)||'',jsToken:window.jsToken||''});}catch(e){return '{}';}})()";
        web.evaluateJavascript(js,value->{
            String bt="",jt="";
            try{
                String str=new JSONArray("["+value+"]").getString(0);
                JSONObject data=new JSONObject(str);
                bt=data.optString("bdstoken","");jt=data.optString("jsToken","");
            }catch(Exception ignored){}
            DiagnosticLog.event("AUTH","百度页面返回: cookiePresent="+!cookies.isEmpty()+" bdstokenPresent="+!bt.isEmpty()+" jsTokenPresent="+!jt.isEmpty());
            BaiduShare.rememberOfficialSession(cookies,bt,jt);
            Toast.makeText(this,"页面会话已读取，但不代表已验证账号登录；请返回重新解析",Toast.LENGTH_LONG).show();
            finish();
        });
    }
    @Override public void onBackPressed(){
        if(web!=null&&web.canGoBack())web.goBack();else super.onBackPressed();
    }
    @Override protected void onDestroy(){
        if(web!=null){web.stopLoading();web.destroy();web=null;}
        super.onDestroy();
    }
}