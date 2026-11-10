package com.debangshu.note;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Drawing screen: pen, marker, highlighter, eraser, colours, sizes, undo / redo. */
public class DrawingActivity extends Activity {

    public static final String EXTRA_STROKES = "strokes";   // existing strokes file (to edit a drawing)
    public static final String RESULT_IMAGE = "image";
    public static final String RESULT_STROKES = "strokesOut";
    public static final String EXTRA_INDEX = "blockIndex";

    private DrawView draw;
    private final List<TextView> toolViews = new ArrayList<>();
    private final List<View> colorViews = new ArrayList<>();
    private float dp;

    private static final int[] COLORS = {0xFF1D1C21, 0xFFD32F2F, 0xFFEF6C00, 0xFFFBC02D, 0xFF2E7D32, 0xFF1565C0, 0xFF6A1B9A, 0xFFC2185B, 0xFF795548};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.bg));

        // top: cancel / undo / redo / clear / done
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding((int) (8 * dp), (int) (6 * dp), (int) (8 * dp), (int) (6 * dp));
        top.addView(button("Cancel", v -> confirmCancel()));
        View sp = new View(this);
        top.addView(sp, new LinearLayout.LayoutParams(0, 1, 1));
        top.addView(button("↶ Undo", v -> draw.undo()));
        top.addView(button("↷ Redo", v -> draw.redo()));
        top.addView(button("Clear", v -> new AlertDialog.Builder(this).setMessage("Clear the whole drawing?")
                .setPositiveButton("Clear", (d, w) -> draw.clear()).setNegativeButton("Cancel", null).show()));
        TextView done = button("Done", v -> save());
        done.setTextColor(getColor(R.color.accent));
        top.addView(done);
        root.addView(top);

        draw = new DrawView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        int m = (int) (10 * dp);
        lp.setMargins(m, 0, m, 0);
        root.addView(draw, lp);

        // tools
        HorizontalScrollView hs1 = new HorizontalScrollView(this);
        hs1.setHorizontalScrollBarEnabled(false);
        LinearLayout tools = new LinearLayout(this);
        tools.setPadding((int) (8 * dp), (int) (8 * dp), (int) (8 * dp), 0);
        String[] names = {"✒ Pen", "🖊 Marker", "🖍 Highlighter", "⌫ Eraser", "S", "M", "L"};
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            TextView t = button(names[i], v -> pickTool(k));
            t.setBackgroundResource(R.drawable.bg_tool);
            toolViews.add(t);
            tools.addView(t);
        }
        hs1.addView(tools);
        root.addView(hs1);

        // colours
        HorizontalScrollView hs2 = new HorizontalScrollView(this);
        hs2.setHorizontalScrollBarEnabled(false);
        LinearLayout colors = new LinearLayout(this);
        colors.setPadding((int) (12 * dp), (int) (10 * dp), (int) (12 * dp), (int) (14 * dp));
        for (int c : COLORS) {
            View dot = new View(this);
            int size = (int) (34 * dp);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(size, size);
            dl.setMargins((int) (6 * dp), 0, (int) (6 * dp), 0);
            dot.setOnClickListener(v -> pickColor(c));
            dot.setTag(c);
            colorViews.add(dot);
            colors.addView(dot, dl);
        }
        hs2.addView(colors);
        root.addView(hs2);
        setContentView(root);

        String strokes = getIntent().getStringExtra(EXTRA_STROKES);
        if (strokes != null && !strokes.isEmpty()) draw.load(Media.file(this, strokes));
        pickTool(0);
        pickTool(5);
        pickColor(COLORS[0]);
    }

    private TextView button(String text, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTextColor(getColor(R.color.text));
        int p = (int) (10 * dp);
        t.setPadding(p, p, p, p);
        t.setOnClickListener(l);
        return t;
    }

    private void pickTool(int k) {
        if (k <= 3) {
            draw.tool = k;
            for (int i = 0; i <= 3; i++) toolViews.get(i).setSelected(i == k);
        } else {
            draw.size = k - 4;
            for (int i = 4; i <= 6; i++) toolViews.get(i).setSelected(i == k);
        }
    }

    private void pickColor(int c) {
        draw.color = c;
        for (View v : colorViews) {
            int col = (Integer) v.getTag();
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(col);
            if (col == c) g.setStroke((int) (3 * dp), getColor(R.color.accent));
            v.setBackground(g);
        }
        if (draw.tool == 3) pickTool(0);
    }

    private void confirmCancel() {
        if (!draw.changed) { setResult(RESULT_CANCELED); finish(); return; }
        new AlertDialog.Builder(this).setMessage("Discard this drawing?")
                .setPositiveButton("Discard", (d, w) -> { setResult(RESULT_CANCELED); finish(); })
                .setNegativeButton("Keep drawing", null).show();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() { confirmCancel(); }

    private void save() {
        if (draw.isEmpty()) { setResult(RESULT_CANCELED); finish(); return; }
        try {
            String strokes = Media.newName("json");
            draw.saveStrokes(Media.file(this, strokes));
            String image = Media.newName("png");
            Bitmap bmp = draw.render(1400);
            Media.save(bmp, Media.file(this, image), Bitmap.CompressFormat.PNG, 100);
            bmp.recycle();
            Intent r = new Intent();
            r.putExtra(RESULT_IMAGE, image);
            r.putExtra(RESULT_STROKES, strokes);
            r.putExtra(EXTRA_INDEX, getIntent().getIntExtra(EXTRA_INDEX, -1));
            setResult(RESULT_OK, r);
            finish();
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't save the drawing: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ the canvas

    static class Stroke {
        int color, tool, size;
        final List<float[]> pts = new ArrayList<>();   // x, y as a share of the width (so it scales)
    }

    static class DrawView extends View {
        int tool = 0, size = 1, color = 0xFF1D1C21;
        boolean changed = false;
        private final List<Stroke> strokes = new ArrayList<>();
        private final List<Stroke> undone = new ArrayList<>();
        private Stroke current;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float aspect = 1.4f;    // height / width of the drawing

        DrawView(Context c) {
            super(c);
            setBackgroundColor(Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        boolean isEmpty() { return strokes.isEmpty(); }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            if (w > 0) aspect = (float) h / w;
        }

        static float width(int tool, int size, float w) {
            float base = tool == 1 ? 0.012f : tool == 2 ? 0.035f : tool == 3 ? 0.04f : 0.005f;
            float mult = size == 0 ? 0.6f : size == 2 ? 1.9f : 1f;
            return base * mult * w;
        }

        static void style(Paint p, Stroke s, float w) {
            p.setStrokeWidth(width(s.tool, s.size, w));
            if (s.tool == 3) p.setColor(Color.WHITE);
            else if (s.tool == 2) p.setColor((s.color & 0x00FFFFFF) | 0x66000000);
            else p.setColor(s.color);
        }

        static void drawStroke(Canvas c, Paint p, Stroke s, float w) {
            if (s.pts.isEmpty()) return;
            style(p, s, w);
            Path path = new Path();
            float[] first = s.pts.get(0);
            path.moveTo(first[0] * w, first[1] * w);
            if (s.pts.size() == 1) path.lineTo(first[0] * w + 0.1f, first[1] * w + 0.1f);
            for (int i = 1; i < s.pts.size(); i++) {
                float[] a = s.pts.get(i - 1), b = s.pts.get(i);
                path.quadTo(a[0] * w, a[1] * w, (a[0] + b[0]) / 2 * w, (a[1] + b[1]) / 2 * w);
            }
            c.drawPath(path, p);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth();
            for (Stroke s : strokes) drawStroke(c, paint, s, w);
            if (current != null) drawStroke(c, paint, current, w);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float w = Math.max(1, getWidth());
            float x = e.getX() / w, y = e.getY() / w;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    current = new Stroke();
                    current.color = color;
                    current.tool = tool;
                    current.size = size;
                    current.pts.add(new float[]{x, y});
                    getParent().requestDisallowInterceptTouchEvent(true);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (current != null) {
                        for (int h = 0; h < e.getHistorySize(); h++)
                            current.pts.add(new float[]{e.getHistoricalX(h) / w, e.getHistoricalY(h) / w});
                        current.pts.add(new float[]{x, y});
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (current != null) {
                        strokes.add(current);
                        undone.clear();
                        changed = true;
                        current = null;
                    }
                    break;
            }
            invalidate();
            return true;
        }

        void undo() { if (!strokes.isEmpty()) { undone.add(strokes.remove(strokes.size() - 1)); changed = true; invalidate(); } }
        void redo() { if (!undone.isEmpty()) { strokes.add(undone.remove(undone.size() - 1)); changed = true; invalidate(); } }
        void clear() { undone.addAll(strokes); strokes.clear(); changed = true; invalidate(); }

        Bitmap render(int outWidth) {
            int h = Math.max(1, Math.round(outWidth * aspect));
            Bitmap b = Bitmap.createBitmap(outWidth, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.drawColor(Color.WHITE);
            Paint p = new Paint(paint);
            for (Stroke s : strokes) drawStroke(c, p, s, outWidth);
            return b;
        }

        void saveStrokes(File f) throws Exception {
            JSONObject root = new JSONObject();
            root.put("aspect", (double) aspect);
            JSONArray arr = new JSONArray();
            for (Stroke s : strokes) {
                JSONObject o = new JSONObject();
                o.put("c", s.color);
                o.put("t", s.tool);
                o.put("s", s.size);
                JSONArray pts = new JSONArray();
                for (float[] pt : s.pts) { pts.put((double) pt[0]); pts.put((double) pt[1]); }
                o.put("p", pts);
                arr.put(o);
            }
            root.put("strokes", arr);
            try (FileOutputStream out = new FileOutputStream(f)) { out.write(root.toString().getBytes("UTF-8")); }
        }

        void load(File f) {
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[(int) f.length()];
                int off = 0, r;
                while (off < buf.length && (r = in.read(buf, off, buf.length - off)) > 0) off += r;
                JSONObject root = new JSONObject(new String(buf, 0, off, "UTF-8"));
                JSONArray arr = root.getJSONArray("strokes");
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Stroke s = new Stroke();
                    s.color = o.getInt("c");
                    s.tool = o.getInt("t");
                    s.size = o.getInt("s");
                    JSONArray pts = o.getJSONArray("p");
                    for (int k = 0; k + 1 < pts.length(); k += 2)
                        s.pts.add(new float[]{(float) pts.getDouble(k), (float) pts.getDouble(k + 1)});
                    strokes.add(s);
                }
            } catch (Exception ignored) { }
            invalidate();
        }
    }
}
