package app.usagewidget;

import android.app.*;
import android.appwidget.AppWidgetManager;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.math.BigDecimal;

public final class MainActivity extends Activity {
    private final int ink=Color.rgb(239,245,238), muted=Color.rgb(173,187,178), orange=Color.rgb(255,186,122);
    private LinearLayout page;
    private TextView feedback;
    private EditText account,token;
    private CheckBox paid;
    private boolean busy;

    @Override public void onCreate(Bundle state) { super.onCreate(state); render(); }
    private int dp(float n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private GradientDrawable background(int color,int radius) {
        GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private TextView text(LinearLayout parent,String value,int size,int color) {
        TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setPadding(0,dp(5),0,dp(5)); parent.addView(t); return t;
    }
    private LinearLayout card() {
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20),dp(18),dp(20),dp(18));
        GradientDrawable bg=background(Color.rgb(23,35,29),24); bg.setStroke(dp(1),Color.rgb(57,72,62)); box.setBackground(bg);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2); params.setMargins(0,dp(16),0,dp(8)); page.addView(box,params); return box;
    }
    private Button button(LinearLayout parent,String value,Runnable action) {
        Button b=new Button(this); b.setText(value); b.setAllCaps(false); b.setTextColor(ink); b.setTextSize(14);
        b.setBackground(background(Color.rgb(48,65,54),14)); b.setMinHeight(dp(48));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.topMargin=dp(10); parent.addView(b,p);
        b.setOnClickListener(v->{if(!busy) action.run();}); return b;
    }
    private void render() {
        Store s=new Store(this);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Color.rgb(17,25,22));
        page=new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(24),dp(20),dp(24),dp(32)); scroll.addView(page);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom); return insets;
        });
        setContentView(scroll);
        text(page,"USAGE WIDGET FOR CLOUDFLARE",12,orange).setLetterSpacing(.18f);
        text(page,"A little peace\nof mind.",34,ink).setTypeface(null,Typeface.BOLD);
        text(page,"Your cloud usage, one glance away.",15,muted);
        LinearLayout overview=card();
        if(!s.raw().isEmpty()) {
            try {
                Billing b=Billing.parse(s.raw());
                Subscriptions subs=null; boolean subsParseFailed=false;
                String subsRaw=s.subscriptionsRaw();
                if(!subsRaw.isEmpty()) { try { subs=Subscriptions.parse(subsRaw); } catch(Exception e) { subsParseFailed=true; } }
                java.time.LocalDate today=java.time.LocalDate.now(java.time.ZoneOffset.UTC);
                text(overview,s.demo()?"DEMO · SAMPLE DATA":"USAGE CHARGES",11,orange).setLetterSpacing(.1f);
                text(overview,b.total(),38,ink).setTypeface(null,Typeface.BOLD);
                text(overview,b.period(),13,muted);
                Billing.WorkersMeter req=b.workersMeter(false), cpu=b.workersMeter(true);
                renderMeter(overview,"Workers requests",req,Billing.REQUESTS_INCLUDED,"requests",s.paid(),b);
                renderMeter(overview,"Workers CPU",cpu,Billing.CPU_MS_INCLUDED,"ms",s.paid(),b);
                if(req==null && !s.demo()) renderCandidates(overview,b);
                text(overview,b.coverage(),12,muted);
                if(!s.demo()) text(overview,Billing.checked(s.checked()),12,muted);
                if(!s.demo() && b.oldCoverage()) text(overview,"Cloudflare's latest data is more than two days old.",13,orange);
                text(overview,"Usage charges exclude subscription fees, tax, and credits. Cloudflare updates billing data daily.",12,muted);
                if(s.paid()) text(overview,"Selected plan: Workers Paid · $5/month base, separate from usage. Allowances: 10M requests and 30M CPU ms per month.",12,muted);
                if(!s.error().isEmpty() && !s.demo()) text(overview,s.error()+" Last successful snapshot is shown.",13,orange);
                if(!s.demo()) button(overview,"Refresh usage",this::refresh);
                else if(s.configured()) button(overview,"Return to my account",()->{s.prefs.edit().putBoolean("demo",false).apply(); UsageWidget.updateAll(this); render();});
                button(overview,"Add home-screen widget",this::pin);
                LinearLayout upcoming=card();
                text(upcoming,"UPCOMING CHARGES",11,orange).setLetterSpacing(.1f);
                Billing.CostProjection cp=b.projectTotals();
                String usage="Usage so far "+b.usageThisCycle();
                switch(cp.reason) {
                    case OK: usage+=" · on pace for about "+Billing.moneyJoin(cp.projected)
                                +" by cycle end (day "+cp.elapsedDays+" of "+cp.cycleDays+")"; break;
                    case EARLY: usage+=" · too early in the cycle to project"; break;
                    default: usage+=" · cycle dates unavailable"; break;
                }
                text(upcoming,usage,13,muted);
                String subsError=s.subsError();
                if(subsRaw.isEmpty() && subsError.isEmpty()) text(upcoming,"Subscriptions not loaded yet. Refresh to fetch them.",12,muted);
                else if(subsRaw.isEmpty() && !subsError.isEmpty()) text(upcoming,"Subscriptions could not be refreshed: "+subsError,13,orange);
                else if(subsParseFailed) text(upcoming,"Saved subscriptions could not be read.",13,orange);
                else {
                    java.util.List<Subscriptions.Item> act=subs.active();
                    if(act.isEmpty()) text(upcoming,"No active account subscriptions returned.",12,muted);
                    else for(Subscriptions.Item item:act) {
                        String line=item.name+" · "
                            +(item.priced()?Billing.money(item.price,item.currency):"price unavailable")
                            +" "+item.frequency
                            +(item.periodEnd==null?"":" · renews "+Billing.date(item.periodEnd))
                            +("Paid".equalsIgnoreCase(item.state)?"":" ("+item.state+")");
                        text(upcoming,line,13,ink);
                    }
                    if(!subsError.isEmpty()) text(upcoming,"Subscriptions could not be refreshed: "+subsError+" Last saved list shown.",13,orange);
                }
                java.util.SortedMap<String,java.math.BigDecimal> due=(subs==null)?new java.util.TreeMap<>():subs.dueWithin(today,31);
                java.util.SortedMap<String,java.math.BigDecimal> base=(cp.reason==Billing.Projection.Reason.OK)?cp.projected:b.currentCycleTotals();
                java.util.SortedMap<String,java.math.BigDecimal> est=Billing.combine(base,due);
                if(!est.isEmpty()) {
                    text(upcoming,"Estimated next bill: about "+Billing.moneyJoin(est)+".",15,ink).setTypeface(null,Typeface.BOLD);
                    String word=(cp.reason==Billing.Projection.Reason.OK)?"estimate":"so far";
                    text(upcoming,"Usage "+word+" plus subscriptions renewing within 31 days. Excludes tax, credits, and zone plans, which need zone permissions.",12,muted);
                }
                if(subs!=null && subs.mentionsWorkersPaid()) text(upcoming,"Your account lists a Workers Paid subscription.",12,muted);
                if(!b.metrics.isEmpty()) {
                    LinearLayout details=card(); text(details,"THE BREAKDOWN",11,orange).setLetterSpacing(.12f);
                    for(Billing.Metric metric:b.metrics) {
                        text(details,metric.label(),15,ink).setTypeface(null,Typeface.BOLD);
                        text(details,metric.quantity()+"\n"+Billing.money(metric.cost,metric.currency)+" usage charge",13,muted);
                    }
                    text(details,"Services appear exactly as Cloudflare reports them. Missing metrics are not treated as zero.",12,muted);
                    button(details,"Copy row names",()->{
                        android.content.ClipboardManager cm=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Usage rows",String.join("\n",Billing.rowLabels(b))));
                        Toast.makeText(this,"Row names copied",Toast.LENGTH_SHORT).show();
                    });
                }
            } catch(Exception e) { text(overview,"Saved usage could not be read. Refresh or reconnect below.",15,orange); }
        } else {
            text(overview,"YOUR ACCOUNT, AT A GLANCE",11,orange);
            text(overview,"Let's connect.",27,ink).setTypeface(null,Typeface.BOLD);
            text(overview,"See compute, requests, and usage charges directly from Cloudflare. Works without Google Play services.",14,muted);
            button(overview,"Try the demo widget",()->{s.prefs.edit().putBoolean("demo",true).apply(); UsageWidget.updateAll(this); render();});
        }
        LinearLayout setup=card(); text(setup,s.configured()?"ACCOUNT SETTINGS":"CONNECT CLOUDFLARE",11,orange).setLetterSpacing(.1f);
        text(setup,"Account ID",13,muted);
        account=new EditText(this); account.setSingleLine(true); account.setTextColor(ink); account.setTextSize(13);
        account.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        account.setText(s.account());
        account.setHint("32-character account ID from your Cloudflare dashboard URL");
        account.setHintTextColor(muted);
        setup.addView(account,new LinearLayout.LayoutParams(-1,dp(52)));
        text(setup,"Billing Read API token",13,muted);
        token=new EditText(this); token.setSingleLine(true); token.setTextColor(ink); token.setTextSize(13); token.setHintTextColor(muted);
        token.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setHint(s.configured()?"Leave blank to keep saved token":"Paste your read-only API token");
        token.setSaveEnabled(false); token.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        setup.addView(token,new LinearLayout.LayoutParams(-1,dp(52)));
        paid=new CheckBox(this); paid.setText("I use Workers Paid ($5/month base)"); paid.setTextColor(ink); paid.setTextSize(13); paid.setChecked(s.paid()); setup.addView(paid);
        text(setup,"Create a custom token: Account > Billing > Read. Scope it to this account. Your deployment login uses different permissions.",13,muted);
        button(setup,"Open Cloudflare API tokens",()->browse("https://dash.cloudflare.com/profile/api-tokens"));
        button(setup,"Save and connect",this::connect);
        feedback=text(setup,"Token encrypted on this device. Direct HTTPS to Cloudflare. No server, ads, analytics, or subscription for this app.",12,muted);
        if(s.configured()) {
            button(setup,"Open Cloudflare billing",()->browse("https://dash.cloudflare.com/"+s.account()+"/billing"));
            button(setup,"Disconnect and clear local data",()->new AlertDialog.Builder(this).setTitle("Disconnect account?")
                .setMessage("Remove the saved token and usage snapshot from this device?")
                .setNegativeButton("Cancel",null).setPositiveButton("Disconnect",(d,w)->disconnect()).show());
        }
        text(page,"Long-press the widget to resize it. It adapts from a one-line strip to a full card.",12,muted);
        text(page,"Updates about every 3 hours, when Android allows. On GrapheneOS, allow Network access for this app. Tap the widget to see details.",12,muted);
        text(page,"Independent tool. Not affiliated with or endorsed by Cloudflare, Inc.",11,muted);
    }
    private void renderMeter(LinearLayout parent,String title,Billing.WorkersMeter m,BigDecimal included,String unit,boolean paid,Billing b) {
        text(parent,title,14,ink);
        if(m==null) { text(parent,"Not identified in this billing snapshot",12,muted); return; }
        if(m.consumed==null) { text(parent,"Identified, but this snapshot has no usage amount",12,muted); return; }
        BigDecimal consumed=m.consumed;
        if(paid) text(parent,Billing.compact(consumed)+" / "+Billing.compact(included)+" "+unit+" · "+Billing.percentText(consumed,included)+" used",13,muted);
        else text(parent,Billing.compact(consumed)+" "+unit,13,muted);
        if(paid) {
            String leftUnit=unit.equals("ms")?"CPU ms":unit;
            text(parent,"Left this cycle: "+Billing.compact(Billing.remaining(consumed,included))+" of "+Billing.compact(included)+" "+leftUnit,13,muted);
            ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); bar.setMax(1000);
            bar.setProgress(consumed.multiply(new BigDecimal("1000")).divide(included,0,java.math.RoundingMode.DOWN).max(BigDecimal.ZERO).min(new BigDecimal("1000")).intValue());
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(orange));
            parent.addView(bar,new LinearLayout.LayoutParams(-1,dp(8)));
            text(parent,paceText(b.project(m),included),13,muted);
        }
    }
    private String paceText(Billing.Projection p,BigDecimal included) {
        switch(p.reason) {
            case OK:
                if(p.overage.signum()==0)
                    return "On pace for about "+Billing.compact(p.projected)+" by cycle end (day "+p.elapsedDays+" of "+p.cycleDays+"). No overage expected.";
                return "On pace for about "+Billing.compact(p.projected)+" by cycle end (day "+p.elapsedDays+" of "+p.cycleDays+"). That exceeds the "+Billing.compact(included)+" allowance; about "+Billing.money(p.overage,"USD")+" extra.";
            case NON_USD:
                return "On pace for about "+Billing.compact(p.projected)+" by cycle end (day "+p.elapsedDays+" of "+p.cycleDays+"). Overage estimate shown only for USD billing.";
            case EARLY:
                return "Too early in the cycle to project a total (day "+p.elapsedDays+" of "+p.cycleDays+").";
            case PARTIAL:
                return "Projection withheld: some Workers rows had no usage amount.";
            case UNKNOWN_DATES:
            default:
                return "Not enough cycle date information to project a total.";
        }
    }
    private void renderCandidates(LinearLayout parent,Billing b) {
        java.util.List<String> cand=b.workersCandidates();
        if(cand.isEmpty()) text(parent,"No rows mention Workers in this snapshot.",12,muted);
        else {
            text(parent,"Workers requests were not identified in this snapshot. Rows seen:",12,orange);
            for(String s:cand) text(parent,"• "+s,12,muted);
        }
    }
    private void connect() {
        String id=account.getText().toString().trim(), entered=token.getText().toString().trim(); boolean chosenPaid=paid.isChecked();
        busy=true; feedback.setText("Checking billing access…");
        Repository.IO.execute(()->{
            String error=null;
            synchronized(Repository.LOCK) {
                try {
                    Store s=new Store(this);
                    if(entered.isEmpty() && !s.configured()) throw new IllegalArgumentException("Paste a Billing Read API token to connect.");
                    if(entered.isEmpty() && !id.equals(s.account())) throw new IllegalArgumentException("Enter a token when changing accounts.");
                    String secret=entered.isEmpty()?s.token():entered;
                    String raw=Repository.fetch(id,secret);
                    s.save(id,secret,chosenPaid);
                    s.prefs.edit().putString("snapshot",raw).putLong("checked",System.currentTimeMillis()).remove("status").commit();
                } catch(Exception e) { error=e instanceof IllegalArgumentException?e.getMessage():"Could not connect. Check network access and try again."; }
            }
            String result=error;
            runOnUiThread(()->{ busy=false; if(isDestroyed()) return;
                if(result!=null) { feedback.setText(result); feedback.setTextColor(orange); }
                else { token.setText(""); RefreshJob.schedule(this); UsageWidget.updateAll(this); render(); fetchSubscriptionsAsync(); }
            });
        });
    }
    private void fetchSubscriptionsAsync() {
        Repository.IO.execute(()->{
            synchronized(Repository.LOCK) {
                Store s=new Store(this);
                if(s.demo()||!s.configured()) return;
                try {
                    String subs=Repository.fetchSubscriptions(s.account(),s.token());
                    s.prefs.edit().putString("subscriptions",subs).putLong("subs_checked",System.currentTimeMillis()).remove("subs_error").commit();
                } catch(Exception e) {
                    String m=e instanceof IllegalArgumentException?e.getMessage():"Could not refresh subscriptions.";
                    s.prefs.edit().putString("subs_error",m).commit();
                }
            }
            UsageWidget.updateAll(this);
            runOnUiThread(()->{ if(!isDestroyed()) render(); });
        });
    }
    private void refresh() {
        busy=true; Toast.makeText(this,"Refreshing usage…",Toast.LENGTH_SHORT).show();
        Repository.IO.execute(()->{ Repository.refresh(getApplicationContext()); runOnUiThread(()->{busy=false;if(!isDestroyed())render();}); });
    }
    private void disconnect() {
        Repository.IO.execute(()->{
            synchronized(Repository.LOCK) { try { new Store(this).clear(); RefreshJob.cancel(this); } catch(Exception ignored) { } }
            runOnUiThread(()->{UsageWidget.updateAll(this);if(!isDestroyed())render();});
        });
    }
    private void pin() {
        AppWidgetManager m=AppWidgetManager.getInstance(this);
        if(m.isRequestPinAppWidgetSupported()) m.requestPinAppWidget(new ComponentName(this,UsageWidget.class),null,null);
        else Toast.makeText(this,"Long-press the home screen, choose Widgets, then Usage Widget for Cloudflare.",Toast.LENGTH_LONG).show();
    }
    private void browse(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url))); }
        catch(ActivityNotFoundException e) { Toast.makeText(this,"Install or enable a browser to open Cloudflare.",Toast.LENGTH_LONG).show(); }
    }
}
