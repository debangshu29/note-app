package com.debangshu.note;

import android.content.Intent;
import android.widget.LinearLayout;
import android.widget.TextView;

/** "Your week": the Sunday summary as a page (also opens any day from Features). */
public class WeekActivity extends Screen {

    @Override
    protected void build() {
        body.removeAllViews();
        Weekly.Summary m = Weekly.compute(this);
        setTitles("📊 Your week", "The last 7 days and the next 7");

        LinearLayout money = card("💰", "Spent this week", m.spent == 0 ? "Nothing in your price lists this week"
                : m.spentBefore > 0 ? (m.spent >= m.spentBefore ? ListTotals.money(m.spent - m.spentBefore, true) + " more" : ListTotals.money(m.spentBefore - m.spent, true) + " less")
                + " than the week before" : "");
        if (m.spent > 0) {
            TextView big = text(ListTotals.money(m.spent, true), 28, Ui.EXTRABOLD, Ui.col(this, R.color.accent));
            big.setPadding(px(4), px(8), 0, px(2));
            money.addView(big);
            if (!m.topCategory.isEmpty()) money.addView(hint("Most on " + m.topCategory + ": " + ListTotals.money(m.topAmount, true)));
        }
        money.setOnClickListener(v -> startActivity(new Intent(this, MoneyActivity.class)));

        LinearLayout rem = card("✅", "Reminders", m.remindersDone + " done" + (m.remindersMissed > 0 ? " · " + m.remindersMissed + " not ticked" : "")
                + " · " + m.nextWeekReminders + " coming");
        for (String s : m.missed) rem.addView(row("☐  " + s, "Not ticked last week", null, null));
        for (String s : m.coming) rem.addView(row(s, null, null, null));
        if (m.coming.isEmpty() && m.missed.isEmpty()) rem.addView(hint("Nothing planned for the next 7 days."));

        if (!m.bills.isEmpty()) {
            LinearLayout b = card("📅", "Bills next week", m.bills.size() + (m.bills.size() == 1 ? " bill" : " bills"));
            for (String s : m.bills) b.addView(row(s, null, null, null));
        }

        LinearLayout h = card("🔥", "Habits", m.habits.isEmpty() ? "No habits yet" : "Days done in the last 7");
        for (String s : m.habits) h.addView(row(s, null, null, null));
        h.setOnClickListener(v -> startActivity(new Intent(this, HabitsActivity.class)));

        LinearLayout n = card("📝", "Notes", m.notesMade + " new this week");
        n.addView(hint("This summary also comes as one notification every Sunday evening (Settings → Reminders to change the time or turn it off)."));
    }
}
