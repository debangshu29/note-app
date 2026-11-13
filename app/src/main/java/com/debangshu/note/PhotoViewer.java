package com.debangshu.note;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** Full-screen picture inside the app: pinch or double-tap to zoom, drag to look around, ✕ or Back to close. */
public final class PhotoViewer {

    private PhotoViewer() {}

    public static void show(Activity a, String file) {
        Bitmap bmp = Media.load(a, file, Math.max(a.getResources().getDisplayMetrics().widthPixels,
                a.getResources().getDisplayMetrics().heightPixels));
        if (bmp == null) return;
        float dp = a.getResources().getDisplayMetrics().density;
        Dialog d = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout root = new FrameLayout(a);
        root.setBackgroundColor(Color.BLACK);
        ZoomView iv = new ZoomView(a, bmp, d::dismiss);
        root.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView close = new TextView(a);
        close.setText("✕");
        close.setTextColor(Color.WHITE);
        close.setTextSize(22);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription(L.t("Close"));
        close.setBackgroundColor(0x55000000);
        close.setOnClickListener(v -> d.dismiss());
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams((int) (48 * dp), (int) (48 * dp), Gravity.TOP | Gravity.END);
        cl.setMargins(0, (int) (24 * dp), (int) (12 * dp), 0);
        root.addView(close, cl);

        TextView hint = new TextView(a);
        hint.setText(L.t("Pinch or double-tap to zoom · swipe down to close"));
        hint.setTextColor(0xAAFFFFFF);
        hint.setTextSize(13);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hl.bottomMargin = (int) (28 * dp);
        root.addView(hint, hl);
        hint.postDelayed(() -> hint.animate().alpha(0).setDuration(400), 2500);

        d.setContentView(root);
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        d.setOnDismissListener(x -> bmp.recycle());
        d.show();
    }

    /** ImageView with pinch-zoom and panning. */
    static final class ZoomView extends ImageView {
        private final Matrix m = new Matrix();
        private final Bitmap bmp;
        private float base = 1f, scale = 1f;
        private final ScaleGestureDetector scaler;
        private final GestureDetector gestures;
        private final Runnable onClose;
        private float pull = 0f;   // swipe-down distance when not zoomed

        ZoomView(Activity a, Bitmap b, Runnable close) {
            super(a);
            bmp = b;
            onClose = close;
            setImageBitmap(b);
            setScaleType(ScaleType.MATRIX);
            scaler = new ScaleGestureDetector(a, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector g) {
                    float f = g.getScaleFactor();
                    float next = Math.max(1f, Math.min(8f, scale * f));
                    f = next / scale;
                    scale = next;
                    m.postScale(f, f, g.getFocusX(), g.getFocusY());
                    fix();
                    return true;
                }
            });
            gestures = new GestureDetector(a, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                    if (scale <= 1.01f && !scaler.isInProgress()) {
                        pull -= dy;
                        setTranslationY(Math.max(0, pull));
                        setAlpha(1f - Math.min(0.6f, Math.max(0, pull) / (getHeight() + 1f)));
                        return true;
                    }
                    m.postTranslate(-dx, -dy);
                    fix();
                    return true;
                }
                @Override public boolean onDoubleTap(MotionEvent e) {
                    float target = scale > 1.5f ? 1f : 2.5f;
                    float f = target / scale;
                    scale = target;
                    m.postScale(f, f, e.getX(), e.getY());
                    fix();
                    return true;
                }
            });
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            base = Math.min((float) w / bmp.getWidth(), (float) h / bmp.getHeight());
            scale = 1f;
            m.reset();
            m.postScale(base, base);
            m.postTranslate((w - bmp.getWidth() * base) / 2f, (h - bmp.getHeight() * base) / 2f);
            setImageMatrix(m);
        }

        /** Keeps the picture centred when smaller than the screen and stops it sliding off the edges. */
        private void fix() {
            RectF r = new RectF(0, 0, bmp.getWidth(), bmp.getHeight());
            m.mapRect(r);
            float w = getWidth(), h = getHeight(), dx = 0, dy = 0;
            if (r.width() <= w) dx = (w - r.width()) / 2 - r.left;
            else if (r.left > 0) dx = -r.left;
            else if (r.right < w) dx = w - r.right;
            if (r.height() <= h) dy = (h - r.height()) / 2 - r.top;
            else if (r.top > 0) dy = -r.top;
            else if (r.bottom < h) dy = h - r.bottom;
            m.postTranslate(dx, dy);
            setImageMatrix(m);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            scaler.onTouchEvent(e);
            gestures.onTouchEvent(e);
            int act = e.getActionMasked();
            if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                if (pull > getHeight() / 6f) onClose.run();
                else { animate().translationY(0).alpha(1f).setDuration(150); }
                pull = 0f;
            }
            return true;
        }
    }
}
