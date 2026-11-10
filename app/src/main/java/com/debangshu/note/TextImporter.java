package com.debangshu.note;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * Turns a shared / picked file into note text:
 *   - text files: read as they are
 *   - photos, screenshots: text is read ON THE PHONE (Google ML Kit, nothing is uploaded)
 *   - PDFs: each page is drawn and its text read the same way
 * Run off the UI thread.
 */
public final class TextImporter {

    private TextImporter() {}

    private static final int MAX_PDF_PAGES = 20;

    public static final class Result {
        public String title = "";
        public String text = "";
    }

    public static Result read(Context ctx, Uri uri, String mime) throws Exception {
        Result r = new Result();
        String name = displayName(ctx, uri);
        r.title = stripExtension(name);
        String type = mime != null ? mime : ctx.getContentResolver().getType(uri);
        if (type == null) type = guessType(name);

        if (type.startsWith("image/")) {
            r.text = ocr(InputImage.fromFilePath(ctx, uri));
            if (r.title.isEmpty() || r.title.matches("(?i)(img|image|screenshot|pxl|dsc|whatsapp).*")) r.title = "";
        } else if (type.equals("application/pdf")) {
            r.text = readPdf(ctx, uri);
        } else {
            r.text = readText(ctx, uri);
        }
        r.text = tidy(r.text);
        return r;
    }

    // ------------------------------------------------------------------ text

    private static String readText(Context ctx, Uri uri) throws Exception {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("can't open the file");
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n, total = 0;
            while ((n = in.read(b)) > 0 && total < 2_000_000) { buf.write(b, 0, n); total += n; }
            byte[] data = buf.toByteArray();
            int zeros = 0;
            for (int i = 0; i < Math.min(data.length, 4000); i++) if (data[i] == 0) zeros++;
            if (zeros > 5) throw new Exception("this kind of file has no readable text");
            return new String(data, "UTF-8");
        }
    }

    // ------------------------------------------------------------------ images (on-device text reading)

    private static TextRecognizer recognizer;

    private static synchronized TextRecognizer recognizer() {
        if (recognizer == null) recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        return recognizer;
    }

    private static String ocr(InputImage image) throws Exception {
        try {
            Text t = Tasks.await(recognizer().process(image), 60, TimeUnit.SECONDS);
            return t.getText();
        } catch (java.util.concurrent.ExecutionException e) {
            String msg = String.valueOf(e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            if (msg.toLowerCase().contains("download") || msg.toLowerCase().contains("unavailable")) {
                throw new Exception("The text reader is still being set up by Google Play services. "
                        + "Please try again in a minute (needs internet once).");
            }
            throw new Exception("Couldn't read text from it: " + msg);
        }
    }

    // ------------------------------------------------------------------ PDF

    private static String readPdf(Context ctx, Uri uri) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) throw new Exception("can't open the PDF");
            try (PdfRenderer pdf = new PdfRenderer(pfd)) {
                int pages = Math.min(pdf.getPageCount(), MAX_PDF_PAGES);
                for (int i = 0; i < pages; i++) {
                    Bitmap bmp;
                    try (PdfRenderer.Page page = pdf.openPage(i)) {
                        int w = 1600;
                        int h = Math.max(1, (int) ((long) w * page.getHeight() / Math.max(1, page.getWidth())));
                        bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        bmp.eraseColor(Color.WHITE);
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    }
                    String text = ocr(InputImage.fromBitmap(bmp, 0));
                    bmp.recycle();
                    if (!text.trim().isEmpty()) {
                        if (sb.length() > 0) sb.append("\n\n");
                        sb.append(text.trim());
                    }
                }
                if (pdf.getPageCount() > MAX_PDF_PAGES) {
                    sb.append("\n\n(Only the first ").append(MAX_PDF_PAGES).append(" pages were added.)");
                }
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ helpers

    public static String displayName(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) return n;
            }
        } catch (Exception ignored) { }
        String last = uri.getLastPathSegment();
        return last == null ? "" : last;
    }

    private static String stripExtension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base.replace('_', ' ').trim();
    }

    private static String guessType(String name) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.matches(".*\\.(jpe?g|png|webp|heic|bmp|gif)$")) return "image/*";
        return "text/plain";
    }

    /** Joins broken lines a bit and removes extra blank lines. */
    static String tidy(String s) {
        if (s == null) return "";
        s = s.replace("\r\n", "\n").replace('\r', '\n');
        s = s.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }
}
