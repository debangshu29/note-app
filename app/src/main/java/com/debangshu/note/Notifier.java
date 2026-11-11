package com.debangshu.note;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

import java.text.DateFormat;
import java.util.Date;

/**
 * Two kinds of alerts, so nothing nags:
 *   - reminders: ONE notification with one sound (Done / In 30 min). No repeat, no calendar alert.
 *   - alarms:    rings with the alarm sound until you press Stop or Snooze, and gives up by itself after 10 min.
 */
public final class Notifier {

    private Notifier() {}

    public static final String CHANNEL = "reminders";
    public static final String ALARM_CHANNEL = "alarms";
    public static final int SNOOZE_REMINDER_MIN = 30;
    public static final int SNOOZE_ALARM_MIN = 5;

    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("One notification for each reminder found in your notes");
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(ALARM_CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(ALARM_CHANNEL, "Alarms", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Notes like \"wake me up at 6 am\" ring like an alarm");
            Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (sound == null) sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            ch.setSound(sound, new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 600, 400, 600, 400, 600});
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }
    }

    public static boolean canNotify(Context ctx) {
        return Build.VERSION.SDK_INT < 33
                || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Android 14+ asks the user before an app may show a full-screen alarm. */
    public static boolean canFullScreen(Context ctx) {
        if (Build.VERSION.SDK_INT < 34) return true;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        return nm == null || nm.canUseFullScreenIntent();
    }

    private static PendingIntent openNote(Context ctx, Reminder r) {
        Intent open = new Intent(ctx, EditorActivity.class);
        open.putExtra(EditorActivity.EXTRA_NOTE_ID, r.noteId);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(ctx, r.requestCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @SuppressWarnings("deprecation")
    private static Notification.Builder builder(Context ctx, String channel, boolean alarm) {
        if (Build.VERSION.SDK_INT >= 26) return new Notification.Builder(ctx, channel);
        Notification.Builder b = new Notification.Builder(ctx).setPriority(Notification.PRIORITY_HIGH);
        if (alarm) b.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), android.media.AudioManager.STREAM_ALARM)
                .setVibrate(new long[]{0, 600, 400, 600});
        else b.setDefaults(Notification.DEFAULT_ALL);
        return b;
    }

    /** A normal reminder: one gentle notification. */
    public static void show(Context ctx, Reminder r) {
        if (!canNotify(ctx)) return;
        ensureChannel(ctx);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        String when = r.hasTime
                ? android.text.format.DateFormat.getTimeFormat(ctx).format(new Date(r.time))
                : "Today";
        Notification.Builder b = builder(ctx, CHANNEL, false)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(r.title)
                .setContentText(when + " · " + r.sourceLine)
                .setSubText(r.repeats() ? "🔁 " + DateParser.describe(r.repeat) : null)
                .setStyle(new Notification.BigTextStyle().bigText(r.sourceLine))
                .setContentIntent(openNote(ctx, r))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setColor(Ui.col(ctx, R.color.accent))
                .addAction(new Notification.Action.Builder(null, "Done",
                        actionIntent(ctx, r, ActionReceiver.ACTION_DONE, 0)).build())
                .addAction(new Notification.Action.Builder(null, "In " + SNOOZE_REMINDER_MIN + " min",
                        actionIntent(ctx, r, ActionReceiver.ACTION_SNOOZE, SNOOZE_REMINDER_MIN)).build());
        nm.notify(r.requestCode(), b.build());
    }

    /** An alarm: rings until Stop / Snooze, full screen when the phone allows it, stops itself after 10 minutes. */
    public static void showAlarm(Context ctx, Reminder r) {
        if (!canNotify(ctx)) return;
        ensureChannel(ctx);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Intent full = new Intent(ctx, AlarmActivity.class);
        full.putExtra("reminderId", r.id);
        full.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        PendingIntent fullPi = PendingIntent.getActivity(ctx, r.requestCode() + 3, full,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String time = android.text.format.DateFormat.getTimeFormat(ctx).format(new Date(r.time));
        Notification.Builder b = builder(ctx, ALARM_CHANNEL, true)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("⏰ " + r.title)
                .setContentText(time + " · " + r.sourceLine)
                .setContentIntent(fullPi)
                .setFullScreenIntent(fullPi, true)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setColor(Ui.col(ctx, R.color.accent))
                .addAction(new Notification.Action.Builder(null, "Stop",
                        actionIntent(ctx, r, ActionReceiver.ACTION_DONE, 0)).build())
                .addAction(new Notification.Action.Builder(null, "Snooze " + SNOOZE_ALARM_MIN + " min",
                        actionIntent(ctx, r, ActionReceiver.ACTION_SNOOZE, SNOOZE_ALARM_MIN)).build());
        if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(10 * 60 * 1000L);
        Notification n = b.build();
        n.flags |= Notification.FLAG_INSISTENT;   // keep ringing until the user reacts
        nm.notify(r.requestCode(), n);
    }

    static PendingIntent actionIntent(Context ctx, Reminder r, String action, int minutes) {
        Intent i = new Intent(ctx, ActionReceiver.class);
        i.setAction(action);
        i.putExtra("reminderId", r.id);
        i.putExtra("minutes", minutes);
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
