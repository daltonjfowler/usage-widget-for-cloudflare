package app.usagewidget;
import org.junit.Test;
import org.json.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.SortedMap;
import static org.junit.Assert.*;

public class SubscriptionsTest {
    private final LocalDate today = LocalDate.now(ZoneOffset.UTC);

    private String wrap(JSONObject... items) throws JSONException {
        return new JSONObject().put("success",true).put("result",new JSONArray(items)).toString();
    }
    private String iso(LocalDate d) { return d + "T00:00:00Z"; }
    /** Item with a public_name rate plan. Any null argument is omitted from the JSON. */
    private JSONObject item(String publicName,String currency,Object price,String frequency,String state,String periodEnd) throws JSONException {
        JSONObject o=new JSONObject();
        if(currency!=null) o.put("currency",currency);
        if(price!=null) o.put("price",price);
        if(frequency!=null) o.put("frequency",frequency);
        if(state!=null) o.put("state",state);
        if(periodEnd!=null) o.put("current_period_end",periodEnd);
        JSONObject rp=new JSONObject();
        if(publicName!=null) rp.put("public_name",publicName);
        o.put("rate_plan",rp);
        return o;
    }

    @Test public void parsesFieldsFromRatePlan() throws Exception {
        Subscriptions s=Subscriptions.parse(wrap(item("Business Plan","USD",20,"monthly","Paid","2026-10-01T00:00:00Z")));
        assertEquals(1,s.active().size());
        Subscriptions.Item it=s.active().get(0);
        assertEquals("Business Plan",it.name);
        assertEquals(0,it.price.compareTo(new BigDecimal("20")));
        assertEquals("USD",it.currency);
        assertEquals("monthly",it.frequency);
        assertEquals(LocalDate.of(2026,10,1),it.periodEnd);
    }
    @Test public void periodEndTakenAsIsNoMinusNano() throws Exception {
        Subscriptions s=Subscriptions.parse(wrap(item("Business Plan","USD",20,"monthly","Paid","2026-10-01T00:00:00Z")));
        assertEquals(LocalDate.of(2026,10,1),s.active().get(0).periodEnd);
        assertNotEquals(LocalDate.of(2026,9,30),s.active().get(0).periodEnd);
    }
    @Test public void missingPriceIsNullAndUnpriced() throws Exception {
        Subscriptions s=Subscriptions.parse(wrap(item("X","USD",null,"monthly","Paid",iso(today.plusDays(5)))));
        Subscriptions.Item it=s.active().get(0);
        assertNull(it.price);
        assertTrue(s.unpriced().contains(it));
        assertFalse(s.dueWithin(today,31).containsKey("USD"));
    }
    @Test public void invalidCurrencyTreatedAsUnpriced() throws Exception {
        Subscriptions s=Subscriptions.parse(wrap(item("X",null,9,"monthly","Paid",iso(today.plusDays(5)))));
        Subscriptions.Item it=s.active().get(0);
        assertFalse(it.priced());
        assertTrue(s.unpriced().contains(it));
        assertTrue(s.dueWithin(today,31).isEmpty());
    }
    @Test public void currencyFallsBackToRatePlan() throws Exception {
        JSONObject o=new JSONObject().put("price",10).put("state","Paid").put("frequency","monthly")
            .put("rate_plan",new JSONObject().put("public_name","X").put("currency","EUR"));
        Subscriptions s=Subscriptions.parse(wrap(o));
        Subscriptions.Item it=s.active().get(0);
        assertEquals("EUR",it.currency);
        assertTrue(it.priced());
    }
    @Test public void activeFiltersStatesCaseInsensitive() throws Exception {
        Subscriptions s=Subscriptions.parse(wrap(
            item("A","USD",1,"monthly","paid",iso(today.plusDays(3))),
            item("B","USD",1,"monthly","PROVISIONED",iso(today.plusDays(3))),
            item("C","USD",1,"monthly","awaitingpayment",iso(today.plusDays(3))),
            item("D","USD",1,"monthly","trial",iso(today.plusDays(3))),
            item("E","USD",1,"monthly","Cancelled",iso(today.plusDays(3))),
            item("F","USD",1,"monthly","Failed",iso(today.plusDays(3))),
            item("G","USD",1,"monthly","Expired",iso(today.plusDays(3)))));
        assertEquals(4,s.active().size());
        for(Subscriptions.Item it:s.active()) {
            String st=it.state.toLowerCase(java.util.Locale.ROOT);
            assertTrue(st.equals("paid")||st.equals("provisioned")||st.equals("awaitingpayment")||st.equals("trial"));
        }
    }
    @Test public void dueWithinSeparatesCurrenciesAndWindowEdges() throws Exception {
        Subscriptions s=Subscriptions.parse(wrap(
            item("A","USD",5,"monthly","Paid",iso(today)),
            item("B","EUR",3,"monthly","Paid",iso(today.plusDays(31))),
            item("C","USD",9,"monthly","Paid",iso(today.plusDays(32))),
            item("D","USD",7,"monthly","Paid",iso(today.minusDays(1)))));
        SortedMap<String,BigDecimal> due=s.dueWithin(today,31);
        assertEquals(2,due.size());
        assertEquals(0,due.get("USD").compareTo(new BigDecimal("5")));
        assertEquals(0,due.get("EUR").compareTo(new BigDecimal("3")));
    }
    @Test public void dueWithinExcludesTrialFromSum() throws Exception {
        Subscriptions s=Subscriptions.parse(wrap(item("T","USD",5,"monthly","Trial",iso(today.plusDays(5)))));
        assertTrue(s.dueWithin(today,31).isEmpty());
        assertEquals(1,s.active().size());
    }
    @Test public void nextDueIsEarliestPricedNonTrial() throws Exception {
        Subscriptions s=Subscriptions.parse(wrap(
            item("A","USD",5,"monthly","Paid",iso(today.plusDays(10))),
            item("B","USD",5,"monthly","Paid",iso(today.plusDays(3))),
            item("C","USD",5,"monthly","Trial",iso(today.plusDays(1))),
            item("D","USD",null,"monthly","Paid",iso(today.plusDays(2)))));
        assertEquals(today.plusDays(3),s.nextDue());
    }
    @Test public void demoParsesAndMentionsWorkersPaid() throws Exception {
        Subscriptions s=Subscriptions.parse(Subscriptions.demo());
        assertEquals(1,s.active().size());
        assertTrue(s.active().get(0).name.contains("Workers Paid"));
        assertEquals(0,s.active().get(0).price.compareTo(new BigDecimal("5")));
        assertTrue(s.mentionsWorkersPaid());
        assertEquals(0,s.dueWithin(today,31).get("USD").compareTo(new BigDecimal("5")));
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsFailureResponse() throws Exception {
        Subscriptions.parse("{\"success\":false,\"result\":[]}");
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsNonArrayResult() throws Exception {
        Subscriptions.parse(new JSONObject().put("success",true).put("result",new JSONObject()).toString());
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsNonObjectElement() throws Exception {
        Subscriptions.parse("{\"success\":true,\"result\":[\"hello\"]}");
    }
    @Test public void unparseablePeriodEndIsNull() throws Exception {
        Subscriptions s=Subscriptions.parse(wrap(item("X","USD",5,"monthly","Paid","not-a-date")));
        Subscriptions.Item it=s.active().get(0);
        assertNull(it.periodEnd);
        assertEquals(1,s.active().size());
        assertTrue(s.dueWithin(today,31).isEmpty());
    }
}
