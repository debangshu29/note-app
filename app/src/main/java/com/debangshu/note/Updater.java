package com.debangshu.note;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * In-app updates, Play-Store style, from this app's GitHub releases.
 *
 *  - checks github.com/debangshu29/note-app for a newer version (in the background about twice a day,
 *    and when the app is opened)
 *  - shows a popup "Update available", downloads the new APK and hands it to Android's installer
 *  - the app is updated in place: notes, reminders and settings stay
 *
 * The ONLY internet use of the app is downloading these two files. Notes are never sent anywhere.
 */
public final class Updater {

    private Updater() {}

    private static final String BASE = "https://github.com/debangshu29/note-app/releases/download/latest/";
    private static final String VERSION_URL = BASE + "version.json";
    private static final String APK_URL = BASE + "Note.apk";

    private static final String CHANNEL = "updates";
    private static final int NOTIF_ID = 777001;
    private static final int JOB_ID = 4242;
    private static final long CHECK_EVERY = 6L * 60 * 60 * 1000;     // when opening the app
    private static final long LATER_SNOOZE = 24L * 60 * 60 * 1000;  // "Later" hides the popup for a day

    public static final class Info {
        public int versionCode;
        public String versionName;
        public String notes;
    }

    // ------------------------------------------------------------------ checking

    public static int currentVersionCode(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    public static String currentVersionName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** Network call: returns the newest published version, or null if offline / not found. Run off the UI thread. */
    public static Info fetchLatest() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(VERSION_URL + "?t=" + System.currentTimeMillis()).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            c.setUseCaches(false);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                JSONObject o = new JSONObject(buf.toString("UTF-8"));
                Info i = new Info();
                i.versionCode = o.getInt("versionCode");
                i.versionName = o.optString("versionName", String.valueOf(i.versionCode));
                i.notes = o.optString("notes", "");
                return i;
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("updates", Context.MODE_PRIVATE);
    }

    private static void remember(Context ctx, Info i) {
        prefs(ctx).edit()
                .putInt("latestCode", i.versionCode)
                .putString("latestName", i.versionName)
                .putString("latestNotes", i.notes)
                .putLong("lastCheck", System.currentTimeMillis())
                .apply();
    }

    private static Info remembered(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (!p.contains("latestCode")) return null;
        Info i = new Info();
        i.versionCode = p.getInt("latestCode", 0);
        i.versionName = p.getString("latestName", "");
        i.notes = p.getString("latestNotes", "");
        return i;
    }

    // ------------------------------------------------------------------ background check

    /** Schedules a background check about every 12 hours (only when online). */
    public static void scheduleBackgroundChecks(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        if (!Store.get(ctx).setting(Store.S_AUTO_UPDATE)) { js.cancel(JOB_ID); return; }
        for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB_ID) return; // already scheduled
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, UpdateJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(12L * 60 * 60 * 1000)
                .setPersisted(true)
                .build();
        try { js.schedule(job); } catch (Exception ignored) { }
    }

    /** Runs in the background job: if there is a new version, show ONE notification for it. */
    static void backgroundCheck(Context ctx) {
        Info i = fetchLatest();
        if (i == null) return;
        remember(ctx, i);
        if (i.versionCode <= currentVersionCode(ctx)) return;
        if (prefs(ctx).getInt("notifiedCode", 0) >= i.versionCode) return; // never nag twice
        prefs(ctx).edit().putInt("notifiedCode", i.versionCode).apply();
        showNotification(ctx, i);
    }

    private static void showNotification(Context ctx, Info i) {
        if (!Notifier.canNotify(ctx)) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Tells you when a new version of Note is ready");
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(ctx, MainActivity.class);
        open.putExtra("showUpdate", true);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, NOTIF_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        @SuppressWarnings("deprecation")
        android.app.Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(ctx, CHANNEL)
                : new android.app.Notification.Builder(ctx);
        b.setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Note " + i.versionName + " is ready")
                .setContentText("Tap to update — your notes stay as they are")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setColor(ctx.getColor(R.color.accent));
        nm.notify(NOTIF_ID, b.build());
    }

    // ------------------------------------------------------------------ popup when the app is opened

    /** Called when the app opens: uses the last known result, checks again if it's been a while. */
    public static void maybePrompt(Activity a, boolean force) {
        Store s = Store.get(a);
        if (!force && !s.setting(Store.S_AUTO_UPDATE)) return;
        Info known = remembered(a);
        if (known != null && known.versionCode > currentVersionCode(a)) {
            showPopup(a, known, force);
            if (!force) return;
        }
        long last = prefs(a).getLong("lastCheck", 0);
        if (!force && System.currentTimeMillis() - last < CHECK_EVERY) return;
        new Thread(() -> {
            Info i = fetchLatest();
            if (i != null) remember(a, i);
            a.runOnUiThread(() -> {
                if (a.isFinishing()) return;
                if (i == null) {
                    if (force) Toast.makeText(a, "Couldn't check — are you online?", Toast.LENGTH_LONG).show();
                } else if (i.versionCode > currentVersionCode(a)) {
                    showPopup(a, i, force);
                } else if (force) {
                    Toast.makeText(a, "You have the latest version (" + currentVersionName(a) + ")", Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private static boolean popupShowing = false;

    private static void showPopup(Activity a, Info i, boolean force) {
        if (popupShowing || a.isFinishing()) return;
        SharedPreferences p = prefs(a);
        if (!force && p.getInt("laterCode", 0) == i.versionCode
                && System.currentTimeMillis() < p.getLong("laterUntil", 0)) return;
        popupShowing = true;
        String msg = "Version " + i.versionName + " is ready (you have " + currentVersionName(a) + ").";
        if (i.notes != null && !i.notes.trim().isEmpty()) msg += "\n\nWhat's new:\n" + i.notes.trim();
        msg += "\n\nYour notes, reminders and settings stay as they are.";
        new AlertDialog.Builder(a)
                .setTitle("Update available")
                .setMessage(msg)
                .setPositiveButton("Update", (d, w) -> startUpdate(a))
                .setNegativeButton("Later", (d, w) -> p.edit()
                        .putInt("laterCode", i.versionCode)
                        .putLong("laterUntil", System.currentTimeMillis() + LATER_SNOOZE).apply())
                .setOnDismissListener(d -> popupShowing = false)
                .show();
    }

    // ------------------------------------------------------------------ download + install

    public static void startUpdate(Activity a) {
        // Android asks once: "Allow Note to install apps?" (needed to update itself)
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(a)
                    .setTitle("One-time permission")
                    .setMessage("To update itself, Note needs “Install unknown apps”.\n\n"
                            + "On the next screen switch on “Allow from this source”, then come back and tap Update again.")
                    .setPositiveButton("Open settings", (d, w) -> {
                        try {
                            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + a.getPackageName())));
                        } catch (Exception e) {
                            Toast.makeText(a, "Please allow it in phone Settings → Apps → Note", Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        int pad = (int) (24 * a.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        TextView label = new TextView(a);
        label.setText("Starting download…");
        box.addView(bar);
        box.addView(label);
        AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle("Downloading update")
                .setView(box)
                .setCancelable(false)
                .show();

        new Thread(() -> {
            File apk = new File(a.getCacheDir(), "update.apk");
            String error = null;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(APK_URL + "?t=" + System.currentTimeMillis()).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                if (c.getResponseCode() != 200) throw new Exception("server said " + c.getResponseCode());
                int total = c.getContentLength();
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                    byte[] b = new byte[16384];
                    int n;
                    long done = 0;
                    while ((n = in.read(b)) > 0) {
                        out.write(b, 0, n);
                        done += n;
                        final int pct = total > 0 ? (int) (done * 100 / total) : 0;
                        final long kb = done / 1024;
                        a.runOnUiThread(() -> { bar.setProgress(pct); label.setText(kb + " KB"); });
                    }
                }
                install(a, apk);
            } catch (Exception e) {
                error = e.getMessage();
            } finally {
                if (c != null) c.disconnect();
            }
            final String err = error;
            a.runOnUiThread(() -> {
                try { dlg.dismiss(); } catch (Exception ignored) { }
                if (err != null) Toast.makeText(a, "Update failed: " + err, Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    /** Hands the APK to Android's installer (it shows its own "Update this app?" screen). */
    private static void install(Context ctx, File apk) throws Exception {
        PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            // after the first in-app update, Android 12+ can update silently without asking again
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (OutputStream out = session.openWrite("note.apk", 0, apk.length());
                 InputStream in = new FileInputStream(apk)) {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                session.fsync(out);
            }
            Intent done = new Intent(ctx, InstallReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, id, done, flags);
            session.commit(pi.getIntentSender());
        }
    }

    /** After an update: show "Updated to x.y" once. */
    public static void showWhatsNewOnce(Activity a) {
        SharedPreferences p = prefs(a);
        int cur = currentVersionCode(a);
        int seen = p.getInt("seenCode", 0);
        p.edit().putInt("seenCode", cur).apply();
        if (seen == 0 || seen >= cur) return;
        Info known = remembered(a);
        String notes = known != null && known.versionCode == cur ? known.notes : "";
        if (notes.trim().isEmpty()) {
            Toast.makeText(a, "Updated to Note " + currentVersionName(a), Toast.LENGTH_LONG).show();
        } else {
            new AlertDialog.Builder(a)
                    .setTitle("Updated to " + currentVersionName(a))
                    .setMessage("What's new:\n" + notes.trim())
                    .setPositiveButton("OK", null)
                    .show();
        }
        NotificationManager nm = (NotificationManager) a.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIF_ID);
    }
}
