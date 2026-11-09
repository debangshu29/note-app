package com.debangshu.note;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/** Draws notes as a neat PNG card or a PDF document (A4), ready to share. */
public final class NoteRenderer {

    private NoteRenderer() {}

    private static final int INK = Color.rgb(0x1D, 0x1C, 0x21);
    private static final int GREY = Color.rgb(0x8A, 0x87, 0x91);
    private static final int ACCENT = Color.rgb(0x4F, 0x46, 0xE5);
    private static final int LINE = Color.rgb(0xE7, 0xE4, 0xDE);

    /** One note to draw. */
    public static final class Page {
        public final String title, body;
        public Page(String title, String body) { this.title = title == null ? "" : title; this.body = body == null ? "" : body; }
    }

    private static StaticLayout layout(CharSequence text, TextPaint p, int width, float spacing) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), p, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0, spacing)
                .setIncludePad(false)
                .build();
    }

    private static String today() {
        return DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date());
    }

    // ------------------------------------------------------------------ image

    /** Writes a PNG card. Returns false if the note is too long for one image (use PDF then). */
    public static boolean toImage(Page n, File out) throws Exception {
        final int width = 1080, pad = 72, cw = width - 2 * pad;
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(INK);
        tp.setTextSize(58);
        tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        TextPaint bp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        bp.setColor(INK);
        bp.setTextSize(40);
        TextPaint mp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        mp.setColor(GREY);
        mp.setTextSize(28);

        StaticLayout title = n.title.trim().isEmpty() ? null : layout(n.title.trim(), tp, cw, 1.1f);
        StaticLayout body = layout(n.body.trim().isEmpty() ? " " : n.body.trim(), bp, cw, 1.3f);
        StaticLayout meta = layout(today(), mp, cw, 1f);

        int h = 16 + pad + (title == null ? 0 : title.getHeight() + 18) + meta.getHeight() + 40 + 2 + 40
                + body.getHeight() + pad + 60;
        if (h > 9000) return false;

        Bitmap bmp = Bitmap.createBitmap(width, h, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.WHITE);
        Paint accent = new Paint();
        accent.setColor(ACCENT);
        c.drawRect(0, 0, width, 16, accent);

        float y = 16 + pad;
        if (title != null) {
            c.save(); c.translate(pad, y); title.draw(c); c.restore();
            y += title.getHeight() + 18;
        }
        c.save(); c.translate(pad, y); meta.draw(c); c.restore();
        y += meta.getHeight() + 40;
        Paint line = new Paint();
        line.setColor(LINE);
        c.drawRect(pad, y, width - pad, y + 2, line);
        y += 2 + 40;
        c.save(); c.translate(pad, y); body.draw(c); c.restore();

        mp.setTextSize(24);
        c.drawText("Made with Note", pad, h - 36, mp);

        try (OutputStream os = new FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
        } finally {
            bmp.recycle();
        }
        return true;
    }

    // ------------------------------------------------------------------ PDF

    /** Writes an A4 PDF; each note starts on a new page and long notes continue on the next pages. */
    public static void toPdf(List<Page> notes, File out) throws Exception {
        final int W = 595, H = 842, M = 50, CW = W - 2 * M, BOTTOM = H - M - 20;
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(INK);
        tp.setTextSize(20);
        tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        TextPaint bp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        bp.setColor(INK);
        bp.setTextSize(12);
        TextPaint mp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        mp.setColor(GREY);
        mp.setTextSize(9);
        Paint accent = new Paint();
        accent.setColor(ACCENT);
        Paint line = new Paint();
        line.setColor(LINE);

        PdfDocument doc = new PdfDocument();
        int pageNo = 0;
        try {
            for (Page n : notes) {
                StaticLayout title = n.title.trim().isEmpty() ? null : layout(n.title.trim(), tp, CW, 1.1f);
                StaticLayout body = layout(n.body.trim().isEmpty() ? " " : n.body.trim(), bp, CW, 1.35f);

                int firstLine = 0;
                boolean firstPage = true;
                while (firstPage || firstLine < body.getLineCount()) {
                    pageNo++;
                    PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder(W, H, pageNo).create());
                    Canvas c = page.getCanvas();
                    c.drawRect(0, 0, W, 6, accent);
                    float y = M;
                    if (firstPage) {
                        if (title != null) {
                            c.save(); c.translate(M, y); title.draw(c); c.restore();
                            y += title.getHeight() + 6;
                        }
                        c.drawText(today(), M, y + 10, mp);
                        y += 22;
                        c.drawRect(M, y, W - M, y + 0.8f, line);
                        y += 14;
                    }
                    // as many body lines as fit on this page
                    int top = body.getLineTop(firstLine);
                    int last = firstLine;
                    while (last < body.getLineCount() && y + (body.getLineBottom(last) - top) <= BOTTOM) last++;
                    if (last == firstLine) last = Math.min(firstLine + 1, body.getLineCount());
                    int height = body.getLineBottom(last - 1) - top;
                    c.save();
                    c.translate(M, y - top);
                    c.clipRect(0, top, CW, top + height);
                    body.draw(c);
                    c.restore();
                    c.drawText("Note · page " + pageNo, M, H - M + 14, mp);
                    doc.finishPage(page);
                    firstLine = last;
                    firstPage = false;
                }
            }
            try (OutputStream os = new FileOutputStream(out)) {
                doc.writeTo(os);
            }
        } finally {
            doc.close();
        }
    }
}
