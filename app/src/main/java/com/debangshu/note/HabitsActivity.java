package com.debangshu.note;

import android.app.TimePickerDialog;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** Habit tracker: tick today (or the last few days), see streaks, get a nudge at a time you pick. */
public class HabitsActivity extends Screen {

    private static final String[] EMOJIS = {"✅", "💪", "🏃", "📖", "💧", "🧘", "🥗", "😴", "🦷", "💊", "✍️", "🎸", "🙏", "🚭", "📵", "🧹"};

    @Override
    protected void onCreate(android.os.Bundle b) {
        super.onCreate(b);
        topButton("＋", "New habit", true, v -> edit(null));
    }

    @Override
    protected void build() {
        body.removeAllViews();
        List<Habit> list = Habits.all(this);
        Calendar today = Calendar.getInstance();
        int due = 0, did = 0;
        for (Habit h : list) if (h.onDay(today)) { due++; if (h.doneOn(today)) did++; }
        setTitles("Habits", list.isEmpty() ? "Small things, every day" : did + " of " + due + " done today");

        if (list.isEmpty()) {
            LinearLayout c = card("🌱", "Start a habit", "Tap ＋ at the top");
            c.addView(hint("Pick something small — drink water, read 10 pages, walk 20 minutes. Tick it each day "
                    + "and watch the streak grow 🔥. You can get a reminder at the time you choose."));
            LinearLayout quick = new LinearLayout(this);
            quick.setOrientation(LinearLayout.VERTICAL);
            String[][] ideas = {{"💧", "Drink 8 glasses of water"}, {"📖", "Read 10 pages"}, {"🏃", "Walk 20 minutes"}, {"🧘", "Meditate 5 minutes"}};
            for (String[] idea : ideas) {
                quick.addView(row(idea[0] + "  " + idea[1], null, "＋", v -> {
                    Habit h = new Habit();
                    h.id = System.currentTimeMillis();
                    h.created = h.id;
                    h.emoji = idea[0];
                    h.name = L.t(idea[1]);
                    Habits.save(this, h);
                    build();
                    Ui.fonts(root);
                }));
            }
            c.addView(quick);
            return;
        }
        for (Habit h : list) body.addView(habitCard(h, today), cardParams());
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(12);
        return lp;
    }

    private View habitCard(Habit h, Calendar today) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.bg_card_plain);
        c.setElevation(dp);
        c.setPadding(px(16), px(14), px(16), px(14));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = text(h.emoji, 20, Ui.REGULAR, getColor(R.color.text));
        icon.setGravity(Gravity.CENTER);
        icon.setBackgroundResource(R.drawable.bg_icon_soft);
        head.addView(icon, new LinearLayout.LayoutParams(px(42), px(42)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(12), 0, px(8), 0);
        col.addView(text(h.name, 16, Ui.EXTRABOLD, getColor(R.color.text)));
        String sub = h.daysText() + (h.hour >= 0 ? " · ⏰ " + String.format(Locale.ROOT, "%d:%02d %s",
                h.hour % 12 == 0 ? 12 : h.hour % 12, h.minute, h.hour < 12 ? "am" : "pm") : "");
        col.addView(text(sub, 12, Ui.SEMIBOLD, getColor(R.color.text2)));
        head.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        int streak = h.streak(today);
        TextView st = text((streak > 0 ? "🔥 " : "") + streak, 18, Ui.EXTRABOLD, streak > 0 ? Ui.col(this, R.color.accent) : getColor(R.color.text2));
        head.addView(st);
        c.addView(head);

        // the last 7 days; tap any of them
        LinearLayout week = new LinearLayout(this);
        week.setPadding(0, px(12), 0, px(4));
        SimpleDateFormat dn = new SimpleDateFormat("EEE", L.dates());
        for (int i = 6; i >= 0; i--) {
            Calendar d = (Calendar) today.clone();
            d.add(Calendar.DAY_OF_MONTH, -i);
            boolean on = h.onDay(d), done = h.doneOn(d);
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.addView(text(i == 0 ? "Today" : dn.format(d.getTime()), 10, i == 0 ? Ui.BOLD : Ui.SEMIBOLD,
                    getColor(i == 0 ? R.color.text : R.color.text2)));
            TextView dot = text(done ? "✓" : String.valueOf(d.get(Calendar.DAY_OF_MONTH)), 13, Ui.BOLD,
                    done ? getColor(R.color.on_accent) : getColor(on ? R.color.text : R.color.hint));
            dot.setGravity(Gravity.CENTER);
            dot.setBackground(Ui.round(done ? Ui.col(this, R.color.accent) : on ? Ui.col(this, R.color.surface2) : 0, 18 * dp));
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(px(34), px(34));
            dl.topMargin = px(4);
            cell.addView(dot, dl);
            final Calendar day = d;
            cell.setOnClickListener(v -> {
                boolean now = Habits.toggle(this, h.id, day);
                if (now && i0(day, today) && h.streak(today) > 1) {
                    Ui.toast(this, "🔥 " + h.streak(today) + " days in a row!", Toast.LENGTH_SHORT).show();
                }
                build();
                Ui.fonts(root);
            });
            week.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        c.addView(week);
        TextView stats = text("Last 30 days: " + h.percent(today, 30) + "%  ·  best streak " + h.best(today)
                + "  ·  " + h.done.size() + " times in all", 12, Ui.REGULAR, getColor(R.color.text2));
        stats.setPadding(px(2), px(8), 0, 0);
        c.addView(stats);
        c.setOnClickListener(v -> edit(h));
        return c;
    }

    private static boolean i0(Calendar a, Calendar b) { return Habit.key(a).equals(Habit.key(b)); }

    // ------------------------------------------------------------------ add / edit

    private void edit(Habit existing) {
        Habit h = existing != null ? existing : new Habit();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(20), px(4), px(20), 0);

        EditText name = new EditText(this);
        name.setHint(L.t("What do you want to do? (e.g. Read 10 pages)"));
        name.setText(h.name);
        name.setSingleLine(true);
        box.addView(name);

        final String[] emoji = {h.emoji};
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout er = new LinearLayout(this);
        er.setPadding(0, px(10), 0, px(6));
        TextView[] eviews = new TextView[EMOJIS.length];
        for (int i = 0; i < EMOJIS.length; i++) {
            TextView e = text(EMOJIS[i], 20, Ui.REGULAR, getColor(R.color.text));
            e.setGravity(Gravity.CENTER);
            eviews[i] = e;
            final String em = EMOJIS[i];
            e.setOnClickListener(v -> {
                emoji[0] = em;
                for (TextView x : eviews) x.setBackground(Ui.round(x == e ? Ui.col(this, R.color.accent_soft) : 0, 12 * dp));
            });
            e.setBackground(Ui.round(em.equals(emoji[0]) ? Ui.col(this, R.color.accent_soft) : 0, 12 * dp));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(42), px(42));
            lp.rightMargin = px(4);
            er.addView(e, lp);
        }
        hs.addView(er);
        box.addView(hs);

        // days of the week
        final int[] days = {h.days};
        LinearLayout dr = new LinearLayout(this);
        dr.setPadding(0, px(6), 0, px(6));
        String[] dn = L.lang().equals("hi") ? new String[]{"र", "सो", "मं", "बु", "गु", "शु", "श"}
                : L.lang().equals("bn") ? new String[]{"র", "সো", "ম", "বু", "বৃ", "শু", "শ"}
                : new String[]{"S", "M", "T", "W", "T", "F", "S"};
        int[] order = {1, 2, 3, 4, 5, 6, 0};   // Monday first
        TextView[] dv = new TextView[7];
        for (int k = 0; k < 7; k++) {
            int d = order[k];
            TextView t = text(dn[d], 14, Ui.BOLD, getColor(R.color.text));
            t.setGravity(Gravity.CENTER);
            dv[d] = t;
            Runnable paint = () -> {
                boolean on = (days[0] & (1 << d)) != 0;
                t.setTextColor(on ? getColor(R.color.on_accent) : getColor(R.color.text2));
                t.setBackground(Ui.round(on ? Ui.col(this, R.color.accent) : Ui.col(this, R.color.surface2), 18 * dp));
            };
            paint.run();
            t.setOnClickListener(v -> { days[0] ^= (1 << d); paint.run(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(36), 1);
            lp.rightMargin = px(4);
            dr.addView(t, lp);
        }
        box.addView(text("Which days", 12, Ui.BOLD, getColor(R.color.text2)));
        box.addView(dr);

        final int[] time = {h.hour, h.minute};
        TextView rem = text("", 15, Ui.SEMIBOLD, Ui.col(this, R.color.accent));
        rem.setPadding(0, px(10), 0, px(10));
        Runnable paintTime = () -> rem.setText(time[0] < 0 ? "⏰  Add a reminder…"
                : String.format(Locale.ROOT, "⏰  Remind me at %d:%02d %s   (tap to change)",
                time[0] % 12 == 0 ? 12 : time[0] % 12, time[1], time[0] < 12 ? "am" : "pm"));
        paintTime.run();
        rem.setOnClickListener(v -> {
            String[] opts = time[0] < 0 ? new String[]{"Pick a time"} : new String[]{"Change the time", "No reminder"};
            new Ui.Popup(this).setItems(opts, (dd, w) -> {
                if (w == 1) { time[0] = -1; paintTime.run(); return; }
                new TimePickerDialog(this, (tp, hh, mm) -> { time[0] = hh; time[1] = mm; paintTime.run(); },
                        time[0] < 0 ? 8 : time[0], time[1], android.text.format.DateFormat.is24HourFormat(this)).show();
            }).show();
        });
        box.addView(rem);
        Ui.fonts(box);

        Ui.Popup p = new Ui.Popup(this);
        p.sheet().setTitle(existing == null ? "New habit" : "Edit habit").setView(box)
                .setPositiveButton(existing == null ? "Add" : "Save", (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) { Ui.toast(this, "Give it a name", Toast.LENGTH_SHORT).show(); return; }
                    if (days[0] == 0) days[0] = 127;
                    h.name = n;
                    h.emoji = emoji[0];
                    h.days = days[0];
                    h.hour = time[0];
                    h.minute = time[1];
                    if (h.id == 0) { h.id = System.currentTimeMillis(); h.created = h.id; }
                    Habits.save(this, h);
                    if (h.hour >= 0 && !Notifier.canNotify(this) && android.os.Build.VERSION.SDK_INT >= 33) {
                        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 5);
                    }
                    build();
                    Ui.fonts(root);
                })
                .setNegativeButton("Cancel", null);
        if (existing != null) p.setNeutralButton("Delete", (d, w) -> new Ui.Popup(this).setTitle("Delete “" + h.name + "”?")
                .setMessage("Its streak and history go too.")
                .setPositiveButton("Delete", (d2, w2) -> { Habits.delete(this, h.id); build(); Ui.fonts(root); })
                .setNegativeButton("Keep", null).show());
        p.show();
    }
}
