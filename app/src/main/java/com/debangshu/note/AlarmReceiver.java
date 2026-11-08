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
        Notifier.show(ctx, r);
    }
}
