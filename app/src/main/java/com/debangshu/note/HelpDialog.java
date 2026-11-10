package com.debangshu.note;

import android.app.AlertDialog;
import android.content.Context;

/** Short "how it works" shown on first launch and from Settings. */
public final class HelpDialog {
    private HelpDialog() {}

    public static void show(Context ctx) {
        String msg =
                "Everything you write stays on this phone. No account, no server — your notes are never uploaded. "
                + "(The app only goes online to download its own updates.)\n\n"
                + "🧮  Auto math\n"
                + "Type 1+1= and the answer appears by itself: 1+1=2\n"
                + "Or just type 450+120+80 — the answer shows in grey after it. "
                + "Tap the grey answer (or swipe it to the right) to keep it.\n"
                + "Works with + − × ÷ ^ % ( ) and √\n\n"
                + "🧾  Price lists add up by themselves\n"
                + "Write items with prices, one per line (apple 50 rs, banana 20, milk ₹60, mango 3 x 40). "
                + "The total appears in grey under the list — tap it to keep it. "
                + "Or type “total =” and it fills in. A Total line keeps itself correct when you change a price. "
                + "With checkboxes (☐ ☑) it also shows what's bought and what's left.\n\n"
                + "⏰ 📅 🔔  Reminders that fit the note\n"
                + "⏰  “wake me up at 6 am tomorrow” → a real alarm with Stop / Snooze. Not added to the calendar.\n"
                + "📅  “call mom at 5 pm on 12 Nov”, “exam on 5 Jan” → added to your phone calendar + one notification.\n"
                + "🔔  “drink water in 2 hours”, “buy milk at 8 pm” → just one notification.\n"
                + "Only one alert per reminder — the calendar entry stays silent. "
                + "Tap the chip under the note to change the type or turn it off.\n\n"
                + "☑  Checklists\n"
                + "Type [] and a space for a checkbox. Tap the box to tick it. "
                + "Lists continue when you press Enter.\n\n"
                + "📤  Sharing\n"
                + "Share a note as text, image or PDF. For checklists you can share only what's left to buy or only what's bought. "
                + "Long-press notes on the home screen to select several.\n\n"
                + "📥  Anything → note\n"
                + "Share a photo, screenshot, PDF or text file to Note (or tap 📄+ on the home screen). "
                + "Its text is read on your phone and becomes a new note.\n\n"
                + "🗑  Your data\n"
                + "Settings → Delete all data wipes everything from the phone. "
                + "You can also export a backup file first.\n\n"
                + "📁  Note folder\n"
                + "Internal storage/Documents/Note has Backups (automatic daily), Exports (images/PDFs you share) "
                + "and Imports (files you turned into notes). Paths are in Settings.\n\n"
                + "⬆  Updates\n"
                + "When a new version is ready, a popup appears. Tap Download, then tap Note.apk and choose Update. "
                + "Your notes stay, and Note offers to delete the downloaded file afterwards.";
        new AlertDialog.Builder(ctx)
                .setTitle("Welcome to Note")
                .setMessage(msg)
                .setPositiveButton("Got it", null)
                .show();
    }
}
