package com.debangshu.note;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The word list for one language and the matching (plain Java, tested on a computer). See {@link L}. */
final class Words {

    private Words() {}

    static final Map<String, String> exact = new HashMap<>();
    /** Lines with {0} as they are written, for {@link L#f}. */
    static final Map<String, String> templates = new HashMap<>();
    static final List<Pat> pats = new ArrayList<>();
    /** Your own words (note titles, reminder and habit names): never translated, even if they look like app words. */
    static volatile java.util.Set<String> keep = new java.util.HashSet<>();

    static final class Pat {
        Pattern re;
        String to;
        int literal;   // how specific it is (longer fixed text wins)
    }

    static void addPattern(String k, String v) {
        StringBuilder re = new StringBuilder("^");
        Matcher m = PH.matcher(k);
        int last = 0, lit = 0;
        while (m.find()) {
            String fixed = k.substring(last, m.start());
            lit += fixed.length();
            // {#0} = a number only ("3 notes"), {0} = any text
            re.append(Pattern.quote(fixed)).append(m.group(1).isEmpty() ? "(.+?)" : "(-?\\d[\\d,.]*)");
            last = m.end();
        }
        String tail = k.substring(last);
        lit += tail.length();
        re.append(Pattern.quote(tail)).append("$");
        Pat p = new Pat();
        p.re = Pattern.compile(re.toString(), Pattern.DOTALL);
        p.to = v;
        p.literal = lit;
        // which {n} is which group
        pats.add(p);
        orderOf.put(p, groupOrder(k));
    }

    static final Map<Pat, int[]> orderOf = new HashMap<>();
    static final Pattern PH = Pattern.compile("\\{(#?)(\\d)\\}");

    static boolean isPattern(String k) { return PH.matcher(k).find(); }

    static int[] groupOrder(String k) {
        List<Integer> l = new ArrayList<>();
        Matcher m = PH.matcher(k);
        while (m.find()) l.add(Integer.parseInt(m.group(2)));
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    /** Recent answers (the same labels are shown again and again while scrolling). */
    private static final Map<String, String> cache = new java.util.LinkedHashMap<String, String>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > 3000; }
    };
    private static final String NONE = "\u0000";

    static void clearCache() { synchronized (cache) { cache.clear(); } }

    /** Translated text, or null when there is no translation. */
    static String find(String s) {
        synchronized (cache) {
            String c = cache.get(s);
            if (c != null) return c == NONE ? null : c;
        }
        String r = tr(s, 0);
        synchronized (cache) { cache.put(s, r == null ? NONE : r); }
        return r;
    }

    static final Pattern EDGE = Pattern.compile("^([^\\p{L}\\p{N}{\\[“\"‘'#₹]*)(.*?)([\\s.:…!?)\\]]*)$", Pattern.DOTALL);

    /** Null when nothing could be translated. */
    static String tr(String s, int depth) {
        if (depth > 4) return null;
        if (keep.contains(s.trim())) return null;
        String e = exact.get(s);
        if (e != null) return e;
        // leading emoji / spaces / brackets and trailing punctuation stay as they are
        Matcher m = EDGE.matcher(s);
        if (m.matches()) {
            String pre = m.group(1), core = m.group(2), post = m.group(3);
            if (!core.isEmpty() && keep.contains(core)) return null;
            if (!core.isEmpty() && (pre.length() > 0 || post.length() > 0)) {
                String c = exact.get(core);
                if (c != null) return pre + c + post;
                String withPunct = core + post.trim();          // "Delete this note?" kept whole
                c = exact.get(withPunct);
                if (c != null) return pre + c + post.substring(post.trim().isEmpty() ? 0 : post.indexOf(post.trim()) + post.trim().length());
                // the better (more specific) of: the core alone, or the core with its end punctuation
                Pat pc = best(core), pw = post.trim().isEmpty() ? null : best(withPunct);
                if (pw != null && (pc == null || pw.literal > pc.literal)) {
                    c = apply(pw, withPunct, depth);               // "🔔  Remind me to ask {0} (tomorrow 10 am)"
                    if (c != null) return pre + c + post.substring(post.indexOf(post.trim()) + post.trim().length());
                }
                if (pc != null) {
                    c = apply(pc, core, depth);
                    if (c != null) return pre + c + post;
                }
            }
        }
        String p = pattern(s, depth);
        if (p != null) return p;
        // several lines: each on its own
        if (s.indexOf('\n') >= 0) {
            String[] lines = s.split("\n", -1);
            boolean any = false;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                String x = lines[i].isEmpty() ? null : tr(lines[i], depth + 1);
                if (x != null) any = true;
                sb.append(x != null ? x : lines[i]);
                if (i < lines.length - 1) sb.append('\n');
            }
            return any ? sb.toString() : null;
        }
        // "A  ·  B" pieces
        for (String sep : new String[]{"  ·  ", " · ", "   ", " — "}) {
            if (!s.contains(sep)) continue;
            String[] parts = s.split(Pattern.quote(sep), -1);
            boolean any = false;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                String x = parts[i].trim().isEmpty() ? null : tr(parts[i], depth + 1);
                if (x != null) any = true;
                sb.append(x != null ? x : parts[i]);
                if (i < parts.length - 1) sb.append(sep);
            }
            if (any) return sb.toString();
        }
        return null;
    }

    /** The most specific line with {0} that fits the whole text (a {0} never spans " · " or a new line). */
    static Pat best(String s) {
        for (Pat p : pats) {
            Matcher m = p.re.matcher(s);
            if (m.matches() && clean(m)) return p;
        }
        return null;
    }

    private static boolean clean(Matcher m) {
        for (int g = 1; g <= m.groupCount(); g++) {
            String x = m.group(g);
            if (x.indexOf('\n') >= 0 || x.contains(" · ") || x.contains(" — ")) return false;
        }
        return true;
    }

    static String pattern(String s, int depth) {
        Pat p = best(s);
        return p == null ? null : apply(p, s, depth);
    }

    static String apply(Pat p, String s, int depth) {
        Matcher m = p.re.matcher(s);
        if (!m.matches()) return null;
        int[] order = orderOf.get(p);
        String out = p.to;
        for (int g = 1; g <= m.groupCount(); g++) {
            String part = m.group(g);
            String tp = tr(part, depth + 1);
            int idx = order != null && g - 1 < order.length ? order[g - 1] : g - 1;
            String val = tp != null ? tp : part;
            out = out.replace("{" + idx + "}", val).replace("{#" + idx + "}", val);
        }
        return out;
    }

    /** For tests on a computer: use these words. */
    static void use(Map<String, String> words) {
        clearCache();
        exact.clear();
        pats.clear();
        orderOf.clear();
        templates.clear();
        for (Map.Entry<String, String> e : words.entrySet()) {
            if (isPattern(e.getKey())) { addPattern(e.getKey(), e.getValue()); templates.put(e.getKey(), e.getValue()); }
            else exact.put(e.getKey(), e.getValue());
        }
        pats.sort((a, b2) -> b2.literal - a.literal);
    }

}
