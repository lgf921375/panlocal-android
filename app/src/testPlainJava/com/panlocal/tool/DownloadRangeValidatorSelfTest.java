package com.panlocal.tool;

/** Offline validation; no Baidu network calls and no account credentials. */
public final class DownloadRangeValidatorSelfTest {
    private static void expectValid(int status,long offset,long expected,String range,long length){
        DownloadRangeValidator.validate(status,offset,expected,range,length);
    }
    private static void expectInvalid(int status,long offset,long expected,String range,long length){
        try{DownloadRangeValidator.validate(status,offset,expected,range,length);}
        catch(IllegalArgumentException correct){return;}
        throw new AssertionError("Invalid HTTP range accepted");
    }
    public static void main(String[] args){
        long big=2_381_418_495L;
        expectValid(206,0,big,"bytes 0-0/"+big,1);
        expectValid(206,1,big,"bytes 1-999/"+big,999);
        expectValid(206,2_147_483_648L,big,
                    "bytes 2147483648-2381418494/"+big,233_934_847L);
        expectValid(200,0,big,null,big);
        expectValid(200,0,big,null,-1);
        expectInvalid(206,1,big,"bytes 0-999/"+big,1000);
        expectInvalid(206,0,big,"bytes 0-0/3000000000",1);
        expectInvalid(206,1,big,"bytes */"+big,-1);
        expectInvalid(206,10,big,"bytes 10-9/"+big,-1);
        expectInvalid(206,10,big,"bytes 10-"+big+"/"+big,-1);
        expectInvalid(206,0,big,"bytes 0-0/"+big,2);
        expectInvalid(206,0,big,null,1);
        expectInvalid(200,100,big,null,-1);
        expectInvalid(200,0,big,null,1000);
        expectInvalid(206,0,big,"bytes 0-9223372036854775808/"+big,-1);
        expectInvalid(416,0,big,"bytes */"+big,-1);
        System.out.println("PASS: strict HTTP Content-Range and 64-bit resume tests");
    }
}
