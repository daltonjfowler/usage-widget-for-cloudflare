package app.usagewidget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w411dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WidgetPreviewTest {

    private static View measured(Context c,UsageWidget.Variant v,int widthDp,int heightDp) {
        View widget=UsageWidget.variant(c,v).apply(c,new FrameLayout(c));
        float density=c.getResources().getDisplayMetrics().density;
        int width=Math.round(widthDp*density),height=Math.round(heightDp*density);
        widget.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
        widget.layout(0,0,width,height);
        return widget;
    }

    @Test public void everyVariantKeepsFooterInsideCardAtItsKey() {
        Context c=RuntimeEnvironment.getApplication();
        new Store(c).prefs.edit().clear().putBoolean("demo",true).commit();
        float density=c.getResources().getDisplayMetrics().density;
        UsageWidget.Variant[] vs={UsageWidget.Variant.MEDIUM,UsageWidget.Variant.LARGE,UsageWidget.Variant.TALL,UsageWidget.Variant.XL};
        int[][] keys={{180,100},{180,200},{180,256},{180,330}};
        for(int i=0;i<vs.length;i++) {
            View widget=measured(c,vs[i],keys[i][0],keys[i][1]);
            int height=Math.round(keys[i][1]*density);
            View footer=widget.findViewById(R.id.freshness);
            int available=height-widget.getPaddingBottom();
            assertTrue(vs[i]+" footer bottom="+footer.getBottom()+" available="+available,
                footer.getBottom()<=available);
        }
    }

    /** Live-like store (2-line footer + subscriptions estimate) must still keep the footer inside the card. */
    @Test public void liveLikeLargeAndTallKeepFooterInside() {
        Context c=RuntimeEnvironment.getApplication();
        new Store(c).prefs.edit().clear().putString("account","a".repeat(32)).putString("token","placeholder")
            .putString("snapshot",Billing.demo()).putString("subscriptions",Subscriptions.demo())
            .putLong("checked",System.currentTimeMillis()).commit();
        float density=c.getResources().getDisplayMetrics().density;
        UsageWidget.Variant[] vs={UsageWidget.Variant.LARGE,UsageWidget.Variant.TALL,UsageWidget.Variant.XL};
        int[][] keys={{180,200},{180,256},{180,330}};
        for(int i=0;i<vs.length;i++) {
            View widget=measured(c,vs[i],keys[i][0],keys[i][1]);
            int height=Math.round(keys[i][1]*density);
            TextView footer=widget.findViewById(R.id.freshness);
            int available=height-widget.getPaddingBottom();
            assertTrue(vs[i]+" footer bottom="+footer.getBottom()+" available="+available,footer.getBottom()<=available);
            android.text.Layout lay=footer.getLayout();
            assertNotNull(vs[i]+" footer layout",lay);
            int last=lay.getLineCount()-1;
            assertEquals(vs[i]+" footer last-line ellipsis",0,lay.getEllipsisCount(last));
        }
    }

    /** At 180dp width every visible meter bar keeps a usable width (>=30dp) alongside the count+percent text. */
    @Test public void meterBarsAtLeastThirtyDpAt180() {
        Context c=RuntimeEnvironment.getApplication();
        new Store(c).prefs.edit().clear().putBoolean("demo",true).commit();
        float density=c.getResources().getDisplayMetrics().density;
        int min=Math.round(30*density);
        UsageWidget.Variant[] vs={UsageWidget.Variant.MEDIUM,UsageWidget.Variant.LARGE,UsageWidget.Variant.TALL};
        int[] h={100,200,256};
        for(int i=0;i<vs.length;i++) {
            View widget=measured(c,vs[i],180,h[i]);
            for(int id:new int[]{R.id.request_bar,R.id.cpu_bar}) {
                View bar=widget.findViewById(id);
                if(bar.getVisibility()==View.VISIBLE)
                    assertTrue(vs[i]+" bar width="+bar.getWidth()+" min="+min,bar.getWidth()>=min);
            }
        }
    }

    @Test public void mediumMeterShowsLabelCountAndPercent() {
        Context c=RuntimeEnvironment.getApplication();
        new Store(c).prefs.edit().clear().putBoolean("demo",true).commit();
        View widget=UsageWidget.variant(c,UsageWidget.Variant.MEDIUM).apply(c,new FrameLayout(c));
        assertEquals("Requests",((TextView)widget.findViewById(R.id.request_label)).getText().toString());
        assertEquals("2.4M · 24%",((TextView)widget.findViewById(R.id.request_value)).getText().toString());
    }

    /** A live-like snapshot with no identifiable Workers rows: TALL extra, MEDIUM footer and LARGE metrics all say so. */
    @Test public void noWorkersRowsShowNotIdentified() {
        Context c=RuntimeEnvironment.getApplication();
        String snap="{\"success\":true,\"result\":["
            +"{\"ServiceName\":\"Container Memory\",\"ServiceFamilyName\":\"Containers\",\"BillingCurrency\":\"USD\",\"ContractedCost\":\"0.42\",\"ConsumedUnit\":\"GB-seconds\",\"ConsumedQuantity\":\"410000\",\"BillingPeriodStart\":\"2026-08-14T00:00:00Z\",\"ChargePeriodEnd\":\"2026-09-13T00:00:00Z\"},"
            +"{\"ServiceName\":\"D1 Rows Read\",\"ServiceFamilyName\":\"D1\",\"BillingCurrency\":\"USD\",\"ContractedCost\":\"0\",\"ConsumedUnit\":\"Rows\",\"ConsumedQuantity\":\"150000\",\"BillingPeriodStart\":\"2026-08-14T00:00:00Z\",\"ChargePeriodEnd\":\"2026-09-13T00:00:00Z\"}"
            +"]}";
        new Store(c).prefs.edit().clear().putString("account","a".repeat(32)).putString("token","placeholder")
            .putString("snapshot",snap).putBoolean("paid",true).putLong("checked",System.currentTimeMillis()).commit();
        TextView tallExtra=(TextView)UsageWidget.variant(c,UsageWidget.Variant.TALL).apply(c,new FrameLayout(c)).findViewById(R.id.extra);
        assertTrue("tall extra="+tallExtra.getText(),tallExtra.getText().toString().contains("not identified"));
        TextView medFooter=(TextView)UsageWidget.variant(c,UsageWidget.Variant.MEDIUM).apply(c,new FrameLayout(c)).findViewById(R.id.freshness);
        assertTrue("medium footer="+medFooter.getText(),medFooter.getText().toString().contains("not identified"));
        TextView largeMetrics=(TextView)UsageWidget.variant(c,UsageWidget.Variant.LARGE).apply(c,new FrameLayout(c)).findViewById(R.id.metrics);
        assertTrue("large metrics="+largeMetrics.getText(),largeMetrics.getText().toString().contains("not identified"));
    }

    @Test public void stripFitsFortyDp() {
        Context c=RuntimeEnvironment.getApplication();
        new Store(c).prefs.edit().clear().putBoolean("demo",true).commit();
        float density=c.getResources().getDisplayMetrics().density;
        int width=Math.round(110*density),height=Math.round(40*density);
        View widget=measured(c,UsageWidget.Variant.STRIP,110,40);
        TextView total=widget.findViewById(R.id.total),refresh=widget.findViewById(R.id.refresh),req=widget.findViewById(R.id.req);
        assertTrue("total right="+total.getRight()+" width="+width,total.getRight()<=width);
        assertTrue("refresh right="+refresh.getRight()+" width="+width,refresh.getRight()<=width);
        assertTrue("total bottom="+total.getBottom()+" height="+height,total.getBottom()<=height);
        assertTrue("refresh bottom="+refresh.getBottom()+" height="+height,refresh.getBottom()<=height);
        assertEquals("req ellipsis",0,req.getLayout().getEllipsisCount(0));
        android.view.ViewGroup card=(android.view.ViewGroup)widget;
        for(int i=0;i<card.getChildCount();i++) {
            View child=card.getChildAt(i);
            assertTrue("child "+i+" right="+child.getRight()+" width="+width,child.getRight()<=width);
        }
    }

    /** WIDE (>=200dp) shows both meters as words. Demo and live-like stores must not ellipsize the req line and
     *  must keep every child inside the width. The error-marked live store may ellipsize, but the marker leads. */
    @Test public void wideFitsAndMarksState() {
        Context c=RuntimeEnvironment.getApplication();
        float density=c.getResources().getDisplayMetrics().density;
        int[][] sizes={{200,40},{250,51}};
        for(int mode=0;mode<2;mode++) {
            if(mode==0) new Store(c).prefs.edit().clear().putBoolean("demo",true).commit();
            else new Store(c).prefs.edit().clear().putString("account","a".repeat(32)).putString("token","placeholder")
                .putString("snapshot",Billing.demo()).putLong("checked",System.currentTimeMillis()).commit();
            for(int[] sz:sizes) {
                int width=Math.round(sz[0]*density);
                View widget=measured(c,UsageWidget.Variant.WIDE,sz[0],sz[1]);
                TextView req=widget.findViewById(R.id.req);
                assertEquals("req ellipsis mode="+mode+" "+sz[0]+"x"+sz[1],0,req.getLayout().getEllipsisCount(0));
                android.view.ViewGroup card=(android.view.ViewGroup)widget;
                for(int i=0;i<card.getChildCount();i++) {
                    View child=card.getChildAt(i);
                    assertTrue("child "+i+" right="+child.getRight()+" width="+width+" mode="+mode+" "+sz[0]+"x"+sz[1],
                        child.getRight()<=width);
                }
            }
        }
        // Error-marked live store: the req may ellipsize at 250x51, but the marker must stay at the start.
        new Store(c).prefs.edit().clear().putString("account","a".repeat(32)).putString("token","placeholder")
            .putString("snapshot",Billing.demo()).putString("error","Offline").putLong("checked",System.currentTimeMillis()).commit();
        View widget=measured(c,UsageWidget.Variant.WIDE,250,51);
        TextView req=widget.findViewById(R.id.req);
        assertTrue("req="+req.getText(),req.getText().toString().startsWith("Refresh failed"));
    }

    @Test public void exportsMediumDemoPng() throws Exception {
        Context c=RuntimeEnvironment.getApplication();
        new Store(c).prefs.edit().clear().putBoolean("demo",true).commit();
        float density=c.getResources().getDisplayMetrics().density;
        int width=Math.round(250*density),height=Math.round(102*density);
        View widget=measured(c,UsageWidget.Variant.MEDIUM,250,102);
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        widget.draw(new Canvas(bitmap));
        File file=new File("build/previews/widget-demo.png"); file.getParentFile().mkdirs();
        try(FileOutputStream out=new FileOutputStream(file)) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out)); }
    }

    /** Renders the launcher icon drawable at 192x192 px so the lead can inspect it. */
    @Test public void exportsIconPng() throws Exception {
        Context c=RuntimeEnvironment.getApplication();
        android.graphics.drawable.Drawable d=c.getResources().getDrawable(R.drawable.ic_usage,c.getTheme());
        assertNotNull(d);
        Bitmap bitmap=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bitmap);
        d.setBounds(0,0,192,192);
        d.draw(canvas);
        File file=new File("build/previews/icon.png"); file.getParentFile().mkdirs();
        try(FileOutputStream out=new FileOutputStream(file)) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out)); }
    }

    /** Renders every variant at its key size and at typical Pixel cell sizes, for demo and for a live-like snapshot. */
    @Test public void exportsEveryVariantPng() throws Exception {
        Context c=RuntimeEnvironment.getApplication();
        Object[][] shots={{UsageWidget.Variant.STRIP,110,40},{UsageWidget.Variant.STRIP,250,51},
            {UsageWidget.Variant.WIDE,200,40},{UsageWidget.Variant.WIDE,250,51},
            {UsageWidget.Variant.MEDIUM,180,100},{UsageWidget.Variant.MEDIUM,250,102},
            {UsageWidget.Variant.LARGE,180,200},{UsageWidget.Variant.LARGE,276,220},
            {UsageWidget.Variant.TALL,180,256},{UsageWidget.Variant.TALL,276,338},
            {UsageWidget.Variant.XL,180,330},{UsageWidget.Variant.XL,276,338}};
        for(int mode=0;mode<2;mode++) {
            if(mode==0) new Store(c).prefs.edit().clear().putBoolean("demo",true).commit();
            else new Store(c).prefs.edit().clear().putString("account","a".repeat(32)).putString("token","test-placeholder")
                .putString("snapshot",Billing.demo()).putString("subscriptions",Subscriptions.demo())
                .putLong("checked",System.currentTimeMillis()).commit();
            for(Object[] shot:shots) export(c,(UsageWidget.Variant)shot[0],(Integer)shot[1],(Integer)shot[2],mode==0?"demo":"live");
        }
    }

    private static void export(Context c,UsageWidget.Variant v,int widthDp,int heightDp,String tag) throws Exception {
        float density=c.getResources().getDisplayMetrics().density;
        View widget=measured(c,v,widthDp,heightDp);
        Bitmap bitmap=Bitmap.createBitmap(Math.round(widthDp*density),Math.round(heightDp*density),Bitmap.Config.ARGB_8888);
        widget.draw(new Canvas(bitmap));
        File file=new File("build/previews/"+tag+"-"+v.name().toLowerCase(java.util.Locale.ROOT)+"-"+widthDp+"x"+heightDp+".png");
        file.getParentFile().mkdirs();
        try(FileOutputStream out=new FileOutputStream(file)) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out)); }
    }
}
