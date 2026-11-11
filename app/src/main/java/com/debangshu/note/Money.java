package com.debangshu.note;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Money in plain text (no Android here, so it can be tested on a computer):
 *   - what was spent (price lists in notes),
 *   - lend / borrow lines ("gave Rahul 500", "borrowed 200 from mom", "Rahul returned 200"),
 *   - split with friends ("Rahul paid 1200", "I paid 800", "split between me, Rahul, Amit"),
 *   - bills & subscriptions ("Netflix ₹649/month on 5th", "rent 15000 pm", "insurance 12000 per year"),
 *   - the item name of a price line, for the price memory,
 *   - a scanned shop bill → a price list.
 */
public final class Money {

    private Money() {}

    // ------------------------------------------------------------------ shared bits

    static final String AMT = "(?:₹|rs\\.?|inr)?\\s*(\\d[\\d,]*(?:\\.\\d{1,2})?)(?:\\s*(?:rs\\.?|rupees?|inr|/-|k\\b))?";
    private static final String NAME = "([\\p{L}][\\p{L}\\p{M}.']{0,20})";
    private static final Pattern LIST_MARK = Pattern.compile("^\\s*(?:[-*•☐☑➜]\\s*|\\d{1,3}[.)]\\s+)");
    private static final java.util.Set<String> NOT_NAMES = new java.util.HashSet<>(java.util.Arrays.asList(
            "me", "i", "him", "her", "them", "the", "a", "an", "it", "back", "money", "rs", "₹", "to", "from", "for",
            "and", "of", "on", "at", "in", "my", "his", "their", "you", "us", "we", "cash", "upi", "total", "advance"));

    static double num(String s) {
        try { return Double.parseDouble(s.replace(",", "")); } catch (Exception e) { return 0; }
    }

    /** "rahul" → "Rahul", "i"/"me"/"myself" → "Me". */
    public static String person(String raw) {
        String p = raw.trim().replaceAll("[.']+$", "");
        String low = p.toLowerCase(Locale.ROOT);
        if (low.equals("i") || low.equals("me") || low.equals("myself") || low.equals("mine")) return "Me";
        if (p.isEmpty()) return p;
        return p.substring(0, 1).toUpperCase(Locale.ROOT) + p.substring(1);
    }

    private static boolean okName(String n) {
        return n != null && !NOT_NAMES.contains(n.toLowerCase(Locale.ROOT)) && !n.matches("\\d.*");
    }

    private static String bare(String line) {
        return LIST_MARK.matcher(line).replaceFirst("").trim();
    }

    // ------------------------------------------------------------------ lend & borrow

    public static final class Debt {
        public String person;
        public double amount;     // + they owe me, − I owe them
        public boolean settled;   // the line is ticked ☑
        public String line;
    }

    private static final Pattern[] OWE_ME = {
            Pattern.compile("(?i)\\b(?:gave|lent|loaned|given|lend|give)\\s+" + NAME + "\\s+" + AMT),
            Pattern.compile("(?i)\\b(?:gave|lent|loaned|given|lend)\\s+" + AMT + "\\s+to\\s+" + NAME),
            Pattern.compile("(?i)^" + NAME + "\\s+(?:owes|has to (?:give|pay|return)|needs to (?:give|pay|return)|should (?:give|pay|return))\\s+(?:me\\s+)?" + AMT),
            Pattern.compile("(?i)^" + NAME + "\\s+(?:borrowed|took)\\s+" + AMT),
            Pattern.compile("(?i)^" + NAME + "\\s+(?:ko|ke|k)\\s+" + AMT + "\\s*(?:diye|diya|dia|de diye|udhar|udhaar|dilam|diyechi|dichi)\\b"),
    };
    private static final Pattern[] I_OWE = {
            Pattern.compile("(?i)\\b(?:borrowed|took|taken|got|loan of|loan)\\s+" + AMT + "\\s+from\\s+" + NAME),
            Pattern.compile("(?i)\\b(?:i\\s+)?owe\\s+" + NAME + "\\s+" + AMT),
            Pattern.compile("(?i)\\b(?:i\\s+)?owe\\s+" + AMT + "\\s+to\\s+" + NAME),
            Pattern.compile("(?i)^" + NAME + "\\s+(?:lent|gave|loaned|given)\\s+me\\s+" + AMT),
            Pattern.compile("(?i)^" + NAME + "\\s+(?:se|theke|er theke|kach theke)\\s+" + AMT + "\\s*(?:liye|liya|lia|udhar|udhaar|nilam|niyechi)\\b"),
    };
    /** Paying back: lowers what someone owes. */
    private static final Pattern[] THEY_RETURNED = {
            Pattern.compile("(?i)^" + NAME + "\\s+(?:returned|paid back|repaid|gave back|paid me back|paid me)\\s+(?:me\\s+)?" + AMT),
            Pattern.compile("(?i)\\b(?:got|received)\\s+" + AMT + "\\s+(?:back\\s+)?from\\s+" + NAME),
    };
    private static final Pattern[] I_RETURNED = {
            Pattern.compile("(?i)\\b(?:returned|paid back|repaid|gave back)\\s+(?:to\\s+)?" + NAME + "\\s+" + AMT),
            Pattern.compile("(?i)\\b(?:returned|paid back|repaid|gave back)\\s+" + AMT + "\\s+to\\s+" + NAME),
    };

    /** A lend / borrow line, or null. */
    public static Debt debt(String line) {
        if (line == null) return null;
        String t = line.trim();
        boolean settled = t.startsWith("☑");
        String b = bare(t);
        if (b.isEmpty() || ListTotals.isTotalLine(b)) return null;
        Debt d = match(b, THEY_RETURNED, -1, true);
        if (d == null) d = match(b, I_RETURNED, +1, false);
        if (d == null) d = match(b, OWE_ME, +1, false);
        if (d == null) d = match(b, I_OWE, -1, false);
        if (d == null) return null;
        d.settled = settled;
        d.line = t;
        return d;
    }

    /** The name and the amount can come in either order in the patterns, so both groups are looked at. */
    private static Debt match(String b, Pattern[] ps, int sign, boolean unused) {
        for (Pattern p : ps) {
            Matcher m = p.matcher(b);
            if (!m.find()) continue;
            String name = null, amt = null;
            for (int g = 1; g <= m.groupCount(); g++) {
                String v = m.group(g);
                if (v == null) continue;
                if (v.matches("\\d[\\d,]*(?:\\.\\d+)?")) { if (amt == null) amt = v; }
                else if (name == null) name = v;
            }
            if (name == null || amt == null || !okName(name)) continue;
            double a = num(amt);
            if (b.substring(m.start(), m.end()).matches("(?is).*\\d\\s*k\\b.*")) a *= 1000;   // "gave Rahul 5k"
            if (a <= 0) continue;
            Debt d = new Debt();
            d.person = person(name);
            d.amount = sign * a;
            return d;
        }
        return null;
    }

    /** Everyone's balance from these lines (ticked lines are settled and left out). + = they owe me. */
    public static Map<String, Double> balances(List<String> lines) {
        Map<String, Double> out = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (String l : lines) {
            Debt d = debt(l);
            if (d == null || d.settled) continue;
            String key = d.person.toLowerCase(Locale.ROOT);
            if (!names.containsKey(key)) names.put(key, d.person);
            String n = names.get(key);
            out.put(n, (out.containsKey(n) ? out.get(n) : 0) + d.amount);
        }
        return out;
    }

    // ------------------------------------------------------------------ split with friends

    public static final class Split {
        public final Map<String, Double> paid = new LinkedHashMap<>();
        public double total, share;
        /** {from, to, amount} */
        public final List<String[]> transfers = new ArrayList<>();
        public int people() { return paid.size(); }
    }

    private static final Pattern PAID = Pattern.compile(
            "(?i)^" + NAME + "\\s+(?:paid|spent|pays|gave)\\s+(?:for\\s+[^\\d₹]*?\\s*)?" + AMT + "(?:\\s+(?:for|on)\\b.*)?$");
    private static final Pattern PAID_FOR = Pattern.compile(
            "(?i)^" + NAME + "\\s+(?:paid|spent)\\s+(?:for|on)\\s+.+?\\s+" + AMT + "\\s*$");
    private static final Pattern PEOPLE = Pattern.compile(
            "(?i)^(?:split|divide|share|shared)\\s+(?:it\\s+|this\\s+|equally\\s+)?(?:between|among|amongst|with|by)\\s+(.+)$"
            + "|^(?:people|friends|members|with)\\s*[:\\-]\\s*(.+)$");

    /** "Rahul paid 1200 / Amit paid 800 / I paid 0" → who pays whom. Null when it isn't a split. */
    public static Split split(String text) {
        if (text == null) return null;
        Split s = new Split();
        Map<String, String> names = new LinkedHashMap<>();
        int paidLines = 0;
        for (String raw : text.split("\n")) {
            String b = bare(raw.trim());
            if (b.isEmpty()) continue;
            Matcher m = PAID.matcher(b);
            if (!m.find()) { m = PAID_FOR.matcher(b); if (!m.find()) m = null; }
            if (m != null && okNameOrMe(m.group(1))) {
                String n = canon(names, person(m.group(1)));
                double a = num(m.group(2));
                if (b.matches("(?is).*\\d\\s*k\\b.*")) a *= 1000;
                s.paid.put(n, (s.paid.containsKey(n) ? s.paid.get(n) : 0) + a);
                paidLines++;
                continue;
            }
            Matcher p = PEOPLE.matcher(b);
            if (p.find()) {
                String list = p.group(1) != null ? p.group(1) : p.group(2);
                for (String part : list.split("\\s*(?:,|&|\\band\\b|\\+|/)\\s*")) {
                    String w = part.trim().replaceAll("[.!]+$", "");
                    if (w.isEmpty() || w.contains(" ") && !w.matches("(?i)me|i")) {
                        if (w.split("\\s+").length > 2) continue;
                    }
                    if (!w.isEmpty() && okNameOrMe(w.split("\\s+")[0])) {
                        String n = canon(names, person(w));
                        if (!s.paid.containsKey(n)) s.paid.put(n, 0.0);
                    }
                }
            }
        }
        if (paidLines == 0 || s.paid.size() < 2) return null;
        for (double v : s.paid.values()) s.total += v;
        if (s.total <= 0) return null;
        s.share = s.total / s.paid.size();

        // who is short (pays) and who is over (gets), biggest first
        List<String> names2 = new ArrayList<>(s.paid.keySet());
        double[] bal = new double[names2.size()];
        for (int i = 0; i < bal.length; i++) bal[i] = Math.round((s.paid.get(names2.get(i)) - s.share) * 100) / 100.0;
        for (int guard = 0; guard < 100; guard++) {
            int lo = 0, hi = 0;
            for (int i = 1; i < bal.length; i++) { if (bal[i] < bal[lo]) lo = i; if (bal[i] > bal[hi]) hi = i; }
            if (bal[lo] > -0.01 || bal[hi] < 0.01) break;
            double x = Math.min(-bal[lo], bal[hi]);
            x = Math.round(x * 100) / 100.0;
            s.transfers.add(new String[]{names2.get(lo), names2.get(hi), Double.toString(x)});
            bal[lo] += x;
            bal[hi] -= x;
        }
        return s;
    }

    private static boolean okNameOrMe(String n) {
        return n != null && (n.equalsIgnoreCase("i") || n.equalsIgnoreCase("me") || okName(n));
    }

    private static String canon(Map<String, String> names, String n) {
        String k = n.toLowerCase(Locale.ROOT);
        if (!names.containsKey(k)) names.put(k, n);
        return names.get(k);
    }

    /** The lines to add to the note: "Settle up (₹2,500 ÷ 3 = ₹833.33 each)" + "Amit → Rahul ₹433.33". */
    public static String settleText(Split s) {
        StringBuilder sb = new StringBuilder("Settle up (" + ListTotals.money(s.total, true) + " ÷ " + s.people()
                + " = " + ListTotals.money(s.share, true) + " each)");
        if (s.transfers.isEmpty()) sb.append("\n☑ Everyone is even");
        for (String[] t : s.transfers) {
            String from = t[0].equals("Me") ? "I" : t[0];
            sb.append("\n☐ ").append(from).append(" → ").append(t[1]).append(" ")
                    .append(ListTotals.money(Double.parseDouble(t[2]), true));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ bills & subscriptions

    public static final class Bill {
        public String name;
        public double amount;     // as written
        public double perMonth;   // yearly / 12, weekly × 52 / 12
        public String every;      // "month", "year", "week"
    }

    private static final Pattern PER_MONTH = Pattern.compile(
            "(?i)(?:\\s*/\\s*(?:month|mon|mo|m)\\b|\\s+(?:per|a|each|every)\\s+month\\b|\\s+monthly\\b|\\s+p\\.?m\\.?(?=\\s|$|[,.;)])|\\s+mahina\\b|\\s+mahine\\b)");
    private static final Pattern PER_YEAR = Pattern.compile(
            "(?i)(?:\\s*/\\s*(?:year|yr|y|annum)\\b|\\s+(?:per|a|each|every)\\s+(?:year|annum)\\b|\\s+yearly\\b|\\s+annually\\b|\\s+p\\.?a\\.?(?=\\s|$|[,.;)]))");
    private static final Pattern PER_WEEK = Pattern.compile(
            "(?i)(?:\\s*/\\s*(?:week|wk|w)\\b|\\s+(?:per|a|each|every)\\s+week\\b|\\s+weekly\\b)");
    private static final Pattern MONEY_PM = Pattern.compile(
            "(?i)((?:₹|rs\\.?)\\s*\\d[\\d,]*(?:\\.\\d+)?|\\d[\\d,]{2,}(?:\\.\\d+)?)(\\s*(?:/-)?\\s*)p\\.?m\\.?(?=\\s|$|[,.;)])");

    /** A bill or subscription line ("Netflix ₹649/month", "rent 15000 pm", "LIC 12000 per year"), or null. */
    public static Bill bill(String line) {
        if (line == null) return null;
        String t = line.trim();
        if (t.startsWith("☑") && false) return null;
        String b = bare(t);
        String every = null;
        Matcher m;
        int cut = -1, cutEnd = -1;
        if ((m = MONEY_PM.matcher(b)).find()) { every = "month"; cut = m.end(1); cutEnd = m.end(); }
        else if ((m = PER_MONTH.matcher(b)).find() && !isClockPm(b, m)) { every = "month"; cut = m.start(); cutEnd = m.end(); }
        else if ((m = PER_YEAR.matcher(b)).find()) { every = "year"; cut = m.start(); cutEnd = m.end(); }
        else if ((m = PER_WEEK.matcher(b)).find()) { every = "week"; cut = m.start(); cutEnd = m.end(); }
        else if (b.matches("(?i).*\\b(?:every|each)\\s+month\\b.*|.*\\bmonthly\\b.*|.*\\bof every month\\b.*")) every = "month";
        else if (b.matches("(?i).*\\b(?:every|each)\\s+year\\b.*|.*\\byearly\\b.*|.*\\bannually\\b.*")) every = "year";
        if (every == null) return null;
        String rest = cut >= 0 ? b.substring(0, cut) + " " + b.substring(cutEnd) : b;
        Double a = amountIn(rest);
        if (a == null || a <= 0) return null;
        Bill bl = new Bill();
        bl.amount = a;
        bl.every = every;
        bl.perMonth = every.equals("year") ? a / 12 : every.equals("week") ? a * 52 / 12 : a;
        bl.name = nameOf(rest.replaceAll("(?i)\\b(?:on|due(?:\\s+on)?|by|before)\\s+(?:the\\s+)?\\d{1,2}(?:st|nd|rd|th)?\\b(?:\\s+of\\b)?(?:\\s+(?:every|each)\\s+month)?", " ")
                .replaceAll("(?i)\\b(?:every|each)\\s+(?:month|year)\\b|\\bmonthly\\b|\\byearly\\b|\\bannually\\b", " "));
        if (bl.name.isEmpty()) bl.name = "Bill";
        return bl;
    }

    /**
     * For the reminder finder: "Netflix ₹649/month on 5th" → {"Netflix ₹649 on 5th", "month"}.
     * Only the short money forms (/month, pm, per year…); "every month" is understood already. Null if none.
     */
    public static String[] repeatMarker(String line) {
        if (line == null) return null;
        Matcher m;
        if ((m = MONEY_PM.matcher(line)).find())
            return new String[]{line.substring(0, m.end(1)) + line.substring(m.end()), "month"};
        if ((m = PER_MONTH.matcher(line)).find() && !isClockPm(line, m) && amountIn(line.substring(0, m.start())) != null)
            return new String[]{line.substring(0, m.start()) + line.substring(m.end()), "month"};
        if ((m = PER_YEAR.matcher(line)).find() && amountIn(line.substring(0, m.start())) != null)
            return new String[]{line.substring(0, m.start()) + line.substring(m.end()), "year"};
        if ((m = PER_WEEK.matcher(line)).find() && amountIn(line.substring(0, m.start())) != null)
            return new String[]{line.substring(0, m.start()) + line.substring(m.end()), "week"};
        return null;
    }

    /** "5 pm" / "at 7 p.m." is a time, not "per month". */
    private static boolean isClockPm(String b, Matcher m) {
        String g = m.group();
        if (!g.trim().toLowerCase(Locale.ROOT).startsWith("p")) return false;   // "/month", "per month"…
        String before = b.substring(0, m.start());
        Matcher n = Pattern.compile("(?i)(₹|rs\\.?)?\\s*(\\d[\\d,]*(?:[.:]\\d+)?)\\s*$").matcher(before);
        if (!n.find()) return true;
        if (n.group(1) != null) return false;
        String v = n.group(2);
        if (v.contains(":")) return true;
        return num(v) <= 12;
    }

    private static final Pattern CUR_AMT = Pattern.compile("(?i)(?:₹|\\brs\\.?|\\binr)\\s*(\\d[\\d,]*(?:\\.\\d{1,2})?)(k\\b)?"
            + "|(\\d[\\d,]*(?:\\.\\d{1,2})?)(k)?\\s*(?:rs\\.?(?![a-z])|rupees?\\b|inr\\b|/-)");
    private static final Pattern ANY_NUM = Pattern.compile("(?i)(?<![\\d:/.])(\\d[\\d,]*(?:\\.\\d{1,2})?)(k\\b)?"
            + "(?!\\s*(?:st|nd|rd|th|am|pm|a\\.m|p\\.m|:|%|/\\d|-\\d|hours?|hrs?|mins?|minutes?|days?|weeks?|months?|years?|yrs?)\\b)(?![\\d:/])");

    /** The money amount in a line: one with ₹ / rs first, else the biggest plain number (not a day, time or year). */
    public static Double amountIn(String line) {
        if (line == null) return null;
        Matcher m = CUR_AMT.matcher(line);
        if (m.find()) {
            double v = m.group(1) != null ? num(m.group(1)) : num(m.group(3));
            if ((m.group(2) != null) || (m.group(4) != null)) v *= 1000;
            return v;
        }
        String low = line.toLowerCase(Locale.ROOT);
        Double best = null;
        m = ANY_NUM.matcher(line);
        while (m.find()) {
            String g = m.group(1);
            double v = num(g);
            if (m.group(2) != null) v *= 1000;
            boolean yearish = g.matches("(19|20)\\d\\d") && low.matches("(?s).*\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s*" + g + ".*");
            if (yearish) continue;
            // a day number next to a month name ("5 Dec") is a date
            String after = line.substring(m.end()).toLowerCase(Locale.ROOT);
            if (v <= 31 && after.matches("(?s)\\s*(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec).*")) continue;
            if (v < 10 && m.group(2) == null) continue;
            if (best == null || v > best) best = v;
        }
        return best;
    }

    // ------------------------------------------------------------------ item names (price memory)

    private static final Pattern UNIT_QTY = Pattern.compile(
            "(?i)\\b\\d+(?:\\.\\d+)?\\s*(?:kg|kgs|g|gm|gms|gram|grams|l|ltr|litre|litres|liter|liters|ml|pcs|pc|piece|pieces|dozen|doz|packet|packets|pkt|pkts|pack|bottle|bottles|box|boxes|nos|no)\\b");
    private static final Pattern PRICE_BITS = Pattern.compile(
            "(?i)(?:₹|\\brs\\.?|\\binr|\\$)\\s*\\d[\\d,]*(?:\\.\\d+)?|\\d[\\d,]*(?:\\.\\d+)?\\s*(?:rs\\.?(?![a-z])|rupees?|inr|/-)"
            + "|\\d+(?:\\.\\d+)?\\s*[x×*@]\\s*\\d[\\d,]*(?:\\.\\d+)?|=\\s*\\d[\\d,]*(?:\\.\\d+)?|\\b\\d[\\d,]*(?:\\.\\d+)?\\b");

    /** "☐ Milk 2 litre ₹60" → "milk". The key the price memory uses. Empty if it has no name. */
    public static String itemKey(String line) {
        String n = nameOf(line).toLowerCase(Locale.ROOT);
        return n.replaceAll("[^\\p{L}\\p{M}\\p{N} ]+", " ").replaceAll("\\s+", " ").trim();
    }

    /** "☐ Milk 2 litre ₹60" → "Milk": the line without its list mark, quantity and price. */
    public static String nameOf(String line) {
        if (line == null) return "";
        String b = bare(line.trim());
        b = UNIT_QTY.matcher(b).replaceAll(" ");
        b = PRICE_BITS.matcher(b).replaceAll(" ");
        b = b.replaceAll("(?i)\\b(?:rs|rupees?|inr)\\b|₹|/-", " ");
        b = b.replaceAll("[\\s:=\\-–—,.]+$", "").replaceAll("^[\\s:=\\-–—,.]+", "");
        b = b.replaceAll("\\s{2,}", " ").trim();
        if (!b.matches("(?s).*[\\p{L}].*")) return "";
        return b;
    }

    // ------------------------------------------------------------------ spending

    public static final class Spent {
        public String name;
        public double amount;
    }

    /**
     * What a note says was spent: the lines of its price lists. In a list with ☐ ☑ only the ticked
     * (bought) lines count. Lend / borrow, split and bill lines are not spending.
     */
    public static List<Spent> spent(String text) {
        List<Spent> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        String[] lines = text.split("\n", -1);
        boolean[] used = new boolean[lines.length];
        for (int i = lines.length - 1; i >= 0; i--) {
            if (used[i]) continue;
            ListTotals.Block b = ListTotals.blockEndingAt(lines, i);
            if (b == null) continue;
            List<Spent> part = new ArrayList<>();
            for (int k = b.firstLine; k <= b.lastLine; k++) {
                used[k] = true;
                String l = lines[k];
                String t = l.trim();
                if (b.hasChecks && !t.startsWith("☑")) continue;
                if (debt(l) != null || bill(l) != null || PAID.matcher(bare(t)).find()) continue;
                if (t.contains("→") || t.toLowerCase(Locale.ROOT).startsWith("settle up")) continue;   // split results
                ListTotals.Item it = ListTotals.price(l);
                if (it == null || it.price <= 0) continue;
                Spent s = new Spent();
                s.name = nameOf(l);
                s.amount = it.price;
                part.add(s);
            }
            out.addAll(0, part);   // lists are found bottom-up; keep the note's order
            i = b.firstLine;       // the loop's i-- moves above the list
        }
        return out;
    }

    // ------------------------------------------------------------------ scanned shop bill → price list

    private static final Pattern BILL_SKIP = Pattern.compile("(?i)\\b(?:sub\\s*-?\\s*total|total|grand|net\\s+(?:amt|amount|payable)|amount\\s+(?:payable|due|paid)|"
            + "payable|tax(?:able)?|gst|cgst|sgst|igst|utgst|vat|cess|discount|disc|round(?:ed|ing)?\\s*off|roundoff|r/off|"
            + "cash|change|balance|tender(?:ed)?|card|upi|paytm|gpay|phonepe|visa|mastercard|"
            + "invoice|bill\\s*no|bill\\s*#|receipt|txn|order\\s*(?:no|id)|token|kot|table\\s*no|date|time|cashier|"
            + "phone|ph|mob(?:ile)?|tel|gstin|fssai|cin|pan|hsn|sac|thank|visit|again|savings|you\\s+saved|"
            + "items?\\s*:|qty|quantity|mrp\\s*$|rate\\s*$|amount\\s*$|particulars|description)\\b");
    private static final Pattern BILL_NUM = Pattern.compile("(?<![\\d.])(\\d{1,3}(?:,\\d{2,3})+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)(?![\\d])");

    public static final class ScannedBill {
        public final List<String[]> items = new ArrayList<>();   // {name, amount}
        public double sum;          // our total of the items
        public Double printedTotal; // the bill's own total, when it could be read
    }

    /** Reads the rows of a scanned bill (one row per line, left to right) into items with prices. */
    public static ScannedBill readBill(String rows) {
        ScannedBill r = new ScannedBill();
        if (rows == null) return r;
        List<String> names = new ArrayList<>();
        List<Double> loose = new ArrayList<>();
        for (String raw : rows.split("\n")) {
            String row = raw.replace('|', ' ').replaceAll("\\s+", " ").trim();
            if (row.isEmpty()) continue;
            String low = row.toLowerCase(Locale.ROOT);
            if (low.matches(".*\\b(?:grand\\s+total|net\\s+(?:amt|amount|payable)|total(?:\\s+amount)?|amount\\s+payable)\\b.*")
                    && !low.matches(".*\\b(?:sub\\s*-?\\s*total|total\\s+(?:qty|items|quantity))\\b.*")) {
                Double v = lastNumber(row);
                if (v != null && (r.printedTotal == null || v > r.printedTotal)) r.printedTotal = v;
                continue;
            }
            if (BILL_SKIP.matcher(row).find()) continue;
            boolean letters = row.matches(".*\\p{L}{2,}.*");
            Double price = lastNumber(row);
            if (letters && price != null) {
                String name = row;
                // drop the numbers at the end (qty, rate, amount) and a leading serial number
                name = name.replaceAll("(?:\\s*(?:₹|rs\\.?|x|×|@|-)?\\s*\\d[\\d,]*(?:\\.\\d{1,2})?\\s*(?:/-)?)+\\s*$", "");
                name = name.replaceAll("^\\s*\\d{1,3}\\s*[.)]?\\s+", "").replaceAll("[\\s:.\\-–]+$", "").trim();
                if (name.matches(".*\\p{L}{2,}.*") && price > 0 && price < 1_000_000) {
                    r.items.add(new String[]{name, Double.toString(price)});
                    continue;
                }
            }
            if (letters && price == null) { names.add(row.replaceAll("^\\s*\\d{1,3}\\s*[.)]?\\s+", "")); continue; }
            if (!letters && price != null) loose.add(price);
        }
        // a column of names and a separate column of prices (the reader sometimes splits them)
        if (r.items.isEmpty() && !names.isEmpty() && names.size() == loose.size()) {
            for (int i = 0; i < names.size(); i++) r.items.add(new String[]{names.get(i), Double.toString(loose.get(i))});
        }
        for (String[] it : r.items) r.sum += Double.parseDouble(it[1]);
        return r;
    }

    private static Double lastNumber(String row) {
        Matcher m = BILL_NUM.matcher(row);
        String last = null;
        while (m.find()) last = m.group(1);
        if (last == null) return null;
        if (last.replace(",", "").replace(".", "").length() > 8) return null;   // phone / bill numbers
        return num(last);
    }

    /** The price list to put in the note. */
    public static String billText(ScannedBill b, String shop) {
        StringBuilder sb = new StringBuilder();
        if (shop != null && !shop.isEmpty()) sb.append(shop).append("\n");
        for (String[] it : b.items) {
            sb.append(it[0]).append(" ").append(ListTotals.money(Double.parseDouble(it[1]), true)).append("\n");
        }
        sb.append("Total = ").append(ListTotals.money(b.sum, true));
        if (b.printedTotal != null && Math.abs(b.printedTotal - b.sum) >= 1) {
            sb.append("\n(the bill says ").append(ListTotals.money(b.printedTotal, true)).append(" — tax or a line the scan missed)");
        }
        return sb.toString();
    }
}
