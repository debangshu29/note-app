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

    /** Pinned first, then most recently edited. */
    public synchronized List<Note> notes() {
        List<Note> copy = new ArrayList<>(notes);
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

    public synchronized void removeNote(long id) {
        for (int i = notes.size() - 1; i >= 0; i--) if (notes.get(i).id == id) notes.remove(i);
        saveNotes();
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

    private void saveNotes() {
        try {
            JSONArray arr = new JSONArray();
            for (Note n : notes) arr.put(n.toJson());
            write(notesFile, arr.toString());
        } catch (Exception ignored) { }
    }

    private void saveReminders() {
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
