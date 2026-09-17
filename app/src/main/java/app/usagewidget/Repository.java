package app.usagewidget;
import android.content.Context;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

final class Repository {
    static final ExecutorService IO=Executors.newSingleThreadExecutor();
    static final Object LOCK=new Object();
    static String fetch(String account,String token) throws Exception {
        if(!account.matches("[a-fA-F0-9]{32}")) throw new IllegalArgumentException("Enter a 32-character Cloudflare account ID.");
        if(token.isBlank() || token.contains("\n") || token.contains("\r")) throw new IllegalArgumentException("Enter a valid Billing Read API token.");
        HttpURLConnection c=(HttpURLConnection)new URL("https://api.cloudflare.com/client/v4/accounts/"+account+"/billable-usage").openConnection();
        try {
            c.setInstanceFollowRedirects(false); c.setRequestMethod("GET"); c.setConnectTimeout(15000); c.setReadTimeout(20000);
            c.setRequestProperty("Authorization","Bearer "+token); c.setRequestProperty("Accept","application/json");
            int status=c.getResponseCode();
            if(status==401 || status==403) throw new IllegalArgumentException("Billing access denied. Use a token with Account > Billing > Read for this account.");
            if(status==429) throw new IllegalArgumentException("Cloudflare rate limit reached. Try again later.");
            if(status!=200) throw new IllegalArgumentException("Cloudflare returned HTTP "+status+". Last snapshot kept.");
            try(InputStream in=c.getInputStream()) {
                // Avoid readNBytes: unavailable on some supported Android releases.
                java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream(); byte[] buf=new byte[8192]; int n;
                while((n=in.read(buf))!=-1) { if(out.size()+n>8*1024*1024) throw new IllegalArgumentException("Usage response is too large."); out.write(buf,0,n); }
                String raw=out.toString(StandardCharsets.UTF_8.name()); Billing.parse(raw); return raw;
            }
        } finally { c.disconnect(); }
    }
    static String fetchSubscriptions(String account,String token) throws Exception {
        if(!account.matches("[a-fA-F0-9]{32}")) throw new IllegalArgumentException("Enter a 32-character Cloudflare account ID.");
        if(token.isBlank() || token.contains("\n") || token.contains("\r")) throw new IllegalArgumentException("Enter a valid Billing Read API token.");
        HttpURLConnection c=(HttpURLConnection)new URL("https://api.cloudflare.com/client/v4/accounts/"+account+"/subscriptions").openConnection();
        try {
            c.setInstanceFollowRedirects(false); c.setRequestMethod("GET"); c.setConnectTimeout(15000); c.setReadTimeout(20000);
            c.setRequestProperty("Authorization","Bearer "+token); c.setRequestProperty("Accept","application/json");
            int status=c.getResponseCode();
            if(status==401 || status==403) throw new IllegalArgumentException("Billing access denied. Use a token with Account > Billing > Read for this account.");
            if(status==429) throw new IllegalArgumentException("Cloudflare rate limit reached. Try again later.");
            if(status!=200) throw new IllegalArgumentException("Cloudflare returned HTTP "+status+". Last subscriptions kept.");
            try(InputStream in=c.getInputStream()) {
                // Avoid readNBytes: unavailable on some supported Android releases.
                java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream(); byte[] buf=new byte[8192]; int n;
                while((n=in.read(buf))!=-1) { if(out.size()+n>8*1024*1024) throw new IllegalArgumentException("Subscriptions response is too large."); out.write(buf,0,n); }
                String raw=out.toString(StandardCharsets.UTF_8.name()); Subscriptions.parse(raw); return raw;
            }
        } finally { c.disconnect(); }
    }
    static boolean refresh(Context context) {
        synchronized(LOCK) {
            Store s=new Store(context); if(s.demo() || !s.configured()) return true;
            try {
                String raw=fetch(s.account(),s.token());
                s.prefs.edit().putString("snapshot",raw).putLong("checked",System.currentTimeMillis()).remove("error").remove("status").commit();
                History.record(context,raw);
                try {
                    String subs=fetchSubscriptions(s.account(),s.token());
                    s.prefs.edit().putString("subscriptions",subs).putLong("subs_checked",System.currentTimeMillis()).remove("subs_error").commit();
                } catch(Exception e) {
                    String m=e instanceof IllegalArgumentException ? e.getMessage() : "Could not refresh subscriptions.";
                    s.prefs.edit().putString("subs_error",m).commit();   // keep old "subscriptions"
                }
                return true;
            } catch(Exception e) {
                String error=e instanceof IllegalArgumentException?e.getMessage():"Could not refresh. Check your connection and saved token.";
                s.prefs.edit().putString("error",error).remove("status").commit(); return false;
            } finally { UsageWidget.updateAll(context); }
        }
    }
}
