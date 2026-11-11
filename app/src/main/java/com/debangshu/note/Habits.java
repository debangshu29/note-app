package com.debangshu.note;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** Habits are kept in their own file (habits.json) on the phone, with a daily reminder for the ones that want it. */
public final class Habits {

    private Habits() {}

    private static List<Habit> cache;

    private static File file(Context c) { return new File(c.getApplicationContext().getFilesDir(), "habits.json"); }

    public static synchronized List<Habit> all(Context c) {
        if (cache == null) cache = load(c);
        return new ArrayList<>(cache);
    }

    public static synchronized Habit get(Context c, long id) {
        for (Habit h : all(c)) if (h.id == id) return h;
        return null;
    }

    public static synchronized void save(Context c, Habit h) {
        if (cache == null) cache = load(c);
        boolean found = false;
        for (int i = 0; i < cache.size(); i++) if (cache.get(i).id == h.id) { cache.set(i, h); found = true; }
        if (!found) cache.add(h);
        write(c);
        schedule(c);
        Widgets.changed(c.getApplicationContext());
    }

    public static synchronized void delete(Context c, long id) {
        if (cache == null) cache = load(c);
        for (int i = cache.size() - 1; i >= 0; i--) if (cache.get(i).id == id) cache.remove(i);
        write(c);
        cancel(c, id);
    }

    /** Ticks / unticks a day. Returns the new state. */
    public static synchronized boolean toggle(Context c, long id, Calendar day) {
        Habit h = get(c, id);
        if (h == null) return false;
        String k = Habit.key(day);
        boolean now = !h.done.contains(k);
        if (now) h.done.add(k); else h.done.remove(k);
        save(c, h);
        if (now && Habit.key(Calendar.getInstance()).equals(k)) {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(notifyId(id));
        }
        return now;
    }

    /** Wipes everything (Settings → Delete all data). */
    public static synchronized void deleteAll(Context c) {
        for (Habit h : all(c)) cancel(c, h.id);
        cache = new ArrayList<>();
        file(c).delete();
    }

    private static List<Habit> load(Context c) {
        List<Habit> out = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return out;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; }
            JSONArray arr = new JSONArray(new String(b, 0, n, "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Habit h = new Habit();
                h.id = o.optLong("id");
                h.name = o.optString("name");
                h.emoji = o.optString("emoji", "✅");
                h.days = o.optInt("days", 127);
                h.hour = o.optInt("hour", -1);
                h.minute = o.optInt("minute", 0);
                h.created = o.optLong("created");
                JSONArray d = o.optJSONArray("done");
                if (d != null) for (int k = 0; k < d.length(); k++) h.done.add(d.optString(k));
                out.add(h);
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static void write(Context c) {
        try {
            JSONArray arr = new JSONArray();
            for (Habit h : cache) {
                JSONObject o = new JSONObject();
                o.put("id", h.id);
                o.put("name", h.name);
                o.put("emoji", h.emoji);
                o.put("days", h.days);
                o.put("hour", h.hour);
                o.put("minute", h.minute);
                o.put("created", h.created);
                JSONArray d = new JSONArray();
                for (String s : h.done) d.put(s);
                o.put("done", d);
                arr.put(o);
            }
            File f = file(c);
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(arr.toString().getBytes("UTF-8")); }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
        } catch (Exception ignored) { }
    }

    /** For backups: the habits as JSON text. */
    public static synchronized String exportJson(Context c) {
        if (cache == null) cache = load(c);
        File f = file(c);
        if (!f.exists()) return "[]";
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = in.read(b);
            return new String(b, 0, Math.max(0, n), "UTF-8");
        } catch (Exception e) { return "[]"; }
    }

    public static synchronized void importJson(Context c, String json) {
        try {
            new JSONArray(json);   // only write something that reads back
            try (FileOutputStream out = new FileOutputStream(file(c))) { out.write(json.getBytes("UTF-8")); }
            cache = null;
            schedule(c);
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ reminders

    private static final String CHANNEL = "habits";
    private static final String ACTION_DONE = "com.debangshu.note.HABIT_DONE";
    private static final String EXTRA_ID = "habitId";

    private static int notifyId(long id) { return 880000 + (int) (id % 100000); }

    private static PendingIntent alarmPi(Context c, long id) {
        Intent i = new Intent(c, Receiver.class);
        i.putExtra(EXTRA_ID, id);
        return PendingIntent.getBroadcast(c, notifyId(id), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void cancel(Context c, long id) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(alarmPi(c, id));
    }

    /** The next reminder for every habit that has a time (within a few minutes; saves battery). */
    public static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        for (Habit h : all(c)) {
            am.cancel(alarmPi(c, h.id));
            if (h.hour < 0 || h.days == 0) continue;
            Calendar t = Calendar.getInstance();
            t.set(Calendar.HOUR_OF_DAY, h.hour);
            t.set(Calendar.MINUTE, h.minute);
            t.set(Calendar.SECOND, 0);
            t.set(Calendar.MILLISECOND, 0);
            if (t.getTimeInMillis() <= System.currentTimeMillis() + 30_000) t.add(Calendar.DAY_OF_MONTH, 1);
            for (int g = 0; g < 8 && !h.onDay(t); g++) t.add(Calendar.DAY_OF_MONTH, 1);
            try { am.setWindow(AlarmManager.RTC_WAKEUP, t.getTimeInMillis(), 5 * 60 * 1000L, alarmPi(c, h.id)); }
            catch (Exception ignored) { }
        }
    }

    public static class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            long id = i.getLongExtra(EXTRA_ID, -1);
            if (ACTION_DONE.equals(i.getAction())) {
                Calendar today = Calendar.getInstance();
                Habit h = get(c, id);
                if (h != null && !h.doneOn(today)) toggle(c, id, today);
                NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(notifyId(id));
                return;
            }
            Habit h = get(c, id);
            if (h != null) remind(c, h);
            schedule(c);
        }
    }

    @SuppressWarnings("deprecation")
    private static void remind(Context c, Habit h) {
        Calendar today = Calendar.getInstance();
        if (!h.onDay(today) || h.doneOn(today) || !Notifier.canNotify(c)) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Habits", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("A nudge at the time you chose for a habit");
            nm.createNotificationChannel(ch);
        }
        int streak = h.streak(today);
        Intent open = new Intent(c, HabitsActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Intent done = new Intent(c, Receiver.class);
        done.setAction(ACTION_DONE);
        done.putExtra(EXTRA_ID, h.id);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(h.emoji + "  " + h.name)
                .setContentText(streak > 0 ? "🔥 " + streak + "-day streak — keep it going" : "Time for it today")
                .setColor(Ui.col(c, R.color.accent))
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, notifyId(h.id), open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .addAction(new Notification.Action.Builder(null, "Done ✓",
                        PendingIntent.getBroadcast(c, notifyId(h.id) + 1, done, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build());
        nm.notify(notifyId(h.id), b.build());
    }
}
