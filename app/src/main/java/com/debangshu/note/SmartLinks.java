package com.debangshu.note;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Phone numbers, web links, e-mails and [[note links]] inside a note (pure Java). */
public final class SmartLinks {

    private SmartLinks() {}

    private static final Pattern PHONE = Pattern.compile(
            "(?<![\\d+])(\\+\\d{1,3}[\\s-]?\\d{2,5}[\\s-]?\\d{3,5}[\\s-]?\\d{0,5}|(?:0|91)?[6-9]\\d{4}[\\s-]?\\d{5}|0\\d{2,4}[\\s-]\\d{6,8})(?![\\d])");
    private static final Pattern URL = Pattern.compile("\\b((?:https?://|www\\.)[^\\s<>\"]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL = Pattern.compile("\\b([\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+)\\b");
    private static final Pattern LINK = Pattern.compile("\\[\\[([^\\[\\]\\n]{1,80})\\]\\]");

    /** Phone numbers, digits only (a leading + kept). Prices and dates aren't caught: they need 10+ digits. */
    public static List<String> phones(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = PHONE.matcher(text);
        while (m.find()) {
            String raw = m.group(1);
            String digits = raw.replaceAll("[^\\d+]", "");
            int n = digits.replace("+", "").length();
            if (n < 10 || n > 13) continue;
            if (!out.contains(digits)) out.add(digits);
        }
        return out;
    }

    /** Number for WhatsApp links: country code first, no +. Indian 10-digit numbers get 91. */
    public static String whatsapp(String phone) {
        String d = phone.replace("+", "");
        if (d.startsWith("0") && d.length() == 11) d = d.substring(1);
        if (d.length() == 10) d = "91" + d;
        return d;
    }

    /** "98765 43210" style for showing. */
    public static String pretty(String phone) {
        String d = phone.replace("+", "");
        if (!phone.startsWith("+") && d.length() == 10) return d.substring(0, 5) + " " + d.substring(5);
        return phone;
    }

    public static List<String> urls(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = URL.matcher(text);
        while (m.find()) {
            String u = m.group(1).replaceAll("[.,;:!?)\\]]+$", "");
            if (!out.contains(u)) out.add(u);
        }
        return out;
    }

    public static List<String> emails(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = EMAIL.matcher(text);
        while (m.find()) if (!out.contains(m.group(1))) out.add(m.group(1));
        return out;
    }

    /** Titles written as [[Title]]. */
    public static List<String> links(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = LINK.matcher(text);
        while (m.find()) {
            String t = m.group(1).trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    public static List<int[]> linkRanges(CharSequence text) {
        List<int[]> out = new ArrayList<>();
        Matcher m = LINK.matcher(text);
        while (m.find()) out.add(new int[]{m.start(), m.end()});
        return out;
    }

    /** "call mom" / "phone dad" lines: the person to call, or null. */
    public static String callWho(String line) {
        if (line == null) return null;
        Matcher m = Pattern.compile("(?i)\\b(?:call|phone|ring|dial)\\s+([\\p{L}][\\p{L}'.]{1,20})").matcher(line);
        return m.find() ? m.group(1) : null;
    }
}
