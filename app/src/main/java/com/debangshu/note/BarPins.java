package com.debangshu.note;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.widget.RemoteViews;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Notes pinned to the notification bar: the note stays there (title, first lines, ☐ items you can
 * tick right there) until you unpin it — in the note (⋮ → Unpin) or with "Unpin" on the notification.
 */
public final class BarPins {

    private BarPins() {}

    private static final String CHANNEL = "pinned_notes";
    static final String ACTION_UNPIN = "com.debangshu.note.BAR_UNPIN";
    static final String ACTION_TICK = "com.debangshu.note.BAR_TICK";
    static final String ACTION_GONE = "com.debangshu.note.BAR_GONE";
    private static final int MAX_ROWS = 6;

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("bar_pins", Context.MODE_PRIVATE); }

    public static Set<String> ids(Context c) { return new HashSet<>(prefs(c).getStringSet("ids", new HashSet<>())); }

    public static boolean isPinned(Context c, long id) { return ids(c).contains(String.valueOf(id)); }

    public static void pin(Context c, long id) {
        Set<String> s = ids(c);
        s.add(String.valueOf(id));
        prefs(c).edit().putStringSet("ids", s).apply();
        show(c, id);
    }

    public static void unpin(Context c, long id) {
        Set<String> s = ids(c);
        s.remove(String.valueOf(id));
        prefs(c).edit().putStringSet("ids", s).apply();
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(nid(id));
    }

    private static int nid(long id) { return 990000 + (int) (id % 9000); }

    /** Brings every pinned note up to date (after saving, after a restart). */
    public static void refreshAll(Context c) {
        for (String s : ids(c)) {
            try { show(c, Long.parseLong(s)); } catch (Exception ignored) { }
        }
    }

    private static PendingIntent broadcast(Context c, String action, long id, int block, int offset, int code) {
        Intent i = new Intent(c, Receiver.class);
        i.setAction(action);
        i.putExtra("noteId", id);
        i.putExtra("block", block);
        i.putExtra("offset", offset);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @SuppressWarnings("deprecation")
    static void show(Context c, long id) {
        Context app = c.getApplicationContext();
        Note n = Store.get(app).note(id);
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (n == null || n.deleted > 0) { unpin(app, id); return; }
        if (!Notifier.canNotify(app)) return;
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, L.t("Notes pinned to the notification bar"), NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(L.t("Notes you pinned, so they're always one swipe away"));
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        String title = n.displayTitle().isEmpty() ? "Note" : n.displayTitle();

        // the rows: ☐ items you can tick here, else the first lines of the note
        RemoteViews big = new RemoteViews(app.getPackageName(), R.layout.bar_pin);
        big.setTextViewText(R.id.bar_title, "📌 " + title);
        big.removeAllViews(R.id.bar_rows);
        StringBuilder preview = new StringBuilder();
        int shown = 0;
        if (n.locked) {
            preview.append("🔒 Locked note — tap to open");
            RemoteViews row = new RemoteViews(app.getPackageName(), R.layout.bar_pin_row);
            row.setTextViewText(R.id.bar_row_text, L.t("🔒 Locked note — tap to open"));
            big.addView(R.id.bar_rows, row);
        } else {
            List<Checklist.Item> items = Checklist.items(n);
            if (!items.isEmpty()) {
                int open = 0;
                for (Checklist.Item it : items) if (!it.done) open++;
                for (Checklist.Item it : items) {
                    if (shown >= MAX_ROWS) break;
                    if (it.done && open >= MAX_ROWS) continue;   // room is short: show what's still to do
                    RemoteViews row = new RemoteViews(app.getPackageName(), R.layout.bar_pin_row);
                    row.setTextViewText(R.id.bar_row_text, (it.done ? "☑  " : "☐  ") + it.text);
                    row.setOnClickPendingIntent(R.id.bar_row_text,
                            broadcast(app, ACTION_TICK, id, it.block, it.offset, nid(id) * 31 + shown));
                    big.addView(R.id.bar_rows, row);
                    if (preview.length() > 0) preview.append("  ·  ");
                    preview.append(it.done ? "☑ " : "☐ ").append(it.text);
                    shown++;
                }
                int left = open;
                big.setTextViewText(R.id.bar_sub, L.t(left == 0 ? "All done ✓" : left + (left == 1 ? " thing left · tap ☐ to tick" : " things left · tap ☐ to tick")));
            } else {
                for (String l : n.body.split("\n")) {
                    if (l.trim().isEmpty()) continue;
                    if (shown >= MAX_ROWS) break;
                    RemoteViews row = new RemoteViews(app.getPackageName(), R.layout.bar_pin_row);
                    row.setTextViewText(R.id.bar_row_text, l.trim());
                    big.addView(R.id.bar_rows, row);
                    if (preview.length() > 0) preview.append("  ·  ");
                    preview.append(l.trim());
                    shown++;
                }
                big.setTextViewText(R.id.bar_sub, L.t("Pinned note"));
            }
        }
        big.setOnClickPendingIntent(R.id.bar_unpin, broadcast(app, ACTION_UNPIN, id, 0, 0, nid(id) * 31 + 29));

        RemoteViews small = new RemoteViews(app.getPackageName(), R.layout.bar_pin_small);
        small.setTextViewText(R.id.bar_title, "📌 " + title);
        small.setTextViewText(R.id.bar_sub, preview.length() == 0 ? L.t("Empty note") : preview.toString());

        Intent open = new Intent(app, EditorActivity.class);
        open.putExtra(EditorActivity.EXTRA_NOTE_ID, id);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(app, CHANNEL)
                : new Notification.Builder(app).setPriority(Notification.PRIORITY_LOW);
        b.setSmallIcon(R.drawable.ic_notify)
                .setColor(Ui.col(app, R.color.accent))
                .setContentTitle("📌 " + title)
                .setContentText(preview.length() == 0 ? L.t("Empty note") : preview.toString())
                .setCustomContentView(small)
                .setCustomBigContentView(big)
                .setStyle(new Notification.DecoratedCustomViewStyle())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(PendingIntent.getActivity(app, nid(id), open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setDeleteIntent(broadcast(app, ACTION_GONE, id, 0, 0, nid(id) * 31 + 30))
                .addAction(new Notification.Action.Builder(null, L.t("Unpin"), broadcast(app, ACTION_UNPIN, id, 0, 0, nid(id) * 31 + 28)).build());
        nm.notify(nid(id), b.build());
    }

    public static class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            long id = i.getLongExtra("noteId", -1);
            String a = i.getAction();
            if (ACTION_UNPIN.equals(a) || ACTION_GONE.equals(a)) {
                // swiped away (newer Android lets you swipe even pinned ones) = unpinned too
                unpin(c, id);
                return;
            }
            if (ACTION_TICK.equals(a)) {
                Checklist.toggle(c, id, i.getIntExtra("block", 0), i.getIntExtra("offset", 0));
                show(c, id);
            }
        }
    }
}
