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
            {"🧠", "Smart calculator",
                    "Ends in = and Note works it out: “2499 + 18% gst =”, “1200 incl 18% gst =”, “3400 split 4 =”, "
                    + "“emi 5 lakh 9% 3 years =”, “5 ft in cm =”, “2 bigha in sq ft =”, “12 Nov + 45 days =”, “days until 25 Dec =”, “age 14 Aug 1995 =”."},
            {"🧾", "Price lists add up",
                    "Write items with prices, one per line (apple 50 rs, banana 20, milk ₹60, mango 3 x 40). "
                    + "The total appears under the list and keeps itself correct. With ☐ ☑ it shows what's bought and what's left."},
            {"📅", "Calendar",
                    "Home → ⚙ → Calendar: a month with dots for reminders (repeating ones on every day they come), notes you made "
                    + "and days all habits were done. Tap a day to see it; ＋ starts a note for that day."},
            {"📋", "Board view",
                    "⋮ → Board view: # headings become columns (To do · Doing · Done) and ☐ lines become cards. "
                    + "Long-press a card and drag it to another column, or tap it to move, tick, edit or delete."},
            {"✅", "Habits",
                    "Home → ⚙ → Habits: tick a habit each day, see your streak 🔥 and the last 7 days, and get a nudge at the time "
                    + "you pick (the notification has a Done button). Today's habits are on the Today page too."},
            {"🎴", "Flashcards & quiz",
                    "Write “question :: answer” (or Q: … then A: …) in any note. Home → ⚙ → Study, or ⋮ → Study these cards: "
                    + "flip cards (ones you knew come back after 1, 2, 4, 8, 16 days) or take a multiple-choice quiz."},
            {"💰", "Money",
                    "Home → ⚙ → Money: what you spent each month (from the prices in your notes), who owes whom, and bills. "
                    + "“gave Rahul 500”, “borrowed 200 from mom”, “Netflix ₹649/month on 5th” (a reminder every month). "
                    + "On a list, Note fills in last time's price in grey — tap it to keep it."},
            {"🤝", "Split with friends",
                    "Write “Rahul paid 1200”, “I paid 800”, “split between me, Rahul, Amit”. The 🤝 chip shows who pays whom "
                    + "and can add the settle-up to the note."},
            {"🧾", "Scan a bill",
                    "＋ → Scan a bill: the shop bill becomes a price list with its total (tax and total lines are skipped), "
                    + "and the photo is kept under it."},
            {"⏰", "Reminders that fit the note",
                    "“wake me up at 6 am tomorrow” → a real alarm. “call mom at 5 pm on 12 Nov” → your calendar + one notification. "
                    + "“buy milk at 8 pm” → just one notification. Tap the chip under the note to change it."},
            {"🗣", "Hindi & Bengali-style reminders",
                    "Write the way you talk: “kal subah 7 baje gym”, “parso shaam 6 baje call mama”, “har roz raat 10 baje dawai”, "
                    + "“2 ghante baad paani”, “somvar ko meeting”. Birthdays and anniversaries repeat every year by themselves."},
            {"⏳", "Countdowns, warranty & expiry",
                    "Reminder chips show “in 12 days”. “TV warranty till mar 2027” or “passport expires 3 Dec 2028” → "
                    + "a reminder a week before (a day before when it's close)."},
            {"📞", "Call, WhatsApp & links",
                    "Phone numbers, web links and e-mails in a note show as chips under the title: tap a number to Call, WhatsApp, "
                    + "text or save it. A reminder like “call Rahul at 6” gets a Call button on its notification."},
            {"🔗", "Linked notes",
                    "Type [[ and pick a note to link it, or write [[Any name]] to make a new one. Tap the 🔗 chip to open it; "
                    + "the other note shows ↩ back to this one."},
            {"🗂", "Outline & to-do roll-over",
                    "Start lines with #, ## or ### for headings; ⋮ → Outline folds them and jumps to any part. "
                    + "Unticked ☐ in older #todo notes move to today's “To-do” note each morning (Settings → Reminders)."},
            {"🔁", "Repeating reminders",
                    "“gym every Monday at 6 am”, “take medicine every day at 9 pm”, “pay rent on the 1st of every month”, "
                    + "“drink water every 2 hours”, “birthday every year on 12 Nov”. After each one rings, the next is set by itself."},
            {"🏷", "Labels",
                    "Write #work, #shopping… anywhere in a note (or ⋮ → Add a label). Chips on the home screen show only those notes."},
            {"📋", "Templates",
                    "Long-press New note (or use the chips in an empty note) for a shopping list, to-do, meeting notes, "
                    + "trip packing, monthly budget or daily journal."},
            {"🗑", "Recycle bin",
                    "Deleted notes wait 30 days in the recycle bin (home → ⚙ View & sort → Recycle bin), so you can bring them back."},
            {"🔒", "App lock & locked notes",
                    "Settings → Privacy & lock: a PIN and fingerprint for the whole app. Or lock single notes: ⋮ → Lock this note."},
            {"🎨", "Look & feel",
                    "Settings → Look & feel: light, dark or pure black, 8 accent colours, and the text size. "
                    + "Home → ⚙ switches between list and grid and changes the sort order."},
            {"☀️", "Today & morning brief",
                    "Tap “☀ Today” on the home screen: today's reminders, everything still unticked on your lists, pinned notes. "
                    + "Each morning one quiet notification shows the same (Settings → Reminders)."},
            {"📲", "Widgets & quick capture",
                    "Long-press your home screen → Widgets → Note: quick buttons, Today, a tickable checklist, a countdown. "
                    + "Long-press the Note icon for New note / checklist / voice / scan, or add the “New note” quick-settings tile."},
            {"🎤", "Voice notes & read aloud",
                    "Tap 🎤 on the formatting bar and speak — Note types it. ⋮ → Read aloud reads a note to you."},
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
            row.setBackground(Ui.round(Ui.col(ctx, R.color.surface2), 18 * d));
            TextView icon = new TextView(ctx);
            icon.setText(t[0]);
            icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            icon.setGravity(Gravity.CENTER);
            icon.setBackground(Ui.round(Ui.col(ctx, R.color.surface), 14 * d));
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
