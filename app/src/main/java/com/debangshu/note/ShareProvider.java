package com.debangshu.note;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Lets other apps (WhatsApp, Gmail…) read the image / PDF we just made, and nothing else.
 * Files live in the app's cache ("shared" folder) and are replaced on every share,
 * so they never pile up.
 */
public class ShareProvider extends ContentProvider {

    public static final String AUTHORITY = "com.debangshu.note.share";

    public static File dir(Context ctx) {
        File d = new File(ctx.getCacheDir(), "shared");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Deletes everything shared before, so only the newest files exist. */
    public static void clear(Context ctx) {
        File[] files = dir(ctx).listFiles();
        if (files != null) for (File f : files) f.delete();
    }

    public static Uri uriFor(File f) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(f.getName()).build();
    }

    /** Where the camera app may write the photo it takes (cache/capture). */
    public static Uri captureUri(File f) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("capture").appendPath(f.getName()).build();
    }

    private boolean isCapture(Uri uri) {
        java.util.List<String> seg = uri.getPathSegments();
        return seg != null && seg.size() == 2 && "capture".equals(seg.get(0));
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        if (isCapture(uri)) {
            String name = uri.getLastPathSegment();
            if (name == null || name.contains("/") || name.contains("..")) throw new FileNotFoundException();
            File d = new File(getContext().getCacheDir(), "capture");
            if (!d.exists()) d.mkdirs();
            return new File(d, name);
        }
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("..")) throw new FileNotFoundException();
        File f = new File(dir(getContext()), name);
        try {
            if (!f.getCanonicalPath().startsWith(dir(getContext()).getCanonicalPath())) throw new FileNotFoundException();
        } catch (IOException e) {
            throw new FileNotFoundException();
        }
        if (!f.exists()) throw new FileNotFoundException();
        return f;
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (isCapture(uri) && mode != null && mode.contains("w")) {
            return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_WRITE
                    | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
        }
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String n = uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment().toLowerCase();
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String sel, String[] args, String sort) {
        File f;
        try { f = fileFor(uri); } catch (FileNotFoundException e) { return null; }
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
