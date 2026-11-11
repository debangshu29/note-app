package com.debangshu.note;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** One note. Saved as JSON on the phone (no server, no database). */
public class Note {
    public long id;
    public String title = "";
    /** Plain text of all blocks (search, reminders, price totals, text sharing). Kept in step by setBlocks(). */
    public String body = "";
    public long created;
    public long updated;
    public boolean pinned;

    /** The content, top to bottom. Older notes (before 2.0) get one text block made from body. */
    public List<Block> blocks = new ArrayList<>();

    // ---- note style
    public int color = 0;        // NoteStyle.COLORS index, 0 = default
    public int paper = 0;        // NoteStyle.PAPER_* (plain, lined, grid, dotted, …)
    public int customColor = 0;  // any colour from the colour picker (0 = use the colour index)
    public int lineColor = 0;    // paper line colour (0 = automatic)
    public String font = "";     // the note's font (NoteStyle.FONT_IDS; "" = the app font)

    // ---- page settings (used for PDF)
    public String pageSize = "A4";      // A4, LETTER, A5, ENDLESS
    public boolean landscape = false;
    public int margins = 1;             // 0 narrow, 1 normal, 2 wide

    public void setBlocks(List<Block> list) {
        blocks = list;
        StringBuilder sb = new StringBuilder();
        for (Block b : list) {
            String p = b.plain();
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(p);
        }
        body = sb.toString();
    }

    public List<Block> blocksOrText() {
        if (blocks == null || blocks.isEmpty()) {
            List<Block> l = new ArrayList<>();
            l.add(Block.text(body));
            return l;
        }
        return blocks;
    }

    public int count(String type) {
        int n = 0;
        if (blocks != null) for (Block b : blocks) if (type.equals(b.type)) n++;
        return n;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("title", title);
        o.put("body", body);
        o.put("created", created);
        o.put("updated", updated);
        o.put("pinned", pinned);
        if (blocks != null && !blocks.isEmpty()) {
            JSONArray arr = new JSONArray();
            for (Block b : blocks) arr.put(b.toJson());
            o.put("blocks", arr);
        }
        o.put("color", color);
        o.put("paper", paper);
        if (customColor != 0) o.put("customColor", customColor);
        if (lineColor != 0) o.put("lineColor", lineColor);
        if (font != null && !font.isEmpty()) o.put("font", font);
        o.put("pageSize", pageSize);
        o.put("landscape", landscape);
        o.put("margins", margins);
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
        JSONArray arr = o.optJSONArray("blocks");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject b = arr.optJSONObject(i);
                if (b != null) n.blocks.add(Block.fromJson(b));
            }
        }
        if (n.blocks.isEmpty()) n.blocks.add(Block.text(n.body));
        n.color = o.optInt("color", 0);
        n.paper = o.optInt("paper", 0);
        n.customColor = o.optInt("customColor", 0);
        n.lineColor = o.optInt("lineColor", 0);
        n.font = o.optString("font", "");
        n.pageSize = o.optString("pageSize", "A4");
        n.landscape = o.optBoolean("landscape", false);
        n.margins = o.optInt("margins", 1);
        return n;
    }

    /** Title to show in lists: the title, or the first line of the body. */
    public String displayTitle() {
        if (title != null && !title.trim().isEmpty()) return title.trim();
        String b = body == null ? "" : body.trim();
        int nl = b.indexOf('\n');
        String first = nl >= 0 ? b.substring(0, nl) : b;
        if (first.length() > 60) first = first.substring(0, 60) + "…";
        if (!first.isEmpty()) return first;
        if (count(Block.IMAGE) > 0) return "Picture note";
        if (count(Block.TABLE) > 0) return "Table";
        return "Untitled note";
    }

    public boolean isEmpty() {
        boolean noText = (title == null || title.trim().isEmpty()) && (body == null || body.trim().isEmpty());
        if (!noText) return false;
        if (blocks != null) for (Block b : blocks) if (Block.IMAGE.equals(b.type) || Block.TABLE.equals(b.type)) return false;
        return true;
    }
}
