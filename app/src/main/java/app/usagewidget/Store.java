package app.usagewidget;
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

final class Store {
    private static final String KEY="usagewidget-token";
    final SharedPreferences prefs;
    Store(Context context) { prefs=context.getSharedPreferences("usagewidget",Context.MODE_PRIVATE); }
    String account() { return prefs.getString("account",""); }
    boolean configured() { return !account().isEmpty() && prefs.contains("token"); }
    boolean demo() { return prefs.getBoolean("demo",false); }
    String raw() { return demo()?Billing.demo():prefs.getString("snapshot",""); }
    long checked() { return prefs.getLong("checked",0); }
    String error() { return prefs.getString("error",""); }
    boolean paid() { return prefs.getBoolean("paid",true); }
    String subscriptionsRaw() { return demo() ? Subscriptions.demo() : prefs.getString("subscriptions",""); }
    String subsError()        { return prefs.getString("subs_error",""); }
    long   subsChecked()      { return prefs.getLong("subs_checked",0); }
    private SecretKey key() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if(ks.containsAlias(KEY)) return (SecretKey)ks.getKey(KEY,null);
        KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(KEY,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return gen.generateKey();
    }
    String token() throws Exception {
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(prefs.getString("iv",""),Base64.NO_WRAP)));
        return new String(c.doFinal(Base64.decode(prefs.getString("token",""),Base64.NO_WRAP)),StandardCharsets.UTF_8);
    }
    void save(String account,String token,boolean paid) throws Exception {
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,key());
        String encrypted=Base64.encodeToString(c.doFinal(token.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
        SharedPreferences.Editor e=prefs.edit();
        if(!account.equals(account())) e.remove("snapshot").remove("checked").remove("subscriptions").remove("subs_checked").remove("subs_error");
        if(!e.putString("account",account).putString("token",encrypted).putString("iv",Base64.encodeToString(c.getIV(),Base64.NO_WRAP))
            .putBoolean("paid",paid).putBoolean("demo",false).remove("error").commit()) throw new IllegalStateException("Could not save settings.");
    }
    void clear() throws Exception {
        prefs.edit().clear().commit(); KeyStore ks=KeyStore.getInstance("AndroidKeyStore"); ks.load(null); ks.deleteEntry(KEY);
    }
}
