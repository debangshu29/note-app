package com.debangshu.note;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** One habit: a name, which days it's for, an optional reminder time and the days it was done. */
public final class Habit {

    public long id;
    public String name = "";
    public String emoji = "✅";
    /** Bit 0 = Sunday … bit 6 = Saturday (Calendar.DAY_OF_WEEK − 1). 127 = every day. */
    public int days = 127;
    public int hour = -1, minute = 0;    // reminder; hour −1 = none
    public long created;
    public final Set<String> done = new HashSet<>();   // "yyyyMMdd"

    public static String key(Calendar c) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.ROOT);
        f.setTimeZone(c.getTimeZone());
        return f.format(c.getTime());
    }

    public boolean onDay(Calendar c) { return (days & (1 << (c.get(Calendar.DAY_OF_WEEK) - 1))) != 0; }

    public boolean doneOn(Calendar c) { return done.contains(key(c)); }

    /** Days in a row (only counting the habit's own days). Today not done yet doesn't break it. */
    public int streak(Calendar today) {
        Calendar c = (Calendar) today.clone();
        int n = 0;
        if (!doneOn(c)) c.add(Calendar.DAY_OF_MONTH, -1);
        for (int guard = 0; guard < 3660; guard++) {
            if (onDay(c)) {
                if (doneOn(c)) n++;
                else break;
            }
            c.add(Calendar.DAY_OF_MONTH, -1);
            if (created > 0 && c.getTimeInMillis() < created - 86_400_000L) break;
        }
        return n;
    }

    /** Best run ever (up to {@code today}). */
    public int best(Calendar today) {
        if (done.isEmpty()) return 0;
        String first = null;
        for (String d : done) if (first == null || d.compareTo(first) < 0) first = d;
        Calendar c = Calendar.getInstance();
        c.set(toInt(first.substring(0, 4)), toInt(first.substring(4, 6)) - 1, toInt(first.substring(6, 8)), 12, 0, 0);
        Calendar end = (Calendar) today.clone();
        end.set(Calendar.HOUR_OF_DAY, 23);
        int best = 0, run = 0;
        for (int guard = 0; guard < 3660 && !c.after(end); guard++) {
            if (onDay(c)) {
                if (doneOn(c)) { run++; best = Math.max(best, run); }
                else if (!key(c).equals(key(end))) run = 0;
            }
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return best;
    }

    /** Share of the habit's days done in the last {@code n} days (today included if done). */
    public int percent(Calendar today, int n) {
        Calendar c = (Calendar) today.clone();
        int due = 0, did = 0;
        for (int i = 0; i < n; i++) {
            boolean isToday = i == 0;
            if (onDay(c) && (created <= 0 || c.getTimeInMillis() >= created - 86_400_000L)) {
                if (doneOn(c)) { due++; did++; }
                else if (!isToday) due++;
            }
            c.add(Calendar.DAY_OF_MONTH, -1);
        }
        return due == 0 ? 0 : Math.round(100f * did / due);
    }

    public String daysText() {
        if (days == 127) return "Every day";
        if (days == 0b0111110) return "Weekdays";
        if (days == 0b1000001) return "Weekends";
        String[] n = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 7; i++) {
            int d = i % 7;
            if ((days & (1 << d)) != 0) sb.append(sb.length() > 0 ? ", " : "").append(n[d]);
        }
        return sb.toString();
    }

    private static int toInt(String s) { try { return Integer.parseInt(s); } catch (Exception e) { return 1; } }
}
