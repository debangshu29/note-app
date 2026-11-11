package com.debangshu.note;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Notes as QR codes (plain Java, tested on a computer). A short note is one QR with its plain text, so any
 * phone camera can read it. A long one is packed and split: "NOTEQR1|id|part|of|data", and Note puts the
 * parts back together whatever order they are scanned in.
 */
public final class QrCodec {

    private QrCodec() {}

    public static final String PREFIX = "NOTEQR1|";
    static final int PLAIN_MAX = 700;     // characters in one plain QR
    static final int CHUNK = 900;         // packed characters per QR
    public static final int MAX_PARTS = 16;

    /** The QR contents for a note. Null when it is too long even when packed. */
    public static List<String> encode(String title, String body) {
        String t = title == null ? "" : title.trim();
        String b = body == null ? "" : body.replaceAll("\\s+$", "");
        String plain = t.isEmpty() ? b : b.isEmpty() ? t : t + "\n\n" + b;
        List<String> out = new ArrayList<>();
        if (plain.length() <= PLAIN_MAX && !plain.startsWith(PREFIX)) { out.add(plain); return out; }
        String packed = b64(deflate((t + "\u0000" + b).getBytes(StandardCharsets.UTF_8)));
        int n = (packed.length() + CHUNK - 1) / CHUNK;
        if (n > MAX_PARTS) return null;
        String id = Integer.toHexString((plain.hashCode() ^ (int) System.nanoTime()) & 0xFFFFFF);
        for (int i = 0; i < n; i++) {
            out.add(PREFIX + id + "|" + (i + 1) + "|" + n + "|" + packed.substring(i * CHUNK, Math.min(packed.length(), (i + 1) * CHUNK)));
        }
        return out;
    }

    /** Collects scanned parts. */
    public static final class Collector {
        private final Map<String, Map<Integer, String>> parts = new HashMap<>();
        private final Map<String, Integer> totals = new HashMap<>();
        public String lastId;

        /** Adds one scan. Returns {title, body} when the note is complete, else null. */
        public String[] add(String raw) {
            if (raw == null) return null;
            if (!raw.startsWith(PREFIX)) {
                String t = raw.trim();
                int nl = t.indexOf("\n\n");
                if (nl > 0 && nl <= 80 && !t.substring(0, nl).contains("\n")) return new String[]{t.substring(0, nl), t.substring(nl + 2)};
                return new String[]{"", t};
            }
            String[] f = raw.split("\\|", 5);
            if (f.length < 5) return null;
            int i, n;
            try { i = Integer.parseInt(f[2]); n = Integer.parseInt(f[3]); } catch (Exception e) { return null; }
            if (n < 1 || n > MAX_PARTS || i < 1 || i > n) return null;
            lastId = f[1];
            Map<Integer, String> m = parts.get(f[1]);
            if (m == null) { m = new HashMap<>(); parts.put(f[1], m); }
            m.put(i, f[4]);
            totals.put(f[1], n);
            if (m.size() < n) return null;
            StringBuilder sb = new StringBuilder();
            for (int k = 1; k <= n; k++) sb.append(m.get(k));
            try {
                String s = new String(inflate(unb64(sb.toString())), StandardCharsets.UTF_8);
                int z = s.indexOf('\u0000');
                parts.remove(f[1]);
                return z >= 0 ? new String[]{s.substring(0, z), s.substring(z + 1)} : new String[]{"", s};
            } catch (Exception e) {
                parts.remove(f[1]);
                return null;
            }
        }

        public int have(String id) { Map<Integer, String> m = parts.get(id); return m == null ? 0 : m.size(); }
        public int total(String id) { Integer t = totals.get(id); return t == null ? 0 : t; }
        public List<Integer> missing(String id) {
            List<Integer> out = new ArrayList<>();
            Map<Integer, String> m = parts.get(id);
            int n = total(id);
            for (int k = 1; k <= n; k++) if (m == null || !m.containsKey(k)) out.add(k);
            return out;
        }
    }

    // ------------------------------------------------------------------ packing

    static byte[] deflate(byte[] in) {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        d.setInput(in);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!d.finished()) out.write(buf, 0, d.deflate(buf));
        d.end();
        return out.toByteArray();
    }

    static byte[] inflate(byte[] in) throws Exception {
        Inflater i = new Inflater();
        i.setInput(in);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!i.finished()) {
            int n = i.inflate(buf);
            if (n == 0 && (i.needsInput() || i.needsDictionary())) break;
            out.write(buf, 0, n);
            if (out.size() > 5_000_000) break;
        }
        i.end();
        return out.toByteArray();
    }

    private static final char[] A = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    static String b64(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i += 3) {
            int b = (d[i] & 0xFF) << 16 | (i + 1 < d.length ? (d[i + 1] & 0xFF) << 8 : 0) | (i + 2 < d.length ? d[i + 2] & 0xFF : 0);
            sb.append(A[b >> 18 & 63]).append(A[b >> 12 & 63]);
            sb.append(i + 1 < d.length ? A[b >> 6 & 63] : '=');
            sb.append(i + 2 < d.length ? A[b & 63] : '=');
        }
        return sb.toString();
    }

    static byte[] unb64(String s) {
        int[] rev = new int[128];
        java.util.Arrays.fill(rev, -1);
        for (int i = 0; i < A.length; i++) rev[A[i]] = i;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buf = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=' || c >= 128 || rev[c] < 0) continue;
            buf = buf << 6 | rev[c];
            bits += 6;
            if (bits >= 8) { bits -= 8; out.write(buf >> bits & 0xFF); }
        }
        return out.toByteArray();
    }
}
