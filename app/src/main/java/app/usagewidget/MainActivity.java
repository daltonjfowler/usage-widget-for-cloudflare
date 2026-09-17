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
    /** Update-notification tap sets this so onCreate scrolls to the Updates card. */
    public static final String EXTRA_SHOW_UPDATES="app.usagewidget.SHOW_UPDATES";
    private final int ink=Color.rgb(239,245,238), muted=Color.rgb(173,187,178), orange=Color.rgb(255,186,122);
    private LinearLayout page;
    private TextView feedback;
    private EditText account,token;
    private CheckBox paid;
    private boolean busy;
    private ScrollView scrollRoot;
    private LinearLayout updatesCard;
    private TextView updateStatus;
    private Button updateButton;
    private EditText updateUrlInput;
    private Updater.Info pendingUpdate;
    private BarChartView weekChart;
    private Button[] weekChips;
    private TextView weekCaption;
    private int weekMetric;   // 0 charges, 1 requests, 2 CPU; kept across re-renders
    private TextView widgetAlphaLabel;
    private Button[] accentSwatches;
    private int tab;   // 0 Usage, 1 Settings; kept across re-renders
    private java.util.List<View> usageViews, settingsViews;
    private boolean buildingSettings;
    private Button[] tabButtons;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Notifications.createChannels(this);
        RefreshJob.scheduleUpdateCheck(this);
        render();
        maybeScrollToUpdates();
    }
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
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2); params.setMargins(0,dp(16),0,dp(8)); page.addView(box,params);
        if(settingsViews!=null) (buildingSettings?settingsViews:usageViews).add(box);
        return box;
    }
    private Button button(LinearLayout parent,String value,Runnable action) {
        Button b=new Button(this); b.setText(value); b.setAllCaps(false); b.setTextColor(ink); b.setTextSize(14);
        b.setBackground(background(Color.rgb(48,65,54),14)); b.setMinHeight(dp(48));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.topMargin=dp(10); parent.addView(b,p);
        b.setOnClickListener(v->{if(!busy) action.run();}); return b;
    }
    private void render() {
        Store s=new Store(this);
        ScrollView scroll=new ScrollView(this); scrollRoot=scroll; scroll.setFillViewport(true); scroll.setBackgroundColor(Color.rgb(17,25,22));
        page=new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(24),dp(20),dp(24),dp(32)); scroll.addView(page);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom); return insets;
        });
        setContentView(scroll);
        text(page,"USAGE WIDGET FOR CLOUDFLARE",12,orange).setLetterSpacing(.18f);
        text(page,"A little peace\nof mind.",34,ink).setTypeface(null,Typeface.BOLD);
        text(page,"Your cloud usage, one glance away.",15,muted);
        usageViews=new java.util.ArrayList<>(); settingsViews=new java.util.ArrayList<>(); buildingSettings=false;
        boolean tabbed=s.configured()||s.demo();
        if(tabbed) buildTabBar();
        LinearLayout overview=card();
        if(!s.raw().isEmpty()) {
            try {
                Billing b=Billing.parse(s.raw());
                Subscriptions subs=null; boolean subsParseFailed=false;
                String subsRaw=s.subscriptionsRaw();
                if(!subsRaw.isEmpty()) { try { subs=Subscriptions.parse(subsRaw); } catch(Exception e) { subsParseFailed=true; } }
                java.time.LocalDate today=java.time.LocalDate.now(java.time.ZoneOffset.UTC);
                text(overview,s.demo()?"DEMO · SAMPLE DATA":"USAGE CHARGES · THIS CYCLE",11,orange).setLetterSpacing(.1f);
                text(overview,b.usageThisCycle(),38,ink).setTypeface(null,Typeface.BOLD);
                text(overview,b.period(),13,muted);
                if(b.multipleCycles()) {
                    java.time.LocalDate e=b.earliestCycle();
                    text(overview,"Running total"+(e==null?"":" since "+Billing.date(e))+": "+b.runningTotal(),12,muted);
                }
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
                if(!s.demo()) { try { buildWeek(); } catch(Exception ignored) { } }
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
                if(!b.metrics.isEmpty()) buildBreakdown(b);
            } catch(Exception e) { text(overview,"Saved usage could not be read. Refresh or reconnect below.",15,orange); }
        } else {
            text(overview,"YOUR ACCOUNT, AT A GLANCE",11,orange);
            text(overview,"Let's connect.",27,ink).setTypeface(null,Typeface.BOLD);
            text(overview,"See compute, requests, and usage charges directly from Cloudflare. Works without Google Play services.",14,muted);
            button(overview,"Try the demo widget",()->{s.prefs.edit().putBoolean("demo",true).apply(); UsageWidget.updateAll(this); render();});
        }
        buildingSettings=true;
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
        if(tabbed) { buildWidgetAppearance(); buildUpdates(); }
        buildAbout();
        if(tabbed) applyTab();
    }
    // ---- tabs ----------------------------------------------------------------
    /** Two-tab switch (Usage / Settings) so the page is not one long scroll once connected. */
    private void buildTabBar() {
        LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2); rp.setMargins(0,dp(18),0,0); page.addView(row,rp);
        String[] names={"Usage","Settings"}; tabButtons=new Button[2];
        for(int i=0;i<2;i++) {
            final int idx=i;
            Button t=new Button(this); t.setText(names[i]); t.setAllCaps(false); t.setTextSize(14); t.setMinHeight(dp(44)); t.setMinWidth(0);
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-2,1f); cp.rightMargin=(i<1)?dp(8):0;
            t.setOnClickListener(v->{ tab=idx; applyTab(); if(scrollRoot!=null) scrollRoot.smoothScrollTo(0,0); });
            row.addView(t,cp); tabButtons[i]=t;
        }
    }
    private void applyTab() {
        if(usageViews!=null) for(View v:usageViews) v.setVisibility(tab==0?View.VISIBLE:View.GONE);
        if(settingsViews!=null) for(View v:settingsViews) v.setVisibility(tab==1?View.VISIBLE:View.GONE);
        if(tabButtons!=null) for(int i=0;i<tabButtons.length;i++) {
            boolean sel=i==tab;
            tabButtons[i].setBackground(background(sel?Color.rgb(48,65,54):Color.rgb(28,42,35),14));
            tabButtons[i].setTextColor(sel?ink:muted);
        }
    }
    private void buildAbout() {
        LinearLayout box=card();
        text(box,"ABOUT",11,orange).setLetterSpacing(.1f);
        text(box,"Long-press the widget to resize it. It adapts from a one-line strip to a full card.",12,muted);
        text(box,"Updates about every 3 hours, when Android allows. On GrapheneOS, allow Network access for this app. Tap the widget to see details.",12,muted);
        text(box,"Independent tool. Not affiliated with or endorsed by Cloudflare, Inc.",11,muted);
    }
    // ---- week view -----------------------------------------------------------
    /** A seven-day bar chart of daily usage, with a Charges/Requests/CPU toggle. History is local-only. */
    private void buildWeek() {
        LinearLayout box=card();
        text(box,"THIS WEEK",11,orange).setLetterSpacing(.12f);
        text(box,"Daily usage",22,ink).setTypeface(null,Typeface.BOLD);
        LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2); rp.topMargin=dp(10); box.addView(row,rp);
        String[] names={"Charges","Requests","CPU"};
        weekChips=new Button[3];
        for(int i=0;i<3;i++) {
            final int idx=i;
            Button chip=new Button(this); chip.setText(names[i]); chip.setAllCaps(false); chip.setTextSize(13);
            chip.setMinHeight(dp(42)); chip.setMinWidth(0); chip.setPadding(dp(10),0,dp(10),0);
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-2,1f); cp.rightMargin=(i<2)?dp(8):0;
            chip.setOnClickListener(v->{ weekMetric=idx; refreshChart(); });
            row.addView(chip,cp); weekChips[i]=chip;
        }
        weekChart=new BarChartView(this);
        LinearLayout.LayoutParams chartP=new LinearLayout.LayoutParams(-1,dp(150)); chartP.topMargin=dp(14); box.addView(weekChart,chartP);
        weekCaption=text(box,"",12,muted);
        refreshChart();
    }
    private void refreshChart() {
        if(weekChart==null) return;
        History.Metric m=weekMetric==1?History.Metric.REQUESTS:weekMetric==2?History.Metric.CPU:History.Metric.CHARGES;
        History.Week wk=History.week(History.read(this),m);
        int n=wk.days.size();
        String[] days=new String[n]; float[] frac=new float[n]; String[] vals=new String[n];
        boolean[] absent=new boolean[n]; boolean[] base=new boolean[n];
        double max=wk.max.doubleValue();
        for(int i=0;i<n;i++) {
            History.Day d=wk.days.get(i);
            days[i]=Billing.date(d.date); absent[i]=d.absent; base[i]=d.baseline;
            boolean plain=!d.absent && !d.baseline;
            frac[i]=(plain && max>0)?(float)(d.value.doubleValue()/max):0f;
            vals[i]=plain?formatValue(wk,d.value):"";
        }
        weekChart.set(days,frac,vals,absent,base);
        for(int i=0;i<weekChips.length;i++) {
            boolean sel=i==weekMetric;
            weekChips[i].setBackground(background(sel?Color.rgb(48,65,54):Color.rgb(28,42,35),14));
            weekChips[i].setTextColor(sel?ink:muted);
        }
        if(!wk.hasData) weekCaption.setText("Collecting your daily history. One bar appears per day of Cloudflare usage, starting now — check back tomorrow. Cloudflare reports usage a day or two behind.");
        else if(weekMetric==0) weekCaption.setText("Each bar is one day's usage charge ("+wk.unit+"). Charges reset every billing cycle. Blank days had no snapshot; Cloudflare reports usage a day or two behind.");
        else weekCaption.setText("Each bar is one day's Workers "+(weekMetric==1?"requests":"CPU time")+". Blank days had no snapshot; Cloudflare reports usage a day or two behind.");
    }
    private String formatValue(History.Week wk,java.math.BigDecimal v) {
        if(wk.money) return Billing.money(v,wk.unit);
        return Billing.compact(v)+(wk.unit.equals("ms")?" ms":"");
    }
    // ---- the breakdown -------------------------------------------------------
    /** Collapsed by default. Shows only charged rows when opened; no-charge rows hide behind a sub-toggle. */
    private void buildBreakdown(Billing b) {
        LinearLayout details=card();
        text(details,"THE BREAKDOWN",11,orange).setLetterSpacing(.12f);
        java.util.List<Billing.Metric> charged=new java.util.ArrayList<>(), included=new java.util.ArrayList<>();
        for(Billing.Metric m:b.metrics) { if(m.cost.signum()!=0) charged.add(m); else included.add(m); }
        text(details,charged.size()+(charged.size()==1?" service with a charge":" services with charges")
            +" · "+included.size()+" included at no charge",12,muted);

        LinearLayout body=new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setVisibility(View.GONE);
        final Button[] toggle=new Button[1];
        toggle[0]=button(details,"Show breakdown",()->{
            boolean show=body.getVisibility()!=View.VISIBLE;
            body.setVisibility(show?View.VISIBLE:View.GONE);
            toggle[0].setText(show?"Hide breakdown":"Show breakdown");
        });
        details.addView(body,new LinearLayout.LayoutParams(-1,-2));

        if(charged.isEmpty()) text(body,"No usage charges this cycle. Everything is within the included allowances.",13,muted);
        for(Billing.Metric m:charged) {
            text(body,shortName(m),14,ink).setTypeface(null,Typeface.BOLD);
            text(body,m.quantity()+" · "+Billing.money(m.cost,m.currency),13,muted);
        }
        if(!included.isEmpty()) {
            LinearLayout inc=new LinearLayout(this); inc.setOrientation(LinearLayout.VERTICAL); inc.setVisibility(View.GONE);
            final Button[] incToggle=new Button[1];
            incToggle[0]=button(body,"Show "+included.size()+" included services",()->{
                boolean show=inc.getVisibility()!=View.VISIBLE;
                inc.setVisibility(show?View.VISIBLE:View.GONE);
                incToggle[0].setText(show?"Hide included services":"Show "+included.size()+" included services");
            });
            body.addView(inc,new LinearLayout.LayoutParams(-1,-2));
            for(Billing.Metric m:included) {
                text(inc,shortName(m),13,muted);
                text(inc,m.quantity()+" · "+Billing.money(m.cost,m.currency),12,muted);
            }
        }
        text(body,"Names are shortened. Services appear as Cloudflare reports them; missing metrics are not treated as zero.",12,muted);
        button(body,"Copy full row names",()->{
            android.content.ClipboardManager cm=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Usage rows",String.join("\n",Billing.rowLabels(b))));
            Toast.makeText(this,"Row names copied",Toast.LENGTH_SHORT).show();
        });
    }
    /** Trim Cloudflare's long service name to the part before its "(First … included)" allowance note. */
    private static String shortName(Billing.Metric m) {
        String n=m.name; int p=n.indexOf(" (First ");
        return p>0 ? n.substring(0,p) : n;
    }
    // ---- widget appearance ---------------------------------------------------
    /** Global widget styling: a background-transparency slider and accent swatches. Applies to every widget. */
    private void buildWidgetAppearance() {
        Store s=new Store(this);
        LinearLayout box=card();
        text(box,"WIDGET APPEARANCE",11,orange).setLetterSpacing(.1f);
        text(box,"Style the home-screen widget. Changes apply to every placed widget.",12,muted);
        widgetAlphaLabel=text(box,"",13,ink);
        updateAlphaLabel(s.widgetOpacity());
        SeekBar seek=new SeekBar(this); seek.setMax(90); seek.setProgress(100-s.widgetOpacity());
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(orange));
        seek.setThumbTintList(android.content.res.ColorStateList.valueOf(orange));
        box.addView(seek,new LinearLayout.LayoutParams(-1,-2));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb,int p,boolean fromUser) {
                int op=100-p; new Store(MainActivity.this).setWidgetOpacity(op); updateAlphaLabel(op);
            }
            public void onStartTrackingTouch(SeekBar sb) { }
            public void onStopTrackingTouch(SeekBar sb) { UsageWidget.updateAll(MainActivity.this); }
        });
        text(box,"A more transparent widget blends into the wallpaper. Corners stay rounded on Android 12 and up.",12,muted);
        text(box,"Accent",13,muted);
        LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2); rp.topMargin=dp(6); box.addView(row,rp);
        accentSwatches=new Button[UsageWidget.ACCENTS.length];
        for(int i=0;i<UsageWidget.ACCENTS.length;i++) {
            final int idx=i;
            Button sw=new Button(this); sw.setText(""); sw.setMinWidth(0); sw.setMinHeight(dp(44));
            sw.setContentDescription(UsageWidget.ACCENT_NAMES[i]);
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,dp(44),1f); cp.rightMargin=(i<UsageWidget.ACCENTS.length-1)?dp(8):0;
            sw.setOnClickListener(v->{ new Store(MainActivity.this).setWidgetAccent(idx); styleSwatches(); UsageWidget.updateAll(MainActivity.this); });
            row.addView(sw,cp); accentSwatches[i]=sw;
        }
        styleSwatches();
    }
    private void updateAlphaLabel(int opacity) {
        if(widgetAlphaLabel!=null) widgetAlphaLabel.setText("Background transparency "+(100-opacity)+"% · widget "+opacity+"% opaque");
    }
    private void styleSwatches() {
        if(accentSwatches==null) return;
        int sel=new Store(this).widgetAccent();
        for(int i=0;i<accentSwatches.length;i++) {
            GradientDrawable g=background(UsageWidget.ACCENTS[i],12);
            if(i==sel) g.setStroke(dp(3),ink);
            accentSwatches[i].setBackground(g);
        }
    }
    // ---- in-app updates ------------------------------------------------------
    private void buildUpdates() {
        pendingUpdate=null;
        LinearLayout box=card(); updatesCard=box;
        text(box,getString(R.string.settings_updates),11,orange).setLetterSpacing(.1f);
        updateStatus=text(box,getString(R.string.update_you_have,BuildConfig.VERSION_NAME,BuildConfig.VERSION_CODE),13,muted);
        button(box,getString(R.string.update_check),this::checkForUpdate);
        updateButton=button(box,getString(R.string.update_button),this::startUpdate);
        updateButton.setVisibility(View.GONE);
        text(box,getString(R.string.update_source_label),13,muted);
        updateUrlInput=new EditText(this); updateUrlInput.setSingleLine(true); updateUrlInput.setTextColor(ink); updateUrlInput.setTextSize(13);
        updateUrlInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        updateUrlInput.setHint(R.string.update_source_hint); updateUrlInput.setHintTextColor(muted);
        updateUrlInput.setText(new Store(this).updateUrl());
        box.addView(updateUrlInput,new LinearLayout.LayoutParams(-1,dp(52)));
    }
    private void checkForUpdate() {
        pendingUpdate=null; updateButton.setVisibility(View.GONE);
        final String url=updateUrlInput.getText().toString().trim();
        updateStatus.setText(getString(R.string.update_checking)); busy=true;
        Repository.IO.execute(()->{
            if(!url.isEmpty()) new Store(this).setUpdateUrl(url);
            final String base=new Store(this).updateUrl();
            final Updater.CheckResult r=Updater.check(BuildConfig.VERSION_CODE,base,new Updater.HttpSource());
            runOnUiThread(()->{ busy=false; if(isDestroyed()) return; renderCheck(r); });
        });
    }
    private void renderCheck(Updater.CheckResult r) {
        switch(r.status) {
            case AVAILABLE:
                pendingUpdate=r.info; String size=Updater.formatSize(r.info.size);
                if(r.info.notes==null || r.info.notes.isEmpty())
                    updateStatus.setText(getString(R.string.update_available_no_notes,r.info.versionName,r.info.versionCode,size));
                else updateStatus.setText(getString(R.string.update_available,r.info.versionName,r.info.versionCode,size,r.info.notes));
                updateButton.setEnabled(true); updateButton.setVisibility(View.VISIBLE); break;
            case UP_TO_DATE: updateStatus.setText(R.string.update_latest); break;
            case NO_NETWORK: updateStatus.setText(R.string.update_no_connection); break;
            default: updateStatus.setText(R.string.update_bad_shape); break;
        }
    }
    private void startUpdate() {
        if(pendingUpdate==null) return;
        // GrapheneOS gates installs from other apps; send the user to turn it on first.
        if(!Updater.canInstall(this)) { updateStatus.setText(R.string.update_needs_permission); openUnknownSources(); return; }
        final Updater.Info info=pendingUpdate; final String base=new Store(this).updateUrl();
        busy=true; updateButton.setEnabled(false); updateStatus.setText(getString(R.string.update_downloading,0));
        Repository.IO.execute(()->{
            final Updater.DownloadResult dr=Updater.download(getApplicationContext(),base,info,new Updater.HttpSource(),
                pct->runOnUiThread(()->{ if(!isDestroyed()) updateStatus.setText(getString(R.string.update_downloading,pct)); }));
            runOnUiThread(()->{ if(isDestroyed()) return; onDownloadDone(dr); });
        });
    }
    private void onDownloadDone(Updater.DownloadResult dr) {
        switch(dr.status) {
            case DONE:
                updateStatus.setText(R.string.update_installing);
                final java.io.File file=dr.file;
                Repository.IO.execute(()->{
                    try { Updater.install(getApplicationContext(),file); busy=false; }
                    catch(Exception e) { runOnUiThread(()->{ busy=false; if(isDestroyed()) return; updateStatus.setText(R.string.update_bad_shape); updateButton.setEnabled(true); }); }
                });
                break;
            case NO_NETWORK: busy=false; updateStatus.setText(R.string.update_no_connection); updateButton.setEnabled(true); break;
            case CHECKSUM_MISMATCH: busy=false; updateStatus.setText(R.string.update_checksum_mismatch); updateButton.setEnabled(true); break;
            default: busy=false; updateStatus.setText(R.string.update_signature_mismatch); updateButton.setEnabled(true); break;
        }
    }
    private void openUnknownSources() {
        try { startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName()))); }
        catch(RuntimeException ignored) { }
    }
    private void maybeScrollToUpdates() {
        if(!getIntent().getBooleanExtra(EXTRA_SHOW_UPDATES,false)) return;
        tab=1; applyTab();   // Updates lives under Settings now
        final ScrollView scroll=scrollRoot; final LinearLayout target=updatesCard;
        if(scroll==null || target==null) return;
        scroll.post(()->{
            int y=0; View v=target;
            while(v!=null && v!=scroll) { y+=v.getTop(); ViewParent pnt=v.getParent(); v=(pnt instanceof View)?(View)pnt:null; }
            scroll.smoothScrollTo(0,y);
        });
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
                    History.record(this,raw);
                } catch(Exception e) { error=e instanceof IllegalArgumentException?e.getMessage():"Could not connect. Check network access and try again."; }
            }
            String result=error;
            runOnUiThread(()->{ busy=false; if(isDestroyed()) return;
                if(result!=null) { feedback.setText(result); feedback.setTextColor(orange); }
                else { token.setText(""); tab=0; RefreshJob.schedule(this); UsageWidget.updateAll(this); render(); fetchSubscriptionsAsync(); }
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
