package com.debangshu.note;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks which part of a note to share:
 *   ALL     - everything as written
 *   LEFT    - only items still to buy (☐), with a fresh total
 *   BOUGHT  - only items already bought (☑), with a fresh total
 * No Android imports here, so it can be tested on a normal JVM.
 */
public final class NoteFilter {

    private NoteFilter() {}

    public static final int ALL = 0, LEFT = 1, BOUGHT = 2;

    public static boolean hasChecks(String body) {
        if (body == null) return false;
        for (String l : body.split("\n")) {
            String t = l.trim();
            if (t.startsWith("☐") || t.startsWith("☑")) return true;
        }
        return false;
    }

    public static String titleFor(String title, int mode) {
        String t = title == null ? "" : title.trim();
        if (mode == LEFT) return t.isEmpty() ? "Left to buy" : t + " (left to buy)";
        if (mode == BOUGHT) return t.isEmpty() ? "Bought" : t + " (bought)";
        return t;
    }

    public static String filter(String body, int mode) {
        if (body == null) return "";
        if (mode == ALL) return body;
        List<String> out = new ArrayList<>();
        for (String line : body.split("\n", -1)) {
            String t = line.trim();
            if (mode == LEFT && t.startsWith("☑")) continue;
            if (mode == BOUGHT && t.startsWith("☐")) continue;
            if (ListTotals.isTotalLine(line)) continue;                  // old totals: recalculated below
            if (t.isEmpty() && !out.isEmpty() && out.get(out.size() - 1).trim().isEmpty()) continue;
            out.add(line);
        }
        while (!out.isEmpty() && out.get(out.size() - 1).trim().isEmpty()) out.remove(out.size() - 1);
        return withTotals(out);
    }

    /** Adds "Total = …" under every price list in the text. */
    static String withTotals(List<String> lines) {
        String[] arr = lines.toArray(new String[0]);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(arr[i]);
            boolean blockEnds = !arr[i].trim().isEmpty() && (i + 1 == arr.length || arr[i + 1].trim().isEmpty());
            if (blockEnds) {
                ListTotals.Block b = ListTotals.blockEndingAt(arr, i);
                if (b != null) sb.append("\nTotal = ").append(ListTotals.money(b.total, b.currency));
            }
        }
        return sb.toString();
    }

    /** Plain-text version of one note for messages. */
    public static String asText(String title, String body) {
        String t = title == null ? "" : title.trim();
        String b = body == null ? "" : body.trim();
        if (t.isEmpty()) return b;
        if (b.isEmpty()) return t;
        return t + "\n\n" + b;
    }
}
