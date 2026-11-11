package com.debangshu.note;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Home-screen widgets:
 *   Quick   – New note / Checklist / Voice / Scan buttons
 *   Today   – today's reminders, then things still to do (tap ☐ to tick)
 *   Checklist – one checklist you pick, tickable from the home screen
 *   Countdown – days left until a reminder you pick
 * With app lock on, they show "locked" instead of your notes.
 */
public final class Widgets {

    private Widgets() {}

    public static final String ACTION_TOGGLE = "com.debangshu.note.WIDGET_TOGGLE";
    static final String P_CHECKLIST = "w_cl_", P_COUNTDOWN = "w_cd_";

    public static class QuickProvider extends AppWidgetProvider {
        @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { for (int id : ids) m.updateAppWidget(id, quick(c)); }
    }

    public static class TodayProvider extends AppWidgetProvider {
        @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { for (int id : ids) m.updateAppWidget(id, today(c, id)); }
    }

    public static class ChecklistProvider extends AppWidgetProvider {
        @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { for (int id : ids) m.updateAppWidget(id, checklist(c, id)); }
        @Override public void onDeleted(Context c, int[] ids) {
            SharedPreferences.Editor e = prefs(c).edit();
            for (int id : ids) e.remove(P_CHECKLIST + id);
            e.apply();
        }
    }

    public static class CountdownProvider extends AppWidgetProvider {
        @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { for (int id : ids) m.updateAppWidget(id, countdown(c, id)); }
        @Override public void onDeleted(Context c, int[] ids) {
            SharedPreferences.Editor e = prefs(c).edit();
            for (int id : ids) e.remove(P_COUNTDOWN + id);
            e.apply();
        }
    }

    /** Ticks a checklist line from a widget. */
    public static class Actions extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            if (!ACTION_TOGGLE.equals(i.getAction())) return;
            if (Lock.appLockOn(c)) return;
            Checklist.toggle(c, i.getLongExtra("note", -1), i.getIntExtra("block", -1), i.getIntExtra("offset", -1));
            updateAll(c);
        }
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("widgets", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ refreshing

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static Context pending;
    private static final Runnable refresh = () -> { if (pending != null) updateAll(pending); };

    /** Notes or reminders changed: refresh widgets a moment later (not on every key press). */
    public static void changed(Context c) {
        pending = c.getApplicationContext();
        handler.removeCallbacks(refresh);
        handler.postDelayed(refresh, 1500);
    }

    public static void updateAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        if (m == null) return;
        try {
            for (int id : m.getAppWidgetIds(new ComponentName(c, QuickProvider.class))) m.updateAppWidget(id, quick(c));
            for (int id : m.getAppWidgetIds(new ComponentName(c, TodayProvider.class))) m.updateAppWidget(id, today(c, id));
            for (int id : m.getAppWidgetIds(new ComponentName(c, ChecklistProvider.class))) m.updateAppWidget(id, checklist(c, id));
            for (int id : m.getAppWidgetIds(new ComponentName(c, CountdownProvider.class))) m.updateAppWidget(id, countdown(c, id));
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ taps

    private static PendingIntent activity(Context c, int code, Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Opens the home screen with a quick action (new note, checklist, voice, scan, today). */
    static PendingIntent quickAction(Context c, String what, int code) {
        Intent i = new Intent(c, MainActivity.class);
        i.setAction("note.quick." + what);
        i.putExtra("quick", what);
        return activity(c, code, i);
    }

    private static PendingIntent openNote(Context c, long noteId, int code) {
        Intent i = new Intent(c, MainActivity.class);
        i.setAction("note.open." + noteId);
        i.putExtra("openNote", noteId);
        return activity(c, code, i);
    }

    private static PendingIntent toggle(Context c, Checklist.Item it, int code) {
        Intent i = new Intent(c, Actions.class);
        i.setAction(ACTION_TOGGLE);
        i.putExtra("note", it.noteId);
        i.putExtra("block", it.block);
        i.putExtra("offset", it.offset);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ------------------------------------------------------------------ the widgets

    static RemoteViews quick(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_quick);
        v.setOnClickPendingIntent(R.id.w_new, quickAction(c, "note", 9101));
        v.setOnClickPendingIntent(R.id.w_check, quickAction(c, "checklist", 9102));
        v.setOnClickPendingIntent(R.id.w_voice, quickAction(c, "voice", 9103));
        v.setOnClickPendingIntent(R.id.w_scan, quickAction(c, "scan", 9104));
        return v;
    }

    private static RemoteViews locked(Context c, String title) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_list);
        v.setTextViewText(R.id.w_title, title);
        v.setTextViewText(R.id.w_sub, "🔒 App lock is on");
        v.removeAllViews(R.id.w_rows);
        v.setViewVisibility(R.id.w_empty, View.VISIBLE);
        v.setTextViewText(R.id.w_empty, "Open Note to see it");
        v.setOnClickPendingIntent(R.id.w_root, quickAction(c, "today", 9110));
        v.setOnClickPendingIntent(R.id.w_add, quickAction(c, "note", 9111));
        return v;
    }

    private static int maxRows(Context c, int id) {
        try {
            android.os.Bundle o = AppWidgetManager.getInstance(c).getAppWidgetOptions(id);
            int h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180);
            return Math.max(2, Math.min(12, (h - 70) / 30));
        } catch (Exception e) {
            return 5;
        }
    }

    static RemoteViews today(Context c, int id) {
        if (Lock.appLockOn(c)) return locked(c, "Today");
        Store s = Store.get(c);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_list);
        v.setTextViewText(R.id.w_title, "Today");
        String day = new java.text.SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(new Date());
        List<Reminder> rem = Today.remindersToday(s);
        List<Checklist.Item> todo = Checklist.open(s);
        String sub = day;
        if (!rem.isEmpty()) sub += " · " + rem.size() + (rem.size() == 1 ? " reminder" : " reminders");
        if (!todo.isEmpty()) sub += " · " + todo.size() + " to do";
        v.setTextViewText(R.id.w_sub, sub);
        v.setOnClickPendingIntent(R.id.w_head, quickAction(c, "today", 9120));
        v.setOnClickPendingIntent(R.id.w_add, quickAction(c, "note", 9121));
        v.removeAllViews(R.id.w_rows);
        int max = maxRows(c, id), n = 0;
        java.text.DateFormat tf = DateFormat.getTimeFormat(c);
        for (Reminder r : rem) {
            if (n >= max) break;
            RemoteViews row = new RemoteViews(c.getPackageName(), R.layout.widget_row);
            row.setTextViewText(R.id.w_lead, ReminderKind.emoji(r.kind));
            row.setTextViewText(R.id.w_text, (r.hasTime ? tf.format(new Date(r.time)) + "  " : "") + r.title);
            row.setOnClickPendingIntent(R.id.w_row, openNote(c, r.noteId, 20000 + id * 40 + n));
            v.addView(R.id.w_rows, row);
            n++;
        }
        for (Checklist.Item it : todo) {
            if (n >= max) break;
            RemoteViews row = new RemoteViews(c.getPackageName(), R.layout.widget_row);
            row.setTextViewText(R.id.w_lead, "☐");
            row.setTextViewText(R.id.w_text, it.text);
            row.setOnClickPendingIntent(R.id.w_row, toggle(c, it, 20000 + id * 40 + n));
            v.addView(R.id.w_rows, row);
            n++;
        }
        boolean empty = rem.isEmpty() && todo.isEmpty();
        v.setViewVisibility(R.id.w_empty, empty ? View.VISIBLE : View.GONE);
        v.setTextViewText(R.id.w_empty, "Nothing planned for today ✨");
        return v;
    }

    static RemoteViews checklist(Context c, int id) {
        if (Lock.appLockOn(c)) return locked(c, "Checklist");
        Store s = Store.get(c);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_list);
        long noteId = prefs(c).getLong(P_CHECKLIST + id, -1);
        Note note = noteId == -1 ? null : s.note(noteId);
        v.removeAllViews(R.id.w_rows);
        v.setViewVisibility(R.id.w_add, View.GONE);
        if (note == null || note.deleted > 0 || note.locked) {
            v.setTextViewText(R.id.w_title, "Checklist");
            v.setTextViewText(R.id.w_sub, "");
            v.setViewVisibility(R.id.w_empty, View.VISIBLE);
            v.setTextViewText(R.id.w_empty, note != null && note.locked ? "That note is locked" : "Tap to pick a checklist");
            Intent pick = new Intent(c, WidgetSetupActivity.class);
            pick.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
            pick.setAction("note.pick." + id);
            v.setOnClickPendingIntent(R.id.w_root, activity(c, 9130 + id, pick));
            return v;
        }
        List<Checklist.Item> items = Checklist.items(note);
        int left = 0;
        for (Checklist.Item it : items) if (!it.done) left++;
        v.setTextViewText(R.id.w_title, note.displayTitle());
        v.setTextViewText(R.id.w_sub, items.isEmpty() ? "No checkboxes yet" : left == 0 ? "All done ✓" : left + " of " + items.size() + " left");
        v.setOnClickPendingIntent(R.id.w_head, openNote(c, note.id, 30000 + id));
        // unticked first, then ticked
        List<Checklist.Item> order = new ArrayList<>();
        for (Checklist.Item it : items) if (!it.done) order.add(it);
        for (Checklist.Item it : items) if (it.done) order.add(it);
        int max = maxRows(c, id), n = 0;
        for (Checklist.Item it : order) {
            if (n >= max) break;
            RemoteViews row = new RemoteViews(c.getPackageName(), R.layout.widget_row);
            row.setTextViewText(R.id.w_lead, it.done ? "☑" : "☐");
            row.setTextViewText(R.id.w_text, it.text);
            if (it.done) row.setTextColor(R.id.w_text, c.getColor(R.color.hint));
            row.setOnClickPendingIntent(R.id.w_row, toggle(c, it, 30000 + id * 40 + n + 1));
            v.addView(R.id.w_rows, row);
            n++;
        }
        v.setViewVisibility(R.id.w_empty, items.isEmpty() ? View.VISIBLE : View.GONE);
        v.setTextViewText(R.id.w_empty, "Add ☐ items to this note");
        return v;
    }

    static RemoteViews countdown(Context c, int id) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_countdown);
        long remId = prefs(c).getLong(P_COUNTDOWN + id, -1);
        Reminder r = remId == -1 ? null : Store.get(c).reminder(remId);
        if (Lock.appLockOn(c)) {
            v.setTextViewText(R.id.w_days, "🔒");
            v.setTextViewText(R.id.w_unit, "");
            v.setTextViewText(R.id.w_title, "Note is locked");
            v.setTextViewText(R.id.w_date, "");
            v.setOnClickPendingIntent(R.id.w_root, quickAction(c, "today", 9140));
            return v;
        }
        if (r == null) {
            v.setTextViewText(R.id.w_days, "⏳");
            v.setTextViewText(R.id.w_unit, "");
            v.setTextViewText(R.id.w_title, "Tap to pick a date");
            v.setTextViewText(R.id.w_date, "from your reminders");
            Intent pick = new Intent(c, WidgetSetupActivity.class);
            pick.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
            pick.setAction("note.pick." + id);
            v.setOnClickPendingIntent(R.id.w_root, activity(c, 9150 + id, pick));
            return v;
        }
        int days = daysUntil(r.time);
        if (days < 0) { v.setTextViewText(R.id.w_days, "✓"); v.setTextViewText(R.id.w_unit, "done"); }
        else if (days == 0) { v.setTextViewText(R.id.w_days, "Today"); v.setTextViewText(R.id.w_unit, ""); }
        else { v.setTextViewText(R.id.w_days, String.valueOf(days)); v.setTextViewText(R.id.w_unit, days == 1 ? "day to go" : "days to go"); }
        v.setTextViewText(R.id.w_title, r.title);
        v.setTextViewText(R.id.w_date, new java.text.SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(new Date(r.time)));
        v.setOnClickPendingIntent(R.id.w_root, openNote(c, r.noteId, 40000 + id));
        return v;
    }

    /** Whole days from today to that date (0 = today, negative = passed). */
    public static int daysUntil(long time) {
        Calendar a = Calendar.getInstance();
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(time);
        for (Calendar x : new Calendar[]{a, b}) {
            x.set(Calendar.HOUR_OF_DAY, 12);
            x.set(Calendar.MINUTE, 0);
            x.set(Calendar.SECOND, 0);
            x.set(Calendar.MILLISECOND, 0);
        }
        return (int) Math.round((b.getTimeInMillis() - a.getTimeInMillis()) / 86_400_000.0);
    }
}
