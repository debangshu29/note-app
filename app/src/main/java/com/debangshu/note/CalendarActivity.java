package com.debangshu.note;

import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Month calendar: reminders (repeating ones on every day they come), notes made that day, habits done. */
public class CalendarActivity extends Screen {

    private final Calendar month = Calendar.getInstance();
    private final Calendar selected = Calendar.getInstance();

    static final class Item {
        long time;
        boolean hasTime, past, repeat;
        String title;
        long noteId;
        int kind;
    }

    @Override
    protected void onCreate(android.os.Bundle b) {
        super.onCreate(b);
        month.set(Calendar.DAY_OF_MONTH, 1);
        topButton("◎", "Today", false, v -> {
            month.setTimeInMillis(System.currentTimeMillis());
            month.set(Calendar.DAY_OF_MONTH, 1);
            selected.setTimeInMillis(System.currentTimeMillis());
            build();
            Ui.fonts(root);
        });
        topButton("＋", "New note for the chosen day", true, v -> newNoteForDay());
    }

    private static long dayStart(Calendar c) {
        Calendar d = (Calendar) c.clone();
        d.set(Calendar.HOUR_OF_DAY, 0); d.set(Calendar.MINUTE, 0); d.set(Calendar.SECOND, 0); d.set(Calendar.MILLISECOND, 0);
        return d.getTimeInMillis();
    }

    /** Every reminder in [from, to), repeating ones expanded. Key = day start. */
    private Map<Long, List<Item>> reminders(long from, long to) {
        Map<Long, List<Item>> out = new HashMap<>();
        long now = System.currentTimeMillis();
        for (Reminder r : store.reminders()) {
            if (r.ignored) continue;
            Note n = store.note(r.noteId);
            if (n == null || n.deleted > 0) continue;
            List<Long> times = new ArrayList<>();
            if (r.repeats() && !r.repeat.startsWith("I")) {
                long t = r.time;
                for (int g = 0; g < 400 && t < to; g++) {
                    if (t >= from) times.add(t);
                    long nx = DateParser.nextAfter(t, r.repeat, t);
                    if (nx <= t) break;
                    t = nx;
                }
            } else if (r.time >= from && r.time < to) times.add(r.time);
            for (long t : times) {
                Item it = new Item();
                it.time = t;
                it.hasTime = r.hasTime;
                it.title = r.title;
                it.noteId = r.noteId;
                it.kind = r.kind;
                it.repeat = r.repeats();
                it.past = t < now || (r.done && !r.repeats());
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(t);
                long k = dayStart(c);
                if (!out.containsKey(k)) out.put(k, new ArrayList<>());
                out.get(k).add(it);
            }
        }
        for (List<Item> l : out.values()) Collections.sort(l, (a, b) -> Long.compare(a.time, b.time));
        return out;
    }

    @Override
    protected void build() {
        body.removeAllViews();
        setTitles(L.date("MMMM yyyy", month.getTime()), "Tap a day to see it");

        Calendar first = (Calendar) month.clone();
        first.set(Calendar.DAY_OF_MONTH, 1);
        long from = dayStart(first);
        Calendar end = (Calendar) first.clone();
        end.add(Calendar.MONTH, 1);
        long to = dayStart(end);
        Map<Long, List<Item>> rem = reminders(from, to);
        Map<Long, List<Note>> made = new HashMap<>();
        for (Note n : store.notes()) {
            if (n.deleted > 0 || n.created < from || n.created >= to) continue;
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(n.created);
            long k = dayStart(c);
            if (!made.containsKey(k)) made.put(k, new ArrayList<>());
            made.get(k).add(n);
        }
        List<Habit> habits = Habits.all(this);

        // ---- month card with arrows
        LinearLayout card = card(null, null, null);
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        TextView prev = text("‹", 26, Ui.BOLD, Ui.col(this, R.color.accent));
        prev.setPadding(px(10), 0, px(10), px(2));
        prev.setOnClickListener(v -> { month.add(Calendar.MONTH, -1); build(); Ui.fonts(root); });
        TextView next = text("›", 26, Ui.BOLD, Ui.col(this, R.color.accent));
        next.setPadding(px(10), 0, px(10), px(2));
        next.setOnClickListener(v -> { month.add(Calendar.MONTH, 1); build(); Ui.fonts(root); });
        TextView label = text(L.date("MMMM yyyy", month.getTime()), 16, Ui.EXTRABOLD, getColor(R.color.text));
        label.setGravity(Gravity.CENTER);
        nav.addView(prev);
        nav.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        nav.addView(next);
        card.addView(nav);

        LinearLayout names = new LinearLayout(this);
        names.setPadding(0, px(8), 0, px(4));
        for (String d : new String[]{"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"}) {
            TextView t = text(d, 11, Ui.BOLD, getColor(R.color.text2));
            t.setGravity(Gravity.CENTER);
            names.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        card.addView(names);

        int lead = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7;   // Monday first
        int days = first.getActualMaximum(Calendar.DAY_OF_MONTH);
        long todayKey = dayStart(Calendar.getInstance());
        long selKey = dayStart(selected);
        boolean selInMonth = selected.get(Calendar.MONTH) == month.get(Calendar.MONTH) && selected.get(Calendar.YEAR) == month.get(Calendar.YEAR);
        int cell = 0;
        LinearLayout week = null;
        for (int i = 0; i < lead + days; i++) {
            if (cell % 7 == 0) {
                week = new LinearLayout(this);
                card.addView(week);
            }
            cell++;
            if (i < lead) { week.addView(new View(this), new LinearLayout.LayoutParams(0, px(52), 1)); continue; }
            int dayNo = i - lead + 1;
            Calendar d = (Calendar) first.clone();
            d.set(Calendar.DAY_OF_MONTH, dayNo);
            long k = dayStart(d);
            List<Item> r = rem.get(k);
            List<Note> m = made.get(k);
            int habitsDone = 0, habitsDue = 0;
            for (Habit h : habits) if (h.onDay(d)) { habitsDue++; if (h.doneOn(d)) habitsDone++; }

            LinearLayout c = new LinearLayout(this);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            c.setPadding(0, px(4), 0, px(4));
            boolean isSel = selInMonth && k == selKey, isToday = k == todayKey;
            TextView num = text(String.valueOf(dayNo), 14, isToday || isSel ? Ui.EXTRABOLD : Ui.SEMIBOLD,
                    isSel ? getColor(R.color.on_accent) : isToday ? Ui.col(this, R.color.accent) : getColor(R.color.text));
            num.setGravity(Gravity.CENTER);
            num.setBackground(Ui.round(isSel ? Ui.col(this, R.color.accent) : isToday ? Ui.col(this, R.color.accent_soft) : 0, 16 * dp));
            c.addView(num, new LinearLayout.LayoutParams(px(32), px(32)));
            LinearLayout dots = new LinearLayout(this);
            dots.setGravity(Gravity.CENTER);
            dots.setPadding(0, px(3), 0, 0);
            if (r != null) dots.addView(dot(Ui.col(this, R.color.accent)));
            if (m != null) dots.addView(dot(getColor(R.color.text2)));
            if (habitsDue > 0 && habitsDone == habitsDue) dots.addView(dot(getColor(R.color.success)));
            c.addView(dots, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(9)));
            c.setOnClickListener(v -> { selected.setTimeInMillis(d.getTimeInMillis()); build(); Ui.fonts(root); });
            week.addView(c, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        while (cell % 7 != 0) { week.addView(new View(this), new LinearLayout.LayoutParams(0, px(52), 1)); cell++; }

        LinearLayout legend = new LinearLayout(this);
        legend.setGravity(Gravity.CENTER);
        legend.setPadding(0, px(8), 0, 0);
        legend.addView(dot(Ui.col(this, R.color.accent)));
        legend.addView(text(" reminder   ", 11, Ui.REGULAR, getColor(R.color.text2)));
        legend.addView(dot(getColor(R.color.text2)));
        legend.addView(text(" note made   ", 11, Ui.REGULAR, getColor(R.color.text2)));
        legend.addView(dot(getColor(R.color.success)));
        legend.addView(text(" all habits done", 11, Ui.REGULAR, getColor(R.color.text2)));
        card.addView(legend);

        // ---- the chosen day
        if (!selInMonth) return;
        List<Item> r = rem.get(selKey);
        List<Note> m = made.get(selKey);
        String dayTitle = L.date("EEEE, d MMMM", selected.getTime());
        LinearLayout dc = card("📅", dayTitle, (r == null ? "No reminders" : r.size() + (r.size() == 1 ? " reminder" : " reminders"))
                + (m == null ? "" : " · " + m.size() + (m.size() == 1 ? " note" : " notes")));
        java.text.DateFormat tf = android.text.format.DateFormat.getTimeFormat(this);
        if (r != null) for (Item it : r) {
            String t = it.hasTime ? tf.format(new Date(it.time)) : "All day";
            LinearLayout row = row(ReminderKind.emoji(it.kind) + "  " + it.title + (it.repeat ? "  🔁" : ""), t, null, v -> openNote(it.noteId));
            if (it.past) row.setAlpha(0.55f);
            dc.addView(row);
        }
        if (m != null) {
            dc.addView(sectionLabel("Notes made this day"));
            for (Note n : m) dc.addView(row("📝  " + (n.displayTitle().isEmpty() ? "Untitled" : n.displayTitle()),
                    n.locked ? "🔒 Locked" : firstLine(n.body), null, v -> openNote(n.id)));
        }
        if (!habits.isEmpty()) {
            List<String> done = new ArrayList<>();
            for (Habit h : habits) if (h.doneOn(selected)) done.add(h.emoji + " " + h.name);
            if (!done.isEmpty()) {
                dc.addView(sectionLabel("Habits done"));
                dc.addView(row(android.text.TextUtils.join("\n", done), null, null, v -> startActivity(new Intent(this, HabitsActivity.class))));
            }
        }
        if (r == null && m == null) dc.addView(hint("Tap ＋ at the top to plan something for this day."));
    }

    private TextView sectionLabel(String s) {
        TextView t = text(s, 12, Ui.BOLD, getColor(R.color.text2));
        t.setPadding(px(8), px(12), 0, px(2));
        return t;
    }

    private static String firstLine(String body) {
        if (body == null) return "";
        for (String l : body.split("\n")) if (!l.trim().isEmpty()) return l.trim();
        return "";
    }

    private View dot(int color) {
        View v = new View(this);
        v.setBackground(Ui.round(color, 3 * dp));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(6), px(6));
        lp.leftMargin = px(1);
        lp.rightMargin = px(1);
        v.setLayoutParams(lp);
        return v;
    }

    /** A new note that starts with the chosen day, so what you write becomes a reminder on that day. */
    private void newNoteForDay() {
        String d = new SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(selected.getTime());
        Intent i = new Intent(this, EditorActivity.class);
        i.putExtra(EditorActivity.EXTRA_NEW_BODY, "on " + d + " ");
        startActivity(i);
    }
}
