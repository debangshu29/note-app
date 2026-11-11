package com.debangshu.note;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Today page: today's (and tomorrow's) reminders, everything still unticked on your lists, and pinned notes. */
public class TodayActivity extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(Ui.wrap(base)); }

    private Store store;
    private LinearLayout body;
    private float dp;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.install(this);
        store = Store.get(this);
        dp = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.col(this, R.color.bg));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(px(12), px(10), px(12), px(6));
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_back);
        back.setBackgroundResource(R.drawable.bg_circle);
        back.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.text)));
        back.setPadding(px(11), px(11), px(11), px(11));
        back.setElevation(dp);
        back.setContentDescription("Back");
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(px(44), px(44)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(px(14), 0, 0, 0);
        TextView t = text("Today", 24, Ui.EXTRABOLD, getColor(R.color.text));
        TextView d = text(DateFormat.format("EEEE, d MMMM", new Date()).toString(), 13, Ui.SEMIBOLD, getColor(R.color.text2));
        titles.addView(t);
        titles.addView(d);
        top.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView add = text("＋", 22, Ui.BOLD, getColor(R.color.on_accent));
        add.setGravity(Gravity.CENTER);
        add.setBackgroundResource(R.drawable.bg_circle_accent);
        add.setElevation(3 * dp);
        add.setContentDescription("New note");
        add.setOnClickListener(v -> startActivity(new Intent(this, EditorActivity.class)));
        top.addView(add, new LinearLayout.LayoutParams(px(44), px(44)));
        root.addView(top);

        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(px(16), px(6), px(16), px(40));
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.refreshTheme(this)) return;
        if (Lock.guard(this)) return;
        build();
    }

    private void build() {
        body.removeAllViews();
        List<Reminder> today = Today.remindersToday(store);
        List<Reminder> tomorrow = Today.remindersTomorrow(store);
        List<Checklist.Item> todo = Checklist.open(store);

        // ---- reminders
        LinearLayout card = card("⏰", "Reminders", today.isEmpty() ? "Nothing planned today" : today.size() + " today");
        java.text.DateFormat tf = DateFormat.getTimeFormat(this);
        long now = System.currentTimeMillis();
        for (Reminder r : today) card.addView(reminderRow(r, tf, r.time < now));
        if (!tomorrow.isEmpty()) {
            TextView h = text("Tomorrow", 12, Ui.BOLD, getColor(R.color.text2));
            h.setPadding(px(4), px(12), 0, px(4));
            card.addView(h);
            for (Reminder r : tomorrow) card.addView(reminderRow(r, tf, false));
        }

        // ---- to do, grouped by note
        LinearLayout todoCard = card("☑️", "Still to do", todo.isEmpty() ? "All your lists are done ✓" : todo.size() + (todo.size() == 1 ? " thing" : " things") + " left");
        Map<Long, List<Checklist.Item>> byNote = new LinkedHashMap<>();
        for (Checklist.Item it : todo) {
            if (!byNote.containsKey(it.noteId)) byNote.put(it.noteId, new ArrayList<>());
            byNote.get(it.noteId).add(it);
        }
        for (Map.Entry<Long, List<Checklist.Item>> e : byNote.entrySet()) {
            List<Checklist.Item> items = e.getValue();
            TextView h = text(items.get(0).noteTitle + "  ›", 13, Ui.BOLD, Ui.col(this, R.color.accent));
            h.setPadding(px(4), px(10), 0, px(2));
            long id = e.getKey();
            h.setOnClickListener(v -> openNote(id));
            todoCard.addView(h);
            int shown = 0;
            for (Checklist.Item it : items) {
                if (shown++ >= 12) {
                    TextView more = text("+" + (items.size() - 12) + " more", 12, Ui.REGULAR, getColor(R.color.text2));
                    more.setPadding(px(36), px(2), 0, px(4));
                    todoCard.addView(more);
                    break;
                }
                todoCard.addView(todoRow(it));
            }
        }

        // ---- habits for today
        List<Habit> habits = new ArrayList<>();
        java.util.Calendar now0 = java.util.Calendar.getInstance();
        for (Habit h : Habits.all(this)) if (h.onDay(now0)) habits.add(h);
        if (!habits.isEmpty()) {
            int did = 0;
            for (Habit h : habits) if (h.doneOn(now0)) did++;
            LinearLayout hc = card("✅", "Habits", did + " of " + habits.size() + " done");
            for (Habit h : habits) {
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(px(4), px(6), px(4), px(6));
                row.setBackground(Ui.ripple(Ui.round(0, 12 * dp)));
                boolean done = h.doneOn(now0);
                TextView box = text(done ? "☑" : "☐", 20, Ui.REGULAR, Ui.col(this, R.color.accent));
                box.setGravity(Gravity.CENTER);
                box.setMinWidth(px(30));
                row.addView(box);
                int st = h.streak(now0);
                TextView t = text(h.emoji + "  " + h.name + (st > 1 ? "   🔥" + st : ""), 15, Ui.REGULAR, getColor(done ? R.color.hint : R.color.text));
                t.setPadding(px(6), 0, 0, 0);
                row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                row.setOnClickListener(v -> { Habits.toggle(this, h.id, java.util.Calendar.getInstance()); build(); });
                hc.addView(row);
            }
        }

        // ---- calendar link
        TextView cal = text("📅  Open the calendar  ›", 14, Ui.BOLD, Ui.col(this, R.color.accent));
        cal.setPadding(px(8), px(14), 0, px(4));
        cal.setOnClickListener(v -> startActivity(new Intent(this, CalendarActivity.class)));
        body.addView(cal);

        // ---- money this month
        List<MoneyActivity.Month> months = MoneyActivity.months(store, 1);
        double spent = months.isEmpty() ? 0 : months.get(0).total;
        int owe = MoneyActivity.people(store).size();
        if (spent > 0 || owe > 0) {
            LinearLayout mc = card("💰", "Money", (spent > 0 ? "Spent " + ListTotals.money(spent, true) + " this month" : "This month")
                    + (owe > 0 ? " · " + owe + (owe == 1 ? " person" : " people") + " to settle with" : ""));
            TextView open = text("Open Money  ›", 14, Ui.BOLD, Ui.col(this, R.color.accent));
            open.setPadding(px(4), px(10), 0, px(2));
            open.setOnClickListener(v -> startActivity(new Intent(this, MoneyActivity.class)));
            mc.addView(open);
            mc.setOnClickListener(v -> startActivity(new Intent(this, MoneyActivity.class)));
        }

        // ---- pinned notes
        List<Note> pinned = new ArrayList<>();
        for (Note n : store.notes()) if (n.pinned) pinned.add(n);
        if (!pinned.isEmpty()) {
            LinearLayout pc = card("📌", "Pinned", pinned.size() + (pinned.size() == 1 ? " note" : " notes"));
            for (Note n : pinned) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(px(12), px(10), px(12), px(10));
                row.setBackground(Ui.ripple(Ui.round(Ui.col(this, R.color.surface2), 14 * dp)));
                row.addView(text(n.displayTitle(), 15, Ui.BOLD, getColor(R.color.text)));
                String preview = n.locked ? "🔒 Locked note" : n.body == null ? "" : n.body.trim();
                if (!preview.isEmpty()) {
                    TextView p = text(preview, 13, Ui.REGULAR, getColor(R.color.text2));
                    p.setMaxLines(2);
                    p.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    row.addView(p);
                }
                row.setOnClickListener(v -> openNote(n.id));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = px(8);
                pc.addView(row, lp);
            }
        }
        Ui.fonts(body);
    }

    private View reminderRow(Reminder r, java.text.DateFormat tf, boolean past) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(4), px(8), px(4), px(8));
        row.setBackground(Ui.ripple(Ui.round(0, 12 * dp)));
        TextView time = text(r.hasTime ? tf.format(new Date(r.time)) : "All day", 12, Ui.BOLD,
                past ? getColor(R.color.text2) : Ui.col(this, R.color.accent));
        time.setGravity(Gravity.CENTER);
        time.setBackground(Ui.round(past ? Ui.col(this, R.color.surface2) : Ui.col(this, R.color.accent_soft), 10 * dp));
        time.setPadding(px(8), px(5), px(8), px(5));
        time.setMinWidth(px(70));
        row.addView(time);
        TextView t = text(ReminderKind.emoji(r.kind) + "  " + r.title
                + (r.repeats() ? "  🔁" : ""), 15, Ui.SEMIBOLD, getColor(R.color.text));
        t.setPadding(px(12), 0, 0, 0);
        t.setMaxLines(2);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setOnClickListener(v -> openNote(r.noteId));
        return row;
    }

    private View todoRow(Checklist.Item it) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(4), px(6), px(4), px(6));
        row.setBackground(Ui.ripple(Ui.round(0, 12 * dp)));
        TextView box = text("☐", 20, Ui.REGULAR, Ui.col(this, R.color.accent));
        box.setGravity(Gravity.CENTER);
        box.setMinWidth(px(30));
        row.addView(box);
        TextView t = text(it.text, 15, Ui.REGULAR, getColor(R.color.text));
        t.setPadding(px(6), 0, 0, 0);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setOnClickListener(v -> {
            Boolean done = Checklist.toggle(this, it.noteId, it.block, it.offset);
            if (done == null) { build(); return; }
            box.setText(done ? "☑" : "☐");
            t.setPaintFlags(done ? t.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG : t.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            t.setTextColor(getColor(done ? R.color.hint : R.color.text));
        });
        return row;
    }

    private LinearLayout card(String emoji, String title, String sub) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.bg_card_plain);
        c.setElevation(dp);
        c.setPadding(px(16), px(14), px(16), px(14));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = text(emoji, 18, Ui.REGULAR, getColor(R.color.text));
        icon.setGravity(Gravity.CENTER);
        icon.setBackgroundResource(R.drawable.bg_icon_soft);
        head.addView(icon, new LinearLayout.LayoutParams(px(38), px(38)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(12), 0, 0, 0);
        col.addView(text(title, 16, Ui.EXTRABOLD, getColor(R.color.text)));
        col.addView(text(sub, 12, Ui.SEMIBOLD, getColor(R.color.text2)));
        head.addView(col);
        c.addView(head);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(12);
        body.addView(c, lp);
        return c;
    }

    private TextView text(String s, int sp, int weight, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Ui.face(this, weight));
        t.setTextColor(color);
        t.setTag(Ui.KEEP_FONT);
        return t;
    }

    private void openNote(long id) {
        Note n = store.note(id);
        if (n != null && n.locked && !Lock.isOpen(id)) {
            Lock.verify(this, "Locked note", ok -> { if (ok) { Lock.markOpen(id); openNote(id); } });
            return;
        }
        Intent i = new Intent(this, EditorActivity.class);
        i.putExtra(EditorActivity.EXTRA_NOTE_ID, id);
        startActivity(i);
    }

    private int px(float v) { return Math.round(v * dp); }
}
