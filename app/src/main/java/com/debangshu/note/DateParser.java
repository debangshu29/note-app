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
 *   "gym every monday at 6 am", "pay rent on the 1st of every month", "drink water every 2 hours"  (repeating)
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
        /** "" = once. Otherwise see {@link #describe(String)}: D, WD, W2 (Monday), M15, Y, I120 (minutes). */
        public String repeat = "";
        /** Warranty / expiry: the day it ends (the reminder itself comes {@link #leadDays} earlier). 0 = not an expiry. */
        public long dueMillis = 0;
        public int leadDays = 0;

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

    private static final Pattern P_MONTH_YEAR = Pattern.compile("\\b" + MONTHS + "\\.?\\s*,?\\s*(\\d{4})\\b");
    private static final Pattern P_YEARLY_WORDS = Pattern.compile("\\b(birthday|bday|b'day|b-day|anniversary|janmadin|jonmodin|death anniversary|barsi)\\b");
    private static final Pattern P_EXPIRY = Pattern.compile("\\b(warranty|guarantee|expir(?:y|es|ed|ing)|exp|valid\\s+(?:till|until|upto|up to|thru|through)|"
            + "subscription ends|plan ends|ends on|best before|use by|due for renewal)\\b");

    // ---- repeating
    private static final String NUM_WORDS = "(\\d{1,3}|two|three|four|five|six|ten|twelve|fifteen|twenty|thirty)";
    private static final Pattern P_EVERY_N = Pattern.compile(
            "\\b(?:every|each)\\s+(?:" + NUM_WORDS + "\\s*)?(minutes?|mins?|hours?|hrs?|days?|weeks?)\\b");
    private static final Pattern P_DAILY = Pattern.compile(
            "\\b(?:every\\s*day|everyday|daily|each\\s+day)\\b|\\b(every|each)\\s+(?=(?:morning|afternoon|evening|night)\\b)");
    private static final Pattern P_WEEKDAYS = Pattern.compile(
            "\\b(?:every\\s+weekday|(?:on\\s+)?weekdays|every\\s+working\\s+day|mon(?:day)?\\s*(?:-|to)\\s*fri(?:day)?)\\b");
    private static final Pattern P_EVERY_WEEKDAY = Pattern.compile(
            "\\b(?:(?:every|each)\\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tues?|wed|thu(?:rs?)?|fri|sat|sun)"
            + "|(?:on\\s+)?(mondays|tuesdays|wednesdays|thursdays|fridays|saturdays|sundays))\\b");
    private static final Pattern P_WEEKLY = Pattern.compile("\\b(?:every\\s+week|weekly|each\\s+week)\\b");
    private static final Pattern P_MONTHLY_DAY = Pattern.compile(
            "\\b(?:on\\s+)?(?:the\\s+)?(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?(?:every|each)\\s+month\\b"
            + "|\\b(?:every|each)\\s+month\\s+(?:on\\s+)?(?:the\\s+)?(\\d{1,2})(?:st|nd|rd|th)?\\b"
            + "|\\bmonthly\\s+(?:on\\s+)?(?:the\\s+)?(\\d{1,2})(?:st|nd|rd|th)?\\b"
            + "|\\b(?:every|each)\\s+(\\d{1,2})(?:st|nd|rd|th)\\b(?!\\s*" + MONTHS + ")");
    private static final Pattern P_MONTHLY = Pattern.compile("\\b(?:every\\s+month|monthly|each\\s+month)\\b");
    private static final Pattern P_YEARLY = Pattern.compile("\\b(?:every\\s+year|yearly|annually|each\\s+year)\\b");

    private static final Pattern P_LEADING_FILLER = Pattern.compile(
            "^(?:\\s*(?:[-*•☐☑✓]|\\[\\s?\\]|\\[x\\]|todo:?|to do:?|reminder:?|remind me(?: to)?|remember to|"
            + "don'?t forget to|do not forget to|i need to|need to|i have to|have to|i must|must|i should|"
            + "i will|i'll|please|pls|plz)\\s*)+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern P_EDGE_WORDS = Pattern.compile(
            "^(?:(?:on|at|by|in|this|next|the|of|before|until|till|coming|for|and|every|each|,)\\s+)+|"
            + "(?:\\s+(?:on|at|by|in|this|next|the|of|before|until|till|coming|for|and|from|every|each))+\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** Convenience: uses the real clock and 9:00 as default time. */
    public static Result parse(String line) {
        return parse(line, Calendar.getInstance(), 9, 0);
    }

    public static Result parse(String line, Calendar now, int defaultHour, int defaultMinute) {
        if (line == null) return null;
        line = hinglish(line);
        line = billRepeat(line);
        String orig = line;
        if (orig.trim().length() < 3) return null;
        String low = orig.toLowerCase(Locale.ROOT);
        boolean mathLine = low.indexOf('=') >= 0;

        char[] masked = low.toCharArray();      // matched parts get blanked out
        boolean[] removed = new boolean[low.length()];

        Calendar date = null;     // the day (time part ignored)
        Integer hour = null, minute = null;
        boolean guessed = false;  // "at 7" made into 7 pm by guessing
        Long exact = null;        // "in 2 hours"
        boolean tonight = false;
        boolean explicitNoYear = false;

        // ---- repeating: "every 2 hours", "daily", "every monday", "1st of every month", "every year"
        String repeat = "";
        int weeklyDow = 0;
        Matcher m = P_EVERY_N.matcher(new String(masked));
        if (m.find()) {
            int n = m.group(1) == null ? 1 : wordNumber(m.group(1));
            String unit = m.group(2);
            int mins = unit.startsWith("m") ? n : unit.startsWith("h") ? n * 60 : unit.startsWith("d") ? n * 1440 : n * 10080;
            if (n > 0 && mins >= 5) {
                if (unit.startsWith("d") && n == 1) repeat = "D";
                else if (unit.startsWith("w") && n == 1) repeat = "W";
                else repeat = "I" + mins;
                if (mins < 1440) {
                    Calendar c = (Calendar) now.clone();
                    c.set(Calendar.SECOND, 0);
                    c.set(Calendar.MILLISECOND, 0);
                    c.add(Calendar.MINUTE, mins);
                    exact = c.getTimeInMillis();
                } else {
                    date = dayOffset(now, 0);
                }
                mask(masked, removed, m.start(), m.end());
            }
        }
        if (repeat.isEmpty() && (m = P_WEEKDAYS.matcher(new String(masked))).find()) {
            repeat = "WD";
            date = dayOffset(now, 0);
            mask(masked, removed, m.start(), m.end());
        }
        if (repeat.isEmpty() && (m = P_EVERY_WEEKDAY.matcher(new String(masked))).find()) {
            String day = m.group(1) != null ? m.group(1) : m.group(2);
            weeklyDow = weekdayIndex(day);
            repeat = "W" + weeklyDow;
            date = nextWeekday(now, weeklyDow, false);
            mask(masked, removed, m.start(), m.end());
        }
        if (repeat.isEmpty() && (m = P_DAILY.matcher(new String(masked))).find()) {
            repeat = "D";
            if (m.group(1) != null) mask(masked, removed, m.start(1), m.end(1));   // keep "morning" for the time
            else mask(masked, removed, m.start(), m.end());
        }
        if (repeat.isEmpty() && (m = P_MONTHLY_DAY.matcher(new String(masked))).find()) {
            int day = -1;
            for (int g = 1; g <= 4 && day < 0; g++) if (m.group(g) != null) day = toInt(m.group(g));
            if (day >= 1 && day <= 31) {
                repeat = "M" + day;
                date = monthDay(now, day, 0);
                mask(masked, removed, m.start(), m.end());
            }
        }
        if (repeat.isEmpty() && (m = P_MONTHLY.matcher(new String(masked))).find()) {
            repeat = "M";        // day decided below (a date in the line, or today)
            mask(masked, removed, m.start(), m.end());
        }
        if (repeat.isEmpty() && (m = P_YEARLY.matcher(new String(masked))).find()) {
            repeat = "Y";
            mask(masked, removed, m.start(), m.end());
        }
        if (repeat.isEmpty() && (m = P_WEEKLY.matcher(new String(masked))).find()) {
            repeat = "W";        // weekday decided below
            mask(masked, removed, m.start(), m.end());
        }

        // ---- "in 2 hours" / "in 3 days"
        m = P_IN.matcher(new String(masked));
        if (exact == null && date == null && m.find()) {
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

        // ---- "march 2027" / "till mar 2027": the last day of that month
        if (date == null && exact == null && (m = P_MONTH_YEAR.matcher(new String(masked))).find()) {
            Calendar c = dayOffset(now, 0);
            c.set(Calendar.DAY_OF_MONTH, 1);
            c.set(Calendar.YEAR, toInt(m.group(2)));
            c.set(Calendar.MONTH, monthIndex(m.group(1)));
            c.set(Calendar.DAY_OF_MONTH, c.getActualMaximum(Calendar.DAY_OF_MONTH));
            date = c;
            mask(masked, removed, m.start(), m.end());
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
                    hour = guessHour(h); minute = 0; guessed = true;
                    mask(masked, removed, m.start(), m.end());
                }
            }
            if (hour == null && (m = P_TIME_AT.matcher(new String(masked))).find()) {
                int h = toInt(m.group(1));
                if (h >= 0 && h <= 23) {
                    hour = h <= 12 ? guessHour(h) : h; minute = 0; guessed = h <= 12;
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
                } else if (guessed && hour > 12 && part.equals("morning")) {
                    hour -= 12; // "morning at 7" is 7 am, not the guessed 7 pm
                }
                // only treat a part of day as a reminder hint if there is a date or time word too
                if (date != null || hour != null) mask(masked, removed, m.start(), m.end());
            }
        }

        if (date == null && hour == null && exact == null) {
            if (repeat.isEmpty()) return null;
            date = dayOffset(now, 0);                    // "every month", "daily"… without a day: from today
        }
        // birthdays and anniversaries come back every year
        if (repeat.isEmpty() && exact == null && date != null && P_YEARLY_WORDS.matcher(low).find()) repeat = "Y";
        boolean expiry = repeat.isEmpty() && exact == null && date != null && hour == null && P_EXPIRY.matcher(low).find();
        if (expiry) explicitNoYear = false;
        if (repeat.equals("M")) repeat = "M" + (date != null ? date.get(Calendar.DAY_OF_MONTH) : now.get(Calendar.DAY_OF_MONTH));
        if (repeat.equals("W")) repeat = "W" + (date != null ? date.get(Calendar.DAY_OF_WEEK) : now.get(Calendar.DAY_OF_WEEK));

        if (tonight && hour != null && hour >= 5 && hour < 12) hour += 12;   // "tonight at 10" = 10 pm

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

            if (!repeat.isEmpty()) {
                if (repeat.equals("WD")) {
                    while (isWeekend(when)) when.add(Calendar.DAY_OF_MONTH, 1);
                }
                if (repeat.startsWith("I")) {
                    while (!when.after(now)) when.add(Calendar.DAY_OF_MONTH, 1);   // "every 3 days at 7 am"
                }
                if (!when.after(now)) when.setTimeInMillis(nextAfter(when.getTimeInMillis(), repeat, now.getTimeInMillis()));
            } else if (when.before(now)) {
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
        r.repeat = repeat;
        if (expiry) {
            // warranty / passport / insurance: a week ahead; food and short things: the day before
            long due = when.getTimeInMillis();
            long days = (due - now.getTimeInMillis()) / 86_400_000L;
            int lead = days > 30 ? 7 : days >= 2 ? 1 : 0;
            Calendar remind = (Calendar) when.clone();
            remind.add(Calendar.DAY_OF_MONTH, -lead);
            if (!hasTime) { remind.set(Calendar.HOUR_OF_DAY, defaultHour); remind.set(Calendar.MINUTE, defaultMinute); }
            if (!remind.after(now)) { remind = (Calendar) when.clone(); lead = 0; }
            if (!remind.after(now)) return null;
            r.dueMillis = due;
            r.leadDays = lead;
            r.timeMillis = remind.getTimeInMillis();
            String t = r.title.replaceAll("(?i)\\s*\\b(?:expires?|expiring|expiry|exp|valid(?:\\s+(?:till|until|upto|up to))?|ends?(?:\\s+on)?|till|until|on|by)\\s*$", "").trim();
            if (t.isEmpty()) t = r.title;
            r.title = t + " — ends " + String.format(Locale.ENGLISH, "%te %<tb %<tY", when);
        }
        return r;
    }

    // ------------------------------------------------------------ Hinglish / Bengali (Roman letters)

    private static final String[][] HI_DAYS = {
            {"somvaar|somvar|somwar|sombar|shombar", "monday"}, {"mangalvaar|mangalvar|mangalwar|mangalbar|mongolbar", "tuesday"},
            {"budhvaar|budhvar|budhwar|budhbar|budbar", "wednesday"}, {"guruvaar|guruvar|guruwar|brihaspativar|brihaspatibar|brihospotibar|bisudbar", "thursday"},
            {"shukravaar|shukravar|shukrawar|shukrabar|shukrobar", "friday"}, {"shanivaar|shanivar|shaniwar|shanibar|shonibar", "saturday"},
            {"ravivaar|ravivar|raviwar|itvaar|itwar|rabibar|robibar", "sunday"}};


    private static final Pattern P_BILL_DAY = Pattern.compile(
            "(?i)\\b(?:on|due(?:\\s+on)?|by|before|every)\\s+(?:the\\s+)?(\\d{1,2})(?:st|nd|rd|th)?\\b(?!\\s*(?:of\\s+)?" + MONTHS + ")(?!\\s*(?:am|pm|a\\.m|p\\.m|:|\\.\\d))");

    /**
     * Bills and subscriptions: "Netflix ₹649/month on 5th" → "Netflix ₹649 every month on the 5th",
     * "LIC 24000 per year due 10 Mar" → "... due 10 Mar every year". Without a day it is not a reminder.
     */
    static String billRepeat(String line) {
        String[] mk = Money.repeatMarker(line);
        if (mk == null) return line;
        String rest = mk[0];
        if (mk[1].equals("month")) {
            Matcher d = P_BILL_DAY.matcher(rest);
            if (d.find()) return rest.substring(0, d.start()) + " every month on the " + d.group(1) + "th" + rest.substring(d.end());
        }
        Result r = parse(rest);
        if (r == null || !r.repeat.isEmpty()) return rest;
        return rest + (mk[1].equals("month") ? " every month" : mk[1].equals("year") ? " every year" : " every week");
    }

    /** Turns Hinglish / Bengali time words into English ones the rest of the parser knows. */
    static String hinglish(String line) {
        String l = line;
        if (!l.matches("(?is).*\\b(kal|parso\\w*|porshu|aaj|aj|baje|bajey|subah|subha|savere|sokal|dopahar|dupur|bikel|shaam|sham|sondhya|sandhya|raat|"
                + "har|roz|rozana|agle|agli|hafte|mahine|ghante|ghanta|din|tarikh|ta|minute|"
                + "somvaar|somvar|somwar|sombar|mangal\\w*|budh\\w*|guru\\w*|brihas\\w*|shukr\\w*|shani\\w*|ravi\\w*|itvaar|itwar|rabibar|robibar|"
                + "saade|sade|sawa|paune|dedh|dhai)\\b.*")) return line;
        String f = "(?i)";
        // "kal" means yesterday too: only read it as tomorrow when the line isn't about the past
        boolean past = l.matches("(?is).*\\b(tha|thi|the|gaya|gayi|gaye|kiya|kari|hua|hui|chilo|chhilo|gechilo|korechi)\\b.*");
        l = l.replaceAll(f + "\\b(?:parso|parson|parsoon|porshu|poshu)\\b", "day after tomorrow");
        if (!past) l = l.replaceAll(f + "\\bkal\\b", "tomorrow");
        l = l.replaceAll(f + "\\baaj\\s+raat\\b", "tonight").replaceAll(f + "\\b(?:aaj|aj)\\b", "today");
        // half / quarter hours: saade 5 = 5:30, sawa 5 = 5:15, paune 6 = 5:45, dedh = 1:30, dhai = 2:30
        l = l.replaceAll(f + "\\b(?:saade|sade|saare)\\s*(\\d{1,2})\\b", "$1:30");
        l = l.replaceAll(f + "\\b(?:sawa|sava)\\s*(\\d{1,2})\\b", "$1:15");
        java.util.regex.Matcher pm = Pattern.compile(f + "\\b(?:paune|pone)\\s*(\\d{1,2})\\b").matcher(l);
        StringBuffer sb = new StringBuffer();
        while (pm.find()) {
            int h = Integer.parseInt(pm.group(1));
            pm.appendReplacement(sb, (h <= 1 ? 12 : h - 1) + ":45");
        }
        pm.appendTail(sb);
        l = sb.toString();
        l = l.replaceAll(f + "\\bdedh\\b", "1:30").replaceAll(f + "\\bdhai\\b", "2:30");
        // "4:30 baje" has no am/pm: morning words → am, evening words or 1–7 o'clock → pm
        boolean morningWord = l.matches("(?is).*\\b(subah|subha|subhe|savere|sawere|sokal|shokal|morning)\\b.*");
        boolean eveningWord = l.matches("(?is).*\\b(dopahar|dopehar|dupur|bikel|shaam|sham|sandhya|sondhya|raat|raate|tonight)\\b.*");
        java.util.regex.Matcher hm = Pattern.compile(f + "\\b(\\d{1,2}):(\\d{2})\\s*(?:baje|bajey|bje|tay|tai)\\b").matcher(l);
        sb = new StringBuffer();
        while (hm.find()) {
            int h = Integer.parseInt(hm.group(1));
            String ap = h > 12 ? "" : morningWord ? " am" : (eveningWord || (h >= 1 && h <= 7)) ? " pm" : " am";
            hm.appendReplacement(sb, "at " + h + ":" + hm.group(2) + ap);
        }
        hm.appendTail(sb);
        l = sb.toString();
        // "5 baje", "5:30 baje", "5 tay" (Bengali) → "at 5"
        l = l.replaceAll(f + "\\b(\\d{1,2}(?::\\d{2})?)\\s*(?:baje|bajey|bje|tay|tai|tar\\s+somoy)\\b", "at $1");
        // parts of the day
        l = l.replaceAll(f + "\\b(?:subah|subha|subhe|savere|sawere|sokal|shokal)\\b", "morning");
        l = l.replaceAll(f + "\\b(?:dopahar|dopehar|dupahar|dupur|bikel|bikal)\\b", "afternoon");
        l = l.replaceAll(f + "\\b(?:shaam|sham|shyam|sandhya|sondhya|sondha)\\b", "evening");
        l = l.replaceAll(f + "\\b(?:raat|rat\\s+ko|raate)\\b", "night");
        // repeats
        l = l.replaceAll(f + "\\b(?:har\\s+roz|har\\s+din|rozana|roz|protidin|roj)\\b", "every day");
        l = l.replaceAll(f + "\\bhar\\s+(\\d{1,3})\\s*(?:ghante|ghanta)\\b", "every $1 hours");
        l = l.replaceAll(f + "\\bhar\\s+(?:ghante|ghanta)\\b", "every hour");
        l = l.replaceAll(f + "\\bhar\\s+(?:mahine|mahina|maas|mash)\\b", "every month");
        l = l.replaceAll(f + "\\bhar\\s+(?:saal|sal|bochor|bochhor)\\b", "every year");
        l = l.replaceAll(f + "\\bhar\\s+(?:hafte|hafta)\\b", "every week");
        // "2 ghante mein", "10 minute baad", "3 din mein"
        l = l.replaceAll(f + "\\b(\\d{1,3})\\s*(?:ghante|ghanta)\\s*(?:mein|me|main|baad|bad|pore|por)\\b", "in $1 hours");
        l = l.replaceAll(f + "\\b(\\d{1,3})\\s*(?:minute|minat|mint|min)\\s*(?:mein|me|main|baad|bad|pore|por)\\b", "in $1 minutes");
        l = l.replaceAll(f + "\\b(\\d{1,3})\\s*(?:din|dino)\\s*(?:mein|me|main|baad|bad|pore|por)\\b", "in $1 days");
        l = l.replaceAll(f + "\\baadhe\\s+ghante\\s*(?:mein|me|baad)\\b", "in 30 minutes");
        // next week / month
        l = l.replaceAll(f + "\\b(?:agle|agli)\\s+(?:hafte|week)\\b", "next week");
        l = l.replaceAll(f + "\\b(?:agle|agli)\\s+(?:mahine|month)\\b", "next month");
        l = l.replaceAll(f + "\\b(?:agle|agli|porer)\\s+", "next ");
        // weekdays, and "har somvar" = every monday
        for (String[] d : HI_DAYS) l = l.replaceAll(f + "\\b(?:" + d[0] + ")\\b", d[1]);
        l = l.replaceAll(f + "\\bhar\\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b", "every $1");
        // "15 tarikh" = the 15th
        l = l.replaceAll(f + "\\b(\\d{1,2})\\s*tarikh\\b", "$1th");
        // "ko" right after a time word reads like English "on"/"at"
        l = l.replaceAll(f + "\\b(tomorrow|today|tonight|morning|afternoon|evening|night|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\s+ko\\b", "$1");
        return l;
    }

    // ------------------------------------------------------------ repeating

    /** First time of the series that is later than {@code now} (stepping from {@code base}). */
    public static long nextAfter(long base, String repeat, long now) {
        if (repeat == null || repeat.isEmpty()) return base;
        if (repeat.startsWith("I")) {
            long step = Math.max(5, toInt(repeat.substring(1))) * 60_000L;
            if (base > now) return base;
            long k = (now - base) / step + 1;
            return base + k * step;
        }
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(base);
        int target = c.get(Calendar.DAY_OF_MONTH);
        if (repeat.startsWith("M") && repeat.length() > 1) target = toInt(repeat.substring(1));
        int guard = 0;
        while (c.getTimeInMillis() <= now && guard++ < 5000) step(c, repeat, target);
        return c.getTimeInMillis();
    }

    private static void step(Calendar c, String repeat, int monthDay) {
        if (repeat.equals("D")) c.add(Calendar.DAY_OF_MONTH, 1);
        else if (repeat.equals("WD")) {
            do { c.add(Calendar.DAY_OF_MONTH, 1); } while (isWeekend(c));
        } else if (repeat.startsWith("W")) c.add(Calendar.DAY_OF_MONTH, 7);
        else if (repeat.startsWith("M")) {
            c.set(Calendar.DAY_OF_MONTH, 1);
            c.add(Calendar.MONTH, 1);
            c.set(Calendar.DAY_OF_MONTH, Math.min(monthDay, c.getActualMaximum(Calendar.DAY_OF_MONTH)));
        } else if (repeat.equals("Y")) c.add(Calendar.YEAR, 1);
        else c.add(Calendar.DAY_OF_MONTH, 1);
    }

    private static boolean isWeekend(Calendar c) {
        int d = c.get(Calendar.DAY_OF_WEEK);
        return d == Calendar.SATURDAY || d == Calendar.SUNDAY;
    }

    /** This month's day {@code day} (or the last day), or next month's if it has passed. */
    private static Calendar monthDay(Calendar now, int day, int unused) {
        Calendar c = dayOffset(now, 0);
        c.set(Calendar.DAY_OF_MONTH, Math.min(day, c.getActualMaximum(Calendar.DAY_OF_MONTH)));
        return c;
    }

    /** "Every day", "Every Monday", "Every month on the 5th"… */
    public static String describe(String repeat) {
        if (repeat == null || repeat.isEmpty()) return "";
        if (repeat.equals("D")) return "Every day";
        if (repeat.equals("WD")) return "Every weekday";
        if (repeat.equals("Y")) return "Every year";
        if (repeat.startsWith("W")) {
            String[] names = {"", "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
            int d = toInt(repeat.substring(1));
            return d >= 1 && d <= 7 ? "Every " + names[d] : "Every week";
        }
        if (repeat.startsWith("M")) {
            int d = toInt(repeat.substring(1));
            return "Every month on the " + d + ordinal(d);
        }
        if (repeat.startsWith("I")) {
            int mins = toInt(repeat.substring(1));
            if (mins % 10080 == 0) return mins == 10080 ? "Every week" : "Every " + mins / 10080 + " weeks";
            if (mins % 1440 == 0) return mins == 1440 ? "Every day" : "Every " + mins / 1440 + " days";
            if (mins % 60 == 0) return mins == 60 ? "Every hour" : "Every " + mins / 60 + " hours";
            return "Every " + mins + " minutes";
        }
        return "Repeats";
    }

    private static String ordinal(int d) {
        if (d % 100 >= 11 && d % 100 <= 13) return "th";
        switch (d % 10) { case 1: return "st"; case 2: return "nd"; case 3: return "rd"; default: return "th"; }
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
        // Hinglish fillers: "… yaad dila dena", "… karna hai", "mujhe …"
        s = s.replaceAll("(?i)\\b(?:yaad\\s+(?:dila|kara)\\w*(?:\\s+(?:dena|do|diyo|dijiye|na))?|remind\\s+kar\\w*(?:\\s+(?:dena|do))?|mujhe|mujhko)\\b", " ")
                .replaceAll("[ \\t]{2,}", " ").trim();
        String before;
        do { before = s; s = s.replaceAll("(?i)\\s+(?:ko|hai|hain|karna|karni|karo|dena|hoga|ke liye|ki|ka|ke|na|due on|due)$", "").trim(); } while (!s.equals(before));
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
            case "six": return 6;
            case "ten": return 10;
            case "twelve": return 12;
            case "fifteen": return 15;
            case "twenty": return 20;
            case "thirty": return 30;
        }
        if (s.startsWith("half")) return 0;
        return toInt(s);
    }
}
