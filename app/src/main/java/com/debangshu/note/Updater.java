package com.debangshu.note;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * In-app updates, Play-Store style, from this app's GitHub releases.
 *
 *  - checks github.com/debangshu29/note-app for a newer version (in the background about twice a day,
 *    and when the app is opened)
 *  - shows a popup "Update available"; "Update" downloads ONE file into Downloads with a progress bar,
 *    then "Update ready → Install" and Android updates the app in place: notes, reminders and settings stay
 *  - the downloaded file is deleted automatically after the update
 *
 * The ONLY internet use of the app is downloading these two files. Notes are never sent anywhere.
 */
public final class Updater {

    private Updater() {}

    // fast file server first; the GitHub release page is the backup (it can be slow / time out with 504)
    private static final String[] BASES = {
            "https://raw.githubusercontent.com/debangshu29/note-app/updates/",
            "https://github.com/debangshu29/note-app/releases/download/latest/"};
    private static final String APK_URL = BASES[0] + "Note.apk";

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
        // each address is tried twice; a temporary GitHub error (504 etc.) doesn't stop the check
        for (int attempt = 0; attempt < 2; attempt++) {
            for (String base : BASES) {
                Info i = fetchFrom(base + "version.json?t=" + System.currentTimeMillis());
                if (i != null) return i;
            }
            try { Thread.sleep(2000); } catch (InterruptedException e) { return null; }
        }
        return null;
    }

    private static Info fetchFrom(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
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
        if (updateWaiting(a)) { showReady(a); return; }
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

    private static final String UPDATE_FILE = "Note-update.apk";

    /** Older Androids (9 and below): the app's own folder is still visible in file managers. */
    private static File updateDir(Context ctx) {
        return ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
    }

    /**
     * Tap "Update":
     *   1. the new version downloads into your normal Downloads folder as ONE file (Note-update.apk),
     *      with a progress bar in the app
     *   2. "Update ready" → Install → tap the file → Android asks "Update this app?" → Update
     *   3. the file is deleted by itself once the new version is installed
     * The app never installs anything by itself (that's what made Play Protect suspicious).
     */
    public static void startUpdate(Activity a) {
        DownloadManager dm = (DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) { openInBrowser(a); return; }
        SharedPreferences p = prefs(a);
        Info latest = remembered(a);
        int target = latest == null ? currentVersionCode(a) + 1 : latest.versionCode;

        long old = p.getLong("dlId", -1);
        if (old != -1) {
            int status = downloadStatus(dm, old);
            boolean sameVersion = p.getInt("dlFor", 0) == target;
            if (sameVersion && status == DownloadManager.STATUS_SUCCESSFUL) { showReady(a); return; }
            if (sameVersion && (status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING
                    || status == DownloadManager.STATUS_PAUSED)) { showProgress(a, dm, old); return; }
            try { dm.remove(old); } catch (Exception ignored) { }   // older or failed download: delete it
        }
        deleteUpdateFiles(a);

        try {
            String name = latest == null ? "" : " " + latest.versionName;
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(APK_URL + "?v=" + target))
                    .setTitle("Note" + name + " update")
                    .setDescription("Tap to install")
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            if (Build.VERSION.SDK_INT >= 29) {
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, UPDATE_FILE);
            } else {
                r.setDestinationInExternalFilesDir(a, Environment.DIRECTORY_DOWNLOADS, UPDATE_FILE);
            }
            long id = dm.enqueue(r);
            p.edit().putLong("dlId", id).putInt("dlFor", target).apply();
            showProgress(a, dm, id);
        } catch (Exception e) {
            openInBrowser(a);
        }
    }

    /** Progress bar while downloading; turns into "Update ready" when done. */
    private static void showProgress(Activity a, DownloadManager dm, long id) {
        float dp = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (24 * dp), (int) (12 * dp), (int) (24 * dp), 0);
        ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setIndeterminate(true);
        TextView label = new TextView(a);
        label.setText("Starting…");
        label.setPadding(0, (int) (8 * dp), 0, 0);
        box.addView(bar);
        box.addView(label);
        AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle("Downloading update")
                .setView(box)
                .setNegativeButton("Hide", null)   // keeps downloading; the notification shows when it's done
                .show();

        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable poll = new Runnable() {
            @Override public void run() {
                if (!dlg.isShowing() || a.isFinishing()) return;
                long done = 0, total = -1;
                int status = -1;
                try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
                    if (c != null && c.moveToFirst()) {
                        status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    }
                } catch (Exception ignored) { }
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    dlg.dismiss();
                    showReady(a);
                    return;
                }
                if (status == DownloadManager.STATUS_FAILED || status == -1) {
                    dlg.dismiss();
                    try { dm.remove(id); } catch (Exception ignored) { }
                    prefs(a).edit().remove("dlId").remove("dlFor").apply();
                    new AlertDialog.Builder(a)
                            .setTitle("Download failed")
                            .setMessage("Check your internet connection and try again.")
                            .setPositiveButton("Try again", (d, w) -> startUpdate(a))
                            .setNegativeButton("Later", null)
                            .show();
                    return;
                }
                if (total > 0) {
                    bar.setIndeterminate(false);
                    bar.setProgress((int) (done * 100 / total));
                    label.setText(String.format(java.util.Locale.ROOT, "%.1f of %.1f MB", done / 1048576.0, total / 1048576.0));
                } else {
                    label.setText("Waiting for the connection…");
                }
                h.postDelayed(this, 400);
            }
        };
        h.post(poll);
    }

    /** The update is downloaded: one button opens Downloads, where the file is at the top. */
    public static void showReady(Activity a) {
        if (a.isFinishing()) return;
        Info latest = remembered(a);
        String name = latest == null ? "" : " " + latest.versionName;
        new AlertDialog.Builder(a)
                .setTitle("Update ready")
                .setMessage("Note" + name + " is downloaded.\n\n"
                        + "Tap Install, then tap “" + UPDATE_FILE + "” at the top of your Downloads and choose Update.\n\n"
                        + "Your notes stay, and the file is deleted by itself after the update.")
                .setPositiveButton("Install", (d, w) -> openDownloads(a))
                .setNegativeButton("Later", null)
                .show();
    }

    private static void openDownloads(Activity a) {
        try {
            a.startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Toast.makeText(a, "Tap " + UPDATE_FILE + ", then Update", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(a, "Open the Files app → Downloads → " + UPDATE_FILE, Toast.LENGTH_LONG).show();
        }
    }

    /** True when an update file is downloaded and waiting to be installed. */
    private static boolean updateWaiting(Context ctx) {
        SharedPreferences p = prefs(ctx);
        long id = p.getLong("dlId", -1);
        if (id == -1 || p.getInt("dlFor", 0) <= currentVersionCode(ctx)) return false;
        DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
        return dm != null && downloadStatus(dm, id) == DownloadManager.STATUS_SUCCESSFUL;
    }

    private static int downloadStatus(DownloadManager dm, long id) {
        try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
            if (c != null && c.moveToFirst()) return c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
        } catch (Exception ignored) { }
        return -1;
    }

    private static void deleteUpdateFiles(Context ctx) {
        File dir = updateDir(ctx);
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return;
        for (File f : files) if (f.getName().startsWith("Note-update")) f.delete();
    }

    /**
     * Called when the app starts and right after it was updated: once the downloaded version
     * is installed, its file is no longer needed, so delete it (also from Downloads).
     */
    public static void cleanUpAfterUpdate(Context ctx) {
        SharedPreferences p = prefs(ctx);
        long id = p.getLong("dlId", -1);
        int forCode = p.getInt("dlFor", 0);
        if (id == -1 && forCode == 0) return;
        if (currentVersionCode(ctx) >= forCode) {
            DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null && id != -1) {
                try { dm.remove(id); } catch (Exception ignored) { }
            }
            deleteUpdateFiles(ctx);
            p.edit().remove("dlId").remove("dlFor").apply();
        }
    }

    /** Fallback when the phone has no download manager. */
    private static void openInBrowser(Activity a) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(APK_URL));
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            a.startActivity(i);
            Toast.makeText(a, "When the download finishes, tap it and choose Update", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(a, "No browser found to download the update", Toast.LENGTH_LONG).show();
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
