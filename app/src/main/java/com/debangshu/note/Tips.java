package com.debangshu.note;

import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.Toast;

/** Long-press any icon to see what it does (Android's own tooltip; a short message on older phones). */
public final class Tips {

    private Tips() {}

    public static void set(View v, CharSequence raw) {
        if (raw == null || raw.length() == 0) return;
        final CharSequence text = L.cs(raw);
        if (Build.VERSION.SDK_INT >= 26) {
            v.setTooltipText(text);
        } else {
            v.setOnLongClickListener(x -> {
                Ui.toast(x.getContext(), text, Toast.LENGTH_SHORT).show();
                return true;
            });
        }
    }

    /** Gives every icon button under {@code root} a tooltip from its description. */
    public static void applyAll(View root) {
        if (root instanceof ImageButton || (root.isClickable() && !(root instanceof ViewGroup)
                && root.getContentDescription() != null && !(root instanceof android.widget.EditText))) {
            set(root, root.getContentDescription());
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) applyAll(g.getChildAt(i));
        }
    }
}
