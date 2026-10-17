package com.panlocal.tool;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/**
 * Device-local owner credentials. Never included in source/APK and never logged.
 * The non-exportable AES key lives in AndroidKeyStore. Preferences store only
 * a public App Key, a random IV and AES-256-GCM ciphertext of the SecretKey.
 * Application android:allowBackup=false prevents automatic data backup.
 */
public final class LocalCredentialVault {
    private static final String KEY_ALIAS="panlocal-local-credential-key-v1";
    private static final String PREFS="panlocal-local-credentials";
    private static final String KEY_APP="app_key";
    private static final String KEY_IV="iv";
    private static final String KEY_CIPHER="ciphertext";
    private static final String KEY_OAUTH_IV="oauth_iv";
    private static final String KEY_OAUTH_BLOB="oauth_blob";
    private LocalCredentialVault(){}

    private static SharedPreferences preferences(Context context){
        return context.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    }
    private static SecretKey getKey() throws Exception {
        KeyStore keystore=KeyStore.getInstance("AndroidKeyStore");
        keystore.load(null);
        KeyStore.Entry stored=keystore.getEntry(KEY_ALIAS,null);
        if(stored instanceof KeyStore.SecretKeyEntry){
            return ((KeyStore.SecretKeyEntry)stored).getSecretKey();
        }
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        KeyGenParameterSpec spec=new KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build();
        generator.init(spec);
        return generator.generateKey();
    }
    public static synchronized String savedAppKey(Context context){
        return preferences(context).getString(KEY_APP,"");
    }
    public static synchronized boolean hasSavedSecret(Context context){
        SharedPreferences p=preferences(context);
        return !p.getString(KEY_APP,"").isEmpty()
            && !p.getString(KEY_IV,"").isEmpty()
            && !p.getString(KEY_CIPHER,"").isEmpty();
    }
    /** Stored refresh/access tokens share Android Keystore encryption, never plaintext prefs. */
    public static synchronized void saveOAuth(Context context,String appKey,String access,String refresh,long expiryMillis) throws Exception{
        if(appKey==null||!appKey.equals(savedAppKey(context)))throw new Exception("app_key_mismatch");
        if(access==null||access.isEmpty())throw new Exception("empty_token");
        JSONObject blob=new JSONObject();
        blob.put("access",access).put("refresh",refresh==null?"":refresh).put("expiry",expiryMillis);
        Cipher aes=Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE,getKey());
        aes.updateAAD(("oauth:"+appKey).getBytes(StandardCharsets.UTF_8));
        byte[] encrypted=aes.doFinal(blob.toString().getBytes(StandardCharsets.UTF_8));
        if(!preferences(context).edit()
            .putString(KEY_OAUTH_IV,Base64.encodeToString(aes.getIV(),Base64.NO_WRAP))
            .putString(KEY_OAUTH_BLOB,Base64.encodeToString(encrypted,Base64.NO_WRAP)).commit())
            throw new Exception("token_storage_failed");
    }
    public static synchronized JSONObject loadOAuth(Context context,String appKey) throws Exception{
        SharedPreferences p=preferences(context);
        if(appKey==null||!appKey.equals(savedAppKey(context)))return null;
        String iv=p.getString(KEY_OAUTH_IV,""),blob=p.getString(KEY_OAUTH_BLOB,"");
        if(iv.isEmpty()||blob.isEmpty())return null;
        Cipher aes=Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.DECRYPT_MODE,getKey(),new GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)));
        aes.updateAAD(("oauth:"+appKey).getBytes(StandardCharsets.UTF_8));
        return new JSONObject(new String(aes.doFinal(Base64.decode(blob,Base64.NO_WRAP)),StandardCharsets.UTF_8));
    }
    public static synchronized void clearOAuth(Context context){
        preferences(context).edit().remove(KEY_OAUTH_IV).remove(KEY_OAUTH_BLOB).commit();
    }
    public static synchronized void save(Context context,String appKey,String secret) throws Exception {
        if(appKey==null||!appKey.matches("[A-Za-z0-9_-]{8,128}"))
            throw new IllegalArgumentException("invalid_app_key");
        if(secret==null||!secret.matches("[A-Za-z0-9._~-]{12,512}"))
            throw new IllegalArgumentException("invalid_secret");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,getKey());
        cipher.updateAAD(appKey.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted=cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
        String iv=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP);
        String ciphertext=Base64.encodeToString(encrypted,Base64.NO_WRAP);
        if(!appKey.equals(savedAppKey(context)))clearOAuth(context);
        if(!preferences(context).edit().putString(KEY_APP,appKey)
            .putString(KEY_IV,iv).putString(KEY_CIPHER,ciphertext).commit()){
            throw new IllegalStateException("local_storage_failed");
        }
    }
    public static synchronized String secretFor(Context context,String appKey) throws Exception {
        SharedPreferences p=preferences(context);
        if(appKey==null||!appKey.equals(p.getString(KEY_APP,"")))return "";
        String iv=p.getString(KEY_IV,""),ciphertext=p.getString(KEY_CIPHER,"");
        if(iv.isEmpty()||ciphertext.isEmpty())return "";
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,getKey(),
            new GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)));
        cipher.updateAAD(appKey.getBytes(StandardCharsets.UTF_8));
        byte[] decrypted=cipher.doFinal(Base64.decode(ciphertext,Base64.NO_WRAP));
        return new String(decrypted,StandardCharsets.UTF_8);
    }
    public static synchronized void clear(Context context) throws Exception{
        // Remove data first; a Keystore deletion failure must not retain ciphertext.
        if(!preferences(context).edit().clear().commit())
            throw new IllegalStateException("local_clear_failed");
        KeyStore keystore=KeyStore.getInstance("AndroidKeyStore");
        keystore.load(null);
        if(keystore.containsAlias(KEY_ALIAS))keystore.deleteEntry(KEY_ALIAS);
    }
}
