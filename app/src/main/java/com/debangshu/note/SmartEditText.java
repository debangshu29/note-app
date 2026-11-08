package com.debangshu.note;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.EditText;

/** EditText that tells us when the cursor moves (used for the "= answer" suggestion). */
public class SmartEditText extends EditText {

    public interface OnCursorMoved { void onCursorMoved(); }

    private OnCursorMoved listener;

    public SmartEditText(Context c) { super(c); }
    public SmartEditText(Context c, AttributeSet a) { super(c, a); }
    public SmartEditText(Context c, AttributeSet a, int s) { super(c, a, s); }

    public void setOnCursorMoved(OnCursorMoved l) { listener = l; }

    @Override
    protected void onSelectionChanged(int selStart, int selEnd) {
        super.onSelectionChanged(selStart, selEnd);
        if (listener != null) listener.onCursorMoved();
    }
}
