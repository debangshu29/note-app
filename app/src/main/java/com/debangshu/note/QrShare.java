package com.debangshu.note;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Share a note as QR code(s) to a phone nearby — no internet, no app needed to read a short one. */
public final class QrShare {

    private QrShare() {}

    public static Bitmap qr(String text, int size) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);
        hints.put(EncodeHintType.MARGIN, 2);
        BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
        int w = m.getWidth(), h = m.getHeight();
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.setPixels(px, 0, w, 0, 0, w, h);
        return b;
    }

    /** Shows the note's QR code(s); with several, ‹ › go through them. */
    public static void show(Activity a, String title, String body) {
        List<String> parts = QrCodec.encode(title, body);
        if (parts == null) {
            new Ui.Popup(a).setTitle("Too long for QR codes")
                    .setMessage("This note needs more than " + QrCodec.MAX_PARTS + " QR codes. Share it as a PDF or text instead.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        float dp = a.getResources().getDisplayMetrics().density;
        int size = (int) Math.min(a.getResources().getDisplayMetrics().widthPixels * 0.78f, 900);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, (int) (6 * dp), 0, 0);
        ImageView img = new ImageView(a);
        img.setBackgroundColor(Color.WHITE);
        box.addView(img, new LinearLayout.LayoutParams(size, size));
        TextView info = new Ui.T(a);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        info.setTextColor(a.getColor(R.color.text2));
        info.setGravity(Gravity.CENTER);
        info.setPadding((int) (16 * dp), (int) (10 * dp), (int) (16 * dp), 0);
        box.addView(info);
        final int[] at = {0};
        Runnable paint = () -> {
            try { img.setImageBitmap(qr(parts.get(at[0]), size)); } catch (Exception e) { img.setImageBitmap(null); }
            info.setText(parts.size() == 1
                    ? "Scan with any phone camera. On another phone with Note: home ＋ → Scan a QR code."
                    : "Code " + (at[0] + 1) + " of " + parts.size() + " — on the other phone, Note → home ＋ → Scan a QR code, "
                    + "then scan each one (any order).");
        };
        paint.run();
        Ui.Popup p = new Ui.Popup(a);
        p.setTitle("📱 Share by QR").setView(box).setNegativeButton("Close", null);
        if (parts.size() > 1) {
            p.setPositiveButton("Next ›", null).setNeutralButton("‹ Back", null);
        }
        AlertDialog d = p.create();
        WindowManager.LayoutParams old = a.getWindow().getAttributes();
        float oldBright = old.screenBrightness;
        d.setOnShowListener(x -> {
            WindowManager.LayoutParams lp = a.getWindow().getAttributes();
            lp.screenBrightness = 1f;   // easier to scan
            a.getWindow().setAttributes(lp);
            if (parts.size() > 1) {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> { at[0] = (at[0] + 1) % parts.size(); paint.run(); });
                d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> { at[0] = (at[0] + parts.size() - 1) % parts.size(); paint.run(); });
            }
        });
        d.setOnDismissListener(x -> {
            WindowManager.LayoutParams lp = a.getWindow().getAttributes();
            lp.screenBrightness = oldBright;
            a.getWindow().setAttributes(lp);
        });
        d.show();
    }

    private static final QrCodec.Collector collector = new QrCodec.Collector();

    /** Scans a QR code (Google's scanner; no camera permission needed) and makes a note from it. */
    public static void scan(Activity a) {
        try {
            GmsBarcodeScanning.getClient(a, new GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
                    .startScan()
                    .addOnSuccessListener(code -> got(a, code == null ? null : code.getRawValue()))
                    .addOnFailureListener(e -> Ui.toast(a, "The QR scanner isn't ready (Google Play services is setting it up). Try again in a minute.",
                            Toast.LENGTH_LONG).show());
        } catch (Throwable t) {
            Ui.toast(a, "This phone can't run the QR scanner", Toast.LENGTH_LONG).show();
        }
    }

    private static void got(Activity a, String raw) {
        if (raw == null || raw.isEmpty()) return;
        String[] note = collector.add(raw);
        if (note == null) {
            String id = collector.lastId;
            if (id == null) { Ui.toast(a, "That QR code couldn't be read", Toast.LENGTH_SHORT).show(); return; }
            new Ui.Popup(a).setTitle("Got " + collector.have(id) + " of " + collector.total(id))
                    .setMessage("Still needed: code " + android.text.TextUtils.join(", ", collector.missing(id)) + ".")
                    .setPositiveButton("Scan the next", (d, w) -> scan(a))
                    .setNegativeButton("Later", null).show();
            return;
        }
        Intent i = new Intent(a, EditorActivity.class);
        i.putExtra(Intent.EXTRA_TEXT, note[1]);
        if (!note[0].isEmpty()) i.putExtra(EditorActivity.EXTRA_TITLE, note[0]);
        a.startActivity(i);
        Ui.toast(a, "Note made from the QR code", Toast.LENGTH_SHORT).show();
    }
}
