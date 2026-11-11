package com.debangshu.note;

import android.content.Intent;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Document wallet: ID cards, licences, policies… Each document is a locked note labelled #wallet
 * (scans + details), kept off the home list and opened only with your PIN or fingerprint.
 */
public class WalletActivity extends Screen {

    static final long WALLET_KEY = -77;
    public static final String TAG = "wallet";

    private static final String[][] KINDS = {
            {"🪪", "Aadhaar card"}, {"🪪", "PAN card"}, {"🛂", "Passport"}, {"🚗", "Driving licence"},
            {"🚘", "Vehicle RC"}, {"🗳", "Voter ID"}, {"🏥", "Health insurance"}, {"🛡", "Insurance policy"},
            {"🎓", "Marksheet / certificate"}, {"💳", "Bank / card details"}, {"🏠", "Rent agreement"}, {"📄", "Other document"}};

    private boolean asked;

    @Override
    protected void onCreate(android.os.Bundle b) {
        super.onCreate(b);
        topButton("＋", "Add a document", true, v -> add());
    }

    @Override
    protected void build() {
        body.removeAllViews();
        setTitles("Document wallet", "Locked · stays on this phone");
        if (!Lock.isOpen(WALLET_KEY)) {
            if (!asked) {
                asked = true;
                Lock.verify(this, "Document wallet", ok -> {
                    if (ok) { Lock.markOpen(WALLET_KEY); build(); Ui.fonts(root); } else finish();
                });
            }
            return;
        }
        List<Note> docs = new ArrayList<>();
        for (Note n : store.notes()) if (n.deleted == 0 && n.tags().contains(TAG)) docs.add(n);
        setTitles("Document wallet", docs.isEmpty() ? "Locked · stays on this phone" : docs.size() + (docs.size() == 1 ? " document" : " documents") + " · locked");

        if (docs.isEmpty()) {
            LinearLayout c = card("🗂", "Keep your documents safe", "Scan once, find in seconds");
            c.addView(hint("Tap ＋ to scan an Aadhaar card, licence, passport, insurance policy… Each one is a locked note "
                    + "with the scan and its details (number, valid till). Write “valid till 3 Dec 2030” and Note reminds you "
                    + "before it runs out.\n\nAny note can join too: ⋮ → Move to document wallet."));
            return;
        }
        LinearLayout c = card(null, null, null);
        for (Note n : docs) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(px(6), px(8), px(6), px(8));
            row.setBackground(Ui.ripple(Ui.round(0, 12 * dp)));
            ImageView thumb = new ImageView(this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackground(Ui.round(Ui.col(this, R.color.surface2), 10 * dp));
            thumb.setClipToOutline(true);
            row.addView(thumb, new LinearLayout.LayoutParams(px(64), px(44)));
            TextView em = text(emoji(n.displayTitle()), 22, Ui.REGULAR, getColor(R.color.text));
            em.setGravity(Gravity.CENTER);
            String pic = firstPicture(n);
            if (pic != null) {
                new Thread(() -> {
                    Bitmap bmp = Media.load(this, pic, px(128));
                    runOnUiThread(() -> { if (bmp != null) thumb.setImageBitmap(bmp); });
                }).start();
            } else {
                row.removeView(thumb);
                row.addView(em, 0, new LinearLayout.LayoutParams(px(64), px(44)));
            }
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(px(12), 0, 0, 0);
            col.addView(text(n.displayTitle(), 15, Ui.SEMIBOLD, getColor(R.color.text)));
            String sub = detail(n);
            if (!sub.isEmpty()) col.addView(text(sub, 12, Ui.REGULAR, getColor(R.color.text2)));
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(text("›", 18, Ui.BOLD, Ui.col(this, R.color.accent)));
            row.setOnClickListener(v -> { Lock.markOpen(n.id); openNote(n.id); });
            c.addView(row);
        }
    }

    private static String emoji(String title) {
        String t = title.toLowerCase(Locale.ROOT);
        for (String[] k : KINDS) if (t.contains(k[1].toLowerCase(Locale.ROOT).split(" ")[0])) return k[0];
        if (t.contains("licen") || t.contains("dl")) return "🚗";
        if (t.contains("card")) return "💳";
        return "📄";
    }

    private static String firstPicture(Note n) {
        if (n.blocks == null) return null;
        for (Block b : n.blocks) if (Block.IMAGE.equals(b.type) && b.file != null && !b.file.isEmpty()) return b.file;
        return null;
    }

    /** "Valid till 3 Dec 2030" or the first line with a number on it. */
    private String detail(Note n) {
        String best = "";
        for (String l : n.body.split("\n")) {
            String t = l.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String low = t.toLowerCase(Locale.ROOT);
            if (low.matches(".*(valid|expir|renew|till|until).*") && t.matches(".*\\d.*")) return t;
            if (best.isEmpty() && t.matches(".*\\d{3,}.*")) best = t.replaceAll("(\\d{4})(?=\\d{4})", "$1 ");
        }
        return best;
    }

    private void add() {
        if (!Lock.isOpen(WALLET_KEY)) { asked = false; build(); return; }
        CharSequence[] items = new CharSequence[KINDS.length];
        for (int i = 0; i < KINDS.length; i++) items[i] = KINDS[i][0] + "   " + KINDS[i][1];
        new Ui.Popup(this).setTitle("Add a document").setItems(items, (d, w) -> {
            Note n = new Note();
            n.id = store.newId();
            n.created = System.currentTimeMillis();
            n.updated = n.created;
            n.title = KINDS[w][1].replace(" / certificate", "");
            n.locked = true;
            List<Block> blocks = new ArrayList<>();
            blocks.add(Block.text("Number: \nName on it: \nValid till: \n\n#wallet"));
            n.setBlocks(blocks);
            store.saveNote(n);
            Lock.markOpen(n.id);
            Intent i = new Intent(this, EditorActivity.class);
            i.putExtra(EditorActivity.EXTRA_NOTE_ID, n.id);
            i.putExtra(EditorActivity.EXTRA_AUTO_SCAN, true);
            startActivity(i);
        }).show();
    }
}
