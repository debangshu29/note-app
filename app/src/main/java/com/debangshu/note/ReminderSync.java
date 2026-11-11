package com.debangshu.note;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reads every line of a note, finds dates/times, and keeps the reminders
 * in step with the text.
 *
 *   - new line with a date      -> new reminder (alarm, calendar+notification, or notification: see ReminderKind)
 *   - date of a line changed    -> same reminder moved to the new time
 *   - line deleted              -> reminder, alarm and calendar event removed
 */
public final class ReminderSync {

    private ReminderSync() {}

    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** A date found in a line, used for the live preview in the editor. */
    public static final class Found {
        public final String line;
        public final DateParser.Result result;
        public final int kind;
        Found(String line, DateParser.Result result, int kind) {
            this.line = line; this.result = result; this.kind = kind;
        }
    }

    /** Finds all reminders in a note's text (fast, no saving). */
    public static List<Found> detect(Context ctx, String title, String body) {
        List<Found> out = new ArrayList<>();
        Store s = Store.get(ctx);
        if (!s.setting(Store.S_AUTO_REMINDERS)) return out;
        Calendar now = Calendar.getInstance();
        int hour = s.defaultHour();
        String all = (title == null ? "" : title) + "\n" + (body == null ? "" : body);
        Map<String, Boolean> seen = new HashMap<>();
        for (String raw : all.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("☑")) continue; // ticked items are finished
            int eq = line.indexOf('=');
            if (SmartCalc.isCalc(eq > 0 ? line.substring(0, eq) : line)) continue;   // "45 days from today = …" is maths
            DateParser.Result r = DateParser.parse(line, now, hour, 0);
            if (r == null) continue;
            String k = r.title.toLowerCase();
            if (seen.containsKey(k)) continue;
            seen.put(k, true);
            out.add(new Found(line, r, ReminderKind.classify(line, r, now.getTimeInMillis())));
        }
        return out;
    }

    /** Saves reminders for a note in the background, then runs {@code done} on the UI thread. */
    public static void syncAsync(Context ctx, long noteId, Runnable done) {
        final Context app = ctx.getApplicationContext();
        worker.execute(() -> {
            try { sync(app, noteId); } catch (Exception ignored) { }
            if (done != null) main.post(done);
        });
    }

    public static void runInBackground(Runnable job, Runnable done) {
        worker.execute(() -> {
            try { job.run(); } catch (Exception ignored) { }
            if (done != null) main.post(done);
        });
    }

    private static void sync(Context ctx, long noteId) {
        Store s = Store.get(ctx);
        Note note = s.note(noteId);
        List<Reminder> existing = s.remindersFor(noteId);

        if (note == null || note.deleted > 0) {   // note was deleted (or is in the bin) -> remove all of its reminders
            for (Reminder r : existing) removeReminder(ctx, r);
            return;
        }
        if (!s.setting(Store.S_AUTO_REMINDERS)) return;

        List<Found> found = detect(ctx, note.title, note.body);
        String fullText = note.title + "\n" + note.body;
        Map<String, Reminder> byKey = new HashMap<>();
        for (Reminder r : existing) byKey.put(r.key, r);

        List<String> keep = new ArrayList<>();
        for (Found f : found) {
            String key = Reminder.makeKey(noteId, f.result.title);
            keep.add(key);
            Reminder r = byKey.get(key);
            if (r == null) {
                r = new Reminder();
                r.id = s.newId();
                r.noteId = noteId;
                r.key = key;
                r.title = f.result.title;
                r.sourceLine = f.line;
                r.time = f.result.timeMillis;
                r.hasTime = f.result.hasTime;
                r.kind = f.kind;
                r.repeat = f.result.repeat;
                r.anchor = r.time;
                s.saveReminder(r);
                activate(ctx, r);
            } else if (r.ignored) {
                r.sourceLine = f.line;
                s.saveReminder(r);
            } else if (!f.line.equals(r.sourceLine)) {
                // only re-read the time when the line itself was edited: "tomorrow" written
                // yesterday must keep meaning the day it was written for
                boolean moved = r.time != f.result.timeMillis;
                boolean kindChanged = !r.kindLocked && r.kind != f.kind;
                r.sourceLine = f.line;
                r.title = f.result.title;
                r.time = f.result.timeMillis;
                r.hasTime = f.result.hasTime;
                boolean repeatChanged = !f.result.repeat.equals(r.repeat == null ? "" : r.repeat);
                r.repeat = f.result.repeat;
                r.anchor = r.time;
                if (repeatChanged && r.calendarEventId > 0) {
                    CalendarHelper.delete(ctx, r.calendarEventId);   // a new event with / without repeat
                    r.calendarEventId = -1;
                }
                if (kindChanged) r.kind = f.kind;
                if (moved) r.done = false;
                s.saveReminder(r);
                if (!r.done) activate(ctx, r);
            }
        }

        long now = System.currentTimeMillis();
        for (Reminder r : existing) {
            if (keep.contains(r.key)) continue;
            // the line was ticked (☐ -> ☑): mark the reminder done instead of deleting it
            if (r.sourceLine != null && r.sourceLine.startsWith("☐")
                    && fullText.contains("☑" + r.sourceLine.substring(1))) {
                if (!r.done) setDone(ctx, r, true);
                continue;
            }
            // a reminder whose time is over stops being "detected" but should stay in the list
            boolean lineStillThere = r.sourceLine != null && !r.sourceLine.isEmpty()
                    && fullText.contains(r.sourceLine);
            if (lineStillThere && (r.time <= now || r.done || r.ignored || r.repeats())) continue;
            removeReminder(ctx, r);
        }
    }

    /**
     * Arms the reminder the way its type says:
     *   ALARM  -> alarm-clock alarm, no calendar event
     *   EVENT  -> exact alarm for one notification + phone calendar event (event has no alert of its own)
     *   NOTIFY -> exact alarm for one notification, no calendar event
     */
    static void activate(Context ctx, Reminder r) {
        Store s = Store.get(ctx);
        AlarmScheduler.cancel(ctx, r);
        AlarmScheduler.schedule(ctx, r);
        boolean wantCalendar = r.kind == ReminderKind.EVENT && s.setting(Store.S_ADD_TO_CALENDAR);
        if (wantCalendar && CalendarHelper.hasPermission(ctx)) {
            if (r.calendarEventId > 0 && CalendarHelper.update(ctx, r)) return;
            long ev = CalendarHelper.insert(ctx, r);
            if (ev > 0) { r.calendarEventId = ev; s.saveReminder(r); }
        } else if (!wantCalendar && r.calendarEventId > 0) {
            CalendarHelper.delete(ctx, r.calendarEventId);
            r.calendarEventId = -1;
            s.saveReminder(r);
        }
    }

    /** User picked a type from the chip menu. */
    public static void setKind(Context ctx, Reminder r, int kind) {
        r.kind = kind;
        r.kindLocked = true;
        Store.get(ctx).saveReminder(r);
        if (!r.done && !r.ignored && r.time > System.currentTimeMillis()) activate(ctx, r);
        else if (kind != ReminderKind.EVENT && r.calendarEventId > 0) {
            CalendarHelper.delete(ctx, r.calendarEventId);
            r.calendarEventId = -1;
            Store.get(ctx).saveReminder(r);
        }
    }

    public static void removeReminder(Context ctx, Reminder r) {
        AlarmScheduler.cancel(ctx, r);
        Notifier.cancel(ctx, r);
        if (r.calendarEventId > 0) CalendarHelper.delete(ctx, r.calendarEventId);
        Store.get(ctx).removeReminder(r.id);
    }

    public static void setDone(Context ctx, Reminder r, boolean done) {
        if (done && r.repeats()) { skip(ctx, r); return; }   // repeating: this time is done, the next one stays
        r.done = done;
        Store.get(ctx).saveReminder(r);
        if (done) { AlarmScheduler.cancel(ctx, r); Notifier.cancel(ctx, r); }
        else if (r.time > System.currentTimeMillis()) AlarmScheduler.schedule(ctx, r);
    }

    /** User tapped "don't remind" on a detected line. */
    public static void ignore(Context ctx, Reminder r) {
        r.ignored = true;
        AlarmScheduler.cancel(ctx, r);
        Notifier.cancel(ctx, r);
        if (r.calendarEventId > 0) { CalendarHelper.delete(ctx, r.calendarEventId); r.calendarEventId = -1; }
        Store.get(ctx).saveReminder(r);
    }

    public static void unignore(Context ctx, Reminder r) {
        r.ignored = false;
        Store.get(ctx).saveReminder(r);
        if (!r.done && r.time > System.currentTimeMillis()) activate(ctx, r);
    }

    /** Re-arm every future alarm (after a reboot, an app update, or app start). */
    public static void rescheduleAll(Context ctx) {
        long now = System.currentTimeMillis();
        Store s = Store.get(ctx);
        for (Reminder r : s.reminders()) {
            if (r.done || r.ignored) continue;
            if (r.repeats() && r.time <= now) {            // missed while the phone was off: go to the next one
                r.time = DateParser.nextAfter(r.anchor > 0 ? r.anchor : r.time, r.repeat, now);
                s.saveReminder(r);
            }
            if (r.time > now) AlarmScheduler.schedule(ctx, r);
        }
    }

    /** After a repeating reminder rang: move it to its next time and arm that. */
    public static void advance(Context ctx, Reminder r) {
        if (!r.repeats()) return;
        long now = System.currentTimeMillis();
        r.time = DateParser.nextAfter(r.anchor > 0 ? r.anchor : r.time, r.repeat, Math.max(now, r.time));
        Store.get(ctx).saveReminder(r);
        AlarmScheduler.schedule(ctx, r);
    }

    /** "Done for now" on a repeating reminder: skip to the next time, the series keeps going. */
    public static void skip(Context ctx, Reminder r) {
        AlarmScheduler.cancel(ctx, r);
        Notifier.cancel(ctx, r);
        advance(ctx, r);
    }

    /** Stops a repeating reminder (keeps it as done). */
    public static void stopRepeating(Context ctx, Reminder r) {
        r.repeat = "";
        if (r.calendarEventId > 0) { CalendarHelper.delete(ctx, r.calendarEventId); r.calendarEventId = -1; }
        setDone(ctx, r, true);
    }

    /** Adds calendar events for "calendar" reminders that don't have one yet (after calendar access is allowed). */
    public static void addMissingCalendarEvents(Context ctx) {
        Store s = Store.get(ctx);
        if (!s.setting(Store.S_ADD_TO_CALENDAR) || !CalendarHelper.hasPermission(ctx)) return;
        long now = System.currentTimeMillis();
        for (Reminder r : s.reminders()) {
            if (r.kind != ReminderKind.EVENT || r.calendarEventId > 0 || r.ignored || r.time <= now) continue;
            long ev = CalendarHelper.insert(ctx, r);
            if (ev > 0) { r.calendarEventId = ev; s.saveReminder(r); }
        }
    }

    /** Removes every alarm, notification and calendar event this app made. */
    public static void removeAll(Context ctx, boolean alsoCalendar) {
        for (Reminder r : Store.get(ctx).reminders()) {
            AlarmScheduler.cancel(ctx, r);
            Notifier.cancel(ctx, r);
            if (alsoCalendar && r.calendarEventId > 0) CalendarHelper.delete(ctx, r.calendarEventId);
        }
    }
}
