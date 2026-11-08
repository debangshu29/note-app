package com.debangshu.note;

import android.app.AlertDialog;
import android.content.Context;

/** Short "how it works" shown on first launch and from Settings. */
public final class HelpDialog {
    private HelpDialog() {}

    public static void show(Context ctx) {
        String msg =
                "Everything you write stays on this phone. No account, no internet, no server.\n\n"
                + "🧮  Auto math\n"
                + "Type 1+1= and the answer appears by itself: 1+1=2\n"
                + "Or just type 450+120+80 — the answer shows in grey after it. "
                + "Tap the grey answer (or swipe it to the right) to keep it.\n"
                + "Works with + − × ÷ ^ % ( ) and √\n\n"
                + "⏰ 📅 🔔  Reminders that fit the note\n"
                + "⏰  “wake me up at 6 am tomorrow” → a real alarm with Stop / Snooze. Not added to the calendar.\n"
                + "📅  “call mom at 5 pm on 12 Nov”, “exam on 5 Jan” → added to your phone calendar + one notification.\n"
                + "🔔  “drink water in 2 hours”, “buy milk at 8 pm” → just one notification.\n"
                + "Only one alert per reminder — the calendar entry stays silent. "
                + "Tap the chip under the note to change the type or turn it off.\n\n"
                + "☑  Checklists\n"
                + "Type [] and a space for a checkbox. Tap the box to tick it. "
                + "Lists continue when you press Enter.\n\n"
                + "🗑  Your data\n"
                + "Settings → Delete all data wipes everything from the phone. "
                + "You can also export a backup file first.";
        new AlertDialog.Builder(ctx)
                .setTitle("Welcome to Note")
                .setMessage(msg)
                .setPositiveButton("Got it", null)
                .show();
    }
}
