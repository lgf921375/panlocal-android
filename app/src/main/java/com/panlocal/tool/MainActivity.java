package com.panlocal.tool;

import android.app.Activity;
import android.os.Bundle;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;
import java.util.Locale;

/** Offline-installed UI; requests go from this phone to Baidu. */
public final class MainActivity extends Activity {
    private static final int NAVY=0xff152442, BLUE=0xff2163e7, GREY=0xff596c88, RED=0xffae3333, GREEN=0xff146947;
    private EditText shareInput,codeInput;
    private TextView status;
    private LinearLayout results;
    private Button readButton;
    private BaiduShare active;
    private boolean busy;
    private int dp(float v){return (int)(getResources().getDisplayMetrics().density*v+0.5f);}
    private LinearLayout column(){
        LinearLayout l=new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);return l;
    }
    private GradientDrawable background(int color,int radius){
        GradientDrawable d=new GradientDrawable();
        d.setColor(color);d.setCornerRadius(dp(radius));return d;
    }
    private LinearLayout.LayoutParams params(int top) {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin=dp(top);return p;
    }
    private TextView text(String s,int size,int color,boolean strong){
        TextView v=new TextView(this);
        v.setText(s);v.setTextSize(size);v.setTextColor(color);
        if(strong)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return v;
    }
    private Button button(String s,boolean primary) {
        Button b=new Button(this);
        b.setText(s);b.setAllCaps(false);b.setTextSize(14);
        b.setTextColor(primary?Color.WHITE:NAVY);
        b.setBackground(background(primary?BLUE:0xffe4ecf8,10));
        b.setPadding(dp(10),dp(9),dp(10),dp(9));
        b.setMinHeight(dp(46));return b;
    }
    private EditText edit(String placeholder,boolean multiline) {
        EditText e=new EditText(this);
        e.setTextSize(15);e.setTextColor(NAVY);e.setHint(placeholder);
        e.setHintTextColor(0xff8190a8);e.setPadding(dp(12),dp(12),dp(12),dp(12));
        e.setBackground(background(0xfff0f4f9,10));
        e.setSingleLine(!multiline);
        if(multiline){e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);e.setMinLines(3);e.setMaxLines(6);e.setGravity(Gravity.TOP);}
        else e.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        return e;
    }
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_SHORT).show();}
    private void note(String msg,int color){status.setText(msg);status.setTextColor(color);}
    private void setBusy(boolean value) {busy=value;readButton.setEnabled(!value);readButton.setAlpha(value?0.6f:1f);}

    @Override public void onCreate(Bundle bundle){
        super.onCreate(bundle);
        getWindow().setStatusBarColor(NAVY);
        getWindow().setNavigationBarColor(NAVY);
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout outer=column();outer.setBackgroundColor(0xfff3f6fa);
        scroll.addView(outer);setContentView(scroll);
        LinearLayout hero=column();
        hero.setPadding(dp(20),dp(25),dp(20),dp(24));
        hero.setBackgroundColor(NAVY);outer.addView(hero);
        hero.addView(text("PANLOCAL · 安卓本地版 V0.4",12,0xffa5c4fc,true),params(0));
        hero.addView(text("百度网盘直链助手",25,Color.WHITE,true),params(10));
        hero.addView(text("粘贴分享链接 → 尝试读取文件 → 请求下载地址",13,0xffd0dcf0,false),params(10));

        LinearLayout content=column();
        content.setPadding(dp(15),dp(16),dp(15),dp(24));outer.addView(content);
        LinearLayout form=column();form.setBackground(background(Color.WHITE,14));form.setPadding(dp(16),dp(16),dp(16),dp(18));
        content.addView(form,params(0));
        form.addView(text("01  分享信息",18,NAVY,true),params(0));
        form.addView(text("百度网盘分享链接或整段分享文案",13,GREY,false),params(14));
        shareInput=edit("https://pan.baidu.com/s/...",true);form.addView(shareInput,params(7));
        form.addView(text("提取码（可空，自动识别）",13,GREY,false),params(14));
        codeInput=edit("例如 331e",false);form.addView(codeInput,params(7));
        readButton=button("尝试解析文件",true);form.addView(readButton,params(16));
        readButton.setOnClickListener(v->inspect());

        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);
        form.addView(actions,params(9));
        Button paste=button("粘贴",false);
        actions.addView(paste,new LinearLayout.LayoutParams(0,dp(48),1));
        LinearLayout.LayoutParams gap=new LinearLayout.LayoutParams(0,dp(48),1);gap.leftMargin=dp(9);
        Button official=button("官方页面打开",false);actions.addView(official,gap);
        paste.setOnClickListener(v->paste());official.setOnClickListener(v->openOfficial());
        Button login=button("百度网盘官方页面 · 登录/转存",false);
        form.addView(login,params(9));
        login.setOnClickListener(v->openOfficialLogin());

        LinearLayout resultCard=column();
        resultCard.setBackground(background(Color.WHITE,14));
        resultCard.setPadding(dp(16),dp(16),dp(16),dp(18));
        content.addView(resultCard,params(16));
        resultCard.addView(text("02  解析结果",18,NAVY,true),params(0));
        status=text("等待分享链接。",14,GREY,false);resultCard.addView(status,params(12));
        results=column();resultCard.addView(results,params(10));

        LinearLayout help=column();help.setPadding(dp(16),dp(16),dp(16),dp(18));
        help.setBackground(background(Color.WHITE,14));content.addView(help,params(16));
        help.addView(text("说明",16,NAVY,true),params(0));
        help.addView(text("本工具仅通过手机直接尝试访问百度网盘官方接口，不需要网站、Termux 或独立服务器。\n\n这是独立实验实现，不是 LinkSwift 官方 APP。可在本 APP 内打开百度官方登录页，使用当前应用会话重新请求。不会读取其他浏览器或百度 APP 的登录凭证。获取到地址也不意味着可用或不限速。请不要在任何第三方页面提交账号密码。",13,GREY,false),params(10));
        Button github=button("查看 LinkSwift 官方源码",false);
        help.addView(github,params(12));
        github.setOnClickListener(v->openUrl("https://github.com/hmjz100/LinkSwift"));
        Intent incoming=getIntent();
        if(Intent.ACTION_SEND.equals(incoming.getAction())&&"text/plain".equals(incoming.getType())){
            String s=incoming.getStringExtra(Intent.EXTRA_TEXT);
            if(s!=null)shareInput.setText(s);
        }
    }
    private void paste(){
        ClipboardManager clipboard=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        if(clipboard==null||!clipboard.hasPrimaryClip()){toast("剪贴板没有内容");return;}
        ClipData clips=clipboard.getPrimaryClip();
        if(clips==null||clips.getItemCount()<1)return;
        CharSequence s=clips.getItemAt(0).coerceToText(this);
        if(s!=null){shareInput.setText(s);shareInput.setSelection(shareInput.length());}
    }
    private void inspect(){
        if(busy)return;
        String raw=shareInput.getText().toString(),code=codeInput.getText().toString();
        try{BaiduShare.parse(raw,code);}
        catch(Exception e){note(e.getMessage(),RED);return;}
        results.removeAllViews();note("正在联系百度网盘读取文件列表……",GREY);
        setBusy(true);
        InputMethodManager keyboard=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        if(keyboard!=null)keyboard.hideSoftInputFromWindow(shareInput.getWindowToken(),0);
        new Thread(()->{
            BaiduShare client=new BaiduShare();
            try{
                List<BaiduShare.Entry> entries=client.inspect(raw,code);
                runOnUiThread(()->{active=client;setBusy(false);showEntries(entries);});
            }catch(Exception e){
                runOnUiThread(()->{setBusy(false);note("未能读取文件列表："+safe(e),RED);});
            }
        },"pan-lookup").start();
    }
    private String safe(Exception e) {
        String s=e.getMessage();
        if(s==null||s.isEmpty())return "网络或接口异常";
        return s.length()>190?s.substring(0,190):s;
    }
    private void showEntries(List<BaiduShare.Entry> entries){
        if(entries.isEmpty()){note("接口已响应，但没有返回可展示的文件。",GREY);return;}
        note("文件列表读取成功（"+entries.size()+" 项）。下载直链尚未验证。",GREEN);
        for(BaiduShare.Entry e:entries){
            LinearLayout item=column();
            item.setPadding(dp(12),dp(12),dp(12),dp(12));
            item.setBackground(background(0xffeff4fa,10));results.addView(item,params(10));
            item.addView(text((e.directory?"📁 ":"📄 ")+e.name,14,NAVY,true),params(0));
            String desc=e.directory?"目录（暂不支持）":String.format(Locale.US,"%.2f MB",e.size/1048576d);
            item.addView(text(desc,12,GREY,false),params(5));
            if(!e.directory){
                Button get=button("尝试提取此文件直链",true);item.addView(get,params(8));
                get.setOnClickListener(v->getLink(e,item,get));
                Button save=button("去百度网盘官方页面转存/下载",false);
                item.addView(save,params(8));
                save.setOnClickListener(v->openOfficialLogin());
            }
        }
    }
    private void getLink(BaiduShare.Entry e,LinearLayout item,Button button){
        if(busy||active==null)return;
        setBusy(true);button.setEnabled(false);button.setText("请求中…");
        BaiduShare client=active;
        new Thread(()->{
            try{
                String link=client.obtain(e.id);
                runOnUiThread(()->{
                    setBusy(false);button.setEnabled(true);button.setText("再次尝试提取");
                    note("接口返回了下载地址，尚未验证实际能否下载。",GREEN);
                    item.addView(text(link,12,GREY,false),params(9));
                    Button copy=button("复制下载地址",false);item.addView(copy,params(8));
                    copy.setOnClickListener(v->{
                        ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                        if(clipboard!=null)clipboard.setPrimaryClip(ClipData.newPlainText("下载地址",link));
                        toast("已复制地址；注意可能短时失效");
                    });
                });
            }catch(Exception ex){
                runOnUiThread(()->{
                    setBusy(false);button.setEnabled(true);button.setText("再次尝试提取");
                    note("未取得直链："+safe(ex),RED);
                });
            }
        },"pan-direct").start();
    }
    private void openOfficialLogin(){
        String url="https://pan.baidu.com/";
        try{url=BaiduShare.parse(shareInput.getText().toString(),codeInput.getText().toString()).official;}
        catch(Exception ignored){}
        Intent i=new Intent(this,BaiduLoginActivity.class);
        i.putExtra("official_url",url);
        startActivity(i);
    }
    private void openOfficial(){
        try{BaiduShare.Link l=BaiduShare.parse(shareInput.getText().toString(),codeInput.getText().toString());openUrl(l.official);}
        catch(Exception e){note(safe(e),RED);}
    }
    private void openUrl(String url){
        try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}
        catch(Exception e){toast("无法打开浏览器或官方链接");}
    }
}
