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
import android.text.format.DateFormat;

import java.util.Calendar;
import java.util.Date;
import java.util.List;

/** Morning brief: one quiet notification each morning with today's reminders and what's left on your lists. */
public final class Brief {

    private Brief() {}

    public static final String P_ON = "brief_on", P_HOUR = "brief_hour", P_MIN = "brief_min";
    private static final String CHANNEL = "brief";
    private static final int NOTIF_ID = 7700, REQ = 7701;

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public static boolean on(Context c) { return prefs(c).getBoolean(P_ON, true); }
    public static int hour(Context c) { return prefs(c).getInt(P_HOUR, 8); }
    public static int minute(Context c) { return prefs(c).getInt(P_MIN, 0); }

    public static class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            RollOver.run(c);
            show(c);
            schedule(c);
            Widgets.updateAll(c);
        }
    }

    private static PendingIntent pi(Context c) {
        Intent i = new Intent(c, Receiver.class);
        return PendingIntent.getBroadcast(c, REQ, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Sets (or clears) tomorrow's / today's brief. Not exact on purpose: within ~10 minutes is fine and saves battery. */
    public static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(pi(c));
        if (!on(c)) return;
        Calendar t = Calendar.getInstance();
        t.set(Calendar.HOUR_OF_DAY, hour(c));
        t.set(Calendar.MINUTE, minute(c));
        t.set(Calendar.SECOND, 0);
        t.set(Calendar.MILLISECOND, 0);
        if (t.getTimeInMillis() <= System.currentTimeMillis() + 30_000) t.add(Calendar.DAY_OF_MONTH, 1);
        try {
            am.setWindow(AlarmManager.RTC_WAKEUP, t.getTimeInMillis(), 10 * 60 * 1000L, pi(c));
        } catch (Exception ignored) { }
    }

    static void show(Context c) {
        if (!on(c) || !Notifier.canNotify(c)) return;
        Store s = Store.get(c);
        List<Reminder> rem = Today.remindersToday(s);
        List<Checklist.Item> todo = Checklist.open(s);
        if (rem.isEmpty() && todo.isEmpty()) return;   // nothing to say: stay quiet

        StringBuilder title = new StringBuilder("Today: ");
        if (!rem.isEmpty()) title.append(rem.size()).append(rem.size() == 1 ? " reminder" : " reminders");
        if (!rem.isEmpty() && !todo.isEmpty()) title.append(" · ");
        if (!todo.isEmpty()) title.append(todo.size()).append(" to do");

        StringBuilder big = new StringBuilder();
        java.text.DateFormat tf = DateFormat.getTimeFormat(c);
        int n = 0;
        for (Reminder r : rem) {
            if (n++ >= 6) break;
            big.append(r.hasTime ? tf.format(new Date(r.time)) : "Today").append("  ").append(r.title).append('\n');
        }
        if (!todo.isEmpty()) {
            if (big.length() > 0) big.append('\n');
            big.append("Still to do: ");
            for (int i = 0; i < Math.min(4, todo.size()); i++) big.append(i == 0 ? "" : ", ").append(todo.get(i).text);
            if (todo.size() > 4) big.append(" +").append(todo.size() - 4).append(" more");
        }
        String text = big.toString().trim();
        boolean locked = Lock.appLockOn(c);
        if (locked) text = "Open Note to see today's plan";

        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Morning brief", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("One quiet note each morning with today's plan");
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(c, MainActivity.class);
        open.setAction("note.quick.today");
        open.putExtra("quick", "today");
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent p = PendingIntent.getActivity(c, NOTIF_ID, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        @SuppressWarnings("deprecation")
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("☀ " + title)
                .setContentText(text.replace('\n', ' '))
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(p)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setColor(Ui.col(c, R.color.accent));
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
        nm.notify(NOTIF_ID, b.build());
    }
}
