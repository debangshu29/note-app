package com.debangshu.note;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fired by the phone alarm at the reminder time. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        long id = intent.getLongExtra("reminderId", -1);
        Reminder r = Store.get(ctx).reminder(id);
        if (r == null || r.done || r.ignored) return;
        if (r.kind == ReminderKind.ALARM) Notifier.showAlarm(ctx, r);
        else Notifier.show(ctx, r);
        if (r.repeats()) ReminderSync.advance(ctx, r);   // arm the next one right away
    }
}
