package com.debangshu.note;

import org.json.JSONException;
import org.json.JSONObject;

/** One note. Saved as JSON on the phone (no server, no database). */
public class Note {
    public long id;
    public String title = "";
    public String body = "";
    public long created;
    public long updated;
    public boolean pinned;

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("title", title);
        o.put("body", body);
        o.put("created", created);
        o.put("updated", updated);
        o.put("pinned", pinned);
        return o;
    }

    public static Note fromJson(JSONObject o) {
        Note n = new Note();
        n.id = o.optLong("id");
        n.title = o.optString("title", "");
        n.body = o.optString("body", "");
        n.created = o.optLong("created");
        n.updated = o.optLong("updated");
        n.pinned = o.optBoolean("pinned");
        return n;
    }

    /** Title to show in lists: the title, or the first line of the body. */
    public String displayTitle() {
        if (title != null && !title.trim().isEmpty()) return title.trim();
        String b = body == null ? "" : body.trim();
        int nl = b.indexOf('\n');
        String first = nl >= 0 ? b.substring(0, nl) : b;
        if (first.length() > 60) first = first.substring(0, 60) + "…";
        return first.isEmpty() ? "Untitled note" : first;
    }

    public boolean isEmpty() {
        return (title == null || title.trim().isEmpty()) && (body == null || body.trim().isEmpty());
    }
}
