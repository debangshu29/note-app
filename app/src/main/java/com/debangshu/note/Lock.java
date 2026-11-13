package com.debangshu.note;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

/**
 * App lock and locked notes: a 4-digit PIN (stored only as a salted hash) and, where the phone
 * has one, fingerprint / face unlock. The app locks again after it has been in the background
 * for the chosen time; opening the camera, gallery or a share screen from Note doesn't count.
 */
public final class Lock {

    private Lock() {}

    public static final String P_ON = "lock_on", P_HASH = "lock_hash", P_SALT = "lock_salt",
            P_BIO = "lock_bio", P_AFTER = "lock_after";
    public static final int[] AFTER_SECONDS = {0, 60, 300, 1800};
    public static final String[] AFTER_NAMES = {"Immediately", "After 1 minute", "After 5 minutes", "After 30 minutes"};
    static final int PIN_LENGTH = 4;

    private static boolean unlocked = false;
    private static long backgroundAt = 0;
    private static long afterMs = 0;
    private static long externalAt = 0;
    private static final Set<Long> openNotes = new HashSet<>();
    private static int wrong = 0;
    private static long waitUntil = 0;

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public static boolean hasPin(Context c) { return prefs(c).getString(P_HASH, null) != null; }
    public static boolean appLockOn(Context c) { return prefs(c).getBoolean(P_ON, false) && hasPin(c); }
    public static boolean bioOn(Context c) { return prefs(c).getBoolean(P_BIO, true) && bioAvailable(c); }

    public static int afterIndex(Context c) {
        int s = prefs(c).getInt(P_AFTER, 0);
        for (int i = 0; i < AFTER_SECONDS.length; i++) if (AFTER_SECONDS[i] == s) return i;
        return 0;
    }

    // ------------------------------------------------------------------ timer

    static void wentToBackground() { backgroundAt = System.currentTimeMillis(); }

    static void cameToFront() {
        if (backgroundAt <= 0) return;
        long now = System.currentTimeMillis();
        long away = now - backgroundAt;
        boolean ownTrip = externalAt > 0 && now - externalAt < 15 * 60 * 1000L;   // camera, gallery, share…
        externalAt = 0;
        backgroundAt = 0;
        if (ownTrip) return;
        if (away > afterMs + 1500) { unlocked = false; openNotes.clear(); }
    }

    /** Note is opening another app for the user (camera, file picker, share): coming back doesn't lock. */
    public static void leavingForOtherApp() { externalAt = System.currentTimeMillis(); }

    /** Call in onResume of every screen with notes on it. Shows the lock screen when needed; returns true then. */
    static boolean screenShowing = false;

    public static boolean guard(Activity a) {
        afterMs = prefs(a).getInt(P_AFTER, 0) * 1000L;
        if (!appLockOn(a) || unlocked) return false;
        if (screenShowing) return true;            // one lock screen is enough
        screenShowing = true;
        Intent i = new Intent(a, LockActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        a.startActivity(i);
        a.overridePendingTransition(0, 0);
        return true;
    }

    static void markUnlocked() { unlocked = true; backgroundAt = 0; }

    public static boolean isOpen(long noteId) { return openNotes.contains(noteId); }
    public static void markOpen(long noteId) { openNotes.add(noteId); }

    // ------------------------------------------------------------------ PIN

    private static String hash(String salt, String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((salt + ":" + pin).getBytes("UTF-8"));
            for (int i = 0; i < 2000; i++) h = md.digest(h);
            return Base64.encodeToString(h, Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    static boolean check(Context c, String pin) {
        SharedPreferences p = prefs(c);
        String salt = p.getString(P_SALT, "");
        String want = p.getString(P_HASH, null);
        return want != null && want.equals(hash(salt, pin));
    }

    static void savePin(Context c, String pin) {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        String salt = Base64.encodeToString(b, Base64.NO_WRAP);
        prefs(c).edit().putString(P_SALT, salt).putString(P_HASH, hash(salt, pin)).apply();
    }

    // ------------------------------------------------------------------ fingerprint / face

    public static boolean bioAvailable(Context c) {
        if (Build.VERSION.SDK_INT < 28) return false;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                android.hardware.biometrics.BiometricManager bm = c.getSystemService(android.hardware.biometrics.BiometricManager.class);
                return bm != null && bm.canAuthenticate(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK)
                        == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS;
            }
            if (Build.VERSION.SDK_INT >= 29) {
                android.hardware.biometrics.BiometricManager bm = c.getSystemService(android.hardware.biometrics.BiometricManager.class);
                return bm != null && bm.canAuthenticate() == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS;
            }
            return c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FINGERPRINT);
        } catch (Exception e) {
            return false;
        }
    }

    static void askBio(Activity a, String title, Runnable ok) {
        if (Build.VERSION.SDK_INT < 28) return;
        try {
            android.hardware.biometrics.BiometricPrompt.Builder b = new android.hardware.biometrics.BiometricPrompt.Builder(a)
                    .setTitle(L.t(title))
                    .setSubtitle(L.t("Use your fingerprint or face"))
                    .setNegativeButton(L.t("Use PIN"), a.getMainExecutor(), (d, w) -> { });
            if (Build.VERSION.SDK_INT >= 30) {
                b.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK);
            }
            b.build().authenticate(new CancellationSignal(), a.getMainExecutor(),
                    new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult r) {
                            ok.run();
                        }
                    });
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ the PIN pad

    public interface Result { void done(boolean ok); }

    public static final int VERIFY = 0, CREATE = 1;

    /** Full-screen PIN pad in a dialog: VERIFY checks the PIN, CREATE asks for a new PIN twice and saves it. */
    public static void showPad(Activity a, int mode, String title, Result r) {
        Dialog d = new Dialog(a, android.R.style.Theme_Material_NoActionBar_Fullscreen);
        final boolean[] answered = {false};
        Result once = ok -> {
            if (answered[0]) return;
            answered[0] = true;
            d.dismiss();
            r.done(ok);
        };
        View pad = pad(a, mode, title, true, once);
        d.setContentView(pad);
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Ui.col(a, R.color.bg)));
        d.setOnCancelListener(x -> once.done(false));
        d.show();
    }

    /** The pad itself (also used by the lock screen). */
    static View pad(Activity a, int mode, String title, boolean cancellable, Result r) {
        float dp = a.getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(a);
        root.setBackgroundColor(Ui.col(a, R.color.bg));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        ImageView icon = new ImageView(a);
        icon.setImageResource(R.drawable.ic_lock);
        icon.setBackgroundResource(R.drawable.bg_circle_accent);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        int ip = (int) (20 * dp);
        icon.setPadding(ip, ip, ip, ip);
        col.addView(icon, new LinearLayout.LayoutParams((int) (72 * dp), (int) (72 * dp)));

        TextView head = new Ui.T(a);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        head.setTypeface(Ui.face(a, Ui.EXTRABOLD));
        head.setTextColor(a.getColor(R.color.text));
        head.setGravity(Gravity.CENTER);
        head.setPadding(0, (int) (18 * dp), 0, (int) (4 * dp));
        col.addView(head);
        TextView sub = new Ui.T(a);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sub.setTypeface(Ui.face(a, Ui.REGULAR));
        sub.setGravity(Gravity.CENTER);
        sub.setPadding((int) (32 * dp), 0, (int) (32 * dp), (int) (18 * dp));
        col.addView(sub);

        LinearLayout dots = new LinearLayout(a);
        dots.setGravity(Gravity.CENTER);
        View[] dotViews = new View[PIN_LENGTH];
        for (int i = 0; i < PIN_LENGTH; i++) {
            View v = new View(a);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (14 * dp), (int) (14 * dp));
            lp.setMargins((int) (9 * dp), 0, (int) (9 * dp), 0);
            dots.addView(v, lp);
            dotViews[i] = v;
        }
        col.addView(dots);

        final StringBuilder entered = new StringBuilder();
        final String[] first = {null};
        final boolean create = mode == CREATE;
        final boolean bio = !create && bioOn(a);
        Runnable paint = () -> {
            for (int i = 0; i < PIN_LENGTH; i++) {
                android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
                g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                if (i < entered.length()) g.setColor(Ui.col(a, R.color.accent));
                else g.setStroke((int) (2 * dp), a.getColor(R.color.hint));
                dotViews[i].setBackground(g);
            }
        };
        Runnable normalText = () -> {
            head.setText(title);
            sub.setTextColor(a.getColor(R.color.text2));
            if (create) sub.setText(first[0] == null ? "Choose a " + PIN_LENGTH + "-digit PIN" : "Enter the same PIN again");
            else sub.setText("Enter your PIN" + (bio ? " or use your fingerprint" : ""));
        };
        normalText.run();
        paint.run();

        Runnable submit = () -> {
            String pin = entered.toString();
            entered.setLength(0);
            if (create) {
                if (first[0] == null) { first[0] = pin; normalText.run(); paint.run(); return; }
                if (first[0].equals(pin)) { savePin(a, pin); r.done(true); return; }
                first[0] = null;
                normalText.run();
                sub.setText("The two PINs were different. Choose a PIN again.");
                sub.setTextColor(a.getColor(R.color.danger));
                paint.run();
                return;
            }
            long now = System.currentTimeMillis();
            if (now < waitUntil) {
                sub.setText("Too many tries. Wait " + ((waitUntil - now) / 1000 + 1) + " seconds.");
                sub.setTextColor(a.getColor(R.color.danger));
                paint.run();
                return;
            }
            if (check(a, pin)) { wrong = 0; r.done(true); return; }
            wrong++;
            if (wrong >= 5) { waitUntil = now + 30_000; wrong = 0; }
            dots.animate().translationX(18 * dp).setDuration(60).withEndAction(() ->
                    dots.animate().translationX(-18 * dp).setDuration(60).withEndAction(() ->
                            dots.animate().translationX(0).setDuration(60).start()).start()).start();
            dots.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            sub.setText(now + 30_000 <= waitUntil + 1 ? "Too many tries. Wait 30 seconds." : "Wrong PIN. Try again.");
            sub.setTextColor(a.getColor(R.color.danger));
            paint.run();
        };

        // keypad
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", bio ? "bio" : "", "0", "del"};
        LinearLayout grid = new LinearLayout(a);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(0, (int) (26 * dp), 0, 0);
        col.addView(grid);
        LinearLayout row = null;
        for (int i = 0; i < keys.length; i++) {
            if (i % 3 == 0) { row = new LinearLayout(a); row.setGravity(Gravity.CENTER); grid.addView(row); }
            String k = keys[i];
            View key;
            if (k.equals("bio") || k.equals("del")) {
                ImageView iv = new ImageView(a);
                iv.setImageResource(k.equals("bio") ? R.drawable.ic_fingerprint : R.drawable.ic_backspace);
                iv.setImageTintList(android.content.res.ColorStateList.valueOf(a.getColor(R.color.text)));
                int p = (int) (22 * dp);
                iv.setPadding(p, p, p, p);
                iv.setContentDescription(L.t(k.equals("bio") ? "Use fingerprint" : "Delete"));
                iv.setBackground(Ui.ripple(Ui.round(0, 36 * dp)));
                key = iv;
            } else {
                TextView t = new Ui.T(a);
                t.setText(k);
                t.setGravity(Gravity.CENTER);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
                t.setTypeface(Ui.face(a, Ui.SEMIBOLD));
                t.setTextColor(a.getColor(R.color.text));
                if (!k.isEmpty()) t.setBackground(Ui.ripple(Ui.round(Ui.col(a, R.color.surface2), 36 * dp)));
                key = t;
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (72 * dp), (int) (72 * dp));
            lp.setMargins((int) (12 * dp), (int) (7 * dp), (int) (12 * dp), (int) (7 * dp));
            row.addView(key, lp);
            if (k.isEmpty()) continue;
            key.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                if (k.equals("bio")) { askBio(a, title, () -> r.done(true)); return; }
                if (k.equals("del")) {
                    if (entered.length() > 0) entered.setLength(entered.length() - 1);
                    paint.run();
                    return;
                }
                if (entered.length() >= PIN_LENGTH) return;
                entered.append(k);
                paint.run();
                if (entered.length() == PIN_LENGTH) v.postDelayed(submit, 120);
            });
        }
        if (cancellable) {
            TextView cancel = new Ui.T(a);
            cancel.setText("Cancel");
            cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            cancel.setTypeface(Ui.face(a, Ui.SEMIBOLD));
            cancel.setTextColor(Ui.col(a, R.color.accent));
            cancel.setPadding((int) (24 * dp), (int) (14 * dp), (int) (24 * dp), (int) (14 * dp));
            cancel.setOnClickListener(v -> r.done(false));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            lp.topMargin = (int) (10 * dp);
            col.addView(cancel, lp);
        }
        if (bio) root.post(() -> askBio(a, title, () -> r.done(true)));
        return root;
    }

    /** Asks for the PIN (or fingerprint) before something private. Creates a PIN first if there is none. */
    public static void verify(Activity a, String title, Result r) {
        if (!hasPin(a)) {
            showPad(a, CREATE, "Set a PIN", ok -> r.done(ok));
            return;
        }
        showPad(a, VERIFY, title, r);
    }
}
