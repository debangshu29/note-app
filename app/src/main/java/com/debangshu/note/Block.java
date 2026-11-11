package com.debangshu.note;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One piece of a note. A note is a list of blocks, top to bottom:
 *   TEXT    formatted text (bold, colours, alignment… kept as "spans")
 *   IMAGE   a photo, scanned page, PDF page or drawing (file in Note's private storage)
 *   STICKY  a coloured sticky-note box
 *   TABLE   rows and columns
 *   PAGE    a page break
 */
public class Block {
    public static final String TEXT = "text", IMAGE = "image", STICKY = "sticky", TABLE = "table", PAGE = "page";

    public String type = TEXT;
    public String text = "";
    public JSONArray spans = new JSONArray();   // TEXT: formatting (see RichText)
    public String file = "";                    // IMAGE: file name in the media folder
    public String drawing = "";                 // IMAGE from the drawing screen: strokes file, so it can be edited again
    public float width = 1f;                    // IMAGE: share of the page width (0.1 .. 1)
    public float aspect = 0f;                   // IMAGE: height / width after free resizing; 0 = the picture's own shape
    public float x = 0.5f;                      // IMAGE: position on the line (0 left, 0.5 centre, 1 right)
    public String source = "";                  // IMAGE: camera, gallery, scan, pdf, drawing, import
    public String original = "";                // IMAGE: the picture before black & white, so it can be undone
    public int color = 0;                       // STICKY: colour index (old colours)
    public int argb = 0;                        // STICKY: any colour from the colour picker (0 = use the index)
    public List<List<String>> rows = new ArrayList<>(); // TABLE: cells
    public boolean header = true;               // TABLE: first row is a header

    public static Block text(String t) {
        Block b = new Block();
        b.type = TEXT;
        b.text = t == null ? "" : t;
        return b;
    }

    public static Block image(String file) {
        Block b = new Block();
        b.type = IMAGE;
        b.file = file;
        return b;
    }

    public static Block sticky(int color) {
        Block b = new Block();
        b.type = STICKY;
        b.color = color;
        return b;
    }

    public static Block table(int r, int c) {
        Block b = new Block();
        b.type = TABLE;
        for (int i = 0; i < r; i++) {
            List<String> row = new ArrayList<>();
            for (int j = 0; j < c; j++) row.add("");
            b.rows.add(row);
        }
        return b;
    }

    public static Block pageBreak() {
        Block b = new Block();
        b.type = PAGE;
        return b;
    }

    public static Block image(String file, String source) {
        Block b = image(file);
        b.source = source == null ? "" : source;
        return b;
    }

    public boolean isText() { return TEXT.equals(type); }

    /** Plain text of this block, used for search, reminders, totals and text sharing. */
    public String plain() {
        switch (type) {
            case TEXT:
            case STICKY:
                return text;
            case TABLE: {
                StringBuilder sb = new StringBuilder();
                for (List<String> row : rows) {
                    if (sb.length() > 0) sb.append('\n');
                    StringBuilder line = new StringBuilder();
                    for (String c : row) {
                        if (line.length() > 0) line.append(" | ");
                        line.append(c == null ? "" : c.trim());
                    }
                    sb.append(line);
                }
                return sb.toString();
            }
            default:
                return "";
        }
    }

    public int columns() {
        int c = 0;
        for (List<String> r : rows) c = Math.max(c, r.size());
        return c;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("type", type);
        switch (type) {
            case TEXT:
                o.put("text", text);
                if (spans != null && spans.length() > 0) o.put("spans", spans);
                break;
            case STICKY:
                o.put("text", text);
                o.put("color", color);
                if (argb != 0) o.put("argb", argb);
                break;
            case IMAGE:
                o.put("file", file);
                o.put("width", (double) width);
                if (drawing != null && !drawing.isEmpty()) o.put("drawing", drawing);
                if (aspect > 0) o.put("aspect", (double) aspect);
                if (x != 0.5f) o.put("x", (double) x);
                if (source != null && !source.isEmpty()) o.put("source", source);
                if (original != null && !original.isEmpty()) o.put("original", original);
                break;
            case TABLE: {
                JSONArray rs = new JSONArray();
                for (List<String> row : rows) {
                    JSONArray r = new JSONArray();
                    for (String c : row) r.put(c == null ? "" : c);
                    rs.put(r);
                }
                o.put("rows", rs);
                o.put("header", header);
                break;
            }
        }
        return o;
    }

    public static Block fromJson(JSONObject o) {
        Block b = new Block();
        b.type = o.optString("type", TEXT);
        b.text = o.optString("text", "");
        JSONArray sp = o.optJSONArray("spans");
        b.spans = sp == null ? new JSONArray() : sp;
        b.file = o.optString("file", "");
        b.drawing = o.optString("drawing", "");
        b.width = (float) o.optDouble("width", 1.0);
        b.aspect = (float) o.optDouble("aspect", 0);
        b.x = (float) o.optDouble("x", 0.5);
        b.source = o.optString("source", "");
        b.original = o.optString("original", "");
        b.color = o.optInt("color", 0);
        b.argb = o.optInt("argb", 0);
        b.header = o.optBoolean("header", true);
        JSONArray rs = o.optJSONArray("rows");
        if (rs != null) {
            for (int i = 0; i < rs.length(); i++) {
                JSONArray r = rs.optJSONArray(i);
                List<String> row = new ArrayList<>();
                if (r != null) for (int j = 0; j < r.length(); j++) row.add(r.optString(j, ""));
                b.rows.add(row);
            }
        }
        return b;
    }

    public Block copy() {
        try { return fromJson(toJson()); } catch (JSONException e) { return text(text); }
    }
}
