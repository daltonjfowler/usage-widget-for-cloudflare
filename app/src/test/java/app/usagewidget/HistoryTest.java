package app.usagewidget;
import org.junit.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.Assert.*;

public class HistoryTest {
    private History.Sample usd(String date,String amount) {
        TreeMap<String,BigDecimal> c=new TreeMap<>(); c.put("USD",new BigDecimal(amount));
        return new History.Sample(LocalDate.parse(date),c,null,null);
    }
    private History.Sample charges(String date,String... pairs) {
        TreeMap<String,BigDecimal> c=new TreeMap<>();
        for(int i=0;i<pairs.length;i+=2) c.put(pairs[i],new BigDecimal(pairs[i+1]));
        return new History.Sample(LocalDate.parse(date),c,null,null);
    }
    private History.Sample req(String date,String r) {
        return new History.Sample(LocalDate.parse(date),new TreeMap<>(),r==null?null:new BigDecimal(r),null);
    }
    private History.Day day(History.Week w,String date) {
        for(History.Day d:w.days) if(d.date.equals(LocalDate.parse(date))) return d;
        return null;
    }
    private List<History.Sample> list(History.Sample... s) { return new ArrayList<>(Arrays.asList(s)); }

    @Test public void weekAlwaysHasSevenDaysEndingAtLatestSample() {
        History.Week w=History.week(list(usd("2026-09-10","1"),usd("2026-09-12","2")),History.Metric.CHARGES);
        assertEquals(7,w.days.size());
        assertEquals(LocalDate.parse("2026-09-12"),w.days.get(6).date);
        assertEquals(LocalDate.parse("2026-09-06"),w.days.get(0).date);
    }
    @Test public void dailyBarsAreTheIncreaseSincePreviousDay() {
        History.Week w=History.week(list(usd("2026-09-10","1.00"),usd("2026-09-11","1.30"),usd("2026-09-12","1.50")),History.Metric.CHARGES);
        assertTrue(day(w,"2026-09-10").baseline);                       // first-ever sample: no prior to diff
        assertEquals(0,new BigDecimal("0.30").compareTo(day(w,"2026-09-11").value));
        assertEquals(0,new BigDecimal("0.20").compareTo(day(w,"2026-09-12").value));
        assertTrue(w.hasData);
    }
    @Test public void missingDayIsAbsentNotZero() {
        History.Week w=History.week(list(usd("2026-09-10","1.00"),usd("2026-09-12","1.40")),History.Metric.CHARGES);
        assertTrue(day(w,"2026-09-11").absent);
        assertFalse(day(w,"2026-09-11").baseline);
        // 09-12 diffs against 09-10 across the gap; still a real bar
        assertEquals(0,new BigDecimal("0.40").compareTo(day(w,"2026-09-12").value));
    }
    @Test public void cycleResetCountsTheNewCycleNotANegativeBar() {
        History.Week w=History.week(list(usd("2026-09-10","4.00"),usd("2026-09-11","5.00"),usd("2026-09-12","0.40")),History.Metric.CHARGES);
        assertEquals(0,new BigDecimal("1.00").compareTo(day(w,"2026-09-11").value));
        assertEquals(0,new BigDecimal("0.40").compareTo(day(w,"2026-09-12").value));   // reset, not -4.60
        assertTrue(day(w,"2026-09-12").value.signum()>0);
    }
    @Test public void chargesPreferUsd() {
        History.Week w=History.week(list(charges("2026-09-11","EUR","1","USD","2"),charges("2026-09-12","EUR","3","USD","5")),History.Metric.CHARGES);
        assertEquals("USD",w.unit);
        assertEquals(0,new BigDecimal("3").compareTo(day(w,"2026-09-12").value));
    }
    @Test public void chargesFallBackToLargestCurrencyWhenNoUsd() {
        History.Week w=History.week(list(charges("2026-09-11","EUR","2","GBP","4"),charges("2026-09-12","EUR","3","GBP","9")),History.Metric.CHARGES);
        assertEquals("GBP",w.unit);
        assertEquals(0,new BigDecimal("5").compareTo(day(w,"2026-09-12").value));
    }
    @Test public void requestBarsDeltaAndUnknownDaysAreAbsent() {
        History.Week w=History.week(list(req("2026-09-10","100"),req("2026-09-11","250"),req("2026-09-12",null)),History.Metric.REQUESTS);
        assertEquals("requests",w.unit);
        assertEquals(0,new BigDecimal("150").compareTo(day(w,"2026-09-11").value));
        assertTrue(day(w,"2026-09-12").absent);                        // amount unknown that day
    }
    @Test public void aDayWhosePriorAmountIsUnknownIsBaselineNotAWrongDelta() {
        History.Week w=History.week(list(req("2026-09-10",null),req("2026-09-11","250")),History.Metric.REQUESTS);
        assertTrue(day(w,"2026-09-11").baseline);
    }
    @Test public void emptyHistoryReportsNoData() {
        History.Week w=History.week(new ArrayList<>(),History.Metric.CHARGES);
        assertEquals(7,w.days.size());
        assertFalse(w.hasData);
        for(History.Day d:w.days) assertTrue(d.absent);
    }
    @Test public void upsertReplacesSameCoverageDate() {
        List<History.Sample> h=History.upsert(list(usd("2026-09-12","1.00")),usd("2026-09-12","2.00"));
        assertEquals(1,h.size());
        assertEquals(0,new BigDecimal("2.00").compareTo(h.get(0).charges.get("USD")));
    }
    @Test public void upsertSortsAscendingByDate() {
        List<History.Sample> h=History.upsert(list(usd("2026-09-12","2")),usd("2026-09-10","1"));
        assertEquals(LocalDate.parse("2026-09-10"),h.get(0).date);
        assertEquals(LocalDate.parse("2026-09-12"),h.get(1).date);
    }
    @Test public void historyIsCappedToTheMostRecentSamples() {
        List<History.Sample> h=new ArrayList<>();
        LocalDate start=LocalDate.parse("2026-01-01");
        for(int i=0;i<History.MAX_SAMPLES+5;i++) h=History.upsert(h,usd(start.plusDays(i).toString(),"1"));
        assertEquals(History.MAX_SAMPLES,h.size());
        assertEquals(start.plusDays(5),h.get(0).date);                 // earliest five dropped
        assertEquals(start.plusDays(History.MAX_SAMPLES+4),h.get(h.size()-1).date);
    }
    @Test public void serializeSurvivesRoundTrip() {
        List<History.Sample> in=list(
            new History.Sample(LocalDate.parse("2026-09-11"),new TreeMap<>(Map.of("USD",new BigDecimal("1.25"))),new BigDecimal("166950"),new BigDecimal("87840")),
            usd("2026-09-12","2.50"));
        List<History.Sample> out=History.read(History.serialize(in));
        assertEquals(2,out.size());
        assertEquals(0,new BigDecimal("1.25").compareTo(out.get(0).charges.get("USD")));
        assertEquals(0,new BigDecimal("166950").compareTo(out.get(0).requests));
        assertEquals(0,new BigDecimal("87840").compareTo(out.get(0).cpuMs));
        assertNull(out.get(1).requests);
    }
    @Test public void unreadableHistoryIsDiscardedNotFatal() {
        assertTrue(History.read("not json").isEmpty());
        assertTrue(History.read("").isEmpty());
    }
}
