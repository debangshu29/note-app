package com.debangshu.note;

import android.graphics.Typeface;
import android.text.Editable;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.AlignmentSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Word-style formatting on top of plain text. Our own span classes are used so that only real
 * formatting is saved (not the keyboard's temporary underlines). The plain text stays the same,
 * so auto math, reminders and price totals keep working on formatted text.
 */
public final class RichText {

    private RichText() {}

    // ------------------------------------------------------------------ our span types

    public static class Bold extends StyleSpan { public Bold() { super(Typeface.BOLD); } }
    public static class Italic extends StyleSpan { public Italic() { super(Typeface.ITALIC); } }
    public static class Under extends UnderlineSpan { }
    public static class Strike extends StrikethroughSpan { }
    public static class Size extends AbsoluteSizeSpan { public final int sp; public Size(int sp) { super(sp, true); this.sp = sp; } }
    /** Any font from {@link Fonts}; keeps bold / italic working on top of it. */
    public static class Font extends android.text.style.MetricAffectingSpan {
        public final String family;
        public Font(String f) { family = f; }
        @Override public void updateMeasureState(android.text.TextPaint p) { apply(p); }
        @Override public void updateDrawState(android.text.TextPaint p) { apply(p); }
        private void apply(android.text.TextPaint p) {
            Typeface old = p.getTypeface();
            int style = old == null ? Typeface.NORMAL : old.getStyle();
            Typeface tf = Typeface.create(Fonts.get(family), style);
            p.setTypeface(tf);
            int missing = style & ~tf.getStyle();          // font has no bold / italic: fake it
            if ((missing & Typeface.BOLD) != 0) p.setFakeBoldText(true);
            if ((missing & Typeface.ITALIC) != 0) p.setTextSkewX(-0.25f);
        }
    }
    public static class Ink extends ForegroundColorSpan { public Ink(int c) { super(c); } }
    public static class Mark extends BackgroundColorSpan { public Mark(int c) { super(c); } }
    public static class Align extends AlignmentSpan.Standard {
        public final String code;
        public Align(String code) { super(alignment(code)); this.code = code; }
        static Layout.Alignment alignment(String c) {
            if ("c".equals(c)) return Layout.Alignment.ALIGN_CENTER;
            if ("r".equals(c)) return Layout.Alignment.ALIGN_OPPOSITE;
            return Layout.Alignment.ALIGN_NORMAL;
        }
    }

    /** Find-in-note highlight: shown only on screen, never saved. */
    public static class FindMark extends BackgroundColorSpan { public FindMark(int c) { super(c); } }
    /** #label colour in the editor (never saved). */
    public static class TagMark extends android.text.style.ForegroundColorSpan { public TagMark(int c) { super(c); } }

    // ------------------------------------------------------------------ choices offered in the toolbar

    /** Word-style sizes. 17 is the normal size of note text. */
    public static final int[] SIZES = {8, 9, 10, 11, 12, 14, 16, 17, 18, 20, 22, 24, 26, 28, 32, 36, 40, 48, 60, 72};
    public static final int NORMAL_SIZE = 17;

    /** Text size at a position (from the Size spans), or the normal size. */
    public static int sizeAt(Spanned sp, int pos) {
        int p = Math.max(0, pos - 1);
        for (Object o : sp.getSpans(p, Math.min(sp.length(), p + 1), Size.class)) {
            int a = sp.getSpanStart(o), b = sp.getSpanEnd(o);
            if (a <= p && b > p) return ((Size) o).sp;
        }
        return NORMAL_SIZE;
    }

    /** Value of a colour span (Ink / Mark) at a position, or 0. */
    public static int colorAt(Spanned sp, int pos, Class<?> cls) {
        int p = Math.max(0, pos - 1);
        for (Object o : sp.getSpans(p, Math.min(sp.length(), p + 1), cls)) {
            int a = sp.getSpanStart(o), b = sp.getSpanEnd(o);
            if (a <= p && b > p) {
                if (o instanceof Ink) return ((Ink) o).getForegroundColor();
                if (o instanceof Mark) return ((Mark) o).getBackgroundColor();
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------ save / load

    private static final Class<?>[] SAVED = {Bold.class, Italic.class, Under.class, Strike.class,
            Size.class, Font.class, Ink.class, Mark.class, Align.class};

    public static JSONArray toJson(Spanned s) {
        JSONArray arr = new JSONArray();
        for (Class<?> c : SAVED) {
            for (Object sp : s.getSpans(0, s.length(), c)) {
                int a = s.getSpanStart(sp), b = s.getSpanEnd(sp);
                if (a < 0 || b < a || (a == b && !(sp instanceof Align))) continue;
                try {
                    JSONObject o = new JSONObject();
                    o.put("t", code(sp));
                    o.put("s", a);
                    o.put("e", b);
                    Object v = value(sp);
                    if (v != null) o.put("v", v);
                    arr.put(o);
                } catch (Exception ignored) { }
            }
        }
        return arr;
    }

    public static void apply(Spannable s, JSONArray spans) {
        if (spans == null) return;
        int len = s.length();
        for (int i = 0; i < spans.length(); i++) {
            JSONObject o = spans.optJSONObject(i);
            if (o == null) continue;
            int a = Math.max(0, Math.min(len, o.optInt("s")));
            int b = Math.max(a, Math.min(len, o.optInt("e")));
            Object sp = make(o.optString("t"), o.opt("v"));
            if (sp == null || (a == b && !(sp instanceof Align))) continue;
            s.setSpan(sp, a, b, flagsFor(sp));
        }
    }

    public static SpannableStringBuilder build(String text, JSONArray spans) {
        SpannableStringBuilder sb = new SpannableStringBuilder(text == null ? "" : text);
        apply(sb, spans);
        return sb;
    }

    private static String code(Object sp) {
        if (sp instanceof Bold) return "b";
        if (sp instanceof Italic) return "i";
        if (sp instanceof Under) return "u";
        if (sp instanceof Strike) return "x";
        if (sp instanceof Size) return "z";
        if (sp instanceof Font) return "f";
        if (sp instanceof Ink) return "c";
        if (sp instanceof Mark) return "h";
        if (sp instanceof Align) return "a";
        return "";
    }

    private static Object value(Object sp) {
        if (sp instanceof Size) return ((Size) sp).sp;
        if (sp instanceof Font) return ((Font) sp).family;
        if (sp instanceof Ink) return ((Ink) sp).getForegroundColor();
        if (sp instanceof Mark) return ((Mark) sp).getBackgroundColor();
        if (sp instanceof Align) return ((Align) sp).code;
        return null;
    }

    static Object make(String t, Object v) {
        try {
            switch (t) {
                case "b": return new Bold();
                case "i": return new Italic();
                case "u": return new Under();
                case "x": return new Strike();
                case "z": return new Size(((Number) v).intValue());
                case "f": return new Font(String.valueOf(v));
                case "c": return new Ink(((Number) v).intValue());
                case "h": return new Mark(((Number) v).intValue());
                case "a": return new Align(String.valueOf(v));
            }
        } catch (Exception ignored) { }
        return null;
    }

    static Object copyOf(Object sp) {
        return make(code(sp), value(sp));
    }

    private static int flagsFor(Object sp) {
        return sp instanceof Align ? Spanned.SPAN_INCLUSIVE_INCLUSIVE : Spanned.SPAN_EXCLUSIVE_INCLUSIVE;
    }

    // ------------------------------------------------------------------ editing helpers

    /** Removes spans of a type from [s, e), keeping the parts outside the range. */
    public static void removeRange(Editable ed, int s, int e, Class<?> cls) {
        for (Object sp : ed.getSpans(s, e, cls)) {
            int a = ed.getSpanStart(sp), b = ed.getSpanEnd(sp);
            if (b <= s || a >= e) { if (!(a == b && a == s)) continue; }
            ed.removeSpan(sp);
            if (a < s) ed.setSpan(copyOf(sp), a, s, flagsFor(sp));
            if (b > e) ed.setSpan(copyOf(sp), e, b, flagsFor(sp));
        }
    }

    /** True if every character in [s, e) has the style. For an empty range, the character before it. */
    public static boolean has(Spanned sp, int s, int e, Class<?> cls) {
        if (s == e) {
            if (s == 0) return false;
            s = s - 1;
            e = s + 1;
        }
        int pos = s;
        while (pos < e) {
            int next = -1;
            for (Object o : sp.getSpans(pos, pos + 1, cls)) {
                int a = sp.getSpanStart(o), b = sp.getSpanEnd(o);
                if (a <= pos && b > pos) next = Math.max(next, b);
            }
            if (next < 0) return false;
            pos = next;
        }
        return true;
    }

    /** Bold / italic / underline / strikethrough on a selection. */
    public static void toggle(Editable ed, int s, int e, Class<?> cls) {
        if (s >= e) return;
        boolean on = has(ed, s, e, cls);
        removeRange(ed, s, e, cls);
        if (!on) {
            Object sp = make(codeFor(cls), null);
            if (sp != null) ed.setSpan(sp, s, e, Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
        }
    }

    /** Size / font / colour / highlight on a selection; null value = back to normal. */
    public static void setValue(Editable ed, int s, int e, Class<?> cls, Object value) {
        if (s >= e) return;
        removeRange(ed, s, e, cls);
        if (value != null) {
            Object sp = make(codeFor(cls), value);
            if (sp != null) ed.setSpan(sp, s, e, Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
        }
    }

    /** Alignment for every paragraph touched by [s, e): "l", "c" or "r". */
    public static void setAlign(Editable ed, int s, int e, String code) {
        String t = ed.toString();
        int ps = s == 0 ? 0 : t.lastIndexOf('\n', s - 1) + 1;
        int pe = t.indexOf('\n', e);
        if (pe < 0) pe = t.length();
        for (Object sp : ed.getSpans(ps, pe, Align.class)) {
            int a = ed.getSpanStart(sp), b = ed.getSpanEnd(sp);
            ed.removeSpan(sp);
            if (a < ps) ed.setSpan(copyOf(sp), a, Math.max(a, ps - 1), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            if (b > pe) ed.setSpan(copyOf(sp), Math.min(b, pe + 1), b, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        }
        if (!"l".equals(code)) ed.setSpan(new Align(code), ps, pe, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
    }

    public static String alignAt(Spanned sp, int pos) {
        for (Object o : sp.getSpans(pos, pos, Align.class)) return ((Align) o).code;
        return "l";
    }

    /** Removes all formatting from [s, e). */
    public static void clear(Editable ed, int s, int e) {
        for (Class<?> c : SAVED) if (c != Align.class) removeRange(ed, s, e, c);
    }

    static String codeFor(Class<?> cls) {
        if (cls == Bold.class) return "b";
        if (cls == Italic.class) return "i";
        if (cls == Under.class) return "u";
        if (cls == Strike.class) return "x";
        if (cls == Size.class) return "z";
        if (cls == Font.class) return "f";
        if (cls == Ink.class) return "c";
        if (cls == Mark.class) return "h";
        if (cls == Align.class) return "a";
        return "";
    }

    /** Copy for drawing on a PDF / image: sizes given in sp are converted with {@code spToPx}. */
    public static SpannableStringBuilder forDrawing(CharSequence src, float spToPx) {
        SpannableStringBuilder sb = new SpannableStringBuilder(src);
        for (Size z : sb.getSpans(0, sb.length(), Size.class)) {
            int a = sb.getSpanStart(z), b = sb.getSpanEnd(z);
            sb.removeSpan(z);
            sb.setSpan(new AbsoluteSizeSpan(Math.round(z.sp * spToPx), false), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        for (FindMark f : sb.getSpans(0, sb.length(), FindMark.class)) sb.removeSpan(f);
        return sb;
    }

    /** Splits formatting at {@code at}: returns spans for the second part (shifted to start at 0). */
    public static JSONArray tail(Spanned s, int at) {
        SpannableStringBuilder sb = new SpannableStringBuilder(s, at, s.length());
        return toJson(sb);
    }

    public static JSONArray head(Spanned s, int at) {
        SpannableStringBuilder sb = new SpannableStringBuilder(s, 0, at);
        return toJson(sb);
    }
}
