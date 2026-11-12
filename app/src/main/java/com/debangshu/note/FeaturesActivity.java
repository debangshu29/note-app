package com.debangshu.note;

import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Features: everything Note can do, by category (Smart text, Money, Views & study, Media & privacy),
 * each with a short description. Tapping one opens it, or starts a note that shows how it works.
 */
public class FeaturesActivity extends Screen {

    private static final class Feature {
        final String emoji, name, desc, action;
        final Runnable run;
        Feature(String emoji, String name, String desc, String action, Runnable run) {
            this.emoji = emoji; this.name = name; this.desc = desc; this.action = action; this.run = run;
        }
    }

    private static final class Category {
        final String emoji, title, sub;
        final List<Feature> features = new ArrayList<>();
        Category(String emoji, String title, String sub) { this.emoji = emoji; this.title = title; this.sub = sub; }
        Category add(Feature f) { features.add(f); return this; }
    }

    private final List<Category> cats = new ArrayList<>();
    private final List<View> cards = new ArrayList<>();
    private int shown = -1;   // −1 = all categories

    @Override
    protected void onCreate(android.os.Bundle b) {
        super.onCreate(b);
    }

    // ------------------------------------------------------------------ what's in each category

    private void define() {
        cats.add(new Category("🧠", "Smart text", "Note understands what you write")
                .add(new Feature("🧮", "Smart calculator",
                        "End a line with = for GST, discount, tip, split, EMI, interest, units (ft, kg, bigha…) and dates.",
                        "Try it", () -> example("Smart calculator", "2499 + 18% gst =\n1200 incl 18% gst =\n3400 split 4 =\n"
                        + "emi 5 lakh 9% 3 years =\n5 ft in cm =\n2 bigha in sq ft =\ndays until 25 Dec =\n12 Nov + 45 days =\n")))
                .add(new Feature("🗣", "Reminders the way you talk",
                        "Write in English, Hindi or Bengali style — “kal subah 7 baje gym”, “har roz raat 10 baje dawai” — and the reminder is set.",
                        "Try it", () -> example("Reminders", "kal subah 7 baje gym\nparso shaam 6 baje call mama\nhar roz raat 10 baje dawai\n")))
                .add(new Feature("⏳", "Countdowns, birthdays & expiry",
                        "Reminders show “in 12 days”. Birthdays repeat every year. “Warranty till mar 2027” reminds you a week before.",
                        "Try it", () -> example("Dates to remember", "Mom's birthday 14 Aug\nTV warranty till mar 2027\nPassport expires 3 Dec 2030\n")))
                .add(new Feature("📞", "Call & WhatsApp buttons",
                        "Phone numbers, links and e-mails in a note become chips — tap to Call, WhatsApp, SMS or save the contact.",
                        "Try it", () -> example("Contacts", "Plumber Raju 9876543210\ncall Rahul at 6 pm\n")))
                .add(new Feature("🔗", "Linked notes",
                        "Type [[ to link another note; tap the link to jump there. The other note shows a link back.",
                        "Try it", () -> example("Linked notes", "See also [[")))
                .add(new Feature("🗂", "Outline",
                        "Start lines with #, ## or ### for headings; ⋮ → Outline folds them and jumps to any part.",
                        "Try it", () -> example("Outline", "# Trip plan\n## Day 1\nBeach, dinner\n## Day 2\nFort, market\n")))
                .add(new Feature("☑", "To-do roll-over",
                        "Each morning, unticked ☐ in older #todo notes move to today's To-do note, so nothing is forgotten.",
                        RollOver.on(this) ? "On" : "Off", this::rollOverSwitch)));

        cats.add(new Category("💰", "Money", "Spending, splitting and bills — from your notes")
                .add(new Feature("🧾", "Money page",
                        "What you spent each month (from the prices in your notes), who owes whom, bills and the price book.",
                        "Open", () -> open(MoneyActivity.class)))
                .add(new Feature("🏷", "Price memory",
                        "On a shopping list, type “milk” and last time's price appears in grey — tap to keep it.",
                        "Open", () -> open(MoneyActivity.class)))
                .add(new Feature("🤝", "Split with friends",
                        "“Rahul paid 1200”, “I paid 800”, “split between me, Rahul, Amit” — a chip shows who pays whom.",
                        "Start", () -> template(6)))
                .add(new Feature("💸", "Lend & borrow",
                        "“gave Rahul 500”, “borrowed 200 from mom” — see everyone's balance; tick the line when it's settled.",
                        "Start", () -> template(8)))
                .add(new Feature("📅", "Bills & subscriptions",
                        "“Netflix ₹649/month on 5th”, “rent 15000 pm due on 1st” — a reminder every month and a monthly total.",
                        "Start", () -> template(7)))
                .add(new Feature("📷", "Scan a bill",
                        "Photograph a shop bill and it becomes a price list with its total (tax and total lines skipped).",
                        "Scan", () -> newNote("Bill", "bill"))));

        cats.add(new Category("📊", "Views & study", "See your notes in new ways")
                .add(new Feature("📅", "Calendar",
                        "Your month with reminders (repeating ones too), notes you made and habits. Tap a day; ＋ plans something.",
                        "Open", () -> open(CalendarActivity.class)))
                .add(new Feature("📋", "Board view",
                        "# To do, # Doing, # Done become columns; drag cards between them. In a note: ⋮ → Board view.",
                        "Start", () -> template(9)))
                .add(new Feature("✅", "Habit tracker",
                        "Tick a habit each day, keep the streak 🔥 going and get a nudge at the time you choose.",
                        "Open", () -> open(HabitsActivity.class)))
                .add(new Feature("🎴", "Flashcards & quiz",
                        "Write “question :: answer” in a note; flip cards (spaced repetition) or take a multiple-choice quiz.",
                        "Open", () -> open(StudyActivity.class))));

        cats.add(new Category("🔐", "Media & privacy", "Recordings, files, documents — kept on your phone")
                .add(new Feature("🎙", "Voice recordings",
                        "Record audio right inside a note and play it back there.",
                        "Record", () -> newNote("Voice note", "record")))
                .add(new Feature("📎", "Attach any file",
                        "Keep a PDF, Excel, Word or any other file in a note; tap it to open.",
                        "Attach", () -> newNote("Files", "file")))
                .add(new Feature("🗂", "Document wallet",
                        "Scan Aadhaar, PAN, licence, passport, policies… locked with your PIN or fingerprint, off the home screen.",
                        "Open", () -> open(WalletActivity.class)))
                .add(new Feature("📍", "Place reminders",
                        "Save home or office, then write “when I reach office call Rahul” — reminded when you get there.",
                        "Open", () -> open(PlacesActivity.class)))
                .add(new Feature("🌐", "Translate",
                        "Turn a note into English, हिन्दी or বাংলা — on your phone, even offline after the first time.",
                        "Pick a note", () -> pickNote("Translate which note?", "translate")))
                .add(new Feature("✍️", "Handwriting → text",
                        "Write with your finger in a drawing; long-press it → Handwriting → text.",
                        "Write", () -> newNote("Handwriting", "draw")))
                .add(new Feature("🔎", "Search inside photos",
                        "Search on the home screen also finds words inside your photos and scans.",
                        "Search", this::search))
                .add(new Feature("📱", "QR sharing",
                        "Send a note to a phone next to you as QR codes — no internet. Or scan one to make a note.",
                        "Share / scan", this::qrMenu))
                .add(new Feature("🔒", "Password-protected backups",
                        "Lock your backups with a password (AES-256); only you can restore them.",
                        store.backupProtected() ? "On" : "Set", () -> {
                            Intent i = new Intent(this, SettingsActivity.class);
                            i.putExtra(SettingsActivity.EXTRA_BACKUP_PASSWORD, true);
                            startActivity(i);
                        })));
    }

    // ------------------------------------------------------------------ screen

    @Override
    protected void build() {
        body.removeAllViews();
        cards.clear();
        int total = 0;
        for (Category c : cats) total += c.features.size();
        setTitles("Features", total + " things Note can do · tap one to use it");

        // category chips: show one category or all
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(0, px(4), 0, px(2));
        chips.addView(chip("All", shown == -1, -1));
        for (int i = 0; i < cats.size(); i++) chips.addView(chip(cats.get(i).emoji + " " + cats.get(i).title, shown == i, i));
        hs.addView(chips);
        body.addView(hs);

        for (int i = 0; i < cats.size(); i++) {
            if (shown != -1 && shown != i) continue;
            Category c = cats.get(i);
            LinearLayout card = card(c.emoji, c.title, c.features.size() + " features · " + c.sub);
            for (Feature f : c.features) card.addView(featureRow(f));
            cards.add(card);
        }
    }

    private TextView chip(String label, boolean on, int index) {
        TextView t = pill(label, on);
        t.setTextSize(13);
        t.setPadding(px(14), px(8), px(14), px(8));
        t.setOnClickListener(v -> { shown = index; build(); Ui.fonts(root); scroller.scrollTo(0, 0); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = px(8);
        t.setLayoutParams(lp);
        return t;
    }

    private View featureRow(Feature f) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(4), px(11), px(4), px(11));
        row.setBackground(Ui.ripple(Ui.round(0, 12 * dp)));
        TextView icon = text(f.emoji, 18, Ui.REGULAR, getColor(R.color.text));
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(Ui.round(Ui.col(this, R.color.surface2), 12 * dp));
        row.addView(icon, new LinearLayout.LayoutParams(px(40), px(40)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(12), 0, px(8), 0);
        col.addView(text(f.name, 15, Ui.BOLD, getColor(R.color.text)));
        TextView d = text(f.desc, 12, Ui.REGULAR, getColor(R.color.text2));
        d.setLineSpacing(0, 1.1f);
        d.setPadding(0, px(2), 0, 0);
        col.addView(d);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView act = text(f.action, 12, Ui.BOLD, Ui.col(this, R.color.accent));
        act.setPadding(px(10), px(6), px(10), px(6));
        act.setBackground(Ui.round(Ui.col(this, R.color.accent_soft), 14 * dp));
        row.addView(act);
        row.setOnClickListener(v -> f.run.run());
        act.setOnClickListener(v -> f.run.run());
        return row;
    }

    // ------------------------------------------------------------------ actions

    private void open(Class<?> screen) { startActivity(new Intent(this, screen)); }

    /** A new note with examples in it, so you see the feature work. Kept only if you change something. */
    private void example(String title, String body) {
        Intent i = new Intent(this, EditorActivity.class);
        i.putExtra(EditorActivity.EXTRA_NEW_BODY, title + " — try these:\n\n" + body);
        startActivity(i);
    }

    private void template(int index) {
        Intent i = new Intent(this, EditorActivity.class);
        i.putExtra(EditorActivity.EXTRA_TEMPLATE, index);
        startActivity(i);
    }

    /** A new note that starts by recording, attaching, scanning… */
    private void newNote(String title, String auto) {
        Intent i = new Intent(this, EditorActivity.class);
        i.putExtra(EditorActivity.EXTRA_AUTO, auto);   // an empty note isn't kept if nothing is added
        startActivity(i);
    }

    private void pickNote(String title, String auto) {
        List<Note> notes = new ArrayList<>();
        for (Note n : store.notes()) if (n.deleted == 0 && !n.locked && !n.isEmpty()) notes.add(n);
        Collections.sort(notes, (a, b) -> Long.compare(b.updated, a.updated));
        if (notes.isEmpty()) {
            new Ui.Popup(this).setTitle(title).setMessage("Write a note first, then come back here (or open it and tap ⋮).")
                    .setPositiveButton("OK", null).show();
            return;
        }
        if (notes.size() > 40) notes = notes.subList(0, 40);
        CharSequence[] items = new CharSequence[notes.size()];
        for (int k = 0; k < notes.size(); k++) items[k] = "📝  " + (notes.get(k).displayTitle().isEmpty() ? "Untitled" : notes.get(k).displayTitle());
        final List<Note> fn = notes;
        new Ui.Popup(this).sheet().setTitle(title).setItems(items, (d, w) -> {
            Intent i = new Intent(this, EditorActivity.class);
            i.putExtra(EditorActivity.EXTRA_NOTE_ID, fn.get(w).id);
            i.putExtra(EditorActivity.EXTRA_AUTO, auto);
            startActivity(i);
        }).setNegativeButton("Cancel", null).show();
    }

    private void qrMenu() {
        String[] items = {"📤  Share a note as QR codes", "📷  Scan a QR code (make a note from it)"};
        new Ui.Popup(this).setTitle("QR sharing").setItems(items, (d, w) -> {
            if (w == 0) pickNote("Share which note?", "qr");
            else QrShare.scan(this);
        }).show();
    }

    private void search() {
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra("quick", "search");
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }

    private void rollOverSwitch() {
        boolean on = RollOver.on(this);
        new Ui.Popup(this).setTitle("To-do roll-over")
                .setMessage((on ? "It's on. " : "It's off. ") + "Each morning, unticked ☐ lines in older notes labelled #todo move to "
                        + "today's “To-do” note under “From earlier” (in the old note they become ➜).")
                .setPositiveButton(on ? "Turn off" : "Turn on", (d, w) -> {
                    store.prefs().edit().putBoolean(RollOver.P_ON, !on).apply();
                    if (!on) ReminderSync.runInBackground(() -> RollOver.run(this), null);
                    cats.clear();
                    define();
                    build();
                    Ui.fonts(root);
                })
                .setNegativeButton("Close", null).show();
    }

    @Override
    protected void onResume() {
        cats.clear();
        define();   // "On / Off" labels may have changed
        super.onResume();
    }
}
