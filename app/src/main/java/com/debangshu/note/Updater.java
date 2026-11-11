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
import java.util.List;
import java.util.ArrayList;

/**
 * In-app updates, Play-Store style, from this app's GitHub releases.
 *
 *  - checks github.com/debangshu29/note-app for a newer version (in the background about twice a day,
 *    and when the app is opened)
 *  - shows a popup "Update available" with what's new, how to update, and a Help guide
 *  - "Update" downloads ONE file (Download/Note-update.apk) with a progress bar; "Install" → tap it → Update
 *  - after updating, the file is deleted automatically (Android lets Note delete files it downloaded itself)
 *  - backup: download in the browser (then Note offers a picker to delete that file)
 *
 * The ONLY internet use of the app is downloading these two files. Notes are never sent anywhere.
 */
public final class Updater {

    private Updater() {}

    // fast file server first; the GitHub release page is the backup (it can be slow / time out with 504)
    private static final String[] BASES = {
            "https://raw.githubusercontent.com/debangshu29/note-app/updates/",
            "https://github.com/debangshu29/note-app/releases/download/latest/"};
    /** The direct download link (always the newest version). */
    public static final String APK_URL = BASES[0] + "Note.apk";
    public static final int REQ_CLEANUP = 7301;

    private static final String CHANNEL = "updates";
    private static final int NOTIF_ID = 777001;
    private static final int JOB_ID = 4242;
    private static final long CHECK_EVERY = 15L * 60 * 1000;        // when opening the app (at most every 15 min)
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

    /** Called every time the home screen opens: uses the last result, and checks again if it's been 15 min. */
    public static void maybePrompt(Activity a, boolean force) {
        Store s = Store.get(a);
        if (!force && !s.setting(Store.S_AUTO_UPDATE)) return;
        if (updateWaiting(a)) { showReady(a); return; }
        Info known = remembered(a);
        if (!force && known != null && known.versionCode > currentVersionCode(a)) {
            showPopup(a, known, false);
            return;
        }
        long last = prefs(a).getLong("lastCheck", 0);
        if (!force && System.currentTimeMillis() - last < CHECK_EVERY) return;
        prefs(a).edit().putLong("lastCheck", System.currentTimeMillis()).apply();
        if (force) Toast.makeText(a, "Checking for updates…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            Info i = fetchLatest();
            if (i != null) remember(a, i);
            a.runOnUiThread(() -> {
                if (a.isFinishing()) return;
                if (i == null) {
                    if (force) Toast.makeText(a, "Couldn't check — are you online? Try again in a minute.", Toast.LENGTH_LONG).show();
                } else if (i.versionCode > currentVersionCode(a)) {
                    showPopup(a, i, force);
                } else if (force) {
                    Toast.makeText(a, "You have the latest version (" + currentVersionName(a) + ")", Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private static boolean popupShowing = false;

    /** "Update available": what's new + how to update + Download / Later / Help. */
    private static void showPopup(Activity a, Info i, boolean force) {
        if (popupShowing || a.isFinishing()) return;
        SharedPreferences p = prefs(a);
        if (!force && p.getInt("laterCode", 0) == i.versionCode
                && System.currentTimeMillis() < p.getLong("laterUntil", 0)) return;
        popupShowing = true;
        StringBuilder msg = new StringBuilder();
        msg.append("Version ").append(i.versionName).append(" is ready (you have ").append(currentVersionName(a)).append(").");
        if (i.notes != null && !i.notes.trim().isEmpty()) msg.append("\n\nWhat's new:\n").append(i.notes.trim());
        msg.append("\n\nHow to update:\n")
           .append("1. Tap Update — Note downloads it (you'll see a progress bar).\n")
           .append("2. Tap Install, then tap Note-update.apk at the top of Downloads and choose Update.\n")
           .append("3. Done. Your notes stay, and the downloaded file is deleted automatically.\n\n")
           .append("Trouble? Tap Help.");
        new Ui.Popup(a)
                .setTitle("Update available")
                .setMessage(msg.toString())
                .setPositiveButton("Update", (d, w) -> startUpdate(a))
                .setNegativeButton("Later", (d, w) -> p.edit()
                        .putInt("laterCode", i.versionCode)
                        .putLong("laterUntil", System.currentTimeMillis() + LATER_SNOOZE).apply())
                .setNeutralButton("Help", (d, w) -> showHelp(a))
                .setOnDismissListener(d -> popupShowing = false)
                .show();
    }

    /** What to do if something goes wrong while downloading or installing. */
    public static void showHelp(Activity a) {
        String msg =
                "• Download fails or says “504” / error\n"
                + "   GitHub is busy for a moment. Wait a minute and tap Update again.\n\n"
                + "• Can't find the file\n"
                + "   Files app → Downloads → Note-update.apk (newest at the top).\n\n"
                + "• “Install unknown apps” / install blocked\n"
                + "   Allow it for Files (or Downloads) when Android asks — it's needed once.\n\n"
                + "• Play Protect warning\n"
                + "   Tap More details → Install anyway.\n\n"
                + "• “App not installed”\n"
                + "   An older file was opened. Tap Update again: Note replaces the old file.\n\n"
                + "• Still stuck\n"
                + "   Download in the browser instead, or copy the link and open it on your computer.\n"
                + "   (Files downloaded by the browser can't be deleted by Note automatically — use "
                + "Settings → Delete old update files.)";
        new Ui.Popup(a)
                .setTitle("Update help")
                .setMessage(msg)
                .setPositiveButton("Update again", (d, w) -> startUpdate(a))
                .setNeutralButton("Use browser", (d, w) -> downloadInBrowser(a))
                .setNegativeButton("Close", null)
                .show();
    }

    public static void copyLink(Activity a) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("Note download link", APK_URL));
        Toast.makeText(a, "Link copied", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ download (browser) + clean-up

    /** Backup way: download in the browser. Then Note can't delete the file by itself, so it offers the picker. */
    public static void downloadInBrowser(Activity a) {
        Info latest = remembered(a);
        int target = latest == null ? currentVersionCode(a) + 1 : latest.versionCode;
        prefs(a).edit().putInt("cleanupFor", target).apply();
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(APK_URL + "?v=" + target));
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            a.startActivity(i);
            Toast.makeText(a, "When the download finishes, tap Note.apk and choose Update", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(a, "No browser found — use Copy link", Toast.LENGTH_LONG).show();
        }
    }

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
        prefs(a).edit().remove("cleanupFor").apply();
        DownloadManager dm = (DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) { downloadInBrowser(a); return; }
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
            downloadInBrowser(a);
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
        AlertDialog dlg = new Ui.Popup(a)
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
                    new Ui.Popup(a)
                            .setTitle("Download failed")
                            .setMessage("GitHub may be busy for a moment, or the internet dropped. Try again in a minute.")
                            .setPositiveButton("Try again", (d, w) -> startUpdate(a))
                            .setNeutralButton("Help", (d, w) -> showHelp(a))
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
        new Ui.Popup(a)
                .setTitle("Update ready")
                .setMessage("Note" + name + " is downloaded.\n\n"
                        + "Tap Install: the Files app opens your Download folder. Tap “" + UPDATE_FILE
                        + "” and choose Update.\n(Or pull down your notifications and tap “Note" + name + " update”.)\n\n"
                        + "Your notes stay, and Note deletes this file by itself after the update.")
                .setPositiveButton("Install", (d, w) -> openDownloads(a))
                .setNegativeButton("Later", null)
                .show();
    }

    /**
     * Opens the phone's own Files app right at the folder with the update file
     * (not "view downloads", which some phones send to Google Drive, where installing doesn't work).
     */
    private static void openDownloads(Activity a) {
        String docId = Build.VERSION.SDK_INT >= 29
                ? "primary:" + Environment.DIRECTORY_DOWNLOADS
                : "primary:Android/data/" + a.getPackageName() + "/files/" + Environment.DIRECTORY_DOWNLOADS;
        Uri dir = android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId);
        String[] filesApps = {"com.google.android.documentsui", "com.android.documentsui", null};
        for (String pkg : filesApps) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(dir, android.provider.DocumentsContract.Document.MIME_TYPE_DIR);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (pkg != null) i.setPackage(pkg);
                a.startActivity(i);
                Toast.makeText(a, "Tap " + UPDATE_FILE + ", then Update", Toast.LENGTH_LONG).show();
                return;
            } catch (Exception ignored) { }
        }
        new Ui.Popup(a)
                .setTitle("Install the update")
                .setMessage("Open the Files app → Downloads → tap " + UPDATE_FILE + " → Update.\n\n"
                        + "Or pull down your notifications and tap “Note update”.")
                .setPositiveButton("OK", null)
                .show();
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
     * Android doesn't let an app delete files that the browser downloaded, unless you point at them.
     * This opens your Downloads: select the Note.apk files there and Note deletes them.
     */
    public static void pickOldFilesToDelete(Activity a) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/vnd.android.package-archive");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        if (Build.VERSION.SDK_INT >= 26) {
            i.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                    android.provider.DocumentsContract.buildDocumentUri(
                            "com.android.externalstorage.documents", "primary:Download"));
        }
        try {
            a.startActivityForResult(i, REQ_CLEANUP);
            Toast.makeText(a, "Long-press to select every old Note….apk, then tap Select", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(a, "Open the Files app → Downloads and delete the Note.apk files", Toast.LENGTH_LONG).show();
        }
    }

    /** Call from onActivityResult: deletes the picked APK files. */
    public static void onCleanupResult(Activity a, int req, int res, Intent data) {
        if (req != REQ_CLEANUP || res != Activity.RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int k = 0; k < data.getClipData().getItemCount(); k++) uris.add(data.getClipData().getItemAt(k).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        int deleted = 0, failed = 0;
        for (Uri u : uris) {
            try {
                if (android.provider.DocumentsContract.deleteDocument(a.getContentResolver(), u)) deleted++;
                else failed++;
            } catch (Exception e) {
                failed++;
            }
        }
        prefs(a).edit().remove("cleanupFor").apply();
        String msg = deleted == 1 ? "1 file deleted" : deleted + " files deleted";
        if (failed > 0) msg += " · " + failed + " couldn't be deleted — remove them in the Files app";
        Toast.makeText(a, msg, Toast.LENGTH_LONG).show();
    }

    /**
     * Runs right after an update (and whenever Note starts): the file Note downloaded is deleted
     * automatically, no questions asked. Older leftovers in Note's own folder go too.
     */
    public static void cleanUpAfterUpdate(Context ctx) {
        SharedPreferences p = prefs(ctx);
        long id = p.getLong("dlId", -1);
        int forCode = p.getInt("dlFor", 0);
        if (id != -1 && currentVersionCode(ctx) >= forCode) {
            DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                try { dm.remove(id); } catch (Exception ignored) { }   // also deletes Download/Note-update.apk
            }
            p.edit().remove("dlId").remove("dlFor").apply();
        }
        deleteUpdateFiles(ctx);
    }

    /** After an update: show what's new, then offer to delete the downloaded file. */
    public static void showWhatsNewOnce(Activity a) {
        SharedPreferences p = prefs(a);
        int cur = currentVersionCode(a);
        int seen = p.getInt("seenCode", 0);
        p.edit().putInt("seenCode", cur).apply();
        NotificationManager nm = (NotificationManager) a.getSystemService(Context.NOTIFICATION_SERVICE);
        if (seen == 0 || seen >= cur) return;
        if (nm != null) nm.cancel(NOTIF_ID);

        Info known = remembered(a);
        String notes = known != null && known.versionCode == cur ? known.notes : "";
        boolean offerCleanup = p.getInt("cleanupFor", 0) > 0 && p.getInt("cleanupFor", 0) <= cur;
        StringBuilder msg = new StringBuilder();
        if (!notes.trim().isEmpty()) msg.append("What's new:\n").append(notes.trim());
        if (offerCleanup) {
            if (msg.length() > 0) msg.append("\n\n");
            msg.append("The downloaded Note.apk isn't needed any more. Tap “Delete file”, select Note.apk "
                    + "(and any Note (1).apk…) in Downloads, and tap Select.");
        }
        if (!offerCleanup) {
            if (msg.length() > 0) msg.append("\n\n");
            msg.append("The update file Note downloaded has been deleted automatically. "
                    + "If your browser or Files app saved older Note.apk files, tap “Clean up old files” to remove them.");
        }
        AlertDialog.Builder b = new Ui.Popup(a)
                .setTitle("Updated to " + currentVersionName(a))
                .setMessage(msg.toString());
        if (offerCleanup) {
            b.setPositiveButton("Delete file", (d, w) -> pickOldFilesToDelete(a))
             .setNegativeButton("Not now", (d, w) -> p.edit().remove("cleanupFor").apply());
        } else {
            b.setPositiveButton("OK", null)
             .setNeutralButton("Clean up old files", (d, w) -> pickOldFilesToDelete(a));
        }
        b.show();
    }
}
