package com.debangshu.note;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides HOW to remind, based on what the note says, so the user is never
 * pinged by three apps for one thing.
 *
 *   ALARM   "wake me up at 10 am tomorrow", "alarm 6:30"   -> rings like a clock alarm, NOT added to calendar
 *   EVENT   "call mom at 5 pm on 12 Nov", "exam on 5 Jan" -> phone calendar entry + ONE quiet notification
 *   NOTIFY  "drink water in 2 hours", "buy milk tonight"  -> ONE notification only, no calendar clutter
 *
 * The calendar entry never has its own alert, so only this app notifies.
 * The user can switch the type of any reminder from its chip in the note.
 * No Android imports here, so it can be tested on a normal JVM.
 */
public final class ReminderKind {

    private ReminderKind() {}

    public static final int NOTIFY = 0;
    public static final int EVENT = 1;
    public static final int ALARM = 2;

    private static final Pattern ALARM_WORDS = Pattern.compile(
            "\\b(wake\\s*(me\\s*)?up|wakeup|wake me|alarm|get up|getup)\\b");

    /** Things people normally want to see in a calendar, whenever they happen. */
    private static final Pattern EVENT_WORDS = Pattern.compile(
            "\\b(meeting|meet|appointment|appt|interview|exam|test|viva|class|lecture|seminar|webinar|"
            + "flight|train|bus|trip|travel|journey|party|wedding|birthday|bday|anniversary|function|"
            + "dinner|lunch|doctor|dentist|hospital|checkup|check-up|deadline|due|submission|submit|"
            + "presentation|call with|event|concert|match|movie|show|visa|embassy|puja|festival)\\b");

    private static final long TWELVE_HOURS = 12L * 60 * 60 * 1000;

    public static int classify(String line, DateParser.Result r, long now) {
        String l = line == null ? "" : line.toLowerCase(Locale.ROOT);
        if (r.hasTime && ALARM_WORDS.matcher(l).find()) return ALARM;
        if (EVENT_WORDS.matcher(l).find()) return EVENT;
        if (r.relative) return NOTIFY;
        // daily habits, weekday routines and "every 2 hours" don't belong in the calendar
        if (r.repeat != null && (r.repeat.equals("D") || r.repeat.equals("WD") || r.repeat.startsWith("I"))) return NOTIFY;                         // "in 20 minutes"
        if (r.timeMillis - now < TWELVE_HOURS && r.hasTime) return NOTIFY; // quick same-day things
        return EVENT;                                           // planned for a later day
    }

    public static String emoji(int kind) {
        switch (kind) {
            case ALARM: return "⏰";
            case EVENT: return "📅";
            default: return "🔔";
        }
    }

    public static String label(int kind) {
        switch (kind) {
            case ALARM: return "Alarm";
            case EVENT: return "Calendar + notification";
            default: return "Notification only";
        }
    }
}
