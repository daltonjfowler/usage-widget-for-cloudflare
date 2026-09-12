package app.usagewidget;
import org.junit.Test;
import org.json.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import static org.junit.Assert.*;

public class BillingTest {
    private JSONObject row(String service,String currency,String cost,String consumed,String unit) throws JSONException {
        return new JSONObject().put("ServiceName",service).put("ServiceFamilyName","Workers")
            .put("BillingCurrency",currency).put("ContractedCost",cost).put("ConsumedQuantity",consumed).put("ConsumedUnit",unit)
            .put("BillingPeriodStart","2026-08-18T00:00:00Z").put("ChargePeriodEnd","2026-09-12T00:00:00Z");
    }
    private JSONObject row(String service,String currency,String cost,String consumed,String unit,String description) throws JSONException {
        return row(service,currency,cost,consumed,unit).put("ChargeDescription",description);
    }
    private Billing parse(JSONObject... rows) throws Exception { return Billing.parse(new JSONObject().put("success",true).put("result",new JSONArray(rows)).toString()); }
    @Test public void sumsPeriodCostsNeverRunningTotals() throws Exception {
        Billing b=parse(row("Workers Standard CPU","USD","0.1","100","ms").put("CumulatedContractedCost",5),
            row("Workers Standard CPU","USD","0.2","200","ms").put("CumulatedContractedCost",5.2));
        assertEquals(new BigDecimal("0.3"),b.totals.get("USD")); assertEquals(new BigDecimal("300"),b.workersUsage(true));
    }
    @Test public void midnightCoverageIsPreviousDay() { assertEquals(LocalDate.of(2026,9,11),Billing.coverageDate("2026-09-12T00:00:00Z")); }
    @Test public void missingCoverageRemainsUnknown() { assertNull(Billing.coverageDate("nonsense")); }
    @Test public void emptyResponseDoesNotClaimZeroBill() throws Exception { assertEquals("No usage reported",parse().total()); }
    @Test public void currenciesNeverAddedTogether() throws Exception {
        Billing b=parse(row("D1","USD","2","10","Rows"),row("D1","EUR","3","10","Rows"));
        assertEquals(2,b.totals.size()); assertTrue(b.total().contains("$2.00")); assertTrue(b.total().contains("€3.00"));
    }
    @Test public void countsDoNotBecomeRequestAllowances() throws Exception {
        Billing b=parse(row("Workers Standard","USD","0","123","Count")); assertNull(b.workersUsage(false)); assertNull(b.workersUsage(true));
    }
    @Test public void cpuSecondsConvertedButDurableDurationExcluded() throws Exception {
        Billing b=parse(row("Workers Standard CPU","USD","0","2","CPU-seconds"),row("Durable Objects CPU","USD","5","90","ms"));
        assertEquals(new BigDecimal("2000"),b.workersUsage(true));
    }
    @Test public void missingConsumedNeverFallsBackToBillableQuantity() throws Exception {
        JSONObject r=row("Workers Standard Requests","USD","0","12","Requests"); r.remove("ConsumedQuantity"); r.put("PricingQuantity",500);
        Billing b=parse(r); assertNull(b.workersUsage(false)); assertFalse(b.metrics.get(0).hasConsumed);
    }
    @Test public void mixedUnitsRemainSeparate() throws Exception {
        Billing b=parse(row("Workers Standard CPU","USD","0","1000","ms"),row("Workers Standard CPU","USD","0","2","seconds"));
        assertEquals(2,b.metrics.size()); assertEquals(new BigDecimal("3000"),b.workersUsage(true));
    }
    @Test public void cycleUsesApiAnchorNotCalendarMonth() throws Exception {
        Billing b=parse(row("D1","USD","0","12","Rows")); assertEquals("Cycle since 2026-08-18",b.period());
    }
    @Test public void newCycleDoesNotAccumulatePreviousSnapshot() throws Exception {
        Billing old=parse(row("D1","USD","12","100","Rows"));
        Billing current=parse(row("D1","USD","0","1","Rows").put("BillingPeriodStart","2026-09-18T00:00:00Z"));
        assertEquals("$12.00",old.total()); assertEquals("$0.00",current.total()); assertEquals("Cycle since 2026-09-18",current.period());
    }
    @Test(expected=IllegalArgumentException.class) public void missingCostsFailClosed() throws Exception {
        JSONObject r=row("D1","USD","0","12","Rows"); r.remove("ContractedCost"); parse(r);
    }
    @Test(expected=IllegalArgumentException.class) public void failedApiIsNotZero() throws Exception { Billing.parse("{\"success\":false,\"result\":[]}"); }
    @Test public void unknownServiceStillIncluded() throws Exception {
        Billing b=parse(row("Future Product","USD","0.75","15","Units")); assertEquals("$0.75",b.total());
    }
    @Test public void demoHasExpectedMetrics() throws Exception {
        Billing b=Billing.parse(Billing.demo()); assertEquals("$0.42",b.total()); assertEquals(new BigDecimal("2400000"),b.workersUsage(false));
    }

    // --- New tests (spec 4.1) ---

    @Test public void identifiesRequestsByDescriptionUnderGenericServiceName() throws Exception {
        Billing b=parse(row("Workers Standard","USD","0","500","Requests","Requests"));
        assertEquals(new BigDecimal("500"),b.workersUsage(false));
        assertTrue(b.workersMeter(false).sources.contains("Workers Standard · Requests"));
    }
    @Test public void identifiesCpuByUnitWithoutCpuWord() throws Exception {
        Billing b=parse(row("Workers Standard","USD","0","10","milliseconds","Compute"));
        assertEquals(new BigDecimal("10"),b.workersUsage(true));
    }
    @Test public void excludesWorkersKvAiPagesRows() throws Exception {
        Billing b=parse(row("Workers KV","USD","0","1","requests"),
            row("Workers AI","USD","0","1","requests"),
            row("Pages Functions","USD","0","1","requests"));
        assertNull(b.workersMeter(false));
    }
    @Test public void excludesStaticAssetRequests() throws Exception {
        Billing b=parse(row("Workers Standard","USD","0","999","requests","Static asset requests"));
        assertNull(b.workersMeter(false));
        boolean seen=false; for(String s:b.workersCandidates()) if(s.contains("Static asset requests")) seen=true;
        assertTrue(seen);
    }
    @Test public void olderCycleRowsExcludedFromMeter() throws Exception {
        Billing b=parse(row("Workers Standard","USD","0","100","requests"),
            row("Workers Standard","USD","0","5","requests").put("BillingPeriodStart","2026-09-18T00:00:00Z").put("ChargePeriodEnd","2026-09-19T00:00:00Z"));
        assertEquals(new BigDecimal("5"),b.workersUsage(false));
        assertEquals(LocalDate.of(2026,9,18),b.workersMeter(false).cycleStart);
    }
    @Test public void missingAmountYieldsUnknownNotZero() throws Exception {
        JSONObject r=row("Workers Standard","USD","0","1","Requests","Requests"); r.remove("ConsumedQuantity");
        Billing b=parse(r);
        Billing.WorkersMeter m=b.workersMeter(false);
        assertNotNull(m); assertNull(m.consumed); assertTrue(m.partial); assertNull(b.workersUsage(false));
    }
    @Test public void projectionMathIsExact() throws Exception {
        Billing b=parse(row("Workers Standard","USD","0","171630","requests")
            .put("BillingPeriodStart","2026-08-14T00:00:00Z").put("ChargePeriodEnd","2026-09-13T00:00:00Z"));
        Billing.Projection p=b.project(b.workersMeter(false));
        assertEquals(new BigDecimal("177351"),p.projected);
        assertEquals(30,p.elapsedDays); assertEquals(31,p.cycleDays);
        assertEquals(Billing.Projection.Reason.OK,p.reason);
        assertEquals(new BigDecimal("0.00"),p.overage);
    }
    @Test public void overageEstimateAboveAllowance() {
        assertEquals(new BigDecimal("0.60"),Billing.overageEstimate(new BigDecimal("12000000"),Billing.REQUESTS_INCLUDED,Billing.REQUESTS_RATE));
    }
    @Test public void overageEstimateBelowAllowanceIsZero() {
        assertEquals(new BigDecimal("0.00"),Billing.overageEstimate(new BigDecimal("177351"),Billing.REQUESTS_INCLUDED,Billing.REQUESTS_RATE));
    }
    @Test public void percentTextRounding() {
        BigDecimal tenM=new BigDecimal("10000000");
        assertEquals("2%",Billing.percentText(new BigDecimal("171630"),tenM));
        assertEquals("0%",Billing.percentText(new BigDecimal("0"),tenM));
        assertEquals("<1%",Billing.percentText(new BigDecimal("50000"),tenM));
        assertEquals("120%",Billing.percentText(new BigDecimal("12000000"),tenM));
    }
    @Test public void projectionEarlyWithheld() throws Exception {
        Billing b=parse(row("Workers Standard","USD","0","100","requests")
            .put("BillingPeriodStart","2026-08-14T00:00:00Z").put("ChargePeriodEnd","2026-08-15T00:00:00Z"));
        Billing.Projection p=b.project(b.workersMeter(false));
        assertEquals(Billing.Projection.Reason.EARLY,p.reason); assertNull(p.projected);
    }
    @Test public void projectionPartialWithheld() throws Exception {
        JSONObject r2=row("Workers Standard","USD","0","1","requests","Extra requests"); r2.remove("ConsumedQuantity");
        Billing b=parse(row("Workers Standard","USD","0","100","requests","Base requests"),r2);
        Billing.WorkersMeter m=b.workersMeter(false);
        assertNotNull(m.consumed); assertTrue(m.partial);
        assertEquals(Billing.Projection.Reason.PARTIAL,b.project(m).reason);
        assertNull(b.project(m).projected);
    }
    @Test public void projectionNonUsdSuppressesOverage() throws Exception {
        Billing b=parse(row("Workers Standard","EUR","0","171630","requests")
            .put("BillingPeriodStart","2026-08-14T00:00:00Z").put("ChargePeriodEnd","2026-09-13T00:00:00Z"));
        Billing.Projection p=b.project(b.workersMeter(false));
        assertNotNull(p.projected); assertNull(p.overage); assertEquals(Billing.Projection.Reason.NON_USD,p.reason);
    }
    @Test public void projectionUnknownWhenCoverageBeforeCycle() throws Exception {
        Billing b=parse(row("Workers Standard","USD","0","100","requests")
            .put("BillingPeriodStart","2026-09-18T00:00:00Z").put("ChargePeriodEnd","2026-09-12T00:00:00Z"));
        Billing.Projection p=b.project(b.workersMeter(false));
        assertEquals(Billing.Projection.Reason.UNKNOWN_DATES,p.reason); assertNull(p.projected);
    }
    @Test public void familyLinesNoPlusTokenAndCurrencySeparated() throws Exception {
        JSONObject a=row("A","USD","10","1","Rows").put("ServiceFamilyName","A");
        JSONObject bb=row("B","USD","3","1","Rows").put("ServiceFamilyName","B");
        JSONObject c=row("C","USD","1","1","Rows").put("ServiceFamilyName","C");
        JSONObject d=row("D","EUR","2","1","Rows").put("ServiceFamilyName","D");
        Billing bill=parse(a,bb,c,d);
        List<String> lines=bill.familyLines(2);
        String last=lines.get(lines.size()-1);
        assertTrue(last.contains(" more families · "));
        assertTrue(last.contains("$")); assertTrue(last.contains("€"));
        for(String s:lines) assertFalse(s.matches(".*\\+[0-9].*"));
    }
    @Test public void familyLinesSingularGrammar() throws Exception {
        JSONObject a=row("A","USD","10","1","Rows").put("ServiceFamilyName","A");
        JSONObject bb=row("B","USD","3","1","Rows").put("ServiceFamilyName","B");
        JSONObject c=row("C","USD","1","1","Rows").put("ServiceFamilyName","C");
        Billing three=parse(a,bb,c);
        List<String> l2=three.familyLines(2);
        assertTrue(l2.get(l2.size()-1).startsWith("2 more families"));
        Billing two=parse(a,bb);
        List<String> l1=two.familyLines(2);
        assertTrue(l1.get(l1.size()-1).startsWith("1 more family"));
    }
    @Test public void candidatesListsUnidentifiedWorkersRows() throws Exception {
        Billing b=parse(row("Workers KV","USD","0","1","requests"));
        boolean seen=false; for(String s:b.workersCandidates()) if(s.contains("Workers KV") && s.contains("requests")) seen=true;
        assertTrue(seen);
    }
    @Test public void demoIdentifiesViaDescription() throws Exception {
        Billing b=Billing.parse(Billing.demo());
        assertEquals(new BigDecimal("2400000"),b.workersUsage(false));
        assertEquals(new BigDecimal("2400000"),b.workersUsage(true));
        assertTrue(b.familyLines(2).get(0).startsWith("Durable Objects"));
    }

    // --- New tests (spec2 7.2) ---

    @Test public void projectTotalsOkMath() throws Exception {
        Billing b=parse(row("Workers Standard","USD","0.42","1","requests")
            .put("BillingPeriodStart","2026-08-14T00:00:00Z").put("ChargePeriodEnd","2026-09-13T00:00:00Z"));
        Billing.CostProjection cp=b.projectTotals();
        assertEquals(Billing.Projection.Reason.OK,cp.reason);
        assertEquals(30,cp.elapsedDays); assertEquals(31,cp.cycleDays);
        assertEquals(0,cp.projected.get("USD").compareTo(new BigDecimal("0.43")));
    }
    @Test public void projectTotalsEarlyWithheld() throws Exception {
        Billing b=parse(row("Workers Standard","USD","0.42","1","requests")
            .put("BillingPeriodStart","2026-08-14T00:00:00Z").put("ChargePeriodEnd","2026-08-15T00:00:00Z"));
        Billing.CostProjection cp=b.projectTotals();
        assertEquals(Billing.Projection.Reason.EARLY,cp.reason);
        assertTrue(cp.projected.isEmpty());
    }
    @Test public void projectTotalsUnknownWhenCoverageBeforeCycle() throws Exception {
        Billing b=parse(row("Workers Standard","USD","0.42","1","requests")
            .put("BillingPeriodStart","2026-09-18T00:00:00Z").put("ChargePeriodEnd","2026-09-12T00:00:00Z"));
        Billing.CostProjection cp=b.projectTotals();
        assertEquals(Billing.Projection.Reason.UNKNOWN_DATES,cp.reason);
        assertTrue(cp.projected.isEmpty());
    }
    @Test public void projectTotalsTwoCurrenciesSeparately() throws Exception {
        Billing b=parse(
            row("Workers Standard","USD","0.42","1","requests").put("BillingPeriodStart","2026-08-14T00:00:00Z").put("ChargePeriodEnd","2026-09-13T00:00:00Z"),
            row("D1","EUR","1.00","1","Rows").put("ServiceFamilyName","D1").put("BillingPeriodStart","2026-08-14T00:00:00Z").put("ChargePeriodEnd","2026-09-13T00:00:00Z"));
        Billing.CostProjection cp=b.projectTotals();
        assertEquals(2,cp.projected.size());
        assertEquals(0,cp.projected.get("USD").compareTo(new BigDecimal("0.43")));
        assertEquals(0,cp.projected.get("EUR").compareTo(new BigDecimal("1.03")));
    }
    @Test public void projectTotalsScopesToLatestCycleNotGlobalTotals() throws Exception {
        Billing b=parse(
            row("Workers Standard","USD","10.00","1","requests").put("BillingPeriodStart","2026-07-14T00:00:00Z").put("ChargePeriodEnd","2026-08-13T00:00:00Z"),
            row("Workers Standard","USD","0.42","1","requests").put("BillingPeriodStart","2026-08-14T00:00:00Z").put("ChargePeriodEnd","2026-09-13T00:00:00Z"));
        assertEquals(0,b.currentCycleTotals().get("USD").compareTo(new BigDecimal("0.42")));
        assertEquals(0,b.projectTotals().projected.get("USD").compareTo(new BigDecimal("0.43")));
    }
    @Test public void usageThisCycleScopesToLatest() throws Exception {
        Billing b=parse(
            row("Workers Standard","USD","10.00","1","requests").put("BillingPeriodStart","2026-07-14T00:00:00Z").put("ChargePeriodEnd","2026-08-13T00:00:00Z"),
            row("Workers Standard","USD","0.42","1","requests").put("BillingPeriodStart","2026-08-14T00:00:00Z").put("ChargePeriodEnd","2026-09-13T00:00:00Z"));
        String u=b.usageThisCycle();
        assertTrue(u.contains("$0.42"));
        assertFalse(u.contains("$10"));
    }
    @Test public void dateFormatsEnglishLocale() {
        assertEquals("Sep 14",Billing.date(LocalDate.of(2026,9,14)));
        assertEquals("",Billing.date(null));
    }
    @Test public void combineUnionsCurrenciesWithZeroDefault() {
        SortedMap<String,BigDecimal> a=new TreeMap<>(); a.put("USD",new BigDecimal("1.00"));
        SortedMap<String,BigDecimal> b=new TreeMap<>(); b.put("EUR",new BigDecimal("2.00")); b.put("USD",new BigDecimal("0.50"));
        SortedMap<String,BigDecimal> r=Billing.combine(a,b);
        assertEquals(2,r.size());
        assertEquals(0,r.get("USD").compareTo(new BigDecimal("1.50")));
        assertEquals(0,r.get("EUR").compareTo(new BigDecimal("2.00")));
    }

    // --- New tests (brief4 section 1: identification fixes, unit variants, diagnostics helpers) ---

    @Test public void workersAndPagesFamilyIdentifiesWorkersNotPages() throws Exception {
        Billing b=parse(
            row("Workers Standard","USD","0","500","Requests","Requests").put("ServiceFamilyName","Workers & Pages"),
            row("Pages Functions","USD","0","300","Requests","Requests").put("ServiceFamilyName","Workers & Pages"));
        assertEquals(new BigDecimal("500"),b.workersUsage(false));
        Billing.WorkersMeter m=b.workersMeter(false);
        assertTrue(m.sources.contains("Workers Standard · Requests"));
        for(String src:m.sources) assertFalse("Pages leaked into meter: "+src,src.contains("Pages Functions"));
    }
    @Test public void requestsAcceptMillionUnit() throws Exception {
        Billing b=parse(row("Workers Standard","USD","0","2","million requests","Requests"));
        assertEquals(new BigDecimal("2000000"),b.workersUsage(false));
    }
    @Test public void cpuAcceptsMillionUnit() throws Exception {
        Billing b=parse(row("Workers Standard","USD","0","3","m cpu ms","CPU time"));
        assertEquals(new BigDecimal("3000000"),b.workersUsage(true));
    }
    @Test public void rowLabelsSortedWithoutCosts() throws Exception {
        JSONObject r=row("Workers Standard","USD","0","5","milliseconds","CPU time"); r.remove("ConsumedQuantity");
        Billing b=parse(row("D1 Rows Read","USD","0.42","150000","Rows").put("ServiceFamilyName","D1"),r);
        List<String> labels=Billing.rowLabels(b);
        assertEquals(2,labels.size());
        assertEquals("D1 Rows Read · Rows · D1",labels.get(0));
        assertEquals("Workers Standard · CPU time · milliseconds · Workers · no amount",labels.get(1));
        for(String s:labels) { assertFalse(s.contains("$")); assertFalse(s.contains("0.42")); }
    }
    @Test public void remainingFloorsAtZero() {
        assertEquals(new BigDecimal("7600000"),Billing.remaining(new BigDecimal("2400000"),Billing.REQUESTS_INCLUDED));
        assertEquals(BigDecimal.ZERO,Billing.remaining(new BigDecimal("40000000"),Billing.REQUESTS_INCLUDED));
    }
    // The following three tests mirror the exact row shapes seen on the device on 2026-09-12: ConsumedUnit is empty,
    // ChargeDescription repeats the name plus " usage measured in Count", and the name states the metric and scale.
    @Test public void liveShapeIdentifiesRequestsWithoutUnit() throws Exception {
        String name="Workers Standard Requests (first 10M are included)";
        Billing b=parse(row(name,"USD","0","171630","",name+" usage measured in Count"));
        assertEquals(new BigDecimal("171630"),b.workersUsage(false)); assertNull(b.workersUsage(true));
    }
    @Test public void liveShapeIdentifiesCpuMsWithoutUnit() throws Exception {
        String name="Workers CPU ms (first 30M are included)";
        Billing b=parse(row(name,"USD","0","90488","",name+" usage measured in Count"));
        assertEquals(new BigDecimal("90488"),b.workersUsage(true)); assertNull(b.workersUsage(false));
    }
    @Test public void liveShapeExcludesKvAndBuildMinutes() throws Exception {
        Billing b=parse(row("KV Read Operations (First 10M is included)","USD","0","5000","","KV Read Operations (First 10M is included) usage measured in Count"),
            row("Worker Build Minutes (6000 minutes included per month)","USD","0","2","","Worker Build Minutes (6000 minutes included per month) usage measured in Count"),
            row("KV Storage (GB, First GB is included)","USD","0","0.01","GB-months"));
        assertNull(b.workersMeter(false)); assertNull(b.workersMeter(true)); assertEquals(3,b.workersCandidates().size());
    }
}
