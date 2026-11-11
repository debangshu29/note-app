package com.debangshu.note;

import java.util.Collection;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Location reminders in plain text (no Android here, so it can be tested on a computer):
 *   "when I reach office call Rahul", "buy milk when I get to the market", "at home: water the plants",
 *   "when I leave office call mom", "office pahunch ke mail bhejna", "ghar se nikalte waqt keys lena".
 * A place counts when you've saved it (Places) or it's a common one (home, office, college…).
 */
public final class GeoParse {

    private GeoParse() {}

    public static final class Hit {
        public String place;     // as saved / normalised ("home", "office")
        public boolean leaving;  // true = when you leave
        public String task;      // what to do
        public boolean known;    // the place is one you saved
    }

    public static final String[] COMMON = {"home", "office", "work", "college", "school", "gym", "market", "mall", "station", "hospital", "bank"};

    /** Hindi / Bengali words for the same places. */
    public static String normalise(String place) {
        String p = place.trim().toLowerCase(Locale.ROOT).replaceAll("^(?:the|my)\\s+", "");
        switch (p) {
            case "ghar": case "bari": case "baari": case "house": return "home";
            case "daftar": case "dafter": case "offis": return "office";
            case "bazaar": case "bazar": case "baazar": return "market";
            default: return p;
        }
    }

    private static final String REACH = "(?:reach|get to|get|arrive at|arrive in|am at|i'm at|im at|come to|go to|am near|get near)";
    private static final String LEAVE = "(?:leave|am leaving|i'm leaving|exit|go out of|head out of)";

    /** A location reminder in this line, or null. {@code saved} = names of saved places. */
    public static Hit parse(String line, Collection<String> saved) {
        if (line == null) return null;
        String t = line.trim();
        if (t.startsWith("☑")) return null;
        String l = t.replaceFirst("^\\s*(?:[☐•*\\-]\\s*|\\d{1,3}[.)]\\s+)", "").trim();
        if (l.isEmpty()) return null;
        String low = l.toLowerCase(Locale.ROOT);

        // every place we know, longest first, so "office canteen" wins over "office"
        java.util.List<String> names = new java.util.ArrayList<>();
        if (saved != null) for (String n : saved) if (n != null && !n.trim().isEmpty()) names.add(n.trim().toLowerCase(Locale.ROOT));
        for (String c : COMMON) names.add(c);
        for (String c : new String[]{"ghar", "bari", "baari", "house", "daftar", "bazaar", "bazar"}) names.add(c);
        java.util.Collections.sort(names, (x, y) -> y.length() - x.length());
        StringBuilder alt = new StringBuilder();
        for (String n : names) { if (alt.length() > 0) alt.append('|'); alt.append(Pattern.quote(n)); }
        String P = "(" + alt + ")(?![\\p{L}])";
        String[] patterns = {
                // when I reach office, call Rahul  /  when I get home call mom
                "^(?:remind me\\s+)?(?:when|once|as soon as|after)\\s+(?:i\\s+)?(" + REACH + "|" + LEAVE + ")\\s+(?:the\\s+|my\\s+)?" + P + "\\s*[,:\\-–]?\\s*(?:remind me to\\s+|then\\s+)?(.+)$",
                // call Rahul when I reach office
                "^(.+?)\\s+(?:when|once|as soon as)\\s+(?:i\\s+)?(" + REACH + "|" + LEAVE + ")\\s+(?:the\\s+|my\\s+)?" + P + "[.!]?$",
                // at office: call Rahul  /  at home remind me to water plants
                "^(?:at|near|in)\\s+(?:the\\s+|my\\s+)?" + P + "\\s*(?:[,:\\-–]\\s*|\\s+remind me to\\s+)(.+)$",
                // office pahunch ke mail bhejna / ghar pahunchte hi call
                "^" + P + "\\s+(?:pahunch\\w*|pohoch\\w*|pouch\\w*)\\s+(?:ke baad|ke|kar|kr|te hi|hi)?\\s*(.+)$",
                // ghar se nikalte waqt keys lena
                "^" + P + "\\s+(?:se|theke)\\s+(?:nikal\\w*|bero\\w*)\\s+(?:waqt|ke|samay|somoy|time)?\\s*(.+)$",
        };
        for (int k = 0; k < patterns.length; k++) {
            Matcher m = Pattern.compile(patterns[k], Pattern.CASE_INSENSITIVE).matcher(l);
            if (!m.find()) continue;
            String verb = "", place, task;
            switch (k) {
                case 0: verb = m.group(1); place = m.group(2); task = m.group(3); break;
                case 1: task = m.group(1); verb = m.group(2); place = m.group(3); break;
                case 2: place = m.group(1); task = m.group(2); break;
                case 3: place = m.group(1); task = m.group(2); break;
                default: place = m.group(1); task = m.group(2); verb = "leave"; break;
            }
            // "get home" / "reach home" carry the place in the verb

            Hit h = match(place, saved);
            if (h == null) continue;
            h.leaving = verb.toLowerCase(Locale.ROOT).matches("(?:" + LEAVE + ")");
            h.task = task.replaceAll("^(?:to\\s+|remind me to\\s+)", "").replaceAll("[.!]+$", "").trim();
            if (h.task.isEmpty()) continue;
            h.task = Character.toUpperCase(h.task.charAt(0)) + h.task.substring(1);
            return h;
        }
        return null;
    }

    /** The place in these words: a saved one (longest first), or a common one. */
    private static Hit match(String words, Collection<String> saved) {
        String w = normalise(words);
        if (saved != null) {
            String best = null;
            for (String s : saved) {
                String sn = s.toLowerCase(Locale.ROOT);
                if (w.equals(sn) || w.startsWith(sn + " ")) if (best == null || sn.length() > best.length()) best = sn;
            }
            if (best != null) { Hit h = new Hit(); h.place = best; h.known = true; return h; }
        }
        String first = w.split("\\s+")[0];
        for (String c : COMMON) {
            if (w.equals(c) || first.equals(c)) {
                Hit h = new Hit();
                h.place = c;
                h.known = saved != null && saved.contains(c);
                return h;
            }
        }
        return null;
    }
}
