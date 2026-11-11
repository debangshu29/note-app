package com.debangshu.note;

import android.app.Activity;
import android.content.Intent;
import android.text.format.DateFormat;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Ready-made starting points for a new note. */
public final class Templates {

    private Templates() {}

    public static final String[] NAMES = {"Shopping list", "To-do list", "Meeting notes", "Trip packing", "Monthly budget", "Daily journal", "Split with friends", "Bills & subscriptions", "Lend & borrow"};
    public static final String[] EMOJI = {"🛒", "✅", "🗓", "🧳", "💰", "📔", "🤝", "📅", "💸"};

    /** Title for template {@code i}. */
    public static String title(int i) {
        Date now = new Date();
        String day = new java.text.SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(now);
        String month = new java.text.SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(now);
        switch (i) {
            case 0: return "Shopping list";
            case 1: return "To-do · " + day;
            case 2: return "Meeting · " + day;
            case 3: return "Trip packing list";
            case 4: return "Budget · " + month;
            case 5: return "Journal · " + day;
            case 6: return "Split · " + day;
            case 7: return "Bills & subscriptions";
            case 8: return "Lend & borrow";
        }
        return "";
    }

    /** Body text for template {@code i}; ☐ lines are checkboxes, price lines add up by themselves. */
    public static String body(int i) {
        switch (i) {
            case 0: return "☐ \n☐ \n☐ \n\n#shopping";
            case 1: return "Most important\n☐ \n\nAlso today\n☐ \n☐ \n☐ \n\n#todo";
            case 2: return "Who: \nWhere: \n\nAgenda\n• \n\nNotes\n• \n\nAction items\n☐ \n\n#meeting";
            case 3: return "Clothes\n☐ T-shirts\n☐ Trousers\n☐ Nightwear\n☐ Jacket\n\n"
                    + "Toiletries\n☐ Toothbrush & paste\n☐ Soap & shampoo\n☐ Medicines\n\n"
                    + "Documents\n☐ ID card\n☐ Tickets\n☐ Wallet & cards\n\n"
                    + "Gadgets\n☐ Phone charger\n☐ Power bank\n☐ Earphones\n\n#trip";
            case 4: return "Rent 0 rs\nGroceries 0 rs\nElectricity 0 rs\nPhone & internet 0 rs\nTravel 0 rs\nEating out 0 rs\nOther 0 rs\n\n#budget";
            case 5: return "Mood: \n\nToday\n\n\nGrateful for\n• \n\nTomorrow\n☐ \n\n#journal";
            case 6: return "I paid 0\nRahul paid 0\nAmit paid 0\nsplit between me, Rahul, Amit\n\n(change the names and amounts — tap the 🤝 chip to see who pays whom)";
            case 7: return "Rent ₹0 /month\nElectricity ₹0 /month\nPhone & internet ₹0 /month\nNetflix ₹0 /month\nInsurance ₹0 /year\n\n"
                    + "(write the amounts; add the day — “on 5th” — to be reminded every month)\n\n#bills";
            case 8: return "☐ gave Rahul 0\n☐ borrowed 0 from mom\n\n(tick a line when it's settled — Money on the home ⚙ menu shows everyone's balance)";
        }
        return "";
    }

    /** Sheet with the templates; picking one opens a new note made from it. */
    public static void pick(Activity a) {
        CharSequence[] items = new CharSequence[NAMES.length];
        for (int i = 0; i < NAMES.length; i++) items[i] = EMOJI[i] + "   " + NAMES[i];
        new Ui.Popup(a).setTitle("New note from a template").setItems(items, (d, w) -> {
            Intent i = new Intent(a, EditorActivity.class);
            i.putExtra(EditorActivity.EXTRA_TEMPLATE, w);
            a.startActivity(i);
        }).show();
    }
}
