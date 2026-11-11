package com.debangshu.note;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Draws notes as a PDF (A4 / Letter / A5, portrait or landscape, with margins and page breaks)
 * or as one tall PNG card — with formatting, pictures, sticky notes and tables.
 */
public final class NoteRenderer {

    private NoteRenderer() {}

    private static final int INK = Color.rgb(0x1D, 0x1C, 0x21);
    private static final int GREY = Color.rgb(0x8A, 0x87, 0x91);
    private static final int ACCENT = Color.rgb(0x4F, 0x46, 0xE5);
    private static final int LINE = Color.rgb(0xE7, 0xE4, 0xDE);
    private static final int GRID = Color.rgb(0xB9, 0xB6, 0xBF);

    /** Plain text page (used for "only left to buy" / "only bought"). */
    public static final class Page {
        public final String title, body;
        public Page(String title, String body) { this.title = title == null ? "" : title; this.body = body == null ? "" : body; }
    }

    private static Note asNote(Page p) {
        Note n = new Note();
        n.title = p.title;
        List<Block> l = new ArrayList<>();
        l.add(Block.text(p.body));
        n.setBlocks(l);
        return n;
    }

    private static StaticLayout layout(CharSequence text, TextPaint p, int width, float spacing) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), p, Math.max(10, width))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0, spacing)
                .setIncludePad(false)
                .build();
    }

    private static String today() {
        return DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date());
    }

    // ------------------------------------------------------------------ laying out blocks

    private static final int T_TEXT = 0, T_IMAGE = 1, T_STICKY = 2, T_TABLE = 3, T_PAGE = 4;

    private static final class Item {
        int kind;
        StaticLayout text;          // TEXT, STICKY
        float pad;                  // STICKY / TABLE cell padding
        int color;                  // STICKY background
        String file;                // IMAGE
        float w, h;                 // IMAGE size on the page; STICKY total height
        float xf = 0.5f;            // IMAGE position on the line (0 left .. 1 right)
        StaticLayout[][] cells;     // TABLE
        float[] rowH;
        float colW;
        boolean header;
    }

    private static List<Item> layoutBlocks(Context ctx, Note n, int cw, float body) {
        float spToPx = body / RichText.NORMAL_SIZE;
        TextPaint bp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        bp.setColor(INK);
        bp.setTextSize(body);
        TextPaint hp = new TextPaint(bp);
        hp.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        List<Item> out = new ArrayList<>();
        for (Block b : n.blocksOrText()) {
            Item it = new Item();
            switch (b.type) {
                case Block.IMAGE: {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(Media.file(ctx, b.file).getPath(), o);
                    if (o.outWidth <= 0) continue;
                    it.kind = T_IMAGE;
                    it.file = b.file;
                    it.w = cw * Math.max(0.1f, Math.min(1f, b.width));
                    it.h = it.w * (b.aspect > 0 ? b.aspect : (float) o.outHeight / o.outWidth);
                    it.xf = Math.max(0f, Math.min(1f, b.x));
                    break;
                }
                case Block.STICKY: {
                    if (b.text.trim().isEmpty()) continue;
                    it.kind = T_STICKY;
                    it.pad = body * 0.8f;
                    it.color = b.argb != 0 ? b.argb : NoteStyle.stickyPrintColor(b.color);
                    TextPaint sp = bp;
                    if (ColorPicker.luminance(it.color) < 0.4) { sp = new TextPaint(bp); sp.setColor(0xFFF2F1F5); }
                    it.text = layout(b.text, sp, (int) (cw - 2 * it.pad), 1.3f);
                    it.h = it.text.getHeight() + 2 * it.pad;
                    break;
                }
                case Block.TABLE: {
                    int cols = Math.max(1, b.columns());
                    if (b.rows.isEmpty()) continue;
                    it.kind = T_TABLE;
                    it.header = b.header;
                    it.pad = body * 0.4f;
                    it.colW = (float) cw / cols;
                    it.cells = new StaticLayout[b.rows.size()][cols];
                    it.rowH = new float[b.rows.size()];
                    for (int r = 0; r < b.rows.size(); r++) {
                        float rh = 0;
                        for (int c = 0; c < cols; c++) {
                            String v = c < b.rows.get(r).size() ? b.rows.get(r).get(c) : "";
                            StaticLayout l = layout(v == null ? "" : v, b.header && r == 0 ? hp : bp,
                                    (int) (it.colW - 2 * it.pad), 1.2f);
                            it.cells[r][c] = l;
                            rh = Math.max(rh, l.getHeight());
                        }
                        it.rowH[r] = Math.max(rh, body * 1.2f) + 2 * it.pad;
                    }
                    break;
                }
                case Block.PAGE:
                    it.kind = T_PAGE;
                    break;
                default: {
                    if (b.text.isEmpty()) continue;
                    it.kind = T_TEXT;
                    CharSequence cs = RichText.forDrawing(RichText.build(b.text, b.spans), spToPx);
                    it.text = layout(cs, bp, cw, 1.35f);
                    break;
                }
            }
            out.add(it);
        }
        return out;
    }

    private static void drawSticky(Canvas c, Item it, float x, float y, float cw) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(it.color);
        c.drawRoundRect(new RectF(x, y, x + cw, y + it.h), it.pad / 2, it.pad / 2, p);
        c.save();
        c.translate(x + it.pad, y + it.pad);
        it.text.draw(c);
        c.restore();
    }

    private static void drawImage(Context ctx, Canvas c, Item it, float x, float y, float cw, float w, float h) {
        Bitmap bmp = Media.load(ctx, it.file, (int) Math.min(1600, w * 2));
        if (bmp == null) return;
        float left = x + (cw - w) * it.xf;
        c.drawBitmap(bmp, null, new RectF(left, y, left + w, y + h), new Paint(Paint.FILTER_BITMAP_FLAG));
        bmp.recycle();
    }

    private static void drawTableRows(Canvas c, Item it, float x, float y, int from, int to, float cw) {
        Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        grid.setColor(GRID);
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(Math.max(0.6f, it.pad / 8));
        Paint head = new Paint();
        head.setColor(Color.rgb(0xE9, 0xE8, 0xFC));
        float yy = y;
        int cols = it.cells[0].length;
        for (int r = from; r < to; r++) {
            if (r == 0 && it.header) {
                c.drawRect(x, yy, x + cw, yy + it.rowH[r], head);
            }
            for (int col = 0; col < cols; col++) {
                float cx = x + col * it.colW;
                c.drawRect(cx, yy, cx + it.colW, yy + it.rowH[r], grid);
                c.save();
                c.translate(cx + it.pad, yy + it.pad);
                it.cells[r][col].draw(c);
                c.restore();
            }
            yy += it.rowH[r];
        }
    }

    // ------------------------------------------------------------------ PDF

    private static int[] pageSize(Note n) {
        int w = 595, h = 842;                                  // A4 (points)
        if ("LETTER".equals(n.pageSize)) { w = 612; h = 792; }
        else if ("A5".equals(n.pageSize)) { w = 420; h = 595; }
        if (n.landscape) { int t = w; w = h; h = t; }
        return new int[]{w, h};
    }

    private static int margin(Note n) {
        return n.margins == 0 ? 28 : n.margins == 2 ? 72 : 50;
    }

    /** PDF of notes: each note starts on a new page, page breaks start a new page. */
    public static void toPdf(Context ctx, List<Note> notes, File out) throws Exception {
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(INK);
        tp.setTextSize(20);
        tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        TextPaint mp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        mp.setColor(GREY);
        mp.setTextSize(9);
        Paint accent = new Paint();
        accent.setColor(ACCENT);
        Paint line = new Paint();
        line.setColor(LINE);

        PdfDocument doc = new PdfDocument();
        final int[] pageNo = {0};
        try {
            for (Note n : notes) {
                int[] size = pageSize(n);
                final int W = size[0], H = size[1], M = margin(n), CW = W - 2 * M, BOTTOM = H - M - 12;
                final float body = 12f, gap = 7f;
                List<Item> items = layoutBlocks(ctx, n, CW, body);

                final PdfDocument.Page[] page = {null};
                final float[] y = {0};
                final boolean[] empty = {true};
                Runnable newPage = () -> {
                    if (page[0] != null) {
                        page[0].getCanvas().drawText("Note · page " + pageNo[0], M, H - M + 14, mp);
                        doc.finishPage(page[0]);
                    }
                    pageNo[0]++;
                    page[0] = doc.startPage(new PdfDocument.PageInfo.Builder(W, H, pageNo[0]).create());
                    page[0].getCanvas().drawRect(0, 0, W, 5, accent);
                    y[0] = M;
                    empty[0] = true;
                };
                newPage.run();

                // header
                Canvas c0 = page[0].getCanvas();
                if (n.title != null && !n.title.trim().isEmpty()) {
                    StaticLayout t = layout(n.title.trim(), tp, CW, 1.1f);
                    c0.save(); c0.translate(M, y[0]); t.draw(c0); c0.restore();
                    y[0] += t.getHeight() + 6;
                }
                c0.drawText(today(), M, y[0] + 10, mp);
                y[0] += 22;
                c0.drawRect(M, y[0], W - M, y[0] + 0.8f, line);
                y[0] += 14;

                for (Item it : items) {
                    Canvas c = page[0].getCanvas();
                    switch (it.kind) {
                        case T_PAGE:
                            if (!empty[0] || y[0] > M + 1) newPage.run();
                            continue;
                        case T_TEXT: {
                            StaticLayout L = it.text;
                            int first = 0;
                            while (first < L.getLineCount()) {
                                int top = L.getLineTop(first);
                                int last = first;
                                while (last < L.getLineCount() && y[0] + (L.getLineBottom(last) - top) <= BOTTOM) last++;
                                if (last == first) {
                                    if (!empty[0]) { newPage.run(); continue; }
                                    last = first + 1;
                                }
                                c = page[0].getCanvas();
                                int height = L.getLineBottom(last - 1) - top;
                                c.save();
                                c.translate(M, y[0] - top);
                                c.clipRect(0, top, CW, top + height);
                                L.draw(c);
                                c.restore();
                                y[0] += height;
                                empty[0] = false;
                                first = last;
                                if (first < L.getLineCount()) newPage.run();
                            }
                            break;
                        }
                        case T_IMAGE: {
                            float w = it.w, h = it.h, maxH = BOTTOM - M;
                            if (h > maxH) { w = w * maxH / h; h = maxH; }
                            if (y[0] + h > BOTTOM && !empty[0]) newPage.run();
                            drawImage(ctx, page[0].getCanvas(), it, M, y[0], CW, w, h);
                            y[0] += h;
                            empty[0] = false;
                            break;
                        }
                        case T_STICKY: {
                            if (y[0] + it.h > BOTTOM && !empty[0]) newPage.run();
                            drawSticky(page[0].getCanvas(), it, M, y[0], CW);
                            y[0] += it.h;
                            empty[0] = false;
                            break;
                        }
                        case T_TABLE: {
                            int r = 0;
                            while (r < it.rowH.length) {
                                int end = r;
                                float hh = 0;
                                while (end < it.rowH.length && y[0] + hh + it.rowH[end] <= BOTTOM) { hh += it.rowH[end]; end++; }
                                if (end == r) {
                                    if (!empty[0]) { newPage.run(); continue; }
                                    end = r + 1;
                                    hh = it.rowH[r];
                                }
                                drawTableRows(page[0].getCanvas(), it, M, y[0], r, end, CW);
                                y[0] += hh;
                                empty[0] = false;
                                r = end;
                                if (r < it.rowH.length) newPage.run();
                            }
                            break;
                        }
                    }
                    y[0] += gap;
                }
                page[0].getCanvas().drawText("Note · page " + pageNo[0], M, H - M + 14, mp);
                doc.finishPage(page[0]);
            }
            try (OutputStream os = new FileOutputStream(out)) {
                doc.writeTo(os);
            }
        } finally {
            doc.close();
        }
    }

    public static void toPdfPages(Context ctx, List<Page> pages, File out) throws Exception {
        List<Note> notes = new ArrayList<>();
        for (Page p : pages) notes.add(asNote(p));
        toPdf(ctx, notes, out);
    }

    // ------------------------------------------------------------------ image

    /** One tall PNG card. Returns false if the note is too long for one image (use PDF then). */
    public static boolean toImage(Context ctx, Note n, File out) throws Exception {
        final int width = 1080, pad = 72, cw = width - 2 * pad;
        final float body = 40f, gap = 24f;
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(INK);
        tp.setTextSize(58);
        tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        TextPaint mp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        mp.setColor(GREY);
        mp.setTextSize(28);

        StaticLayout title = n.title == null || n.title.trim().isEmpty() ? null : layout(n.title.trim(), tp, cw, 1.1f);
        StaticLayout meta = layout(today(), mp, cw, 1f);
        List<Item> items = layoutBlocks(ctx, n, cw, body);

        float h = 16 + pad + (title == null ? 0 : title.getHeight() + 18) + meta.getHeight() + 40 + 2 + 40;
        for (Item it : items) {
            switch (it.kind) {
                case T_TEXT: h += it.text.getHeight(); break;
                case T_IMAGE: case T_STICKY: h += it.h; break;
                case T_TABLE: for (float r : it.rowH) h += r; break;
                case T_PAGE: h += 40; break;
            }
            h += gap;
        }
        h += pad + 60;
        if (h > 12000) return false;

        Bitmap bmp = Bitmap.createBitmap(width, (int) h, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.WHITE);
        Paint accent = new Paint();
        accent.setColor(ACCENT);
        c.drawRect(0, 0, width, 16, accent);
        Paint line = new Paint();
        line.setColor(LINE);

        float y = 16 + pad;
        if (title != null) { c.save(); c.translate(pad, y); title.draw(c); c.restore(); y += title.getHeight() + 18; }
        c.save(); c.translate(pad, y); meta.draw(c); c.restore();
        y += meta.getHeight() + 40;
        c.drawRect(pad, y, width - pad, y + 2, line);
        y += 2 + 40;

        for (Item it : items) {
            switch (it.kind) {
                case T_TEXT:
                    c.save(); c.translate(pad, y); it.text.draw(c); c.restore();
                    y += it.text.getHeight();
                    break;
                case T_IMAGE:
                    drawImage(ctx, c, it, pad, y, cw, it.w, it.h);
                    y += it.h;
                    break;
                case T_STICKY:
                    drawSticky(c, it, pad, y, cw);
                    y += it.h;
                    break;
                case T_TABLE: {
                    drawTableRows(c, it, pad, y, 0, it.rowH.length, cw);
                    for (float r : it.rowH) y += r;
                    break;
                }
                case T_PAGE: {
                    Paint dash = new Paint();
                    dash.setColor(GRID);
                    for (float x = pad; x < width - pad; x += 24) c.drawRect(x, y + 19, x + 12, y + 21, dash);
                    y += 40;
                    break;
                }
            }
            y += gap;
        }
        mp.setTextSize(24);
        c.drawText("Made with Note", pad, h - 36, mp);

        try (OutputStream os = new FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
        } finally {
            bmp.recycle();
        }
        return true;
    }

    public static boolean toImage(Context ctx, Page p, File out) throws Exception {
        return toImage(ctx, asNote(p), out);
    }
}
