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

    /** Turns a picture a quarter turn to the right; returns the new file name. */
    public static String rotate(Context ctx, String name) throws Exception {
        Bitmap b = BitmapFactory.decodeFile(file(ctx, name).getPath());
        if (b == null) throw new Exception("couldn't read the picture");
        Matrix m = new Matrix();
        m.postRotate(90);
        Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (r != b) b.recycle();
        boolean png = name.endsWith(".png");
        String out = newName(png ? "png" : "jpg");
        save(r, file(ctx, out), png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 90);
        r.recycle();
        return out;
    }

    /** Black & white "document" look: grey, brighter paper, stronger ink. Returns the new file name. */
    public static String blackAndWhite(Context ctx, String name) throws Exception {
        Bitmap b = BitmapFactory.decodeFile(file(ctx, name).getPath());
        if (b == null) throw new Exception("couldn't read the picture");
        Bitmap out = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(out);
        c.drawColor(Color.WHITE);
        android.graphics.ColorMatrix grey = new android.graphics.ColorMatrix();
        grey.setSaturation(0);
        float k = 1.6f, t = -0.25f * 255;   // more contrast, pushes paper to white
        android.graphics.ColorMatrix contrast = new android.graphics.ColorMatrix(new float[]{
                k, 0, 0, 0, t + 40,
                0, k, 0, 0, t + 40,
                0, 0, k, 0, t + 40,
                0, 0, 0, 1, 0});
        grey.postConcat(contrast);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new android.graphics.ColorMatrixColorFilter(grey));
        c.drawBitmap(b, 0, 0, p);
        b.recycle();
        String n = newName("jpg");
        save(out, file(ctx, n), Bitmap.CompressFormat.JPEG, 90);
        out.recycle();
        return n;
    }

    /** Copies a picture to the phone's gallery (Pictures/Note). */
    public static boolean saveToGallery(Context ctx, String name) {
        File f = file(ctx, name);
        boolean png = name.endsWith(".png");
        String mime = png ? "image/png" : "image/jpeg";
        String display = "Note_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT)
                .format(new java.util.Date()) + (png ? ".png" : ".jpg");
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, display);
                v.put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime);
                v.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Note");
                Uri u = ctx.getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (u == null) return false;
                try (InputStream in = new java.io.FileInputStream(f);
                     OutputStream os = ctx.getContentResolver().openOutputStream(u)) {
                    if (os == null) return false;
                    copy(in, os);
                }
                return true;
            } else {
                File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_PICTURES), "Note");
                if (!dir.exists() && !dir.mkdirs()) return false;
                File out = new File(dir, display);
                try (InputStream in = new java.io.FileInputStream(f); OutputStream os = new FileOutputStream(out)) {
                    copy(in, os);
                }
                android.media.MediaScannerConnection.scanFile(ctx, new String[]{out.getPath()}, new String[]{mime}, null);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** Copies a picture to the shared folder so other apps can open it. */
    public static File shareCopy(Context ctx, String name, String as) throws Exception {
        File out = new File(ShareProvider.dir(ctx), as);
        try (InputStream in = new java.io.FileInputStream(file(ctx, name)); OutputStream os = new FileOutputStream(out)) {
            copy(in, os);
        }
        return out;
    }

    /** One A4 PDF page holding the picture (for sharing a scanned page as a document). */
    public static File pagePdf(Context ctx, String name, String as) throws Exception {
        Bitmap b = load(ctx, name, 1400);
        if (b == null) throw new Exception("couldn't read the picture");
        int pw = 595, ph = 842, m = 24;
        android.graphics.pdf.PdfDocument doc = new android.graphics.pdf.PdfDocument();
        android.graphics.pdf.PdfDocument.Page page = doc.startPage(new android.graphics.pdf.PdfDocument.PageInfo.Builder(pw, ph, 1).create());
        float s = Math.min((pw - 2f * m) / b.getWidth(), (ph - 2f * m) / b.getHeight());
        float w = b.getWidth() * s, h = b.getHeight() * s;
        float l = (pw - w) / 2, t = (ph - h) / 2;
        page.getCanvas().drawBitmap(b, null, new android.graphics.RectF(l, t, l + w, t + h),
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        doc.finishPage(page);
        File out = new File(ShareProvider.dir(ctx), as);
        try (OutputStream os = new FileOutputStream(out)) { doc.writeTo(os); }
        doc.close();
        b.recycle();
        return out;
    }

    private static void copy(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    /** Files used by a list of blocks (pictures and drawing strokes). */
    public static Set<String> filesOf(List<Block> blocks) {
        Set<String> s = new HashSet<>();
        if (blocks != null) for (Block b : blocks) {
            if (Block.IMAGE.equals(b.type)) {
                if (b.file != null && !b.file.isEmpty()) s.add(b.file);
                if (b.drawing != null && !b.drawing.isEmpty()) s.add(b.drawing);
                if (b.original != null && !b.original.isEmpty()) s.add(b.original);
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
