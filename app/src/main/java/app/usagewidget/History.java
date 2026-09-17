package app.usagewidget;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * A rolling record of daily usage samples, kept on the device only.
 *
 * <p>The Cloudflare billable-usage endpoint returns cumulative current-cycle totals, never a per-day
 * series, so a week view can only be built from samples the app records itself over time. One sample
 * is kept per Cloudflare coverage date (the day the usage actually pertains to); repeated refreshes on
 * the same coverage date overwrite, so the stored value is that day's final cumulative reading. The
 * daily bar for a date is the increase since the previous stored date. A drop (cumulative reset at a
 * billing-cycle boundary) is read as the new cycle's usage so far, never as a negative bar.
 *
 * <p>All logic operates on plain {@link Sample} lists so it can be unit-tested without Android.
 */
final class History {
    static final int MAX_SAMPLES = 40;   // ~six weeks of daily coverage dates
    static final int WINDOW = 7;         // days shown in the week view

    enum Metric { CHARGES, REQUESTS, CPU }

    /** One coverage date's cumulative current-cycle figures. requests/cpuMs are null when unknown. */
    static final class Sample {
        final LocalDate date;
        final SortedMap<String,BigDecimal> charges;   // per-currency, never null (may be empty)
        final BigDecimal requests;                    // nullable
        final BigDecimal cpuMs;                        // nullable
        Sample(LocalDate date, SortedMap<String,BigDecimal> charges, BigDecimal requests, BigDecimal cpuMs) {
            this.date=date; this.charges=charges==null?new TreeMap<>():charges; this.requests=requests; this.cpuMs=cpuMs;
        }
    }

    /** One bar. absent: no sample that day. baseline: first-ever sample, no prior to diff against. */
    static final class Day {
        final LocalDate date;
        final boolean absent, baseline;
        final BigDecimal value;   // the day's usage; ZERO when absent/baseline
        Day(LocalDate date, boolean absent, boolean baseline, BigDecimal value) {
            this.date=date; this.absent=absent; this.baseline=baseline; this.value=value==null?BigDecimal.ZERO:value;
        }
    }

    /** A week of bars plus how to label them. money==true: values are currency amounts in {@code unit}. */
    static final class Week {
        final List<Day> days;      // exactly WINDOW entries, oldest first
        final BigDecimal max;      // largest non-baseline value, at least ONE for scaling
        final boolean money;
        final String unit;         // currency code (money) or a noun like "requests" / "ms"
        final boolean hasData;     // at least one non-baseline, non-absent bar
        Week(List<Day> days, BigDecimal max, boolean money, String unit, boolean hasData) {
            this.days=days; this.max=max; this.money=money; this.unit=unit; this.hasData=hasData;
        }
    }

    // ---- Context I/O ---------------------------------------------------------

    /** Parse a fresh snapshot and fold it into the stored history. Silent on any parse trouble. */
    static void record(Context context, String raw) {
        try {
            Billing b = Billing.parse(raw);
            if (b.through == null) return;   // no datable coverage; nothing to place on the axis
            BigDecimal req = b.workersUsage(false);   // null when unidentified or amount missing
            BigDecimal cpu = b.workersUsage(true);
            Sample fresh = new Sample(b.through, b.currentCycleTotals(), req, cpu);
            Store s = new Store(context);
            List<Sample> merged = upsert(read(s.prefs.getString("history","")), fresh);
            s.prefs.edit().putString("history", serialize(merged)).apply();
        } catch (Exception ignored) { /* history is best-effort; never break a refresh */ }
    }

    static List<Sample> read(Context context) { return read(new Store(context).prefs.getString("history","")); }

    // ---- pure logic (unit-tested) -------------------------------------------

    /** Insert or replace fresh by date, keep sorted ascending, trim to the most recent MAX_SAMPLES. */
    static List<Sample> upsert(List<Sample> existing, Sample fresh) {
        Map<LocalDate,Sample> byDate = new TreeMap<>();
        for (Sample s : existing) byDate.put(s.date, s);
        byDate.put(fresh.date, fresh);
        List<Sample> all = new ArrayList<>(byDate.values());   // ascending by date
        if (all.size() > MAX_SAMPLES) all = new ArrayList<>(all.subList(all.size()-MAX_SAMPLES, all.size()));
        return all;
    }

    /** Build the week ending at the latest stored sample. Samples need not be sorted. */
    static Week week(List<Sample> samples, Metric metric) {
        List<Sample> asc = new ArrayList<>(samples);
        asc.sort(Comparator.comparing(x -> x.date));
        boolean money = metric == Metric.CHARGES;
        String unit = money ? chargesCurrency(asc) : (metric == Metric.REQUESTS ? "requests" : "ms");
        List<Day> days = new ArrayList<>();
        if (asc.isEmpty()) {
            LocalDate end = LocalDate.now(java.time.ZoneOffset.UTC);
            for (int i = WINDOW-1; i >= 0; i--) days.add(new Day(end.minusDays(i), true, false, BigDecimal.ZERO));
            return new Week(days, BigDecimal.ONE, money, unit, false);
        }
        LocalDate end = asc.get(asc.size()-1).date;
        BigDecimal max = BigDecimal.ZERO; boolean any = false;
        for (int i = WINDOW-1; i >= 0; i--) {
            LocalDate d = end.minusDays(i);
            Sample cur = on(asc, d);
            BigDecimal curVal = cur == null ? null : value(cur, metric, unit);
            if (curVal == null) { days.add(new Day(d, true, false, BigDecimal.ZERO)); continue; }
            Sample prev = before(asc, d);
            BigDecimal prevVal = prev == null ? null : value(prev, metric, unit);
            if (prev == null || prevVal == null) { days.add(new Day(d, false, true, BigDecimal.ZERO)); continue; }
            BigDecimal daily = curVal.subtract(prevVal);
            if (daily.signum() < 0) daily = curVal;   // cycle reset: cumulative dropped, count the new cycle so far
            days.add(new Day(d, false, false, daily));
            if (daily.compareTo(max) > 0) max = daily;
            any = true;
        }
        if (max.signum() <= 0) max = BigDecimal.ONE;
        return new Week(days, max, money, unit, any);
    }

    private static BigDecimal value(Sample s, Metric metric, String unit) {
        switch (metric) {
            case CHARGES:   return s.charges.getOrDefault(unit, BigDecimal.ZERO);
            case REQUESTS:  return s.requests;
            default:        return s.cpuMs;
        }
    }

    /** USD when any sample reports it; otherwise the currency with the largest amount in the latest sample. */
    private static String chargesCurrency(List<Sample> asc) {
        for (Sample s : asc) if (s.charges.containsKey("USD")) return "USD";
        String best = "USD";
        if (!asc.isEmpty()) {
            BigDecimal top = null;
            for (Map.Entry<String,BigDecimal> e : asc.get(asc.size()-1).charges.entrySet())
                if (top == null || e.getValue().compareTo(top) > 0) { top = e.getValue(); best = e.getKey(); }
        }
        return best;
    }

    private static Sample on(List<Sample> asc, LocalDate d) {
        for (Sample s : asc) if (s.date.equals(d)) return s;
        return null;
    }
    private static Sample before(List<Sample> asc, LocalDate d) {
        Sample found = null;
        for (Sample s : asc) { if (s.date.isBefore(d)) found = s; else break; }
        return found;   // asc is sorted, so the last one before d
    }

    // ---- serialization -------------------------------------------------------

    static String serialize(List<Sample> samples) {
        JSONArray arr = new JSONArray();
        try {
            for (Sample s : samples) {
                JSONObject o = new JSONObject();
                o.put("d", s.date.toString());
                JSONObject c = new JSONObject();
                for (Map.Entry<String,BigDecimal> e : s.charges.entrySet()) c.put(e.getKey(), e.getValue().toPlainString());
                o.put("c", c);
                if (s.requests != null) o.put("r", s.requests.toPlainString());
                if (s.cpuMs != null)    o.put("p", s.cpuMs.toPlainString());
                arr.put(o);
            }
        } catch (org.json.JSONException e) { return "[]"; }
        return arr.toString();
    }

    static List<Sample> read(String stored) {
        List<Sample> out = new ArrayList<>();
        if (stored == null || stored.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(stored);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                LocalDate date = LocalDate.parse(o.getString("d"));
                SortedMap<String,BigDecimal> charges = new TreeMap<>();
                JSONObject c = o.optJSONObject("c");
                if (c != null) for (Iterator<String> it = c.keys(); it.hasNext(); ) { String k = it.next(); charges.put(k, new BigDecimal(c.getString(k))); }
                BigDecimal req = o.has("r") && !o.isNull("r") ? new BigDecimal(o.getString("r")) : null;
                BigDecimal cpu = o.has("p") && !o.isNull("p") ? new BigDecimal(o.getString("p")) : null;
                out.add(new Sample(date, charges, req, cpu));
            }
        } catch (Exception ignored) { return new ArrayList<>(); }   // unreadable history is discarded, not fatal
        return out;
    }

    private History() {}
}
