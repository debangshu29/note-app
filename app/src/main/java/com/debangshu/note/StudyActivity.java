package com.debangshu.note;

import android.content.Intent;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Study: flashcards and quizzes made from the cards written in notes ("question :: answer", "Q: … / A: …").
 * Flashcards use spaced repetition — a card you knew comes back after 1, 2, 4, 8, 16 days; one you missed comes back today.
 */
public class StudyActivity extends Screen {

    public static final String EXTRA_NOTE = "studyNote";

    private SharedPreferences prefs;
    private long deckId = -1;
    private int mode = 0;            // 0 = decks, 1 = flashcards, 2 = quiz, 3 = finished
    private List<Cards.Card> deck, queue;
    private int pos, right, wrong;
    private boolean flipped, answered;
    private final Random rnd = new Random();

    @Override
    protected void onCreate(android.os.Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("study", MODE_PRIVATE);
        deckId = getIntent().getLongExtra(EXTRA_NOTE, -1);
    }

    @Override
    public void onBackPressed() {
        if (mode != 0 && getIntent().getLongExtra(EXTRA_NOTE, -1) < 0) { mode = 0; build(); Ui.fonts(root); return; }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ progress

    private String pkey(long note, Cards.Card c) { return note + "_" + c.key(); }

    private int box(long note, Cards.Card c) {
        String v = prefs.getString(pkey(note, c), null);
        if (v == null) return 0;
        try { return Integer.parseInt(v.split(":")[0]); } catch (Exception e) { return 0; }
    }

    private long due(long note, Cards.Card c) {
        String v = prefs.getString(pkey(note, c), null);
        if (v == null) return 0;
        try { return Long.parseLong(v.split(":")[1]); } catch (Exception e) { return 0; }
    }

    private void mark(long note, Cards.Card c, boolean knew) {
        int b = Cards.nextBox(box(note, c), knew);
        prefs.edit().putString(pkey(note, c), b + ":" + Cards.dueAfter(b, System.currentTimeMillis())).apply();
    }

    private int dueCount(long note, List<Cards.Card> cards) {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Cards.Card c : cards) if (due(note, c) <= now) n++;
        return n;
    }

    // ------------------------------------------------------------------ screens

    @Override
    protected void build() {
        body.removeAllViews();
        if (deckId >= 0 && mode == 0 && !menuShown) {
            menuShown = true;
            Note n = store.note(deckId);
            List<Cards.Card> cards = n == null ? new ArrayList<>() : Cards.parse(n.title + "\n" + n.body);
            if (cards.size() >= 1) { deckMenu(n, cards, true); }
            else { deckId = -1; }
        }
        if (mode == 0) decks();
        else if (mode == 1) flash();
        else if (mode == 2) quiz();
        else finished();
    }

    private void decks() {
        setTitles("Study", "Flashcards & quizzes from your notes");
        List<Note> notes = new ArrayList<>();
        for (Note n : store.notes()) {
            if (n.deleted > 0 || n.locked) continue;
            if (Cards.parse(n.title + "\n" + n.body).size() >= 2) notes.add(n);
        }
        if (notes.isEmpty()) {
            LinearLayout c = card("🎴", "Make your first cards", "Write them in any note");
            c.addView(hint("One card per line:\n\n"
                    + "   Capital of France :: Paris\n"
                    + "   H2O :: Water\n\n"
                    + "or a question and its answer on two lines:\n\n"
                    + "   Q: Who wrote Gitanjali?\n"
                    + "   A: Rabindranath Tagore\n\n"
                    + "In a note labelled #flashcards (or #study) you can also write “word - meaning” or “word: meaning”."));
            TextView make = pill("New flashcards note", true);
            make.setOnClickListener(v -> {
                Intent i = new Intent(this, EditorActivity.class);
                i.putExtra(EditorActivity.EXTRA_NEW_TITLE, "Flashcards");
                i.putExtra(EditorActivity.EXTRA_NEW_BODY, "#flashcards\n\nCapital of France :: Paris\nH2O :: Water\n");
                startActivity(i);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = px(12);
            c.addView(make, lp);
            return;
        }
        int totalDue = 0;
        LinearLayout c = card("🎴", "Your decks", notes.size() + (notes.size() == 1 ? " note with cards" : " notes with cards"));
        for (Note n : notes) {
            List<Cards.Card> cards = Cards.parse(n.title + "\n" + n.body);
            int d = dueCount(n.id, cards);
            totalDue += d;
            c.addView(row("🎴  " + (n.displayTitle().isEmpty() ? "Untitled" : n.displayTitle()),
                    cards.size() + " cards" + (d > 0 ? " · " + d + " to review" : " · all caught up ✓"), "›",
                    v -> deckMenu(n, cards, false)));
        }
        setTitles("Study", totalDue > 0 ? totalDue + " cards to review today" : "All caught up ✓");
    }

    private void deckMenu(Note n, List<Cards.Card> cards, boolean fromNote) {
        int d = dueCount(n.id, cards);
        String[] items = {"🎴  Flashcards" + (d > 0 ? "  (" + d + " to review)" : "  (all " + cards.size() + ")"),
                "❓  Quiz (multiple choice)", "📝  Open the note", "↺  Start this deck over"};
        new Ui.Popup(this).setTitle(n.displayTitle().isEmpty() ? "Cards" : n.displayTitle()).setItems(items, (dd, w) -> {
            deck = cards;
            deckId = n.id;
            if (w == 0) startFlash(n.id);
            else if (w == 1) startQuiz();
            else if (w == 2) { openNote(n.id); return; }
            else {
                SharedPreferences.Editor e = prefs.edit();
                for (Cards.Card c : cards) e.remove(pkey(n.id, c));
                e.apply();
                if (!fromNote) { build(); Ui.fonts(root); }
                return;
            }
            build();
            Ui.fonts(root);
        }).setOnCancelListener(x -> { if (fromNote) finish(); }).show();
    }

    private void startFlash(long note) {
        long now = System.currentTimeMillis();
        queue = new ArrayList<>();
        for (Cards.Card c : deck) if (due(note, c) <= now) queue.add(c);
        if (queue.isEmpty()) queue.addAll(deck);   // nothing due: go through all of them
        Collections.shuffle(queue, rnd);
        pos = 0; right = 0; wrong = 0; flipped = false;
        mode = 1;
        lastWasQuiz = false;
    }

    private void startQuiz() {
        queue = new ArrayList<>(deck);
        Collections.shuffle(queue, rnd);
        if (queue.size() > 20) queue = queue.subList(0, 20);
        pos = 0; right = 0; wrong = 0; answered = false;
        mode = 2;
        lastWasQuiz = true;
    }

    private void flash() {
        if (pos >= queue.size()) { mode = 3; finished(); return; }
        Cards.Card c = queue.get(pos);
        setTitles("Flashcards", (pos + 1) + " of " + queue.size() + "  ·  ✓ " + right + "  ✗ " + wrong);
        body.addView(progress((float) pos / queue.size()));

        LinearLayout cardV = new LinearLayout(this);
        cardV.setOrientation(LinearLayout.VERTICAL);
        cardV.setGravity(Gravity.CENTER);
        cardV.setMinimumHeight(px(300));
        cardV.setPadding(px(22), px(22), px(22), px(22));
        cardV.setBackground(Ui.ripple(Ui.round(flipped ? Ui.col(this, R.color.accent_soft) : Ui.col(this, R.color.surface), 24 * dp)));
        cardV.setElevation(3 * dp);
        TextView label = text(flipped ? "ANSWER" : "QUESTION", 11, Ui.BOLD, Ui.col(this, R.color.accent));
        label.setLetterSpacing(0.12f);
        cardV.addView(label);
        TextView main = text(flipped ? c.a : c.q, flipped ? 22 : 24, Ui.EXTRABOLD, getColor(R.color.text));
        main.setGravity(Gravity.CENTER);
        main.setPadding(0, px(14), 0, px(14));
        cardV.addView(main);
        if (flipped) {
            TextView q = text(c.q, 14, Ui.REGULAR, getColor(R.color.text2));
            q.setGravity(Gravity.CENTER);
            cardV.addView(q);
        } else cardV.addView(text("Tap to see the answer", 13, Ui.REGULAR, getColor(R.color.text2)));
        cardV.setOnClickListener(v -> { flipped = !flipped; build(); Ui.fonts(root); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(16);
        body.addView(cardV, lp);

        if (flipped) {
            LinearLayout btns = new LinearLayout(this);
            btns.setPadding(0, px(18), 0, 0);
            TextView again = pill("✗  Again", false);
            again.setOnClickListener(v -> answer(c, false));
            TextView knew = pill("✓  Knew it", true);
            knew.setOnClickListener(v -> answer(c, true));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, px(52), 1);
            bl.rightMargin = px(10);
            btns.addView(again, bl);
            btns.addView(knew, new LinearLayout.LayoutParams(0, px(52), 1));
            body.addView(btns);
            int b = Cards.nextBox(box(deckId, c), true);
            TextView when = hint("Knew it → see it again in " + Cards.BOX_DAYS[b] + (Cards.BOX_DAYS[b] == 1 ? " day" : " days")
                    + ".  Again → later today.");
            when.setGravity(Gravity.CENTER);
            body.addView(when);
        }
    }

    private void answer(Cards.Card c, boolean knew) {
        mark(deckId, c, knew);
        if (knew) right++;
        else { wrong++; queue.add(c); }   // missed: once more at the end
        pos++;
        flipped = false;
        build();
        Ui.fonts(root);
    }

    private void quiz() {
        if (pos >= queue.size()) { mode = 3; finished(); return; }
        Cards.Card c = queue.get(pos);
        setTitles("Quiz", (pos + 1) + " of " + queue.size() + "  ·  score " + right);
        body.addView(progress((float) pos / queue.size()));
        LinearLayout qc = card(null, null, null);
        TextView q = text(c.q, 21, Ui.EXTRABOLD, getColor(R.color.text));
        q.setPadding(0, px(6), 0, px(6));
        qc.addView(q);
        List<String> choices = Cards.choices(deck, c, new Random(c.key().hashCode() + pos));
        for (String ch : choices) {
            TextView t = text(ch, 15, Ui.SEMIBOLD, getColor(R.color.text));
            t.setPadding(px(16), px(14), px(16), px(14));
            t.setBackground(Ui.ripple(Ui.round(Ui.col(this, R.color.surface2), 14 * dp)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = px(10);
            body.addView(t, lp);
            t.setOnClickListener(v -> {
                if (answered) return;
                answered = true;
                boolean ok = ch.equals(c.a);
                if (ok) right++; else wrong++;
                mark(deckId, c, ok);
                for (int i = 0; i < body.getChildCount(); i++) {
                    View x = body.getChildAt(i);
                    if (!(x instanceof TextView) || x == t) continue;
                    if (((TextView) x).getText().toString().equals(c.a)) {
                        x.setBackground(Ui.round(getColor(R.color.success), 14 * dp));
                        ((TextView) x).setTextColor(getColor(R.color.on_accent));
                    }
                }
                t.setBackground(Ui.round(getColor(ok ? R.color.success : R.color.danger), 14 * dp));
                t.setTextColor(getColor(R.color.on_accent));
                TextView next = pill(pos + 1 >= queue.size() ? "See the score" : "Next  ›", true);
                next.setOnClickListener(v2 -> { pos++; answered = false; build(); Ui.fonts(root); });
                LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(52));
                nl.topMargin = px(18);
                body.addView(next, nl);
                Ui.fonts(next);
            });
        }
    }

    private void finished() {
        int total = right + wrong;
        boolean wasQuiz = queue != null && mode == 3 && answeredQuiz();
        setTitles("Done!", "");
        LinearLayout c = card("🎉", wasQuiz ? "Quiz finished" : "Review finished",
                total == 0 ? "" : right + " right · " + wrong + (wrong == 1 ? " to practise" : " to practise"));
        if (total > 0) {
            int pct = Math.round(100f * right / total);
            TextView big = text(pct + "%", 40, Ui.EXTRABOLD, Ui.col(this, R.color.accent));
            big.setPadding(px(4), px(10), 0, px(4));
            c.addView(big);
            c.addView(hint(pct >= 90 ? "Excellent! 🌟" : pct >= 70 ? "Good going — the missed ones come back sooner." : "Keep at it — missed cards come back today."));
        }
        LinearLayout btns = new LinearLayout(this);
        btns.setPadding(0, px(14), 0, 0);
        TextView again = pill("Go again", false);
        again.setOnClickListener(v -> { if (wasQuiz) startQuiz(); else startFlash(deckId); build(); Ui.fonts(root); });
        TextView close = pill("Close", true);
        close.setOnClickListener(v -> {
            if (getIntent().getLongExtra(EXTRA_NOTE, -1) >= 0) finish();
            else { mode = 0; deckId = -1; build(); Ui.fonts(root); }
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, px(50), 1);
        bl.rightMargin = px(10);
        btns.addView(again, bl);
        btns.addView(close, new LinearLayout.LayoutParams(0, px(50), 1));
        c.addView(btns);
    }

    private boolean lastWasQuiz, menuShown;
    private boolean answeredQuiz() { return lastWasQuiz; }

    private View progress(float f) {
        LinearLayout track = new LinearLayout(this);
        track.setBackground(Ui.round(Ui.col(this, R.color.surface2), 3 * dp));
        View fill = new View(this);
        fill.setBackground(Ui.round(Ui.col(this, R.color.accent), 3 * dp));
        track.addView(fill, new LinearLayout.LayoutParams(0, px(6), Math.max(0.01f, f)));
        track.addView(new View(this), new LinearLayout.LayoutParams(0, px(6), Math.max(0.0f, 1 - f)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(6));
        lp.topMargin = px(8);
        track.setLayoutParams(lp);
        return track;
    }
}
