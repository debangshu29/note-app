package com.debangshu.note;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Search inside photos: the words in every picture of every note are read once (on the phone, in the
 * background) and kept in photo_text.json, so searching "invoice" also finds a photo of an invoice.
 */
public final class PhotoText {

    private PhotoText() {}

    private static Map<String, String> cache;
    private static volatile boolean running;

    private static File file(Context c) { return new File(c.getApplicationContext().getFilesDir(), "photo_text.json"); }

    private static synchronized Map<String, String> map(Context c) {
        if (cache != null) return cache;
        cache = new HashMap<>();
        File f = file(c);
        if (f.exists()) {
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] b = new byte[(int) f.length()];
                int n = 0, r;
                while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
                JSONObject o = new JSONObject(new String(b, 0, n, "UTF-8"));
                Iterator<String> it = o.keys();
                while (it.hasNext()) { String k = it.next(); cache.put(k, o.optString(k)); }
            } catch (Exception ignored) { }
        }
        return cache;
    }

    private static synchronized void write(Context c) {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, String> e : cache.entrySet()) o.put(e.getKey(), e.getValue());
            try (FileOutputStream out = new FileOutputStream(file(c))) { out.write(o.toString().getBytes("UTF-8")); }
        } catch (Exception ignored) { }
    }

    /** The words found in a note's pictures and the names of its attached files (lower-case). */
    public static String forNote(Context c, Note n) {
        if (n.blocks == null) return "";
        Map<String, String> m = map(c);
        StringBuilder sb = new StringBuilder();
        for (Block b : n.blocks) {
            if (Block.IMAGE.equals(b.type)) {
                String t;
                synchronized (PhotoText.class) { t = m.get(b.file); }
                if (t != null && !t.isEmpty()) sb.append('\n').append(t);
            } else if (b.isAttachment()) {
                sb.append('\n').append(Attachments.label(b).toLowerCase(java.util.Locale.ROOT));
            }
        }
        return sb.toString();
    }

    /** True if the note matched only because of a picture (to show "found in a photo"). */
    public static boolean inPhoto(Context c, Note n, String q) {
        return !q.isEmpty() && forNote(c, n).contains(q);
    }

    /** Reads pictures not read yet (a few at a time; cheap to call often). */
    public static void indexAsync(Context c) {
        if (running) return;
        running = true;
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try { index(app, 40); } finally { running = false; }
        }).start();
    }

    private static void index(Context c, int max) {
        Map<String, String> m = map(c);
        Set<String> used = new java.util.HashSet<>();
        int done = 0;
        for (Note n : Store.get(c).notes()) {
            if (n.blocks == null) continue;
            for (Block b : n.blocks) {
                if (!Block.IMAGE.equals(b.type) || b.file == null || b.file.isEmpty()) continue;
                used.add(b.file);
                boolean have;
                synchronized (PhotoText.class) { have = m.containsKey(b.file); }
                if (have || done >= max) continue;
                File f = Media.file(c, b.file);
                if (!f.exists()) continue;
                String text = "";
                try {
                    text = TextImporter.read(c, Uri.fromFile(f), "image/jpeg").text;
                } catch (Exception e) {
                    String msg = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
                    if (msg.contains("set up") || msg.contains("download")) return;   // reader not ready: try later
                }
                synchronized (PhotoText.class) { m.put(b.file, text.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ").trim()); }
                done++;
            }
        }
        synchronized (PhotoText.class) { m.keySet().retainAll(used); }
        write(c);
    }

    public static synchronized void deleteAll(Context c) {
        cache = new HashMap<>();
        file(c).delete();
    }
}
