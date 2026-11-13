package com.debangshu.note;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Shared look for the simple full screens (Calendar, Habits, Study…): a back button, a title, cards. */
public abstract class Screen extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(Ui.wrap(base)); }

    protected Store store;
    protected float dp;
    protected LinearLayout root, body;
    protected TextView titleTv, subTv;
    protected LinearLayout topBar;
    protected ScrollView scroller;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.install(this);
        store = Store.get(this);
        dp = getResources().getDisplayMetrics().density;

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.col(this, R.color.bg));

        topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(px(12), px(10), px(12), px(6));
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_back);
        back.setBackgroundResource(R.drawable.bg_circle);
        back.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.text)));
        back.setPadding(px(11), px(11), px(11), px(11));
        back.setElevation(dp);
        back.setContentDescription(L.t("Back"));
        back.setOnClickListener(v -> onBackPressed());
        topBar.addView(back, new LinearLayout.LayoutParams(px(44), px(44)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(px(14), 0, px(8), 0);
        titleTv = text("", 24, Ui.EXTRABOLD, getColor(R.color.text));
        titleTv.setMaxLines(1);
        titleTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        subTv = text("", 13, Ui.SEMIBOLD, getColor(R.color.text2));
        subTv.setMaxLines(1);
        subTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(titleTv);
        titles.addView(subTv);
        topBar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(topBar);

        if (scrolls()) {
            scroller = new ScrollView(this);
            scroller.setClipToPadding(false);
            body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(px(16), px(6), px(16), px(90));
            scroller.addView(body);
            root.addView(scroller, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        } else {
            body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        setContentView(root);
    }

    /** False for screens that lay out their own scrolling (the board). */
    protected boolean scrolls() { return true; }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.refreshTheme(this)) return;
        if (Lock.guard(this)) return;
        build();
        Ui.fonts(root);
    }

    protected abstract void build();

    protected void setTitles(String title, String sub) {
        titleTv.setText(title);
        subTv.setText(sub);
        subTv.setVisibility(sub == null || sub.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** A round button on the right of the top bar. */
    protected TextView topButton(String label, String description, boolean accent, View.OnClickListener l) {
        TextView t = text(label, accent ? 22 : 17, Ui.BOLD, accent ? getColor(R.color.on_accent) : Ui.col(this, R.color.accent));
        t.setGravity(Gravity.CENTER);
        t.setBackgroundResource(accent ? R.drawable.bg_circle_accent : R.drawable.bg_circle);
        t.setElevation((accent ? 3 : 1) * dp);
        t.setContentDescription(L.t(description));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(44), px(44));
        lp.leftMargin = px(8);
        topBar.addView(t, lp);
        return t;
    }

    protected LinearLayout card(String emoji, String title, String sub) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.bg_card_plain);
        c.setElevation(dp);
        c.setPadding(px(16), px(14), px(16), px(14));
        if (title != null) {
            LinearLayout head = new LinearLayout(this);
            head.setGravity(Gravity.CENTER_VERTICAL);
            if (emoji != null) {
                TextView icon = text(emoji, 18, Ui.REGULAR, getColor(R.color.text));
                icon.setGravity(Gravity.CENTER);
                icon.setBackgroundResource(R.drawable.bg_icon_soft);
                head.addView(icon, new LinearLayout.LayoutParams(px(38), px(38)));
            }
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(emoji != null ? px(12) : 0, 0, 0, 0);
            col.addView(text(title, 16, Ui.EXTRABOLD, getColor(R.color.text)));
            if (sub != null && !sub.isEmpty()) col.addView(text(sub, 12, Ui.SEMIBOLD, getColor(R.color.text2)));
            head.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            c.addView(head);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(12);
        body.addView(c, lp);
        return c;
    }

    /** A tappable row: title, a smaller line under it, and something on the right. */
    protected LinearLayout row(String title, String sub, String right, View.OnClickListener tap) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(8), px(9), px(8), px(9));
        row.setBackground(Ui.ripple(Ui.round(0, 12 * dp)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(title, 15, Ui.SEMIBOLD, getColor(R.color.text));
        t.setMaxLines(3);
        col.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = text(sub, 12, Ui.REGULAR, getColor(R.color.text2));
            s.setMaxLines(2);
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(s);
        }
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (right != null && !right.isEmpty()) {
            TextView r = text(right, 14, Ui.BOLD, Ui.col(this, R.color.accent));
            r.setPadding(px(10), 0, 0, 0);
            row.addView(r);
        }
        if (tap != null) row.setOnClickListener(tap);
        return row;
    }

    protected TextView hint(String s) {
        TextView t = text(s, 13, Ui.REGULAR, getColor(R.color.text2));
        t.setPadding(px(4), px(8), px(4), px(4));
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    protected TextView pill(String s, boolean on) {
        TextView t = text(s, 14, Ui.BOLD, on ? getColor(R.color.on_accent) : getColor(R.color.text));
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(16), px(10), px(16), px(10));
        t.setBackground(Ui.ripple(Ui.round(on ? Ui.col(this, R.color.accent) : Ui.col(this, R.color.surface2), 22 * dp)));
        return t;
    }

    protected TextView text(String s, int sp, int weight, int color) {
        TextView t = new Ui.T(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Ui.face(this, weight));
        t.setTextColor(color);
        t.setTag(Ui.KEEP_FONT);
        return t;
    }

    protected void openNote(long id) {
        Note n = store.note(id);
        if (n != null && n.locked && !Lock.isOpen(id)) {
            Lock.verify(this, "Locked note", ok -> { if (ok) { Lock.markOpen(id); openNote(id); } });
            return;
        }
        Intent i = new Intent(this, EditorActivity.class);
        i.putExtra(EditorActivity.EXTRA_NOTE_ID, id);
        startActivity(i);
    }

    protected int px(float v) { return Math.round(v * dp); }
}
