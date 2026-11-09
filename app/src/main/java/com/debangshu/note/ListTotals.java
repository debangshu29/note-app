package com.debangshu.note;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds up price lists written in a note, e.g.
 *
 *   apple 50 rs
 *   banana is 20 rs
 *   milk 2 litre ₹60
 *   bread 35
 *   apple 3 x 40          (= 120)
 *
 * A "list" is a run of lines without a blank line in between. A line like
 * "Total = ..." closes the list above it and is kept up to date automatically.
 * With checkboxes (☐ / ☑) the total is split into bought (☑) and left (☐).
 *
 * No Android imports here, so it can be tested on a normal JVM.
 */
public final class ListTotals {

    private ListTotals() {}

    public static final class Item {
        public final double price;
        public final boolean strong;   // had ₹ / rs / rupees / "/-"
        Item(double price, boolean strong) { this.price = price; this.strong = strong; }
    }

    public static final class Block {
        public int firstLine, lastLine;   // item lines (inclusive)
        public int count;                 // lines with a price
        public double total, bought, left;
        public boolean hasChecks, currency;
    }

    private static final String CUR_WORD = "(?:rs\\.?|rupees?|inr|₹|/-|\\$)";
    private static final Pattern CURRENCY = Pattern.compile("(?i)(₹|\\brs\\.?(?![a-z])|\\brupees?\\b|\\binr\\b|/-|\\$)");
    private static final Pattern NUM = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");
    private static final Pattern NUM_THEN_CUR = Pattern.compile(
            "(?i)(\\d[\\d,]*(?:\\.\\d+)?)\\s*(?:rs\\.?(?![a-z])|rupees?\\b|inr\\b|/-)");
    private static final Pattern CUR_THEN_NUM = Pattern.compile(
            "(?i)(?:₹|\\brs\\.?|\\binr|\\$)\\s*(\\d[\\d,]*(?:\\.\\d+)?)");
    private static final Pattern ENDS_WITH_NUM = Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)\\s*$");
    private static final Pattern AFTER_EQUALS = Pattern.compile(
            "=\\s*(?:₹|rs\\.?)?\\s*(-?\\d[\\d,]*(?:\\.\\d+)?)\\s*" + CUR_WORD + "?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOTAL_LINE = Pattern.compile(
            "^(\\s*(?:[-*•☐☑]\\s*)?)(grand total|total|sum|subtotal)(\\s*[:=]\\s*)(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIST_MARK = Pattern.compile("^\\s*(?:[-*•☐☑]\\s*|\\d{1,3}[.)]\\s+)");

    // ------------------------------------------------------------------ one line

    public static boolean isTotalLine(String line) {
        return line != null && TOTAL_LINE.matcher(line).matches();
    }

    /** The price on a line, or null if it has none. */
    public static Item price(String line) {
        if (line == null) return null;
        String l = line.trim();
        if (l.isEmpty() || isTotalLine(l)) return null;
        l = LIST_MARK.matcher(l).replaceFirst("");
        boolean strong = CURRENCY.matcher(l).find();

        // a date or time on the line ("pay rent 01/12", "call at 5") is not a price, unless rs/₹ is written
        // ("10.50" is a price, not 10:50, so dotted numbers are left out of the date check)
        if (!strong && DateParser.parse(l.replaceAll("(\\d)\\.(\\d)", "$1_$2")) != null) return null;

        // "apple 3 x 40 = 120"  -> 120
        Matcher m = AFTER_EQUALS.matcher(l);
        if (m.find()) {
            Double v = num(m.group(1));
            if (v != null) return new Item(v, strong);
        }

        // "apple 3 x 40", "apple 50 rs x 2", "tea 2*15" -> work it out
        String plain = CURRENCY.matcher(l).replaceAll(" ").replaceAll("\\s+", " ").trim();
        String calc = MathEval.answerFor(plain);
        if (calc != null) {
            try { return new Item(Double.parseDouble(calc), strong); } catch (NumberFormatException ignored) { }
        }

        // "milk 2 litre 60 rs", "₹60 milk", "rs 60"
        if (strong) {
            m = NUM_THEN_CUR.matcher(l);
            if (m.find()) { Double v = num(m.group(1)); if (v != null) return new Item(v, true); }
            m = CUR_THEN_NUM.matcher(l);
            if (m.find()) { Double v = num(m.group(1)); if (v != null) return new Item(v, true); }
        }

        // "bread 35"  (a word, then a number at the end)
        m = ENDS_WITH_NUM.matcher(plain);
        if (m.find() && m.start() > 0 && plain.substring(0, m.start()).matches(".*[\\p{L}].*")) {
            Double v = num(m.group(1));
            if (v != null) return new Item(v, strong);
        }
        return null;
    }

    private static Double num(String s) {
        try { return Double.parseDouble(s.replace(",", "")); } catch (Exception e) { return null; }
    }

    // ------------------------------------------------------------------ blocks

    /**
     * The list that ends at line {@code idx} (going upwards until a blank line, a total line or the top).
     * Returns null when it isn't a price list (fewer than 2 prices, or mostly ordinary text).
     */
    public static Block blockEndingAt(String[] lines, int idx) {
        if (idx < 0 || idx >= lines.length) return null;
        Block b = new Block();
        b.lastLine = idx;
        int nonEmpty = 0;
        int i = idx;
        for (; i >= 0; i--) {
            String raw = lines[i];
            if (raw.trim().isEmpty() || isTotalLine(raw)) break;
            nonEmpty++;
            Item it = price(raw);
            if (it == null) continue;
            b.count++;
            b.total += it.price;
            if (it.strong) b.currency = true;
            String t = raw.trim();
            if (t.startsWith("☑")) { b.hasChecks = true; b.bought += it.price; }
            else if (t.startsWith("☐")) { b.hasChecks = true; b.left += it.price; }
            else b.left += it.price;
        }
        b.firstLine = i + 1;
        if (b.count < 2) return null;
        // ordinary paragraphs with a few numbers are not lists
        if (!b.currency && b.count * 10 < nonEmpty * 6) return null;
        return b;
    }

    /** "₹165" or "₹165 · bought ₹105 · left ₹60" */
    public static String summary(Block b) {
        String s = money(b.total, b.currency);
        if (b.hasChecks && b.bought > 0) {
            s += " · bought " + money(b.bought, b.currency) + " · left " + money(b.left, b.currency);
        }
        return s;
    }

    /** Indian-style grouping: 125000 -> 1,25,000 ; 12.5 -> 12.50 */
    public static String money(double v, boolean rupee) {
        long paise = Math.round(Math.abs(v) * 100);
        long whole = paise / 100;
        int frac = (int) (paise % 100);
        String intPart = Long.toString(whole);
        StringBuilder sb = new StringBuilder();
        int len = intPart.length();
        for (int i = 0; i < len; i++) {
            sb.append(intPart.charAt(i));
            int rem = len - 1 - i;
            if (rem == 3 || (rem > 3 && (rem - 3) % 2 == 0)) sb.append(',');
        }
        if (frac != 0) sb.append(String.format(Locale.ROOT, ".%02d", frac));
        return (v < 0 && paise != 0 ? "-" : "") + (rupee ? "₹" : "") + sb;
    }

    // ------------------------------------------------------------------ total lines

    /** A text change to keep a "Total = ..." line correct. */
    public static final class Edit {
        public final int start, end;
        public final String text;
        Edit(int start, int end, String text) { this.start = start; this.end = end; this.text = text; }
    }

    /**
     * Finds every "Total = ..." line and returns the changes needed so each one shows the
     * current total of the list right above it. Edits are in reverse order (safe to apply one by one).
     */
    public static List<Edit> totalLineEdits(String text) {
        return totalLineEdits(text, -2, -1);
    }

    /**
     * @param fillLine  line index where an EMPTY "total =" may be filled in (the one just typed);
     *                  -2 means every empty total line may be filled
     * @param skipLine  line the cursor is on: never rewrite it while the user is editing it
     */
    public static List<Edit> totalLineEdits(String text, int fillLine, int skipLine) {
        List<Edit> edits = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        int offset = 0;
        int[] starts = new int[lines.length];
        for (int i = 0; i < lines.length; i++) { starts[i] = offset; offset += lines[i].length() + 1; }
        for (int i = lines.length - 1; i >= 0; i--) {
            if (i == skipLine) continue;
            Matcher m = TOTAL_LINE.matcher(lines[i]);
            if (!m.matches()) continue;
            if (m.group(4).trim().isEmpty() && fillLine != -2 && fillLine != i) continue;
            Block b = blockEndingAt(lines, i - 1);
            if (b == null) continue;
            String want = summary(b);
            String have = m.group(4);
            if (have.trim().equals(want)) continue;
            // only rewrite values we wrote (numbers / ₹ / our words); keep anything else the user typed
            String bare = have.trim().replaceAll("(?i)\\b(bought|left|rs\\.?)\\b", "");
            if (!bare.matches("[₹\\d,.\\s·/-]*")) continue;
            int s = starts[i] + m.start(4);
            String sep = have.isEmpty() && !m.group(3).endsWith(" ") ? " " : "";
            edits.add(new Edit(s, starts[i] + lines[i].length(), sep + want));
        }
        return edits;
    }

    /** True if the line is "total =" / "total:" with nothing after it yet. */
    public static boolean isEmptyTotalLine(String line) {
        Matcher m = TOTAL_LINE.matcher(line);
        return m.matches() && m.group(4).trim().isEmpty();
    }
}
