package com.debangshu.note;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * "Share" for one or more notes:
 *   as Text / Image / PDF, and for checklists: all items, only left to buy (☐) or only bought (☑).
 */
public final class ShareHelper {

    private ShareHelper() {}

    private static final int TEXT = 0, IMAGE = 1, PDF = 2;

    public static void show(Activity a, List<Note> notes) {
        if (notes == null || notes.isEmpty()) return;
        boolean checks = false;
        for (Note n : notes) if (NoteFilter.hasChecks(n.body)) checks = true;

        SharedPreferences p = a.getSharedPreferences("share", Activity.MODE_PRIVATE);
        float dp = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (24 * dp), (int) (8 * dp), (int) (24 * dp), 0);

        box.addView(label(a, "Share as"));
        RadioGroup format = group(a, new String[]{"Text message (words only)",
                        "Image (as it looks, with pictures)", "PDF (pages, formatting, pictures)"},
                p.getInt("format", TEXT));
        box.addView(format);

        RadioGroup which = null;
        if (checks) {
            box.addView(label(a, "Which items"));
            which = group(a, new String[]{"All items", "Only left to buy ☐", "Only bought ☑"}, NoteFilter.ALL);
            box.addView(which);
        }

        final RadioGroup whichF = which;
        new Ui.Popup(a)
                .setTitle(notes.size() == 1 ? "Share note" : "Share " + notes.size() + " notes")
                .setView(box)
                .setPositiveButton("Share", (d, w) -> {
                    int f = checkedIndex(format);
                    int mode = whichF == null ? NoteFilter.ALL : checkedIndex(whichF);
                    p.edit().putInt("format", f).apply();
                    share(a, notes, f, mode);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static TextView label(Activity a, String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(Ui.col(a, R.color.accent));
        t.setPadding(0, (int) (14 * a.getResources().getDisplayMetrics().density), 0, 0);
        return t;
    }

    private static RadioGroup group(Activity a, String[] options, int checked) {
        RadioGroup g = new RadioGroup(a);
        for (int i = 0; i < options.length; i++) {
            RadioButton b = new RadioButton(a);
            b.setId(View.generateViewId());
            b.setText(options[i]);
            b.setTag(i);
            g.addView(b);
            if (i == checked) b.setChecked(true);
        }
        return g;
    }

    private static int checkedIndex(RadioGroup g) {
        View v = g.findViewById(g.getCheckedRadioButtonId());
        return v == null ? 0 : (Integer) v.getTag();
    }

    // ------------------------------------------------------------------

    private static void share(Activity a, List<Note> notes, int format, int mode) {
        List<NoteRenderer.Page> pages = new ArrayList<>();
        for (Note n : notes) {
            String body = NoteFilter.filter(n.body, mode);
            String title = mode == NoteFilter.ALL ? n.title : NoteFilter.titleFor(n.title, mode);
            if (mode != NoteFilter.ALL && body.trim().isEmpty()) continue;
            pages.add(new NoteRenderer.Page(title, body));
        }
        if (pages.isEmpty()) {
            Toast.makeText(a, mode == NoteFilter.LEFT ? "Nothing left to buy 🎉" : "Nothing ticked yet", Toast.LENGTH_LONG).show();
            return;
        }

        if (format == TEXT) {
            StringBuilder sb = new StringBuilder();
            for (NoteRenderer.Page pg : pages) {
                if (sb.length() > 0) sb.append("\n\n— — —\n\n");
                sb.append(NoteFilter.asText(pg.title, pg.body));
            }
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, sb.toString());
            if (pages.size() == 1 && !pages.get(0).title.trim().isEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, pages.get(0).title.trim());
            a.startActivity(Intent.createChooser(i, "Share"));
            return;
        }

        Toast.makeText(a, format == PDF ? "Making PDF…" : "Making image…", Toast.LENGTH_SHORT).show();
        // "All items": the notes as they look (formatting, pictures, tables, pages).
        // "Only left / only bought": the filtered list as plain text.
        final List<Note> rich = new ArrayList<>();
        if (mode == NoteFilter.ALL) rich.addAll(notes);
        else for (NoteRenderer.Page pg : pages) {
            Note n = new Note();
            n.title = pg.title;
            List<Block> l = new ArrayList<>();
            l.add(Block.text(pg.body));
            n.setBlocks(l);
            rich.add(n);
        }
        new Thread(() -> {
            try {
                ShareProvider.clear(a);
                String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new Date());
                ArrayList<Uri> uris = new ArrayList<>();
                String mime;
                boolean tooLong = false;
                if (format == IMAGE) {
                    mime = "image/png";
                    int k = 1;
                    for (Note n : rich) {
                        File f = new File(ShareProvider.dir(a), fileName(n.title, stamp, rich.size() > 1 ? k++ : 0) + ".png");
                        if (NoteRenderer.toImage(a, n, f)) uris.add(ShareProvider.uriFor(f));
                        else tooLong = true;
                    }
                    if (tooLong) {           // a very long note: send everything as one PDF instead
                        uris.clear();
                        ShareProvider.clear(a);
                        mime = "application/pdf";
                        File f = new File(ShareProvider.dir(a), fileName(rich.get(0).title, stamp, 0) + ".pdf");
                        NoteRenderer.toPdf(a, rich, f);
                        uris.add(ShareProvider.uriFor(f));
                    }
                } else {
                    mime = "application/pdf";
                    File f = new File(ShareProvider.dir(a), fileName(rich.size() == 1 ? rich.get(0).title : "Notes", stamp, 0) + ".pdf");
                    NoteRenderer.toPdf(a, rich, f);
                    uris.add(ShareProvider.uriFor(f));
                }
                // keep a copy in Documents/Note/Exports
                File[] made = ShareProvider.dir(a).listFiles();
                if (made != null) for (File f : made) {
                    AppFolder.saveCopy(a, AppFolder.EXPORTS, f.getName(),
                            f.getName().endsWith(".pdf") ? "application/pdf" : "image/png", f);
                }
                final String m = mime;
                final boolean switched = tooLong;
                a.runOnUiThread(() -> send(a, uris, m, switched));
            } catch (Exception e) {
                a.runOnUiThread(() -> Toast.makeText(a, "Couldn't make the file: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private static String fileName(String title, String stamp, int index) {
        String t = title == null ? "" : title.replaceAll("[^\\p{L}\\p{N} _-]", "").trim();
        if (t.length() > 40) t = t.substring(0, 40).trim();
        if (t.isEmpty()) t = "Note";
        return t.replace(' ', '_') + "_" + stamp + (index > 0 ? "_" + index : "");
    }

    private static void send(Activity a, ArrayList<Uri> uris, String mime, boolean switchedToPdf) {
        if (switchedToPdf) Toast.makeText(a, "Note is very long — sharing it as a PDF", Toast.LENGTH_LONG).show();
        Intent i;
        if (uris.size() == 1) {
            i = new Intent(Intent.ACTION_SEND);
            i.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            i = new Intent(Intent.ACTION_SEND_MULTIPLE);
            i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        i.setType(mime);
        ClipData clip = ClipData.newRawUri("", uris.get(0));
        for (int k = 1; k < uris.size(); k++) clip.addItem(new ClipData.Item(uris.get(k)));
        i.setClipData(clip);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        a.startActivity(Intent.createChooser(i, "Share"));
    }
}
