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
                + "Or type 450+120+80 and tap the “= 650” bar above the keyboard.\n"
                + "Works with + − × ÷ ^ % ( ) and √\n\n"
                + "🔔  Automatic reminders\n"
                + "Write a line with a day or time, for example:\n"
                + "   call mom tomorrow at 5pm\n"
                + "   submit form on 15 Nov\n"
                + "   gym on monday 6:30 am\n"
                + "   drink water in 2 hours\n"
                + "The app sets an alarm notification and adds it to your phone calendar. "
                + "Tap the 🔔 chip under the note to turn one off.\n\n"
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
