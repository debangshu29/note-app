package com.debangshu.note;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/** What's on today: reminders between now-ish and midnight, plus tomorrow's. */
public final class Today {

    private Today() {}

    static long startOfDay(int plusDays) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_MONTH, plusDays);
        return c.getTimeInMillis();
    }

    /** Today's reminders that aren't done (including ones earlier today that weren't ticked). */
    public static List<Reminder> remindersToday(Store s) {
        return between(s, startOfDay(0), startOfDay(1));
    }

    public static List<Reminder> remindersTomorrow(Store s) {
        return between(s, startOfDay(1), startOfDay(2));
    }

    private static List<Reminder> between(Store s, long from, long to) {
        List<Reminder> out = new ArrayList<>();
        for (Reminder r : s.reminders()) {
            if (r.done || r.ignored) continue;
            Note n = s.note(r.noteId);
            if (n == null || n.deleted > 0) continue;
            if (r.time >= from && r.time < to) out.add(r);
        }
        Collections.sort(out, (a, b) -> Long.compare(a.time, b.time));
        return out;
    }
}
