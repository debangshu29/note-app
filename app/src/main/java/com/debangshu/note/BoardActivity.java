package com.debangshu.note;

import android.content.ClipData;
import android.content.Intent;
import android.graphics.Paint;
import android.text.SpannableStringBuilder;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Board view of a note: "# To do", "# Doing", "# Done" headings become columns and the list lines
 * under them become cards. Drag a card (long-press) to another column, or tap it for the options.
 * Everything is written back into the note's text, so the note and the board are the same thing.
 */
public class BoardActivity extends Screen {

    public static final String EXTRA_NOTE = "boardNote";
    public static final String EXTRA_FROM_EDITOR = "boardFromEditor";

    private long noteId;
    private int blockIndex = -1;
    private BoardModel model;
    private HorizontalScrollView hs;

    @Override
    protected boolean scrolls() { return false; }

    @Override
    protected void onCreate(android.os.Bundle b) {
        super.onCreate(b);
        noteId = getIntent().getLongExtra(EXTRA_NOTE, -1);
        topButton("📝", "Open as a note", false, v -> openAsNote());
    }

    @Override
    public void onBackPressed() {
        if (getIntent().getBooleanExtra(EXTRA_FROM_EDITOR, false)) { openAsNote(); return; }
        super.onBackPressed();
    }

    private void openAsNote() {
        Intent i = new Intent(this, EditorActivity.class);
        i.putExtra(EditorActivity.EXTRA_NOTE_ID, noteId);
        startActivity(i);
        finish();
    }

    private Note note() { return store.note(noteId); }

    /** The text block the board lives in: the first one with headings, else the one with the most list lines. */
    private int findBlock(Note n) {
        List<Block> blocks = n.blocksOrText();
        int best = -1, bestCards = -1;
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            if (!b.isText()) continue;
            BoardModel m = BoardModel.parse(b.text);
            if (m.hasColumns()) return i;
            int c = 0;
            for (BoardModel.Column col : m.columns) c += col.cards.size();
            if (c > bestCards) { bestCards = c; best = i; }
        }
        return best;
    }

    @Override
    protected void build() {
        body.removeAllViews();
        Note n = note();
        if (n == null || n.deleted > 0) { finish(); return; }
        if (n.locked && !Lock.isOpen(noteId)) {
            Lock.verify(this, "Locked note", ok -> { if (ok) { Lock.markOpen(noteId); build(); Ui.fonts(root); } else finish(); });
            return;
        }
        blockIndex = findBlock(n);
        String title = n.displayTitle().isEmpty() ? "Board" : n.displayTitle();
        if (blockIndex < 0) { blockIndex = 0; }
        List<Block> blocks = n.blocksOrText();
        Block b = blockIndex < blocks.size() ? blocks.get(blockIndex) : null;
        model = BoardModel.parse(b == null ? "" : b.text);
        int cards = 0;
        for (BoardModel.Column c : model.columns) cards += c.cards.size();
        setTitles(title, model.hasColumns() ? model.columns.size() + " columns · " + cards + " cards" : "Board view");

        if (!model.hasColumns()) {
            ScrollView sv = new ScrollView(this);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(px(16), px(6), px(16), px(40));
            sv.addView(box);
            body.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout old = body;
            body = box;
            LinearLayout c = card("🗂", "Make this note a board", "To do · Doing · Done");
            c.addView(hint("A board is made from headings: every line starting with # becomes a column and the "
                    + "☐ lines under it become cards. Note will add “# To do”, “# Doing” and “# Done” and put your "
                    + cards + (cards == 1 ? " list line" : " list lines") + " in To do (ticked ones in Done)."));
            TextView go = pill("Make the board", true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = px(12);
            go.setOnClickListener(v -> makeStarter());
            c.addView(go, lp);
            body = old;
            return;
        }

        hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        hs.setPadding(px(12), px(8), px(12), px(16));
        LinearLayout row = new LinearLayout(this);
        hs.addView(row);
        for (int ci = 0; ci < model.columns.size(); ci++) row.addView(column(ci));
        TextView add = pill("＋ Column", false);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = px(4);
        alp.topMargin = px(6);
        add.setOnClickListener(v -> ask("New column", "", "e.g. Waiting", t -> appendText("\n# " + t)));
        row.addView(add, alp);
        body.addView(hs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private View column(int ci) {
        BoardModel.Column col = model.columns.get(ci);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.round(Ui.col(this, R.color.surface2), 18 * dp));
        c.setPadding(px(10), px(10), px(10), px(10));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(px(4), 0, px(4), px(6));
        TextView name = text((col.isDone() ? "✅ " : "") + col.title, 15, Ui.EXTRABOLD, getColor(R.color.text));
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView count = text(String.valueOf(col.cards.size()), 12, Ui.BOLD, Ui.col(this, R.color.accent));
        count.setPadding(px(8), px(2), px(8), px(2));
        count.setBackground(Ui.round(Ui.col(this, R.color.accent_soft), 10 * dp));
        head.addView(count);
        if (col.headingLine >= 0) head.setOnClickListener(v -> columnMenu(ci));
        c.addView(head);

        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        for (int line : col.cards) list.addView(cardView(line, ci));
        if (col.cards.isEmpty()) {
            TextView e = text("Drop cards here", 13, Ui.REGULAR, getColor(R.color.hint));
            e.setGravity(Gravity.CENTER);
            e.setPadding(0, px(18), 0, px(18));
            list.addView(e);
        }
        c.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        TextView add = text("＋ Add a card", 14, Ui.BOLD, Ui.col(this, R.color.accent));
        add.setPadding(px(6), px(10), px(6), px(2));
        add.setOnClickListener(v -> ask("Add a card to " + col.title, "", "What needs doing?",
                t -> insertCard(ci, (col.isDone() ? "☑ " : "☐ ") + t)));
        c.addView(add);

        // drop target
        c.setOnDragListener((v, ev) -> {
            switch (ev.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED: return true;
                case DragEvent.ACTION_DRAG_ENTERED:
                    v.setBackground(Ui.round(Ui.col(this, R.color.accent_soft), 18 * dp)); return true;
                case DragEvent.ACTION_DRAG_EXITED:
                case DragEvent.ACTION_DRAG_ENDED:
                    v.setBackground(Ui.round(Ui.col(this, R.color.surface2), 18 * dp)); return true;
                case DragEvent.ACTION_DRAG_LOCATION: {
                    // near the screen edge: scroll the board
                    float x = v.getLeft() - hs.getScrollX() + ev.getX();
                    int w = hs.getWidth();
                    if (x > w - px(48)) hs.smoothScrollBy(px(24), 0);
                    else if (x < px(48)) hs.smoothScrollBy(-px(24), 0);
                    return true;
                }
                case DragEvent.ACTION_DROP: {
                    Object st = ev.getLocalState();
                    if (st instanceof Integer) {
                        int line = (Integer) st;
                        v.post(() -> moveCard(line, ci));
                    }
                    return true;
                }
            }
            return false;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(270), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.rightMargin = px(10);
        c.setLayoutParams(lp);
        return c;
    }

    private View cardView(int line, int ci) {
        String raw = model.lines[line];
        boolean ticked = BoardModel.isTicked(raw);
        TextView t = text(BoardModel.cardText(raw), 14, Ui.SEMIBOLD, getColor(ticked ? R.color.text2 : R.color.text));
        if (ticked) t.setPaintFlags(t.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        t.setPadding(px(12), px(12), px(12), px(12));
        t.setBackground(Ui.ripple(Ui.round(Ui.col(this, R.color.surface), 12 * dp)));
        t.setElevation(dp);
        t.setOnClickListener(v -> cardMenu(line, ci));
        t.setOnLongClickListener(v -> {
            v.startDragAndDrop(ClipData.newPlainText("card", BoardModel.cardText(raw)), new View.DragShadowBuilder(v), line, 0);
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(8);
        lp.leftMargin = px(2);
        lp.rightMargin = px(2);
        t.setLayoutParams(lp);
        return t;
    }

    // ------------------------------------------------------------------ menus

    private void cardMenu(int line, int ci) {
        List<String> items = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        for (int k = 0; k < model.columns.size(); k++) {
            if (k == ci) continue;
            final int target = k;
            items.add("➜  Move to " + model.columns.get(k).title);
            acts.add(() -> moveCard(line, target));
        }
        boolean ticked = BoardModel.isTicked(model.lines[line]);
        items.add(ticked ? "☐  Untick" : "☑  Tick");
        acts.add(() -> edit(ssb -> { setMark(ssb, line, !ticked); return true; }));
        items.add("✏️  Edit");
        acts.add(() -> ask("Edit card", BoardModel.cardText(model.lines[line]), "", t -> edit(ssb -> {
            int[] r = range(ssb, line);
            String l = ssb.subSequence(r[0], r[1]).toString();
            String now = BoardModel.cardText(l);
            int at = l.lastIndexOf(now);
            if (at < 0) return false;
            ssb.replace(r[0] + at, r[0] + at + now.length(), t);
            return true;
        })));
        items.add("🗑  Delete");
        acts.add(() -> edit(ssb -> { cut(ssb, line); return true; }));
        new Ui.Popup(this).setTitle(BoardModel.cardText(model.lines[line]))
                .setItems(items.toArray(new String[0]), (d, w) -> acts.get(w).run()).show();
    }

    private void columnMenu(int ci) {
        BoardModel.Column col = model.columns.get(ci);
        String[] items = {"＋  Add a card", "✏️  Rename", "⇤  Move column left", "⇥  Move column right", "🗑  Remove column (its cards join the one before)"};
        new Ui.Popup(this).setTitle(col.title).setItems(items, (d, w) -> {
            switch (w) {
                case 0: ask("Add a card to " + col.title, "", "What needs doing?", t -> insertCard(ci, (col.isDone() ? "☑ " : "☐ ") + t)); break;
                case 1: ask("Rename column", col.title, "", t -> edit(ssb -> {
                    int[] r = range(ssb, col.headingLine);
                    String l = ssb.subSequence(r[0], r[1]).toString();
                    int at = l.indexOf(col.title);
                    if (at < 0) return false;
                    ssb.replace(r[0] + at, r[0] + at + col.title.length(), t);
                    return true;
                })); break;
                case 2: swapColumns(ci, ci - 1); break;
                case 3: swapColumns(ci, ci + 1); break;
                case 4: edit(ssb -> { cut(ssb, col.headingLine); return true; }); break;
            }
        }).show();
    }

    private interface Text { void got(String t); }

    private void ask(String title, String start, String hintText, Text then) {
        EditText et = new EditText(this);
        et.setText(start);
        et.setHint(hintText);
        et.setSelection(et.length());
        LinearLayout box = new LinearLayout(this);
        box.setPadding(px(20), px(4), px(20), 0);
        box.addView(et, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new Ui.Popup(this).setTitle(title).setView(box)
                .setPositiveButton("OK", (d, w) -> {
                    String t = et.getText().toString().replace('\n', ' ').trim();
                    if (!t.isEmpty()) then.got(t);
                })
                .setNegativeButton("Cancel", null).show();
        et.requestFocus();
    }

    // ------------------------------------------------------------------ editing the note's text

    private interface Change { boolean apply(SpannableStringBuilder ssb); }

    /** Runs a change on the board's text block (formatting kept) and saves the note. */
    private void edit(Change ch) {
        Note n = note();
        if (n == null) return;
        List<Block> blocks = new ArrayList<>(n.blocksOrText());
        if (blockIndex < 0 || blockIndex >= blocks.size() || !blocks.get(blockIndex).isText()) return;
        Block b = blocks.get(blockIndex);
        SpannableStringBuilder ssb = RichText.build(b.text, b.spans);
        if (!ch.apply(ssb)) { Toast.makeText(this, "Couldn't change that", Toast.LENGTH_SHORT).show(); return; }
        b.text = ssb.toString();
        b.spans = RichText.toJson(ssb);
        n.setBlocks(blocks);
        n.updated = System.currentTimeMillis();
        store.saveNote(n);
        ReminderSync.syncAsync(this, n.id, null);
        int x = hs == null ? 0 : hs.getScrollX();
        build();
        Ui.fonts(root);
        if (hs != null) hs.post(() -> hs.scrollTo(x, 0));
    }

    /** {start, end} of a line (end = before its '\n'). */
    private static int[] range(CharSequence s, int line) {
        int start = 0;
        for (int i = 0; i < line; i++) {
            int nl = indexOf(s, '\n', start);
            if (nl < 0) return new int[]{s.length(), s.length()};
            start = nl + 1;
        }
        int end = indexOf(s, '\n', start);
        return new int[]{start, end < 0 ? s.length() : end};
    }

    private static int indexOf(CharSequence s, char c, int from) {
        for (int i = from; i < s.length(); i++) if (s.charAt(i) == c) return i;
        return -1;
    }

    /** Removes a line and gives it back (with its formatting). */
    private static CharSequence cut(SpannableStringBuilder ssb, int line) {
        int[] r = range(ssb, line);
        CharSequence piece = new SpannableStringBuilder(ssb.subSequence(r[0], r[1]));
        if (r[1] < ssb.length()) ssb.delete(r[0], r[1] + 1);           // the line and its '\n'
        else if (r[0] > 0) ssb.delete(r[0] - 1, r[1]);                 // last line: the '\n' before it
        else ssb.delete(r[0], r[1]);
        return piece;
    }

    /** Puts a line after line {@code after} (−1 = at the very top). */
    private static void insertAfter(SpannableStringBuilder ssb, int after, CharSequence piece) {
        if (after < 0) { ssb.insert(0, "\n"); ssb.insert(0, piece); return; }
        int[] r = range(ssb, after);
        ssb.insert(r[1], "\n");
        ssb.insert(r[1] + 1, piece);
    }

    private static void setMark(SpannableStringBuilder ssb, int line, boolean ticked) {
        int[] r = range(ssb, line);
        for (int i = r[0]; i < r[1]; i++) {
            char c = ssb.charAt(i);
            if (c == ' ' || c == '\t') continue;
            if (c == '☐' || c == '☑') ssb.replace(i, i + 1, ticked ? "☑" : "☐");
            else if (ticked && (c == '•' || c == '-' || c == '*')) {
                ssb.replace(i, i + 1, "☑");
            } else if (ticked) ssb.insert(i, "☑ ");
            return;
        }
    }

    /** The last line that belongs to a column (its last card, or its heading). */
    private int lastLineOf(int ci) {
        BoardModel.Column c = model.columns.get(ci);
        if (!c.cards.isEmpty()) return c.cards.get(c.cards.size() - 1);
        return c.headingLine;
    }

    private void moveCard(int line, int toCol) {
        int fromCol = -1;
        for (int k = 0; k < model.columns.size(); k++) if (model.columns.get(k).cards.contains(line)) fromCol = k;
        if (fromCol == toCol || fromCol < 0) return;
        int target = lastLineOf(toCol);
        boolean done = model.columns.get(toCol).isDone(), wasDone = model.columns.get(fromCol).isDone();
        edit(ssb -> {
            CharSequence piece = cut(ssb, line);
            int after = target > line ? target - 1 : target;
            insertAfter(ssb, after, piece);
            int newLine = after + 1;
            if (done) setMark(ssb, newLine, true);
            else if (wasDone) setMark(ssb, newLine, false);
            return true;
        });
    }

    private void insertCard(int ci, String text) {
        int after = lastLineOf(ci);
        edit(ssb -> { insertAfter(ssb, after, text); return true; });
    }

    private void appendText(String t) {
        edit(ssb -> { ssb.append(ssb.length() == 0 || ssb.charAt(ssb.length() - 1) == '\n' ? t.substring(1) : t); return true; });
    }

    /** Moves a whole column (heading and cards) past its neighbour. */
    private void swapColumns(int a, int b) {
        if (b < 0 || b >= model.columns.size()) return;
        int lo = Math.min(a, b), hi = Math.max(a, b);
        BoardModel.Column left = model.columns.get(lo), right = model.columns.get(hi);
        if (left.headingLine < 0 || right.headingLine < 0) return;
        int rightEnd = hi + 1 < model.columns.size() ? model.columns.get(hi + 1).headingLine - 1 : model.lines.length - 1;
        edit(ssb -> {
            // cut the right column's lines (heading .. just before the next heading) and put them before the left heading
            List<CharSequence> pieces = new ArrayList<>();
            for (int l = rightEnd; l >= right.headingLine; l--) pieces.add(0, cut(ssb, l));
            int after = left.headingLine - 1;
            for (CharSequence p : pieces) { insertAfter(ssb, after, p); after++; }
            return true;
        });
    }

    private void makeStarter() {
        Note n = note();
        if (n == null) return;
        List<Block> blocks = new ArrayList<>(n.blocksOrText());
        if (blockIndex < 0 || blockIndex >= blocks.size()) { blocks.add(Block.text("")); blockIndex = blocks.size() - 1; }
        Block b = blocks.get(blockIndex);
        b.text = BoardModel.starter(b.text);
        b.spans = new org.json.JSONArray();
        n.setBlocks(blocks);
        n.updated = System.currentTimeMillis();
        store.saveNote(n);
        build();
        Ui.fonts(root);
    }
}
