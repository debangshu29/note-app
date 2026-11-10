package com.debangshu.note;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** Note colours (sticky-note look) and paper backgrounds: plain, lined, grid, dotted. */
public final class NoteStyle {

    private NoteStyle() {}

    public static final String[] COLOR_NAMES = {"Default", "Yellow", "Green", "Blue", "Pink", "Orange", "Purple", "Grey"};
    private static final int[] LIGHT = {0, 0xFFFFF4B8, 0xFFDDF2D5, 0xFFD9ECFA, 0xFFFBDDE7, 0xFFFFE3C7, 0xFFE8DFF7, 0xFFE9E8E6};
    private static final int[] DARK = {0, 0xFF4A4320, 0xFF26402A, 0xFF1F3647, 0xFF472A35, 0xFF4A3420, 0xFF362D4A, 0xFF333336};

    /** Sticky-note blocks inside notes use the same colours (never "default"). */
    public static final String[] STICKY_NAMES = {"Yellow", "Green", "Blue", "Pink", "Orange", "Purple"};

    public static final int PAPER_PLAIN = 0, PAPER_LINED = 1, PAPER_GRID = 2, PAPER_DOTS = 3;
    public static final String[] PAPER_NAMES = {"Plain", "Lined", "Grid", "Dotted"};

    public static boolean dark(Context ctx) {
        return (ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Background colour for a note, or 0 for the normal background. */
    public static int noteColor(Context ctx, int index) {
        if (index <= 0 || index >= LIGHT.length) return 0;
        return dark(ctx) ? DARK[index] : LIGHT[index];
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

    /** Paper pattern drawn behind the note; {@code step} is the text line height in pixels. */
    public static final class Paper extends Drawable {
        private final int kind;
        private final float step;
        private final float offset;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        public Paper(int kind, float step, float offset, int lineColor) {
            this.kind = kind;
            this.step = Math.max(8, step);
            this.offset = offset;
            p.setColor(lineColor);
            p.setStrokeWidth(Math.max(1f, step / 40f));
        }

        @Override
        public void draw(Canvas c) {
            if (kind == PAPER_PLAIN) return;
            int w = getBounds().width(), h = getBounds().height();
            if (kind == PAPER_LINED) {
                for (float y = offset; y < h; y += step) c.drawLine(0, y, w, y, p);
            } else if (kind == PAPER_GRID) {
                for (float y = offset; y < h; y += step) c.drawLine(0, y, w, y, p);
                for (float x = step / 2; x < w; x += step) c.drawLine(x, 0, x, h, p);
            } else if (kind == PAPER_DOTS) {
                float r = Math.max(1.5f, step / 18f);
                for (float y = offset; y < h; y += step)
                    for (float x = step / 2; x < w; x += step) c.drawCircle(x, y, r, p);
            }
        }

        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
