package com.debangshu.note;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.EditText;

/**
 * EditText with Gmail-style "ghost" suggestions.
 *
 * The suggestion (for example " = 2") is drawn right after the cursor in a faded
 * shade of the current text colour (black text -> light grey, blue text -> light blue).
 * It is NOT part of the text until the user taps it or drags it to the right.
 */
public class SmartEditText extends EditText {

    public interface OnCursorMoved { void onCursorMoved(); }
    public interface OnGhostAccepted { void onGhostAccepted(String ghost); }

    private OnCursorMoved cursorListener;
    private OnGhostAccepted acceptListener;

    private String ghost;
    private final Paint ghostPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ghostRect = new RectF();
    private boolean tracking;
    private float downX;

    public SmartEditText(Context c) { super(c); }
    public SmartEditText(Context c, AttributeSet a) { super(c, a); }
    public SmartEditText(Context c, AttributeSet a, int s) { super(c, a, s); }

    public void setOnCursorMoved(OnCursorMoved l) { cursorListener = l; }
    public void setOnGhostAccepted(OnGhostAccepted l) { acceptListener = l; }

    /** Shows (or hides with null) the faded suggestion after the cursor. */
    public void setGhost(String g) {
        ghost = (g == null || g.isEmpty()) ? null : g;
        if (ghost == null) ghostRect.setEmpty();
        invalidate();
    }

    public String getGhost() { return ghost; }

    @Override
    protected void onSelectionChanged(int selStart, int selEnd) {
        super.onSelectionChanged(selStart, selEnd);
        if (cursorListener != null) cursorListener.onCursorMoved();
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, android.graphics.Rect prev) {
        super.onFocusChanged(focused, direction, prev);
        if (!focused) setGhost(null);
        else if (cursorListener != null) cursorListener.onCursorMoved();
    }

    /** Faded version of whatever colour the text currently is. */
    private int ghostColor() {
        int c = getCurrentTextColor();
        int alpha = (int) (Color.alpha(c) * 0.38f);
        return Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c));
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        ghostRect.setEmpty();
        if (ghost == null || !hasFocus()) return;
        Layout layout = getLayout();
        int cur = getSelectionEnd();
        if (layout == null || cur < 0 || cur != getSelectionStart()) return;

        ghostPaint.set(getPaint());
        ghostPaint.setColor(ghostColor());

        int line = layout.getLineForOffset(cur);
        float left = getTotalPaddingLeft();
        float top = getTotalPaddingTop();
        float x = layout.getPrimaryHorizontal(cur) + left;
        float baseline = layout.getLineBaseline(line) + top;
        float lineTop = layout.getLineTop(line) + top;
        float lineBottom = layout.getLineBottom(line) + top;

        float width = ghostPaint.measureText(ghost);
        float maxRight = getWidth() - getTotalPaddingRight();
        if (x + width > maxRight && cur == length()) {
            // no room on this line: show it at the start of the next line
            float h = lineBottom - lineTop;
            x = left;
            baseline += h;
            lineTop += h;
            lineBottom += h;
        }
        canvas.drawText(ghost, x, baseline, ghostPaint);
        ghostRect.set(x - dp(12), lineTop - dp(8), x + width + dp(24), lineBottom + dp(8));
    }

    /** Tap the grey text, or drag it to the right, to make it real text. */
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (ghost != null && !ghostRect.isEmpty()) {
            float x = e.getX() + getScrollX();
            float y = e.getY() + getScrollY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (ghostRect.contains(x, y)) {
                        tracking = true;
                        downX = x;
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (tracking) {
                        if (x - downX > dp(40)) { tracking = false; accept(); }
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (tracking) {
                        tracking = false;
                        if (ghostRect.contains(x, y) || x - downX > dp(40)) accept();
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    tracking = false;
                    break;
            }
        } else {
            tracking = false;
        }
        return super.onTouchEvent(e);
    }

    private void accept() {
        String g = ghost;
        setGhost(null);
        if (g != null && acceptListener != null) acceptListener.onGhostAccepted(g);
    }
}
