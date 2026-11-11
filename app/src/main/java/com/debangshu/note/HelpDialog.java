package com.debangshu.note;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Short "how it works" shown on first launch and from Settings: a list of feature cards. */
public final class HelpDialog {
    private HelpDialog() {}

    private static final String[][] TOPICS = {
            {"🔒", "Private by design",
                    "Everything you write stays on this phone. No account, no server — your notes are never uploaded. "
                    + "(The app only goes online to download its own updates.)"},
            {"✍️", "Formatting & inserts",
                    "The bar above the keyboard has B I U S, size, font, colour, highlighter, alignment, lists, checklists and undo. "
                    + "＋ at the top adds photos, scans, PDF pages, drawings, sticky notes, tables and new pages."},
            {"🖼", "Pictures",
                    "Tap a picture to select it, drag the corner handles to resize or the side handles to stretch, "
                    + "and drag it to move it anywhere. Long-press for full screen, text, rotate, share and more."},
            {"🎨", "Note style",
                    "⋮ → Note style: 24 colours and gradients or any colour, 12 papers (lined, grid, dotted, music, "
                    + "Cornell…), line colour and the note's font. ⋮ also has Find & replace, Page sorter and Page settings."},
            {"🧮", "Auto math",
                    "Type 1+1= and the answer appears by itself. Or type 450+120+80 — the answer shows in grey; "
                    + "tap it or swipe it right to keep it. Works with + − × ÷ ^ % ( ) and √."},
            {"🧾", "Price lists add up",
                    "Write items with prices, one per line (apple 50 rs, banana 20, milk ₹60, mango 3 x 40). "
                    + "The total appears under the list and keeps itself correct. With ☐ ☑ it shows what's bought and what's left."},
            {"⏰", "Reminders that fit the note",
                    "“wake me up at 6 am tomorrow” → a real alarm. “call mom at 5 pm on 12 Nov” → your calendar + one notification. "
                    + "“buy milk at 8 pm” → just one notification. Tap the chip under the note to change it."},
            {"☑️", "Checklists",
                    "Type [] and a space for a checkbox, tap the box to tick it. Lists continue when you press Enter."},
            {"📤", "Sharing",
                    "Share a note as text, image or PDF — for checklists only what's left or only what's bought. "
                    + "Long-press notes on the home screen to select several."},
            {"📥", "Anything → note",
                    "Share a photo, screenshot, PDF or text file to Note (or tap the import button on the home screen). "
                    + "Its text is read on your phone and becomes a new note."},
            {"🗂", "Your data & folder",
                    "Internal storage/Documents/Note has Backups (daily), Exports and Imports. "
                    + "Settings → Your data → Delete all data wipes everything."},
            {"⬆️", "Updates",
                    "When a new version is ready, a popup appears. Tap Update, then Install, then Update. "
                    + "Your notes stay, and the downloaded file is deleted afterwards."},
    };

    public static void show(Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (16 * d), (int) (4 * d), (int) (16 * d), (int) (8 * d));
        TextView intro = new TextView(ctx);
        intro.setText("Your notes, math, lists and reminders — all in one place. Here's what Note can do:");
        intro.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        intro.setTextColor(ctx.getColor(R.color.text2));
        intro.setLineSpacing(3 * d, 1f);
        intro.setPadding((int) (8 * d), 0, (int) (8 * d), (int) (10 * d));
        box.addView(intro);
        for (String[] t : TOPICS) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding((int) (12 * d), (int) (12 * d), (int) (12 * d), (int) (12 * d));
            row.setBackground(Ui.round(ctx.getColor(R.color.surface2), 18 * d));
            TextView icon = new TextView(ctx);
            icon.setText(t[0]);
            icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            icon.setGravity(Gravity.CENTER);
            icon.setBackground(Ui.round(ctx.getColor(R.color.surface), 14 * d));
            row.addView(icon, new LinearLayout.LayoutParams((int) (44 * d), (int) (44 * d)));
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding((int) (12 * d), 0, 0, 0);
            TextView title = new TextView(ctx);
            title.setText(t[1]);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            title.setTextColor(ctx.getColor(R.color.text));
            title.setTypeface(Ui.face(ctx, Ui.BOLD));
            TextView body = new TextView(ctx);
            body.setText(t[2]);
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            body.setTextColor(ctx.getColor(R.color.text2));
            body.setLineSpacing(2 * d, 1f);
            body.setPadding(0, (int) (2 * d), 0, 0);
            col.addView(title);
            col.addView(body);
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (8 * d);
            box.addView(row, lp);
        }
        ScrollView sv = new ScrollView(ctx);
        sv.addView(box);
        new Ui.Popup(ctx)
                .sheet()
                .setTitle("Welcome to Note")
                .setView(Ui.capHeight(sv, 0.62f))
                .setPositiveButton("Got it", null)
                .show();
    }
}
