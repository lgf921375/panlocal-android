package com.panlocal.tool;

import android.content.Context;
import java.math.BigInteger;
import java.util.Arrays;

/**
 * Personal test builds ONLY.
 * The public repository contains a fixed DEX string slot; the private APK
 * receives a reversible base62->base85 representation of its owner credential.
 * This is format compatibility, NOT secret encryption or anti-decompilation.
 */
final class OwnerTestingSeed {
    private static final String INJECT_SLOT="QQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQ";
    private static final String PREF="panlocal-owner-test-seed";
    private static final String SYMBOLS="0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final BigInteger RADIX_62=BigInteger.valueOf(62);
    private static final BigInteger RADIX_85=BigInteger.valueOf(85);
    private OwnerTestingSeed(){}

    static boolean initialize(Context context,String appKey) throws Exception {
        if(LocalCredentialVault.hasSavedSecret(context))return false;
        if(context.getSharedPreferences(PREF,0).getBoolean("disabled",false))return false;
        String slot=INJECT_SLOT;
        if(slot.length()!=32 || slot.charAt(0)!='Q'||slot.charAt(31)!='Z')
            return false; // unchanged public-build placeholder
        BigInteger value=BigInteger.ZERO;
        for(int i=1;i<31;i++){
            int digit=slot.charAt(i)-33;
            if(digit<0||digit>=85)return false;
            value=value.multiply(RADIX_85).add(BigInteger.valueOf(digit));
        }
        char[] secret=new char[32];
        try{
            for(int i=secret.length-1;i>=0;i--){
                BigInteger[] qr=value.divideAndRemainder(RADIX_62);
                secret[i]=SYMBOLS.charAt(qr[1].intValue());
                value=qr[0];
            }
            if(value.signum()!=0)return false;
            LocalCredentialVault.save(context,appKey,new String(secret));
            return true;
        }finally{Arrays.fill(secret,'\0');}
    }
    static void disable(Context context){
        context.getSharedPreferences(PREF,0).edit().putBoolean("disabled",true).commit();
    }
    static void enable(Context context){
        context.getSharedPreferences(PREF,0).edit().putBoolean("disabled",false).commit();
    }
}
