package com.panlocal.tool;

import java.net.URL;

/** Offline regression checks: never connect to a real account or CDN. */
public final class BrowserDirectLinkProbeSelfTest {
    private static void check(boolean test,String message){
        if(!test)throw new AssertionError(message);
    }
    public static void main(String[] args)throws Exception {
        String source="https://d.pcs.baidu.com/file/abcdef?fid=123&access_token=TOPSECRET&sign=signature&expires=8h";
        String removed=BrowserDirectLinkProbe.withoutOAuthToken(source);
        check(!removed.contains("TOPSECRET"),"OAuth bearer token not removed");
        check(removed.equals("https://d.pcs.baidu.com/file/abcdef?fid=123&sign=signature&expires=8h"),
            "Query signature or order mutated");
        check(BrowserDirectLinkProbe.trusted(new URL(removed)),"Official download host rejected");
        check(!BrowserDirectLinkProbe.dangerousQuery(new URL(removed)),"Safe URL marked unsafe");
        check(BrowserDirectLinkProbe.dangerousQuery(new URL(source)),"Embedded access token not detected");
        check(!BrowserDirectLinkProbe.trusted(new URL("http://d.pcs.baidu.com/file/abc")),"HTTP permitted");
        check(!BrowserDirectLinkProbe.trusted(new URL("https://d.pcs.baidu.com.attacker.test/file/abc")),"Host suffix spoof permitted");
        check(!BrowserDirectLinkProbe.trusted(new URL("https://user@d.pcs.baidu.com/file/abc")),"userinfo permitted");
        check(!BrowserDirectLinkProbe.trusted(new URL("https://d.pcs.baidu.com:8443/file/abc")),"custom port permitted");
        check(BrowserDirectLinkProbe.withoutOAuthToken("https://d.pcs.baidu.com/f?access_token=abc")
            .equals("https://d.pcs.baidu.com/f"),"Last query param not removed");
        check(BrowserDirectLinkProbe.withoutOAuthToken("https://d.pcs.baidu.com/f?sign=x")
            .equals("https://d.pcs.baidu.com/f?sign=x"),"No-op changed unsigned URL");
        System.out.println("PASS: browser candidate URL sanitization and trusted hosts");
    }
}
