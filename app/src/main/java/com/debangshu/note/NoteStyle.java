package com.debangshu.note;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;

/**
 * Note style: background colour (solid, gradient or any custom colour), paper (12 kinds),
 * paper line colour and the note's font.
 */
public final class NoteStyle {

    private NoteStyle() {}

    // ------------------------------------------------------------------ colours
    // Index 0 = default (app background). 1..7 are the original colours (kept so old notes look the same).

    public static final String[] COLOR_NAMES = {"Default", "Yellow", "Green", "Blue", "Pink", "Orange", "Purple", "Grey",
            "Mint", "Teal", "Sky", "Lavender", "Rose", "Peach", "Sand", "Lime", "Lilac", "Cream", "Slate",
            "Sunset", "Ocean", "Aurora", "Candy", "Lemonade", "Dusk"};
    private static final int[] LIGHT = {0, 0xFFFFF4B8, 0xFFDDF2D5, 0xFFD9ECFA, 0xFFFBDDE7, 0xFFFFE3C7, 0xFFE8DFF7, 0xFFE9E8E6,
            0xFFD5F5EA, 0xFFCDEFF1, 0xFFDCEBFF, 0xFFE9E3FF, 0xFFFFE0E7, 0xFFFFE6DA, 0xFFF3EBDD, 0xFFEAF6C9, 0xFFF5E1F7,
            0xFFFFF8E6, 0xFFE2E8F0,
            0xFFFFE1C6, 0xFFD3ECFF, 0xFFE4DDFF, 0xFFFFDDEB, 0xFFFFF6BF, 0xFFE3DDF7};
    private static final int[] LIGHT2 = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0xFFFFC9D6, 0xFFD2F5EC, 0xFFD3F3EE, 0xFFE2E4FF, 0xFFFFDCC8, 0xFFFFD9CF};
    private static final int[] DARK = {0, 0xFF4A4320, 0xFF26402A, 0xFF1F3647, 0xFF472A35, 0xFF4A3420, 0xFF362D4A, 0xFF333336,
            0xFF1C3D34, 0xFF1B3A3D, 0xFF1F3352, 0xFF2D284D, 0xFF4A2530, 0xFF4A3026, 0xFF3A3428, 0xFF34401E, 0xFF42264A,
            0xFF3B3627, 0xFF2B323D,
            0xFF4A3020, 0xFF173550, 0xFF2C2552, 0xFF4A2338, 0xFF4A4220, 0xFF2E2648};
    private static final int[] DARK2 = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0xFF4A2234, 0xFF173F38, 0xFF17403A, 0xFF2A2C55, 0xFF4A2E22, 0xFF4A2A2A};

    /** Sticky-note blocks inside notes (old notes): the first six colours. */
    public static final String[] STICKY_NAMES = {"Yellow", "Green", "Blue", "Pink", "Orange", "Purple"};

    public static boolean dark(Context ctx) {
        return (ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Background colour for a colour index, or 0 for the normal background. */
    public static int noteColor(Context ctx, int index) {
        if (index <= 0 || index >= LIGHT.length) return 0;
        return dark(ctx) ? DARK[index] : LIGHT[index];
    }

    /** Main background colour of a note (custom colours are dimmed in dark mode), 0 = normal background. */
    public static int noteColor(Context ctx, Note n) {
        if (n == null) return 0;
        if (n.customColor != 0) return dark(ctx) ? dim(n.customColor) : n.customColor;
        return noteColor(ctx, n.color);
    }

    /** Second colour for gradient styles, or 0. */
    public static int noteColor2(Context ctx, Note n) {
        if (n == null || n.customColor != 0 || n.color <= 0 || n.color >= LIGHT.length) return 0;
        return dark(ctx) ? DARK2[n.color] : LIGHT2[n.color];
    }

    public static boolean isGradient(int index) {
        return index > 0 && index < LIGHT2.length && LIGHT2[index] != 0;
    }

    /** Colour swatch for the style picker (two colours for gradients). */
    public static int[] swatch(Context ctx, int index) {
        boolean d = dark(ctx);
        int a = index == 0 ? ctx.getColor(R.color.bg) : (d ? DARK : LIGHT)[index];
        int b = isGradient(index) ? (d ? DARK2 : LIGHT2)[index] : a;
        return new int[]{a, b};
    }

    /** The note's background as a drawable (solid or gradient), or null for the normal background. */
    public static Drawable background(Context ctx, Note n, float radius) {
        int a = noteColor(ctx, n);
        if (a == 0) return null;
        int b = noteColor2(ctx, n);
        GradientDrawable g = b == 0 ? new GradientDrawable()
                : new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{a, b});
        if (b == 0) g.setColor(a);
        g.setCornerRadius(radius);
        return g;
    }

    /** A custom colour pushed towards the dark background so light text stays readable. */
    static int dim(int c) {
        float k = 0.28f;
        int base = 0xFF0E0F14;
        return Color.rgb((int) (Color.red(c) * k + Color.red(base) * (1 - k)),
                (int) (Color.green(c) * k + Color.green(base) * (1 - k)),
                (int) (Color.blue(c) * k + Color.blue(base) * (1 - k)));
    }

    public static int stickyColor(Context ctx, int stickyIndex) {
        int i = Math.max(0, Math.min(STICKY_NAMES.length - 1, stickyIndex)) + 1;
        return dark(ctx) ? DARK[i] : LIGHT[i];
    }

    /** Light colours for printing (PDF / image are always on white). */
    public static int stickyPrintColor(int stickyIndex) {
        int i = Math.max(0, Math.min(STICKY_NAMES.length - 1, stickyIndex)) + 1;
        return LIGHT[i];
    }

    // ------------------------------------------------------------------ paper

    public static final int PAPER_PLAIN = 0, PAPER_LINED = 1, PAPER_GRID = 2, PAPER_DOTS = 3,
            PAPER_MARGIN = 4, PAPER_LEGAL = 5, PAPER_SMALL_GRID = 6, PAPER_ENGINEER = 7, PAPER_CROSS = 8,
            PAPER_ISO = 9, PAPER_MUSIC = 10, PAPER_CORNELL = 11;
    public static final String[] PAPER_NAMES = {"Plain", "Lined", "Grid", "Dotted", "Lined + margin", "Legal pad",
            "Small grid", "Engineering", "Crosses", "Isometric", "Music", "Cornell"};
    /** Order shown in the picker. */
    public static final int[] PAPER_ORDER = {PAPER_PLAIN, PAPER_LINED, PAPER_MARGIN, PAPER_LEGAL, PAPER_GRID,
            PAPER_SMALL_GRID, PAPER_ENGINEER, PAPER_DOTS, PAPER_CROSS, PAPER_ISO, PAPER_MUSIC, PAPER_CORNELL};

    // ------------------------------------------------------------------ paper line colours

    public static final String[] LINE_NAMES = {"Auto", "Blue", "Red", "Green", "Purple", "Brown", "Black"};
    public static final int[] LINE_COLORS = {0, 0xFF4F7BE8, 0xFFE0555A, 0xFF2FA36B, 0xFF8B5CF6, 0xFF9A6B4F, 0xFF222222};

    /** Paper line colour: the chosen colour, faded, or a soft version of the text colour. */
    public static int lineColor(Context ctx, Note n, int textColor) {
        if (n != null && n.lineColor != 0) return (n.lineColor & 0x00FFFFFF) | 0x59000000;
        return (textColor & 0x00FFFFFF) | 0x1E000000;
    }

    // ------------------------------------------------------------------ note font

    public static final String[] FONT_IDS = {"", "sans-serif", "serif", "a:Lora", "a:PlayfairDisplay", "a:RobotoSlab",
            "a:Poppins", "a:Nunito", "a:Comfortaa", "a:Caveat", "a:PatrickHand", "a:Kalam", "a:IndieFlower",
            "a:CourierPrime", "monospace"};
    public static final String[] FONT_NAMES = {"Note (default)", "Roboto", "Serif", "Lora", "Playfair", "Roboto Slab",
            "Poppins", "Nunito", "Comfortaa", "Caveat", "Patrick Hand", "Kalam", "Indie Flower", "Courier Prime",
            "Monospace"};

    /** The note's text font; {@code bold} for the title. */
    public static Typeface font(Context ctx, Note n, boolean bold) {
        String id = n == null ? "" : n.font;
        if (id == null || id.isEmpty()) return Ui.face(ctx, bold ? Ui.EXTRABOLD : Ui.REGULAR);
        Typeface t = Fonts.get(id);
        if (t == null) t = Typeface.DEFAULT;
        return bold ? Typeface.create(t, Typeface.BOLD) : t;
    }

    // ------------------------------------------------------------------ the paper drawable

    /** Paper pattern drawn behind the note; {@code step} is the text line height in pixels. */
    public static final class Paper extends Drawable {
        private final int kind;
        private final float step;
        private final float offset;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strong = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint margin = new Paint(Paint.ANTI_ALIAS_FLAG);

        public Paper(int kind, float step, float offset, int lineColor) {
            this.kind = kind;
            this.step = Math.max(8, step);
            this.offset = offset;
            float w = Math.max(1f, step / 40f);
            p.setColor(lineColor);
            p.setStrokeWidth(w);
            strong.setColor(lineColor);
            strong.setAlpha(Math.min(255, Color.alpha(lineColor) * 2 + 20));
            strong.setStrokeWidth(w * 1.4f);
            margin.setColor(0x80E0555A);
            margin.setStrokeWidth(w * 1.3f);
        }

        @Override
        public void draw(Canvas c) {
            if (kind == PAPER_PLAIN) return;
            int w = getBounds().width(), h = getBounds().height();
            float s = step;
            switch (kind) {
                case PAPER_LINED:
                    for (float y = offset; y < h; y += s) c.drawLine(0, y, w, y, p);
                    break;
                case PAPER_MARGIN: {
                    for (float y = offset; y < h; y += s) c.drawLine(0, y, w, y, p);
                    float x = s * 0.55f;
                    c.drawLine(x, 0, x, h, margin);
                    break;
                }
                case PAPER_LEGAL: {
                    for (float y = offset; y < h; y += s) c.drawLine(0, y, w, y, p);
                    float x = s * 0.5f;
                    c.drawLine(x, 0, x, h, margin);
                    c.drawLine(x + s * 0.15f, 0, x + s * 0.15f, h, margin);
                    break;
                }
                case PAPER_GRID:
                    grid(c, w, h, s, p);
                    break;
                case PAPER_SMALL_GRID:
                    grid(c, w, h, s / 2f, p);
                    break;
                case PAPER_ENGINEER: {
                    float g = s / 2f;
                    int i = 0;
                    for (float y = offset; y < h; y += g, i++) c.drawLine(0, y, w, y, i % 4 == 0 ? strong : p);
                    i = 0;
                    for (float x = g / 2; x < w; x += g, i++) c.drawLine(x, 0, x, h, i % 4 == 0 ? strong : p);
                    break;
                }
                case PAPER_DOTS: {
                    float r = Math.max(1.5f, s / 18f);
                    for (float y = offset; y < h; y += s)
                        for (float x = s / 2; x < w; x += s) c.drawCircle(x, y, r, strong);
                    break;
                }
                case PAPER_CROSS: {
                    float a = s / 7f;
                    for (float y = offset; y < h; y += s)
                        for (float x = s / 2; x < w; x += s) {
                            c.drawLine(x - a, y, x + a, y, strong);
                            c.drawLine(x, y - a, x, y + a, strong);
                        }
                    break;
                }
                case PAPER_ISO: {
                    // triangle grid: horizontal lines plus lines at ±60°
                    float g = s;
                    for (float y = offset; y < h; y += g) c.drawLine(0, y, w, y, p);
                    float dx = (float) (g / Math.tan(Math.toRadians(60)));
                    float span = h / g * dx + w;
                    for (float x = -span; x < w + span; x += 2 * dx) {
                        c.drawLine(x, 0, x + h / g * dx, h, p);
                        c.drawLine(x, 0, x - h / g * dx, h, p);
                    }
                    break;
                }
                case PAPER_MUSIC: {
                    // staves of 5 lines (half a text line apart) with a gap of 3 lines between them
                    float g = s / 2f;
                    float y = offset;
                    while (y < h) {
                        for (int i = 0; i < 5 && y < h; i++, y += g) c.drawLine(0, y, w, y, strong);
                        y += g * 4;
                    }
                    break;
                }
                case PAPER_CORNELL: {
                    for (float y = offset; y < h; y += s) c.drawLine(0, y, w, y, p);
                    float x = w * 0.28f;
                    c.drawLine(x, 0, x, h, margin);
                    break;
                }
                default:
                    for (float y = offset; y < h; y += s) c.drawLine(0, y, w, y, p);
            }
        }

        private void grid(Canvas c, int w, int h, float g, Paint pt) {
            for (float y = offset % g; y < h; y += g) c.drawLine(0, y, w, y, pt);
            for (float x = g / 2; x < w; x += g) c.drawLine(x, 0, x, h, pt);
        }

        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
