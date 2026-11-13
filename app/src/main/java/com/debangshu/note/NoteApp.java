package com.debangshu.note;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/** Knows when the whole app goes to the background (for the app lock timer). */
public class NoteApp extends Application {

    private int started = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        try { L.init(this); } catch (Exception ignored) { }   // the app language, for notifications and widgets too
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity a) {
                if (started++ == 0) Lock.cameToFront();
            }
            @Override public void onActivityStopped(Activity a) {
                if (--started <= 0) { started = 0; Lock.wentToBackground(); }
            }
            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityResumed(Activity a) { }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
    }
}
