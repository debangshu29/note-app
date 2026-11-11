package com.debangshu.note;

import android.app.Activity;
import android.os.Bundle;

/** Covers the app until the PIN or fingerprint is given. Back leaves the app. */
public class LockActivity extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(Ui.wrap(base)); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.install(this);
        setContentView(Lock.pad(this, Lock.VERIFY, "Note is locked", false, ok -> {
            if (!ok) return;
            Lock.markUnlocked();
            finish();
            overridePendingTransition(0, 0);
        }));
    }

    @Override
    protected void onDestroy() {
        Lock.screenShowing = false;
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        moveTaskToBack(true);
    }
}
