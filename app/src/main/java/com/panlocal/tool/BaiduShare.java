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
    // Browser session stays in this Android process; never sent to an external service.
    private static String sessionCookie="",sessionBdstoken="",sessionJsToken="";
    public static synchronized void rememberOfficialSession(String cookie,String bdstoken,String jsToken) {
        sessionCookie=cookie==null?"":cookie;
        sessionBdstoken=bdstoken==null?"":bdstoken;
        sessionJsToken=jsToken==null?"":jsToken;
        DiagnosticLog.event("AUTH","官方页面会话同步: cookiePresent="+!sessionCookie.isEmpty()+" bdstokenPresent="+!sessionBdstoken.isEmpty()+" jsTokenPresent="+!sessionJsToken.isEmpty());
    }
    private static synchronized String[] officialSession(){
        return new String[]{sessionCookie,sessionBdstoken,sessionJsToken};
    }
    private String bdstoken="",jsToken="";
    private void loadOfficialSession(){
        String[] auth=officialSession();
        bdstoken=auth[1];jsToken=auth[2];
        DiagnosticLog.event("AUTH","加载会话: cookiePresent="+!auth[0].isEmpty()+" bdstokenPresent="+!bdstoken.isEmpty()+" jsTokenPresent="+!jsToken.isEmpty());
        if(!auth[0].isEmpty())for(String raw:auth[0].split(";")){
            String part=raw.trim();int idx=part.indexOf('=');
            if(idx>0&&idx<part.length()-1)cookies.put(part.substring(0,idx),part.substring(idx+1));
        }
    }
    private void fillSignature()throws Exception {
        // LinkSwift retrieves fresh share signatures through /share/tplconfig.
        String url="https://pan.baidu.com/share/tplconfig?"+query(
            "fields","sign,timestamp","channel","chunlei","web","1","app_id","250528",
            "clienttype","0","view_mode","1","surl","1"+surl);
        if(!bdstoken.isEmpty())url+="&"+query("bdstoken",bdstoken);
        String baiduid=cookies.get("BAIDUID");
        if(baiduid!=null&&!baiduid.isEmpty()){
            String logid=java.util.Base64.getEncoder().encodeToString(baiduid.getBytes(StandardCharsets.UTF_8));
            url+="&"+query("logid",logid);
        }
        JSONObject result;
        try{result=json(url,null,official);}
        catch(Exception e){throw new Exception("获取下载签名失败："+e.getMessage());}
        int errno=result.optInt("errno",0);
        if(errno!=0)throw new Exception("签名接口拒绝请求："+failure(errno));
        JSONObject data=result.optJSONObject("data");if(data==null)data=result;
        String returnedSign=data.optString("sign","");
        String returnedTime=data.optString("timestamp","");
        DiagnosticLog.event("SIGN","tplconfig signPresent="+!returnedSign.isEmpty()+" timestampPresent="+!returnedTime.isEmpty());
        if(!returnedSign.isEmpty()&&!returnedTime.isEmpty()){
            sign=returnedSign;timestamp=returnedTime;
            return;
        }
        throw new Exception("签名接口未提供 sign/timestamp；请先在 APP 内打开百度官方登录页并登录后重试。");
    }
    private static final String USER_AGENT="Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36";
    private static final Pattern SHARE=Pattern.compile("https?://pan\\.baidu\\.com/s/1([A-Za-z0-9_-]{8,})",Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE=Pattern.compile("(?:提取码|访问码|密码)\\s*[:：]\\s*([A-Za-z0-9]{4,8})");
    private static final Pattern PWD=Pattern.compile("[?&]pwd=([A-Za-z0-9]{4,8})",Pattern.CASE_INSENSITIVE);
    private final Map<String,String> cookies=new LinkedHashMap<>();
    private final Map<String,Entry> listed=new LinkedHashMap<>();
    private String surl="",shareId="",uk="",sign="",timestamp="",sekey="",official="";
    private boolean sharePageRedirected=false;
    public boolean wasSharePageRedirected(){return sharePageRedirected;}

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
    /** Only pan.baidu.com HTTPS redirects are followed. Login redirects are reported, never followed. */
    private String request(String address,String form,String referer)throws Exception {
        URL url=new URL(address);
        for(int hop=0;hop<=5;hop++){
            final String endpoint=DiagnosticLog.endpoint(url);
            DiagnosticLog.event("HTTP",endpoint+" method="+(form==null?"GET":"POST")+" hop="+hop+" credentialsCount="+cookies.size());
            if(!"https".equalsIgnoreCase(url.getProtocol())
                ||!"pan.baidu.com".equalsIgnoreCase(url.getHost())
                ||(url.getPort()!=-1&&url.getPort()!=443)
                ||url.getUserInfo()!=null)
                throw new Exception("已阻止跳转至非百度网盘官方 HTTPS 接口。");
            HttpURLConnection conn=(HttpURLConnection)url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent",USER_AGENT);
            conn.setRequestProperty("Accept","application/json,text/html,*/*");
            conn.setRequestProperty("Referer",referer);
            // Navigation to the share page is not an AJAX request.
            if(url.getPath().startsWith("/api/") || url.getPath().startsWith("/share/"))
                conn.setRequestProperty("X-Requested-With","XMLHttpRequest");
            if(!cookies.isEmpty()){
                StringBuilder cookie=new StringBuilder();
                for(Map.Entry<String,String> c:cookies.entrySet()){
                    if(cookie.length()>0)cookie.append("; ");
                    cookie.append(c.getKey()).append('=').append(c.getValue());
                }
                conn.setRequestProperty("Cookie",cookie.toString());
            }
            try{
                if(form!=null){
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");
                    try(OutputStream output=conn.getOutputStream()){
                        output.write(form.getBytes(StandardCharsets.UTF_8));
                    }
                }
                int status=conn.getResponseCode();
                DiagnosticLog.event("HTTP",endpoint+" status="+status);
                Map<String,List<String>> headers=conn.getHeaderFields();
                if(headers!=null)for(Map.Entry<String,List<String>> header:headers.entrySet()){
                    if(header.getKey()!=null&&header.getKey().equalsIgnoreCase("Set-Cookie"))
                        for(String val:header.getValue()){
                            String part=val.split(";",2)[0];int eq=part.indexOf('=');
                            if(eq>0)cookies.put(part.substring(0,eq),part.substring(eq+1));
                        }
                }
                if(status==301||status==302||status==303||status==307||status==308){
                    String location=conn.getHeaderField("Location");
                    if(location==null||location.isEmpty())
                        throw new Exception("百度网盘返回 HTTP "+status+"，但没有提供跳转地址。");
                    URL next=new URL(url,location);
                    String host=next.getHost().toLowerCase(java.util.Locale.ROOT);
                    DiagnosticLog.event("REDIRECT",endpoint+" status="+status+" destination="+(host.equals("pan.baidu.com")?"pan.baidu.com":host.endsWith(".baidu.com")?"other-baidu-host":"non-baidu")+" sameEndpoint="+DiagnosticLog.endpoint(next).equals(endpoint));
                    if(host.equals("passport.baidu.com")||host.equals("wappass.baidu.com")
                        ||host.endsWith(".passport.baidu.com"))
                        throw new Exception("百度网盘要求登录（HTTP "+status+"）。当前独立接口无法共享浏览器登录态，请先在官方页面验证分享是否可访问。");
                    if(!"https".equalsIgnoreCase(next.getProtocol())||!host.equals("pan.baidu.com")
                        ||(next.getPort()!=-1&&next.getPort()!=443)||next.getUserInfo()!=null)
                        throw new Exception("网盘请求被重定向至其他站点（HTTP "+status+"），已停止以保护登录信息。");
                    if(form!=null&&status!=307&&status!=308)
                        throw new Exception("百度网盘接口 POST 请求跳转（HTTP "+status+"），可能需要登录或安全验证。");
                    url=next;
                    continue;
                }
                if(status!=200)throw new Exception("百度网盘请求失败：HTTP "+status);
                try(InputStream in=conn.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                    byte[] buf=new byte[8192];int n;
                    while((n=in.read(buf))!=-1){
                        if(bytes.size()+n>1800000)throw new Exception("百度网盘响应过大，停止处理。");
                        bytes.write(buf,0,n);
                    }
                    DiagnosticLog.event("HTTP",endpoint+" responseBytes="+bytes.size()+" type=text");
                    return bytes.toString("UTF-8");
                }
            }finally{conn.disconnect();}
        }
        throw new Exception("百度网盘站内跳转超过5次，可能进入循环；请先用官方页面确认分享链接。");
    }
    private JSONObject json(String address,String form,String referer)throws Exception {
        String body=request(address,form,referer).trim();
        try{
            JSONObject obj=new JSONObject(body);
            Object shape=obj.opt("list");
            JSONObject data=obj.optJSONObject("data");
            String schema="errno="+(obj.has("errno")?String.valueOf(obj.optInt("errno",-999)):"missing")
                +" listType="+DiagnosticLog.listType(shape)
                +" hasTopDlink="+obj.has("dlink")
                +" hasData="+(data!=null);
            DiagnosticLog.event("JSON",DiagnosticLog.endpoint(new URL(address))+" "+schema);
            return obj;
        }catch(Exception e){
            DiagnosticLog.event("JSON","无法解析 JSON，正文未记录");
            throw new Exception("百度网盘没有返回可识别数据；可能需要登录、验证码或接口已经变化。");
        }
    }

    public synchronized List<Entry> inspect(String raw,String manual)throws Exception {
        Link link=parse(raw,manual);
        surl=link.surl;official=link.official;
        DiagnosticLog.event("SHARE","解析开始: pwdPresent="+!link.pwd.isEmpty()+"; 分享链接不写入日志");
        listed.clear();shareId="";uk="";sign="";timestamp="";sekey="";
        sharePageRedirected=false;
        loadOfficialSession();
        String clean="https://pan.baidu.com/s/1"+surl;
        String page="";
        try {page=request(clean,null,"https://pan.baidu.com/");}
        catch(Exception ex){
            String reason=ex.getMessage();
            // Share-page HTML is optional for the public verify/list endpoints.
            // Keep authentication/redirect diagnostics, but do not stop before trying those endpoints.
            if(reason!=null&&reason.contains("百度网盘要求登录（HTTP")){
                sharePageRedirected=true;
                DiagnosticLog.event("SHARE","分享页触发登录跳转，继续尝试公开接口");
            }else{
                throw new Exception("分享页访问失败："+reason);
            }
        }
        if(!link.pwd.isEmpty()){
            String verifyUrl="https://pan.baidu.com/share/verify?"+query("surl",surl,"t",String.valueOf(System.currentTimeMillis()),"channel","chunlei","web","1","app_id","250528","clienttype","0");
            JSONObject resp;
            try{resp=json(verifyUrl,query("pwd",link.pwd,"vcode","","vcode_str",""),clean);}
            catch(Exception ex){throw new Exception("提取码校验请求失败："+ex.getMessage());}
            DiagnosticLog.event("VERIFY","提取码校验 errno="+resp.optInt("errno",-999));
            if(resp.optInt("errno",-999)!=0)throw new Exception("提取码校验失败："+failure(resp.optInt("errno",-999)));
            sekey=resp.optString("randsk","");
            try{sekey=java.net.URLDecoder.decode(sekey,"UTF-8");}catch(Exception ignored){}
            try{page=request(clean,null,clean);}catch(Exception ignored){}
        }
        if(bdstoken.isEmpty())bdstoken=find(page,"bdstoken","BDSTOKEN");
        shareId=find(page,"shareid","SHARE_ID","share_id");
        uk=find(page,"uk","SHARE_UK");
        sign=find(page,"sign","SIGN");
        timestamp=find(page,"timestamp","TIMESTAMP");
        String listUrl="https://pan.baidu.com/share/list?"+query("web","1","channel","chunlei","clienttype","0","app_id","250528","shorturl",surl,"page","1","num","100","root","1","dir","/");
        if(!sekey.isEmpty())listUrl+="&"+query("sekey",sekey);
        JSONObject response;
        try{response=json(listUrl,null,clean);}
        catch(Exception ex){throw new Exception("文件列表请求失败："+ex.getMessage());}
        DiagnosticLog.event("LIST","读取接口 errno="+response.optInt("errno",-999));
        if(response.optInt("errno",-999)!=0)throw new Exception("文件列表读取失败："+failure(response.optInt("errno",-999)));
        shareId=response.optString("shareid",response.optString("share_id",shareId));
        uk=response.optString("uk",uk);
        // Some share/list versions only include share metadata in the file entries.
        JSONArray fromResponse=response.optJSONArray("list");
        if(fromResponse!=null&&fromResponse.length()>0){
            JSONObject first=fromResponse.optJSONObject(0);
            if(first!=null){
                if(shareId.isEmpty())shareId=first.optString("shareid",first.optString("share_id",""));
                if(uk.isEmpty())uk=first.optString("uk","");
            }
        }
        List<Entry> out=new ArrayList<>();
        JSONArray files=response.optJSONArray("list");
        if(files!=null)for(int i=0;i<Math.min(files.length(),100);i++){
            JSONObject f=files.optJSONObject(i);if(f==null)continue;
            String id=f.optString("fs_id","");
            if(!id.matches("[0-9]+"))continue;
            Entry e=new Entry(id,f.optString("server_filename","未命名文件"),f.optLong("size",0),f.optInt("isdir",0)==1);
            listed.put(id,e);out.add(e);
        }
        DiagnosticLog.event("LIST","解析完成 fileCount="+out.size()+" shareIdPresent="+!shareId.isEmpty()+" ukPresent="+!uk.isEmpty());
        return out;
    }

    public synchronized String obtain(String id)throws Exception {
        Entry file=listed.get(id);
        if(file==null)throw new Exception("文件列表已失效，请重新解析。");
        if(file.directory)throw new Exception("文件夹不能直接提取下载链接。");
        if(shareId.isEmpty()||uk.isEmpty())
            throw new Exception("缺少 shareid 或 uk，请重新读取文件列表。");
        DiagnosticLog.event("DIRECT","提取开始 sizeMiB="+(file.size/1048576L)+" shareIdPresent="+!shareId.isEmpty()+" ukPresent="+!uk.isEmpty());
        fillSignature();
        String url="https://pan.baidu.com/api/sharedownload?"+query("sign",sign,"timestamp",timestamp,"app_id","250528","channel","chunlei","clienttype","0","web","1");
        if(!bdstoken.isEmpty())url+="&"+query("bdstoken",bdstoken);
        if(!jsToken.isEmpty())url+="&"+query("jsToken",jsToken);
        String baiduid=cookies.get("BAIDUID");
        if(baiduid!=null&&!baiduid.isEmpty())url+="&"+query("logid",java.util.Base64.getEncoder().encodeToString(baiduid.getBytes(StandardCharsets.UTF_8)));
        String form=query("encrypt","0","product","share","uk",uk,"primaryid",shareId,"fid_list","["+id+"]");
        if(!sekey.isEmpty())form+="&"+query("extra",new JSONObject().put("sekey",sekey).toString());
        JSONObject result=json(url,form,official);
        DiagnosticLog.event("DIRECT","sharedownload errno="+result.optInt("errno",-999)+" listType="+DiagnosticLog.listType(result.opt("list"))+" "+DiagnosticLog.shapeOfString(result.opt("list"))+" dataPresent="+(result.optJSONObject("data")!=null));
        if(result.optInt("errno",-999)!=0)throw new Exception("直链接口拒绝请求："+failure(result.optInt("errno",-999)));
        String direct=result.optString("dlink","");
        if(direct.isEmpty()){
            JSONObject data=result.optJSONObject("data");
            if(data!=null)direct=data.optString("dlink","");
            if(data!=null&&direct.isEmpty()){
                JSONArray child=data.optJSONArray("list");
                if(child!=null&&child.length()>0){
                    JSONObject first=child.optJSONObject(0);
                    if(first!=null)direct=first.optString("dlink","");
                }
            }
        }
        Object listShape=result.opt("list");
        if(direct.isEmpty()&&listShape instanceof JSONArray){
            JSONArray arr=(JSONArray)listShape;
            if(arr.length()>0){
                JSONObject first=arr.optJSONObject(0);
                if(first!=null)direct=first.optString("dlink","");
            }
        }
        if(direct.isEmpty()&&listShape instanceof JSONObject){
            direct=((JSONObject)listShape).optString("dlink","");
        }
        if(direct.isEmpty()){
            // Expose the schema, not raw server data: never print returned credentials or URLs.
            if(listShape instanceof String){
                throw new Exception("接口返回文本而不是直链（list=字符串）。"
                    +"这个文件超过2GB，可能受到百度分享大文件限制；请在官方页面转存到自己网盘后下载。");
            }
            if(listShape instanceof JSONArray){
                throw new Exception("接口返回文件列表，但没有 dlink（list=数组）。"
                    +"请先在 APP 内登录百度网盘后重试；若仍失败，请到官方页面转存文件。");
            }
            if(listShape instanceof JSONObject){
                throw new Exception("接口返回对象，但没有 dlink（list=对象）。"
                    +"当前接口没有提供可下载地址，可尝试官方页面转存。");
            }
            throw new Exception("百度网盘未提供 dlink（list="
                +(listShape==null||listShape==JSONObject.NULL?"无":listShape.getClass().getSimpleName())
                +"）。可能需要登录、转存，或下载接口不支持此分享文件。");
        }
        URL target;
        try{target=new URL(direct);}
        catch(Exception e){throw new Exception("接口返回 dlink 字段，但不是有效的下载 URL。");}
        if(!"https".equalsIgnoreCase(target.getProtocol())||target.getHost().isEmpty())
            throw new Exception("接口没有返回安全的 HTTPS 下载地址。");
        DiagnosticLog.event("DIRECT","dlinkPresent=true URL 内容不写入日志");
        return direct;
    }
}
