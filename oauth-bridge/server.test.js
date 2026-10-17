"use strict";
const test=require("node:test");
const assert=require("node:assert/strict");
const {makeService}=require("./server");

test("OAuth flow binds callback state to an opaque phone session and does not leak tokens",async()=>{
  const server=makeService({appKey:"test-client-id",secret:"test-private-secret",origin:"https://899yx.online",
    fetchImpl:async url=>{
      const u=new URL(url);
      let data={};
      if(u.hostname==="openapi.baidu.com"){
        assert.equal(u.searchParams.get("client_secret"),"test-private-secret");
        data={access_token:"secret-baidu-access-token",expires_in:3600,refresh_token:"private-refresh-token"};
      }else if(u.searchParams.get("method")==="uinfo")data={errno:0};
      else if(u.searchParams.get("method")==="search")data={errno:0,list:[{fs_id:987654321,server_filename:"sample.mp4",path:"/sample.mp4",size:1000,isdir:0}]};
      else if(u.searchParams.get("method")==="filemetas")data={errno:0,list:[{dlink:"https://d.pcs.baidu.com/test?exp=60"}]};
      return {ok:true,text:async()=>JSON.stringify(data)};
    }});
  await new Promise(resolve=>server.listen(0,"127.0.0.1",resolve));
  const base="http://127.0.0.1:"+server.address().port;
  async function post(path,body){
    const r=await fetch(base+path,{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify(body)});
    return {status:r.status,data:await r.json()};
  }
  try{
    let health=await fetch(base+"/api/panlocal/health");
    assert.equal((await health.json()).ok,true);
    const start=await post("/api/panlocal/start",{});
    assert.equal(start.status,200);
    assert.ok(start.data.session_token.length>24);
    assert.ok(!JSON.stringify(start.data).includes("test-private-secret"));
    const url=new URL(start.data.authorize_url);
    assert.equal(url.hostname,"openapi.baidu.com");
    assert.equal(url.searchParams.get("redirect_uri"),"https://899yx.online/api/baidu/callback");
    assert.equal(url.searchParams.get("response_type"),"code");
    const session={session_id:start.data.session_id,session_token:start.data.session_token};
    let bad=await post("/api/panlocal/status",{session_id:session.session_id,session_token:"wrong-token"});
    assert.equal(bad.status,401);
    let pending=await post("/api/panlocal/status",session);
    assert.equal(pending.data.status,"pending");
    let callback=await fetch(base+"/api/baidu/callback?state="+url.searchParams.get("state")+"&code=test-code");
    assert.equal(callback.status,200);
    assert.ok(!(await callback.text()).includes("secret-baidu-access-token"));
    const ready=await post("/api/panlocal/status",session);
    assert.equal(ready.data.status,"ready");
    assert.equal((await post("/api/panlocal/account",session)).data.ok,true);
    const search=await post("/api/panlocal/search",{...session,key:"sample"});
    assert.equal(search.data.files.length,1);
    assert.equal(search.data.files[0].fs_id,"987654321");
    const metas=await post("/api/panlocal/filemetas",{...session,fs_id:"987654321"});
    assert.equal(metas.data.dlink_present,true);
    const logout=await post("/api/panlocal/logout",session);
    assert.equal(logout.data.ok,true);
    assert.equal((await post("/api/panlocal/status",session)).status,401);
  }finally{await new Promise(resolve=>server.close(resolve));}
});
test("refuses to start without real server-side configuration",()=>{
  assert.throws(()=>makeService({appKey:"x",secret:"y",origin:"http://example.org/"}),/HTTPS origin/);
});
