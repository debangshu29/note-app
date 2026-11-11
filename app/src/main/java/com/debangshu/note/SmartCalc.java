package com.debangshu.note;

import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everyday maths written in words, answered as a grey suggestion (like 1+1):
 *   "18% gst on 1200"          → ₹1,416 (GST ₹216)
 *   "10% off 2500"             → ₹2,250 (you save ₹250)
 *   "18% of 2500"              → 450
 *   "split 2400 among 3"       → ₹800 each
 *   "emi 5 lakh 9% 3 years"    → ₹15,900/month · total ₹5,72,393 · interest ₹72,393
 *   "5 km in miles"            → 3.107 miles
 *   "45 days from today"       → Sat, 26 Dec 2026
 *   "days until 25 dec"        → 43 days (Fri, 25 Dec 2026)
 * Pure Java (no Android), so it can be tested on its own.
 */
public final class SmartCalc {

    private SmartCalc() {}

    private static final String N = "(\\d+(?:\\.\\d+)?)";
    private static final String P = "(\\d+(?:\\.\\d+)?)\\s*%";

    /** Answer for the last line of {@code textBeforeCursor} (without a trailing "="), or null. */
    public static String answerFor(String textBeforeCursor) {
        if (textBeforeCursor == null) return null;
        int nl = textBeforeCursor.lastIndexOf('\n');
        String line = textBeforeCursor.substring(nl + 1).trim();
        while (line.endsWith("=") || line.endsWith("?")) line = line.substring(0, line.length() - 1).trim();
        line = line.replaceFirst("^(?:[-*•☐☑]\\s*|\\d{1,3}[.)]\\s+)", "");
        if (line.length() < 5 || line.length() > 90) return null;
        try { return eval(line, Calendar.getInstance()); } catch (RuntimeException e) { return null; }
    }

    /** True when the line is one of these calculations (so it isn't mistaken for a reminder). */
    public static boolean isCalc(String line) {
        return answerFor(line) != null;
    }

    static String eval(String raw, Calendar now) {
        boolean rupee = raw.matches("(?i).*(₹|\\brs\\.?(?![a-z])|\\brupees?\\b|\\binr\\b|\\blakh|\\bcrore|\\bemi\\b|\\bgst\\b|\\bsalary\\b|\\bloan\\b).*");
        String s = numbers(raw.toLowerCase(Locale.ROOT));
        s = s.replaceAll("^(?:what(?:'s| is)|how much is|calculate|calc|find)\\s+", "").trim();
        String r;
        if ((r = tax(s, rupee)) != null) return r;
        if ((r = discount(s, rupee)) != null) return r;
        if ((r = tip(s, rupee)) != null) return r;
        if ((r = percent(s, rupee)) != null) return r;
        if ((r = split(s, rupee)) != null) return r;
        if ((r = emi(s)) != null) return r;
        if ((r = interest(s)) != null) return r;
        if ((r = units(s)) != null) return r;
        if ((r = dates(s, now)) != null) return r;
        return null;
    }

    // ------------------------------------------------------------------ numbers

    /** "5 lakh" → 500000, "2.5 cr" → 25000000, "10k" → 10000, "1,25,000" → 125000, "₹" and "rs" removed. */
    static String numbers(String s) {
        s = s.replaceAll("(\\d),(?=\\d)", "$1");
        s = s.replaceAll("₹\\s*", "").replaceAll("\\b(?:rs\\.?|inr|rupees?)\\s*(?=\\d)", "")
                .replaceAll("(\\d)\\s*(?:rs\\.?|rupees?|/-)(?![a-z])", "$1");
        Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(lakhs?|lacs?|l\\b|crores?|cr\\b|k\\b|thousand)").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            double v = Double.parseDouble(m.group(1));
            String u = m.group(2);
            double mul = u.startsWith("cr") ? 1e7 : u.startsWith("k") || u.startsWith("thousand") ? 1e3 : 1e5;
            // "5 l" could be litres: only treat "l" as lakh next to money words
            if (u.equals("l") && !s.matches(".*\\b(emi|loan|salary|price|cost|lakh)\\b.*")) { m.appendReplacement(sb, m.group(0)); continue; }
            m.appendReplacement(sb, plain(v * mul));
        }
        m.appendTail(sb);
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    private static String plain(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }

    private static double d(String s) { return Double.parseDouble(s); }

    static String money(double v, boolean rupee) {
        return ListTotals.money(Math.round(v * 100) / 100.0, rupee);
    }

    static String num(double v) {
        double a = Math.abs(v);
        String f = a >= 1000 ? String.format(Locale.ROOT, "%.1f", v)
                : a >= 100 ? String.format(Locale.ROOT, "%.2f", v)
                : a >= 1 ? String.format(Locale.ROOT, "%.3f", v)
                : String.format(Locale.ROOT, "%.4f", v);
        if (f.contains(".")) f = f.replaceAll("0+$", "").replaceAll("\\.$", "");
        if (a >= 1000) {
            long whole = (long) Math.floor(a);
            String dec = f.contains(".") ? f.substring(f.indexOf('.')) : "";
            f = (v < 0 ? "-" : "") + String.format(Locale.ROOT, "%,d", whole) + dec;
        }
        return f;
    }

    // ------------------------------------------------------------------ money

    private static final Pattern TAX_ON = Pattern.compile("^(?:add\\s+)?" + P + "\\s*(gst|tax|vat)\\s*(?:on|for|to|of)\\s*" + N + "$");
    private static final Pattern TAX_ON2 = Pattern.compile("^(gst|tax|vat)\\s*(?:of\\s*)?" + P + "\\s*(?:on|for|to|of)\\s*" + N + "$");
    private static final Pattern TAX_PLUS = Pattern.compile("^" + N + "\\s*(?:\\+|plus|with)\\s*" + P + "\\s*(gst|tax|vat)$");
    private static final Pattern TAX_INCL = Pattern.compile("^" + N + "\\s*(?:incl(?:uding|usive)?\\.?|with)\\s*" + P + "\\s*(gst|tax|vat)(?:\\s*included)?\\s*(?:before tax|without (?:gst|tax))?$");
    private static final Pattern TAX_REMOVE = Pattern.compile("^(?:remove|minus|without|exclude)\\s*" + P + "\\s*(gst|tax|vat)\\s*(?:from|on)\\s*" + N + "$");

    static String tax(String s, boolean rupee) {
        Matcher m;
        double pct, amt;
        String name;
        if ((m = TAX_ON.matcher(s)).find()) { pct = d(m.group(1)); name = m.group(2); amt = d(m.group(3)); }
        else if ((m = TAX_ON2.matcher(s)).find()) { name = m.group(1); pct = d(m.group(2)); amt = d(m.group(3)); }
        else if ((m = TAX_PLUS.matcher(s)).find()) { amt = d(m.group(1)); pct = d(m.group(2)); name = m.group(3); }
        else if ((m = TAX_INCL.matcher(s)).find() || (m = TAX_REMOVE.matcher(s)).find()) {
            boolean remove = s.matches("^(remove|minus|without|exclude).*");
            amt = d(remove ? m.group(3) : m.group(1));
            pct = d(m.group(remove ? 1 : 2));
            name = m.group(remove ? 2 : 3).toUpperCase(Locale.ROOT);
            double base = amt / (1 + pct / 100);
            return money(base, true) + " before " + name + " (" + name + " " + money(amt - base, true) + ")";
        } else return null;
        String n = name.toUpperCase(Locale.ROOT);
        if (n.equals("TAX")) n = "tax";
        double t = amt * pct / 100;
        return money(amt + t, true) + " (" + n + " " + money(t, true) + ")";
    }

    private static final Pattern OFF = Pattern.compile("^" + P + "\\s*(?:off|discount)\\s*(?:on|from)?\\s*" + N + "$");
    private static final Pattern OFF2 = Pattern.compile("^(?:discount|off)\\s*(?:of\\s*)?" + P + "\\s*(?:on|from)\\s*" + N + "$");
    private static final Pattern OFF3 = Pattern.compile("^" + N + "\\s*(?:-|minus|less|with)\\s*" + P + "\\s*(?:off|discount)?$");

    static String discount(String s, boolean rupee) {
        Matcher m;
        double pct, amt;
        if ((m = OFF.matcher(s)).find() || (m = OFF2.matcher(s)).find()) { pct = d(m.group(1)); amt = d(m.group(2)); }
        else if ((m = OFF3.matcher(s)).find()) { amt = d(m.group(1)); pct = d(m.group(2)); }
        else return null;
        double save = amt * pct / 100;
        return money(amt - save, rupee || true) + " (you save " + money(save, true) + ")";
    }

    private static final Pattern TIP = Pattern.compile("^" + P + "\\s*tip\\s*(?:on|for)?\\s*" + N + "$");
    private static final Pattern TIP2 = Pattern.compile("^tip\\s*(?:of\\s*)?" + P + "\\s*(?:on|for)\\s*" + N + "$");

    static String tip(String s, boolean rupee) {
        Matcher m;
        if (!(m = TIP.matcher(s)).find() && !(m = TIP2.matcher(s)).find()) return null;
        double pct = d(m.group(1)), amt = d(m.group(2)), t = amt * pct / 100;
        return "tip " + money(t, true) + " · total " + money(amt + t, true);
    }

    private static final Pattern PCT_OF = Pattern.compile("^" + P + "\\s*of\\s*" + N + "$");
    private static final Pattern WHAT_PCT = Pattern.compile("^" + N + "\\s*(?:is\\s*what\\s*(?:%|percent(?:age)?)\\s*of|out of|/)\\s*" + N + "\\s*(?:in\\s*%|as\\s*%|percent(?:age)?)?$");
    private static final Pattern CHANGE = Pattern.compile("^(?:%|percent(?:age)?)\\s*(?:change|increase|decrease|growth)\\s*(?:from)?\\s*" + N + "\\s*to\\s*" + N + "$");

    static String percent(String s, boolean rupee) {
        Matcher m;
        if ((m = PCT_OF.matcher(s)).find()) {
            double v = d(m.group(1)) * d(m.group(2)) / 100;
            return rupee ? money(v, true) : MathEval.format(Math.round(v * 10000) / 10000.0);
        }
        if ((m = WHAT_PCT.matcher(s)).find() && (s.contains("out of") || s.contains("what"))) {
            double b = d(m.group(2));
            if (b == 0) return null;
            return MathEval.format(Math.round(d(m.group(1)) / b * 10000) / 100.0) + "%";
        }
        if ((m = CHANGE.matcher(s)).find()) {
            double a = d(m.group(1)), b = d(m.group(2));
            if (a == 0) return null;
            double c = (b - a) / a * 100;
            return (c >= 0 ? "+" : "") + MathEval.format(Math.round(c * 100) / 100.0) + "%";
        }
        return null;
    }

    private static final Pattern SPLIT = Pattern.compile("^split\\s*" + N + "\\s*(?:among|between|by|in|into|with|for|amongst)\\s*(\\d{1,3})\\s*(?:people|persons|friends|ways|of us)?$");
    private static final Pattern SPLIT2 = Pattern.compile("^" + N + "\\s*(?:split|shared|divided)\\s*(?:among|between|by|in|into)?\\s*(\\d{1,3})\\s*(?:people|persons|friends|ways|of us)?$");
    private static final Pattern SPLIT3 = Pattern.compile("^" + N + "\\s*(?:/|÷|divided by|for)\\s*(\\d{1,3})\\s*(?:people|persons|friends|of us)$");

    static String split(String s, boolean rupee) {
        Matcher m;
        if (!(m = SPLIT.matcher(s)).find() && !(m = SPLIT2.matcher(s)).find() && !(m = SPLIT3.matcher(s)).find()) return null;
        int k = Integer.parseInt(m.group(2));
        if (k < 2) return null;
        return money(d(m.group(1)) / k, true) + " each";
    }

    private static final Pattern EMI = Pattern.compile("^(?:emi|loan emi|monthly emi)\\s*(?:for|on|of)?\\s*" + N + "\\s*(?:loan)?\\s*(?:at|@|for)?\\s*" + P
            + "\\s*(?:interest|p\\.?a\\.?|per year|a year)?\\s*(?:for|over|,)?\\s*(\\d+(?:\\.\\d+)?)\\s*(years?|yrs?|y|months?|mo|m)$");
    private static final Pattern EMI2 = Pattern.compile("^" + N + "\\s*(?:loan)?\\s*(?:at|@)?\\s*" + P + "\\s*(?:for)?\\s*(\\d+(?:\\.\\d+)?)\\s*(years?|yrs?|months?)\\s*emi$");

    static String emi(String s) {
        Matcher m;
        if (!(m = EMI.matcher(s)).find() && !(m = EMI2.matcher(s)).find()) return null;
        double p = d(m.group(1)), rate = d(m.group(2)), t = d(m.group(3));
        int months = (int) Math.round(m.group(4).startsWith("y") ? t * 12 : t);
        if (months <= 0 || p <= 0) return null;
        double r = rate / 12 / 100;
        double emi = r == 0 ? p / months : p * r * Math.pow(1 + r, months) / (Math.pow(1 + r, months) - 1);
        double total = emi * months;
        return money(Math.round(emi), true) + "/month · total " + money(Math.round(total), true)
                + " · interest " + money(Math.round(total - p), true);
    }

    private static final Pattern INTEREST = Pattern.compile("^(simple|compound)?\\s*interest\\s*(?:on|for)?\\s*" + N + "\\s*(?:at|@)\\s*" + P
            + "\\s*(?:p\\.?a\\.?|per year)?\\s*(?:for)?\\s*(\\d+(?:\\.\\d+)?)\\s*(years?|yrs?|months?)$");

    static String interest(String s) {
        Matcher m = INTEREST.matcher(s);
        if (!m.find()) return null;
        boolean compound = "compound".equals(m.group(1));
        double p = d(m.group(2)), rate = d(m.group(3)) / 100, t = d(m.group(4));
        double years = m.group(5).startsWith("m") ? t / 12 : t;
        double amount = compound ? p * Math.pow(1 + rate, years) : p * (1 + rate * years);
        return "interest " + money(amount - p, true) + " · total " + money(amount, true);
    }

    // ------------------------------------------------------------------ units

    /** unit name → {kind, factor to the base unit}. Temperatures are handled separately. */
    private static final Map<String, double[]> UNITS = new LinkedHashMap<>();
    private static final String[] KIND_NAMES = {"length", "weight", "volume", "area", "time", "data", "speed"};

    private static void u(int kind, double factor, String... names) {
        for (String n : names) UNITS.put(n, new double[]{kind, factor});
    }

    static {
        u(0, 1, "m", "metre", "metres", "meter", "meters");
        u(0, 1000, "km", "kms", "kilometre", "kilometres", "kilometer", "kilometers");
        u(0, 0.01, "cm", "centimetre", "centimetres", "centimeter", "centimeters");
        u(0, 0.001, "mm", "millimetre", "millimetres", "millimeter", "millimeters");
        u(0, 1609.344, "mile", "miles", "mi");
        u(0, 0.3048, "ft", "foot", "feet");
        u(0, 0.0254, "inch", "inches", "in");
        u(0, 0.9144, "yd", "yard", "yards");
        u(1, 1, "kg", "kgs", "kilo", "kilos", "kilogram", "kilograms");
        u(1, 0.001, "g", "gm", "gms", "gram", "grams");
        u(1, 0.000001, "mg", "milligram", "milligrams");
        u(1, 0.45359237, "lb", "lbs", "pound", "pounds");
        u(1, 0.028349523, "oz", "ounce", "ounces");
        u(1, 1000, "tonne", "tonnes", "ton", "tons");
        u(1, 100, "quintal", "quintals");
        u(2, 1, "l", "litre", "litres", "liter", "liters", "ltr");
        u(2, 0.001, "ml", "millilitre", "millilitres", "milliliter", "milliliters");
        u(2, 3.785411784, "gallon", "gallons", "gal");
        u(2, 0.24, "cup", "cups");
        u(2, 0.005, "tsp", "teaspoon", "teaspoons");
        u(2, 0.015, "tbsp", "tablespoon", "tablespoons");
        u(3, 1, "sq m", "sqm", "square metre", "square metres", "square meter", "square meters", "m2");
        u(3, 0.09290304, "sq ft", "sqft", "square foot", "square feet", "ft2");
        u(3, 4046.8564224, "acre", "acres");
        u(3, 10000, "hectare", "hectares", "ha");
        u(3, 14400 * 0.09290304, "bigha", "bighas");          // West Bengal bigha (14,400 sq ft)
        u(3, 720 * 0.09290304, "katha", "kathas", "cottah");  // West Bengal katha (720 sq ft)
        u(4, 1, "s", "sec", "secs", "second", "seconds");
        u(4, 60, "min", "mins", "minute", "minutes");
        u(4, 3600, "h", "hr", "hrs", "hour", "hours");
        u(4, 86400, "day", "days");
        u(4, 604800, "week", "weeks");
        u(5, 1, "kb", "kilobyte", "kilobytes");
        u(5, 1024, "mb", "megabyte", "megabytes");
        u(5, 1048576, "gb", "gigabyte", "gigabytes");
        u(5, 1073741824, "tb", "terabyte", "terabytes");
        u(6, 1, "km/h", "kmph", "kph", "kmh", "km per hour");
        u(6, 1.609344, "mph", "miles per hour");
        u(6, 3.6, "m/s", "mps", "metres per second", "meters per second");
    }

    private static final String TEMP = "(°?c|celsius|centigrade|°?f|fahrenheit|kelvin|k)";

    static String units(String s) {
        Matcher m = Pattern.compile("^" + N + "\\s*(.+?)\\s+(?:in|to|into|=|as)\\s+(.+)$").matcher(s);
        if (!m.find()) return null;
        double v = d(m.group(1));
        String a = m.group(2).trim().replace("degrees ", "").replace("degree ", ""), b = m.group(3).trim().replace("degrees ", "").replace("degree ", "");
        if (a.matches(TEMP) && b.matches(TEMP)) {
            double c = temp(a, v, true);
            double out = temp(b, c, false);
            return num(out) + " " + tempName(b);
        }
        double[] ua = UNITS.get(a), ub = UNITS.get(b);
        if (ua == null || ub == null || ua[0] != ub[0]) return null;
        double out = v * ua[1] / ub[1];
        return num(out) + " " + b;
    }

    /** toCelsius=true: value in unit → °C; false: °C → unit. */
    private static double temp(String unit, double v, boolean toCelsius) {
        char k = unit.replace("°", "").charAt(0);
        if (k == 'c') return v;
        if (k == 'f' || unit.startsWith("fahr")) return toCelsius ? (v - 32) * 5 / 9 : v * 9 / 5 + 32;
        return toCelsius ? v - 273.15 : v + 273.15;
    }

    private static String tempName(String unit) {
        char k = unit.replace("°", "").charAt(0);
        return k == 'c' ? "°C" : k == 'f' ? "°F" : "K";
    }

    // ------------------------------------------------------------------ dates

    private static final String[] MONTHS = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};
    private static final String DATE = "(\\d{1,2}(?:st|nd|rd|th)?\\s+[a-z]{3,9}(?:\\s+\\d{4})?|[a-z]{3,9}\\s+\\d{1,2}(?:st|nd|rd|th)?(?:,?\\s+\\d{4})?|\\d{1,2}[/.-]\\d{1,2}(?:[/.-]\\d{2,4})?|today|tomorrow|yesterday)";

    /** A date written as "25 dec", "dec 25 2026", "25/12/2026", "today". Year: the given one, else the coming one if {@code future}. */
    static Calendar date(String t, Calendar now, boolean future) {
        t = t.trim();
        Calendar c = (Calendar) now.clone();
        c.set(Calendar.HOUR_OF_DAY, 12);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (t.equals("today")) return c;
        if (t.equals("tomorrow")) { c.add(Calendar.DAY_OF_MONTH, 1); return c; }
        if (t.equals("yesterday")) { c.add(Calendar.DAY_OF_MONTH, -1); return c; }
        int day, month, year = -1;
        Matcher m;
        if ((m = Pattern.compile("^(\\d{1,2})(?:st|nd|rd|th)?\\s+([a-z]{3,9})(?:\\s+(\\d{4}))?$").matcher(t)).find()) {
            day = Integer.parseInt(m.group(1)); month = month(m.group(2)); if (m.group(3) != null) year = Integer.parseInt(m.group(3));
        } else if ((m = Pattern.compile("^([a-z]{3,9})\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(\\d{4}))?$").matcher(t)).find()) {
            month = month(m.group(1)); day = Integer.parseInt(m.group(2)); if (m.group(3) != null) year = Integer.parseInt(m.group(3));
        } else if ((m = Pattern.compile("^(\\d{1,2})[/.-](\\d{1,2})(?:[/.-](\\d{2,4}))?$").matcher(t)).find()) {
            day = Integer.parseInt(m.group(1)); month = Integer.parseInt(m.group(2)) - 1;
            if (m.group(3) != null) { year = Integer.parseInt(m.group(3)); if (year < 100) year += 2000; }
        } else return null;
        if (month < 0 || month > 11 || day < 1 || day > 31) return null;
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.MONTH, month);
        if (year > 0) c.set(Calendar.YEAR, year);
        c.set(Calendar.DAY_OF_MONTH, Math.min(day, c.getActualMaximum(Calendar.DAY_OF_MONTH)));
        if (year < 0 && future) {
            Calendar today = (Calendar) now.clone();
            today.set(Calendar.HOUR_OF_DAY, 0);
            if (c.before(today)) c.add(Calendar.YEAR, 1);
        }
        return c;
    }

    private static int month(String w) {
        if (w.length() < 3) return -1;
        String k = w.substring(0, 3);
        for (int i = 0; i < 12; i++) if (MONTHS[i].equals(k)) return i;
        return -1;
    }

    static String fmt(Calendar c) {
        return String.format(Locale.ENGLISH, "%ta, %<te %<tb %<tY", c);
    }

    private static long dayDiff(Calendar a, Calendar b) {
        Calendar x = (Calendar) a.clone(), y = (Calendar) b.clone();
        for (Calendar z : new Calendar[]{x, y}) {
            z.set(Calendar.HOUR_OF_DAY, 12); z.set(Calendar.MINUTE, 0); z.set(Calendar.SECOND, 0); z.set(Calendar.MILLISECOND, 0);
        }
        return Math.round((y.getTimeInMillis() - x.getTimeInMillis()) / 86_400_000.0);
    }

    private static final Pattern FROM = Pattern.compile("^(\\d{1,4})\\s*(days?|weeks?|months?|years?)\\s*(?:from|after)\\s*" + DATE + "$");
    private static final Pattern PLUS = Pattern.compile("^" + DATE + "\\s*(\\+|-|plus|minus)\\s*(\\d{1,4})\\s*(days?|weeks?|months?|years?)$");
    private static final Pattern AGO = Pattern.compile("^(\\d{1,4})\\s*(days?|weeks?|months?|years?)\\s*(?:ago|before\\s*" + DATE + ")$");
    private static final Pattern UNTIL = Pattern.compile("^(?:how many\\s*)?days?\\s*(?:left\\s*)?(?:until|till|to|for|before)\\s*" + DATE + "$");
    private static final Pattern SINCE = Pattern.compile("^(?:how many\\s*)?days?\\s*since\\s*" + DATE + "$");
    private static final Pattern BETWEEN = Pattern.compile("^(?:how many\\s*)?days?\\s*(?:between|from)\\s*" + DATE + "\\s*(?:and|to|till|until)\\s*" + DATE + "$");
    private static final Pattern AGE = Pattern.compile("^age\\s*(?:of|from|since|if born(?: on)?|born(?: on)?)?\\s*" + DATE + "$");
    private static final Pattern WEEKDAY = Pattern.compile("^(?:what\\s*)?day\\s*(?:is|was|on|of)?\\s*" + DATE + "$");

    private static void add(Calendar c, String unit, int n) {
        if (unit.startsWith("day")) c.add(Calendar.DAY_OF_MONTH, n);
        else if (unit.startsWith("week")) c.add(Calendar.DAY_OF_MONTH, 7 * n);
        else if (unit.startsWith("month")) c.add(Calendar.MONTH, n);
        else c.add(Calendar.YEAR, n);
    }

    static String dates(String s, Calendar now) {
        Matcher m;
        if ((m = FROM.matcher(s)).find()) {
            Calendar c = date(m.group(3), now, false);
            if (c == null) return null;
            add(c, m.group(2), Integer.parseInt(m.group(1)));
            return fmt(c);
        }
        if ((m = PLUS.matcher(s)).find()) {
            Calendar c = date(m.group(1), now, false);
            if (c == null) return null;
            int n = Integer.parseInt(m.group(3));
            add(c, m.group(4), m.group(2).equals("-") || m.group(2).equals("minus") ? -n : n);
            return fmt(c);
        }
        if ((m = AGO.matcher(s)).find()) {
            Calendar c = m.group(3) != null ? date(m.group(3), now, false) : (Calendar) now.clone();
            if (c == null) return null;
            add(c, m.group(2), -Integer.parseInt(m.group(1)));
            return fmt(c);
        }
        if ((m = UNTIL.matcher(s)).find()) {
            Calendar c = date(m.group(1), now, true);
            if (c == null) return null;
            long n = dayDiff(now, c);
            return (n == 1 ? "1 day" : n + " days") + " (" + fmt(c) + ")";
        }
        if ((m = SINCE.matcher(s)).find()) {
            Calendar c = date(m.group(1), now, false);
            if (c == null) return null;
            if (c.after(now) && !m.group(1).matches(".*\\d{4}.*")) c.add(Calendar.YEAR, -1);
            long n = dayDiff(c, now);
            return n + (n == 1 ? " day" : " days");
        }
        if ((m = BETWEEN.matcher(s)).find()) {
            Calendar a = date(m.group(1), now, false), b = date(m.group(2), now, false);
            if (a == null || b == null) return null;
            long n = Math.abs(dayDiff(a, b));
            return n + (n == 1 ? " day" : " days");
        }
        if ((m = AGE.matcher(s)).find()) {
            Calendar b = date(m.group(1), now, false);
            if (b == null || !m.group(1).matches(".*\\d{4}.*") || b.after(now)) return null;
            int years = now.get(Calendar.YEAR) - b.get(Calendar.YEAR);
            int months = now.get(Calendar.MONTH) - b.get(Calendar.MONTH);
            if (now.get(Calendar.DAY_OF_MONTH) < b.get(Calendar.DAY_OF_MONTH)) months--;
            if (months < 0) { years--; months += 12; }
            return years + (years == 1 ? " year" : " years") + (months > 0 ? ", " + months + (months == 1 ? " month" : " months") : "");
        }
        if ((m = WEEKDAY.matcher(s)).find()) {
            Calendar c = date(m.group(1), now, true);
            if (c == null) return null;
            return String.format(Locale.ENGLISH, "%tA", c);
        }
        return null;
    }
}
