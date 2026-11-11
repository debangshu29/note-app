package com.debangshu.note;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/** Checklist lines (☐ / ☑) inside notes: listed and ticked from the Today page, widgets and the morning brief. */
public final class Checklist {

    private Checklist() {}

    public static final class Item {
        public long noteId;
        public String noteTitle;
        public int block;      // which text block of the note
        public int offset;     // where the ☐ / ☑ is in that block
        public String text;
        public boolean done;
    }

    /** Every ☐ / ☑ line of a note. */
    public static List<Item> items(Note n) {
        List<Item> out = new ArrayList<>();
        List<Block> blocks = n.blocksOrText();
        for (int b = 0; b < blocks.size(); b++) {
            Block bl = blocks.get(b);
            if (!bl.isText() || bl.text == null) continue;
            String t = bl.text;
            int start = 0;
            while (start <= t.length()) {
                int end = t.indexOf('\n', start);
                if (end < 0) end = t.length();
                int i = start;
                while (i < end && (t.charAt(i) == ' ' || t.charAt(i) == '\t')) i++;
                if (i < end && (t.charAt(i) == '☐' || t.charAt(i) == '☑')) {
                    String rest = t.substring(i + 1, end).trim();
                    if (!rest.isEmpty()) {
                        Item it = new Item();
                        it.noteId = n.id;
                        it.noteTitle = n.displayTitle();
                        it.block = b;
                        it.offset = i;
                        it.text = rest;
                        it.done = t.charAt(i) == '☑';
                        out.add(it);
                    }
                }
                start = end + 1;
            }
        }
        return out;
    }

    /** Things still to do in every (unlocked) note: pinned notes first, then most recently edited. */
    public static List<Item> open(Store s) {
        List<Item> out = new ArrayList<>();
        for (Note n : s.notes()) {
            if (n.locked) continue;
            for (Item it : items(n)) if (!it.done) out.add(it);
        }
        return out;
    }

    public static boolean hasItems(Note n) {
        return !items(n).isEmpty();
    }

    /** Ticks / unticks one line. Returns the new state, or null if the note changed meanwhile. */
    public static Boolean toggle(Context ctx, long noteId, int block, int offset) {
        Store s = Store.get(ctx);
        Note n = s.note(noteId);
        if (n == null || n.deleted > 0) return null;
        List<Block> blocks = new ArrayList<>(n.blocksOrText());
        if (block < 0 || block >= blocks.size()) return null;
        Block bl = blocks.get(block);
        if (!bl.isText() || bl.text == null || offset < 0 || offset >= bl.text.length()) return null;
        char c = bl.text.charAt(offset);
        if (c != '☐' && c != '☑') return null;
        boolean nowDone = c == '☐';
        bl.text = bl.text.substring(0, offset) + (nowDone ? '☑' : '☐') + bl.text.substring(offset + 1);
        n.setBlocks(blocks);
        n.updated = System.currentTimeMillis();
        s.saveNote(n);
        return nowDone;
    }
}
