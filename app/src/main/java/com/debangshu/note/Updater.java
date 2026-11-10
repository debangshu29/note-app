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
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
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
 *  - "Download" opens the direct link in the browser; you tap the file and choose Update (notes stay)
 *  - after updating, Note offers to delete the downloaded file
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
           .append("1. Tap Download — the new Note.apk downloads in your browser.\n")
           .append("2. When it finishes, tap the file and choose Update. Your notes stay.\n")
           .append("3. Open Note again — it will offer to delete the downloaded file.\n\n")
           .append("Trouble downloading? Tap Help.");
        new AlertDialog.Builder(a)
                .setTitle("Update available")
                .setMessage(msg.toString())
                .setPositiveButton("Download", (d, w) -> startUpdate(a))
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
                "• Download fails, shows “504” or an error page\n"
                + "   GitHub is busy for a moment. Wait a minute and tap Download again.\n\n"
                + "• Can't find the file\n"
                + "   In Chrome: ⋮ menu → Downloads. Or Files app → Downloads → the newest Note.apk.\n\n"
                + "• “Install unknown apps” / install blocked\n"
                + "   Allow it for Chrome (or Files) when Android asks — it's needed once.\n\n"
                + "• Play Protect warning\n"
                + "   Tap More details → Install anyway.\n\n"
                + "• “App not installed”\n"
                + "   You probably opened an older Note.apk. Delete old Note.apk files (Settings → "
                + "Delete old update files) and download again.\n\n"
                + "• Still stuck\n"
                + "   Copy the link and open it in another browser, or on your computer and send the file to the phone.";
        new AlertDialog.Builder(a)
                .setTitle("Update help")
                .setMessage(msg)
                .setPositiveButton("Download again", (d, w) -> startUpdate(a))
                .setNeutralButton("Copy link", (d, w) -> copyLink(a))
                .setNegativeButton("Close", null)
                .show();
    }

    public static void copyLink(Activity a) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("Note download link", APK_URL));
        Toast.makeText(a, "Link copied", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ download (browser) + clean-up

    /** Opens the direct download link in the browser. Afterwards Note offers to delete the file. */
    public static void startUpdate(Activity a) {
        Info latest = remembered(a);
        int target = latest == null ? currentVersionCode(a) + 1 : latest.versionCode;
        prefs(a).edit().putInt("cleanupFor", target).apply();
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(APK_URL + "?v=" + target));
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            a.startActivity(i);
            Toast.makeText(a, "When the download finishes, tap Note.apk and choose Update", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(a, "No browser found — tap Help → Copy link", Toast.LENGTH_LONG).show();
        }
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
            Toast.makeText(a, "Select every Note.apk / Note (1).apk… then tap Select", Toast.LENGTH_LONG).show();
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

    /** Removes leftovers of the older in-app downloader (1.5 – 1.8), which the app is allowed to delete itself. */
    public static void cleanUpAfterUpdate(Context ctx) {
        SharedPreferences p = prefs(ctx);
        long id = p.getLong("dlId", -1);
        if (id != -1) {
            DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                try { dm.remove(id); } catch (Exception ignored) { }
            }
            p.edit().remove("dlId").remove("dlFor").apply();
        }
        File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File[] files = dir == null ? null : dir.listFiles();
        if (files != null) for (File f : files) if (f.getName().startsWith("Note-update")) f.delete();
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
        if (msg.length() == 0) {
            Toast.makeText(a, "Updated to Note " + currentVersionName(a), Toast.LENGTH_LONG).show();
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle("Updated to " + currentVersionName(a))
                .setMessage(msg.toString());
        if (offerCleanup) {
            b.setPositiveButton("Delete file", (d, w) -> pickOldFilesToDelete(a))
             .setNegativeButton("Not now", (d, w) -> p.edit().remove("cleanupFor").apply());
        } else {
            b.setPositiveButton("OK", null);
        }
        b.show();
    }
}
