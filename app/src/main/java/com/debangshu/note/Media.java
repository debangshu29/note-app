package com.debangshu.note;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Pictures inside notes (photos, scans, PDF pages, drawings), kept in Note's private storage
 * (files/media). Photos are shrunk to at most 1800 px so they don't fill up the phone.
 */
public final class Media {

    private Media() {}

    private static final int MAX_SIDE = 1800;

    public static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "media");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File file(Context ctx, String name) {
        return new File(dir(ctx), name);
    }

    public static String newName(String ext) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "." + ext;
    }

    /** Copies a picture from another app (camera, gallery, scanner), turned upright and shrunk. */
    public static String importImage(Context ctx, Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0) throw new Exception("not a picture");
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample(bounds.outWidth, bounds.outHeight, MAX_SIDE);
        Bitmap bmp;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            bmp = BitmapFactory.decodeStream(in, null, o);
        }
        if (bmp == null) throw new Exception("couldn't read the picture");
        int rot = 0;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in != null) {
                int ori = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (ori == ExifInterface.ORIENTATION_ROTATE_90) rot = 90;
                else if (ori == ExifInterface.ORIENTATION_ROTATE_180) rot = 180;
                else if (ori == ExifInterface.ORIENTATION_ROTATE_270) rot = 270;
            }
        } catch (Exception ignored) { }
        bmp = fit(bmp, rot);
        String name = newName("jpg");
        save(bmp, file(ctx, name), Bitmap.CompressFormat.JPEG, 85);
        bmp.recycle();
        return name;
    }

    /** Draws PDF pages as pictures (at most {@code maxPages}). */
    public static List<String> importPdfPages(Context ctx, Uri uri, int maxPages) throws Exception {
        List<String> out = new ArrayList<>();
        try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) throw new Exception("can't open the PDF");
            try (PdfRenderer pdf = new PdfRenderer(pfd)) {
                int n = Math.min(pdf.getPageCount(), maxPages);
                for (int i = 0; i < n; i++) {
                    try (PdfRenderer.Page page = pdf.openPage(i)) {
                        int w = 1400;
                        int h = Math.max(1, (int) ((long) w * page.getHeight() / Math.max(1, page.getWidth())));
                        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        bmp.eraseColor(Color.WHITE);
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        String name = newName("jpg");
                        save(bmp, file(ctx, name), Bitmap.CompressFormat.JPEG, 88);
                        bmp.recycle();
                        out.add(name);
                    }
                }
            }
        }
        return out;
    }

    public static int pdfPageCount(Context ctx, Uri uri) {
        try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) return 0;
            try (PdfRenderer pdf = new PdfRenderer(pfd)) { return pdf.getPageCount(); }
        } catch (Exception e) {
            return 0;
        }
    }

    public static void save(Bitmap bmp, File f, Bitmap.CompressFormat fmt, int quality) throws Exception {
        try (OutputStream os = new FileOutputStream(f)) {
            bmp.compress(fmt, quality, os);
        }
    }

    private static Bitmap fit(Bitmap b, int rot) {
        int w = b.getWidth(), h = b.getHeight();
        float scale = Math.min(1f, (float) MAX_SIDE / Math.max(w, h));
        if (scale >= 1f && rot == 0) return b;
        Matrix m = new Matrix();
        m.postScale(scale, scale);
        if (rot != 0) m.postRotate(rot);
        Bitmap out = Bitmap.createBitmap(b, 0, 0, w, h, m, true);
        if (out != b) b.recycle();
        return out;
    }

    static int sample(int w, int h, int max) {
        int s = 1;
        while (Math.max(w, h) / (s * 2) >= max) s *= 2;
        return s;
    }

    /** Loads a picture at roughly the size it is shown. */
    public static Bitmap load(Context ctx, String name, int targetWidth) {
        File f = file(ctx, name);
        if (!f.exists()) return null;
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), b);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 1;
        while (b.outWidth / (o.inSampleSize * 2) >= Math.max(200, targetWidth)) o.inSampleSize *= 2;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /** Files used by a list of blocks (pictures and drawing strokes). */
    public static Set<String> filesOf(List<Block> blocks) {
        Set<String> s = new HashSet<>();
        if (blocks != null) for (Block b : blocks) {
            if (Block.IMAGE.equals(b.type)) {
                if (b.file != null && !b.file.isEmpty()) s.add(b.file);
                if (b.drawing != null && !b.drawing.isEmpty()) s.add(b.drawing);
            }
        }
        return s;
    }

    /** Deletes picture files that no note uses any more. */
    public static void deleteUnused(Context ctx, Set<String> used) {
        File[] files = dir(ctx).listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File f : files) {
            // files younger than a minute may belong to a note that is just being edited
            if (!used.contains(f.getName()) && now - f.lastModified() > 60_000) f.delete();
        }
    }

    public static void deleteAll(Context ctx) {
        File[] files = dir(ctx).listFiles();
        if (files != null) for (File f : files) f.delete();
    }
}
