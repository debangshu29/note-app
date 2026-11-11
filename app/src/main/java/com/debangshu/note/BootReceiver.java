package com.debangshu.note;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Phone alarms are cleared when the phone restarts; this puts them back. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        ReminderSync.rescheduleAll(ctx);
        Brief.schedule(ctx);
        Habits.schedule(ctx);
        Widgets.updateAll(ctx);
        // right after an update: delete the downloaded installer file
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) Updater.cleanUpAfterUpdate(ctx);
    }
}
