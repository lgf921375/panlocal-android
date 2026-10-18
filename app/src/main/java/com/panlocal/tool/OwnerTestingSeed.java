package com.panlocal.tool;

import android.content.Context;

/**
 * Personal test build only: the public repository contains a 32-character
 * placeholder. Only the private final APK receives the user-supplied key,
 * after the normal public CI build is complete.
 *
 * APK secrets are extractable. Never distribute the personalized APK publicly.
 */
final class OwnerTestingSeed {
    static final String INJECT_SLOT="QQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQQ";
    private static final String PREF="panlocal-owner-test-seed";
    private OwnerTestingSeed(){}
    static boolean initialize(Context context,String appKey) throws Exception {
        if(LocalCredentialVault.hasSavedSecret(context))return false;
        if(context.getSharedPreferences(PREF,0).getBoolean("disabled",false))return false;
        String raw=INJECT_SLOT;
        boolean isPlaceholder=true;
        for(int i=0;i<raw.length();i++){
            if(raw.charAt(i)!='Q'){isPlaceholder=false;break;}
        }
        if(isPlaceholder)return false;
        LocalCredentialVault.save(context,appKey,raw);
        return true;
    }
    static void disable(Context context){
        context.getSharedPreferences(PREF,0).edit().putBoolean("disabled",true).commit();
    }
    static void enable(Context context){
        context.getSharedPreferences(PREF,0).edit().putBoolean("disabled",false).commit();
    }
}
