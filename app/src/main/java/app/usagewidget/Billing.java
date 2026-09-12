package app.usagewidget;

import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Period costs are additive. Never sum the cumulative cost fields. */
public final class Billing {
    public final List<Metric> metrics = new ArrayList<>();
    public final SortedMap<String, BigDecimal> totals = new TreeMap<>();
    public final SortedMap<String, BigDecimal> families = new TreeMap<>();
    public final SortedSet<String> cycles = new TreeSet<>();
    public LocalDate through;
    public boolean incomplete;
    public int rows;

    public static final BigDecimal REQUESTS_INCLUDED = new BigDecimal("10000000");
    public static final BigDecimal CPU_MS_INCLUDED   = new BigDecimal("30000000");
    public static final BigDecimal REQUESTS_RATE     = new BigDecimal("0.30");  // per additional million, USD
    public static final BigDecimal CPU_RATE          = new BigDecimal("0.02");  // per additional million, USD

    public static final class Metric {
        public String name, family, unit, currency;
        public BigDecimal consumed = BigDecimal.ZERO, cost = BigDecimal.ZERO;
        public boolean hasConsumed = true;
        public String description = "";     // ChargeDescription, "" if blank/null
        public String pricingUnit = "";     // PricingUnit, "" if blank/null
        public LocalDate cycleStart;        // from BillingPeriodStart (10-char), null if absent
        public LocalDate coverage;          // max coverageDate() among this metric's rows, null if none
        Metric(String n, String f, String u, String c) { name=n; family=f; unit=u; currency=c; }
        public String quantity() { return hasConsumed ? compact(consumed)+" "+unit : "Usage amount unavailable"; }
        public String label() {
            return (description.isBlank() || description.equalsIgnoreCase(name)) ? name : name + " · " + description;
        }
    }

    public static final class WorkersMeter {
        public final boolean cpu;
        public final BigDecimal consumed;   // requests, or CPU converted to ms; null = unknown (identified, no amount)
        public final boolean partial;       // some contributing rows lacked ConsumedQuantity
        public final List<String> sources;  // metric.label() of contributing rows; never null
        public final LocalDate cycleStart;  // latest BillingPeriodStart among contributing rows, or null
        public final LocalDate coverage;    // max coverage among contributing rows, or null
        public final String currency;       // BillingCurrency of contributing rows, or null
        WorkersMeter(boolean cpu, BigDecimal consumed, boolean partial, List<String> sources,
                     LocalDate cycleStart, LocalDate coverage, String currency) {
            this.cpu=cpu; this.consumed=consumed; this.partial=partial; this.sources=sources;
            this.cycleStart=cycleStart; this.coverage=coverage; this.currency=currency;
        }
    }

    public static final class Projection {
        public enum Reason { OK, NON_USD, EARLY, PARTIAL, UNKNOWN_DATES }
        public final BigDecimal projected;  // null unless reason==OK or NON_USD
        public final long elapsedDays;      // 0 if unknown
        public final long cycleDays;        // 0 if unknown
        public final BigDecimal overage;    // USD estimate; null unless reason==OK
        public final Reason reason;
        Projection(BigDecimal projected,long elapsedDays,long cycleDays,BigDecimal overage,Reason reason){
            this.projected=projected; this.elapsedDays=elapsedDays; this.cycleDays=cycleDays;
            this.overage=overage; this.reason=reason;
        }
    }

    /** Projected current-cycle usage cost per currency. projected is non-null; empty unless reason==OK. */
    public static final class CostProjection {
        public final SortedMap<String,BigDecimal> projected;  // non-null; empty unless reason==OK
        public final long elapsedDays, cycleDays;             // 0 when unknown
        public final Projection.Reason reason;                // OK, EARLY, or UNKNOWN_DATES only
        CostProjection(SortedMap<String,BigDecimal> projected,long elapsedDays,long cycleDays,Projection.Reason reason){
            this.projected=projected; this.elapsedDays=elapsedDays; this.cycleDays=cycleDays; this.reason=reason;
        }
    }

    private static final Set<String> EXCLUSION = new HashSet<>(Arrays.asList(
        "durable","objects","kv","ai","pages","observability","logs","logpush","build","builds",
        "container","containers","queue","queues","workflow","workflows","hyperdrive","vectorize",
        "platforms","analytics","browser","email","images","image","stream","r2","d1","trace",
        "tail","static","asset","assets","cache","cached"));

    public static Billing parse(String raw) throws Exception {
        JSONObject root = new JSONObject(raw);
        if (!root.optBoolean("success", false)) throw new IllegalArgumentException("Cloudflare did not return a successful usage response.");
        JSONArray data = root.optJSONArray("result");
        if (data == null) throw new IllegalArgumentException("Cloudflare returned an unfamiliar usage response.");
        Billing b = new Billing();
        Map<String,Metric> byMetric = new LinkedHashMap<>();
        for (int i=0; i<data.length(); i++) {
            JSONObject r=data.getJSONObject(i);
            String name=r.optString("ServiceName", ""), currency=r.optString("BillingCurrency", "");
            if (name.isBlank() || !currency.matches("[A-Z]{3}") || !r.has("ContractedCost") || r.isNull("ContractedCost"))
                throw new IllegalArgumentException("Usage response is missing a service, currency, or cost. Last snapshot kept.");
            BigDecimal cost=decimal(r,"ContractedCost");
            String family=r.optString("ServiceFamilyName", "");
            if (family.isBlank() || family.equals("null")) family=name;
            String unit=r.optString("ConsumedUnit", "");
            if (unit.isBlank() || unit.equals("null")) unit="units (unspecified)";
            String description=r.optString("ChargeDescription", "");
            if (description.equals("null")) description="";
            String pricingUnit=r.optString("PricingUnit", "");
            if (pricingUnit.equals("null")) pricingUnit="";
            String billingPeriodStart=r.optString("BillingPeriodStart", "");
            String cycleKey=billingPeriodStart.length()>=10 ? billingPeriodStart.substring(0,10) : "";
            String key=name+"\u0000"+family+"\u0000"+unit+"\u0000"+currency+"\u0000"+description+"\u0000"+cycleKey;
            Metric m=byMetric.get(key);
            if(m==null) {
                m=new Metric(name,family,unit,currency);
                m.description=description; m.pricingUnit=pricingUnit;
                m.cycleStart = cycleKey.isEmpty() ? null : LocalDate.parse(cycleKey);
                byMetric.put(key,m);
            }
            if(!r.has("ConsumedQuantity") || r.isNull("ConsumedQuantity")) m.hasConsumed=false;
            else m.consumed=m.consumed.add(decimal(r,"ConsumedQuantity"));
            m.cost=m.cost.add(cost);
            b.totals.merge(currency,cost,BigDecimal::add);
            b.families.merge(family+"\u0000"+currency,cost,BigDecimal::add);
            if(cycleKey.length()>=10) b.cycles.add(cycleKey);
            LocalDate end=coverageDate(r.optString("ChargePeriodEnd", ""));
            if(end==null) b.incomplete=true;
            else if(b.through==null || end.isAfter(b.through)) b.through=end;
            if(end!=null && (m.coverage==null || end.isAfter(m.coverage))) m.coverage=end;
            b.rows++;
        }
        b.metrics.addAll(byMetric.values());
        b.metrics.sort(Comparator.comparing((Metric m)->m.cost).reversed().thenComparing(m->m.name));
        return b;
    }

    private static BigDecimal decimal(JSONObject row,String key) throws org.json.JSONException { return new BigDecimal(row.get(key).toString()); }

    static LocalDate coverageDate(String input) {
        try { return OffsetDateTime.parse(input).toInstant().minusNanos(1).atOffset(ZoneOffset.UTC).toLocalDate(); }
        catch(Exception ignored) { return null; }
    }

    public String total() {
        if(rows==0) return "No usage reported";
        List<String> values=new ArrayList<>(); totals.forEach((c,a)->values.add(money(a,c)));
        return String.join(" + ",values);
    }
    public String period() { return cycles.size()==1 ? "Cycle since "+cycles.first() : cycles.isEmpty() ? "Current billing period" : "Current subscription cycles"; }
    public String coverage() { return through==null ? "Coverage date unavailable" : "Latest data: "+through+" UTC"+(incomplete?" (partial dates)":""); }
    public boolean oldCoverage() { return through!=null && through.isBefore(LocalDate.now(ZoneOffset.UTC).minusDays(2)); }

    /** Costs summed only over the latest cycle (metrics whose cycleStart == max cycleStart). Never null. */
    public SortedMap<String,BigDecimal> currentCycleTotals() {
        LocalDate latest=null;
        for (Metric m : metrics) if (m.cycleStart!=null && (latest==null || m.cycleStart.isAfter(latest))) latest=m.cycleStart;
        SortedMap<String,BigDecimal> out=new TreeMap<>();
        for (Metric m : metrics)
            if (latest==null || (m.cycleStart!=null && m.cycleStart.equals(latest)))
                out.merge(m.currency, m.cost, BigDecimal::add);
        return out;
    }

    public String usageThisCycle() {
        if (rows==0) return "No usage reported";
        String s=moneyJoin(currentCycleTotals());
        return s.isEmpty() ? "No usage reported" : s;
    }

    /** Projects the current cycle's usage cost to cycle end, per currency, scale 2 HALF_UP. Currencies never mixed. */
    public CostProjection projectTotals() {
        LocalDate latest=null;
        for (Metric m : metrics) if (m.cycleStart!=null && (latest==null || m.cycleStart.isAfter(latest))) latest=m.cycleStart;
        LocalDate coverage=through;
        if (latest==null || coverage==null || coverage.isBefore(latest))
            return new CostProjection(new TreeMap<>(), 0, 0, Projection.Reason.UNKNOWN_DATES);
        long cycleDays=ChronoUnit.DAYS.between(latest, latest.plusMonths(1));   // 28..31
        long elapsedDays=ChronoUnit.DAYS.between(latest, coverage) + 1;         // >=1
        if (elapsedDays < 3) return new CostProjection(new TreeMap<>(), elapsedDays, cycleDays, Projection.Reason.EARLY);
        SortedMap<String,BigDecimal> proj=new TreeMap<>();
        for (Map.Entry<String,BigDecimal> e : currentCycleTotals().entrySet())
            proj.put(e.getKey(), e.getValue().multiply(BigDecimal.valueOf(cycleDays))
                .divide(BigDecimal.valueOf(elapsedDays), 2, RoundingMode.HALF_UP));
        return new CostProjection(proj, elapsedDays, cycleDays, Projection.Reason.OK);
    }

    public static String date(LocalDate d) {
        return d==null ? "" : DateTimeFormatter.ofPattern("MMM d", Locale.US).format(d);
    }

    public static String moneyJoin(SortedMap<String,BigDecimal> map) {
        if (map.isEmpty()) return "";
        List<String> parts=new ArrayList<>();
        for (Map.Entry<String,BigDecimal> e : map.entrySet()) parts.add(money(e.getValue(), e.getKey()));
        return String.join(" + ", parts);
    }

    /** TreeMap over the union of both key sets; each value defaults missing entries to ZERO. Never null. */
    public static SortedMap<String,BigDecimal> combine(SortedMap<String,BigDecimal> a, SortedMap<String,BigDecimal> b) {
        SortedMap<String,BigDecimal> out=new TreeMap<>();
        Set<String> keys=new TreeSet<>();
        keys.addAll(a.keySet()); keys.addAll(b.keySet());
        for (String c : keys) out.put(c, a.getOrDefault(c, BigDecimal.ZERO).add(b.getOrDefault(c, BigDecimal.ZERO)));
        return out;
    }

    /** Family lines with a per-currency overflow. No "+N" count token; overflow amounts are currency-separated. */
    public List<String> familyLines(int max) {
        List<Map.Entry<String,BigDecimal>> entries = new ArrayList<>(families.entrySet());
        entries.sort((a,bx) -> {
            int c = bx.getValue().compareTo(a.getValue());     // value desc
            if (c != 0) return c;
            return famPart(a.getKey()).compareTo(famPart(bx.getKey())); // family asc
        });
        List<String> lines = new ArrayList<>();
        if (entries.size() < max) {
            for (Map.Entry<String,BigDecimal> e : entries) lines.add(familyText(e));
            return lines;
        }
        for (int i=0; i<max-1; i++) lines.add(familyText(entries.get(i)));
        List<Map.Entry<String,BigDecimal>> rest = entries.subList(max-1, entries.size());
        int k = rest.size();
        LinkedHashMap<String,BigDecimal> perCurrency = new LinkedHashMap<>();
        for (Map.Entry<String,BigDecimal> e : rest)
            perCurrency.merge(e.getKey().split("\u0000",-1)[1], e.getValue(), BigDecimal::add);
        boolean allZero = true;
        for (BigDecimal v : perCurrency.values()) if (v.signum()!=0) allZero=false;
        String amountText;
        if (allZero) {
            amountText = money(BigDecimal.ZERO, perCurrency.keySet().iterator().next());
        } else {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String,BigDecimal> e : perCurrency.entrySet()) parts.add(money(e.getValue(), e.getKey()));
            amountText = String.join(" + ", parts);
        }
        lines.add(k + (k==1 ? " more family · " : " more families · ") + amountText);
        return lines;
    }
    private static String famPart(String key) { return key.split("\u0000",-1)[0]; }
    private static String familyText(Map.Entry<String,BigDecimal> e) {
        String[] bits = e.getKey().split("\u0000",-1);
        return bits[0] + "  " + money(e.getValue(), bits[1]);
    }

    private static Set<String> tokens(String s) {
        Set<String> out = new HashSet<>();
        for (String tok : s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) if (!tok.isEmpty()) out.add(tok);
        return out;
    }
    // Inclusion scope: ServiceName + ChargeDescription + ServiceFamilyName. "Workers" may live only in the family.
    private static Set<String> tokensOf(Metric m) { return tokens(m.name + " " + m.description + " " + m.family); }
    // Exclusion scope: ServiceName + ChargeDescription only, so the shared "Workers & Pages" family never
    // rejects a genuine Workers row via the "pages" token.
    private static Set<String> exclusionTokensOf(Metric m) { return tokens(m.name + " " + m.description); }
    private static String normUnit(Metric m) { return m.unit.toLowerCase(Locale.ROOT).trim(); }
    private static boolean candidate(Metric m) {
        if (!tokensOf(m).contains("workers")) return false;
        Set<String> ex = exclusionTokensOf(m);
        for (String x : EXCLUSION) if (ex.contains(x)) return false;
        return true;
    }
    // Multiplier applied to ConsumedQuantity to reach raw requests, or null when the row is not a requests meter.
    private static BigDecimal requestsMultiplier(Metric m) {
        if (!candidate(m)) return null;
        String u = normUnit(m); Set<String> t = tokensOf(m);
        switch (u) {
            case "requests": case "request": return BigDecimal.ONE;
            case "million requests": case "millions of requests": case "m requests": case "1m requests":
                return new BigDecimal("1000000");
            default: break;
        }
        // Live rows arrive with an empty ConsumedUnit (shown as the placeholder) and a name such as
        // "Workers Standard Requests (first 10M are included)"; the name decides, and a CPU row never counts.
        Set<String> own = exclusionTokensOf(m);
        if (unspecifiedUnit(u) && (own.contains("request") || own.contains("requests")) && !own.contains("cpu"))
            return BigDecimal.ONE;
        return null;
    }
    private static boolean unspecifiedUnit(String u) {
        return u.isEmpty() || u.equals("count") || u.equals("units") || u.equals("unit") || u.equals("units (unspecified)");
    }
    private static boolean isRequests(Metric m) { return requestsMultiplier(m) != null; }
    // Multiplier applied to ConsumedQuantity to reach CPU milliseconds, or null when the unit is not CPU time.
    private static BigDecimal cpuMultiplier(Metric m) {
        String u = normUnit(m);
        switch (u) {
            case "ms": case "millisecond": case "milliseconds":
            case "cpu-ms": case "cpu-milliseconds": case "cpu ms": case "cpu milliseconds":
                return BigDecimal.ONE;
            case "s": case "sec": case "second": case "seconds":
            case "cpu-seconds": case "cpu seconds":
                return new BigDecimal("1000");
            case "million cpu ms": case "million cpu-milliseconds": case "million cpu milliseconds": case "m cpu ms":
                return new BigDecimal("1000000");
            default: break;
        }
        // Live rows such as "Workers CPU ms (first 30M are included)" carry no ConsumedUnit; the name states the scale.
        if (!unspecifiedUnit(u)) return null;
        Set<String> own = exclusionTokensOf(m);
        if (!own.contains("cpu")) return null;
        if (own.contains("ms") || own.contains("millisecond") || own.contains("milliseconds")) return BigDecimal.ONE;
        if (own.contains("s") || own.contains("sec") || own.contains("secs") || own.contains("second") || own.contains("seconds"))
            return new BigDecimal("1000");
        return null;
    }
    private static boolean isCpu(Metric m) {
        return candidate(m) && cpuMultiplier(m) != null;
    }

    /** null only if nothing identified; otherwise a meter whose consumed may be null (identified, no amount). */
    public WorkersMeter workersMeter(boolean cpu) {
        List<Metric> matched = new ArrayList<>();
        for (Metric m : metrics) if (cpu ? isCpu(m) : isRequests(m)) matched.add(m);
        if (matched.isEmpty()) return null;
        LocalDate latest = null;
        for (Metric m : matched) if (m.cycleStart!=null && (latest==null || m.cycleStart.isAfter(latest))) latest=m.cycleStart;
        BigDecimal sum = BigDecimal.ZERO; boolean any=false, partial=false;
        List<String> sources = new ArrayList<>();
        LocalDate coverage = null; String currency = null;
        for (Metric m : matched) {
            boolean contributes = (latest==null) || (m.cycleStart!=null && m.cycleStart.equals(latest));
            if (!contributes) continue;
            sources.add(m.label());
            if (currency==null) currency=m.currency;
            if (m.coverage!=null && (coverage==null || m.coverage.isAfter(coverage))) coverage=m.coverage;
            if (m.hasConsumed) {
                any=true;
                sum = sum.add(cpu ? m.consumed.multiply(cpuMultiplier(m)) : m.consumed.multiply(requestsMultiplier(m)));
            } else {
                partial=true;
            }
        }
        BigDecimal consumed = any ? sum : null;   // UNKNOWN, never 0
        return new WorkersMeter(cpu, consumed, partial, sources, latest, coverage, currency);
    }

    /** Only show allowance usage when service and raw units are unambiguous. */
    public BigDecimal workersUsage(boolean cpu) {
        WorkersMeter m = workersMeter(cpu);
        return (m==null || m.consumed==null) ? null : m.consumed;
    }

    public List<String> workersCandidates() {
        List<String> out = new ArrayList<>();
        for (Metric m : metrics) {
            if (tokensOf(m).contains("workers") && !isRequests(m) && !isCpu(m))
                out.add(m.label() + " · " + m.unit + (m.hasConsumed ? "" : " (no amount)"));
        }
        return out;
    }

    /** Every metric as label · unit · family (+ " · no amount" when unmeasured), sorted by label. No costs, no ids. */
    public static List<String> rowLabels(Billing b) {
        List<Metric> sorted = new ArrayList<>(b.metrics);
        sorted.sort(Comparator.comparing(Metric::label));
        List<String> out = new ArrayList<>();
        for (Metric m : sorted)
            out.add(m.label() + " · " + m.unit + " · " + m.family + (m.hasConsumed ? "" : " · no amount"));
        return out;
    }

    /** Allowance left this cycle: included minus consumed, floored at zero. Callers compact() it for display. */
    public static BigDecimal remaining(BigDecimal consumed, BigDecimal included) {
        return included.subtract(consumed).max(BigDecimal.ZERO);
    }

    public Projection project(WorkersMeter m) {
        if (m==null || m.consumed==null || m.cycleStart==null || m.coverage==null || m.coverage.isBefore(m.cycleStart))
            return new Projection(null, 0, 0, null, Projection.Reason.UNKNOWN_DATES);
        BigDecimal included = m.cpu ? CPU_MS_INCLUDED : REQUESTS_INCLUDED;
        BigDecimal rate     = m.cpu ? CPU_RATE       : REQUESTS_RATE;
        long cycleDays   = ChronoUnit.DAYS.between(m.cycleStart, m.cycleStart.plusMonths(1)); // 28..31
        long elapsedDays = ChronoUnit.DAYS.between(m.cycleStart, m.coverage) + 1;             // >=1
        if (m.partial)        return new Projection(null, elapsedDays, cycleDays, null, Projection.Reason.PARTIAL);
        if (elapsedDays < 3)  return new Projection(null, elapsedDays, cycleDays, null, Projection.Reason.EARLY);
        BigDecimal projected = m.consumed.multiply(BigDecimal.valueOf(cycleDays))
            .divide(BigDecimal.valueOf(elapsedDays), 0, RoundingMode.HALF_UP);
        if (!"USD".equals(m.currency)) return new Projection(projected, elapsedDays, cycleDays, null, Projection.Reason.NON_USD);
        BigDecimal overage = overageEstimate(projected, included, rate);
        return new Projection(projected, elapsedDays, cycleDays, overage, Projection.Reason.OK);
    }

    public static BigDecimal overageEstimate(BigDecimal projected, BigDecimal included, BigDecimal ratePerMillion) {
        BigDecimal over = projected.subtract(included).max(BigDecimal.ZERO);
        return over.divide(new BigDecimal("1000000")).multiply(ratePerMillion).setScale(2, RoundingMode.HALF_UP);
    }

    public static String percentText(BigDecimal consumed, BigDecimal included) {
        if (consumed.signum()==0) return "0%";
        BigDecimal pct = consumed.multiply(new BigDecimal("100")).divide(included, 0, RoundingMode.HALF_DOWN);
        if (consumed.signum()>0 && pct.signum()==0) return "<1%";
        return pct.toPlainString()+"%";
    }

    public static String compact(BigDecimal value) {
        BigDecimal abs=value.abs(), divisor=BigDecimal.ONE; String suffix="";
        if(abs.compareTo(new BigDecimal("1000000000"))>=0) { divisor=new BigDecimal("1000000000"); suffix="B"; }
        else if(abs.compareTo(new BigDecimal("1000000"))>=0) { divisor=new BigDecimal("1000000"); suffix="M"; }
        else if(abs.compareTo(new BigDecimal("1000"))>=0) { divisor=new BigDecimal("1000"); suffix="K"; }
        return value.divide(divisor,2,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()+suffix;
    }
    public static String money(BigDecimal value,String code) {
        NumberFormat f=NumberFormat.getCurrencyInstance(Locale.US);
        try { f.setCurrency(Currency.getInstance(code)); } catch(IllegalArgumentException ignored) { return code+" "+value.toPlainString(); }
        f.setMinimumFractionDigits(2); f.setMaximumFractionDigits(2); return f.format(value);
    }
    public static String checked(long millis) {
        return millis==0 ? "Never refreshed" : "Checked "+DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis));
    }
    public static String demo() {
        try {
        LocalDate today=LocalDate.now(ZoneOffset.UTC); JSONArray rows=new JSONArray();
        Object[][] samples={{"Workers Standard","Workers","Requests","Requests",2400000,0},
            {"Workers Standard","Workers","CPU time","CPU-milliseconds",2400000,0},
            {"Durable Objects Duration","Durable Objects",null,"GB-seconds",410000,0.42},
            {"D1 Rows Read","D1",null,"Rows",150000,0}};
        for(Object[] s:samples) {
            JSONObject row=new JSONObject().put("ServiceName",s[0]).put("ServiceFamilyName",s[1]).put("ConsumedUnit",s[3])
                .put("ConsumedQuantity",s[4]).put("ContractedCost",s[5]).put("BillingCurrency","USD")
                .put("BillingPeriodStart",today.withDayOfMonth(1)+"T00:00:00Z").put("ChargePeriodEnd",today+"T00:00:00Z");
            if(s[2]!=null) row.put("ChargeDescription",s[2]);
            rows.put(row);
        }
        return new JSONObject().put("success",true).put("result",rows).toString();
        } catch(org.json.JSONException e) { throw new IllegalStateException("Invalid bundled demo",e); }
    }
}
