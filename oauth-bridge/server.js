"use strict";
/**
 * PanLocal OAuth bridge (prototype). Runs ONLY on loopback behind an HTTPS
 * reverse proxy. All Baidu OAuth tokens stay in RAM on this server.
 * No users' files are uploaded or proxied through this service.
 * Requires Node.js >= 20. No dependencies and no credential logging.
 */
const http=require("node:http");
const crypto=require("node:crypto");
const {URL,URLSearchParams}=require("node:url");

const CALLBACK_PATH="/api/baidu/callback";
const PREFIX="/api/panlocal/";
const rnd=(n=32)=>crypto.randomBytes(n).toString("base64url");
function fixedEquals(a,b){
  if(typeof a!=="string"||typeof b!=="string"||a.length!==b.length||a.length<16)return false;
  return crypto.timingSafeEqual(Buffer.from(a),Buffer.from(b));
}
function makeService(options={}){
  const appKey=options.appKey||process.env.BAIDU_APP_KEY;
  const secret=options.secret||process.env.BAIDU_APP_SECRET;
  const publicOrigin=options.origin||process.env.PANLOCAL_PUBLIC_ORIGIN;
  if(!appKey||!secret||!publicOrigin)throw Error("Set BAIDU_APP_KEY, BAIDU_APP_SECRET, PANLOCAL_PUBLIC_ORIGIN");
  const origin=new URL(publicOrigin);
  if(origin.protocol!=="https:"||origin.username||origin.password||origin.search||origin.hash||origin.pathname!=="/")throw Error("PUBLIC_ORIGIN must be an HTTPS origin ending in /");
  const callback=new URL(CALLBACK_PATH,origin).toString();
  const upstream=options.fetchImpl||fetch;
  const sessions=new Map();
  const recent=new Map();
  function send(res,status,data){
    res.writeHead(status,{"content-type":"application/json; charset=utf-8","cache-control":"no-store","pragma":"no-cache","x-content-type-options":"nosniff","referrer-policy":"no-referrer"});
    res.end(JSON.stringify(data));
  }
  function page(res,status,message){
    res.writeHead(status,{"content-type":"text/html; charset=utf-8","cache-control":"no-store","content-security-policy":"default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'","referrer-policy":"no-referrer","x-content-type-options":"nosniff"});
    res.end('<!doctype html><html lang="zh"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>PanLocal 授权状态</title><body style="font:18px sans-serif;padding:28px"><h3>'+message+'</h3><p>可返回 PanLocal APP 点击“检查授权结果”。</p></body></html>');
  }
  function trim(){
    const now=Date.now();
    for(const [id,s] of sessions)if(now>s.expires)sessions.delete(id);
    for(const [key,t] of recent)if(now-t.time>60000)recent.delete(key);
  }
  async function body(req){
    if(req.headers["content-type"]?.split(";")[0]!=="application/json")throw Error("content_type");
    let chunks=[],length=0;
    for await(const part of req){length+=part.length;if(length>8192)throw Error("body_too_large");chunks.push(part);}
    try{return JSON.parse(Buffer.concat(chunks).toString("utf8"));}catch(_){throw Error("invalid_json");}
  }
  function auth(input){
    const s=sessions.get(input.session_id);
    if(!s||!fixedEquals(s.proof,input.session_token))return null;
    if(Date.now()>s.expires){sessions.delete(input.session_id);return null;}
    return s;
  }
  async function getBaidu(url,params){
    const uri=new URL(url);
    for(const [k,v] of Object.entries(params))uri.searchParams.set(k,String(v));
    const response=await upstream(uri.toString(),{method:"GET",redirect:"error",signal:AbortSignal.timeout(15000),headers:{"Accept":"application/json"}});
    if(!response.ok)throw Error("baidu_http_"+response.status);
    const text=await response.text();
    if(text.length>400000)throw Error("baidu_response_too_large");
    return JSON.parse(text);
  }
  async function account(s){
    const data=await getBaidu("https://pan.baidu.com/rest/2.0/xpan/nas",{"method":"uinfo",access_token:s.accessToken});
    return {ok:data.errno===0,errno:data.errno??-1};
  }
  async function handler(req,res){
    try{
      trim();
      const u=new URL(req.url,"http://localhost");
      const path=u.pathname;
      if(req.method==="GET"&&path===PREFIX+"health"){
        return send(res,200,{ok:true,service:"panlocal-oauth-bridge",version:1,authorized:false});
      }
      if(req.method==="GET"&&path===CALLBACK_PATH){
        const state=u.searchParams.get("state"),code=u.searchParams.get("code");
        const session=[...sessions.values()].find(s=>s.state===state&&s.status==="pending");
        if(!session)return page(res,400,"授权会话不存在或已过期");
        if(u.searchParams.has("error")||!code){session.status="failed";return page(res,400,"百度授权没有完成");}
        // OAuth code is single-use and should be exchanged only on this server.
        session.status="exchanging";
        try{
          const token=await getBaidu("https://openapi.baidu.com/oauth/2.0/token",{
            grant_type:"authorization_code",code,client_id:appKey,client_secret:secret,redirect_uri:callback
          });
          if(typeof token.access_token!=="string"||!token.access_token||!token.expires_in)throw Error("no_access_token");
          session.accessToken=token.access_token;
          session.expires=Date.now()+Math.min(Number(token.expires_in)*1000,2*60*60*1000);
          session.status="ready";
          // Refresh token intentionally not retained in this P1 prototype.
          return page(res,200,"百度账号授权完成");
        }catch(_){
          session.status="failed";
          return page(res,502,"授权交换失败；请在 APP 中重新尝试");
        }
      }
      if(!path.startsWith(PREFIX))return send(res,404,{error:"not_found"});
      if(req.method!=="POST")return send(res,405,{error:"method_not_allowed"});
      const data=await body(req);
      if(path===PREFIX+"start"){
        // In-memory rate limit: maximum 5 new sessions per remote IP per minute.
        const ip=req.socket.remoteAddress||"unknown",now=Date.now();
        const used=recent.get(ip)||{time:now,count:0};
        if(now-used.time>60000){used.time=now;used.count=0;}
        if(++used.count>5)return send(res,429,{error:"rate_limited"});
        recent.set(ip,used);
        if(sessions.size>=60)return send(res,503,{error:"busy"});
        const id=rnd(24),proof=rnd(),state=rnd(24);
        sessions.set(id,{id,proof,state,status:"pending",accessToken:null,expires:now+10*60*1000});
        const url=new URL("https://openapi.baidu.com/oauth/2.0/authorize");
        url.searchParams.set("response_type","code");
        url.searchParams.set("client_id",appKey);
        url.searchParams.set("redirect_uri",callback);
        url.searchParams.set("scope","basic,netdisk");
        url.searchParams.set("state",state);
        return send(res,200,{session_id:id,session_token:proof,authorize_url:url.toString(),expires_in:600});
      }
      const s=auth(data);
      if(!s)return send(res,401,{error:"invalid_session"});
      if(path===PREFIX+"status")return send(res,200,{status:s.status,authenticated:s.status==="ready"});
      if(path===PREFIX+"logout"){
        sessions.delete(s.id);return send(res,200,{ok:true});
      }
      if(s.status!=="ready")return send(res,409,{error:"authorization_not_ready"});
      if(path===PREFIX+"account"){
        try{return send(res,200,await account(s));}
        catch(_){return send(res,502,{error:"baidu_account_unavailable"});}
      }
      if(path===PREFIX+"search"){
        const key=data.key;
        if(typeof key!=="string"||!key.trim()||key.length>120)return send(res,400,{error:"invalid_key"});
        try{
          const result=await getBaidu("https://pan.baidu.com/rest/2.0/xpan/file",{method:"search",access_token:s.accessToken,key:key.trim(),num:30,recursion:1});
          const items=Array.isArray(result.list)?result.list.slice(0,30).map(f=>({
            fs_id:String(f.fs_id||""),name:String(f.server_filename||"").slice(0,200),
            path:String(f.path||"").slice(0,500),size:Number(f.size||0),isdir:Number(f.isdir||0)
          })):[];
          return send(res,200,{errno:result.errno??-1,files:items});
        }catch(_){return send(res,502,{error:"baidu_search_unavailable"});}
      }
      if(path===PREFIX+"filemetas"){
        const id=String(data.fs_id||"");
        if(!/^[0-9]{1,22}$/.test(id))return send(res,400,{error:"invalid_fs_id"});
        try{
          const result=await getBaidu("https://pan.baidu.com/rest/2.0/xpan/multimedia",{
            method:"filemetas",access_token:s.accessToken,fsids:"["+id+"]",dlink:1
          });
          const first=Array.isArray(result.list)?result.list[0]:null;
          const link=typeof first?.dlink==="string"?first.dlink:"";
          const valid=link.startsWith("https://");
          return send(res,200,{errno:result.errno??-1,dlink_present:valid,dlink:valid?link:""});
        }catch(_){return send(res,502,{error:"baidu_filemetas_unavailable"});}
      }
      return send(res,404,{error:"not_found"});
    }catch(e){
      const invalid=["invalid_json","content_type","body_too_large"].includes(e.message);
      return send(res,invalid?400:500,{error:invalid?"invalid_request":"internal_error"});
    }
  }
  return http.createServer((req,res)=>{handler(req,res).catch(()=>send(res,500,{error:"internal_error"}));});
}
if(require.main===module){
  const server=makeService();
  const port=Number(process.env.PANLOCAL_PORT||19731);
  server.listen(port,"127.0.0.1",()=>console.log("PanLocal OAuth bridge listening on 127.0.0.1:"+port+" (no credentials logged)"));
}
module.exports={makeService};
