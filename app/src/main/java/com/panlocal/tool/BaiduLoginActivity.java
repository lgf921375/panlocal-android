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
        Button complete=button("同步登录并返回",buttons);
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
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest req){
                if(allowed(req.getUrl()))return false;
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
        if(uri==null||!"pan.baidu.com".equalsIgnoreCase(uri.getHost())){
            Toast.makeText(this,"请先登录并返回百度网盘分享页，再点击同步",Toast.LENGTH_LONG).show();
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
            BaiduShare.rememberOfficialSession(cookies,bt,jt);
            Toast.makeText(this,"已同步本 APP 的百度官方页面会话，请返回后重新解析",Toast.LENGTH_LONG).show();
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