package com.debangshu.note;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Keeps everything in the phone's own app storage:
 *   notes.json      - all notes
 *   reminders.json  - reminders found in notes
 *   settings        - SharedPreferences
 * Nothing leaves the phone. "Delete all data" in Settings wipes these files.
 */
public final class Store {

    private static Store instance;

    public static synchronized Store get(Context ctx) {
        L.init(ctx);
        if (instance == null) instance = new Store(ctx.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final File notesFile, remindersFile;
    private final List<Note> notes = new ArrayList<>();
    private final List<Reminder> reminders = new ArrayList<>();
    private long lastId = 0;

    // ---------- settings keys
    public static final String S_AUTO_MATH = "auto_math";
    public static final String S_MATH_SUGGEST = "math_suggest";
    public static final String S_AUTO_REMINDERS = "auto_reminders";
    public static final String S_ADD_TO_CALENDAR = "add_to_calendar";
    public static final String S_DEFAULT_HOUR = "default_hour";
    public static final String S_SMART_LISTS = "smart_lists";
    public static final String S_AUTO_UPDATE = "auto_update";
    public static final String S_LIST_TOTALS = "list_totals";
    public static final String S_AUTO_BACKUP = "auto_backup";

    private Store(Context app) {
        this.app = app;
        Fonts.init(app);
        notesFile = new File(app.getFilesDir(), "notes.json");
        remindersFile = new File(app.getFilesDir(), "reminders.json");
        load();
    }

    public SharedPreferences prefs() {
        return app.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public boolean setting(String key) {
        return prefs().getBoolean(key, true); // every smart feature is ON by default
    }

    public int defaultHour() {
        return prefs().getInt(S_DEFAULT_HOUR, 9);
    }

    // ------------------------------------------------------------ ids

    public synchronized long newId() {
        long id = System.currentTimeMillis();
        if (id <= lastId) id = lastId + 1;
        lastId = id;
        return id;
    }

    // ------------------------------------------------------------ notes

    /** Notes that are not in the recycle bin: pinned first, then most recently edited. */
    public synchronized List<Note> notes() {
        List<Note> copy = new ArrayList<>();
        for (Note n : notes) if (n.deleted <= 0) copy.add(n);
        Collections.sort(copy, (a, b) -> {
            if (a.pinned != b.pinned) return a.pinned ? -1 : 1;
            return Long.compare(b.updated, a.updated);
        });
        return copy;
    }

    public synchronized Note note(long id) {
        for (Note n : notes) if (n.id == id) return n;
        return null;
    }

    public synchronized void saveNote(Note n) {
        if (note(n.id) == null) notes.add(n);
        saveNotes();
    }

    /** Notes in the recycle bin, most recently deleted first. */
    public synchronized List<Note> trash() {
        List<Note> copy = new ArrayList<>();
        for (Note n : notes) if (n.deleted > 0) copy.add(n);
        Collections.sort(copy, (a, b) -> Long.compare(b.deleted, a.deleted));
        return copy;
    }

    public static final long TRASH_DAYS = 30;

    /** Moves a note to the recycle bin (kept for 30 days). */
    public synchronized void trashNote(long id) {
        Note n = note(id);
        if (n == null) return;
        n.deleted = System.currentTimeMillis();
        n.pinned = false;
        saveNotes();
    }

    public synchronized void restoreNote(long id) {
        Note n = note(id);
        if (n == null) return;
        n.deleted = 0;
        n.updated = System.currentTimeMillis();
        saveNotes();
    }

    /** Deletes notes that have been in the bin for more than 30 days. Returns how many. */
    public synchronized int purgeTrash() {
        long limit = System.currentTimeMillis() - TRASH_DAYS * 24L * 60 * 60 * 1000;
        int n = 0;
        for (Note x : new ArrayList<>(notes)) if (x.deleted > 0 && x.deleted < limit) { removeNote(x.id); n++; }
        return n;
    }

    /** Every #label in use, with how many notes have it, most used first. */
    public synchronized List<String[]> labels() {
        java.util.Map<String, Integer> count = new java.util.LinkedHashMap<>();
        for (Note n : notes) {
            if (n.deleted > 0) continue;
            for (String t : n.tags()) count.put(t, count.containsKey(t) ? count.get(t) + 1 : 1);
        }
        List<String[]> out = new ArrayList<>();
        for (java.util.Map.Entry<String, Integer> e : count.entrySet()) out.add(new String[]{e.getKey(), String.valueOf(e.getValue())});
        Collections.sort(out, (a, b) -> Integer.parseInt(b[1]) - Integer.parseInt(a[1]));
        return out;
    }

    /** Deletes a note for good (with its pictures). */
    public synchronized void removeNote(long id) {
        for (int i = notes.size() - 1; i >= 0; i--) {
            Note n = notes.get(i);
            if (n.id != id) continue;
            for (String f : Media.filesOf(n.blocks)) Media.file(app, f).delete();   // its pictures too
            notes.remove(i);
        }
        saveNotes();
    }

    /** Picture files used by any note. */
    public synchronized java.util.Set<String> usedMedia() {
        java.util.Set<String> used = new java.util.HashSet<>();
        for (Note n : notes) used.addAll(Media.filesOf(n.blocks));
        return used;
    }

    // ------------------------------------------------------------ reminders

    /** Sorted by time. */
    public synchronized List<Reminder> reminders() {
        List<Reminder> copy = new ArrayList<>(reminders);
        Collections.sort(copy, (a, b) -> Long.compare(a.time, b.time));
        return copy;
    }

    public synchronized List<Reminder> remindersFor(long noteId) {
        List<Reminder> out = new ArrayList<>();
        for (Reminder r : reminders) if (r.noteId == noteId) out.add(r);
        Collections.sort(out, (a, b) -> Long.compare(a.time, b.time));
        return out;
    }

    public synchronized Reminder reminder(long id) {
        for (Reminder r : reminders) if (r.id == id) return r;
        return null;
    }

    public synchronized void saveReminder(Reminder r) {
        if (reminder(r.id) == null) reminders.add(r);
        saveReminders();
    }

    public synchronized void removeReminder(long id) {
        for (int i = reminders.size() - 1; i >= 0; i--) if (reminders.get(i).id == id) reminders.remove(i);
        saveReminders();
    }

    // ------------------------------------------------------------ wipe / backup

    /** Deletes every note, reminder and setting from the phone. */
    public synchronized void wipeEverything() {
        notes.clear();
        reminders.clear();
        notesFile.delete();
        remindersFile.delete();
        new File(notesFile.getPath() + ".tmp").delete();
        new File(remindersFile.getPath() + ".tmp").delete();
        prefs().edit().clear().apply();
        app.getSharedPreferences("study", Context.MODE_PRIVATE).edit().clear().apply();
        Habits.deleteAll(app);
        PhotoText.deleteAll(app);
        Geo.deleteAll(app);
        Media.deleteAll(app);
    }

    public synchronized String exportJson() throws Exception {
        JSONObject root = new JSONObject();
        root.put("app", "Note");
        root.put("version", 1);
        root.put("exported", System.currentTimeMillis());
        JSONArray n = new JSONArray();
        for (Note x : notes) n.put(x.toJson());
        root.put("notes", n);
        JSONArray r = new JSONArray();
        for (Reminder x : reminders) r.put(x.toJson());
        root.put("reminders", r);
        return root.toString(2);
    }

    // ---- password-protected backups: the key made from the password is kept in the app's private settings

    public static final String P_BK_KEY = "bk_key", P_BK_SALT = "bk_salt";

    public boolean backupProtected() { return prefs().getString(P_BK_KEY, null) != null; }

    public void setBackupPassword(String password) throws Exception {
        if (password == null || password.isEmpty()) {
            prefs().edit().remove(P_BK_KEY).remove(P_BK_SALT).apply();
            return;
        }
        byte[] salt = BackupCrypt.newSalt();
        byte[] key = BackupCrypt.key(password.toCharArray(), salt, BackupCrypt.ROUNDS);
        prefs().edit().putString(P_BK_KEY, android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP))
                .putString(P_BK_SALT, android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP)).apply();
    }

    public String backupExtension() { return backupProtected() ? "note" : "zip"; }
    public String backupMime() { return backupProtected() ? "application/octet-stream" : "application/zip"; }

    /** A backup file: the zip, locked with the backup password when one is set. */
    public void exportBackup(java.io.OutputStream os) throws Exception {
        if (!backupProtected()) { exportZip(os); return; }
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        exportZip(buf);
        byte[] key = android.util.Base64.decode(prefs().getString(P_BK_KEY, ""), android.util.Base64.NO_WRAP);
        byte[] salt = android.util.Base64.decode(prefs().getString(P_BK_SALT, ""), android.util.Base64.NO_WRAP);
        os.write(BackupCrypt.encrypt(buf.toByteArray(), key, salt, BackupCrypt.ROUNDS));
    }

    /** Backup file: a zip with notes.json and the pictures (media/…). */
    public void exportZip(java.io.OutputStream os) throws Exception {
        String json;
        java.util.Set<String> files;
        synchronized (this) {
            json = exportJson();
            files = usedMedia();
        }
        java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(os);
        zip.putNextEntry(new java.util.zip.ZipEntry("notes.json"));
        zip.write(json.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        zip.putNextEntry(new java.util.zip.ZipEntry("habits.json"));
        zip.write(Habits.exportJson(app).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        byte[] buf = new byte[16384];
        for (String f : files) {
            File src = Media.file(app, f);
            if (!src.exists()) continue;
            zip.putNextEntry(new java.util.zip.ZipEntry("media/" + f));
            try (FileInputStream in = new FileInputStream(src)) {
                int n;
                while ((n = in.read(buf)) > 0) zip.write(buf, 0, n);
            }
            zip.closeEntry();
        }
        zip.finish();
    }

    /** Restores a backup: the new .zip (with pictures) or an older .json. Returns notes added. */
    public int importBackup(byte[] data) throws Exception {
        if (data.length > 2 && data[0] == 'P' && data[1] == 'K') {
            String json = null;
            try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(data))) {
                java.util.zip.ZipEntry e;
                byte[] buf = new byte[16384];
                while ((e = zip.getNextEntry()) != null) {
                    String name = e.getName();
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    int n;
                    while ((n = zip.read(buf)) > 0) out.write(buf, 0, n);
                    if ("notes.json".equals(name)) {
                        json = new String(out.toByteArray(), StandardCharsets.UTF_8);
                    } else if ("habits.json".equals(name)) {
                        if (Habits.all(app).isEmpty()) Habits.importJson(app, new String(out.toByteArray(), StandardCharsets.UTF_8));
                    } else if (name.startsWith("media/")) {
                        String f = name.substring(6);
                        if (f.isEmpty() || f.contains("/") || f.contains("..")) continue;
                        File dst = Media.file(app, f);
                        if (!dst.exists()) try (FileOutputStream fo = new FileOutputStream(dst)) { fo.write(out.toByteArray()); }
                    }
                }
            }
            if (json == null) throw new Exception("not a Note backup");
            return importJson(json);
        }
        return importJson(new String(data, StandardCharsets.UTF_8));
    }

    /** Adds notes from a backup. Notes that already exist (same id) are kept as they are. Returns count added. */
    public synchronized int importJson(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray arr = root.getJSONArray("notes");
        int added = 0;
        for (int i = 0; i < arr.length(); i++) {
            Note n = Note.fromJson(arr.getJSONObject(i));
            if (note(n.id) == null) { notes.add(n); added++; }
            if (n.id > lastId) lastId = n.id;
        }
        saveNotes();
        return added;
    }

    // ------------------------------------------------------------ files

    private void load() {
        try {
            String s = read(notesFile);
            if (s != null) {
                JSONArray arr = new JSONArray(s);
                for (int i = 0; i < arr.length(); i++) {
                    Note n = Note.fromJson(arr.getJSONObject(i));
                    notes.add(n);
                    if (n.id > lastId) lastId = n.id;
                }
            }
        } catch (Exception ignored) { }
        try {
            String s = read(remindersFile);
            if (s != null) {
                JSONArray arr = new JSONArray(s);
                for (int i = 0; i < arr.length(); i++) {
                    Reminder r = Reminder.fromJson(arr.getJSONObject(i));
                    reminders.add(r);
                    if (r.id > lastId) lastId = r.id;
                }
            }
        } catch (Exception ignored) { }
    }

    /** Goes up every time notes are saved (the price book uses it to know when to look again). */
    public static volatile int changes = 0;

    private void saveNotes() {
        changes++;
        Widgets.changed(app);
        if (!L.english()) L.keep(this, app);
        try {
            JSONArray arr = new JSONArray();
            for (Note n : notes) arr.put(n.toJson());
            write(notesFile, arr.toString());
        } catch (Exception ignored) { }
    }

    private void saveReminders() {
        Widgets.changed(app);
        if (!L.english()) L.keep(this, app);
        try {
            JSONArray arr = new JSONArray();
            for (Reminder r : reminders) arr.put(r.toJson());
            write(remindersFile, arr.toString());
        } catch (Exception ignored) { }
    }

    private static String read(File f) throws IOException {
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0, r;
            while (off < buf.length && (r = in.read(buf, off, buf.length - off)) > 0) off += r;
            return new String(buf, 0, off, StandardCharsets.UTF_8);
        }
    }

    /** Writes to a temp file first, then renames, so a crash can never leave half a file. */
    private static void write(File f, String content) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) throw new IOException("rename failed");
        }
    }
}
