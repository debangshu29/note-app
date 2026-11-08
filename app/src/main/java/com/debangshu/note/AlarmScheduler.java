package com.debangshu.note;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Sets a phone alarm that fires {@link AlarmReceiver} at the reminder time. */
public final class AlarmScheduler {

    private AlarmScheduler() {}

    private static PendingIntent intentFor(Context ctx, Reminder r) {
        Intent i = new Intent(ctx, AlarmReceiver.class);
        i.setAction("com.debangshu.note.ALARM");
        i.putExtra("reminderId", r.id);
        return PendingIntent.getBroadcast(ctx, r.requestCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static void schedule(Context ctx, Reminder r) {
        if (r.done || r.ignored || r.time <= System.currentTimeMillis()) return;
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = intentFor(ctx, r);
        try {
            boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.time, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.time, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.time, pi);
        }
    }

    public static void cancel(Context ctx, Reminder r) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(intentFor(ctx, r));
    }
}
