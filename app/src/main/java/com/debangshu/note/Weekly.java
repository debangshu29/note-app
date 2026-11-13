package com.debangshu.note;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Sunday summary: one notification each Sunday evening — the week's spending, reminders, habits and what's coming next week. */
public final class Weekly {

    private Weekly() {}

    public static final String P_ON = "weekly_on", P_HOUR = "weekly_hour", P_MIN = "weekly_min";
    private static final String CHANNEL = "weekly";
    private static final int NOTIF_ID = 7720, REQ = 7721;
    private static final long DAY = 86_400_000L;

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("settings", Context.MODE_PRIVATE); }

    public static boolean on(Context c) { return prefs(c).getBoolean(P_ON, true); }
    public static int hour(Context c) { return prefs(c).getInt(P_HOUR, 18); }
    public static int minute(Context c) { return prefs(c).getInt(P_MIN, 0); }

    // ------------------------------------------------------------------ the numbers

    public static final class Summary {
        public double spent, spentBefore;
        public String topCategory = "";
        public double topAmount;
        public int notesMade, remindersDone, remindersMissed, nextWeekReminders;
        public final List<String> habits = new ArrayList<>();      // "💧 Water 6/7 · 🔥 12"
        public final List<String> bills = new ArrayList<>();       // "Netflix ₹649 · Tue 17"
        public final List<String> coming = new ArrayList<>();      // next reminders, "Mon 9:00 · Dentist"
        public final List<String> missed = new ArrayList<>();
    }

    public static Summary compute(Context c) {
        Store s = Store.get(c);
        Summary m = new Summary();
        long now = System.currentTimeMillis();
        long weekAgo = now - 7 * DAY, twoWeeks = now - 14 * DAY, nextWeek = now + 7 * DAY;

        // spending: price lists in notes made this week / the week before
        java.util.Map<String, Double> cats = new java.util.HashMap<>();
        for (Note n : s.notes()) {
            if (n.deleted > 0 || n.locked) continue;
            if (n.created >= weekAgo) m.notesMade++;
            List<String> tags = n.tags();
            if (tags.contains("budget") || tags.contains("plan") || tags.contains(WalletActivity.TAG)) continue;
            if (n.created < twoWeeks) continue;
            double sum = 0;
            for (Money.Spent x : Money.spent(n.body)) sum += x.amount;
            if (sum <= 0) continue;
            if (n.created >= weekAgo) {
                m.spent += sum;
                String cat = n.displayTitle().isEmpty() ? "Other" : n.displayTitle();
                for (String t : tags) if (!t.equals("todo")) { cat = "#" + t; break; }
                cats.put(cat, (cats.containsKey(cat) ? cats.get(cat) : 0) + sum);
            } else m.spentBefore += sum;
        }
        for (java.util.Map.Entry<String, Double> e : cats.entrySet())
            if (e.getValue() > m.topAmount) { m.topAmount = e.getValue(); m.topCategory = e.getKey(); }

        // reminders: this week's (one-time ones) and the coming week's
        SimpleDateFormat day = new SimpleDateFormat("EEE d", L.dates());
        java.text.DateFormat tf = android.text.format.DateFormat.getTimeFormat(c);
        List<Reminder> next = new ArrayList<>();
        for (Reminder r : s.reminders()) {
            if (r.ignored) continue;
            Note n = s.note(r.noteId);
            if (n == null || n.deleted > 0) continue;
            if (!r.repeats() && r.time >= weekAgo && r.time < now) {
                if (r.done) m.remindersDone++;
                else { m.remindersMissed++; if (m.missed.size() < 5) m.missed.add(r.title); }
            }
            if (!r.done && r.time >= now && r.time < nextWeek) {
                m.nextWeekReminders++;
                next.add(r);
                Money.Bill b = Money.bill(r.sourceLine);
                if (b != null) m.bills.add(b.name + " " + ListTotals.money(b.amount, true) + " · " + day.format(new Date(r.time)));
            }
        }
        Collections.sort(next, (a, b) -> Long.compare(a.time, b.time));
        for (Reminder r : next) {
            if (m.coming.size() >= 6) break;
            m.coming.add(day.format(new Date(r.time)) + (r.hasTime ? " " + tf.format(new Date(r.time)) : "") + " · " + r.title);
        }

        // habits: days done in the last 7, and the streak
        Calendar today = Calendar.getInstance();
        for (Habit h : Habits.all(c)) {
            int due = 0, did = 0;
            Calendar d = (Calendar) today.clone();
            for (int i = 0; i < 7; i++) {
                if (h.onDay(d)) { due++; if (h.doneOn(d)) did++; }
                d.add(Calendar.DAY_OF_MONTH, -1);
            }
            if (due == 0) continue;
            int st = h.streak(today);
            m.habits.add(h.emoji + " " + h.name + "  " + did + "/" + due + (st > 1 ? "  · 🔥 " + st : ""));
        }
        return m;
    }

    /** The notification's lines. */
    static List<String> lines(Context c, Summary m) {
        List<String> out = new ArrayList<>();
        if (m.spent > 0) {
            String cmp = m.spentBefore > 0
                    ? (m.spent >= m.spentBefore ? " (" + ListTotals.money(m.spent - m.spentBefore, true) + " more than last week)"
                    : " (" + ListTotals.money(m.spentBefore - m.spent, true) + " less than last week)") : "";
            out.add("💰 Spent " + ListTotals.money(m.spent, true) + cmp);
        }
        if (m.remindersDone + m.remindersMissed > 0)
            out.add("✅ " + m.remindersDone + " reminder" + (m.remindersDone == 1 ? "" : "s") + " done"
                    + (m.remindersMissed > 0 ? " · " + m.remindersMissed + " not ticked" : ""));
        if (!m.habits.isEmpty()) out.add("🔥 Habits: " + android.text.TextUtils.join(" · ", m.habits.subList(0, Math.min(3, m.habits.size()))));
        if (!m.bills.isEmpty()) out.add("📅 Bills next week: " + android.text.TextUtils.join(", ", m.bills));
        if (m.nextWeekReminders > 0) out.add("⏰ " + m.nextWeekReminders + " reminder" + (m.nextWeekReminders == 1 ? "" : "s") + " next week");
        if (m.notesMade > 0) out.add("📝 " + m.notesMade + " new note" + (m.notesMade == 1 ? "" : "s") + " this week");
        return out;
    }

    // ------------------------------------------------------------------ Sunday evening

    private static PendingIntent pi(Context c) {
        return PendingIntent.getBroadcast(c, REQ, new Intent(c, Receiver.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(pi(c));
        if (!on(c)) return;
        Calendar t = Calendar.getInstance();
        t.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY);
        t.set(Calendar.HOUR_OF_DAY, hour(c));
        t.set(Calendar.MINUTE, minute(c));
        t.set(Calendar.SECOND, 0);
        t.set(Calendar.MILLISECOND, 0);
        while (t.getTimeInMillis() <= System.currentTimeMillis() + 60_000) t.add(Calendar.WEEK_OF_YEAR, 1);
        try { am.setWindow(AlarmManager.RTC_WAKEUP, t.getTimeInMillis(), 10 * 60_000L, pi(c)); } catch (Exception ignored) { }
    }

    public static class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            show(c);
            schedule(c);
        }
    }

    @SuppressWarnings("deprecation")
    public static void show(Context c) {
        if (!on(c) || !Notifier.canNotify(c)) return;
        Summary m = compute(c);
        List<String> l = lines(c, m);
        if (l.isEmpty()) return;   // a quiet week: no notification
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, L.t("Sunday summary"), NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(L.t("Your week in one notification, every Sunday evening"));
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(c, WeekActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_notify)
                .setColor(Ui.col(c, R.color.accent))
                .setContentTitle(L.t("📊 Your week"))
                .setContentText(L.t(l.get(0)))
                .setStyle(new Notification.BigTextStyle().bigText(L.t(android.text.TextUtils.join("\n", l))))
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, REQ, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        nm.notify(NOTIF_ID, b.build());
    }
}
