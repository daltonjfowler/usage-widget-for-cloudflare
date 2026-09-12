package app.usagewidget;

import android.app.job.JobScheduler;
import android.content.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={31,35})
public class AndroidBehaviorTest {
    private Context c;
    @Before public void before() { c=RuntimeEnvironment.getApplication(); new Store(c).prefs.edit().clear().commit(); }

    @Test public void unconfiguredWidgetInflatesWithSetupPrompt() {
        android.view.View view=UsageWidget.views(c).apply(c,new FrameLayout(c));
        assertEquals("Connect account",((TextView)view.findViewById(R.id.total)).getText().toString());
    }
    @Test public void demoWidgetIsClearlyLabeledAndRendersTotals() {
        new Store(c).prefs.edit().putBoolean("demo",true).commit();
        android.view.View view=UsageWidget.variant(c,UsageWidget.Variant.TALL).apply(c,new FrameLayout(c));
        assertEquals("$0.42",((TextView)view.findViewById(R.id.total)).getText().toString());
        assertTrue(((TextView)view.findViewById(R.id.title)).getText().toString().contains("DEMO"));
        assertTrue(((TextView)view.findViewById(R.id.freshness)).getText().toString().contains("SAMPLE"));
    }
    @Test public void failedRefreshKeepsCachedChargeVisible() {
        new Store(c).prefs.edit().putString("account","a".repeat(32)).putString("token","placeholder")
            .putString("snapshot",Billing.demo()).putString("error","Offline").commit();
        android.view.View view=UsageWidget.variant(c,UsageWidget.Variant.LARGE).apply(c,new FrameLayout(c));
        assertEquals("$0.42",((TextView)view.findViewById(R.id.total)).getText().toString());
        assertTrue(((TextView)view.findViewById(R.id.freshness)).getText().toString().contains("Refresh failed"));
    }
    @Test public void sizedViewsAppliesWithoutThrowing() {
        assertNotNull(UsageWidget.views(c).apply(c,new FrameLayout(c)));
    }
    @Test public void stripHasRequiredIds() {
        android.view.View view=UsageWidget.variant(c,UsageWidget.Variant.STRIP).apply(c,new FrameLayout(c));
        assertNotNull(view.findViewById(R.id.total));
        assertNotNull(view.findViewById(R.id.refresh));
    }
    @Test public void refreshJobRequiresNetworkAndCanBeCancelled() {
        RefreshJob.now(c); JobScheduler scheduler=c.getSystemService(JobScheduler.class);
        assertEquals(1,scheduler.getAllPendingJobs().size());
        assertNotNull(scheduler.getPendingJob(302).getRequiredNetwork());
        RefreshJob.cancel(c); assertEquals(0,scheduler.getAllPendingJobs().size());
    }
    @Test public void setupActivityLaunches() { try(var a=Robolectric.buildActivity(MainActivity.class).setup()) { assertNotNull(a.get()); } }
    @Test public void demoActivityLaunchesWithBreakdown() {
        new Store(c).prefs.edit().putBoolean("demo",true).commit();
        try(var a=Robolectric.buildActivity(MainActivity.class).setup()) { assertNotNull(a.get()); }
    }

    @Test public void upcomingCardShowsSavedSubscriptionsWithError() {
        new Store(c).prefs.edit().putString("account","a".repeat(32)).putString("token","placeholder")
            .putString("snapshot",Billing.demo()).putString("subscriptions",Subscriptions.demo())
            .putString("subs_error","Offline").commit();
        try(var a=Robolectric.buildActivity(MainActivity.class).setup()) {
            java.util.List<String> text=allText(a.get().findViewById(android.R.id.content));
            assertTrue("estimate line",contains(text,"Estimated next bill"));
            assertTrue("workers paid",contains(text,"Workers Paid"));
            assertTrue("last saved list",contains(text,"Last saved list shown"));
        }
    }

    @Test public void upcomingCardEstimateShownInDemo() {
        new Store(c).prefs.edit().putBoolean("demo",true).commit();
        try(var a=Robolectric.buildActivity(MainActivity.class).setup()) {
            assertTrue(contains(allText(a.get().findViewById(android.R.id.content)),"Estimated next bill"));
        }
    }

    @Test public void demoTallWidgetShowsNextBill() {
        new Store(c).prefs.edit().putBoolean("demo",true).commit();
        android.view.View view=UsageWidget.variant(c,UsageWidget.Variant.TALL).apply(c,new FrameLayout(c));
        assertTrue(((TextView)view.findViewById(R.id.extra)).getText().toString().contains("Next bill"));
    }

    @Test public void demoStripAndWideCarryDemoMarker() {
        new Store(c).prefs.edit().putBoolean("demo",true).commit();
        android.view.View strip=UsageWidget.variant(c,UsageWidget.Variant.STRIP).apply(c,new FrameLayout(c));
        assertTrue(((TextView)strip.findViewById(R.id.req)).getText().toString().contains("DEMO"));
        android.view.View wide=UsageWidget.variant(c,UsageWidget.Variant.WIDE).apply(c,new FrameLayout(c));
        assertTrue(((TextView)wide.findViewById(R.id.req)).getText().toString().contains("DEMO"));
    }

    @Test public void errorMarkerShownOnStripAndWide() {
        new Store(c).prefs.edit().putString("account","a".repeat(32)).putString("token","placeholder")
            .putString("snapshot",Billing.demo()).putString("error","Offline").commit();
        android.view.View strip=UsageWidget.variant(c,UsageWidget.Variant.STRIP).apply(c,new FrameLayout(c));
        assertTrue(((TextView)strip.findViewById(R.id.req)).getText().toString().startsWith("! "));
        android.view.View wide=UsageWidget.variant(c,UsageWidget.Variant.WIDE).apply(c,new FrameLayout(c));
        assertTrue(((TextView)wide.findViewById(R.id.req)).getText().toString().startsWith("Refresh failed"));
    }

    @Test public void tallExtraConsidersCpuOverage() throws Exception {
        // Requests project under 10M (no overage) while CPU projects over 30M ms (overage): the pace must flag it.
        String base="\"ServiceName\":\"Workers Standard\",\"ServiceFamilyName\":\"Workers\",\"BillingCurrency\":\"USD\","
            +"\"ContractedCost\":\"0\",\"BillingPeriodStart\":\"2026-08-14T00:00:00Z\",\"ChargePeriodEnd\":\"2026-09-13T00:00:00Z\"";
        String snapshot="{\"success\":true,\"result\":["
            +"{"+base+",\"ChargeDescription\":\"Requests\",\"ConsumedUnit\":\"requests\",\"ConsumedQuantity\":\"100000\"},"
            +"{"+base+",\"ChargeDescription\":\"CPU time\",\"ConsumedUnit\":\"milliseconds\",\"ConsumedQuantity\":\"30000000\"}"
            +"]}";
        new Store(c).prefs.edit().putString("account","a".repeat(32)).putString("token","placeholder")
            .putString("snapshot",snapshot).putBoolean("paid",true).commit();
        android.view.View view=UsageWidget.variant(c,UsageWidget.Variant.TALL).apply(c,new FrameLayout(c));
        String extra=((TextView)view.findViewById(R.id.extra)).getText().toString();
        assertTrue("extra="+extra,extra.contains("exceed"));
        assertFalse("extra="+extra,extra.contains("no overage expected"));
    }

    /** Recurses the view tree collecting the text of every TextView (MainActivity builds id-less TextViews). */
    private static java.util.List<String> allText(android.view.View v) {
        java.util.List<String> out=new java.util.ArrayList<>();
        if(v instanceof TextView) out.add(((TextView)v).getText().toString());
        if(v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g=(android.view.ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++) out.addAll(allText(g.getChildAt(i)));
        }
        return out;
    }
    private static boolean contains(java.util.List<String> texts,String sub) {
        for(String t:texts) if(t.contains(sub)) return true;
        return false;
    }
}
