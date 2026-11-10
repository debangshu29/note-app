package com.debangshu.note;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The app's own folder in phone storage, visible in the Files app:
 *
 *   Internal storage / Documents / Note /
 *       Backups   automatic daily backup of all notes (last 14 days kept) + nothing else
 *       Exports   a copy of every image / PDF you share from Note
 *       Imports   a copy of every photo / PDF / file you turned into a note
 *
 * The notes themselves are kept in Note's private storage (only Note can open it, so other apps
 * can't read or delete them by accident); the Backups folder always has a readable copy.
 */
public final class AppFolder {

    private AppFolder() {}

    public static final String BACKUPS = "Backups", EXPORTS = "Exports", IMPORTS = "Imports";
    private static final String ROOT = "Note";
    private static final int KEEP_BACKUPS = 14;
    private static final long BACKUP_EVERY = 10L * 60 * 1000;

    /** "Internal storage/Documents/Note/Backups" */
    public static String displayPath(String section) {
        return "Internal storage/Documents/" + ROOT + (section == null ? "" : "/" + section);
    }

    /** Where Note's own (private) notes file is kept. */
    public static String privatePath(Context ctx) {
        return ctx.getFilesDir().getAbsolutePath();
    }

    private static String relPath(String section) {
        return Environment.DIRECTORY_DOCUMENTS + "/" + ROOT + "/" + section + "/";
    }

    /** Android 9 and older need the storage permission to write to Documents. */
    public static boolean canWrite(Context ctx) {
        return Build.VERSION.SDK_INT >= 29
                || ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    public static void askPermissionIfNeeded(Activity a, int requestCode) {
        if (!canWrite(a)) a.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, requestCode);
    }

    @SuppressWarnings("deprecation")
    private static File legacyDir(String section) {
        File d = new File(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), ROOT), section);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    // ------------------------------------------------------------------ saving

    /** Saves bytes into a section (replacing a file with the same name). Returns true on success. */
    public static boolean save(Context ctx, String section, String name, String mime, byte[] data) {
        return save(ctx, section, name, mime, out -> out.write(data));
    }

    public static boolean saveCopy(Context ctx, String section, String name, String mime, File src) {
        return save(ctx, section, name, mime, out -> {
            try (InputStream in = new FileInputStream(src)) { copy(in, out); }
        });
    }

    public static boolean saveCopy(Context ctx, String section, String name, String mime, Uri src) {
        return save(ctx, section, name, mime, out -> {
            try (InputStream in = ctx.getContentResolver().openInputStream(src)) {
                if (in == null) throw new Exception("can't open");
                copy(in, out);
            }
        });
    }

    private interface Writer { void write(OutputStream out) throws Exception; }

    private static boolean save(Context ctx, String section, String name, String mime, Writer w) {
        if (!canWrite(ctx)) return false;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = ctx.getContentResolver();
                Uri coll = MediaStore.Files.getContentUri("external");
                Uri target = find(cr, coll, section, name);
                if (target == null) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, relPath(section));
                    target = cr.insert(coll, v);
                }
                if (target == null) return false;
                try (OutputStream out = cr.openOutputStream(target, "wt")) {
                    if (out == null) return false;
                    w.write(out);
                }
            } else {
                try (OutputStream out = new FileOutputStream(new File(legacyDir(section), name))) {
                    w.write(out);
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Uri find(ContentResolver cr, Uri coll, String section, String name) {
        try (Cursor c = cr.query(coll, new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                new String[]{relPath(section), name}, null)) {
            if (c != null && c.moveToFirst()) return ContentUris.withAppendedId(coll, c.getLong(0));
        } catch (Exception ignored) { }
        return null;
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
    }

    // ------------------------------------------------------------------ automatic backup

    /** Writes today's backup (at most every 10 minutes) and keeps the last 14 days. Run off the UI thread. */
    public static void autoBackup(Context ctx, boolean force) {
        Store s = Store.get(ctx);
        if (!s.setting(Store.S_AUTO_BACKUP)) return;
        long last = s.prefs().getLong("lastAutoBackup", 0);
        if (!force && System.currentTimeMillis() - last < BACKUP_EVERY) return;
        if (s.notes().isEmpty()) return;
        try {
            String name = "note-backup-" + new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date()) + ".json";
            if (save(ctx, BACKUPS, name, "application/json", s.exportJson().getBytes("UTF-8"))) {
                s.prefs().edit().putLong("lastAutoBackup", System.currentTimeMillis()).apply();
                trimBackups(ctx);
            }
        } catch (Exception ignored) { }
    }

    private static void trimBackups(Context ctx) {
        List<String> names = list(ctx, BACKUPS);
        Collections.sort(names);
        for (int i = 0; i < names.size() - KEEP_BACKUPS; i++) delete(ctx, BACKUPS, names.get(i));
    }

    // ------------------------------------------------------------------ listing / deleting

    /** File names Note saved in a section. */
    public static List<String> list(Context ctx, String section) {
        List<String> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 29) {
            try (Cursor c = ctx.getContentResolver().query(MediaStore.Files.getContentUri("external"),
                    new String[]{MediaStore.MediaColumns.DISPLAY_NAME},
                    MediaStore.MediaColumns.RELATIVE_PATH + "=?", new String[]{relPath(section)}, null)) {
                while (c != null && c.moveToNext()) out.add(c.getString(0));
            } catch (Exception ignored) { }
        } else if (canWrite(ctx)) {
            String[] names = legacyDir(section).list();
            if (names != null) Collections.addAll(out, names);
        }
        return out;
    }

    /** Total size in bytes of what Note saved in a section. */
    public static long size(Context ctx, String section) {
        long total = 0;
        if (Build.VERSION.SDK_INT >= 29) {
            try (Cursor c = ctx.getContentResolver().query(MediaStore.Files.getContentUri("external"),
                    new String[]{MediaStore.MediaColumns.SIZE},
                    MediaStore.MediaColumns.RELATIVE_PATH + "=?", new String[]{relPath(section)}, null)) {
                while (c != null && c.moveToNext()) total += c.getLong(0);
            } catch (Exception ignored) { }
        } else if (canWrite(ctx)) {
            File[] files = legacyDir(section).listFiles();
            if (files != null) for (File f : files) total += f.length();
        }
        return total;
    }

    private static void delete(Context ctx, String section, String name) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                Uri coll = MediaStore.Files.getContentUri("external");
                Uri u = find(ctx.getContentResolver(), coll, section, name);
                if (u != null) ctx.getContentResolver().delete(u, null, null);
            } else {
                new File(legacyDir(section), name).delete();
            }
        } catch (Exception ignored) { }
    }

    /** Deletes everything Note saved in one section. */
    public static void clear(Context ctx, String section) {
        for (String n : list(ctx, section)) delete(ctx, section, n);
    }

    public static void clearAll(Context ctx) {
        clear(ctx, BACKUPS);
        clear(ctx, EXPORTS);
        clear(ctx, IMPORTS);
    }

    // ------------------------------------------------------------------ open in the Files app

    public static void open(Activity a, String section) {
        String docId = "primary:Documents/" + ROOT + (section == null ? "" : "/" + section);
        Uri uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            a.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(a, "Open the Files app → " + displayPath(section), Toast.LENGTH_LONG).show();
        }
    }

    public static String safeName(String s) {
        String t = s == null ? "" : s.replaceAll("[^\\p{L}\\p{N} ._()-]", "").trim();
        if (t.length() > 60) t = t.substring(0, 60).trim();
        return t.isEmpty() ? "file" : t;
    }
}
