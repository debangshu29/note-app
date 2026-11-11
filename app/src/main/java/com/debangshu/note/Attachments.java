package com.debangshu.note;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/** Voice recordings and attached files inside a note: how they look, play, open and get recorded. */
public final class Attachments {

    private Attachments() {}

    public interface Done { void block(Block b); }

    // ------------------------------------------------------------------ how they look

    public static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    public static String size(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return Math.round(b / 1024.0) + " KB";
        return String.format(Locale.ROOT, "%.1f MB", b / 1048576.0);
    }

    public static String icon(Block b) {
        if (Block.AUDIO.equals(b.type)) return "🎤";
        String m = b.mime == null ? "" : b.mime;
        String n = b.name == null ? "" : b.name.toLowerCase(Locale.ROOT);
        if (m.startsWith("audio/")) return "🎵";
        if (m.startsWith("video/")) return "🎬";
        if (m.startsWith("image/")) return "🖼";
        if (m.equals("application/pdf") || n.endsWith(".pdf")) return "📕";
        if (n.matches(".*\\.(xlsx?|csv|ods)$") || m.contains("spreadsheet")) return "📊";
        if (n.matches(".*\\.(docx?|odt|rtf)$") || m.contains("word")) return "📘";
        if (n.matches(".*\\.(pptx?|odp)$") || m.contains("presentation")) return "📙";
        if (n.matches(".*\\.(zip|rar|7z|tar|gz)$")) return "🗜";
        if (n.endsWith(".apk")) return "📦";
        if (m.startsWith("text/")) return "📄";
        return "📎";
    }

    public static String label(Block b) {
        if (Block.AUDIO.equals(b.type)) return b.name != null && !b.name.isEmpty() ? b.name : "Voice recording";
        return b.name == null || b.name.isEmpty() ? "File" : b.name;
    }

    /** The row shown in the note. */
    public static View view(Activity a, Block b, Runnable onMenu) {
        float dp = a.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int) (12 * dp), (int) (10 * dp), (int) (6 * dp), (int) (10 * dp));
        row.setBackground(Ui.ripple(Ui.round(Ui.col(a, R.color.surface2), 16 * dp)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (6 * dp);
        lp.bottomMargin = (int) (6 * dp);
        row.setLayoutParams(lp);

        boolean audio = Block.AUDIO.equals(b.type);
        TextView icon = new TextView(a);
        icon.setText(audio ? "▶" : icon(b));
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, audio ? 18 : 22);
        icon.setGravity(Gravity.CENTER);
        icon.setTextColor(audio ? a.getColor(R.color.on_accent) : a.getColor(R.color.text));
        if (audio) icon.setBackground(Ui.round(Ui.col(a, R.color.accent), 22 * dp));
        row.addView(icon, new LinearLayout.LayoutParams((int) (44 * dp), (int) (44 * dp)));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding((int) (12 * dp), 0, (int) (4 * dp), 0);
        TextView title = new TextView(a);
        title.setText(label(b));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(Ui.face(a, Ui.SEMIBOLD));
        title.setTextColor(a.getColor(R.color.text));
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        col.addView(title);
        TextView sub = new TextView(a);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setTextColor(a.getColor(R.color.text2));
        col.addView(sub);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView more = new TextView(a);
        more.setText("⋮");
        more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        more.setTextColor(a.getColor(R.color.text2));
        more.setGravity(Gravity.CENTER);
        more.setOnClickListener(v -> onMenu.run());
        row.addView(more, new LinearLayout.LayoutParams((int) (40 * dp), (int) (44 * dp)));
        row.setOnLongClickListener(v -> { onMenu.run(); return true; });

        if (audio) {
            SeekBar bar = new SeekBar(a);
            bar.setMax(1000);
            bar.setPadding((int) (2 * dp), (int) (6 * dp), (int) (2 * dp), 0);
            col.addView(bar);
            bar.setVisibility(View.GONE);
            sub.setText(duration(b.duration) + "  ·  tap ▶ to play");
            Player.Progress ui = new Player.Progress() {
                @Override public void update(boolean playing, int pos, int len) {
                    icon.setText(playing ? "❚❚" : "▶");
                    bar.setVisibility(playing || pos > 0 ? View.VISIBLE : View.GONE);
                    if (len > 0) bar.setProgress((int) (1000L * pos / len));
                    sub.setText(playing || pos > 0 ? duration(pos) + " / " + duration(len > 0 ? len : b.duration) : duration(b.duration) + "  ·  tap ▶ to play");
                }
            };
            icon.setOnClickListener(v -> Player.toggle(a, b.file, ui));
            row.setOnClickListener(v -> Player.toggle(a, b.file, ui));
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) Player.seek(b.file, p / 1000f); }
                public void onStartTrackingTouch(SeekBar s) { }
                public void onStopTrackingTouch(SeekBar s) { }
            });
        } else {
            String type = b.name != null && b.name.contains(".") ? b.name.substring(b.name.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT) + " · " : "";
            sub.setText(type + size(b.size) + "  ·  tap to open");
            row.setOnClickListener(v -> open(a, b));
        }
        Ui.fonts(row);
        return row;
    }

    // ------------------------------------------------------------------ playing

    /** One recording plays at a time. */
    public static final class Player {
        interface Progress { void update(boolean playing, int pos, int len); }

        private static MediaPlayer mp;
        private static String file;
        private static Progress ui;
        private static final Handler h = new Handler(Looper.getMainLooper());
        private static final Runnable tick = new Runnable() {
            @Override public void run() {
                if (mp == null || ui == null) return;
                try { ui.update(mp.isPlaying(), mp.getCurrentPosition(), mp.getDuration()); } catch (Exception ignored) { }
                if (mp != null && mp.isPlaying()) h.postDelayed(this, 250);
            }
        };

        static void toggle(Context c, String f, Progress u) {
            try {
                if (mp != null && f.equals(file)) {
                    ui = u;
                    if (mp.isPlaying()) mp.pause(); else mp.start();
                    h.post(tick);
                    return;
                }
                stop();
                mp = new MediaPlayer();
                mp.setDataSource(Media.file(c, f).getPath());
                mp.prepare();
                file = f;
                ui = u;
                mp.setOnCompletionListener(p -> {
                    if (ui != null) ui.update(false, 0, p.getDuration());
                    try { p.seekTo(0); } catch (Exception ignored) { }
                });
                mp.start();
                h.post(tick);
            } catch (Exception e) {
                Toast.makeText(c, "Couldn't play the recording", Toast.LENGTH_SHORT).show();
                stop();
            }
        }

        static void seek(String f, float share) {
            if (mp == null || !f.equals(file)) return;
            try { mp.seekTo((int) (mp.getDuration() * share)); h.post(tick); } catch (Exception ignored) { }
        }

        public static void stop() {
            h.removeCallbacks(tick);
            if (mp != null) {
                try { mp.stop(); } catch (Exception ignored) { }
                mp.release();
            }
            if (ui != null) { Progress u = ui; ui = null; try { u.update(false, 0, 0); } catch (Exception ignored) { } }
            mp = null;
            file = null;
        }
    }

    // ------------------------------------------------------------------ opening / sharing files

    private static String safeName(Block b) {
        String n = label(b).replaceAll("[\\\\/:*?\"<>|\\n\\r]", "_").trim();
        if (Block.AUDIO.equals(b.type) && !n.toLowerCase(Locale.ROOT).endsWith(".m4a")) n += ".m4a";
        if (n.isEmpty()) n = b.file;
        if (!n.contains(".") && b.file.contains(".")) n += b.file.substring(b.file.lastIndexOf('.'));
        return n;
    }

    private static Uri shareUri(Context c, Block b) throws Exception {
        File f = Media.shareCopy(c, b.file, safeName(b));
        return ShareProvider.uriFor(f);
    }

    private static String mimeOf(Block b) {
        if (b.mime != null && !b.mime.isEmpty()) return b.mime;
        String n = safeName(b).toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        String m = dot >= 0 ? android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(n.substring(dot + 1)) : null;
        return m != null ? m : "application/octet-stream";
    }

    public static void open(Activity a, Block b) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(shareUri(a, b), mimeOf(b));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Lock.leavingForOtherApp();
            a.startActivity(Intent.createChooser(i, "Open with"));
        } catch (Exception e) {
            Toast.makeText(a, "No app on this phone can open this file", Toast.LENGTH_LONG).show();
        }
    }

    public static void share(Activity a, Block b) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(mimeOf(b));
            i.putExtra(Intent.EXTRA_STREAM, shareUri(a, b));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Lock.leavingForOtherApp();
            a.startActivity(Intent.createChooser(i, "Share"));
        } catch (Exception e) {
            Toast.makeText(a, "Couldn't share it", Toast.LENGTH_SHORT).show();
        }
    }

    /** Copies any file from another app into the note's media folder. */
    public static Block importFile(Context c, Uri uri) throws Exception {
        String name = TextImporter.displayName(c, uri);
        if (name == null || name.isEmpty()) name = "File";
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "") : "bin";
        if (ext.isEmpty() || ext.length() > 8) ext = "bin";
        String file = Media.newName(ext);
        long n = 0;
        try (InputStream in = c.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(Media.file(c, file))) {
            if (in == null) throw new Exception("can't open the file");
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                n += r;
                if (n > 200L * 1024 * 1024) throw new Exception("the file is bigger than 200 MB");
            }
        } catch (Exception e) {
            Media.file(c, file).delete();
            throw e;
        }
        String mime = c.getContentResolver().getType(uri);
        return Block.attachment(file, name, mime, n);
    }

    // ------------------------------------------------------------------ recording

    public static final int REQ_MIC = 6601;

    public static boolean hasMic(Activity a) {
        return a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /** Shows the recorder; when you tap "Add", the recording comes back as a block. */
    public static void record(Activity a, Done done) {
        if (!hasMic(a)) {
            a.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        Player.stop();
        String file = Media.newName("m4a");
        File out = Media.file(a, file);
        MediaRecorder rec = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(a) : new MediaRecorder();
        try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC);
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            rec.setAudioEncodingBitRate(64000);
            rec.setAudioSamplingRate(44100);
            rec.setMaxDuration(60 * 60 * 1000);
            rec.setOutputFile(out.getPath());
            rec.prepare();
            rec.start();
        } catch (Exception e) {
            rec.release();
            out.delete();
            Toast.makeText(a, "Couldn't start recording (is another app using the microphone?)", Toast.LENGTH_LONG).show();
            return;
        }
        float dp = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, (int) (10 * dp), 0, (int) (10 * dp));
        TextView dot = new TextView(a);
        dot.setText("●");
        dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        dot.setTextColor(a.getColor(R.color.danger));
        box.addView(dot);
        TextView time = new TextView(a);
        time.setText("0:00");
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        time.setTypeface(Ui.face(a, Ui.EXTRABOLD));
        time.setTextColor(a.getColor(R.color.text));
        box.addView(time);
        TextView hint = new TextView(a);
        hint.setText("Recording… keep Note open. Your voice stays on this phone.");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setTextColor(a.getColor(R.color.text2));
        hint.setGravity(Gravity.CENTER);
        hint.setPadding((int) (20 * dp), (int) (8 * dp), (int) (20 * dp), 0);
        box.addView(hint);

        long start = System.currentTimeMillis();
        Handler h = new Handler(Looper.getMainLooper());
        final boolean[] stopped = {false};
        Runnable tick = new Runnable() {
            @Override public void run() {
                if (stopped[0]) return;
                long ms = System.currentTimeMillis() - start;
                time.setText(duration(ms));
                dot.setAlpha((ms / 500) % 2 == 0 ? 1f : 0.25f);
                h.postDelayed(this, 250);
            }
        };
        h.post(tick);
        Runnable finish = () -> {
            stopped[0] = true;
            try { rec.stop(); } catch (Exception ignored) { }
            rec.release();
        };
        AlertDialog d = new Ui.Popup(a).setTitle("🎤 Voice recording").setView(box)
                .setPositiveButton("Stop & add", (x, w) -> {
                    long ms = System.currentTimeMillis() - start;
                    finish.run();
                    if (!out.exists() || out.length() < 1000) {
                        out.delete();
                        Toast.makeText(a, "Nothing was recorded", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    done.block(Block.audio(file, ms, out.length()));
                })
                .setNegativeButton("Cancel", (x, w) -> { finish.run(); out.delete(); })
                .setCancelable(false)
                .create();
        d.show();
    }
}
