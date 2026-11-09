package com.debangshu.note;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** Home screen: all notes in one place, plus a "Reminders" tab with everything coming up. */
public class MainActivity extends Activity {

    private Store store;
    private ListView list;
    private TextView emptyTv, tabNotes, tabReminders;
    private EditText search;
    private boolean remindersTab = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        store = Store.get(this);
        Notifier.ensureChannel(this);
        ReminderSync.runInBackground(() -> ReminderSync.rescheduleAll(this), null);
        Updater.scheduleBackgroundChecks(this);

        list = findViewById(R.id.list);
        emptyTv = findViewById(R.id.empty);
        search = findViewById(R.id.search);
        tabNotes = findViewById(R.id.tab_notes);
        tabReminders = findViewById(R.id.tab_reminders);

        findViewById(R.id.fab).setOnClickListener(v -> startActivity(new Intent(this, EditorActivity.class)));
        findViewById(R.id.btn_settings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        tabNotes.setOnClickListener(v -> { remindersTab = false; refresh(); });
        tabReminders.setOnClickListener(v -> { remindersTab = true; refresh(); });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void afterTextChanged(Editable s) { refresh(); }
        });

        if ("reminders".equals(getIntent().getStringExtra("tab"))) remindersTab = true;

        if (!store.prefs().getBoolean("welcomed", false)) {
            store.prefs().edit().putBoolean("welcomed", true).apply();
            HelpDialog.show(this);
        } else {
            Updater.showWhatsNewOnce(this);
        }
        Updater.maybePrompt(this, getIntent().getBooleanExtra("showUpdate", false));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("showUpdate", false)) Updater.maybePrompt(this, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        // the editor saves reminders in the background; refresh again shortly
        list.postDelayed(this::refresh, 700);
    }

    private void refresh() {
        tabNotes.setSelected(!remindersTab);
        tabReminders.setSelected(remindersTab);
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        if (remindersTab) showReminders(q); else showNotes(q);
    }

    // ------------------------------------------------------------------ notes tab

    private void showNotes(String q) {
        List<Note> all = store.notes();
        List<Note> shown = new ArrayList<>();
        for (Note n : all) {
            if (q.isEmpty() || (n.title + "\n" + n.body).toLowerCase(Locale.ROOT).contains(q)) shown.add(n);
        }
        list.setAdapter(new NotesAdapter(shown));
        list.setOnItemClickListener((p, v, pos, id) -> open(shown.get(pos).id));
        list.setOnItemLongClickListener((p, v, pos, id) -> { noteMenu(shown.get(pos)); return true; });
        emptyTv.setText(all.isEmpty()
                ? "No notes yet.\nTap + to write your first note."
                : "Nothing matches \"" + q + "\"");
        emptyTv.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void open(long noteId) {
        Intent i = new Intent(this, EditorActivity.class);
        i.putExtra(EditorActivity.EXTRA_NOTE_ID, noteId);
        startActivity(i);
    }

    private void noteMenu(Note n) {
        String[] items = {n.pinned ? "Unpin" : "Pin to top", "Share", "Delete"};
        new AlertDialog.Builder(this)
                .setTitle(n.displayTitle())
                .setItems(items, (d, which) -> {
                    if (which == 0) { n.pinned = !n.pinned; store.saveNote(n); refresh(); }
                    else if (which == 1) {
                        Intent i = new Intent(Intent.ACTION_SEND);
                        i.setType("text/plain");
                        i.putExtra(Intent.EXTRA_TEXT, (n.title + "\n\n" + n.body).trim());
                        startActivity(Intent.createChooser(i, "Share note"));
                    } else confirmDelete(n);
                })
                .show();
    }

    private void confirmDelete(Note n) {
        new AlertDialog.Builder(this)
                .setTitle("Delete this note?")
                .setMessage("\"" + n.displayTitle() + "\" and its reminders will be removed from this phone.")
                .setPositiveButton("Delete", (d, w) -> {
                    store.removeNote(n.id);
                    ReminderSync.syncAsync(this, n.id, this::refresh);
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private int reminderCount(long noteId) {
        int c = 0;
        long now = System.currentTimeMillis();
        for (Reminder r : store.remindersFor(noteId)) if (!r.done && !r.ignored && r.time > now) c++;
        return c;
    }

    private class NotesAdapter extends BaseAdapter {
        final List<Note> items;
        NotesAdapter(List<Note> items) { this.items = items; }
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int p) { return items.get(p); }
        @Override public long getItemId(int p) { return items.get(p).id; }

        @Override public View getView(int pos, View v, ViewGroup parent) {
            if (v == null) v = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_note, parent, false);
            Note n = items.get(pos);
            ((TextView) v.findViewById(R.id.note_title)).setText(n.displayTitle());
            String preview = n.body == null ? "" : n.body.trim();
            if (n.title == null || n.title.trim().isEmpty()) {
                int nl = preview.indexOf('\n');
                preview = nl >= 0 ? preview.substring(nl + 1).trim() : "";
            }
            TextView pv = v.findViewById(R.id.note_preview);
            pv.setText(preview);
            pv.setVisibility(preview.isEmpty() ? View.GONE : View.VISIBLE);
            int rc = reminderCount(n.id);
            String meta = DateUtils.getRelativeTimeSpanString(n.updated, System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS).toString();
            if (rc > 0) meta += "   🔔 " + rc;
            ((TextView) v.findViewById(R.id.note_meta)).setText(meta);
            v.findViewById(R.id.note_pin).setVisibility(n.pinned ? View.VISIBLE : View.GONE);
            return v;
        }
    }

    // ------------------------------------------------------------------ reminders tab

    private void showReminders(String q) {
        long now = System.currentTimeMillis();
        List<Object> rows = new ArrayList<>();
        List<Reminder> overdue = new ArrayList<>(), done = new ArrayList<>(), upcoming = new ArrayList<>();
        for (Reminder r : store.reminders()) {
            if (r.ignored) continue;
            if (!q.isEmpty() && !(r.title + " " + r.sourceLine).toLowerCase(Locale.ROOT).contains(q)) continue;
            if (r.done) done.add(r);
            else if (r.time < now) overdue.add(r);
            else upcoming.add(r);
        }
        if (!overdue.isEmpty()) { rows.add("Missed"); rows.addAll(overdue); }
        String lastHeader = null;
        for (Reminder r : upcoming) {
            String h = dayHeader(r.time);
            if (!h.equals(lastHeader)) { rows.add(h); lastHeader = h; }
            rows.add(r);
        }
        if (!done.isEmpty()) {
            rows.add("Done");
            for (int i = done.size() - 1; i >= 0 && i >= done.size() - 30; i--) rows.add(done.get(i));
        }
        list.setAdapter(new RemindersAdapter(rows));
        list.setOnItemClickListener((p, v, pos, id) -> {
            Object o = rows.get(pos);
            if (o instanceof Reminder) open(((Reminder) o).noteId);
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            Object o = rows.get(pos);
            if (o instanceof Reminder) reminderMenu((Reminder) o);
            return true;
        });
        emptyTv.setText("No reminders yet.\n\nWrite things like\n“call mom tomorrow at 5pm”\n“submit form on 15 Nov”\nin any note and they show up here automatically.");
        emptyTv.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private String dayHeader(long t) {
        if (DateUtils.isToday(t)) return "Today";
        if (DateUtils.isToday(t - DateUtils.DAY_IN_MILLIS)) return "Tomorrow";
        Calendar c = Calendar.getInstance();
        int year = c.get(Calendar.YEAR);
        c.setTimeInMillis(t);
        int flags = DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_WEEKDAY;
        if (c.get(Calendar.YEAR) == year) flags |= DateUtils.FORMAT_NO_YEAR;
        return DateUtils.formatDateTime(this, t, flags);
    }

    private void reminderMenu(Reminder r) {
        String[] items = {"Open note", r.done ? "Mark as not done" : "Mark as done", "Delete reminder"};
        new AlertDialog.Builder(this)
                .setTitle(r.title)
                .setItems(items, (d, which) -> {
                    if (which == 0) open(r.noteId);
                    else if (which == 1) ReminderSync.runInBackground(() -> ReminderSync.setDone(this, r, !r.done), this::refresh);
                    else ReminderSync.runInBackground(() -> ReminderSync.ignore(this, r), this::refresh);
                })
                .show();
    }

    private class RemindersAdapter extends BaseAdapter {
        final List<Object> rows;
        RemindersAdapter(List<Object> rows) { this.rows = rows; }
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int p) { return rows.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int p) { return rows.get(p) instanceof String ? 0 : 1; }
        @Override public boolean isEnabled(int p) { return rows.get(p) instanceof Reminder; }

        @Override public View getView(int pos, View v, ViewGroup parent) {
            Object o = rows.get(pos);
            LayoutInflater inf = LayoutInflater.from(MainActivity.this);
            if (o instanceof String) {
                if (v == null) v = inf.inflate(R.layout.item_header, parent, false);
                ((TextView) v).setText((String) o);
                return v;
            }
            if (v == null) v = inf.inflate(R.layout.item_reminder, parent, false);
            Reminder r = (Reminder) o;
            TextView title = v.findViewById(R.id.rem_title);
            TextView sub = v.findViewById(R.id.rem_sub);
            CheckBox cb = v.findViewById(R.id.rem_check);
            title.setText(ReminderKind.emoji(r.kind) + "  " + r.title);
            Note n = store.note(r.noteId);
            String when = r.hasTime ? DateUtils.formatDateTime(MainActivity.this, r.time, DateUtils.FORMAT_SHOW_TIME) : "Any time";
            if (r.time < System.currentTimeMillis() || r.done) when = Notifier.formatWhen(MainActivity.this, r);
            sub.setText(when + (n != null ? "  ·  " + n.displayTitle() : ""));
            if (r.done) title.setPaintFlags(title.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            else title.setPaintFlags(title.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            boolean missed = !r.done && r.time < System.currentTimeMillis();
            sub.setTextColor(getColor(missed ? R.color.danger : R.color.text2));
            v.setAlpha(r.done ? 0.55f : 1f);
            cb.setOnCheckedChangeListener(null);
            cb.setChecked(r.done);
            cb.setOnCheckedChangeListener((btn, checked) ->
                    ReminderSync.runInBackground(() -> ReminderSync.setDone(MainActivity.this, r, checked),
                            MainActivity.this::refresh));
            return v;
        }
    }
}
