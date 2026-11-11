package com.debangshu.note;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * A picture inside a note (photo, gallery picture, scanned page, PDF page or drawing).
 *
 *  - Tap it to select: a frame with 8 handles appears.
 *  - Corner handles resize and keep the shape; side handles stretch freely (like Word).
 *  - Drag the selected picture to move it: left / right anywhere on the line, up / down between
 *    other parts of the note (the page scrolls when you reach the top or bottom edge).
 *  - Long-press for the picture's menu.
 */
public class PictureView extends FrameLayout {

    public interface Listener {
        void onSelect(PictureView v);
        void onLongPress(PictureView v);
        void onChanged(PictureView v);
        /** Scrolls the page by dy pixels; returns how far it really scrolled. */
        int scrollPage(int dy);
        /** Picture was dragged up/down; {@code centerY} is its middle in the parent's coordinates. */
        void onDrop(PictureView v, float centerY);
        /** Where the finger is on screen during a drag (raw Y), to scroll near the edges. */
        int edgeSpeed(float rawY);
    }

    public final ImageView image;
    private Listener listener;
    private final float dp;
    private final int slop;
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobEdge = new Paint(Paint.ANTI_ALIAS_FLAG);

    // saved shape
    private float widthFrac = 1f;   // share of the line width
    private float aspect = 0f;      // height / width; 0 = the picture's own shape
    private float xFrac = 0.5f;     // 0 = left, 0.5 = centre, 1 = right
    private float natural = 0.75f;  // the picture's own height / width

    private boolean selected = false;

    // touch
    private static final int NONE = 0, MOVE = 1, RESIZE = 2, TAP = 3;
    private int mode = NONE;
    private int handle = -1;        // 0 TL, 1 T, 2 TR, 3 R, 4 BR, 5 B, 6 BL, 7 L
    private float downX, downY, downRawY, lastRawY;
    private float iw0, ih0, left0;
    private int scrolled = 0;
    private boolean moved = false, longDone = false, dirty = false;

    private final Runnable longPress = () -> {
        if (mode == TAP || (mode == MOVE && !moved)) {
            longDone = true;
            mode = NONE;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            setTranslationY(0);
            listener.onLongPress(this);
        }
    };

    private final Runnable autoScroll = new Runnable() {
        @Override public void run() {
            if (mode != MOVE) return;
            int speed = listener.edgeSpeed(lastRawY);
            if (speed != 0) {
                int got = listener.scrollPage(speed);
                scrolled += got;
                setTranslationY(lastRawY - downRawY + scrolled);
            }
            postDelayed(this, 16);
        }
    };

    public PictureView(Context ctx, Listener l) {
        super(ctx);
        listener = l;
        dp = ctx.getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        int pad = (int) (12 * dp);
        setPadding(0, pad, 0, pad);
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
        image = new ImageView(ctx);
        image.setScaleType(ImageView.ScaleType.FIT_XY);
        image.setBackgroundResource(R.drawable.bg_image);
        image.setContentDescription("Picture. Tap to select, drag to move, long-press for options");
        addView(image, new LayoutParams((int) (100 * dp), (int) (75 * dp)));
        int accent = ctx.getColor(R.color.accent);
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(2 * dp);
        frame.setColor(accent);
        frame.setPathEffect(new DashPathEffect(new float[]{6 * dp, 4 * dp}, 0));
        knob.setColor(0xFFFFFFFF);
        knobEdge.setStyle(Paint.Style.STROKE);
        knobEdge.setStrokeWidth(2 * dp);
        knobEdge.setColor(accent);
    }

    // ------------------------------------------------------------------ shape

    public void setShape(float widthFrac, float aspect, float xFrac) {
        this.widthFrac = clamp(widthFrac, 0.1f, 1f);
        this.aspect = aspect > 0 ? clamp(aspect, 0.05f, 20f) : 0f;
        this.xFrac = clamp(xFrac, 0f, 1f);
        requestLayout();
    }

    public float widthFrac() { return widthFrac; }
    public float aspect() { return aspect; }
    public float xFrac() { return xFrac; }

    public void setBitmap(Bitmap b) {
        if (b != null) {
            image.setImageBitmap(b);
            if (b.getWidth() > 0) natural = (float) b.getHeight() / b.getWidth();
        } else {
            image.setImageResource(R.drawable.ic_import);
            natural = 0.75f;
        }
        requestLayout();
    }

    public void setSelectedPicture(boolean s) {
        if (selected == s) return;
        selected = s;
        invalidate();
    }

    public boolean isSelectedPicture() { return selected; }

    private float shape() { return aspect > 0 ? aspect : natural; }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int W = MeasureSpec.getSize(wSpec);
        if (W > 0) {
            int iw = Math.max((int) (40 * dp), Math.round(W * widthFrac));
            iw = Math.min(iw, W);
            int ih = Math.max((int) (24 * dp), Math.round(iw * shape()));
            LayoutParams lp = (LayoutParams) image.getLayoutParams();
            lp.width = iw;
            lp.height = ih;
            lp.leftMargin = Math.round((W - iw) * xFrac);
        }
        super.onMeasure(wSpec, hSpec);
    }

    private static float clamp(float v, float a, float b) { return Math.max(a, Math.min(b, v)); }

    // ------------------------------------------------------------------ drawing the frame

    private RectF box() {
        return new RectF(image.getLeft(), image.getTop(), image.getRight(), image.getBottom());
    }

    private float[][] handles() {
        RectF r = box();
        float cx = r.centerX(), cy = r.centerY();
        return new float[][]{{r.left, r.top}, {cx, r.top}, {r.right, r.top}, {r.right, cy},
                {r.right, r.bottom}, {cx, r.bottom}, {r.left, r.bottom}, {r.left, cy}};
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        if (!selected) return;
        RectF r = box();
        c.drawRect(r, frame);
        float rad = 7 * dp;
        float[][] hs = handles();
        for (int i = 0; i < hs.length; i++) {
            // keep knobs inside the screen when the picture touches the edge
            float x = clamp(hs[i][0], rad + dp, getWidth() - rad - dp);
            float y = hs[i][1];
            if (i % 2 == 0) {                 // corners: round
                c.drawCircle(x, y, rad, knob);
                c.drawCircle(x, y, rad, knobEdge);
            } else {                          // sides: small squares
                RectF s = new RectF(x - rad * 0.8f, y - rad * 0.8f, x + rad * 0.8f, y + rad * 0.8f);
                c.drawRect(s, knob);
                c.drawRect(s, knobEdge);
            }
        }
    }

    private int hitHandle(float x, float y) {
        float reach = 26 * dp;
        float[][] hs = handles();
        int best = -1;
        float bestD = reach * reach;
        for (int i = 0; i < hs.length; i++) {
            float hx = clamp(hs[i][0], 8 * dp, getWidth() - 8 * dp);
            float dx = x - hx, dy = y - hs[i][1];
            float d = dx * dx + dy * dy;
            // corners win over sides when both are close
            if (d < bestD || (d < reach * reach && i % 2 == 0 && best % 2 == 1)) { bestD = d; best = i; }
        }
        return best;
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) { return true; }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = ev.getX();
                downY = ev.getY();
                downRawY = lastRawY = ev.getRawY();
                moved = longDone = dirty = false;
                scrolled = 0;
                RectF r = box();
                iw0 = r.width();
                ih0 = r.height();
                left0 = r.left;
                handle = selected ? hitHandle(downX, downY) : -1;
                RectF grab = new RectF(r);
                grab.inset(-6 * dp, -6 * dp);
                if (handle >= 0) {
                    mode = RESIZE;
                    hold(true);
                } else if (grab.contains(downX, downY)) {
                    mode = selected ? MOVE : TAP;
                    if (selected) hold(true);
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                } else {
                    mode = NONE;
                    return false;
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getX() - downX, dy = ev.getY() - downY;
                lastRawY = ev.getRawY();
                if (!moved && (Math.abs(dx) > slop || Math.abs(ev.getRawY() - downRawY) > slop)) {
                    moved = true;
                    removeCallbacks(longPress);
                    if (mode == MOVE) {
                        setElevation(8 * dp);
                        setAlpha(0.85f);
                        post(autoScroll);
                    }
                }
                if (!moved) return true;
                if (mode == RESIZE) resize(dx, dy);
                else if (mode == MOVE) {
                    int W = getWidth();
                    float iw = image.getWidth();
                    if (W - iw > 1) {
                        float left = clamp(left0 + dx, 0, W - iw);
                        float nx = left / (W - iw);
                        if (nx != xFrac) { xFrac = nx; dirty = true; requestLayout(); }
                    }
                    setTranslationY(lastRawY - downRawY + scrolled);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                removeCallbacks(longPress);
                removeCallbacks(autoScroll);
                boolean cancel = ev.getActionMasked() == MotionEvent.ACTION_CANCEL;
                int was = mode;
                mode = NONE;
                hold(false);
                if (longDone) return true;
                if (was == TAP && !moved && !cancel) {
                    listener.onSelect(this);
                    return true;
                }
                if (was == MOVE) {
                    float ty = getTranslationY();
                    setElevation(0);
                    setAlpha(1f);
                    if (Math.abs(ty) > image.getHeight() / 3f + slop) {
                        float center = getTop() + ty + getHeight() / 2f;
                        setTranslationY(0);
                        listener.onDrop(this, center);
                        dirty = false;
                        return true;
                    }
                    setTranslationY(0);
                }
                if (dirty) listener.onChanged(this);
                dirty = false;
                return true;
            }
        }
        return true;
    }

    private void hold(boolean on) {
        ViewParent p = getParent();
        if (p != null) p.requestDisallowInterceptTouchEvent(on);
    }

    private void resize(float dx, float dy) {
        int W = getWidth();
        float minW = 40 * dp, minH = 24 * dp;
        float iw = iw0, ih = ih0, left = left0;
        boolean leftSide = handle == 0 || handle == 6 || handle == 7;
        boolean rightSide = handle == 2 || handle == 3 || handle == 4;
        boolean top = handle == 0 || handle == 1 || handle == 2;
        boolean corner = handle % 2 == 0;
        if (corner) {
            float ratio = ih0 / Math.max(1f, iw0);
            float byX = leftSide ? iw0 - dx : iw0 + dx;
            float byY = (top ? ih0 - dy : ih0 + dy) / ratio;
            iw = Math.abs(dx) >= Math.abs(dy) ? byX : byY;
            float maxW = leftSide ? left0 + iw0 : W - left0;
            iw = clamp(iw, minW, Math.max(minW, maxW));
            ih = iw * ratio;
            if (leftSide) left = left0 + iw0 - iw;
        } else if (leftSide || rightSide) {
            iw = leftSide ? iw0 - dx : iw0 + dx;
            float maxW = leftSide ? left0 + iw0 : W - left0;
            iw = clamp(iw, minW, Math.max(minW, maxW));
            if (leftSide) left = left0 + iw0 - iw;
        } else {
            ih = clamp(top ? ih0 - dy : ih0 + dy, minH, W * 4f);
        }
        widthFrac = clamp(iw / W, 0.1f, 1f);
        float shapeNow = ih / iw;
        aspect = Math.abs(shapeNow - natural) < 0.01f ? 0f : shapeNow;
        xFrac = W - iw > 1 ? clamp(left / (W - iw), 0, 1) : 0.5f;
        dirty = true;
        requestLayout();
    }
}
