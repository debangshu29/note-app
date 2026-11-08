package com.debangshu.note;

import org.json.JSONException;
import org.json.JSONObject;

/** A reminder that was found automatically inside a note line. */
public class Reminder {
    public long id;
    public long noteId;
    /** noteId + cleaned title: lets us update the same reminder when its time is edited. */
    public String key;
    public String title;
    public String sourceLine;
    public long time;
    public boolean hasTime;
    public boolean done;
    /** User said "don't remind me" for this line: never re-create it. */
    public boolean ignored;
    /** Event id in the phone calendar, or -1. */
    public long calendarEventId = -1;
    /** ReminderKind.NOTIFY / EVENT / ALARM */
    public int kind = ReminderKind.EVENT;
    /** true once the user picked the type by hand: then the text no longer changes it */
    public boolean kindLocked;

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("noteId", noteId);
        o.put("key", key);
        o.put("title", title);
        o.put("sourceLine", sourceLine);
        o.put("time", time);
        o.put("hasTime", hasTime);
        o.put("done", done);
        o.put("ignored", ignored);
        o.put("calendarEventId", calendarEventId);
        o.put("kind", kind);
        o.put("kindLocked", kindLocked);
        return o;
    }

    public static Reminder fromJson(JSONObject o) {
        Reminder r = new Reminder();
        r.id = o.optLong("id");
        r.noteId = o.optLong("noteId");
        r.key = o.optString("key", "");
        r.title = o.optString("title", "");
        r.sourceLine = o.optString("sourceLine", "");
        r.time = o.optLong("time");
        r.hasTime = o.optBoolean("hasTime", true);
        r.done = o.optBoolean("done");
        r.ignored = o.optBoolean("ignored");
        r.calendarEventId = o.optLong("calendarEventId", -1);
        r.kind = o.optInt("kind", ReminderKind.EVENT);
        r.kindLocked = o.optBoolean("kindLocked");
        return r;
    }

    /** Stable int used for the alarm and the notification. */
    public int requestCode() {
        return (int) (id ^ (id >>> 32));
    }

    public static String makeKey(long noteId, String title) {
        return noteId + ":" + (title == null ? "" : title.trim().toLowerCase());
    }
}
