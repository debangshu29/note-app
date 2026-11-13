package com.debangshu.note;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * The app in English, हिन्दी or বাংলা. The app is written in English; when another language is chosen,
 * text is swapped just before it shows (assets/lang/hi.json, bn.json). Lines like "Spent {0} this month"
 * match text that was put together in code, and the parts in {0} are translated too when they can be.
 * Your own notes are never changed.
 */
public final class L {

    private L() {}

    public static final String P_LANG = "app_lang";   // "" = same as the phone, "en", "hi", "bn"
    public static final String[] CODES = {"", "en", "hi", "bn"};
    public static final String[] NAMES = {"Same as my phone", "English", "हिन्दी (Hindi)", "বাংলা (Bengali)"};

    private static String lang = "en";
    private static String loadedFor = null;
    private static Context app;

    /** The language chosen in Settings, or the phone's if it's Hindi or Bengali. */
    public static String chosen(Context c) {
        SharedPreferences p = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String l = p.getString(P_LANG, "");
        if (l.isEmpty()) {
            String sys = Locale.getDefault().getLanguage();
            if (sys.equals("hi") || sys.equals("bn")) return sys;
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                android.os.LocaleList ll = android.content.res.Resources.getSystem().getConfiguration().getLocales();
                if (ll.size() > 0) {
                    String s = ll.get(0).getLanguage();
                    if (s.equals("hi") || s.equals("bn")) return s;
                }
            }
            return "en";
        }
        return l;
    }

    public static String lang() { return lang; }

    public static boolean english() { return "en".equals(lang); }

    /** Loads the words for the chosen language (cheap to call often). */
    public static synchronized void init(Context c) {
        app = c.getApplicationContext();
        lang = chosen(app);
        if (lang.equals(loadedFor)) return;
        loadedFor = lang;
        Map<String, String> w = new HashMap<>();
        if (!lang.equals("en")) {
            try (InputStream in = app.getAssets().open("lang/" + lang + ".json")) {
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[16384];
                int r;
                while ((r = in.read(b)) > 0) buf.write(b, 0, r);
                JSONObject o = new JSONObject(new String(buf.toByteArray(), "UTF-8"));
                Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    String v = o.optString(k);
                    if (!v.isEmpty()) w.put(k, v);
                }
            } catch (Exception ignored) { }
        }
        Words.use(w);
        if (!lang.equals("en")) {
            final Context a = app;
            new Thread(() -> { try { keep(Store.get(a), a); } catch (Exception ignored) { } }).start();
        }
    }

    /** The text in the chosen language (or as it is, when there's no translation). */
    public static String t(CharSequence s) {
        if (s == null) return null;
        String in = s.toString();
        if (app != null && !chosenCached().equals(loadedFor)) init(app);
        if (english() || in.isEmpty()) return in;
        String r = Words.find(in);
        return r == null ? in : r;
    }

    /**
     * A line with your own words in it: f("At {0}", place) → "{0} पहुँचने पर" with the place filled in as it is
     * (the place itself is never translated).
     */
    public static String f(String template, Object... args) {
        String out = template;
        if (!english()) {
            String tr = Words.templates.get(template);
            if (tr != null) out = tr;
        }
        for (int i = 0; i < args.length; i++) {
            String v = String.valueOf(args[i]);
            out = out.replace("{" + i + "}", v).replace("{#" + i + "}", v);
        }
        return out;
    }

    private static String chosenCached() { return lang; }

    /** For CharSequence callers: keeps styled text (Spanned) as it is. */
    public static CharSequence cs(CharSequence s) {
        if (s == null || english() || !(s instanceof String)) return s;
        return t(s);
    }

    public static CharSequence[] all(CharSequence[] a) {
        if (a == null || english()) return a;
        CharSequence[] o = new CharSequence[a.length];
        for (int i = 0; i < a.length; i++) o[i] = cs(a[i]);
        return o;
    }

    /** Settings → App language: saves it and reloads the words. */
    public static void set(Context c, String code) {
        c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString(P_LANG, code).apply();
        loadedFor = null;
        init(c);
    }

    /** Remembers your own titles and names so they're never translated (called when notes are saved). */
    public static void keep(Store s, Context c) {
        java.util.Set<String> k = new java.util.HashSet<>();
        try {
            for (Note n : s.notes()) {
                String t = n.displayTitle();
                if (t != null && !t.trim().isEmpty()) k.add(t.trim());
                if (n.title != null && !n.title.trim().isEmpty()) k.add(n.title.trim());
            }
            for (Reminder r : s.reminders()) if (r.title != null) k.add(r.title.trim());
            for (Habit h : Habits.all(c)) k.add(h.name.trim());
        } catch (Exception ignored) { }
        Words.keep = k;
        Words.clearCache();
    }

    /** For tests on a computer. */
    static void use(String language, Map<String, String> words) {
        lang = language;
        loadedFor = language;
        Words.use(words);
    }

    /** Month and day names in the chosen language (digits stay 0-9). */
    public static Locale dates() {
        if (english()) return Locale.ENGLISH;
        try {
            return new Locale.Builder().setLanguage(lang).setRegion("IN").setUnicodeLocaleKeyword("nu", "latn").build();
        } catch (Exception e) { return new Locale(lang, "IN"); }
    }

    /** A date written for the screen, e.g. date("EEEE, d MMMM", d) → "शनिवार, 14 नवंबर". */
    public static String date(String pattern, java.util.Date d) {
        return new java.text.SimpleDateFormat(pattern, dates()).format(d);
    }

    /** The Locale to use for dates ("5 मिनट पहले", month names). Null = leave the phone's. */
    public static Locale locale(Context c) {
        String l = c.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(P_LANG, "");
        if (l.isEmpty()) return null;
        if (l.equals("en")) return Locale.ENGLISH;
        try {
            return new Locale.Builder().setLanguage(l).setRegion("IN").setUnicodeLocaleKeyword("nu", "latn").build();
        } catch (Exception e) { return new Locale(l, "IN"); }
    }
}
