package com.debangshu.note;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Checklist roll-over: once a day, unticked ☐ lines in older #todo notes move to today's
 * "To-do · 12 Nov" note under "From earlier". In the old note the ☐ becomes ➜ (moved), so
 * nothing is lost and the formatting of the old note stays exactly where it was.
 */
public final class RollOver {

    private RollOver() {}

    public static final String P_ON = "rollover_on";
    private static final String P_DAY = "rollover_day";
    static final char MOVED = '➜';

    public static boolean on(Context c) { return Store.get(c).prefs().getBoolean(P_ON, true); }

    public static String todayTitle(long now) {
        return "To-do · " + new SimpleDateFormat("d MMM", Locale.ENGLISH).format(new Date(now));
    }

    /** Runs at most once a day. Returns how many lines were carried over. */
    public static synchronized int run(Context ctx) {
        if (!on(ctx)) return 0;
        Store s = Store.get(ctx);
        long now = System.currentTimeMillis();
        String day = new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date(now));
        if (day.equals(s.prefs().getString(P_DAY, ""))) return 0;
        s.prefs().edit().putString(P_DAY, day).apply();

        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        long startOfToday = c.getTimeInMillis();
        String title = todayTitle(now);

        Note today = null;
        for (Note n : s.notes()) if (n.deleted == 0 && title.equalsIgnoreCase(n.title.trim())) { today = n; break; }

        List<String> moved = new ArrayList<>();
        for (Note n : new ArrayList<>(s.notes())) {
            if (n.deleted > 0 || n.locked || n == today) continue;
            if (n.created >= startOfToday) continue;           // made today: not "older"
            if (!n.tags().contains("todo")) continue;
            List<Checklist.Item> open = new ArrayList<>();
            for (Checklist.Item it : Checklist.items(n)) if (!it.done) open.add(it);
            if (open.isEmpty()) continue;
            List<Block> blocks = new ArrayList<>(n.blocksOrText());
            for (Checklist.Item it : open) {
                Block b = blocks.get(it.block);
                b.text = b.text.substring(0, it.offset) + MOVED + b.text.substring(it.offset + 1);
                if (!moved.contains(it.text)) moved.add(it.text);
            }
            n.setBlocks(blocks);
            n.updated = now;
            s.saveNote(n);
        }
        if (moved.isEmpty()) return 0;

        // don't add a line that today's list already has
        if (today != null) for (Checklist.Item it : Checklist.items(today)) moved.remove(it.text);
        if (moved.isEmpty()) return 0;
        StringBuilder add = new StringBuilder("From earlier");
        for (String m : moved) add.append("\n☐ ").append(m);

        if (today == null) {
            today = new Note();
            today.id = s.newId();
            today.created = now;
            today.title = title;
            List<Block> bl = new ArrayList<>();
            bl.add(Block.text("#todo\n\n" + add));
            today.setBlocks(bl);
        } else {
            List<Block> blocks = new ArrayList<>(today.blocksOrText());
            Block last = null;
            for (int i = blocks.size() - 1; i >= 0; i--) if (blocks.get(i).isText()) { last = blocks.get(i); break; }
            if (last == null) { last = Block.text(""); blocks.add(last); }
            last.text = (last.text.isEmpty() ? "" : last.text + "\n\n") + add;   // added at the end: formatting stays put
            today.setBlocks(blocks);
        }
        today.updated = now;
        s.saveNote(today);
        return moved.size();
    }
}
