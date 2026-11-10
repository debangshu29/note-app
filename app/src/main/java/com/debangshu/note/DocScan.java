package com.debangshu.note;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import com.google.mlkit.vision.documentscanner.GmsDocumentScanner;
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Document scanner from Google (ML Kit): finds the page edges, straightens and cleans the pages.
 * Runs on the phone; Google Play services provides it.
 */
public final class DocScan {

    private DocScan() {}

    public static final int REQ_SCAN = 7401;

    public static void start(Activity a) {
        try {
            GmsDocumentScannerOptions options = new GmsDocumentScannerOptions.Builder()
                    .setGalleryImportAllowed(true)
                    .setPageLimit(20)
                    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                    .build();
            GmsDocumentScanner scanner = GmsDocumentScanning.getClient(options);
            scanner.getStartScanIntent(a)
                    .addOnSuccessListener(sender -> {
                        try {
                            a.startIntentSenderForResult(sender, REQ_SCAN, null, 0, 0, 0);
                        } catch (Exception e) {
                            Toast.makeText(a, "Couldn't open the scanner", Toast.LENGTH_LONG).show();
                        }
                    })
                    .addOnFailureListener(e -> Toast.makeText(a,
                            "The scanner isn't available yet (Google Play services is setting it up). "
                                    + "Try again in a minute, or use Take photo.", Toast.LENGTH_LONG).show());
        } catch (Throwable t) {
            Toast.makeText(a, "This phone can't run the document scanner. Use Take photo instead.", Toast.LENGTH_LONG).show();
        }
    }

    /** The scanned pages (as picture files), or an empty list. */
    public static List<Uri> pages(int resultCode, Intent data) {
        List<Uri> out = new ArrayList<>();
        if (resultCode != Activity.RESULT_OK || data == null) return out;
        try {
            GmsDocumentScanningResult r = GmsDocumentScanningResult.fromActivityResultIntent(data);
            if (r != null && r.getPages() != null) {
                for (GmsDocumentScanningResult.Page p : r.getPages()) out.add(p.getImageUri());
            }
        } catch (Throwable ignored) { }
        return out;
    }
}
