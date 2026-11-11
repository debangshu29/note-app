package com.debangshu.note;

import android.app.Activity;
import android.app.KeyguardManager;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.Date;

/** Full-screen alarm (shown over the lock screen): big Stop and Snooze buttons. */
public class AlarmActivity extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(Ui.wrap(base)); }

    private long reminderId;

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager km = getSystemService(KeyguardManager.class);
            if (km != null) km.requestDismissKeyguard(this, null);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Ui.install(this);
        setContentView(R.layout.activity_alarm);
        Ui.bars(this, 0xFF3B2FCB, false);

        reminderId = getIntent().getLongExtra("reminderId", -1);
        Reminder r = Store.get(this).reminder(reminderId);
        if (r == null) { finish(); return; }

        ((TextView) findViewById(R.id.alarm_time)).setText(
                android.text.format.DateFormat.getTimeFormat(this).format(new Date(r.repeats() ? System.currentTimeMillis() : r.time)));
        ((TextView) findViewById(R.id.alarm_title)).setText(r.title);
        ((TextView) findViewById(R.id.alarm_line)).setText(r.sourceLine);
        ((TextView) findViewById(R.id.alarm_snooze)).setText("Snooze " + Notifier.SNOOZE_ALARM_MIN + " min");

        findViewById(R.id.alarm_stop).setOnClickListener(v -> {
            ActionReceiver.handle(this, ActionReceiver.ACTION_DONE, reminderId, 0);
            finish();
        });
        findViewById(R.id.alarm_snooze).setOnClickListener(v -> {
            ActionReceiver.handle(this, ActionReceiver.ACTION_SNOOZE, reminderId, Notifier.SNOOZE_ALARM_MIN);
            finish();
        });
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // back = snooze, so an alarm is never lost by accident
        ActionReceiver.handle(this, ActionReceiver.ACTION_SNOOZE, reminderId, Notifier.SNOOZE_ALARM_MIN);
        super.onBackPressed();
    }
}
