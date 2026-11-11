package com.debangshu.note;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Money page: what you spent each month (from the price lists in your notes), who owes whom
 * (lend / borrow lines), bills & subscriptions, and the price book (last price of everything).
 */
public class MoneyActivity extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(Ui.wrap(base)); }

    private Store store;
    private LinearLayout body;
    private float dp;
    private int monthShown = 0;   // 0 = this month, 1 = last month…

    // ------------------------------------------------------------------ data (also used by the Today page)

    static final class Line {
        long noteId;
        String noteTitle;
        String text;
        double amount;
        String name;
    }

    static final class Month {
        String label;
        double total;
        /** category → lines */
        final Map<String, List<Line>> byCat = new LinkedHashMap<>();
    }

    /** Spending for the last {@code count} months, newest first. Notes labelled #budget are plans, not spending. */
    static List<Month> months(Store s, int count) {
        List<Month> out = new ArrayList<>();
        Calendar c = Calendar.getInstance();
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        long[] starts = new long[count + 1];
        starts[0] = Long.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            Month m = new Month();
            m.label = new SimpleDateFormat("MMM yyyy", Locale.ENGLISH).format(c.getTime());
            out.add(m);
            starts[i + 1] = c.getTimeInMillis();
            c.add(Calendar.MONTH, -1);
        }
        for (Note n : s.notes()) {
            if (n.deleted > 0 || n.locked) continue;
            List<String> tags = n.tags();
            if (tags.contains("budget") || tags.contains("plan") || tags.contains(WalletActivity.TAG)) continue;
            int idx = -1;
            for (int i = 0; i < count; i++) if (n.created >= starts[i + 1] && n.created < (i == 0 ? Long.MAX_VALUE : starts[i])) { idx = i; break; }
            if (idx < 0) continue;
            List<Money.Spent> sp = Money.spent(n.body);
            if (sp.isEmpty()) continue;
            String cat = category(n, tags);
            Month m = out.get(idx);
            for (Money.Spent x : sp) {
                Line l = new Line();
                l.noteId = n.id;
                l.noteTitle = n.displayTitle();
                l.name = x.name;
                l.amount = x.amount;
                m.total += x.amount;
                if (!m.byCat.containsKey(cat)) m.byCat.put(cat, new ArrayList<>());
                m.byCat.get(cat).add(l);
            }
        }
        return out;
    }

    private static final String[] SKIP_TAGS = {"todo", "pinned", "important", "money", "shopping-list"};

    private static String category(Note n, List<String> tags) {
        for (String t : tags) {
            boolean skip = false;
            for (String s : SKIP_TAGS) if (s.equals(t)) skip = true;
            if (!skip) return "#" + t;
        }
        String title = n.displayTitle().replaceAll("(?i)\\s*[·\\-–—:]?\\s*\\d{1,2}\\s+[a-z]{3,9}(\\s+\\d{4})?\\s*$", "").trim();
        return title.isEmpty() ? "Other" : title;
    }

    static final class Person {
        String name;
        double balance;
        final List<Line> lines = new ArrayList<>();
    }

    /** Everyone with money still open, biggest first. */
    static List<Person> people(Store s) {
        Map<String, Person> map = new LinkedHashMap<>();
        for (Note n : s.notes()) {
            if (n.deleted > 0 || n.locked) continue;
            for (String raw : n.body.split("\n")) {
                Money.Debt d = Money.debt(raw);
                if (d == null || d.settled) continue;
                String k = d.person.toLowerCase(Locale.ROOT);
                Person p = map.get(k);
                if (p == null) { p = new Person(); p.name = d.person; map.put(k, p); }
                p.balance += d.amount;
                Line l = new Line();
                l.noteId = n.id;
                l.noteTitle = n.displayTitle();
                l.text = raw.trim();
                l.amount = d.amount;
                p.lines.add(l);
            }
        }
        List<Person> out = new ArrayList<>();
        for (Person p : map.values()) if (Math.abs(p.balance) >= 0.5) out.add(p);
        java.util.Collections.sort(out, (a, b) -> Double.compare(Math.abs(b.balance), Math.abs(a.balance)));
        return out;
    }

    static List<Line> bills(Store s) {
        List<Line> out = new ArrayList<>();
        for (Note n : s.notes()) {
            if (n.deleted > 0 || n.locked) continue;
            for (String raw : n.body.split("\n")) {
                Money.Bill b = Money.bill(raw);
                if (b == null || raw.trim().startsWith("☑")) continue;
                Line l = new Line();
                l.noteId = n.id;
                l.noteTitle = n.displayTitle();
                l.text = b.amount == b.perMonth ? ListTotals.money(b.amount, true) + " / month"
                        : ListTotals.money(b.amount, true) + " / " + b.every + "  (≈ " + ListTotals.money(b.perMonth, true) + " a month)";
                l.name = b.name;
                l.amount = b.perMonth;
                out.add(l);
            }
        }
        java.util.Collections.sort(out, (a, b) -> Double.compare(b.amount, a.amount));
        return out;
    }

    // ------------------------------------------------------------------ screen

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.install(this);
        store = Store.get(this);
        dp = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.col(this, R.color.bg));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(px(12), px(10), px(12), px(6));
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_back);
        back.setBackgroundResource(R.drawable.bg_circle);
        back.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.text)));
        back.setPadding(px(11), px(11), px(11), px(11));
        back.setElevation(dp);
        back.setContentDescription("Back");
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(px(44), px(44)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(px(14), 0, 0, 0);
        titles.addView(text("Money", 24, Ui.EXTRABOLD, getColor(R.color.text)));
        titles.addView(text("From the prices in your notes", 13, Ui.SEMIBOLD, getColor(R.color.text2)));
        top.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView help = text("?", 18, Ui.BOLD, Ui.col(this, R.color.accent));
        help.setGravity(Gravity.CENTER);
        help.setBackgroundResource(R.drawable.bg_circle);
        help.setElevation(dp);
        help.setContentDescription("How it works");
        help.setOnClickListener(v -> howItWorks());
        top.addView(help, new LinearLayout.LayoutParams(px(44), px(44)));
        root.addView(top);

        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(px(16), px(6), px(16), px(40));
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.refreshTheme(this)) return;
        if (Lock.guard(this)) return;
        build();
    }

    private void build() {
        body.removeAllViews();
        List<Month> months = months(store, 6);
        Month cur = months.get(Math.min(monthShown, months.size() - 1));

        // ---- spending
        String sub;
        if (monthShown == 0 && months.size() > 1 && months.get(1).total > 0) {
            double diff = cur.total - months.get(1).total;
            sub = ListTotals.money(Math.abs(diff), true) + (diff >= 0 ? " more" : " less") + " than " + months.get(1).label.substring(0, 3);
        } else sub = cur.total == 0 ? "No spending found" : cur.byCat.size() + (cur.byCat.size() == 1 ? " category" : " categories");
        LinearLayout sc = card("🧾", "Spent in " + cur.label, sub);
        TextView big = text(ListTotals.money(cur.total, true), 30, Ui.EXTRABOLD, Ui.col(this, R.color.accent));
        big.setPadding(px(4), px(10), 0, px(6));
        sc.addView(big);

        // six bars, newest on the right; tap one to see that month
        double max = 1;
        for (Month m : months) max = Math.max(max, m.total);
        LinearLayout bars = new LinearLayout(this);
        bars.setGravity(Gravity.BOTTOM);
        bars.setPadding(0, px(6), 0, px(8));
        for (int i = months.size() - 1; i >= 0; i--) {
            Month m = months.get(i);
            final int idx = i;
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
            TextView amt = text(m.total == 0 ? "" : shortMoney(m.total), 10, Ui.SEMIBOLD, getColor(R.color.text2));
            col.addView(amt);
            View bar = new View(this);
            boolean sel = i == monthShown;
            bar.setBackground(Ui.round(sel ? Ui.col(this, R.color.accent) : Ui.col(this, R.color.accent_soft), 6 * dp));
            int h = Math.max(px(4), (int) (px(90) * m.total / max));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(px(26), h);
            blp.topMargin = px(3);
            col.addView(bar, blp);
            TextView lab = text(m.label.substring(0, 3), 11, sel ? Ui.BOLD : Ui.SEMIBOLD, getColor(sel ? R.color.text : R.color.text2));
            lab.setPadding(0, px(4), 0, 0);
            col.addView(lab);
            col.setOnClickListener(v -> { monthShown = idx; build(); });
            bars.addView(col, new LinearLayout.LayoutParams(0, px(130), 1));
        }
        sc.addView(bars);

        if (cur.byCat.isEmpty()) {
            sc.addView(hint("Write prices in a note — “milk 60”, “cab ₹180”, “lunch 250” — and they show up here "
                    + "for the month the note was made. In a list with ☐ ☑ only ticked (bought) things count."));
        } else {
            List<Map.Entry<String, List<Line>>> cats = new ArrayList<>(cur.byCat.entrySet());
            java.util.Collections.sort(cats, (a, b2) -> Double.compare(sum(b2.getValue()), sum(a.getValue())));
            for (Map.Entry<String, List<Line>> e : cats) {
                double s = sum(e.getValue());
                sc.addView(row(e.getKey(), e.getValue().size() + (e.getValue().size() == 1 ? " item" : " items"),
                        ListTotals.money(s, true), (float) (s / Math.max(1, cur.total)), 0, v -> categorySheet(e.getKey(), e.getValue())));
            }
        }

        // ---- lend & borrow
        List<Person> people = people(store);
        double owedToMe = 0, iOwe = 0;
        for (Person p : people) { if (p.balance > 0) owedToMe += p.balance; else iOwe -= p.balance; }
        LinearLayout lc = card("🤝", "Lend & borrow", people.isEmpty() ? "Nobody owes anything"
                : "You get " + ListTotals.money(owedToMe, true) + " · you owe " + ListTotals.money(iOwe, true));
        if (people.isEmpty()) {
            lc.addView(hint("Write “gave Rahul 500”, “borrowed 200 from mom”, “Rahul returned 200” in any note. "
                    + "Tick the line (☑) when it's settled."));
        }
        for (Person p : people) {
            boolean theyOwe = p.balance > 0;
            String what = theyOwe ? p.name + " owes you" : "You owe " + p.name;
            lc.addView(row(what, p.lines.size() + (p.lines.size() == 1 ? " line" : " lines"), ListTotals.money(Math.abs(p.balance), true), -1,
                    getColor(theyOwe ? R.color.success : R.color.danger), v -> personSheet(p)));
        }

        // ---- bills
        List<Line> bills = bills(store);
        double monthly = 0;
        for (Line l : bills) monthly += l.amount;
        LinearLayout bc = card("📅", "Bills & subscriptions", bills.isEmpty() ? "None found"
                : "≈ " + ListTotals.money(monthly, true) + " a month · " + ListTotals.money(monthly * 12, true) + " a year");
        if (bills.isEmpty()) {
            bc.addView(hint("Write “Netflix ₹649/month on 5th”, “rent 15000 pm due on 1st”, “insurance 24000 per year due 10 Mar”. "
                    + "With a day, Note reminds you every month (or year)."));
        }
        for (Line l : bills) bc.addView(row(l.name, l.noteTitle, l.text, -1, 0, v -> openNote(l.noteId)));

        // ---- price book
        PriceBook pb = PriceBook.get(store);
        LinearLayout pc = card("🏷", "Price book", pb.size() == 0 ? "Empty" : pb.size() + " things you've bought");
        pc.addView(hint("The last price of everything on your lists. While you write a list, Note fills in the last price "
                + "in grey — tap it (or swipe right) to keep it."));
        if (pb.size() > 0) pc.addView(row("See all prices", "Search and compare", "›", -1, 0, v -> priceSheet(pb)));

        Ui.fonts(body);
    }

    private double sum(List<Line> l) { double s = 0; for (Line x : l) s += x.amount; return s; }

    private static String shortMoney(double v) {
        if (v >= 100000) return "₹" + String.format(Locale.ROOT, "%.1fL", v / 100000).replace(".0L", "L");
        if (v >= 1000) return "₹" + String.format(Locale.ROOT, "%.1fk", v / 1000).replace(".0k", "k");
        return "₹" + Math.round(v);
    }

    // ------------------------------------------------------------------ sheets

    private void categorySheet(String cat, List<Line> lines) {
        List<CharSequence> items = new ArrayList<>();
        for (Line l : lines) items.add(l.name + "   " + ListTotals.money(l.amount, true) + "\n" + l.noteTitle);
        new Ui.Popup(this).sheet().setTitle(cat + " · " + ListTotals.money(sum(lines), true))
                .setItems(items.toArray(new CharSequence[0]), (d, w) -> openNote(lines.get(w).noteId))
                .setNegativeButton("Close", null).show();
    }

    private void personSheet(Person p) {
        boolean theyOwe = p.balance > 0;
        String[] items = new String[p.lines.size() + 3];
        items[0] = theyOwe ? "🔔  Remind me to ask " + p.name + " (tomorrow 10 am)" : "🔔  Remind me to pay " + p.name + " (tomorrow 10 am)";
        items[1] = "☑  Mark all as settled";
        items[2] = "💬  Ask on WhatsApp / SMS";
        for (int i = 0; i < p.lines.size(); i++) items[i + 3] = "📝  " + p.lines.get(i).text + "\n      " + p.lines.get(i).noteTitle;
        String money = ListTotals.money(Math.abs(p.balance), true);
        new Ui.Popup(this).sheet().setTitle(theyOwe ? p.name + " owes you " + money : "You owe " + p.name + " " + money)
                .setItems(items, (d, w) -> {
                    if (w == 0) {
                        long nid = p.lines.get(p.lines.size() - 1).noteId;
                        appendLine(nid, (theyOwe ? "☐ ask " + p.name + " for " : "☐ pay " + p.name + " ") + money + " tomorrow at 10 am");
                        Toast.makeText(this, "Reminder set for tomorrow 10 am", Toast.LENGTH_SHORT).show();
                    } else if (w == 1) {
                        settleAll(p);
                    } else if (w == 2) {
                        String msg = theyOwe ? "Hi " + p.name + ", just a reminder about the " + money + " 🙂"
                                : "Hi " + p.name + ", I'll send the " + money + " I owe you soon 🙂";
                        Intent i = new Intent(Intent.ACTION_SEND);
                        i.setType("text/plain");
                        i.putExtra(Intent.EXTRA_TEXT, msg);
                        startActivity(Intent.createChooser(i, "Send with"));
                    } else openNote(p.lines.get(w - 3).noteId);
                }).setNegativeButton("Close", null).show();
    }

    /** Ticks every open line about this person (☐ → ☑, or adds ☑ in front). */
    private void settleAll(Person p) {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (Line l : p.lines) ids.add(l.noteId);
        for (long id : ids) {
            Note n = store.note(id);
            if (n == null) continue;
            List<Block> blocks = new ArrayList<>(n.blocksOrText());
            boolean changed = false;
            for (Block b : blocks) {
                if (!b.isText() || b.text == null) continue;
                StringBuilder sb = new StringBuilder();
                String[] ls = b.text.split("\n", -1);
                for (int i = 0; i < ls.length; i++) {
                    String l = ls[i];
                    Money.Debt d = Money.debt(l);
                    if (d != null && !d.settled && d.person.equalsIgnoreCase(p.name)) {
                        int at = 0;
                        while (at < l.length() && (l.charAt(at) == ' ' || l.charAt(at) == '\t')) at++;
                        if (at < l.length() && l.charAt(at) == '☐') l = l.substring(0, at) + "☑" + l.substring(at + 1);
                        else {
                            l = l.substring(0, at) + "☑ " + l.substring(at);
                            // keep formatting in place after the 2 new characters
                            RichText.shiftSpans(b.spans, sb.length() + at, 2);
                        }
                        changed = true;
                    }
                    sb.append(l);
                    if (i < ls.length - 1) sb.append('\n');
                }
                b.text = sb.toString();
            }
            if (changed) {
                n.setBlocks(blocks);
                n.updated = System.currentTimeMillis();
                store.saveNote(n);
            }
        }
        Toast.makeText(this, "Settled with " + p.name, Toast.LENGTH_SHORT).show();
        build();
    }

    /** Adds a line at the end of a note and finds the reminder in it. */
    private void appendLine(long noteId, String line) {
        Note n = store.note(noteId);
        if (n == null) return;
        List<Block> blocks = new ArrayList<>(n.blocksOrText());
        Block last = null;
        for (int i = blocks.size() - 1; i >= 0; i--) if (blocks.get(i).isText()) { last = blocks.get(i); break; }
        if (last == null) { last = Block.text(""); blocks.add(last); }
        last.text = last.text.isEmpty() ? line : last.text + "\n" + line;
        n.setBlocks(blocks);
        n.updated = System.currentTimeMillis();
        store.saveNote(n);
        ReminderSync.syncAsync(this, n.id, null);
    }

    private void priceSheet(PriceBook pb) {
        List<PriceBook.Entry> all = pb.all();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(16), 0, px(16), px(8));
        android.widget.EditText search = new android.widget.EditText(this);
        search.setHint("Search (milk, rice…)");
        search.setSingleLine(true);
        search.setBackground(Ui.round(Ui.col(this, R.color.surface2), 14 * dp));
        search.setPadding(px(14), px(10), px(14), px(10));
        box.addView(search);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(360)));
        Runnable fill = () -> {
            list.removeAllViews();
            String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            int shown = 0;
            for (PriceBook.Entry e : all) {
                if (!q.isEmpty() && !e.key.contains(q)) continue;
                if (shown++ > 200) break;
                String when = new SimpleDateFormat("d MMM", Locale.ENGLISH).format(new Date(e.when));
                String sub = e.line + " · " + when + (e.times > 1 ? " · bought " + e.times + "×" : "")
                        + (e.low < e.price - 0.5 ? " · lowest " + ListTotals.money(e.low, true) : "");
                list.addView(row(e.name, sub, ListTotals.money(e.price, true), -1, 0, v -> openNote(e.noteId)));
            }
            if (shown == 0) list.addView(hint("Nothing found"));
        };
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(android.text.Editable s) { fill.run(); }
        });
        fill.run();
        AlertDialog d = new Ui.Popup(this).sheet().setTitle("Price book").setView(box).setNegativeButton("Close", null).create();
        d.show();
    }

    private void howItWorks() {
        new Ui.Popup(this).setTitle("How Money works")
                .setMessage("Everything comes from what you write — nothing is uploaded.\n\n"
                        + "🧾 Spending: price lists in your notes (“milk 60”, “cab ₹180”), counted in the month the note was made. "
                        + "In a ☐ ☑ list only ticked things count. Notes labelled #budget or #plan are left out.\n\n"
                        + "🤝 Lend & borrow: “gave Rahul 500”, “borrowed 200 from mom”, “Rahul returned 200”, “Rahul ko 500 diye”. "
                        + "Tick the line when it's settled.\n\n"
                        + "👥 Split: in a note write “Rahul paid 1200”, “I paid 800”, “split between me, Rahul, Amit” — "
                        + "a 🤝 chip shows who pays whom.\n\n"
                        + "📅 Bills: “Netflix ₹649/month on 5th”, “rent 15000 pm due on 1st”.\n\n"
                        + "📷 Scan a bill: ＋ → Scan a bill turns a shop bill into a price list.")
                .setPositiveButton("OK", null).show();
    }

    // ------------------------------------------------------------------ views

    private View row(String title, String sub, String amount, float share, int amountColor, View.OnClickListener tap) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(px(10), px(10), px(10), px(10));
        row.setBackground(Ui.ripple(Ui.round(0, 12 * dp)));
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(title, 15, Ui.SEMIBOLD, getColor(R.color.text));
        t.setMaxLines(2);
        col.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = text(sub, 12, Ui.REGULAR, getColor(R.color.text2));
            s.setMaxLines(2);
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(s);
        }
        line.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView a = text(amount, 15, Ui.BOLD, amountColor != 0 ? amountColor : getColor(R.color.text));
        a.setPadding(px(10), 0, 0, 0);
        line.addView(a);
        row.addView(line);
        if (share >= 0) {
            LinearLayout track = new LinearLayout(this);
            track.setBackground(Ui.round(Ui.col(this, R.color.surface2), 3 * dp));
            View fillV = new View(this);
            fillV.setBackground(Ui.round(Ui.col(this, R.color.accent), 3 * dp));
            track.addView(fillV, new LinearLayout.LayoutParams(0, px(5), Math.max(0.02f, share)));
            View rest = new View(this);
            track.addView(rest, new LinearLayout.LayoutParams(0, px(5), Math.max(0f, 1 - share)));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(5));
            tl.topMargin = px(6);
            row.addView(track, tl);
        }
        if (tap != null) row.setOnClickListener(tap);
        return row;
    }

    private TextView hint(String s) {
        TextView t = text(s, 13, Ui.REGULAR, getColor(R.color.text2));
        t.setPadding(px(4), px(8), px(4), px(4));
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private LinearLayout card(String emoji, String title, String sub) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.bg_card_plain);
        c.setElevation(dp);
        c.setPadding(px(16), px(14), px(16), px(14));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = text(emoji, 18, Ui.REGULAR, getColor(R.color.text));
        icon.setGravity(Gravity.CENTER);
        icon.setBackgroundResource(R.drawable.bg_icon_soft);
        head.addView(icon, new LinearLayout.LayoutParams(px(38), px(38)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(12), 0, 0, 0);
        col.addView(text(title, 16, Ui.EXTRABOLD, getColor(R.color.text)));
        col.addView(text(sub, 12, Ui.SEMIBOLD, getColor(R.color.text2)));
        head.addView(col);
        c.addView(head);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(12);
        body.addView(c, lp);
        return c;
    }

    private TextView text(String s, int sp, int weight, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Ui.face(this, weight));
        t.setTextColor(color);
        t.setTag(Ui.KEEP_FONT);
        return t;
    }

    private void openNote(long id) {
        Note n = store.note(id);
        if (n != null && n.locked && !Lock.isOpen(id)) {
            Lock.verify(this, "Locked note", ok -> { if (ok) { Lock.markOpen(id); openNote(id); } });
            return;
        }
        Intent i = new Intent(this, EditorActivity.class);
        i.putExtra(EditorActivity.EXTRA_NOTE_ID, id);
        startActivity(i);
    }

    private int px(float v) { return Math.round(v * dp); }
}
