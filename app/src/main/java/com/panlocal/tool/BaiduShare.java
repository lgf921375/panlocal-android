package com.panlocal.tool;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Experimental client for Baidu's public share endpoints; not an official LinkSwift port. */
public final class BaiduShare {
    public static final class Entry {
        public final String id, name;
        public final long size;
        public final boolean directory;
        Entry(String id, String name, long size, boolean directory) {
            this.id=id; this.name=name; this.size=size; this.directory=directory;
        }
    }
    public static final class Link {
        public final String surl, pwd, official;
        Link(String surl,String pwd) {
            this.surl=surl;this.pwd=pwd;
            this.official="https://pan.baidu.com/s/1"+surl+(pwd.isEmpty()?"":"?pwd="+pwd);
        }
    }
    private static final String USER_AGENT="Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36";
    private static final Pattern SHARE=Pattern.compile("https?://pan\\.baidu\\.com/s/1([A-Za-z0-9_-]{8,})",Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE=Pattern.compile("(?:提取码|访问码|密码)\\s*[:：]\\s*([A-Za-z0-9]{4,8})");
    private static final Pattern PWD=Pattern.compile("[?&]pwd=([A-Za-z0-9]{4,8})",Pattern.CASE_INSENSITIVE);
    private final Map<String,String> cookies=new LinkedHashMap<>();
    private final Map<String,Entry> listed=new LinkedHashMap<>();
    private String surl="",shareId="",uk="",sign="",timestamp="",sekey="",official="";

    public static Link parse(String raw,String manual) throws Exception {
        if(raw==null)raw="";
        raw=raw.replace('\u00a0',' ').replace("\u200b","");
        Matcher m=SHARE.matcher(raw);
        if(!m.find())throw new Exception("没有识别到有效的百度网盘分享链接。");
        String pwd=manual==null?"":manual.trim();
        if(pwd.isEmpty()) {
            Matcher c=CODE.matcher(raw);
            if(c.find())pwd=c.group(1);
            else {
                Matcher p=PWD.matcher(raw.substring(m.start()));
                if(p.find())pwd=p.group(1);
            }
        }
        if(!pwd.isEmpty()&&!pwd.matches("[A-Za-z0-9]{4,8}"))throw new Exception("提取码格式不正确。");
        return new Link(m.group(1),pwd);
    }

    private static String esc(String input)throws Exception {
        return URLEncoder.encode(input,"UTF-8");
    }
    private static String query(String... values)throws Exception {
        StringBuilder b=new StringBuilder();
        for(int i=0;i<values.length;i+=2) {
            if(b.length()>0)b.append('&');
            b.append(esc(values[i])).append('=').append(esc(values[i+1]));
        }
        return b.toString();
    }
    private static String extract(String page,String key) {
        String q=Pattern.quote(key);
        Pattern p=Pattern.compile("(?:[\"']"+q+"[\"']\\s*:\\s*|\\b"+q+"\\s*=\\s*)[\"']?([A-Za-z0-9_+/%=-]+)",Pattern.CASE_INSENSITIVE);
        Matcher m=p.matcher(page);
        return m.find()?m.group(1):"";
    }
    private static String find(String page,String... keys) {
        for(String key:keys){String x=extract(page,key);if(!x.isEmpty())return x;}
        return "";
    }
    private static String failure(int code) {
        if(code==-9)return "提取码错误或已失效。";
        if(code==-20||code==-62||code==31066)return "百度网盘要求验证码或安全验证。";
        if(code==-6||code==111)return "需要先登录百度网盘。";
        if(code==105||code==110||code==-12)return "分享已失效或访问受限。";
        return "百度网盘返回错误码："+code;
    }
    private String request(String address,String form,String referer)throws Exception {
        URL url=new URL(address);
        if(!"https".equalsIgnoreCase(url.getProtocol())||!"pan.baidu.com".equalsIgnoreCase(url.getHost()))
            throw new Exception("仅允许请求百度网盘官方 HTTPS 接口。");
        HttpURLConnection conn=(HttpURLConnection)url.openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent",USER_AGENT);
        conn.setRequestProperty("Accept","application/json,text/html,*/*");
        conn.setRequestProperty("Referer",referer);
        conn.setRequestProperty("X-Requested-With","XMLHttpRequest");
        if(!cookies.isEmpty()){
            StringBuilder cookie=new StringBuilder();
            for(Map.Entry<String,String> c:cookies.entrySet()){
                if(cookie.length()>0)cookie.append("; ");
                cookie.append(c.getKey()).append('=').append(c.getValue());
            }
            conn.setRequestProperty("Cookie",cookie.toString());
        }
        try {
            if(form!=null) {
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");
                try(OutputStream output=conn.getOutputStream()){
                    output.write(form.getBytes(StandardCharsets.UTF_8));
                }
            }
            int status=conn.getResponseCode();
            Map<String,List<String>> headers=conn.getHeaderFields();
            if(headers!=null)for(Map.Entry<String,List<String>> header:headers.entrySet()){
                if(header.getKey()!=null&&header.getKey().equalsIgnoreCase("Set-Cookie"))
                    for(String val:header.getValue()){
                        String part=val.split(";",2)[0];
                        int eq=part.indexOf('=');
                        if(eq>0)cookies.put(part.substring(0,eq),part.substring(eq+1));
                    }
            }
            if(status>=300&&status<400)throw new Exception("网盘要求跳转或登录（HTTP "+status+"）。请尝试官方页面。");
            if(status!=200)throw new Exception("网盘请求失败：HTTP "+status);
            try(InputStream in=conn.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                byte[] buf=new byte[8192];int n;
                while((n=in.read(buf))!=-1){
                    if(bytes.size()+n>1800000)throw new Exception("百度网盘响应过大，停止处理。");
                    bytes.write(buf,0,n);
                }
                return bytes.toString("UTF-8");
            }
        } finally {conn.disconnect();}
    }
    private JSONObject json(String address,String form,String referer)throws Exception {
        String body=request(address,form,referer).trim();
        try{return new JSONObject(body);}
        catch(Exception e){throw new Exception("百度网盘没有返回可识别数据；可能需要登录、验证码或接口已经变化。");}
    }

    public synchronized List<Entry> inspect(String raw,String manual)throws Exception {
        Link link=parse(raw,manual);
        surl=link.surl;official=link.official;
        listed.clear();shareId="";uk="";sign="";timestamp="";sekey="";
        String clean="https://pan.baidu.com/s/1"+surl;
        String page=request(clean,null,"https://pan.baidu.com/");
        if(!link.pwd.isEmpty()){
            String verifyUrl="https://pan.baidu.com/share/verify?"+query("surl",surl,"t",String.valueOf(System.currentTimeMillis()),"channel","chunlei","web","1","app_id","250528","clienttype","0");
            JSONObject resp=json(verifyUrl,query("pwd",link.pwd,"vcode","","vcode_str",""),clean);
            if(resp.optInt("errno",-999)!=0)throw new Exception("提取码校验失败："+failure(resp.optInt("errno",-999)));
            sekey=resp.optString("randsk","");
            try{sekey=java.net.URLDecoder.decode(sekey,"UTF-8");}catch(Exception ignored){}
            try{page=request(clean,null,clean);}catch(Exception ignored){}
        }
        shareId=find(page,"shareid","SHARE_ID","share_id");
        uk=find(page,"uk","SHARE_UK");
        sign=find(page,"sign","SIGN");
        timestamp=find(page,"timestamp","TIMESTAMP");
        String listUrl="https://pan.baidu.com/share/list?"+query("web","1","channel","chunlei","clienttype","0","app_id","250528","shorturl",surl,"page","1","num","100","root","1","dir","/");
        if(!sekey.isEmpty())listUrl+="&"+query("sekey",sekey);
        JSONObject response=json(listUrl,null,clean);
        if(response.optInt("errno",-999)!=0)throw new Exception("文件列表读取失败："+failure(response.optInt("errno",-999)));
        shareId=response.optString("shareid",response.optString("share_id",shareId));
        uk=response.optString("uk",uk);
        List<Entry> out=new ArrayList<>();
        JSONArray files=response.optJSONArray("list");
        if(files!=null)for(int i=0;i<Math.min(files.length(),100);i++){
            JSONObject f=files.optJSONObject(i);if(f==null)continue;
            String id=f.optString("fs_id","");
            if(!id.matches("[0-9]+"))continue;
            Entry e=new Entry(id,f.optString("server_filename","未命名文件"),f.optLong("size",0),f.optInt("isdir",0)==1);
            listed.put(id,e);out.add(e);
        }
        return out;
    }

    public synchronized String obtain(String id)throws Exception {
        Entry file=listed.get(id);
        if(file==null)throw new Exception("文件列表已失效，请重新解析。");
        if(file.directory)throw new Exception("文件夹不能直接提取下载链接。");
        if(shareId.isEmpty()||uk.isEmpty()||sign.isEmpty()||timestamp.isEmpty())
            throw new Exception("分享页面未提供必要下载签名，可能需要登录百度网盘。");
        String url="https://pan.baidu.com/api/sharedownload?"+query("sign",sign,"timestamp",timestamp,"app_id","250528","channel","chunlei","clienttype","0","web","1");
        String form=query("encrypt","0","product","share","uk",uk,"primaryid",shareId,"fid_list","["+id+"]");
        if(!sekey.isEmpty())form+="&"+query("extra",new JSONObject().put("sekey",sekey).toString());
        JSONObject result=json(url,form,official);
        if(result.optInt("errno",-999)!=0)throw new Exception("直链接口拒绝请求："+failure(result.optInt("errno",-999)));
        String direct=result.optString("dlink","");
        JSONArray arr=result.optJSONArray("list");
        if(direct.isEmpty()&&arr!=null&&arr.length()>0){
            JSONObject first=arr.optJSONObject(0);
            if(first!=null)direct=first.optString("dlink","");
        }
        URL target;
        try{target=new URL(direct);}
        catch(Exception e){throw new Exception("网盘没有返回可识别的下载地址。");}
        if(!"https".equalsIgnoreCase(target.getProtocol())||target.getHost().isEmpty())
            throw new Exception("接口没有返回安全的 HTTPS 下载地址。");
        return direct;
    }
}
