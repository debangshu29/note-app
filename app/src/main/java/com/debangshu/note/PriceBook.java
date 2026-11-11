package com.debangshu.note;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Price memory: the last price of every thing on your price lists ("milk" → ₹60, 3 Nov). */
public final class PriceBook {

    public static final class Entry {
        public String key, name, line;
        public double price, low;
        public long when, noteId;
        public int times;
    }

    private final Map<String, Entry> map = new HashMap<>();

    private static PriceBook cached;
    private static int cachedAt = -1;

    /** Built again only after notes changed. */
    public static synchronized PriceBook get(Store s) {
        if (cached == null || cachedAt != Store.changes) {
            cachedAt = Store.changes;
            cached = build(s);
        }
        return cached;
    }

    private static PriceBook build(Store s) {
        PriceBook pb = new PriceBook();
        for (Note n : s.notes()) {
            if (n.deleted > 0 || n.locked || n.body == null || n.tags().contains(WalletActivity.TAG)) continue;
            String[] lines = n.body.split("\n", -1);
            boolean[] seen = new boolean[lines.length];
            for (int i = lines.length - 1; i >= 0; i--) {
                if (seen[i]) continue;
                ListTotals.Block b = ListTotals.blockEndingAt(lines, i);
                if (b == null) continue;
                for (int k = b.firstLine; k <= b.lastLine; k++) {
                    seen[k] = true;
                    String l = lines[k];
                    if (Money.debt(l) != null || Money.bill(l) != null || l.contains("→")) continue;
                    ListTotals.Item it = ListTotals.price(l);
                    if (it == null || it.price <= 0) continue;
                    String key = Money.itemKey(l);
                    if (key.length() < 2 || key.length() > 40) continue;
                    pb.add(key, Money.nameOf(l), l.trim().replaceFirst("^[☐☑•*\\-]\\s*", ""), it.price, n.updated, n.id);
                }
                i = b.firstLine;
            }
        }
        return pb;
    }

    private void add(String key, String name, String line, double price, long when, long noteId) {
        Entry e = map.get(key);
        if (e == null) {
            e = new Entry();
            e.key = key;
            e.low = price;
            map.put(key, e);
        }
        e.times++;
        e.low = Math.min(e.low, price);
        if (when >= e.when) {
            e.name = name;
            e.line = line;
            e.price = price;
            e.when = when;
            e.noteId = noteId;
        }
    }

    public int size() { return map.size(); }

    public Entry find(String line) {
        String k = Money.itemKey(line);
        return k.isEmpty() ? null : map.get(k);
    }

    /** Most recently bought first. */
    public List<Entry> all() {
        List<Entry> l = new ArrayList<>(map.values());
        Collections.sort(l, (a, b) -> Long.compare(b.when, a.when));
        return l;
    }
}
