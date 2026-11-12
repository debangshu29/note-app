package com.debangshu.note;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Locale;

/**
 * Important reminders keep reminding: after it rings, the notification comes back every 10 minutes
 * until you tap Done (or Snooze, or tick the line) — for up to 6 hours.
 */
public final class Nagger {

    private Nagger() {}

    public static final int EVERY_MIN = 10;
    public static final int MAX_TIMES = 36;   // 6 hours

    /** "pay rent at 10 am !!", "… important", "… zaruri" mark a reminder important from the start. */
    public static boolean markedImportant(String line) {
        if (line == null) return false;
        String l = line.toLowerCase(Locale.ROOT);
        return l.contains("!!") || l.contains("⭐") || l.matches(".*\\b(?:important|imp|urgent|zaruri|jaruri|zaroori|jaroori|joruri)\\b.*");
    }

    private static PendingIntent pi(Context c, Reminder r) {
        Intent i = new Intent(c, Receiver.class);
        i.putExtra("reminderId", r.id);
        return PendingIntent.getBroadcast(c, r.requestCode() + 11, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void start(Context c, Reminder r) {
        r.nagging = true;
        r.nagCount = 0;
        Store.get(c).saveReminder(r);
        arm(c, r);
    }

    private static void arm(Context c, Reminder r) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long at = System.currentTimeMillis() + EVERY_MIN * 60_000L;
        try {
            boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c, r));
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c, r));
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c, r));
        }
    }

    static void cancelAlarm(Context c, Reminder r) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pi(c, r));
    }

    /** Answered (Done / Snooze / ticked): stop repeating. */
    static void stop(Context c, Reminder r) {
        cancelAlarm(c, r);
        if (r.nagging) {
            r.nagging = false;
            Store.get(c).saveReminder(r);
        }
    }

    public static class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            Reminder r = Store.get(c).reminder(i.getLongExtra("reminderId", -1));
            if (r == null || !r.nagging || r.ignored || (r.done && !r.repeats())) return;
            if (++r.nagCount > MAX_TIMES) { r.nagging = false; Store.get(c).saveReminder(r); return; }
            Store.get(c).saveReminder(r);
            Notifier.show(c, r);
            arm(c, r);
        }
    }
}
