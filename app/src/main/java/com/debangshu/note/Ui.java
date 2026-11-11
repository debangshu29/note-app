package com.debangshu.note;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.Switch;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Set;

/**
 * The look of the app (Note 3 design):
 *  - Plus Jakarta Sans as the app's own font, in 4 weights (applied to every screen and popup)
 *  - popups with rounded corners, pill buttons and a bold title
 *  - menus (lists of choices) slide up from the bottom as sheets
 * Note text itself keeps the note's own font (see Note style).
 */
public final class Ui {

    private Ui() {}

    public static final int REGULAR = 0, SEMIBOLD = 1, BOLD = 2, EXTRABOLD = 3;
    private static final String[] FILES = {"ui/Regular.ttf", "ui/SemiBold.ttf", "ui/Bold.ttf", "ui/ExtraBold.ttf"};
    private static final Typeface[] FACES = new Typeface[4];
    private static Set<Typeface> systemSans;
    public static final String KEEP_FONT = "keepFont";

    // ------------------------------------------------------------------ fonts

    public static Typeface face(Context ctx, int weight) {
        weight = Math.max(0, Math.min(3, weight));
        if (FACES[weight] == null) {
            try { FACES[weight] = Typeface.createFromAsset(ctx.getApplicationContext().getAssets(), FILES[weight]); }
            catch (Exception e) { FACES[weight] = weight >= BOLD ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT; }
        }
        return FACES[weight];
    }

    private static Set<Typeface> sans() {
        if (systemSans == null) {
            Set<Typeface> s = new HashSet<>();
            s.add(Typeface.DEFAULT);
            s.add(Typeface.DEFAULT_BOLD);
            s.add(Typeface.SANS_SERIF);
            for (String fam : new String[]{"sans-serif", "sans-serif-medium", "sans-serif-light", "sans-serif-black",
                    "sans-serif-condensed", "sans-serif-thin"}) {
                for (int st = 0; st < 4; st++) s.add(Typeface.create(fam, st));
            }
            for (int st = 0; st < 4; st++) s.add(Typeface.create(Typeface.DEFAULT, st));
            systemSans = s;
        }
        return systemSans;
    }

    private static Typeface medium, black;

    /** Swaps the phone's font for the app font, keeping the weight (normal / medium / bold / black). */
    public static void applyFont(TextView t) {
        if (KEEP_FONT.equals(t.getTag())) return;
        Typeface tf = t.getTypeface();
        if (tf != null && !sans().contains(tf)) {
            for (Typeface f : FACES) if (f != null && f.equals(tf)) return;   // already ours
            if (!isOurs(tf)) return;   // a special font (serif, script…) chosen on purpose
        }
        if (medium == null) {
            medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            black = Typeface.create("sans-serif-black", Typeface.NORMAL);
        }
        int w = REGULAR;
        if (tf != null) {
            if (Build.VERSION.SDK_INT >= 28) {
                int x = tf.getWeight();
                w = x >= 800 ? EXTRABOLD : x >= 700 ? BOLD : x >= 500 ? SEMIBOLD : REGULAR;
            } else if (tf.equals(black)) w = EXTRABOLD;
            else if (tf.equals(medium) || tf.equals(Typeface.create(medium, Typeface.ITALIC))) w = SEMIBOLD;
            if (tf.isBold() && w < BOLD) w = BOLD;
        }
        boolean italic = tf != null && tf.isItalic();
        Typeface f = face(t.getContext(), w);
        t.setTypeface(italic ? Typeface.create(f, Typeface.ITALIC) : f);
    }

    private static boolean isOurs(Typeface tf) {
        for (Typeface f : FACES) {
            if (f == null) continue;
            for (int st = 0; st < 4; st++) if (Typeface.create(f, st).equals(tf)) return true;
        }
        return false;
    }

    /** Applies the app font to everything under {@code root} except note text (which uses the note's font). */
    public static void fonts(View root) {
        if (root == null || skip(root)) return;
        if (root instanceof TextView) applyFont((TextView) root);
        if (root instanceof TableView) return;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) fonts(g.getChildAt(i));
        }
    }

    private static boolean skip(View v) {
        int id = v.getId();
        return v instanceof SmartEditText || id == R.id.title || id == R.id.sticky_text || KEEP_FONT.equals(v.getTag());
    }

    /** Call in onCreate before setContentView: every screen and popup made from XML gets the app font. */
    public static void install(Activity a) {
        bars(a, Ui.col(a, R.color.bg), !NoteStyle.dark(a));
        LayoutInflater li = a.getLayoutInflater();
        if (li.getFactory2() != null || li.getFactory() != null) return;
        li.setFactory2(new LayoutInflater.Factory2() {
            @Override public View onCreateView(View parent, String name, Context ctx, AttributeSet attrs) {
                return make(name, ctx, attrs);
            }
            @Override public View onCreateView(String name, Context ctx, AttributeSet attrs) {
                return make(name, ctx, attrs);
            }
        });
    }

    private static View make(String name, Context ctx, AttributeSet attrs) {
        TextView v;
        try {
            switch (name) {
                case "TextView": v = new TextView(ctx, attrs); break;
                case "EditText": v = new EditText(ctx, attrs); break;
                case "Button": v = new Button(ctx, attrs); break;
                case "CheckBox": v = new CheckBox(ctx, attrs); break;
                case "RadioButton": v = new RadioButton(ctx, attrs); break;
                case "Switch": v = new Switch(ctx, attrs); break;
                case "CheckedTextView": v = new CheckedTextView(ctx, attrs); break;
                default: return null;
            }
        } catch (Exception e) {
            return null;
        }
        if (!skip(v)) applyFont(v);
        return v;
    }

    // ------------------------------------------------------------------ theme: accent colour, light / dark / black, text size

    public static final String P_THEME = "theme_mode", P_ACCENT = "accent", P_TEXT = "text_scale";
    public static final String[] THEME_NAMES = {"Same as phone", "Light", "Dark", "Black (AMOLED)"};
    public static final String[] ACCENT_NAMES = {"Indigo", "Ocean", "Teal", "Emerald", "Sunset", "Rose", "Grape", "Graphite"};
    public static final int[] ACCENT_STYLES = {R.style.Accent_Indigo, R.style.Accent_Ocean, R.style.Accent_Teal,
            R.style.Accent_Emerald, R.style.Accent_Sunset, R.style.Accent_Rose, R.style.Accent_Grape, R.style.Accent_Graphite};
    public static final int[] ACCENT_PREVIEW = {0xFF5B4CF5, 0xFF2563EB, 0xFF0F9D8E, 0xFF16A34A, 0xFFEA580C, 0xFFE11D74, 0xFF9333EA, 0xFF334155};
    public static final String[] TEXT_NAMES = {"Small", "Normal", "Large", "Extra large"};
    public static final float[] TEXT_SCALES = {0.9f, 1f, 1.12f, 1.25f};

    private static int themeStamp = 0;
    private static final java.util.WeakHashMap<Activity, Integer> stamps = new java.util.WeakHashMap<>();

    private static android.content.SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /** Use in every screen's attachBaseContext: light / dark choice and the app's text size. */
    public static Context wrap(Context base) {
        android.content.SharedPreferences p = prefs(base);
        int mode = p.getInt(P_THEME, 0);
        float scale = p.getFloat(P_TEXT, 1f);
        if (mode == 0 && scale == 1f) return base;
        android.content.res.Configuration c = new android.content.res.Configuration(base.getResources().getConfiguration());
        int night = android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        if (mode == 1) c.uiMode = (c.uiMode & ~night) | android.content.res.Configuration.UI_MODE_NIGHT_NO;
        else if (mode >= 2) c.uiMode = (c.uiMode & ~night) | android.content.res.Configuration.UI_MODE_NIGHT_YES;
        if (scale != 1f) c.fontScale = c.fontScale * scale;
        return base.createConfigurationContext(c);
    }

    /** Accent colour and pure black, put on top of the screen's theme (before its views are made). */
    private static void applyTheme(Activity a) {
        android.content.SharedPreferences p = prefs(a);
        int accent = Math.max(0, Math.min(ACCENT_STYLES.length - 1, p.getInt(P_ACCENT, 0)));
        a.getTheme().applyStyle(ACCENT_STYLES[accent], true);
        if (p.getInt(P_THEME, 0) == 3 && NoteStyle.dark(a)) a.getTheme().applyStyle(R.style.Surface_Black, true);
        stamps.put(a, themeStamp);
    }

    /** Call after changing the theme: open screens rebuild themselves when they come back. */
    public static void themeChanged() { themeStamp++; }

    /** In onResume: rebuilds the screen if the theme was changed somewhere else. Returns true if it did. */
    public static boolean refreshTheme(Activity a) {
        Integer s = stamps.get(a);
        if (s != null && s != themeStamp) { a.recreate(); return true; }
        return false;
    }

    private static final int[][] THEMED = {
            {R.color.accent, R.attr.nAccent}, {R.color.accent2, R.attr.nAccent2}, {R.color.accent_soft, R.attr.nAccentSoft},
            {R.color.bg, R.attr.nBg}, {R.color.surface, R.attr.nSurface}, {R.color.surface2, R.attr.nSurface2},
            {R.color.card_stroke, R.attr.nCardStroke}, {R.color.divider, R.attr.nDivider}};

    /** A colour that follows the theme (accent colour, black mode); other colours come straight from resources. */
    public static int col(Context c, int colorRes) {
        for (int[] t : THEMED) {
            if (t[0] != colorRes) continue;
            TypedValue tv = new TypedValue();
            if (c.getTheme().resolveAttribute(t[1], tv, true)) {
                if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) return tv.data;
                if (tv.resourceId != 0) return c.getColor(tv.resourceId);
            }
            break;
        }
        return c.getColor(colorRes);
    }

    // ------------------------------------------------------------------ small helpers

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    public static Drawable ripple(Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(0x297A7F99), content, null);
    }

    /** Wraps a scrolling view so a popup shows it at most {@code fraction} of the screen tall. */
    public static View capHeight(View content, float fraction) {
        Context c = content.getContext();
        android.widget.FrameLayout f = new android.widget.FrameLayout(c);
        int h = (int) (c.getResources().getDisplayMetrics().heightPixels * fraction);
        f.addView(content, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
        return f;
    }

    /** A big bold heading in the app font. */
    public static void heading(TextView t, int weight) {
        t.setTypeface(face(t.getContext(), weight));
    }

    // ------------------------------------------------------------------ popups

    /**
     * Drop-in replacement for {@code new AlertDialog.Builder(ctx)}. Lists of choices open as a sheet from
     * the bottom; everything else is a rounded card in the middle. Buttons become pills.
     */
    public static class Popup extends AlertDialog.Builder {
        private CharSequence title;
        private boolean items, view, message, forceSheet;

        public Popup(Context c) { super(c); }

        /** Show as a sheet from the bottom even with a custom view (the screen above stays visible). */
        public Popup sheet() { forceSheet = true; return this; }

        @Override public AlertDialog.Builder setTitle(CharSequence t) { title = t; return this; }
        @Override public AlertDialog.Builder setTitle(int id) { title = getContext().getText(id); return this; }
        @Override public AlertDialog.Builder setMessage(CharSequence m) { message = m != null; return super.setMessage(m); }
        @Override public AlertDialog.Builder setMessage(int id) { message = true; return super.setMessage(id); }
        @Override public AlertDialog.Builder setView(View v) { view = true; return super.setView(v); }
        @Override public AlertDialog.Builder setView(int id) { view = true; return super.setView(id); }

        @Override public AlertDialog.Builder setItems(CharSequence[] i, DialogInterface.OnClickListener l) {
            items = true; return super.setItems(i, l);
        }
        @Override public AlertDialog.Builder setItems(int id, DialogInterface.OnClickListener l) {
            items = true; return super.setItems(id, l);
        }
        @Override public AlertDialog.Builder setAdapter(ListAdapter a, DialogInterface.OnClickListener l) {
            items = true; return super.setAdapter(a, l);
        }
        @Override public AlertDialog.Builder setSingleChoiceItems(CharSequence[] i, int c, DialogInterface.OnClickListener l) {
            items = true; return super.setSingleChoiceItems(i, c, l);
        }
        @Override public AlertDialog.Builder setSingleChoiceItems(ListAdapter a, int c, DialogInterface.OnClickListener l) {
            items = true; return super.setSingleChoiceItems(a, c, l);
        }
        @Override public AlertDialog.Builder setMultiChoiceItems(CharSequence[] i, boolean[] c,
                                                                DialogInterface.OnMultiChoiceClickListener l) {
            items = true; return super.setMultiChoiceItems(i, c, l);
        }

        @Override
        public AlertDialog create() {
            final boolean sheet = forceSheet || (items && !view && !message);
            Context c = getContext();
            if (title != null || sheet) super.setCustomTitle(titleView(c, title, sheet));
            AlertDialog d = super.create();
            Window w = d.getWindow();
            if (w != null && sheet) {
                w.setGravity(Gravity.BOTTOM);
                w.setBackgroundDrawable(c.getDrawable(R.drawable.bg_sheet));
                w.setWindowAnimations(R.style.SheetAnim);
            }
            d.setOnShowListener(x -> style(d, sheet));
            return d;
        }
    }

    private static View titleView(Context c, CharSequence title, boolean sheet) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        if (sheet) {
            View handle = new View(c);
            handle.setBackground(round(Ui.col(c, R.color.divider), dp(c, 3)));
            LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(dp(c, 40), dp(c, 5));
            hl.gravity = Gravity.CENTER_HORIZONTAL;
            hl.topMargin = dp(c, 10);
            hl.bottomMargin = dp(c, title == null ? 8 : 2);
            box.addView(handle, hl);
        }
        if (title != null) {
            TextView t = new TextView(c);
            t.setText(title);
            t.setTextColor(c.getColor(R.color.text));
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sheet ? 18 : 20);
            t.setTypeface(face(c, EXTRABOLD));
            t.setPadding(dp(c, 24), dp(c, sheet ? 12 : 24), dp(c, 24), dp(c, sheet ? 8 : 6));
            box.addView(t);
        }
        return box;
    }

    /** Fonts, colours, pill buttons and sheet size for a popup that is showing. */
    public static void style(AlertDialog d, boolean sheet) {
        Window w = d.getWindow();
        if (w == null) return;
        Context c = d.getContext();
        if (sheet) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            View decor = w.getDecorView();
            decor.setPadding(0, 0, 0, 0);
        }
        fonts(w.getDecorView());
        TextView msg = d.findViewById(android.R.id.message);
        if (msg != null) {
            msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            msg.setLineSpacing(dp(c, 3), 1f);
            msg.setTextColor(c.getColor(R.color.text2));
        }
        ListView lv = d.getListView();
        if (lv != null) {
            lv.setDivider(null);
            lv.setSelector(new ColorDrawable(0));
            lv.setPadding(dp(c, 8), 0, dp(c, 8), dp(c, sheet ? 18 : 6));
            lv.setClipToPadding(false);
        }
        Button pos = d.getButton(DialogInterface.BUTTON_POSITIVE);
        Button neg = d.getButton(DialogInterface.BUTTON_NEGATIVE);
        Button neu = d.getButton(DialogInterface.BUTTON_NEUTRAL);
        if (pos != null) {
            String label = pos.getText() == null ? "" : pos.getText().toString().toLowerCase(java.util.Locale.ROOT);
            boolean danger = label.contains("delete") || label.contains("erase") || label.contains("remove");
            pill(pos, danger ? c.getColor(R.color.danger) : Ui.col(c, R.color.accent), c.getColor(R.color.on_accent));
        }
        if (neg != null) pill(neg, Ui.col(c, R.color.surface2), c.getColor(R.color.text));
        if (neu != null) pill(neu, 0, Ui.col(c, R.color.accent));
        View panel = pos != null ? (View) pos.getParent() : null;
        if (panel != null) panel.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, sheet ? 18 : 16));
    }

    private static void pill(Button b, int fill, int text) {
        Context c = b.getContext();
        b.setAllCaps(false);
        b.setTypeface(face(c, BOLD));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(text);
        b.setStateListAnimator(null);
        b.setMinHeight(dp(c, 42));
        b.setMinimumHeight(dp(c, 42));
        b.setPadding(dp(c, 20), 0, dp(c, 20), 0);
        GradientDrawable g = round(fill, dp(c, 21));
        b.setBackground(ripple(g));
        ViewGroup.LayoutParams lp = b.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) lp).setMargins(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4));
            b.setLayoutParams(lp);
        }
    }

    /** Status and navigation bar colours that match a screen's background. */
    public static void bars(Activity a, int color, boolean lightBg) {
        Window w = a.getWindow();
        w.setStatusBarColor(color);
        w.setNavigationBarColor(color);
        int flags = w.getDecorView().getSystemUiVisibility();
        if (lightBg) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        else flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) {
            if (lightBg) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            else flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        w.getDecorView().setSystemUiVisibility(flags);
        w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
    }
}
