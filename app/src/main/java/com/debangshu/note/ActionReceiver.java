package com.debangshu.note;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Handles the "Done / Stop" and "Snooze" buttons on notifications and the alarm screen. */
public class ActionReceiver extends BroadcastReceiver {
    public static final String ACTION_DONE = "com.debangshu.note.DONE";
    public static final String ACTION_SNOOZE = "com.debangshu.note.SNOOZE";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        handle(ctx, intent.getAction(), intent.getLongExtra("reminderId", -1), intent.getIntExtra("minutes", 10));
    }

    public static void handle(Context ctx, String action, long reminderId, int minutes) {
        Store s = Store.get(ctx);
        Reminder r = s.reminder(reminderId);
        if (r == null) return;
        Notifier.cancel(ctx, r);
        if (ACTION_DONE.equals(action)) {
            ReminderSync.setDone(ctx, r, true);
        } else if (ACTION_SNOOZE.equals(action)) {
            r.time = System.currentTimeMillis() + Math.max(1, minutes) * 60 * 1000L;
            r.hasTime = true;
            s.saveReminder(r);
            AlarmScheduler.schedule(ctx, r);
        }
    }
}
