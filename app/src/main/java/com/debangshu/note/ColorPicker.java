package com.debangshu.note;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Word-style colour picker: Automatic / No colour, theme colours with lighter and darker shades,
 * standard colours, recent colours, and "More colours…" for any colour.
 */
public final class ColorPicker {

    private ColorPicker() {}

    public interface Listener { void picked(int color); }   // 0 = automatic / no highlight

    private static final int[] THEME = {0xFF000000, 0xFFFFFFFF, 0xFF44546A, 0xFFE7E6E6, 0xFF4472C4,
            0xFFED7D31, 0xFFA5A5A5, 0xFFFFC000, 0xFF5B9BD5, 0xFF70AD47};
    private static final int[] STANDARD = {0xFFC00000, 0xFFFF0000, 0xFFFFC000, 0xFFFFFF00, 0xFF92D050,
            0xFF00B050, 0xFF00B0F0, 0xFF0070C0, 0xFF002060, 0xFF7030A0};
    private static final int[] HIGHLIGHT = {0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFFFF00FF, 0xFF0000FF,
            0xFFFF0000, 0xFF000080, 0xFF008080, 0xFF008000, 0xFF800080, 0xFF800000, 0xFF808000,
            0xFF808080, 0xFFC0C0C0, 0xFF000000};
    private static final int[] SOFT = {0xFFFFF59D, 0xFFFFE082, 0xFFC5E1A5, 0xFFB2EBF2, 0xFFB3E5FC,
            0xFFF8BBD0, 0xFFE1BEE7, 0xFFFFCC80, 0xFFD7CCC8, 0xFFEEEEEE};

    /** Sticky-note colours: soft paper colours first, then the full theme palette, standard, recent and any colour. */
    private static final int[] PAPER = {0xFFFFF4B8, 0xFFFFF59D, 0xFFFFE082, 0xFFFFE3C7, 0xFFFFCC80, 0xFFFBDDE7,
            0xFFF8BBD0, 0xFFE8DFF7, 0xFFE1BEE7, 0xFFD9ECFA, 0xFFB3E5FC, 0xFFB2EBF2, 0xFFDDF2D5, 0xFFC5E1A5,
            0xFFE9E8E6, 0xFFD7CCC8, 0xFFFFFFFF, 0xFF4A4320, 0xFF26402A, 0xFF1F3647};

    public static void showFill(Activity a, String title, int current, Listener l) {
        show(a, title, 2, current, l);
    }

    /**
     * @param highlight true for the highlighter (Word's highlight colours), false for text colour
     */
    public static void show(Activity a, String title, boolean highlight, int current, Listener l) {
        show(a, title, highlight ? 1 : 0, current, l);
    }

    /** mode 0 = text colour, 1 = highlighter, 2 = fill (sticky notes). */
    private static void show(Activity a, String title, int mode, int current, Listener l) {
        final boolean highlight = mode == 1, fill = mode == 2;
        float dp = a.getResources().getDisplayMetrics().density;
        SharedPreferences p = a.getSharedPreferences("colors", Activity.MODE_PRIVATE);
        String recentKey = fill ? "recent_fill" : highlight ? "recent_mark" : "recent_ink";
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (20 * dp), (int) (6 * dp), (int) (20 * dp), (int) (6 * dp));
        final AlertDialog[] dlg = {null};
        Listener pick = c -> {
            if (c != 0) remember(p, recentKey, c);
            l.picked(c);
            if (dlg[0] != null) dlg[0].dismiss();
        };

        TextView none = new TextView(a);
        none.setText(highlight ? "⊘   No highlight" : "A   Automatic (normal text colour)");
        none.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        none.setTextColor(a.getColor(R.color.text));
        none.setPadding(0, (int) (10 * dp), 0, (int) (10 * dp));
        none.setBackgroundResource(R.drawable.bg_tool);
        none.setOnClickListener(v -> pick.picked(0));
        if (!fill) box.addView(none);

        if (fill) {
            box.addView(label(a, "Sticky note colours"));
            addRows(a, box, PAPER, current, pick, 10);
        }
        if (highlight) {
            box.addView(label(a, "Highlight colours"));
            addRows(a, box, HIGHLIGHT, current, pick, 8);
            box.addView(label(a, "Soft colours"));
            addRows(a, box, SOFT, current, pick, 10);
        } else {
            box.addView(label(a, "Theme colours"));
            int[] grid = new int[THEME.length * 6];
            for (int c = 0; c < THEME.length; c++) {
                int base = THEME[c];
                boolean dark = luminance(base) < 0.2, light = luminance(base) > 0.85;
                float[] mix = dark ? new float[]{0, 0.5f, 0.35f, 0.25f, 0.15f, 0.05f}
                        : light ? new float[]{0, -0.05f, -0.15f, -0.25f, -0.35f, -0.5f}
                        : new float[]{0, 0.8f, 0.6f, 0.4f, -0.25f, -0.5f};
                for (int r = 0; r < 6; r++) grid[r * THEME.length + c] = shade(base, mix[r]);
            }
            addRows(a, box, grid, current, pick, 10);
            box.addView(label(a, "Standard colours"));
            addRows(a, box, STANDARD, current, pick, 10);
        }

        List<Integer> recent = recent(p, recentKey);
        if (!recent.isEmpty()) {
            box.addView(label(a, "Recent colours"));
            int[] r = new int[recent.size()];
            for (int i = 0; i < r.length; i++) r[i] = recent.get(i);
            addRows(a, box, r, current, pick, 10);
        }

        TextView more = new TextView(a);
        more.setText("🎨   More colours…");
        more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        more.setTextColor(a.getColor(R.color.accent));
        more.setPadding(0, (int) (14 * dp), 0, (int) (8 * dp));
        more.setBackgroundResource(R.drawable.bg_tool);
        more.setOnClickListener(v -> custom(a, current != 0 ? current : (fill ? 0xFFFFF4B8 : highlight ? 0xFFFFFF00 : 0xFFFF0000), pick));
        box.addView(more);

        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        dlg[0] = new Ui.Popup(a).setTitle(title).setView(sv).setNegativeButton("Cancel", null).show();
    }

    private static TextView label(Activity a, String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTextColor(a.getColor(R.color.text2));
        t.setPadding(0, (int) (12 * a.getResources().getDisplayMetrics().density), 0, (int) (4 * a.getResources().getDisplayMetrics().density));
        return t;
    }

    private static void addRows(Activity a, LinearLayout box, int[] colors, int current, Listener pick, int perRow) {
        float dp = a.getResources().getDisplayMetrics().density;
        LinearLayout row = null;
        for (int i = 0; i < colors.length; i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(a);
                row.setGravity(Gravity.START);
                box.addView(row);
            }
            final int c = colors[i];
            View sw = new View(a);
            GradientDrawable g = new GradientDrawable();
            g.setColor(c);
            g.setCornerRadius(7 * dp);
            boolean sel = (c | 0xFF000000) == (current | 0xFF000000) && current != 0;
            g.setStroke((int) ((sel ? 3 : 1) * dp), sel ? a.getColor(R.color.accent) : 0x33000000);
            sw.setBackground(g);
            sw.setContentDescription(String.format(Locale.ROOT, "#%06X", c & 0xFFFFFF));
            sw.setOnClickListener(v -> pick.picked(c));
            int size = (int) (24 * dp);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins((int) (2 * dp), (int) (2 * dp), (int) (2 * dp), (int) (2 * dp));
            row.addView(sw, lp);
        }
    }

    // ------------------------------------------------------------------ "More colours…"

    private static void custom(Activity a, int start, Listener pick) {
        float dp = a.getResources().getDisplayMetrics().density;
        float[] hsv = new float[3];
        Color.colorToHSV(start, hsv);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (24 * dp), (int) (12 * dp), (int) (24 * dp), 0);

        View preview = new View(a);
        box.addView(preview, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (56 * dp)));
        EditText hex = new EditText(a);
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        hex.setHint("#RRGGBB");
        SeekBar h = bar(a, box, "Colour (hue)", 360, (int) hsv[0]);
        SeekBar s = bar(a, box, "Strength (saturation)", 100, (int) (hsv[1] * 100));
        SeekBar v = bar(a, box, "Brightness", 100, (int) (hsv[2] * 100));
        box.addView(label(a, "Hex code"));
        box.addView(hex);

        final boolean[] typing = {false};
        Runnable update = () -> {
            int c = Color.HSVToColor(new float[]{h.getProgress(), s.getProgress() / 100f, v.getProgress() / 100f});
            GradientDrawable g = new GradientDrawable();
            g.setColor(c);
            g.setCornerRadius(8 * dp);
            preview.setBackground(g);
            if (!typing[0]) hex.setText(String.format(Locale.ROOT, "#%06X", c & 0xFFFFFF));
        };
        SeekBar.OnSeekBarChangeListener ch = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int x, boolean user) { if (user) update.run(); }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        };
        h.setOnSeekBarChangeListener(ch);
        s.setOnSeekBarChangeListener(ch);
        v.setOnSeekBarChangeListener(ch);
        hex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence q, int a1, int b, int c) { }
            @Override public void onTextChanged(CharSequence q, int a1, int b, int c) { }
            @Override public void afterTextChanged(Editable e) {
                String t = e.toString().trim().replace("#", "");
                if (!t.matches("[0-9A-Fa-f]{6}")) return;
                float[] x = new float[3];
                Color.colorToHSV(0xFF000000 | Integer.parseInt(t, 16), x);
                typing[0] = true;
                h.setProgress((int) x[0]);
                s.setProgress((int) (x[1] * 100));
                v.setProgress((int) (x[2] * 100));
                update.run();
                typing[0] = false;
            }
        });
        update.run();
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        new Ui.Popup(a).setTitle("More colours").setView(sv)
                .setPositiveButton("Use colour", (d, w) ->
                        pick.picked(Color.HSVToColor(new float[]{h.getProgress(), s.getProgress() / 100f, v.getProgress() / 100f})))
                .setNegativeButton("Cancel", null).show();
    }

    private static SeekBar bar(Activity a, LinearLayout box, String name, int max, int value) {
        box.addView(label(a, name));
        SeekBar sb = new SeekBar(a);
        sb.setMax(max);
        sb.setProgress(value);
        box.addView(sb);
        return sb;
    }

    // ------------------------------------------------------------------ helpers

    private static int shade(int c, float amount) {
        if (amount == 0) return c;
        int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
        if (amount > 0) {        // lighter: mix with white
            r = (int) (r + (255 - r) * amount);
            g = (int) (g + (255 - g) * amount);
            b = (int) (b + (255 - b) * amount);
        } else {                 // darker: mix with black
            float k = 1 + amount;
            r = (int) (r * k); g = (int) (g * k); b = (int) (b * k);
        }
        return Color.rgb(r, g, b);
    }

    public static double luminance(int c) {
        return (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0;
    }

    private static List<Integer> recent(SharedPreferences p, String key) {
        List<Integer> out = new ArrayList<>();
        for (String s : p.getString(key, "").split(",")) {
            try { if (!s.isEmpty()) out.add((int) Long.parseLong(s)); } catch (Exception ignored) { }
        }
        return out;
    }

    private static void remember(SharedPreferences p, String key, int c) {
        List<Integer> l = recent(p, key);
        l.remove(Integer.valueOf(c));
        l.add(0, c);
        while (l.size() > 10) l.remove(l.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (int x : l) { if (sb.length() > 0) sb.append(','); sb.append(x & 0xFFFFFFFFL); }
        p.edit().putString(key, sb.toString()).apply();
    }
}
