package com.debangshu.note;

import android.app.Activity;
import android.content.Context;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.digitalink.DigitalInkRecognition;
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel;
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier;
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer;
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions;
import com.google.mlkit.vision.digitalink.Ink;
import com.google.mlkit.vision.digitalink.RecognitionResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * On-phone language tools from Google's ML Kit (the language packs download once, then work offline):
 *   - handwriting → text, from a drawing's strokes,
 *   - translate between English, Hindi and Bengali.
 * Everything here blocks: call it from a background thread.
 */
public final class SmartTools {

    private SmartTools() {}

    // ------------------------------------------------------------------ handwriting

    public static final String[] INK_NAMES = {"English", "हिन्दी (Hindi)", "বাংলা (Bengali)"};
    public static final String[] INK_TAGS = {"en-US", "hi-IN", "bn-IN"};

    /** Reads the handwriting in a drawing (one line at a time, top to bottom). */
    public static String handwriting(Context c, String strokesFile, String langTag) throws Exception {
        List<float[][]> strokes = loadStrokes(Media.file(c, strokesFile));
        if (strokes.isEmpty()) throw new Exception("The drawing has no writing in it");

        DigitalInkRecognitionModelIdentifier id;
        try { id = DigitalInkRecognitionModelIdentifier.fromLanguageTag(langTag); }
        catch (Exception e) { id = null; }
        if (id == null) throw new Exception("This language isn't available for handwriting");
        DigitalInkRecognitionModel model = DigitalInkRecognitionModel.builder(id).build();
        try {
            Tasks.await(RemoteModelManager.getInstance().download(model, new DownloadConditions.Builder().build()), 180, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new Exception("Couldn't get the handwriting pack (about 20 MB, needed once). Connect to the internet and try again.");
        }
        DigitalInkRecognizer rec = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build());
        try {
            StringBuilder out = new StringBuilder();
            for (List<float[][]> line : lines(strokes)) {
                Ink.Builder ink = Ink.builder();
                long t = 0;
                for (float[][] s : line) {
                    Ink.Stroke.Builder sb = Ink.Stroke.builder();
                    for (float[] p : s) { sb.addPoint(Ink.Point.create(p[0] * 1000f, p[1] * 1000f, t)); t += 10; }
                    ink.addStroke(sb.build());
                    t += 200;
                }
                RecognitionResult r = Tasks.await(rec.recognize(ink.build()), 60, TimeUnit.SECONDS);
                if (r != null && r.getCandidates() != null && !r.getCandidates().isEmpty()) {
                    String text = r.getCandidates().get(0).getText();
                    if (text != null && !text.trim().isEmpty()) {
                        if (out.length() > 0) out.append('\n');
                        out.append(text.trim());
                    }
                }
            }
            if (out.length() == 0) throw new Exception("Couldn't read any words in the drawing");
            return out.toString();
        } finally {
            try { rec.close(); } catch (Exception ignored) { }
        }
    }

    /** Pen strokes only (no highlighter, no eraser), as {x, y} points scaled to the width. */
    private static List<float[][]> loadStrokes(File f) throws Exception {
        List<float[][]> out = new ArrayList<>();
        byte[] buf = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0, r;
            while (off < buf.length && (r = in.read(buf, off, buf.length - off)) > 0) off += r;
        }
        JSONArray arr = new JSONObject(new String(buf, "UTF-8")).getJSONArray("strokes");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            int tool = o.optInt("t");
            if (tool == 2 || tool == 3) continue;
            JSONArray p = o.getJSONArray("p");
            float[][] pts = new float[p.length() / 2][];
            for (int k = 0; k + 1 < p.length(); k += 2) pts[k / 2] = new float[]{(float) p.getDouble(k), (float) p.getDouble(k + 1)};
            if (pts.length > 0) out.add(pts);
        }
        return out;
    }

    /** Groups strokes into lines of writing by height on the page. */
    static List<List<float[][]>> lines(List<float[][]> strokes) {
        List<float[]> boxes = new ArrayList<>();   // top, bottom, index
        List<Float> heights = new ArrayList<>();
        for (int i = 0; i < strokes.size(); i++) {
            float top = Float.MAX_VALUE, bottom = -Float.MAX_VALUE;
            for (float[] p : strokes.get(i)) { top = Math.min(top, p[1]); bottom = Math.max(bottom, p[1]); }
            boxes.add(new float[]{top, bottom, i});
            heights.add(bottom - top);
        }
        Collections.sort(heights);
        float typical = Math.max(0.01f, heights.get(heights.size() / 2));
        Collections.sort(boxes, (a, b) -> Float.compare((a[0] + a[1]) / 2, (b[0] + b[1]) / 2));
        List<List<float[][]>> out = new ArrayList<>();
        List<float[][]> cur = new ArrayList<>();
        float lineBottom = -1;
        for (float[] bx : boxes) {
            float mid = (bx[0] + bx[1]) / 2;
            if (!cur.isEmpty() && mid > lineBottom + typical * 0.6f) { out.add(cur); cur = new ArrayList<>(); lineBottom = -1; }
            cur.add(strokes.get((int) bx[2]));
            lineBottom = Math.max(lineBottom, bx[1]);
        }
        if (!cur.isEmpty()) out.add(cur);
        // keep the writing order inside a line (strokes were drawn left to right)
        return out;
    }

    // ------------------------------------------------------------------ translate

    public static final String[] LANG_NAMES = {"English", "हिन्दी (Hindi)", "বাংলা (Bengali)"};
    public static final String[] LANG_CODES = {TranslateLanguage.ENGLISH, TranslateLanguage.HINDI, TranslateLanguage.BENGALI};

    /** "hi" when the text is mostly Devanagari, "bn" for Bengali script, otherwise "en". */
    public static String guessLanguage(String text) {
        int dev = 0, ben = 0, lat = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= 0x0900 && ch <= 0x097F) dev++;
            else if (ch >= 0x0980 && ch <= 0x09FF) ben++;
            else if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) lat++;
        }
        if (dev > ben && dev > lat / 2) return TranslateLanguage.HINDI;
        if (ben > dev && ben > lat / 2) return TranslateLanguage.BENGALI;
        return TranslateLanguage.ENGLISH;
    }

    /**
     * Translates line by line (keeps ☐ ☑ • marks and empty lines). Hindi ↔ Bengali goes through English.
     * {@code wifiOnly}: the language pack (about 30 MB, once) is only fetched on Wi-Fi.
     */
    public static String translate(String text, String from, String to, boolean wifiOnly) throws Exception {
        if (from.equals(to)) return text;
        if (!from.equals(TranslateLanguage.ENGLISH) && !to.equals(TranslateLanguage.ENGLISH)) {
            return translate(translate(text, from, TranslateLanguage.ENGLISH, wifiOnly), TranslateLanguage.ENGLISH, to, wifiOnly);
        }
        Translator t = Translation.getClient(new TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build());
        try {
            DownloadConditions.Builder dc = new DownloadConditions.Builder();
            if (wifiOnly) dc.requireWifi();
            try {
                Tasks.await(t.downloadModelIfNeeded(dc.build()), 300, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new Exception(wifiOnly ? "The language pack (about 30 MB, needed once) downloads on Wi-Fi. Connect to Wi-Fi, or allow mobile data."
                        : "Couldn't get the language pack (needed once). Check the internet and try again.");
            }
            StringBuilder out = new StringBuilder();
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i];
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\s*(?:[☐☑•*\\-➜]\\s*|\\d{1,3}[.)]\\s+|#{1,3}\\s+)?)(.*)$").matcher(l);
                String mark = "", rest = l;
                if (m.find()) { mark = m.group(1); rest = m.group(2); }
                if (rest.trim().isEmpty() || !rest.matches("(?s).*\\p{L}.*")) out.append(l);
                else out.append(mark).append(Tasks.await(t.translate(rest), 60, TimeUnit.SECONDS));
                if (i < lines.length - 1) out.append('\n');
            }
            return out.toString();
        } finally {
            try { t.close(); } catch (Exception ignored) { }
        }
    }

    /** Runs {@code job} off the main thread and shows "working" meanwhile. */
    public interface Job { String run() throws Exception; }
    public interface Result { void done(String text); }

    public static void inBackground(Activity a, String working, Job job, Result r) {
        android.app.AlertDialog wait = new Ui.Popup(a).setMessage(working).setCancelable(false).create();
        wait.show();
        new Thread(() -> {
            String out = null, err = null;
            try { out = job.run(); } catch (Exception e) { err = e.getMessage(); }
            final String o = out, er = err;
            a.runOnUiThread(() -> {
                try { wait.dismiss(); } catch (Exception ignored) { }
                if (o != null) r.done(o);
                else new Ui.Popup(a).setTitle("Couldn't do that").setMessage(er == null ? "Something went wrong" : er)
                        .setPositiveButton("OK", null).show();
            });
        }).start();
    }
}
