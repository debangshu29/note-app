package com.debangshu.note;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A note's text seen as a board (plain Java, tested on a computer): every "# heading" (or "## …") is a
 * column, and the ☐ / ☑ / • / - lines under it are its cards. Moving a card moves its line.
 */
public final class BoardModel {

    public static final class Column {
        public String title;
        public int headingLine = -1;       // -1 = the part above the first heading
        public final List<Integer> cards = new ArrayList<>();   // line numbers
        public boolean isDone() { return isDoneTitle(title); }
    }

    private static final Pattern HEADING = Pattern.compile("^\\s*#{1,3}\\s+(\\S.*)$");
    private static final Pattern CARD = Pattern.compile("^\\s*(?:[☐☑•*➜]|-(?=\\s)|\\d{1,3}[.)](?=\\s))\\s*(\\S.*)$");

    public final List<Column> columns = new ArrayList<>();
    public final String[] lines;

    private BoardModel(String text) {
        lines = text.split("\n", -1);
        Column cur = new Column();
        cur.title = "";
        columns.add(cur);
        for (int i = 0; i < lines.length; i++) {
            Matcher h = HEADING.matcher(lines[i]);
            if (h.find()) {
                cur = new Column();
                cur.title = h.group(1).trim();
                cur.headingLine = i;
                columns.add(cur);
                continue;
            }
            if (CARD.matcher(lines[i]).find()) cur.cards.add(i);
        }
        if (columns.get(0).cards.isEmpty() && columns.size() > 1) columns.remove(0);
        else columns.get(0).title = columns.size() > 1 ? "Other" : "To do";
    }

    public static BoardModel parse(String text) { return new BoardModel(text == null ? "" : text); }

    /** True when the note already has 2 or more headings to use as columns. */
    public boolean hasColumns() {
        int n = 0;
        for (Column c : columns) if (c.headingLine >= 0) n++;
        return n >= 2;
    }

    public static boolean isDoneTitle(String t) {
        return t != null && t.trim().toLowerCase(Locale.ROOT).matches("(?:✅\\s*)?(?:done|completed?|finished|ho gaya|hoye geche)\\b.*");
    }

    public static String cardText(String line) {
        Matcher m = CARD.matcher(line);
        return m.find() ? m.group(1).trim() : line.trim();
    }

    public static boolean isTicked(String line) { return line.trim().startsWith("☑"); }

    /** The starter board for a note without headings: its list lines go to "To do". */
    public static String starter(String text) {
        StringBuilder todo = new StringBuilder(), rest = new StringBuilder(), done = new StringBuilder();
        for (String l : (text == null ? "" : text).split("\n", -1)) {
            if (CARD.matcher(l).find()) {
                String t = cardText(l);
                if (isTicked(l)) done.append("☑ ").append(t).append('\n');
                else todo.append("☐ ").append(t).append('\n');
            } else rest.append(l).append('\n');
        }
        String head = rest.toString().replaceAll("\\n{3,}", "\n\n").trim();
        return (head.isEmpty() ? "" : head + "\n\n") + "# To do\n" + todo + "\n# Doing\n\n# Done\n" + done;
    }
}
