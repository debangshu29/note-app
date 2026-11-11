package com.debangshu.note;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import java.util.TimeZone;

/**
 * Adds reminders to the phone's own Calendar app (Google Calendar / Samsung Calendar ...).
 * Only works after the user allows calendar access. Everything stays on the phone /
 * the user's own calendar account; this app has no server.
 */
public final class CalendarHelper {

    private CalendarHelper() {}

    public static boolean hasPermission(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
                && ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    /** Picks the primary writable calendar, or -1 if the phone has none. */
    private static long findCalendarId(Context ctx) {
        String[] proj = {
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.IS_PRIMARY,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.VISIBLE
        };
        long best = -1;
        int bestScore = -1;
        try (Cursor c = ctx.getContentResolver().query(CalendarContract.Calendars.CONTENT_URI, proj,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + ">=?",
                new String[]{String.valueOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)}, null)) {
            if (c == null) return -1;
            while (c.moveToNext()) {
                int score = 0;
                if (c.getInt(1) == 1) score += 2;      // primary calendar
                if (c.getInt(3) == 1) score += 1;      // visible
                if (c.getInt(2) >= CalendarContract.Calendars.CAL_ACCESS_OWNER) score += 1;
                if (score > bestScore) { bestScore = score; best = c.getLong(0); }
            }
        } catch (Exception e) {
            return -1;
        }
        return best;
    }

    private static ContentValues values(Reminder r) {
        ContentValues v = new ContentValues();
        v.put(CalendarContract.Events.TITLE, r.title);
        v.put(CalendarContract.Events.DESCRIPTION, "From your Note app:\n" + r.sourceLine);
        if (r.hasTime) {
            v.put(CalendarContract.Events.DTSTART, r.time);
            v.put(CalendarContract.Events.DTEND, r.time + 30 * 60 * 1000L);
            v.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
            v.put(CalendarContract.Events.ALL_DAY, 0);
        } else {
            // date without a time -> all-day event (all-day events must be in UTC midnight)
            java.util.Calendar local = java.util.Calendar.getInstance();
            local.setTimeInMillis(r.time);
            java.util.Calendar utc = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            utc.clear();
            utc.set(local.get(java.util.Calendar.YEAR), local.get(java.util.Calendar.MONTH),
                    local.get(java.util.Calendar.DAY_OF_MONTH));
            v.put(CalendarContract.Events.DTSTART, utc.getTimeInMillis());
            v.put(CalendarContract.Events.DTEND, utc.getTimeInMillis() + 24 * 60 * 60 * 1000L);
            v.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC");
            v.put(CalendarContract.Events.ALL_DAY, 1);
        }
        // repeating reminders become repeating calendar events (DURATION instead of DTEND)
        String rule = rrule(r);
        if (rule != null) {
            v.putNull(CalendarContract.Events.DTEND);
            v.put(CalendarContract.Events.DURATION, r.hasTime ? "PT30M" : "P1D");
            v.put(CalendarContract.Events.RRULE, rule);
            if (r.anchor > 0 && r.hasTime) v.put(CalendarContract.Events.DTSTART, Math.min(r.anchor, r.time));
        }
        // the app shows its own notification, so the calendar event has no extra alarm
        v.put(CalendarContract.Events.HAS_ALARM, 0);
        return v;
    }

    public static long insert(Context ctx, Reminder r) {
        if (!hasPermission(ctx)) return -1;
        try {
            long calId = findCalendarId(ctx);
            if (calId < 0) return -1;
            ContentValues v = values(r);
            v.put(CalendarContract.Events.CALENDAR_ID, calId);
            Uri uri = ctx.getContentResolver().insert(CalendarContract.Events.CONTENT_URI, v);
            return uri == null ? -1 : ContentUris.parseId(uri);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String rrule(Reminder r) {
        if (!r.repeats()) return null;
        String rep = r.repeat;
        if (rep.equals("D")) return "FREQ=DAILY";
        if (rep.equals("WD")) return "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR";
        if (rep.equals("Y")) return "FREQ=YEARLY";
        if (rep.startsWith("W")) {
            String[] days = {"", "SU", "MO", "TU", "WE", "TH", "FR", "SA"};
            try { return "FREQ=WEEKLY;BYDAY=" + days[Integer.parseInt(rep.substring(1))]; }
            catch (Exception e) { return "FREQ=WEEKLY"; }
        }
        if (rep.startsWith("M")) {
            try {
                int d = Integer.parseInt(rep.substring(1));
                return d >= 29 ? "FREQ=MONTHLY;BYMONTHDAY=-1" : "FREQ=MONTHLY;BYMONTHDAY=" + d;
            } catch (Exception e) { return "FREQ=MONTHLY"; }
        }
        return null;   // "every 2 hours" is not a calendar thing
    }

    public static boolean update(Context ctx, Reminder r) {
        if (!hasPermission(ctx) || r.calendarEventId <= 0) return false;
        try {
            Uri uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, r.calendarEventId);
            return ctx.getContentResolver().update(uri, values(r), null, null) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static void delete(Context ctx, long eventId) {
        if (!hasPermission(ctx) || eventId <= 0) return;
        try {
            ContentResolver cr = ctx.getContentResolver();
            cr.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null);
        } catch (Exception ignored) { }
    }
}
