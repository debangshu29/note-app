package com.debangshu.note;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.CalendarContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Writing screen: auto-answers math, continues lists, and finds reminders as you type. */
public class EditorActivity extends Activity {

    public static final String EXTRA_NOTE_ID = "noteId";
    private static final int REQ_PERMS = 41;

    private Store store;
    private Note note;                     // null until the first character is typed

    private EditText titleEt;
    private SmartEditText bodyEt;
    private TextView metaTv, suggestTv;
    private View suggestBar, chipsScroll;
    private LinearLayout chipsRow;
    private ImageButton pinBtn;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean selfEdit = false;
    private boolean askedPermissions = false;
    private String suggestion = null;

    private final Runnable saveTask = this::saveNow;
    private final Runnable chipsTask = this::refreshChips;
    private final Runnable syncTask = () -> {
        if (note != null && store.note(note.id) != null) ReminderSync.syncAsync(this, note.id, this::refreshChips);
    };

    private static final Pattern LIST_PREFIX = Pattern.compile("^(\\s*)(- |• |\\* |☐ |☑ |(\\d{1,3})[.)] )");

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_editor);
        store = Store.get(this);

        titleEt = findViewById(R.id.title);
        bodyEt = findViewById(R.id.body);
        metaTv = findViewById(R.id.meta);
        suggestBar = findViewById(R.id.suggest_bar);
        suggestTv = findViewById(R.id.suggest_text);
        chipsScroll = findViewById(R.id.chips_scroll);
        chipsRow = findViewById(R.id.chips_row);
        pinBtn = findViewById(R.id.btn_pin);

        long id = getIntent().getLongExtra(EXTRA_NOTE_ID, -1);
        if (id > 0) note = store.note(id);

        selfEdit = true;
        if (note != null) {
            titleEt.setText(note.title);
            bodyEt.setText(note.body);
        } else {
            String shared = getIntent().getStringExtra(Intent.EXTRA_TEXT);
            if (shared != null) bodyEt.setText(shared);
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            bodyEt.requestFocus();
        }
        selfEdit = false;
        updateMeta();
        updatePin();

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        pinBtn.setOnClickListener(v -> togglePin());
        findViewById(R.id.btn_share).setOnClickListener(v -> share());
        findViewById(R.id.btn_delete).setOnClickListener(v -> confirmDelete());
        findViewById(R.id.tool_check).setOnClickListener(v -> insertLinePrefix("☐ "));
        findViewById(R.id.tool_bullet).setOnClickListener(v -> insertLinePrefix("• "));
        findViewById(R.id.tool_time).setOnClickListener(v -> pickDateTime());
        suggestBar.setOnClickListener(v -> acceptSuggestion());

        titleEt.addTextChangedListener(new SimpleWatcher() {
            @Override public void afterTextChanged(Editable e) { if (!selfEdit) onEdited(); }
        });
        bodyEt.addTextChangedListener(bodyWatcher);
        bodyEt.setOnCursorMoved(this::updateSuggestion);
        bodyEt.setOnTouchListener(checkboxTapper);

        if (Intent.ACTION_SEND.equals(getIntent().getAction()) && bodyEt.length() > 0) onEdited();
        refreshChips();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(saveTask);
        handler.removeCallbacks(syncTask);
        saveNow();
        if (note != null) {
            if (note.isEmpty()) {                       // never keep empty notes
                store.removeNote(note.id);
            }
            ReminderSync.syncAsync(this, note.id, null);
        }
    }

    // ------------------------------------------------------------------ typing

    private void onEdited() {
        handler.removeCallbacks(saveTask);
        handler.postDelayed(saveTask, 800);
        handler.removeCallbacks(chipsTask);
        handler.postDelayed(chipsTask, 500);
        handler.removeCallbacks(syncTask);
        handler.postDelayed(syncTask, 2500);
    }

    private final TextWatcher bodyWatcher = new SimpleWatcher() {
        int start, before, count;

        @Override public void onTextChanged(CharSequence s, int st, int bf, int ct) {
            start = st; before = bf; count = ct;
        }

        @Override public void afterTextChanged(Editable e) {
            if (selfEdit) return;
            if (count > before && count > 0) {
                int end = start + count;              // just after what was typed
                if (end <= e.length()) {
                    char last = e.charAt(end - 1);
                    if (last == '=' && store.setting(Store.S_AUTO_MATH)) autoAnswer(e, end);
                    else if (last == '\n' && store.setting(Store.S_SMART_LISTS)) continueList(e, end);
                    else if (last == ' ' && store.setting(Store.S_SMART_LISTS)) checkboxShortcut(e, end);
                }
            }
            onEdited();
            updateSuggestion();
        }
    };

    /** "1+1=" -> "1+1=2" */
    private void autoAnswer(Editable e, int end) {
        if (end < e.length() && e.charAt(end) != '\n') return;  // only at the end of a line
        int eqPos = end - 1;
        String answer = MathEval.answerFor(e.subSequence(0, eqPos).toString());
        if (answer == null) return;
        String ins = (eqPos > 0 && e.charAt(eqPos - 1) == ' ') ? " " + answer : answer;
        selfEdit = true;
        e.insert(end, ins);
        selfEdit = false;
        bodyEt.setSelection(Math.min(end + ins.length(), bodyEt.length()));
    }

    /** Pressing Enter after "- item", "☐ item" or "1. item" starts the next item. */
    private void continueList(Editable e, int end) {
        int nl = end - 1;
        int lineStart = lastNewline(e, nl - 1) + 1;
        if (lineStart > nl) return;
        String prev = e.subSequence(lineStart, nl).toString();
        Matcher m = LIST_PREFIX.matcher(prev);
        if (!m.find()) return;
        String indent = m.group(1);
        String prefix = m.group(2);
        selfEdit = true;
        if (prev.trim().equals(prefix.trim())) {
            // empty item -> end the list
            e.delete(lineStart, end);
        } else {
            String next;
            if (m.group(3) != null) next = (Integer.parseInt(m.group(3)) + 1) + prefix.substring(m.group(3).length());
            else if (prefix.equals("☑ ")) next = "☐ ";
            else next = prefix;
            e.insert(end, indent + next);
        }
        selfEdit = false;
    }

    /** Typing "[] " or "[ ] " at the start of a line makes a checkbox. */
    private void checkboxShortcut(Editable e, int end) {
        int lineStart = lastNewline(e, end - 1) + 1;
        String seg = e.subSequence(lineStart, end).toString();
        String t = seg.trim();
        String box = null;
        if (t.equals("[]") || t.equals("[ ]")) box = "☐ ";
        else if (t.equalsIgnoreCase("[x]")) box = "☑ ";
        if (box == null) return;
        int indent = seg.length() - seg.replaceAll("^\\s+", "").length();
        selfEdit = true;
        e.replace(lineStart + indent, end, box);
        selfEdit = false;
    }

    private static int lastNewline(CharSequence s, int from) {
        for (int i = Math.min(from, s.length() - 1); i >= 0; i--) if (s.charAt(i) == '\n') return i;
        return -1;
    }

    /** Tapping ☐ / ☑ at the start of a line ticks it. */
    private final View.OnTouchListener checkboxTapper = new View.OnTouchListener() {
        float downX, downY;

        @Override public boolean onTouch(View v, MotionEvent ev) {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) { downX = ev.getX(); downY = ev.getY(); }
            else if (ev.getAction() == MotionEvent.ACTION_UP
                    && Math.abs(ev.getX() - downX) < 20 && Math.abs(ev.getY() - downY) < 20) {
                int off = bodyEt.getOffsetForPosition(ev.getX(), ev.getY());
                Editable e = bodyEt.getText();
                for (int p = Math.max(0, off - 1); p <= Math.min(off, e.length() - 1); p++) {
                    char c = e.charAt(p);
                    if (c != '☐' && c != '☑') continue;
                    int ls = lastNewline(e, p - 1) + 1;
                    if (!e.subSequence(ls, p).toString().trim().isEmpty()) continue;
                    // only when the finger is really on the box (left part of the line)
                    float boxRight = bodyEt.getLayout() == null ? 9999
                            : bodyEt.getLayout().getPrimaryHorizontal(p + 1) + bodyEt.getTotalPaddingLeft() + 24;
                    if (ev.getX() > boxRight) continue;
                    e.replace(p, p + 1, c == '☐' ? "☑" : "☐");
                    return false;
                }
            }
            return false;
        }
    };

    // ------------------------------------------------------------------ math suggestion bar

    private void updateSuggestion() {
        suggestion = null;
        if (store.setting(Store.S_MATH_SUGGEST) && bodyEt.hasFocus()) {
            int cur = bodyEt.getSelectionStart();
            Editable e = bodyEt.getText();
            if (cur >= 0 && cur == bodyEt.getSelectionEnd() && (cur == e.length() || e.charAt(cur) == '\n')) {
                String before = e.subSequence(0, cur).toString();
                String trimmed = before.replaceAll("[ \\t]+$", "");
                char last = trimmed.isEmpty() ? 0 : trimmed.charAt(trimmed.length() - 1);
                if (last != 0 && last != '=' && "+-*/x×÷^(".indexOf(last) < 0) {
                    suggestion = MathEval.answerFor(before);
                }
            }
        }
        if (suggestion == null) {
            suggestBar.setVisibility(View.GONE);
        } else {
            suggestTv.setText("= " + suggestion);
            suggestBar.setVisibility(View.VISIBLE);
        }
    }

    private void acceptSuggestion() {
        if (suggestion == null) return;
        int cur = bodyEt.getSelectionStart();
        Editable e = bodyEt.getText();
        boolean space = cur > 0 && e.charAt(cur - 1) == ' ';
        String ins = (space ? "= " : " = ") + suggestion;
        selfEdit = true;
        e.insert(cur, ins);
        selfEdit = false;
        bodyEt.setSelection(cur + ins.length());
        onEdited();
        updateSuggestion();
    }

    // ------------------------------------------------------------------ toolbar helpers

    private void insertLinePrefix(String prefix) {
        bodyEt.requestFocus();
        Editable e = bodyEt.getText();
        int cur = Math.max(0, bodyEt.getSelectionStart());
        int ls = lastNewline(e, cur - 1) + 1;
        String line = e.subSequence(ls, cur).toString();
        selfEdit = true;
        if (line.startsWith(prefix)) {
            e.delete(ls, ls + prefix.length());
        } else {
            Matcher m = LIST_PREFIX.matcher(line);
            if (m.find() && m.group(1).isEmpty()) e.replace(ls, ls + m.group(2).length(), prefix);
            else e.insert(ls, prefix);
        }
        selfEdit = false;
        onEdited();
    }

    /** Calendar button: pick a day and time, and write it into the note ("on 12 Nov 2026 at 5:30 pm"). */
    private void pickDateTime() {
        final java.util.Calendar c = java.util.Calendar.getInstance();
        c.add(java.util.Calendar.HOUR_OF_DAY, 1);
        new android.app.DatePickerDialog(this, (dp, y, mo, d) -> {
            c.set(y, mo, d);
            new android.app.TimePickerDialog(this, (tp, h, mi) -> {
                c.set(java.util.Calendar.HOUR_OF_DAY, h);
                c.set(java.util.Calendar.MINUTE, mi);
                String day = new java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.ENGLISH).format(c.getTime());
                String time = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.ENGLISH).format(c.getTime())
                        .toLowerCase(java.util.Locale.ENGLISH);
                Editable e = bodyEt.getText();
                int cur = Math.max(0, bodyEt.getSelectionStart());
                boolean space = cur == 0 || Character.isWhitespace(e.charAt(cur - 1));
                insertAtCursor((space ? "" : " ") + "on " + day + " at " + time);
            }, c.get(java.util.Calendar.HOUR_OF_DAY), 0,
                    android.text.format.DateFormat.is24HourFormat(this)).show();
        }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH),
                c.get(java.util.Calendar.DAY_OF_MONTH)).show();
    }

    private void insertAtCursor(String text) {
        bodyEt.requestFocus();
        int cur = Math.max(0, bodyEt.getSelectionStart());
        bodyEt.getText().insert(cur, text);
    }

    // ------------------------------------------------------------------ save

    private void saveNow() {
        String t = titleEt.getText().toString();
        String bd = bodyEt.getText().toString();
        if (note == null) {
            if (t.trim().isEmpty() && bd.trim().isEmpty()) return;
            note = new Note();
            note.id = store.newId();
            note.created = System.currentTimeMillis();
        } else if (t.equals(note.title) && bd.equals(note.body)) {
            return;
        }
        note.title = t;
        note.body = bd;
        note.updated = System.currentTimeMillis();
        store.saveNote(note);
        updateMeta();
    }

    private void updateMeta() {
        if (note == null) { metaTv.setText("New note"); return; }
        metaTv.setText("Edited " + DateUtils.getRelativeTimeSpanString(note.updated,
                System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
    }

    private void togglePin() {
        saveNow();
        if (note == null) { Toast.makeText(this, "Write something first", Toast.LENGTH_SHORT).show(); return; }
        note.pinned = !note.pinned;
        store.saveNote(note);
        updatePin();
        Toast.makeText(this, note.pinned ? "Pinned to top" : "Unpinned", Toast.LENGTH_SHORT).show();
    }

    private void updatePin() {
        pinBtn.setAlpha(note != null && note.pinned ? 1f : 0.45f);
    }

    private void share() {
        String text = (titleEt.getText() + "\n\n" + bodyEt.getText()).trim();
        if (text.isEmpty()) return;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(i, "Share note"));
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("Delete this note?")
                .setMessage("The note and its reminders will be removed from this phone.")
                .setPositiveButton("Delete", (d, w) -> {
                    handler.removeCallbacks(saveTask);
                    if (note != null) {
                        long id = note.id;
                        store.removeNote(id);
                        ReminderSync.syncAsync(this, id, null);
                    }
                    note = null;
                    selfEdit = true;
                    titleEt.setText("");
                    bodyEt.setText("");
                    selfEdit = false;
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------ reminder chips

    private void refreshChips() {
        List<ReminderSync.Found> found = ReminderSync.detect(this,
                titleEt.getText().toString(), bodyEt.getText().toString());
        Map<String, Reminder> stored = new HashMap<>();
        if (note != null) for (Reminder r : store.remindersFor(note.id)) stored.put(r.key, r);

        chipsRow.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);
        long noteId = note == null ? -1 : note.id;
        for (ReminderSync.Found f : found) {
            Reminder r = stored.get(Reminder.makeKey(noteId, f.result.title));
            View chip = inf.inflate(R.layout.item_chip, chipsRow, false);
            TextView tv = chip.findViewById(R.id.chip_text);
            String when = friendlyWhen(f.result.timeMillis, f.result.hasTime);
            tv.setText("🔔 " + f.result.title + " · " + when);
            boolean off = r != null && r.ignored;
            boolean done = r != null && r.done;
            if (off || done) {
                tv.setPaintFlags(tv.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                chip.setAlpha(0.5f);
            }
            chip.setOnClickListener(v -> chipMenu(r, f));
            chipsRow.addView(chip);
        }
        chipsScroll.setVisibility(found.isEmpty() ? View.GONE : View.VISIBLE);
        if (!found.isEmpty()) askPermissionsOnce();
    }

    private String friendlyWhen(long t, boolean hasTime) {
        String day;
        if (DateUtils.isToday(t)) day = "Today";
        else if (DateUtils.isToday(t - DateUtils.DAY_IN_MILLIS)) day = "Tomorrow";
        else day = DateUtils.formatDateTime(this, t, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_WEEKDAY
                | DateUtils.FORMAT_ABBREV_ALL | DateUtils.FORMAT_NO_YEAR);
        if (!hasTime) return day;
        return day + " " + DateUtils.formatDateTime(this, t, DateUtils.FORMAT_SHOW_TIME);
    }

    private void chipMenu(Reminder r, ReminderSync.Found f) {
        if (r == null) {
            saveNow();
            handler.removeCallbacks(syncTask);
            if (note != null) ReminderSync.syncAsync(this, note.id, this::refreshChips);
            Toast.makeText(this, "Reminder set for " + friendlyWhen(f.result.timeMillis, f.result.hasTime),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> items = new ArrayList<>();
        final String OFF = "Don't remind me", ON = "Turn reminder back on",
                DONE = "Mark as done", UNDONE = "Mark as not done", CAL = "Open in phone calendar";
        items.add(r.ignored ? ON : OFF);
        if (!r.ignored) items.add(r.done ? UNDONE : DONE);
        if (r.calendarEventId > 0) items.add(CAL);
        new AlertDialog.Builder(this)
                .setTitle(r.title)
                .setMessage(Notifier.formatWhen(this, r)
                        + (r.calendarEventId > 0 ? "\nAdded to your phone calendar" : ""))
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    String pick = items.get(which);
                    if (pick.equals(CAL)) {
                        Uri uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, r.calendarEventId);
                        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                        catch (Exception e) { Toast.makeText(this, "No calendar app found", Toast.LENGTH_SHORT).show(); }
                        return;
                    }
                    ReminderSync.runInBackground(() -> {
                        if (pick.equals(OFF)) ReminderSync.ignore(this, r);
                        else if (pick.equals(ON)) ReminderSync.unignore(this, r);
                        else if (pick.equals(DONE)) ReminderSync.setDone(this, r, true);
                        else if (pick.equals(UNDONE)) ReminderSync.setDone(this, r, false);
                    }, this::refreshChips);
                })
                .show();
    }

    // ------------------------------------------------------------------ permissions

    private void askPermissionsOnce() {
        if (askedPermissions) return;
        askedPermissions = true;
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (store.setting(Store.S_ADD_TO_CALENDAR) && !CalendarHelper.hasPermission(this)) {
            need.add(Manifest.permission.READ_CALENDAR);
            need.add(Manifest.permission.WRITE_CALENDAR);
        }
        if (need.isEmpty()) return;
        // ask at most once per install unless the user changes it in Settings
        if (store.prefs().getBoolean("asked_perms", false)) return;
        store.prefs().edit().putBoolean("asked_perms", true).apply();
        requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_PERMS) return;
        if (CalendarHelper.hasPermission(this)) {
            ReminderSync.runInBackground(() -> ReminderSync.addMissingCalendarEvents(this), this::refreshChips);
        }
    }

    // ------------------------------------------------------------------

    private abstract static class SimpleWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void afterTextChanged(Editable e) { }
    }
}
