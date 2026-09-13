package app.usagewidget;

import android.app.PendingIntent;
import android.appwidget.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.util.SizeF;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

public final class UsageWidget extends AppWidgetProvider {
    private static final String REFRESH="app.usagewidget.REFRESH";
    /** Family breakdown rows: name left, amount right-aligned. One row per line familyLines() can return at XL. */
    private static final int[] FAM_ROW ={R.id.fam_row_0,R.id.fam_row_1,R.id.fam_row_2,R.id.fam_row_3,R.id.fam_row_4,R.id.fam_row_5,R.id.fam_row_6,R.id.fam_row_7};
    private static final int[] FAM_NAME={R.id.fam_name_0,R.id.fam_name_1,R.id.fam_name_2,R.id.fam_name_3,R.id.fam_name_4,R.id.fam_name_5,R.id.fam_name_6,R.id.fam_name_7};
    private static final int[] FAM_AMT ={R.id.fam_amt_0,R.id.fam_amt_1,R.id.fam_amt_2,R.id.fam_amt_3,R.id.fam_amt_4,R.id.fam_amt_5,R.id.fam_amt_6,R.id.fam_amt_7};
    private static final int BAR_WARM=0xFFFFBA7A, BAR_HOT=0xFFFF7E6B;   // bar turns hot at 80% of the allowance
    private static final int AMOUNT=0xFFEFF5EE, AMOUNT_ZERO=0xFF7F8B85; // zero amounts recede

    enum Variant { STRIP, WIDE, MEDIUM, LARGE, TALL, XL }

    @Override public void onUpdate(Context c,AppWidgetManager m,int[] ids) { updateAll(c); RefreshJob.schedule(c); RefreshJob.now(c); }
    @Override public void onAppWidgetOptionsChanged(Context c,AppWidgetManager m,int id,Bundle options) { updateAll(c); }
    @Override public void onDisabled(Context c) { RefreshJob.cancel(c); }
    @Override public void onReceive(Context c,Intent i) {
        super.onReceive(c,i);
        if(REFRESH.equals(i.getAction())) {
            Store s=new Store(c);
            if(s.demo()) { updateAll(c); return; }
            s.prefs.edit().putString("status","Refresh queued").apply();
            updateAll(c); RefreshJob.now(c);
        }
    }
    static void updateAll(Context c) {
        AppWidgetManager manager=AppWidgetManager.getInstance(c);
        for(int id:manager.getAppWidgetIds(new ComponentName(c,UsageWidget.class))) manager.updateAppWidget(id,views(c));
    }

    /** Maps each supported size to a variant. apply() with no size chooses the smallest (STRIP). */
    static RemoteViews views(Context c) {
        LinkedHashMap<SizeF,RemoteViews> map=new LinkedHashMap<>();
        map.put(new SizeF(110,40),  variant(c,Variant.STRIP));
        map.put(new SizeF(200,40),  variant(c,Variant.WIDE));
        map.put(new SizeF(180,100), variant(c,Variant.MEDIUM));
        map.put(new SizeF(180,200), variant(c,Variant.LARGE));
        map.put(new SizeF(180,256), variant(c,Variant.TALL));
        map.put(new SizeF(180,330), variant(c,Variant.XL));   // a 3-row Pixel cell (~338dp) lands here
        return new RemoteViews(map);
    }

    static RemoteViews variant(Context c,Variant v) {
        boolean strip=(v==Variant.STRIP || v==Variant.WIDE);
        RemoteViews rv=new RemoteViews(c.getPackageName(), strip ? R.layout.widget_strip : R.layout.widget);
        PendingIntent open=PendingIntent.getActivity(c,0,new Intent(c,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent refresh=PendingIntent.getBroadcast(c,1,new Intent(c,UsageWidget.class).setAction(REFRESH),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.card,open);
        rv.setOnClickPendingIntent(R.id.refresh,refresh);
        if(v==Variant.STRIP) buildStrip(c,rv);
        else if(v==Variant.WIDE) buildStripWide(c,rv);
        else buildCard(c,rv,v);
        return rv;
    }

    private static void buildStrip(Context c,RemoteViews rv) {
        Store s=new Store(c);
        rv.setViewPadding(R.id.card,px(c,6),px(c,4),px(c,6),px(c,4));
        rv.setTextViewTextSize(R.id.total,TypedValue.COMPLEX_UNIT_SP,15);
        rv.setViewVisibility(R.id.request_bar,View.GONE);
        if(!s.configured() && !s.demo()) {
            // At 110dp only the total fits alongside the refresh target, so req stays empty.
            rv.setTextViewText(R.id.total,"Connect account");
            rv.setTextViewText(R.id.req,"");
            return;
        }
        if(s.raw().isEmpty()) {
            rv.setTextViewText(R.id.total,"Waiting");
            rv.setTextViewText(R.id.req,"");
            return;
        }
        try {
            Billing b=Billing.parse(s.raw());
            rv.setTextViewText(R.id.total,b.total());
            Billing.WorkersMeter req=b.workersMeter(false);
            // At the 110dp STRIP width the total, the 32dp refresh target and a bar cannot coexist,
            // so req is bare text only (percent when paid, count otherwise) and the bar stays hidden.
            // The bar returns in the MEDIUM/LARGE/TALL card variants where the width allows it.
            String value;
            if(req==null || req.consumed==null) value="n/a";
            else if(s.paid()) value=Billing.percentText(req.consumed,Billing.REQUESTS_INCLUDED);
            else value=Billing.compact(req.consumed);
            if(s.demo()) {
                // Demo data is always labeled (AGENTS.md). At the 110dp STRIP width a labeled percent
                // ("DEMO 24%") cannot fit beside a readable total and the 32dp refresh target without
                // ellipsizing, so STRIP shows the "DEMO" label alone; the percent returns on the WIDE
                // strip and the card variants. The total drops to 13sp to leave room for the label.
                rv.setTextViewTextSize(R.id.total,TypedValue.COMPLEX_UNIT_SP,13);
                rv.setTextViewText(R.id.req,"DEMO");
            } else {
                // NORMAL: mirror footer() precedence, collapsed to a leading "! " since STRIP has no room for words.
                rv.setTextViewText(R.id.req,stripMarker(b,s)+value);
            }
        } catch(Exception e) {
            rv.setTextViewText(R.id.total,"Reconnect");
            rv.setTextViewText(R.id.req,"Open app");
        }
    }

    /** STRIP has no room for words: error or stale collapses to a leading "! "; a status carries none. */
    private static String stripMarker(Billing b,Store s) {
        if(!s.error().isEmpty()) return "! ";
        if(!s.prefs.getString("status","").isEmpty()) return "";
        boolean stale=b.oldCoverage() || System.currentTimeMillis()-s.checked()>48*60*60*1000L;
        return stale?"! ":"";
    }

    /** Wide 4x1 strip (>=200dp): both meters as text on one line. Same layout ids; bar stays hidden. */
    private static void buildStripWide(Context c,RemoteViews rv) {
        Store s=new Store(c);
        rv.setViewPadding(R.id.card,px(c,6),px(c,4),px(c,6),px(c,4));
        rv.setTextViewTextSize(R.id.total,TypedValue.COMPLEX_UNIT_SP,14);
        rv.setViewVisibility(R.id.request_bar,View.GONE);
        if(!s.configured() && !s.demo()) {
            rv.setTextViewText(R.id.total,"Connect account");
            rv.setTextViewText(R.id.req,"Open app to set up");
            return;
        }
        if(s.raw().isEmpty()) {
            rv.setTextViewText(R.id.total,"Waiting");
            rv.setTextViewText(R.id.req,s.error().isEmpty()?"Tap refresh":s.error());
            return;
        }
        try {
            Billing b=Billing.parse(s.raw());
            rv.setTextViewText(R.id.total,b.total());
            String meters=wideMeters(b.workersMeter(false),b.workersMeter(true),s.paid());
            if(s.demo()) {
                // Demo always labeled (AGENTS.md). The "DEMO ·" prefix plus both meters is long, so the
                // req line drops to 8sp to fit the 200dp key without ellipsizing.
                rv.setTextViewTextSize(R.id.req,TypedValue.COMPLEX_UNIT_SP,8);
                rv.setTextViewText(R.id.req,"DEMO · "+meters);
            } else {
                rv.setTextViewText(R.id.req,wideMarker(b,s)+meters);              // NORMAL: footer() precedence in words
            }
        } catch(Exception e) {
            rv.setTextViewText(R.id.total,"Reconnect");
            rv.setTextViewText(R.id.req,"Open app");
        }
    }

    /** WIDE has room for words: error or stale mirror footer() wording; a status carries none. */
    private static String wideMarker(Billing b,Store s) {
        if(!s.error().isEmpty()) return "Refresh failed · ";
        if(!s.prefs.getString("status","").isEmpty()) return "";
        boolean stale=b.oldCoverage() || System.currentTimeMillis()-s.checked()>48*60*60*1000L;
        return stale?"Older snapshot · ":"";
    }

    /** One-line requests + CPU summary for the wide strip (ui-findings item 1). Paid shows percent
     *  ("Requests 24% · CPU 8%"); unpaid shows counts ("Requests 2.4M · CPU 2.4M ms"). Kept short so
     *  both meters stay visible at the 200dp key without ellipsizing. */
    private static String wideMeters(Billing.WorkersMeter req,Billing.WorkersMeter cpu,boolean paid) {
        StringBuilder sb=new StringBuilder("Requests ");
        if(req==null || req.consumed==null) sb.append("n/a");
        else if(paid) sb.append(Billing.percentText(req.consumed,Billing.REQUESTS_INCLUDED));
        else sb.append(Billing.compact(req.consumed));
        if(cpu!=null && cpu.consumed!=null) {
            if(paid) sb.append(" · CPU ").append(Billing.percentText(cpu.consumed,Billing.CPU_MS_INCLUDED));
            else sb.append(" · CPU ").append(Billing.compact(cpu.consumed)).append(" ms");
        }
        return sb.toString();
    }

    private static void buildCard(Context c,RemoteViews rv,Variant v) {
        Store s=new Store(c);
        boolean medium=v==Variant.MEDIUM, tall=v==Variant.TALL, xl=v==Variant.XL;

        float totalSp; int metricsMax, freshMax;
        switch(v) {
            case MEDIUM: rv.setViewPadding(R.id.card,px(c,10),px(c,8), px(c,10),px(c,8));  totalSp=20; metricsMax=2; freshMax=1; break;
            case LARGE:  rv.setViewPadding(R.id.card,px(c,12),px(c,10),px(c,12),px(c,10)); totalSp=22; metricsMax=2; freshMax=2; break;
            case XL:     rv.setViewPadding(R.id.card,px(c,14),px(c,14),px(c,14),px(c,14)); totalSp=28; metricsMax=8; freshMax=2; break;
            default:     rv.setViewPadding(R.id.card,px(c,12),px(c,8), px(c,12),px(c,8));  totalSp=24; metricsMax=2; freshMax=2; break; // TALL
        }
        rv.setTextViewTextSize(R.id.total,TypedValue.COMPLEX_UNIT_SP,totalSp);
        rv.setInt(R.id.freshness,"setMaxLines",freshMax);
        rv.setInt(R.id.metrics,"setMaxLines",metricsMax);

        int structural=medium?View.GONE:View.VISIBLE;
        rv.setViewVisibility(R.id.title_row,structural);
        rv.setViewVisibility(R.id.subtitle,structural);
        rv.setViewVisibility(R.id.metrics,structural);
        rv.setViewVisibility(R.id.request_row,View.GONE);
        rv.setViewVisibility(R.id.cpu_row,View.GONE);
        rv.setViewVisibility(R.id.extra,View.GONE);
        rv.setViewVisibility(R.id.families,View.GONE);
        if(xl) {
            // XL has vertical room to spare, so the groups breathe. Tighter variants keep the layout defaults.
            rv.setViewLayoutMargin(R.id.request_row,RemoteViews.MARGIN_TOP,8,TypedValue.COMPLEX_UNIT_DIP);
            rv.setViewLayoutMargin(R.id.cpu_row,RemoteViews.MARGIN_TOP,4,TypedValue.COMPLEX_UNIT_DIP);
            rv.setViewLayoutMargin(R.id.extra,RemoteViews.MARGIN_TOP,8,TypedValue.COMPLEX_UNIT_DIP);
        }
        if(v==Variant.LARGE || tall) {
            // LARGE (2-line live footer) and TALL sit within a few dp of their keys, so the group margins are dropped.
            rv.setViewLayoutMargin(R.id.subtitle,RemoteViews.MARGIN_TOP,0,TypedValue.COMPLEX_UNIT_DIP);
            rv.setViewLayoutMargin(R.id.request_row,RemoteViews.MARGIN_TOP,0,TypedValue.COMPLEX_UNIT_DIP);
            rv.setViewLayoutMargin(R.id.cpu_row,RemoteViews.MARGIN_TOP,0,TypedValue.COMPLEX_UNIT_DIP);
        }

        if(!s.configured() && !s.demo()) {
            rv.setTextViewText(R.id.total,"Connect account");
            if(medium) rv.setTextViewText(R.id.freshness,"Tap to set up your widget");
            else { rv.setTextViewText(R.id.metrics,"Tap to set up your widget"); rv.setTextViewText(R.id.freshness,""); }
            return;
        }
        if(s.raw().isEmpty()) {
            rv.setTextViewText(R.id.total,"Waiting for usage");
            String msg=s.error().isEmpty()?"Tap refresh to load your account":s.error();
            if(medium) rv.setTextViewText(R.id.freshness,msg);
            else { rv.setTextViewText(R.id.metrics,msg); rv.setTextViewText(R.id.freshness,""); }
            return;
        }
        try {
            Billing b=Billing.parse(s.raw());
            boolean demo=s.demo(), paid=s.paid();
            rv.setTextViewText(R.id.total,b.total());
            if(!medium) {
                rv.setTextViewText(R.id.title,demo?"USAGE WIDGET · DEMO":"USAGE WIDGET");
                String subtitle=b.cycles.size()==1
                    ? "Usage charges · since "+Billing.date(java.time.LocalDate.parse(b.cycles.first()))
                    : "Usage charges · "+b.period().toLowerCase(Locale.ROOT);
                rv.setTextViewText(R.id.subtitle,subtitle);
            }
            Billing.WorkersMeter req=b.workersMeter(false);
            Billing.WorkersMeter cpu=b.workersMeter(true);
            meterRow(rv,R.id.request_row,R.id.request_label,R.id.request_bar,R.id.request_value,"Requests",req,Billing.REQUESTS_INCLUDED,false,paid);
            meterRow(rv,R.id.cpu_row,R.id.cpu_label,R.id.cpu_bar,R.id.cpu_value,"CPU",cpu,Billing.CPU_MS_INCLUDED,true,paid);
            if(!medium) {
                if(v==Variant.LARGE && req==null) {
                    // The meter rows are hidden when unidentified; LARGE has no extra line, so the metrics view carries the note.
                    rv.setTextViewText(R.id.metrics,"Workers requests not identified · open app for row names");
                } else {
                    List<String> fam=b.familyLines(metricsMax);
                    if(fam.isEmpty()) rv.setTextViewText(R.id.metrics,"No metered usage returned");
                    else { rv.setViewVisibility(R.id.metrics,View.GONE); showFamilies(rv,fam); }
                }
            }
            Subscriptions subs=null;
            try { String r=s.subscriptionsRaw(); if(!r.isEmpty()) subs=Subscriptions.parse(r); } catch(Exception ignored) {}
            if(tall||xl) {
                // extra carries the pool line, the pace line, then the next-bill line. Three maxLines keeps all
                // three visible; the weighted spacer keeps the footer pinned to the bottom.
                rv.setInt(R.id.extra,"setMaxLines",3);
                rv.setViewVisibility(R.id.extra,View.VISIBLE);
                rv.setTextViewText(R.id.extra,extraText(b,req,cpu,paid,subs));
            }
            String footer=footer(b,s,demo);
            if(!medium && !demo) footer=footer+"\n"+Billing.checked(s.checked());
            if(medium && req==null && !demo) footer="Workers requests not identified · open app";
            rv.setTextViewText(R.id.freshness,footer);
        } catch(Exception e) {
            rv.setTextViewText(R.id.total,"Open to reconnect");
            rv.setViewVisibility(R.id.request_row,View.GONE);
            rv.setViewVisibility(R.id.cpu_row,View.GONE);
            if(medium) rv.setTextViewText(R.id.freshness,"Saved usage could not be read.");
            else { rv.setTextViewText(R.id.metrics,"Saved usage could not be read."); rv.setTextViewText(R.id.freshness,""); }
        }
    }

    /** NORMAL footer precedence (demo, refresh failed, status, else coverage). */
    private static String footer(Billing b,Store s,boolean demo) {
        if(demo) return "SAMPLE DATA · connect in app";
        if(!s.error().isEmpty()) return "Refresh failed · "+b.coverage();
        String status=s.prefs.getString("status","");
        if(!status.isEmpty()) return status+" · "+b.coverage();
        boolean stale=b.oldCoverage() || System.currentTimeMillis()-s.checked()>48*60*60*1000L;
        return (stale?"Older snapshot · ":"")+b.coverage();
    }

    /** TALL/XL extra: line 1 pool-left (paid) or counts (unpaid); line 2 pace (paid); line 3 next-bill estimate.
     *  A line is omitted when its data is missing. When requests are unidentified, line 1 says so. */
    private static String extraText(Billing b,Billing.WorkersMeter req,Billing.WorkersMeter cpu,boolean paid,Subscriptions subs) {
        boolean reqKnown=req!=null && req.consumed!=null;
        boolean cpuKnown=cpu!=null && cpu.consumed!=null;
        List<String> lines=new ArrayList<>();
        if(!reqKnown) {
            lines.add("Workers requests not identified · open app");
        } else if(paid) {
            BigDecimal remReq=Billing.remaining(req.consumed,Billing.REQUESTS_INCLUDED);
            if(cpuKnown) {
                BigDecimal remCpu=Billing.remaining(cpu.consumed,Billing.CPU_MS_INCLUDED);
                lines.add("Left: "+Billing.compact(remReq)+" requests · "+Billing.compact(remCpu)+" CPU ms");
            } else {
                lines.add("Left: "+Billing.compact(remReq)+" requests");
            }
        } else {
            String counts="Requests "+Billing.compact(req.consumed);
            if(cpuKnown) counts+=" · CPU "+Billing.compact(cpu.consumed)+" ms";
            lines.add(counts);
        }
        if(paid) {
            String pace=paceLine(b,req,cpu);
            String withBase=pace.isEmpty()?"$5 base separate":pace+" · $5 base separate";
            if(fitsOneLine(withBase)) lines.add(withBase);
            else if(!pace.isEmpty()) lines.add(pace);   // no room for the base note; the app already states it
        }
        String next=nextBillLine(b,subs);
        if(next!=null) lines.add(next);
        return String.join("\n",lines);
    }

    /** Pace note considering both meters (either can drive an overage). "" when no projection is available. */
    private static String paceLine(Billing b,Billing.WorkersMeter req,Billing.WorkersMeter cpu) {
        Billing.Projection pr=b.project(req);
        Billing.Projection pc=b.project(cpu);
        boolean prOk=pr.reason==Billing.Projection.Reason.OK, pcOk=pc.reason==Billing.Projection.Reason.OK;
        boolean prOver=prOk && pr.overage.signum()>0, pcOver=pcOk && pc.overage.signum()>0;
        if(prOver || pcOver) {
            BigDecimal sum=BigDecimal.ZERO;
            if(prOk) sum=sum.add(pr.overage);
            if(pcOk) sum=sum.add(pc.overage);
            return "On pace to exceed allowance · about "+Billing.money(sum,"USD");
        }
        if(prOk && pcOk) return "On pace: no overage expected";
        if(prOk) return "Requests on pace, CPU not projected";
        switch(pr.reason) {
            case NON_USD: return "On pace for "+Billing.compact(pr.projected);
            case EARLY: return "Too early to project";
            case PARTIAL: return "Projection withheld (partial data)";
            default: return "";
        }
    }

    /** Next-bill estimate line (usage estimate + subscriptions due within 31 days), or null when nothing sums. */
    private static String nextBillLine(Billing b,Subscriptions subs) {
        java.time.LocalDate today=java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        Billing.CostProjection cp=b.projectTotals();
        java.util.SortedMap<String,java.math.BigDecimal> due=(subs==null)?new java.util.TreeMap<>():subs.dueWithin(today,31);
        java.util.SortedMap<String,java.math.BigDecimal> base=(cp.reason==Billing.Projection.Reason.OK)?cp.projected:b.currentCycleTotals();
        java.util.SortedMap<String,java.math.BigDecimal> est=Billing.combine(base,due);
        if(est.isEmpty()) return null;
        java.time.LocalDate nd=(subs==null)?null:subs.nextDue();
        return "Next bill about "+Billing.moneyJoin(est)+(nd==null?"":" · "+Billing.date(nd));
    }

    /** Rough one-line character budget for the 11sp extra text at the 180dp width. Cosmetic only. */
    private static boolean fitsOneLine(String s) { return s.length()<=34; }

    private static void meterRow(RemoteViews rv,int row,int label,int bar,int value,String name,
                                 Billing.WorkersMeter m,BigDecimal included,boolean cpu,boolean paid) {
        if(m==null || m.consumed==null) { rv.setViewVisibility(row,View.GONE); return; }
        rv.setViewVisibility(row,View.VISIBLE);
        rv.setTextViewText(label,name);
        if(paid) {
            // Value text shows the count AND the percent; the bar still encodes the percent.
            rv.setViewVisibility(bar,View.VISIBLE);
            rv.setTextViewText(value,Billing.compact(m.consumed)+(cpu?" ms":"")+" · "+Billing.percentText(m.consumed,included));
            int p=progress(m.consumed,included);
            rv.setProgressBar(bar,1000,p,false);
            rv.setColorStateList(bar,"setProgressTintList",ColorStateList.valueOf(p>=800?BAR_HOT:BAR_WARM));
        } else {
            rv.setViewVisibility(bar,View.GONE);
            rv.setTextViewText(value,Billing.compact(m.consumed)+(cpu?" ms":""));
        }
        rv.setContentDescription(bar,name+": "+Billing.compact(m.consumed)+" of "+Billing.compact(included)+(cpu?" ms":" requests")+" included");
    }

    /** Family lines as a two-column ledger: name left, amount right-aligned, zero amounts dimmed. */
    private static void showFamilies(RemoteViews rv,List<String> lines) {
        rv.setViewVisibility(R.id.families,View.VISIBLE);
        for(int i=0;i<FAM_ROW.length;i++) {
            if(i>=lines.size()) { rv.setViewVisibility(FAM_ROW[i],View.GONE); continue; }
            String[] parts=splitFamily(lines.get(i));
            rv.setViewVisibility(FAM_ROW[i],View.VISIBLE);
            rv.setTextViewText(FAM_NAME[i],parts[0]);
            rv.setTextViewText(FAM_AMT[i],parts[1]);
            rv.setTextColor(FAM_AMT[i],parts[1].matches(".*[1-9].*")?AMOUNT:AMOUNT_ZERO);
        }
    }

    /** familyLines() joins a name and its amount with two spaces; the overflow line uses " · ". */
    static String[] splitFamily(String line) {
        int i=line.lastIndexOf("  ");
        if(i>=0) return new String[]{line.substring(0,i),line.substring(i+2)};
        i=line.indexOf(" · ");
        if(i>=0) return new String[]{line.substring(0,i),line.substring(i+3)};
        return new String[]{line,""};
    }

    private static int progress(BigDecimal consumed,BigDecimal included) {
        long p=consumed.multiply(new BigDecimal("1000")).divide(included,0,RoundingMode.DOWN).longValue();
        return (int)Math.max(0L,Math.min(1000L,p));
    }
    private static int px(Context c,float dp) { return Math.round(dp*c.getResources().getDisplayMetrics().density); }
}
