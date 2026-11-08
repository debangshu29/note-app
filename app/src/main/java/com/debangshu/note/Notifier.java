package com.debangshu.note;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import java.text.DateFormat;
import java.util.Date;

/** Shows the reminder notification with "Done" and "Snooze 10 min" buttons. */
public final class Notifier {

    private Notifier() {}

    public static final String CHANNEL = "reminders";

    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Reminders found in your notes");
        ch.enableVibration(true);
        nm.createNotificationChannel(ch);
    }

    public static boolean canNotify(Context ctx) {
        return Build.VERSION.SDK_INT < 33
                || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressWarnings("deprecation")
    public static void show(Context ctx, Reminder r) {
        if (!canNotify(ctx)) return;
        ensureChannel(ctx);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Intent open = new Intent(ctx, EditorActivity.class);
        open.putExtra(EditorActivity.EXTRA_NOTE_ID, r.noteId);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(ctx, r.requestCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CHANNEL)
                : new Notification.Builder(ctx).setPriority(Notification.PRIORITY_HIGH)
                    .setDefaults(Notification.DEFAULT_ALL);

        String when = r.hasTime
                ? DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(r.time))
                : "Today";
        b.setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(r.title)
                .setContentText(when + " · " + r.sourceLine)
                .setStyle(new Notification.BigTextStyle().bigText(r.sourceLine))
                .setContentIntent(openPi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setColor(ctx.getColor(R.color.accent))
                .addAction(new Notification.Action.Builder(null, "Done",
                        actionIntent(ctx, r, ActionReceiver.ACTION_DONE)).build())
                .addAction(new Notification.Action.Builder(null, "Snooze 10 min",
                        actionIntent(ctx, r, ActionReceiver.ACTION_SNOOZE)).build());
        nm.notify(r.requestCode(), b.build());
    }

    private static PendingIntent actionIntent(Context ctx, Reminder r, String action) {
        Intent i = new Intent(ctx, ActionReceiver.class);
        i.setAction(action);
        i.putExtra("reminderId", r.id);
        int code = r.requestCode() * 31 + action.hashCode();
        return PendingIntent.getBroadcast(ctx, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static void cancel(Context ctx, Reminder r) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(r.requestCode());
    }

    public static String formatWhen(Context ctx, Reminder r) {
        Date d = new Date(r.time);
        String day = DateFormat.getDateInstance(DateFormat.MEDIUM).format(d);
        if (!r.hasTime) return day;
        return day + ", " + android.text.format.DateFormat.getTimeFormat(ctx).format(d);
    }
}
