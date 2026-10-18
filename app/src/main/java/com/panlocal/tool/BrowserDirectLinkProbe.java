package com.panlocal.tool;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Browser-compatibility diagnostic for an already-authorized Baidu CDN URL.
 * Sends NO OAuth token, Cookie, Authorization or special Baidu User-Agent.
 * This is a same-device probe only; it does not prove cross-device access.
 */
public final class BrowserDirectLinkProbe {
    private BrowserDirectLinkProbe(){}
    private static final String BAIDU_UA="pan.baidu.com";
    private static final String CHROME_UA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "+
        "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";

    public static final class Probe {
        public final int http;
        public final boolean dataRead;
        public final String mime;
        public final String finalUrl;
        public final String reason;
        Probe(int status,boolean received,String contentType,String lastUrl,String cause){
            http=status;dataRead=received;mime=contentType;finalUrl=lastUrl;reason=cause;
        }
    }
    public static final class Report {
        public final Probe range,normal,uaOnly,uaNoRange,tokenOnly;
        public final boolean oauthParamStripped, browserCandidate;
        public final String candidateUrl,reason;
        Report(Probe range,Probe normal,Probe uaOnly,Probe uaNoRange,Probe tokenOnly,
               boolean stripped,boolean browserReady,String url,String detail){
            this.range=range;this.normal=normal;
            this.uaOnly=uaOnly;this.uaNoRange=uaNoRange;this.tokenOnly=tokenOnly;
            this.oauthParamStripped=stripped;this.browserCandidate=browserReady;
            this.candidateUrl=url;this.reason=detail;
        }
    }
    static boolean trusted(URL url){
        if(!"https".equalsIgnoreCase(url.getProtocol())||url.getUserInfo()!=null||
           url.getRef()!=null||(url.getPort()!=-1&&url.getPort()!=443))return false;
        String host=url.getHost().toLowerCase(Locale.ROOT);
        return host.equals("d.pcs.baidu.com")||host.endsWith(".pcs.baidu.com")||
            host.equals("baidupcs.com")||host.endsWith(".baidupcs.com");
    }
    static boolean dangerousQuery(URL url){
        if(url.getQuery()==null)return false;
        for(String kv:url.getQuery().split("&")){
            String key=kv.split("=",2)[0];
            try{key=URLDecoder.decode(key,"UTF-8").toLowerCase(Locale.ROOT);}
            catch(Exception ignored){return true;}
            if(key.equals("access_token")||key.equals("refresh_token")||
                key.equals("client_secret")||key.equals("authorization")||
                key.equals("session_key")||key.equals("session_secret")||
                key.equals("bduss")||key.equals("cookie"))return true;
        }
        return false;
    }
    /** Removes only the explicit OAuth access_token; preserves signed CDN parameters. */
    static String withoutOAuthToken(String source) throws Exception{
        URL url=new URL(source);
        if(!trusted(url))throw new Exception("untrusted_host");
        if(url.getQuery()==null)return source;
        int mark=source.indexOf('?');
        List<String> keep=new ArrayList<>();
        for(String part:url.getQuery().split("&",-1)){
            String key=part.split("=",2)[0];
            String normalized=URLDecoder.decode(key,"UTF-8");
            if(!"access_token".equalsIgnoreCase(normalized))keep.add(part);
        }
        return source.substring(0,mark)+(keep.isEmpty()?"":"?"+String.join("&",keep));
    }
    private static boolean fileMime(String contentType){
        if(contentType==null)return true;
        String mime=contentType.toLowerCase(Locale.ROOT).split(";",2)[0].trim();
        return !(mime.startsWith("text/")||mime.contains("json")||
            mime.contains("xml")||mime.contains("html"));
    }
    static Probe request(String raw,boolean range){
        return request(raw,range,CHROME_UA);
    }
    static Probe request(String raw,boolean range,String userAgent){
        URL current;
        try{current=new URL(raw);}
        catch(Exception ex){return new Probe(-1,false,"unknown","","invalid_url");}
        for(int hop=0;hop<5;hop++){
            if(!trusted(current))return new Probe(-1,false,"unknown","","untrusted_redirect");
            HttpURLConnection con=null;
            try{
                con=(HttpURLConnection)current.openConnection();
                con.setInstanceFollowRedirects(false);
                con.setUseCaches(false);
                con.setConnectTimeout(12000);
                con.setReadTimeout(16000);
                con.setRequestMethod("GET");
                con.setRequestProperty("User-Agent",userAgent);
                con.setRequestProperty("Accept","*/*");
                if(range)con.setRequestProperty("Range","bytes=0-0");
                // No Authorization, Cookie, Referer or Baidu-specific User-Agent.
                int http=con.getResponseCode();
                if(http==301||http==302||http==303||http==307||http==308){
                    String target=con.getHeaderField("Location");
                    if(target==null)return new Probe(http,false,"unknown","","missing_location");
                    URL next=new URL(current,target);
                    if(!trusted(next))return new Probe(http,false,"unknown","","redirect_outside_baidu");
                    current=next;
                    continue;
                }
                String type=con.getContentType();
                String sanitized=type==null?"unknown":type.split(";",2)[0].toLowerCase(Locale.ROOT).trim();
                if(!sanitized.matches("[a-z0-9.+-]+/[a-z0-9.+-]+"))sanitized="unknown";
                if(http!=200&&http!=206)
                    return new Probe(http,false,sanitized,current.toExternalForm(),"http_rejected");
                if(!fileMime(type))
                    return new Probe(http,false,sanitized,current.toExternalForm(),"not_file_mime");
                // Read at most one byte then close, never transfer the entire file.
                try(InputStream in=con.getInputStream()){
                    boolean exists=in.read()!=-1;
                    return new Probe(http,exists,sanitized,current.toExternalForm(),
                        exists?"received_file_byte":"empty_body");
                }
            }catch(Exception ex){
                return new Probe(-1,false,"unknown",current.toExternalForm(),
                    "network_"+ex.getClass().getSimpleName());
            }finally{
                if(con!=null)con.disconnect();
            }
        }
        return new Probe(-1,false,"unknown","","redirect_limit");
    }
    static String withOAuthToken(String unsigned,String access) throws Exception {
        URL url=new URL(unsigned);
        if(!trusted(url)||dangerousQuery(url))throw new Exception("invalid_candidate");
        if(access==null||access.length()<10)throw new Exception("invalid_oauth");
        return unsigned+(url.getQuery()==null?"?":"&")+"access_token="+URLEncoder.encode(access,"UTF-8");
    }
    public static Report inspect(String authorizedFinalUrl,String knownAccessToken) throws Exception {
        // Preserve the signed CDN URL; only strip the OAuth access token if present.
        String candidate=withoutOAuthToken(authorizedFinalUrl);
        boolean stripped=!candidate.equals(authorizedFinalUrl);
        URL first=new URL(candidate);
        if(dangerousQuery(first)) {
            Probe refused=new Probe(-1,false,"unknown","","sensitive_parameters");
            return new Report(refused,refused,refused,refused,refused,stripped,false,
                "","candidate_contains_credentials");
        }
        // All profile requests are made directly from this phone. No server.
        // Normal GET is deliberately tested without Range, Cookie or OAuth.
        Probe range=request(candidate,true,CHROME_UA);
        Probe normal=request(candidate,false,CHROME_UA);
        // UA-only: no OAuth token, but the exact Baidu downloader UA.
        Probe uaOnly=request(candidate,true,BAIDU_UA);
        // Check if the Baidu UA can download without a Range header.
        // This is distinct from a stock Chrome browser: its UA is different.
        Probe uaNoRange=request(candidate,false,BAIDU_UA);
        // OAuth-only: a valid account token, but an ordinary Chrome UA.
        // Only one byte requested; the token never goes to logs/clipboard.
        Probe tokenOnly=request(withOAuthToken(candidate,knownAccessToken),true,CHROME_UA);
        URL checked=new URL(normal.finalUrl.isEmpty()?candidate:normal.finalUrl);
        boolean ready=normal.dataRead && trusted(checked) && !dangerousQuery(checked);
        String result=ready?normal.finalUrl:"";
        String detail;
        if(ready)detail="same_device_browser_request_succeeded";
        else if(uaOnly.dataRead && !tokenOnly.dataRead)detail="baidu_user_agent_condition";
        else if(tokenOnly.dataRead && !uaOnly.dataRead)detail="oauth_authentication_condition";
        else if(uaOnly.dataRead && tokenOnly.dataRead)detail="either_special_ua_or_oauth_condition";
        else detail="other_server_conditions";
        return new Report(range,normal,uaOnly,uaNoRange,tokenOnly,stripped,ready,result,detail);
    }
}
