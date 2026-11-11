package com.debangshu.note;

import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Smart features for one text box of a note:
 * auto math, grey suggestions (= answer, Total = …), lists, checkboxes, self-updating totals,
 * "formatting while typing" (press B, then type) and undo / redo.
 */
public class TextBlockController {

    public interface Host {
        void onTextEdited(TextBlockController c);
        void onCursorMoved(TextBlockController c);
    }

    public final SmartEditText et;
    private final Store store;
    private final Host host;
    private boolean selfEdit = false;

    private static final int G_MATH = 0, G_TOTAL_BELOW = 1, G_TOTAL_HERE = 2;
    private int ghostKind = G_MATH;

    private static final Pattern LIST_PREFIX = Pattern.compile("^(\\s*)(- |• |\\* |☐ |☑ |(\\d{1,3})[.)] )");

    /** Formatting chosen with nothing selected: applied to the next typed letters. Value TRUE = add, FALSE = remove. */
    private final Map<Class<?>, Object> pending = new HashMap<>();
    private int pendingAt = -1;
    private boolean changing = false;   // inside a text change (the cursor moves then, that's fine)

    // undo / redo
    private final ArrayDeque<SpannableStringBuilder> undo = new ArrayDeque<>();
    private final ArrayDeque<SpannableStringBuilder> redo = new ArrayDeque<>();
    private SpannableStringBuilder lastSnapshot;
    private long lastSnapshotAt = 0;

    public TextBlockController(SmartEditText et, Store store, Host host) {
        this.et = et;
        this.store = store;
        this.host = host;
        et.addTextChangedListener(watcher);
        et.setOnCursorMoved(() -> {
            if (!changing && et.getSelectionStart() != pendingAt) pending.clear();
            updateSuggestion();
            host.onCursorMoved(this);
        });
        et.setOnGhostAccepted(this::acceptGhost);
        et.setOnTouchListener(checkboxTapper);
        lastSnapshot = new SpannableStringBuilder(et.getText());
    }

    /** Programmatic change (loading, replacing): no smart reactions. */
    public void quiet(Runnable r) {
        boolean old = selfEdit;
        selfEdit = true;
        try { r.run(); } finally { selfEdit = old; }
    }

    // ------------------------------------------------------------------ typing

    private final TextWatcher watcher = new TextWatcher() {
        int start, before, count;

        @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            changing = true;
            if (selfEdit) return;
            // remember a snapshot for undo (grouped: one step per pause in typing)
            long now = System.currentTimeMillis();
            if (now - lastSnapshotAt > 1200 || lastSnapshot == null) {
                undo.push(new SpannableStringBuilder(s));
                while (undo.size() > 60) undo.removeLast();
                redo.clear();
            }
            lastSnapshotAt = now;
        }

        @Override public void onTextChanged(CharSequence s, int st, int bf, int ct) {
            start = st; before = bf; count = ct;
        }

        @Override public void afterTextChanged(Editable e) {
            try { afterChange(e); } finally { changing = false; }
        }

        private void afterChange(Editable e) {
            if (selfEdit) return;
            if (count > before && count > 0) {
                int end = start + count;
                applyPending(e, start + before, end);
                if (end <= e.length()) {
                    char last = e.charAt(end - 1);
                    boolean totals = store.setting(Store.S_LIST_TOTALS);
                    if ((last == '=' || last == ':') && totals && fillTotalLine(e, end)) { /* filled */ }
                    else if (last == '=' && store.setting(Store.S_AUTO_MATH)) autoAnswer(e, end);
                    else if (last == '\n' && store.setting(Store.S_SMART_LISTS)) continueList(e, end);
                    else if (last == ' ' && store.setting(Store.S_SMART_LISTS)) checkboxShortcut(e, end);
                }
            }
            if (store.setting(Store.S_LIST_TOTALS)) {
                int cur = et.getSelectionStart();
                applyTotalEdits(e, -1, cur < 0 ? -1 : lineIndex(e, cur));
            }
            lastSnapshot = null;
            host.onTextEdited(TextBlockController.this);
            updateSuggestion();
        }
    };

    private void applyPending(Editable e, int s, int end) {
        if (pending.isEmpty() || s >= end) return;
        for (Map.Entry<Class<?>, Object> en : pending.entrySet()) {
            Class<?> cls = en.getKey();
            Object v = en.getValue();
            RichText.removeRange(e, s, end, cls);
            if (Boolean.TRUE.equals(v)) {
                Object sp = RichText.make(RichText.codeFor(cls), null);
                if (sp != null) e.setSpan(sp, s, end, Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
            } else if (!(v instanceof Boolean) && v != null) {
                Object sp = RichText.make(RichText.codeFor(cls), v);
                if (sp != null) e.setSpan(sp, s, end, Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
            }
        }
        pendingAt = end;
    }

    // ------------------------------------------------------------------ formatting (called by the toolbar)

    /** B / I / U / S. With a selection: applies to it; without: applies to what you type next. */
    public void toggle(Class<?> cls) {
        Editable e = et.getText();
        int s = et.getSelectionStart(), en = et.getSelectionEnd();
        if (s < 0) return;
        if (s != en) {
            snapshot();
            RichText.toggle(e, Math.min(s, en), Math.max(s, en), cls);
            host.onTextEdited(this);
        } else {
            boolean on = isOn(cls);
            pending.put(cls, !on);
            pendingAt = s;
        }
    }

    /** Size / font / colour / highlight; null = normal. */
    public void setValue(Class<?> cls, Object value) {
        Editable e = et.getText();
        int s = et.getSelectionStart(), en = et.getSelectionEnd();
        if (s < 0) return;
        if (s != en) {
            snapshot();
            RichText.setValue(e, Math.min(s, en), Math.max(s, en), cls, value);
            host.onTextEdited(this);
        } else {
            pending.put(cls, value == null ? Boolean.FALSE : value);
            pendingAt = s;
        }
    }

    public void align(String code) {
        int s = Math.max(0, et.getSelectionStart()), en = Math.max(0, et.getSelectionEnd());
        snapshot();
        RichText.setAlign(et.getText(), Math.min(s, en), Math.max(s, en), code);
        et.invalidate();
        host.onTextEdited(this);
    }

    public void clearFormatting() {
        int s = et.getSelectionStart(), en = et.getSelectionEnd();
        if (s < 0) return;
        snapshot();
        if (s == en) {
            // nothing selected: clear the whole paragraph
            String t = et.getText().toString();
            s = s == 0 ? 0 : t.lastIndexOf('\n', s - 1) + 1;
            int pe = t.indexOf('\n', en);
            en = pe < 0 ? t.length() : pe;
        }
        RichText.clear(et.getText(), Math.min(s, en), Math.max(s, en));
        RichText.setAlign(et.getText(), Math.min(s, en), Math.max(s, en), "l");
        pending.clear();
        host.onTextEdited(this);
    }

    /** Is this style on at the cursor / for the whole selection (also counting "pending")? */
    public boolean isOn(Class<?> cls) {
        int s = et.getSelectionStart(), en = et.getSelectionEnd();
        if (s < 0) return false;
        if (s == en && pending.containsKey(cls) && pendingAt == s) return Boolean.TRUE.equals(pending.get(cls));
        return RichText.has(et.getText(), Math.min(s, en), Math.max(s, en), cls);
    }

    public int sizeNow() {
        int s = et.getSelectionStart();
        if (s < 0) return RichText.NORMAL_SIZE;
        if (pending.containsKey(RichText.Size.class) && pendingAt == s) {
            Object v = pending.get(RichText.Size.class);
            return v instanceof Integer ? (Integer) v : RichText.NORMAL_SIZE;
        }
        return RichText.sizeAt(et.getText(), s == et.getSelectionEnd() ? s : s + 1);
    }

    public String fontNow() {
        int s = et.getSelectionStart();
        if (s < 0) return "sans-serif";
        if (pending.containsKey(RichText.Font.class) && pendingAt == s) {
            Object v = pending.get(RichText.Font.class);
            return v instanceof String ? (String) v : "sans-serif";
        }
        int p = Math.max(0, (s == et.getSelectionEnd() ? s : s + 1) - 1);
        for (RichText.Font f : et.getText().getSpans(p, Math.min(et.length(), p + 1), RichText.Font.class)) return f.family;
        return "sans-serif";
    }

    public int colorNow(Class<?> cls) {
        int s = et.getSelectionStart();
        if (s < 0) return 0;
        return RichText.colorAt(et.getText(), s == et.getSelectionEnd() ? s : s + 1, cls);
    }

    public String alignment() {
        return RichText.alignAt(et.getText(), Math.max(0, et.getSelectionStart()));
    }

    // ------------------------------------------------------------------ undo / redo

    private void snapshot() {
        undo.push(new SpannableStringBuilder(et.getText()));
        while (undo.size() > 60) undo.removeLast();
        redo.clear();
        lastSnapshotAt = 0;
    }

    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }

    public void undo() {
        if (undo.isEmpty()) return;
        redo.push(new SpannableStringBuilder(et.getText()));
        restore(undo.pop());
    }

    public void redo() {
        if (redo.isEmpty()) return;
        undo.push(new SpannableStringBuilder(et.getText()));
        restore(redo.pop());
    }

    private void restore(SpannableStringBuilder s) {
        int cur = et.getSelectionStart();
        quiet(() -> et.setText(s));
        et.setSelection(Math.max(0, Math.min(cur, et.length())));
        lastSnapshotAt = 0;
        host.onTextEdited(this);
    }

    // ------------------------------------------------------------------ smart features

    private static int lineIndex(CharSequence s, int pos) {
        int n = 0;
        for (int i = 0; i < Math.min(pos, s.length()); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    private static int lastNewline(CharSequence s, int from) {
        for (int i = Math.min(from, s.length() - 1); i >= 0; i--) if (s.charAt(i) == '\n') return i;
        return -1;
    }

    private boolean fillTotalLine(Editable e, int end) {
        int ls = lastNewline(e, end - 1) + 1;
        int le = end;
        while (le < e.length() && e.charAt(le) != '\n') le++;
        if (!ListTotals.isEmptyTotalLine(e.subSequence(ls, le).toString())) return false;
        int li = lineIndex(e, end - 1);
        if (!applyTotalEdits(e, li, -1)) return false;
        int newEnd = ls;
        while (newEnd < e.length() && e.charAt(newEnd) != '\n') newEnd++;
        et.setSelection(newEnd);
        return true;
    }

    private boolean applyTotalEdits(Editable e, int fillLine, int skipLine) {
        List<ListTotals.Edit> edits = ListTotals.totalLineEdits(e.toString(), fillLine, skipLine);
        if (edits.isEmpty()) return false;
        quiet(() -> { for (ListTotals.Edit ed : edits) e.replace(ed.start, ed.end, ed.text); });
        return true;
    }

    private void autoAnswer(Editable e, int end) {
        if (end < e.length() && e.charAt(end) != '\n') return;
        int eqPos = end - 1;
        String answer = MathEval.answerFor(e.subSequence(0, eqPos).toString());
        if (answer == null) answer = SmartCalc.answerFor(e.subSequence(0, eqPos).toString());   // "18% gst on 1200 ="
        if (answer == null) return;
        String ins = (eqPos > 0 && e.charAt(eqPos - 1) == ' ') ? " " + answer : answer;
        quiet(() -> e.insert(end, ins));
        et.setSelection(Math.min(end + ins.length(), et.length()));
    }

    private void continueList(Editable e, int end) {
        int nl = end - 1;
        int lineStart = lastNewline(e, nl - 1) + 1;
        if (lineStart > nl) return;
        String prev = e.subSequence(lineStart, nl).toString();
        Matcher m = LIST_PREFIX.matcher(prev);
        if (!m.find()) return;
        String indent = m.group(1);
        String prefix = m.group(2);
        quiet(() -> {
            if (prev.trim().equals(prefix.trim())) {
                e.delete(lineStart, end);
            } else {
                String next;
                if (m.group(3) != null) next = (Integer.parseInt(m.group(3)) + 1) + prefix.substring(m.group(3).length());
                else if (prefix.equals("☑ ")) next = "☐ ";
                else next = prefix;
                e.insert(end, indent + next);
            }
        });
    }

    private void checkboxShortcut(Editable e, int end) {
        int lineStart = lastNewline(e, end - 1) + 1;
        String seg = e.subSequence(lineStart, end).toString();
        String t = seg.trim();
        String box = null;
        if (t.equals("[]") || t.equals("[ ]")) box = "☐ ";
        else if (t.equalsIgnoreCase("[x]")) box = "☑ ";
        if (box == null) return;
        int indent = seg.length() - seg.replaceAll("^\\s+", "").length();
        final String b = box;
        quiet(() -> e.replace(lineStart + indent, end, b));
    }

    /** Checklist / bullet button: adds or removes the marker at the start of the line. */
    public void toggleLinePrefix(String prefix) {
        et.requestFocus();
        Editable e = et.getText();
        int cur = Math.max(0, et.getSelectionStart());
        int ls = lastNewline(e, cur - 1) + 1;
        String line = e.subSequence(ls, cur).toString();
        snapshot();
        quiet(() -> {
            if (line.startsWith(prefix)) {
                e.delete(ls, ls + prefix.length());
            } else {
                Matcher m = LIST_PREFIX.matcher(line);
                if (m.find() && m.group(1).isEmpty()) e.replace(ls, ls + m.group(2).length(), prefix);
                else e.insert(ls, prefix);
            }
        });
        host.onTextEdited(this);
    }

    public void insertAtCursor(String text) {
        et.requestFocus();
        int cur = Math.max(0, et.getSelectionStart());
        et.getText().insert(cur, text);
    }

    private final View.OnTouchListener checkboxTapper = new View.OnTouchListener() {
        float downX, downY;

        @Override public boolean onTouch(View v, MotionEvent ev) {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) { downX = ev.getX(); downY = ev.getY(); }
            else if (ev.getAction() == MotionEvent.ACTION_UP
                    && Math.abs(ev.getX() - downX) < 20 && Math.abs(ev.getY() - downY) < 20) {
                int off = et.getOffsetForPosition(ev.getX(), ev.getY());
                Editable e = et.getText();
                for (int p = Math.max(0, off - 1); p <= Math.min(off, e.length() - 1); p++) {
                    char c = e.charAt(p);
                    if (c != '☐' && c != '☑') continue;
                    int ls = lastNewline(e, p - 1) + 1;
                    if (!e.subSequence(ls, p).toString().trim().isEmpty()) continue;
                    float boxRight = et.getLayout() == null ? 9999
                            : et.getLayout().getPrimaryHorizontal(p + 1) + et.getTotalPaddingLeft() + 24;
                    if (ev.getX() > boxRight) continue;
                    e.replace(p, p + 1, c == '☐' ? "☑" : "☐");
                    return false;
                }
            }
            return false;
        }
    };

    // ------------------------------------------------------------------ grey suggestions

    public void updateSuggestion() {
        String ghost = null;
        ghostKind = G_MATH;
        boolean below = false;
        if (store.setting(Store.S_LIST_TOTALS) && et.hasFocus()) {
            int cur = et.getSelectionStart();
            Editable e = et.getText();
            if (cur >= 0 && cur == et.getSelectionEnd() && (cur == e.length() || e.charAt(cur) == '\n')) {
                String[] lines = e.toString().split("\n", -1);
                int li = lineIndex(e, cur);
                String line = lines[li];
                String bare = line.trim();
                boolean onlyMarker = bare.isEmpty() || bare.matches("[-*•☐☑]|\\d{1,3}[.)]");
                boolean nextFree = li + 1 >= lines.length || lines[li + 1].trim().isEmpty();
                if (onlyMarker && li > 0 && nextFree) {
                    ListTotals.Block b = ListTotals.blockEndingAt(lines, li - 1);
                    if (b != null) { ghost = "Total = " + ListTotals.summary(b); ghostKind = G_TOTAL_HERE; }
                } else if (nextFree && ListTotals.price(line) != null) {
                    ListTotals.Block b = ListTotals.blockEndingAt(lines, li);
                    if (b != null) { ghost = "Total = " + ListTotals.summary(b); ghostKind = G_TOTAL_BELOW; below = true; }
                }
            }
        }
        if (ghost == null && store.setting(Store.S_LIST_TOTALS) && et.hasFocus()) {
            // price memory: "milk" on a list → last time's price in grey
            int cur = et.getSelectionStart();
            Editable e = et.getText();
            if (cur > 0 && cur == et.getSelectionEnd() && (cur == e.length() || e.charAt(cur) == '\n')) {
                String[] lines = e.toString().split("\n", -1);
                int li = lineIndex(e, cur);
                String line = lines[li];
                String bare = line.trim().replaceFirst("^(?:[-*•☐☑]|\\d{1,3}[.)])\\s*", "");
                boolean listy = line.trim().matches("^(?:[-*•☐☑]|\\d{1,3}[.)]).*")
                        || (li > 0 && ListTotals.price(lines[li - 1]) != null);
                if (listy && bare.length() >= 2 && !bare.matches(".*\\d.*") && bare.matches(".*\\p{L}$|.*\\p{L}\\s$")) {
                    PriceBook.Entry pe = PriceBook.get(store).find(line);
                    if (pe != null) {
                        ghost = (line.endsWith(" ") ? "" : " ") + ListTotals.money(pe.price, true);
                        ghostKind = G_MATH;
                    }
                }
            }
        }
        if (ghost == null && store.setting(Store.S_MATH_SUGGEST) && et.hasFocus()) {
            int cur = et.getSelectionStart();
            Editable e = et.getText();
            if (cur >= 0 && cur == et.getSelectionEnd() && (cur == e.length() || e.charAt(cur) == '\n')) {
                String before = e.subSequence(0, cur).toString();
                String trimmed = before.replaceAll("[ \\t]+$", "");
                char last = trimmed.isEmpty() ? 0 : trimmed.charAt(trimmed.length() - 1);
                if (last != 0 && last != '=' && "+-*/x×÷^(".indexOf(last) < 0) {
                    String answer = MathEval.answerFor(before);
                    if (answer == null) answer = SmartCalc.answerFor(before);   // GST, split, EMI, units, dates
                    if (answer != null) {
                        boolean space = before.endsWith(" ") || before.endsWith("\t");
                        ghost = (space ? "= " : " = ") + answer;
                    }
                }
            }
        }
        et.setGhost(ghost, below);
    }

    private void acceptGhost(String ghost) {
        int cur = et.getSelectionStart();
        if (cur < 0) return;
        Editable e = et.getText();
        snapshot();
        final int[] c = {cur};
        quiet(() -> {
            if (ghostKind == G_TOTAL_BELOW) {
                e.insert(c[0], "\n" + ghost);
                c[0] += 1 + ghost.length();
            } else if (ghostKind == G_TOTAL_HERE) {
                int ls = lastNewline(e, c[0] - 1) + 1;
                e.replace(ls, c[0], ghost);
                c[0] = ls + ghost.length();
            } else {
                e.insert(c[0], ghost);
                c[0] += ghost.length();
            }
        });
        et.setSelection(Math.min(c[0], e.length()));
        host.onTextEdited(this);
    }
}
