package com.debangshu.note;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flashcards written in a note (plain Java, tested on a computer):
 *   "Capital of France :: Paris"            (any note)
 *   "Q: What is H2O?"  next line  "A: Water" (any note)
 *   "Photosynthesis — how plants make food"  (in notes labelled #flashcards, #study or #quiz,
 *   "mitochondria - powerhouse of the cell"   also "term - meaning" and "term: meaning")
 */
public final class Cards {

    private Cards() {}

    public static final class Card {
        public String q, a;
        public String key() { return Integer.toHexString(q.trim().toLowerCase(Locale.ROOT).hashCode()); }
    }

    private static final Pattern MARK = Pattern.compile("^\\s*(?:[-*•☐☑]\\s*|\\d{1,3}[.)]\\s+)");
    private static final Pattern DOUBLE_COLON = Pattern.compile("^(.+?)\\s*::\\s*(.+)$");
    private static final Pattern Q = Pattern.compile("(?i)^(?:q|question|ques)\\s*[:.)\\-]\\s*(.+)$");
    private static final Pattern A = Pattern.compile("(?i)^(?:a|ans|answer)\\s*[:.)\\-]\\s*(.+)$");
    private static final Pattern DASH = Pattern.compile("^(.{1,80}?)\\s+[—–-]\\s+(.+)$");
    private static final Pattern COLON = Pattern.compile("^([^:]{1,60}?):\\s+(.+)$");
    private static final Pattern STUDY_TAG = Pattern.compile("(?i)(?<![\\p{L}\\p{N}_])#(?:flashcards?|study|quiz|revision|revise|vocab)\\b");

    public static boolean isStudyNote(String text) {
        return text != null && STUDY_TAG.matcher(text).find();
    }

    public static List<Card> parse(String text) {
        List<Card> out = new ArrayList<>();
        if (text == null) return out;
        boolean loose = isStudyNote(text);
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String l = MARK.matcher(lines[i]).replaceFirst("").trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            Matcher m = DOUBLE_COLON.matcher(l);
            if (m.find()) { add(out, m.group(1), m.group(2)); continue; }
            m = Q.matcher(l);
            if (m.find() && i + 1 < lines.length) {
                Matcher a = A.matcher(MARK.matcher(lines[i + 1]).replaceFirst("").trim());
                if (a.find()) { add(out, m.group(1), a.group(1)); i++; continue; }
            }
            if (!loose) continue;
            if (l.matches("(?i)^(?:total|sum)\\b.*") || l.matches(".*https?://.*")) continue;
            m = DASH.matcher(l);
            if (m.find()) { add(out, m.group(1), m.group(2)); continue; }
            m = COLON.matcher(l);
            if (m.find()) add(out, m.group(1), m.group(2));
        }
        return out;
    }

    private static void add(List<Card> out, String q, String a) {
        q = q.trim();
        a = a.trim();
        if (q.isEmpty() || a.isEmpty() || !q.matches("(?s).*[\\p{L}\\p{N}].*") || !a.matches("(?s).*[\\p{L}\\p{N}].*")) return;
        for (Card c : out) if (c.q.equalsIgnoreCase(q)) return;
        Card c = new Card();
        c.q = q;
        c.a = a;
        out.add(c);
    }

    // ------------------------------------------------------------------ spaced repetition (boxes 0..5)

    /** Days to wait after a card reaches each box. */
    public static final int[] BOX_DAYS = {0, 1, 2, 4, 8, 16};

    public static int nextBox(int box, boolean knew) {
        return knew ? Math.min(BOX_DAYS.length - 1, box + 1) : 0;
    }

    public static long dueAfter(int box, long now) {
        return now + BOX_DAYS[Math.max(0, Math.min(BOX_DAYS.length - 1, box))] * 86_400_000L - 3_600_000L;
    }

    // ------------------------------------------------------------------ quiz

    /** Four choices (the right one and up to three others from the same deck), shuffled. */
    public static List<String> choices(List<Card> deck, Card right, Random rnd) {
        List<String> others = new ArrayList<>();
        for (Card c : deck) {
            if (c == right || c.a.equalsIgnoreCase(right.a)) continue;
            boolean dup = false;
            for (String o : others) if (o.equalsIgnoreCase(c.a)) dup = true;
            if (!dup) others.add(c.a);
        }
        Collections.shuffle(others, rnd);
        List<String> out = new ArrayList<>(others.subList(0, Math.min(3, others.size())));
        out.add(right.a);
        Collections.shuffle(out, rnd);
        return out;
    }
}
