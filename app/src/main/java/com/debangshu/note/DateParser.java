package com.debangshu.note;

import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a date / time inside one line of a note, e.g.
 *   "call mom tomorrow at 5pm"         -> tomorrow 17:00, title "Call mom"
 *   "submit form on 15 nov"            -> 15 Nov 09:00,  title "Submit form"
 *   "gym next monday 6:30 am"          -> next Monday 06:30
 *   "pay rent 01/12"                   -> 1 Dec (day/month, Indian style)
 *   "remind me to drink water in 2 hours"
 *
 * Returns null if the line has no date/time, or the time is already over.
 * No Android imports here, so it can be tested on a normal JVM.
 */
public final class DateParser {

    private DateParser() {}

    public static final class Result {
        public long timeMillis;
        public boolean hasTime;
        /** true for "in 20 minutes" / "in 2 hours" style times */
        public boolean relative;
        public String title;

        @Override public String toString() {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(timeMillis);
            return String.format(Locale.ROOT, "%tF %tR  \"%s\"", c, c, title);
        }
    }

    private static final String MONTHS =
            "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|"
            + "sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)";

    private static final Pattern P_IN = Pattern.compile(
            "\\b(?:in|after)\\s+(\\d{1,3}|an?|one|two|three|four|five|ten|half an?)\\s*"
            + "(minutes?|mins?|m|hours?|hrs?|h|days?|weeks?)\\b");
    private static final Pattern P_DAY_AFTER = Pattern.compile("\\b(?:day after tomorrow|overmorrow)\\b");
    private static final Pattern P_TOMORROW = Pattern.compile("\\b(?:tomorrow|tomorow|tommorow|tommorrow|tmrw|tmr|tmrrw)\\b");
    private static final Pattern P_TODAY = Pattern.compile("\\b(?:today|tonight|tonite)\\b");
    private static final Pattern P_NEXT_WEEK = Pattern.compile("\\bnext\\s+week\\b");
    private static final Pattern P_NEXT_MONTH = Pattern.compile("\\bnext\\s+month\\b");
    private static final Pattern P_WEEKEND = Pattern.compile("\\b(?:this\\s+|next\\s+|on\\s+the\\s+)?weekend\\b");
    private static final Pattern P_WEEKDAY = Pattern.compile(
            "\\b(?:(next|this|on|coming|by|every)\\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday|"
            + "mon|tues?|wed|thu(?:rs?)?|fri|sat|sun)\\b");
    private static final Pattern P_DAY_MONTH = Pattern.compile(
            "\\b(\\d{1,2})(?:st|nd|rd|th)?\\s*(?:of\\s+)?" + MONTHS + "\\b(?:,?\\s*(\\d{4}))?");
    private static final Pattern P_MONTH_DAY = Pattern.compile(
            "\\b" + MONTHS + "\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b(?:,?\\s*(\\d{4}))?");
    private static final Pattern P_ISO = Pattern.compile("\\b(\\d{4})-(\\d{1,2})-(\\d{1,2})\\b");
    private static final Pattern P_NUMERIC = Pattern.compile(
            "\\b(\\d{1,2})[/\\-](\\d{1,2})(?:[/\\-](\\d{2}|\\d{4}))?\\b");
    private static final Pattern P_NUMERIC_DOT = Pattern.compile("\\b(\\d{1,2})\\.(\\d{1,2})\\.(\\d{2}|\\d{4})\\b");

    private static final Pattern P_TIME_AMPM = Pattern.compile(
            "(?:\\b(?:at|@|by|around|before)\\s*)?\\b(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|a\\.m\\.?|p\\.m\\.?)(?![a-z])");
    private static final Pattern P_TIME_24 = Pattern.compile(
            "(?:\\b(?:at|@|by|around|before)\\s*)?\\b([01]?\\d|2[0-3])[:.]([0-5]\\d)\\b(?:\\s*(?:hrs|hours|h)\\b)?");
    private static final Pattern P_TIME_OCLOCK = Pattern.compile(
            "(?:\\b(?:at|@|by|around)\\s*)?\\b(\\d{1,2})\\s*o'?\\s?clock\\b");
    private static final Pattern P_TIME_AT = Pattern.compile("\\b(?:at|@)\\s*(\\d{1,2})\\b(?![:./\\-]\\d)");
    private static final Pattern P_PART_OF_DAY = Pattern.compile(
            "\\b(?:in the\\s+|this\\s+|at\\s+)?(morning|noon|midday|afternoon|evening|night|midnight)\\b");

    private static final Pattern P_LEADING_FILLER = Pattern.compile(
            "^(?:\\s*(?:[-*•☐☑✓]|\\[\\s?\\]|\\[x\\]|todo:?|to do:?|reminder:?|remind me(?: to)?|remember to|"
            + "don'?t forget to|do not forget to|i need to|need to|i have to|have to|i must|must|i should|"
            + "i will|i'll|please|pls|plz)\\s*)+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern P_EDGE_WORDS = Pattern.compile(
            "^(?:(?:on|at|by|in|this|next|the|of|before|until|till|coming|for|and|,)\\s+)+|"
            + "(?:\\s+(?:on|at|by|in|this|next|the|of|before|until|till|coming|for|and|from))+\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** Convenience: uses the real clock and 9:00 as default time. */
    public static Result parse(String line) {
        return parse(line, Calendar.getInstance(), 9, 0);
    }

    public static Result parse(String line, Calendar now, int defaultHour, int defaultMinute) {
        if (line == null) return null;
        String orig = line;
        if (orig.trim().length() < 3) return null;
        String low = orig.toLowerCase(Locale.ROOT);
        boolean mathLine = low.indexOf('=') >= 0;

        char[] masked = low.toCharArray();      // matched parts get blanked out
        boolean[] removed = new boolean[low.length()];

        Calendar date = null;     // the day (time part ignored)
        Integer hour = null, minute = null;
        Long exact = null;        // "in 2 hours"
        boolean tonight = false;
        boolean explicitNoYear = false;

        // ---- "in 2 hours" / "in 3 days"
        Matcher m = P_IN.matcher(new String(masked));
        if (m.find()) {
            int n = wordNumber(m.group(1));
            String unit = m.group(2);
            if (n > 0 || m.group(1).startsWith("half")) {
                Calendar c = (Calendar) now.clone();
                c.set(Calendar.SECOND, 0);
                c.set(Calendar.MILLISECOND, 0);
                if (unit.startsWith("m")) { c.add(Calendar.MINUTE, n); exact = c.getTimeInMillis(); }
                else if (unit.startsWith("h")) {
                    if (m.group(1).startsWith("half")) c.add(Calendar.MINUTE, 30); else c.add(Calendar.HOUR_OF_DAY, n);
                    exact = c.getTimeInMillis();
                } else if (unit.startsWith("d")) { c.add(Calendar.DAY_OF_MONTH, n); date = c; }
                else if (unit.startsWith("w")) { c.add(Calendar.DAY_OF_MONTH, 7 * n); date = c; }
                mask(masked, removed, m.start(), m.end());
            }
        }

        // ---- relative words
        if (date == null && exact == null) {
            if ((m = P_DAY_AFTER.matcher(new String(masked))).find()) {
                date = dayOffset(now, 2); mask(masked, removed, m.start(), m.end());
            } else if ((m = P_TOMORROW.matcher(new String(masked))).find()) {
                date = dayOffset(now, 1); mask(masked, removed, m.start(), m.end());
            } else if ((m = P_TODAY.matcher(new String(masked))).find()) {
                date = dayOffset(now, 0);
                tonight = m.group().startsWith("toni");
                mask(masked, removed, m.start(), m.end());
            } else if ((m = P_NEXT_WEEK.matcher(new String(masked))).find()) {
                date = dayOffset(now, 7); mask(masked, removed, m.start(), m.end());
            } else if ((m = P_NEXT_MONTH.matcher(new String(masked))).find()) {
                date = dayOffset(now, 0); date.add(Calendar.MONTH, 1); mask(masked, removed, m.start(), m.end());
            } else if ((m = P_WEEKEND.matcher(new String(masked))).find()) {
                date = nextWeekday(now, Calendar.SATURDAY, m.group().startsWith("next"));
                mask(masked, removed, m.start(), m.end());
            }
        }

        // ---- explicit dates (15 nov, nov 15, 2026-11-15, 15/11)
        if (date == null && exact == null) {
            int[] ymd = null;
            if ((m = P_DAY_MONTH.matcher(new String(masked))).find()) {
                ymd = new int[]{yearOrMinus(m.group(3)), monthIndex(m.group(2)), toInt(m.group(1))};
            } else if ((m = P_MONTH_DAY.matcher(new String(masked))).find()) {
                ymd = new int[]{yearOrMinus(m.group(3)), monthIndex(m.group(1)), toInt(m.group(2))};
            } else if ((m = P_ISO.matcher(new String(masked))).find() && !mathLine) {
                ymd = new int[]{toInt(m.group(1)), toInt(m.group(2)) - 1, toInt(m.group(3))};
            } else if ((m = P_NUMERIC_DOT.matcher(new String(masked))).find() && !mathLine) {
                ymd = new int[]{fixYear(m.group(3)), toInt(m.group(2)) - 1, toInt(m.group(1))};
            } else if ((m = P_NUMERIC.matcher(new String(masked))).find() && !mathLine) {
                ymd = new int[]{m.group(3) == null ? -1 : fixYear(m.group(3)),
                        toInt(m.group(2)) - 1, toInt(m.group(1))};
            }
            if (ymd != null && validDay(ymd)) {
                Calendar c = dayOffset(now, 0);
                c.set(Calendar.DAY_OF_MONTH, 1);
                if (ymd[0] > 0) c.set(Calendar.YEAR, ymd[0]);
                c.set(Calendar.MONTH, ymd[1]);
                if (ymd[2] <= c.getActualMaximum(Calendar.DAY_OF_MONTH)) {
                    c.set(Calendar.DAY_OF_MONTH, ymd[2]);
                    date = c;
                    explicitNoYear = ymd[0] <= 0;
                    mask(masked, removed, m.start(), m.end());
                }
            }
        }

        // ---- weekday names
        if (date == null && exact == null) {
            m = P_WEEKDAY.matcher(new String(masked));
            while (m.find()) {
                String prefix = m.group(1);
                String day = m.group(2);
                boolean shortForm = day.length() <= 5 && !day.endsWith("day");
                if (shortForm && prefix == null) continue; // "sat", "sun", "wed" are too ambiguous alone
                date = nextWeekday(now, weekdayIndex(day), "next".equals(prefix));
                mask(masked, removed, m.start(), m.end());
                break;
            }
        }

        // ---- time of day
        if (exact == null) {
            if ((m = P_TIME_AMPM.matcher(new String(masked))).find()) {
                int h = toInt(m.group(1));
                int min = m.group(2) == null ? 0 : toInt(m.group(2));
                if (h >= 1 && h <= 12 && min < 60) {
                    boolean pm = m.group(3).startsWith("p");
                    if (h == 12) h = pm ? 12 : 0; else if (pm) h += 12;
                    hour = h; minute = min;
                    mask(masked, removed, m.start(), m.end());
                }
            }
            if (hour == null && (m = P_TIME_24.matcher(new String(masked))).find()) {
                hour = toInt(m.group(1)); minute = toInt(m.group(2));
                mask(masked, removed, m.start(), m.end());
            }
            if (hour == null && (m = P_TIME_OCLOCK.matcher(new String(masked))).find()) {
                int h = toInt(m.group(1));
                if (h >= 1 && h <= 12) {
                    hour = guessHour(h); minute = 0;
                    mask(masked, removed, m.start(), m.end());
                }
            }
            if (hour == null && (m = P_TIME_AT.matcher(new String(masked))).find()) {
                int h = toInt(m.group(1));
                if (h >= 0 && h <= 23) {
                    hour = h <= 12 ? guessHour(h) : h; minute = 0;
                    mask(masked, removed, m.start(), m.end());
                }
            }
            if ((m = P_PART_OF_DAY.matcher(new String(masked))).find()) {
                String part = m.group(1);
                if (hour == null) {
                    switch (part) {
                        case "morning": hour = 9; break;
                        case "noon": case "midday": hour = 12; break;
                        case "afternoon": hour = 14; break;
                        case "evening": hour = 18; break;
                        case "night": hour = 21; break;
                        case "midnight": hour = 23; minute = 59; break;
                    }
                    if (minute == null) minute = 0;
                } else if (hour < 12 && (part.equals("evening") || part.equals("night") || part.equals("afternoon"))) {
                    hour += 12; // "7 in the evening" style
                }
                // only treat a part of day as a reminder hint if there is a date or time word too
                if (date != null || hour != null) mask(masked, removed, m.start(), m.end());
            }
        }

        if (date == null && hour == null && exact == null) return null;

        // ---- build the final time
        Calendar when;
        boolean hasTime = true;
        if (exact != null) {
            when = Calendar.getInstance();
            when.setTimeInMillis(exact);
        } else {
            boolean timeOnly = date == null;
            when = date != null ? (Calendar) date.clone() : dayOffset(now, 0);
            if (hour == null) {
                hasTime = false;
                if (tonight) { hour = 20; minute = 0; }
                else { hour = defaultHour; minute = defaultMinute; }
            }
            when.set(Calendar.HOUR_OF_DAY, hour);
            when.set(Calendar.MINUTE, minute == null ? 0 : minute);
            when.set(Calendar.SECOND, 0);
            when.set(Calendar.MILLISECOND, 0);

            if (when.before(now)) {
                if (timeOnly) {
                    when.add(Calendar.DAY_OF_MONTH, 1);          // "at 5pm" when it's 6pm -> tomorrow
                } else if (explicitNoYear) {
                    Calendar sixMonthsAgo = (Calendar) now.clone();
                    sixMonthsAgo.add(Calendar.MONTH, -6);
                    if (when.before(sixMonthsAgo)) when.add(Calendar.YEAR, 1); // "5 jan" in november
                    else return null;                              // a date that just passed
                } else if (!hasTime && sameDay(when, now)) {
                    // "today" without a time and the default hour is gone: remind in 1 hour
                    when = (Calendar) now.clone();
                    when.add(Calendar.HOUR_OF_DAY, 1);
                    when.set(Calendar.SECOND, 0);
                    when.set(Calendar.MILLISECOND, 0);
                } else {
                    return null;
                }
            }
        }

        Result r = new Result();
        r.timeMillis = when.getTimeInMillis();
        r.hasTime = hasTime;
        r.relative = exact != null;
        r.title = cleanTitle(orig, removed);
        return r;
    }

    // ------------------------------------------------------------ helpers

    private static void mask(char[] masked, boolean[] removed, int start, int end) {
        for (int i = start; i < end; i++) { masked[i] = ' '; removed[i] = true; }
    }

    private static String cleanTitle(String orig, boolean[] removed) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < orig.length(); i++) sb.append(removed[i] ? ' ' : orig.charAt(i));
        String s = sb.toString();
        // remove little words that were only there for the date ("on", "at", "by" ...)
        s = s.replaceAll("(?i)\\b(on|at|by|this|next|the|of|before|until|till|coming|around)\\s+(?=(\\s|$))", " ");
        s = s.replaceAll("[ \\t]{2,}", " ").trim();
        s = P_LEADING_FILLER.matcher(s).replaceFirst("");
        String prev;
        do { prev = s; s = P_EDGE_WORDS.matcher(s).replaceAll("").trim(); } while (!s.equals(prev));
        s = s.replaceAll("\\s+([,.!?])", "$1").replaceAll("^[,.;:\\-\\s]+|[,;:\\-\\s]+$", "").trim();
        if (s.isEmpty()) s = "Reminder";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static Calendar dayOffset(Calendar now, int days) {
        Calendar c = (Calendar) now.clone();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DAY_OF_MONTH, days);
        return c;
    }

    private static Calendar nextWeekday(Calendar now, int targetDow, boolean forceNextWeek) {
        Calendar c = dayOffset(now, 0);
        int diff = (targetDow - c.get(Calendar.DAY_OF_WEEK) + 7) % 7;
        if (diff == 0 && forceNextWeek) diff = 7;
        c.add(Calendar.DAY_OF_MONTH, diff);
        return c;
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    /** "at 5" without am/pm: 1..7 is probably evening, 8..12 is morning/noon. */
    private static int guessHour(int h) {
        if (h == 12) return 12;
        if (h >= 1 && h <= 7) return h + 12;
        return h;
    }

    private static int weekdayIndex(String d) {
        if (d.startsWith("mon")) return Calendar.MONDAY;
        if (d.startsWith("tu")) return Calendar.TUESDAY;
        if (d.startsWith("we")) return Calendar.WEDNESDAY;
        if (d.startsWith("th")) return Calendar.THURSDAY;
        if (d.startsWith("fr")) return Calendar.FRIDAY;
        if (d.startsWith("sa")) return Calendar.SATURDAY;
        return Calendar.SUNDAY;
    }

    private static int monthIndex(String m) {
        String[] names = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};
        for (int i = 0; i < 12; i++) if (m.startsWith(names[i])) return i;
        return -1;
    }

    private static boolean validDay(int[] ymd) {
        return ymd[1] >= 0 && ymd[1] <= 11 && ymd[2] >= 1 && ymd[2] <= 31
                && (ymd[0] <= 0 || (ymd[0] >= 2000 && ymd[0] <= 2100));
    }

    private static int yearOrMinus(String s) { return s == null ? -1 : toInt(s); }

    private static int fixYear(String s) {
        int y = toInt(s);
        return y < 100 ? 2000 + y : y;
    }

    private static int toInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return -1; }
    }

    private static int wordNumber(String s) {
        switch (s) {
            case "a": case "an": case "one": return 1;
            case "two": return 2;
            case "three": return 3;
            case "four": return 4;
            case "five": return 5;
            case "ten": return 10;
        }
        if (s.startsWith("half")) return 0;
        return toInt(s);
    }
}
