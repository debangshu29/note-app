package com.debangshu.note;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Handles the "Done" and "Snooze" buttons on a reminder notification. */
public class ActionReceiver extends BroadcastReceiver {
    public static final String ACTION_DONE = "com.debangshu.note.DONE";
    public static final String ACTION_SNOOZE = "com.debangshu.note.SNOOZE";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        long id = intent.getLongExtra("reminderId", -1);
        Store s = Store.get(ctx);
        Reminder r = s.reminder(id);
        if (r == null) return;
        Notifier.cancel(ctx, r);
        if (ACTION_DONE.equals(intent.getAction())) {
            ReminderSync.setDone(ctx, r, true);
        } else if (ACTION_SNOOZE.equals(intent.getAction())) {
            r.time = System.currentTimeMillis() + 10 * 60 * 1000L;
            r.hasTime = true;
            s.saveReminder(r);
            AlarmScheduler.schedule(ctx, r);
        }
    }
}
