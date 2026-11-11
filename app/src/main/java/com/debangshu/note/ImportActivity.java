package com.debangshu.note;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * "Share → Note" / "Open with Note" for text, photos, screenshots, PDFs and text files.
 * Reads the text (on the phone) and opens it as a new note.
 */
public class ImportActivity extends Activity {

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.install(this);
        setContentView(R.layout.activity_import);
        Intent in = getIntent();
        String action = in.getAction();
        String type = in.getType();

        // plain text shared from a chat / browser: no reading needed
        String sharedText = in.getStringExtra(Intent.EXTRA_TEXT);
        Uri single = in.getParcelableExtra(Intent.EXTRA_STREAM);
        if (single == null && Intent.ACTION_VIEW.equals(action)) single = in.getData();
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        } else if (single != null) {
            uris.add(single);
        }

        if (uris.isEmpty()) {
            if (sharedText != null && !sharedText.trim().isEmpty()) {
                openEditor(in.getStringExtra(Intent.EXTRA_SUBJECT), sharedText);
            } else {
                finish();
            }
            return;
        }

        boolean needsReading = false;
        for (Uri u : uris) {
            String t = uris.size() == 1 && type != null ? type : getContentResolver().getType(u);
            if (t == null || t.startsWith("image/") || t.equals("application/pdf")) needsReading = true;
        }
        ((TextView) findViewById(R.id.import_text)).setText(needsReading
                ? "Reading the text… (on your phone, nothing is uploaded)" : "Opening…");

        final String subject = in.getStringExtra(Intent.EXTRA_SUBJECT);
        final String extra = sharedText;
        final String oneType = uris.size() == 1 ? type : null;
        new Thread(() -> {
            StringBuilder all = new StringBuilder();
            ArrayList<String> pictures = new ArrayList<>();
            String title = subject;
            String error = null;
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(new java.util.Date());
            for (Uri u : uris) {
                // keep a copy of the original in Documents/Note/Imports
                try {
                    String name = AppFolder.safeName(TextImporter.displayName(this, u));
                    String t2 = getContentResolver().getType(u);
                    AppFolder.saveCopy(this, AppFolder.IMPORTS, stamp + "_" + name,
                            t2 == null ? "application/octet-stream" : t2, u);
                } catch (Exception ignored) { }
                // photos / screenshots are also kept as pictures in the note
                try {
                    String t3 = oneType != null ? oneType : getContentResolver().getType(u);
                    if (t3 != null && t3.startsWith("image/")) pictures.add(Media.importImage(this, u));
                } catch (Exception ignored) { }
                try {
                    TextImporter.Result r = TextImporter.read(this, u, oneType);
                    if (title == null || title.trim().isEmpty()) title = r.title;
                    if (!r.text.isEmpty()) {
                        if (all.length() > 0) all.append("\n\n");
                        all.append(r.text);
                    }
                } catch (Exception e) {
                    error = e.getMessage();
                }
            }
            if (extra != null && !extra.trim().isEmpty() && !all.toString().contains(extra.trim())) {
                all.insert(0, extra.trim() + (all.length() > 0 ? "\n\n" : ""));
            }
            final String t = title, text = all.toString(), err = error;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (text.trim().isEmpty() && !pictures.isEmpty()) {
                    openEditor(t, "", pictures);       // a photo without text: still becomes a note
                } else if (text.trim().isEmpty()) {
                    new Ui.Popup(this)
                            .setTitle("No text found")
                            .setMessage(err != null ? err : "Couldn't find any text to put in a note.")
                            .setPositiveButton("OK", (d, w) -> finish())
                            .setOnCancelListener(d -> finish())
                            .show();
                } else {
                    openEditor(t, text, pictures);
                }
            });
        }).start();
    }

    private void openEditor(String title, String text) {
        openEditor(title, text, null);
    }

    private void openEditor(String title, String text, ArrayList<String> pictures) {
        Intent e = new Intent(this, EditorActivity.class);
        e.putExtra(Intent.EXTRA_TEXT, text);
        if (pictures != null && !pictures.isEmpty()) e.putStringArrayListExtra(EditorActivity.EXTRA_IMAGES, pictures);
        if (title != null) e.putExtra(EditorActivity.EXTRA_TITLE, title.trim());
        startActivity(e);
        finish();
    }
}
