package app.usagewidget;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Pure parsing + maths for account-scope subscriptions. No Android imports. Fees stay separate from usage. */
public final class Subscriptions {

    public static final class Item {
        public String id = "", name = "", currency = "", frequency = "", state = "";
        public BigDecimal price;      // null if missing or unparseable
        public LocalDate periodEnd;   // null if absent or unparseable
        public boolean priced() { return price != null && currency.matches("[A-Z]{3}"); }
    }

    public final List<Item> items = new ArrayList<>();   // all parsed items, in response order

    public static Subscriptions parse(String raw) throws JSONException {
        JSONObject root = new JSONObject(raw);
        if (!root.optBoolean("success", false))
            throw new IllegalArgumentException("Cloudflare did not return a successful subscriptions response.");
        JSONArray data = root.optJSONArray("result");
        if (data == null)
            throw new IllegalArgumentException("Cloudflare returned an unfamiliar subscriptions response.");
        Subscriptions subs = new Subscriptions();
        for (int i = 0; i < data.length(); i++) {
            JSONObject o = data.optJSONObject(i);
            if (o == null)
                throw new IllegalArgumentException("Subscriptions response contained a non-object entry.");
            JSONObject ratePlan = o.optJSONObject("rate_plan");
            Item item = new Item();
            item.id = norm(o.optString("id", ""));
            String c = norm(o.optString("currency", ""));
            if (!c.matches("[A-Z]{3}")) c = ratePlan == null ? "" : norm(ratePlan.optString("currency", ""));
            if (!c.matches("[A-Z]{3}")) c = "";
            item.currency = c;
            item.frequency = norm(o.optString("frequency", ""));
            item.state = norm(o.optString("state", ""));
            if (o.has("price") && !o.isNull("price")) {
                try { item.price = new BigDecimal(o.get("price").toString()); }
                catch (NumberFormatException ignored) { item.price = null; }
            }
            item.name = name(ratePlan);
            item.periodEnd = date(norm(o.optString("current_period_end", "")));
            subs.items.add(item);
        }
        return subs;
    }

    private static String norm(String s) { return "null".equals(s) ? "" : s; }

    private static String name(JSONObject ratePlan) {
        if (ratePlan != null) {
            String pn = norm(ratePlan.optString("public_name", ""));
            if (!pn.isEmpty()) return pn;
            String id = norm(ratePlan.optString("id", ""));
            if (!id.isEmpty()) return id;
        }
        return "Subscription";
    }

    /** Renewal date, taken as-is. NO minus-nanosecond adjustment (distinct from Billing.coverageDate). */
    private static LocalDate date(String s) {
        try { return OffsetDateTime.parse(s).toLocalDate(); }
        catch (Exception ignored) {
            try { return LocalDate.parse(s.substring(0, 10)); }
            catch (Exception ignored2) { return null; }
        }
    }

    public List<Item> active() {
        List<Item> out = new ArrayList<>();
        for (Item it : items) {
            switch (it.state.toLowerCase(Locale.ROOT)) {
                case "paid": case "provisioned": case "awaitingpayment": case "trial": out.add(it); break;
                default: break;
            }
        }
        return out;
    }

    public SortedMap<String, BigDecimal> dueWithin(LocalDate today, int days) {
        SortedMap<String, BigDecimal> map = new TreeMap<>();
        LocalDate end = today.plusDays(days);
        for (Item it : active()) {
            if (it.priced() && it.periodEnd != null && !"trial".equalsIgnoreCase(it.state)
                    && !it.periodEnd.isBefore(today) && !it.periodEnd.isAfter(end))
                map.merge(it.currency, it.price, BigDecimal::add);
        }
        return map;
    }

    public List<Item> unpriced() {
        List<Item> out = new ArrayList<>();
        for (Item it : active()) if (!it.priced()) out.add(it);
        return out;
    }

    public LocalDate nextDue() {
        LocalDate min = null;
        for (Item it : active()) {
            if (it.priced() && !"trial".equalsIgnoreCase(it.state) && it.periodEnd != null
                    && (min == null || it.periodEnd.isBefore(min)))
                min = it.periodEnd;
        }
        return min;
    }

    public boolean mentionsWorkersPaid() {
        for (Item it : active()) {
            Set<String> toks = new HashSet<>();
            for (String t : it.name.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) if (!t.isEmpty()) toks.add(t);
            if (toks.contains("workers") && toks.contains("paid")) return true;
        }
        return false;
    }

    public static String demo() {
        try {
            String end = LocalDate.now(ZoneOffset.UTC).plusMonths(1).withDayOfMonth(1) + "T00:00:00Z";
            JSONObject item = new JSONObject()
                .put("id", "demo-workers").put("currency", "USD").put("frequency", "monthly")
                .put("price", 5).put("state", "Paid").put("current_period_end", end)
                .put("rate_plan", new JSONObject().put("public_name", "Workers Paid"));
            return new JSONObject().put("success", true).put("result", new JSONArray().put(item)).toString();
        } catch (JSONException e) { throw new IllegalStateException("Invalid bundled demo", e); }
    }
}
