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
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** Home screen: all notes in one place, plus a "Reminders" tab with everything coming up. */
public class MainActivity extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(Ui.wrap(base)); }

    private Store store;
    private ListView list;
    private android.widget.GridView grid;
    private String labelFilter = null;          // show only notes with this #label
    private TextView emptyTv, tabNotes, tabReminders;
    private EditText search;
    private boolean remindersTab = false;
    private android.view.ActionMode actionMode;     // non-null while notes are being selected
    private List<Note> shownNotes = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.install(this);
        setContentView(R.layout.activity_main);
        Tips.applyAll(findViewById(android.R.id.content));
        store = Store.get(this);
        Notifier.ensureChannel(this);
        ReminderSync.runInBackground(() -> ReminderSync.rescheduleAll(this), null);
        Updater.scheduleBackgroundChecks(this);
        Updater.cleanUpAfterUpdate(this);
        ReminderSync.runInBackground(() -> Media.deleteUnused(this, store.usedMedia()), null);
        ReminderSync.runInBackground(() -> store.purgeTrash(), null);   // recycle bin keeps notes 30 days
        ReminderSync.runInBackground(() -> { Habits.schedule(this); Weekly.schedule(this); BarPins.refreshAll(this); }, null);
        PhotoText.indexAsync(this);
        Geo.syncAsync(this);
        ReminderSync.runInBackground(() -> {
            int n = RollOver.run(this);
            if (n > 0) runOnUiThread(() -> Ui.toast(this,
                    n + (n == 1 ? " unfinished to-do" : " unfinished to-dos") + " moved to " + RollOver.todayTitle(System.currentTimeMillis()),
                    android.widget.Toast.LENGTH_LONG).show());
        }, this::refresh);

        list = findViewById(R.id.list);
        grid = findViewById(R.id.grid);
        emptyTv = findViewById(R.id.empty);
        search = findViewById(R.id.search);
        tabNotes = findViewById(R.id.tab_notes);
        tabReminders = findViewById(R.id.tab_reminders);

        findViewById(R.id.fab).setOnClickListener(v -> startActivity(new Intent(this, EditorActivity.class)));
        findViewById(R.id.fab).setOnLongClickListener(v -> { Templates.pick(this); return true; });
        Tips.set(findViewById(R.id.fab), "New note (long-press for templates)");
        findViewById(R.id.btn_view).setOnClickListener(v -> viewMenu());
        findViewById(R.id.btn_settings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btn_import).setOnClickListener(v -> pickImport());
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
        if (getIntent().getBooleanExtra("showUpdate", false)) Updater.maybePrompt(this, true);
        Brief.schedule(this);
        handleQuick(getIntent());
        findViewById(R.id.today_chip).setOnClickListener(v -> startActivity(new Intent(this, TodayActivity.class)));
        findViewById(R.id.features_chip).setOnClickListener(v -> startActivity(new Intent(this, FeaturesActivity.class)));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("showUpdate", false)) Updater.maybePrompt(this, true);
        handleQuick(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.refreshTheme(this)) return;
        if (Lock.guard(this)) return;
        refresh();
        Updater.maybePrompt(this, false);       // every time Note is opened (at most every 15 min)
        // the editor saves reminders in the background; refresh again shortly
        list.postDelayed(this::refresh, 700);
    }

    private void refresh() {
        if (actionMode != null) return;               // don't lose the selection
        tabNotes.setSelected(!remindersTab);
        tabReminders.setSelected(remindersTab);
        float d = getResources().getDisplayMetrics().density;
        tabNotes.setElevation(remindersTab ? 0 : 2 * d);
        tabReminders.setElevation(remindersTab ? 2 * d : 0);
        paintHeader();
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        if (remindersTab) showReminders(q); else showNotes(q);
    }

    /** Home-screen shortcuts, widgets, the quick-settings tile and the morning brief all come in here. */
    private void handleQuick(Intent in) {
        if (in == null) return;
        String q = in.getStringExtra("quick");
        long openId = in.getLongExtra("openNote", -1);
        in.removeExtra("quick");
        in.removeExtra("openNote");
        if (openId != -1) { open(openId); return; }
        if (q == null) return;
        if (q.equals("today")) { startActivity(new Intent(this, TodayActivity.class)); return; }
        if (q.equals("features")) { startActivity(new Intent(this, FeaturesActivity.class)); return; }
        if (q.equals("search")) {
            EditText s = findViewById(R.id.search);
            s.requestFocus();
            s.setHint(L.t("Search notes, words in photos, file names…"));
            s.postDelayed(() -> {
                android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (im != null) im.showSoftInput(s, 0);
            }, 250);
            return;
        }
        if (q.equals("money")) { startActivity(new Intent(this, MoneyActivity.class)); return; }
        if (q.equals("calendar")) { startActivity(new Intent(this, CalendarActivity.class)); return; }
        if (q.equals("habits")) { startActivity(new Intent(this, HabitsActivity.class)); return; }
        if (q.equals("study")) { startActivity(new Intent(this, StudyActivity.class)); return; }
        Intent e = new Intent(this, EditorActivity.class);
        e.putExtra(EditorActivity.EXTRA_QUICK, q);
        startActivity(e);
    }

    /** Greeting with today's date, and how many notes / upcoming reminders there are on the tabs. */
    private void paintHeader() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String hello = h < 5 ? "Good night" : h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : h < 21 ? "Good evening" : "Good night";
        String day = DateUtils.formatDateTime(this, System.currentTimeMillis(),
                DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_NO_YEAR | DateUtils.FORMAT_ABBREV_ALL);
        ((TextView) findViewById(R.id.greeting)).setText(hello + "  ·  " + day);
        int notes = store.notes().size();
        int upcoming = 0;
        long now = System.currentTimeMillis();
        for (Reminder r : store.reminders()) if (!r.done && !r.ignored && r.time > now) upcoming++;
        tabNotes.setText(tabLabel("All notes", notes));
        List<Reminder> todayRem = Today.remindersToday(store);
        int todo = Checklist.open(store).size();
        StringBuilder t = new StringBuilder("☀  Today");
        if (todayRem.isEmpty() && todo == 0) t.append("  ·  nothing planned");
        if (!todayRem.isEmpty()) t.append("  ·  ").append(todayRem.size()).append(todayRem.size() == 1 ? " reminder" : " reminders");
        if (todo > 0) t.append("  ·  ").append(todo).append(" to do");
        ((TextView) findViewById(R.id.today_chip)).setText(t.append("   ›").toString());
        tabReminders.setText(tabLabel("Reminders", upcoming));
    }

    private CharSequence tabLabel(String name, int count) {
        name = L.t(name);
        if (count <= 0) return name;
        android.text.SpannableString s = new android.text.SpannableString(name + "  " + count);
        int at = name.length() + 2;
        s.setSpan(new android.text.style.ForegroundColorSpan(Ui.col(this, R.color.accent)), at, s.length(), 0);
        s.setSpan(new android.text.style.RelativeSizeSpan(0.9f), at, s.length(), 0);
        return s;
    }

    // ------------------------------------------------------------------ notes tab

    private static final String P_GRID = "view_grid", P_SORT = "sort_notes";
    private static final String[] SORT_NAMES = {"Last edited", "Date created", "Title (A–Z)", "Colour"};

    private android.widget.AbsListView notesView() {
        return store.prefs().getBoolean(P_GRID, false) ? grid : list;
    }

    private void showNotes(String q) {
        List<Note> all = store.notes();
        List<String[]> labels = store.labels();
        labels.removeIf(l -> l[0].equals(WalletActivity.TAG));   // the wallet has its own locked screen
        boolean filterOk = false;
        for (String[] l : labels) if (l[0].equals(labelFilter)) filterOk = true;
        if (!filterOk) labelFilter = null;
        paintLabels(labels);
        List<Note> shown = new ArrayList<>();
        int archivedCount = 0;
        for (Note n : all) if (n.archived && !n.tags().contains(WalletActivity.TAG)) archivedCount++;
        View archRow = findViewById(R.id.archived_row);
        archRow.setVisibility(archivedCount > 0 && q.isEmpty() && labelFilter == null ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.archived_count)).setText(String.valueOf(archivedCount));
        archRow.setOnClickListener(v -> startActivity(new Intent(this, ArchiveActivity.class)));
        for (Note n : all) {
            if (n.tags().contains(WalletActivity.TAG)) continue;   // documents live in the wallet
            if (n.archived && q.isEmpty()) continue;               // archived: only in Archived and in search
            String hay = n.locked ? n.title : n.title + "\n" + n.body;
            if (!q.isEmpty() && !hay.toLowerCase(Locale.ROOT).contains(q)
                    && (n.locked || !PhotoText.forNote(this, n).contains(q))) continue;
            if (labelFilter != null && !n.tags().contains(labelFilter)) continue;
            shown.add(n);
        }
        sortNotes(shown);
        shownNotes = shown;
        android.widget.AbsListView v = notesView();
        android.widget.AbsListView other = v == grid ? list : grid;
        other.setVisibility(View.GONE);
        other.setAdapter(null);
        v.setVisibility(View.VISIBLE);
        v.setOnItemLongClickListener(null);
        v.setChoiceMode(android.widget.AbsListView.CHOICE_MODE_MULTIPLE_MODAL);   // long-press = select notes
        v.setMultiChoiceModeListener(selection);
        if (v == grid) grid.setAdapter(new NotesAdapter(shown, true));
        else list.setAdapter(new NotesAdapter(shown, false));
        v.setOnItemClickListener((p, view, pos, id) -> open(shown.get(pos).id));
        emptyTv.setText(all.isEmpty()
                ? "Nothing here yet\nTap New note to write your first one.\nLong-press it for templates."
                : labelFilter != null && q.isEmpty() ? "No notes with #" + labelFilter
                : "Nothing matches \"" + q + "\"");
        emptyTv.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void sortNotes(List<Note> l) {
        int sort = store.prefs().getInt(P_SORT, 0);
        java.util.Collections.sort(l, (a, b) -> {
            if (a.pinned != b.pinned) return a.pinned ? -1 : 1;
            switch (sort) {
                case 1: return Long.compare(b.created, a.created);
                case 2: return a.displayTitle().compareToIgnoreCase(b.displayTitle());
                case 3: {
                    int ka = a.customColor != 0 ? 1000 : a.color, kb = b.customColor != 0 ? 1000 : b.color;
                    if (ka != kb) return ka == 0 ? 1 : kb == 0 ? -1 : Integer.compare(ka, kb);
                    return Long.compare(b.updated, a.updated);
                }
                default: return Long.compare(b.updated, a.updated);
            }
        });
    }

    /** Chips under the tabs: All, #work 3, #shopping 2 … tap one to see only those notes. */
    private void paintLabels(List<String[]> labels) {
        View scroll = findViewById(R.id.label_scroll);
        LinearLayout row = findViewById(R.id.label_chips);
        row.removeAllViews();
        if (labels.isEmpty() || remindersTab) { scroll.setVisibility(View.GONE); return; }
        scroll.setVisibility(View.VISIBLE);
        row.addView(labelChip("All", null, labelFilter == null));
        for (String[] l : labels) row.addView(labelChip("#" + l[0] + "  " + l[1], l[0], l[0].equals(labelFilter)));
    }

    private View labelChip(String text, String tag, boolean selected) {
        float d = getResources().getDisplayMetrics().density;
        TextView t = new Ui.T(this);
        t.setText(text);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(Ui.face(this, Ui.SEMIBOLD));
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding((int) (14 * d), 0, (int) (14 * d), 0);
        android.graphics.drawable.GradientDrawable g = Ui.round(selected ? Ui.col(this, R.color.accent) : Ui.col(this, R.color.surface), 16 * d);
        if (!selected) g.setStroke((int) Math.max(1, d), Ui.col(this, R.color.divider));
        t.setBackground(Ui.ripple(g));
        t.setTextColor(selected ? getColor(R.color.on_accent) : getColor(R.color.text2));
        t.setOnClickListener(v -> { labelFilter = tag == null || tag.equals(labelFilter) ? null : tag; refresh(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, (int) (34 * d));
        lp.setMargins(0, 0, (int) (8 * d), 0);
        t.setLayoutParams(lp);
        return t;
    }

    /** View (list / grid), sort order and the recycle bin. */
    private void viewMenu() {
        boolean gridMode = store.prefs().getBoolean(P_GRID, false);
        int sort = store.prefs().getInt(P_SORT, 0);
        int binCount = store.trash().size();
        List<CharSequence> items = new ArrayList<>();
        items.add(gridMode ? "☰   Show as a list" : "▦   Show as a grid (2 columns)");
        for (int i = 0; i < SORT_NAMES.length; i++) items.add((i == sort ? "✓   " : "      ") + "Sort by " + SORT_NAMES[i].toLowerCase(Locale.ROOT));
        items.add("🗑   Recycle bin" + (binCount > 0 ? "  (" + binCount + ")" : ""));
        items.add("📋   New note from a template");
        items.add("✨   All features (by category)");
        items.add("📅   Calendar");
        items.add("✅   Habits");
        items.add("🎴   Study (flashcards & quiz)");
        items.add("💰   Money (spending, lend & borrow, bills)");
        items.add("🗂   Document wallet (locked)");
        items.add("📍   Places (for place reminders)");
        Class<?>[] screens = {CalendarActivity.class, HabitsActivity.class, StudyActivity.class, MoneyActivity.class,
                WalletActivity.class, PlacesActivity.class};
        new Ui.Popup(this).setTitle("View & tools").setItems(items.toArray(new CharSequence[0]), (d, w) -> {
            if (w == 0) store.prefs().edit().putBoolean(P_GRID, !gridMode).apply();
            else if (w <= SORT_NAMES.length) store.prefs().edit().putInt(P_SORT, w - 1).apply();
            else if (w == SORT_NAMES.length + 1) { startActivity(new Intent(this, TrashActivity.class)); return; }
            else if (w == SORT_NAMES.length + 2) { Templates.pick(this); return; }
            else if (w == SORT_NAMES.length + 3) { startActivity(new Intent(this, FeaturesActivity.class)); return; }
            else { startActivity(new Intent(this, screens[w - SORT_NAMES.length - 4])); return; }
            refresh();
        }).show();
    }

    // ------------------------------------------------------------------ make a note from a file

    private static final int REQ_IMPORT = 51;

    private void pickImport() {
        String[] items = {"📷  Photo or screenshot", "📄  PDF", "📝  Text file", "📱  Scan a QR code (a note from another phone)"};
        String[] types = {"image/*", "application/pdf", "text/*"};
        new Ui.Popup(this)
                .setTitle("Make a note from…")
                .setItems(items, (d, which) -> {
                    if (which == 3) { QrShare.scan(this); return; }
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType(types[which]);
                    try { startActivityForResult(i, REQ_IMPORT); }
                    catch (Exception e) { Ui.toast(this, "No file picker found", Toast.LENGTH_SHORT).show(); }
                })
                .show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        Updater.onCleanupResult(this, req, res, data);
        if (req != REQ_IMPORT || res != RESULT_OK || data == null || data.getData() == null) return;
        Intent i = new Intent(this, ImportActivity.class);
        i.setAction(Intent.ACTION_SEND);
        i.setType(getContentResolver().getType(data.getData()));
        i.putExtra(Intent.EXTRA_STREAM, data.getData());
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(i);
    }

    private void open(long noteId) {
        Note n = store.note(noteId);
        if (n != null && n.locked && !Lock.isOpen(noteId)) {
            Lock.verify(this, "Locked note", ok -> { if (ok) { Lock.markOpen(noteId); open(noteId); } });
            return;
        }
        Intent i = new Intent(this, EditorActivity.class);
        i.putExtra(EditorActivity.EXTRA_NOTE_ID, noteId);
        startActivity(i);
    }

    // ------------------------------------------------------------------ selecting several notes

    private List<Note> selectedNotes() {
        List<Note> out = new ArrayList<>();
        android.util.SparseBooleanArray checked = notesView().getCheckedItemPositions();
        if (checked == null) return out;
        for (int i = 0; i < checked.size(); i++) {
            int pos = checked.keyAt(i);
            if (checked.valueAt(i) && pos < shownNotes.size()) out.add(shownNotes.get(pos));
        }
        return out;
    }

    private static final int M_SHARE = 1, M_PIN = 2, M_DELETE = 3, M_ALL = 4, M_ARCHIVE = 5, M_BAR = 6;

    private final android.widget.AbsListView.MultiChoiceModeListener selection =
            new android.widget.AbsListView.MultiChoiceModeListener() {
        @Override
        public boolean onCreateActionMode(android.view.ActionMode mode, android.view.Menu menu) {
            actionMode = mode;
            menu.add(0, M_SHARE, 0, L.t("Share")).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);
            menu.add(0, M_PIN, 1, L.t("Pin")).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM);
            menu.add(0, M_DELETE, 2, L.t("Delete")).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM);
            menu.add(0, M_ARCHIVE, 2, L.t("Archive")).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM);
            menu.add(0, M_BAR, 3, L.t("Pin to notification bar")).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
            menu.add(0, M_ALL, 3, L.t("Select all")).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(android.view.ActionMode mode, android.view.Menu menu) { return false; }

        @Override
        public void onItemCheckedStateChanged(android.view.ActionMode mode, int position, long id, boolean checked) {
            List<Note> sel = selectedNotes();
            mode.setTitle(L.t(sel.size() + " selected"));
            boolean allPinned = !sel.isEmpty();
            for (Note n : sel) if (!n.pinned) allPinned = false;
            android.view.MenuItem pin = mode.getMenu().findItem(M_PIN);
            if (pin != null) pin.setTitle(L.t(allPinned ? "Unpin" : "Pin"));
            boolean allArch = !sel.isEmpty(), allBar = !sel.isEmpty();
            for (Note n : sel) { if (!n.archived) allArch = false; if (!BarPins.isPinned(MainActivity.this, n.id)) allBar = false; }
            android.view.MenuItem ar = mode.getMenu().findItem(M_ARCHIVE);
            if (ar != null) ar.setTitle(L.t(allArch ? "Unarchive" : "Archive"));
            android.view.MenuItem bar = mode.getMenu().findItem(M_BAR);
            if (bar != null) bar.setTitle(L.t(allBar ? "Unpin from notification bar" : "Pin to notification bar"));
        }

        @Override
        public boolean onActionItemClicked(android.view.ActionMode mode, android.view.MenuItem item) {
            List<Note> sel = selectedNotes();
            switch (item.getItemId()) {
                case M_SHARE:
                    mode.finish();
                    ShareHelper.show(MainActivity.this, sel);
                    return true;
                case M_PIN: {
                    boolean allPinned = true;
                    for (Note n : sel) if (!n.pinned) allPinned = false;
                    for (Note n : sel) { n.pinned = !allPinned; store.saveNote(n); }
                    mode.finish();
                    return true;
                }
                case M_DELETE:
                    confirmDelete(sel, mode);
                    return true;
                case M_ARCHIVE: {
                    boolean allArchived = true;
                    for (Note n : sel) if (!n.archived) allArchived = false;
                    for (Note n : sel) { n.archived = !allArchived; store.saveNote(n); }
                    Ui.toast(MainActivity.this, allArchived ? "Back on the home screen"
                            : (sel.size() == 1 ? "Archived" : sel.size() + " notes archived") + " — see them under 📦 Archived",
                            Toast.LENGTH_SHORT).show();
                    mode.finish();
                    return true;
                }
                case M_BAR: {
                    boolean allOn = true;
                    for (Note n : sel) if (!BarPins.isPinned(MainActivity.this, n.id)) allOn = false;
                    for (Note n : sel) { if (allOn) BarPins.unpin(MainActivity.this, n.id); else BarPins.pin(MainActivity.this, n.id); }
                    if (!allOn && !Notifier.canNotify(MainActivity.this) && android.os.Build.VERSION.SDK_INT >= 33)
                        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 9);
                    Ui.toast(MainActivity.this, allOn ? "Unpinned from the notification bar" : "Pinned to the notification bar",
                            Toast.LENGTH_SHORT).show();
                    mode.finish();
                    return true;
                }
                case M_ALL:
                    android.widget.AbsListView v = notesView();
                    for (int i = 0; i < v.getCount(); i++) v.setItemChecked(i, true);
                    return true;
            }
            return false;
        }

        @Override
        public void onDestroyActionMode(android.view.ActionMode mode) {
            actionMode = null;
            list.post(MainActivity.this::refresh);
        }
    };

    private void confirmDelete(List<Note> notes, android.view.ActionMode mode) {
        if (notes.isEmpty()) return;
        String what = notes.size() == 1 ? "\"" + notes.get(0).displayTitle() + "\"" : notes.size() + " notes";
        new Ui.Popup(this)
                .setTitle(notes.size() == 1 ? "Delete this note?" : "Delete " + notes.size() + " notes?")
                .setMessage(what + " will move to the recycle bin for 30 days. Their reminders stop.")
                .setPositiveButton("Delete", (d, w) -> {
                    for (Note n : notes) {
                        store.trashNote(n.id);
                        ReminderSync.syncAsync(this, n.id, null);
                    }
                    if (mode != null) mode.finish();
                    Ui.toast(this, notes.size() == 1 ? "Moved to the recycle bin" : notes.size() + " notes moved to the recycle bin",
                            Toast.LENGTH_SHORT).show();
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
        final boolean gridMode;
        NotesAdapter(List<Note> items, boolean gridMode) { this.items = items; this.gridMode = gridMode; }
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
            if (n.locked) preview = "🔒  Locked note";
            pv.setMaxLines(gridMode ? 9 : 4);
            pv.setText(preview);
            pv.setVisibility(preview.isEmpty() ? View.GONE : View.VISIBLE);
            int rc = reminderCount(n.id);
            String meta = DateUtils.getRelativeTimeSpanString(n.updated, System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS).toString();
            if (gridMode) meta = DateUtils.getRelativeTimeSpanString(n.updated, System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_ALL).toString();
            if (rc > 0) meta += "  ·  🔔 " + rc;
            if (n.archived) meta = "📦 Archived  ·  " + meta;
            if (BarPins.isPinned(MainActivity.this, n.id)) meta = "📌 " + meta;
            int pics = n.count(Block.IMAGE), tables = n.count(Block.TABLE), pages = n.count(Block.PAGE) + 1;
            if (pics > 0) meta += "  ·  🖼 " + pics;
            if (tables > 0) meta += "  ·  ▦ " + tables;
            if (pages > 1) meta += "  ·  📑 " + pages;
            // note colour: the card looks like a sticky note
            View card = v.findViewById(R.id.note_card);
            int col = NoteStyle.noteColor(MainActivity.this, n);
            TextView metaTv = v.findViewById(R.id.note_meta);
            if (col != 0) {
                float d = getResources().getDisplayMetrics().density;
                android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
                g.setColor(col);
                g.setCornerRadius(22 * d);
                card.setBackground(Ui.ripple(g));
                boolean darkCard = ColorPicker.luminance(col) < 0.45;
                ((TextView) v.findViewById(R.id.note_title)).setTextColor(darkCard ? 0xFFF4F4F8 : 0xFF14151A);
                pv.setTextColor(darkCard ? 0xCCF4F4F8 : 0xB314151A);
                metaTv.setTextColor(darkCard ? 0xCCF4F4F8 : 0xB314151A);
                metaTv.setBackground(Ui.round(darkCard ? 0x26FFFFFF : 0x14000000, 10 * d));
            } else {
                card.setBackgroundResource(R.drawable.bg_card);
                ((TextView) v.findViewById(R.id.note_title)).setTextColor(getColor(R.color.text));
                pv.setTextColor(getColor(R.color.text2));
                metaTv.setTextColor(getColor(R.color.text2));
                metaTv.setBackgroundResource(R.drawable.bg_badge);
            }
            ((TextView) v.findViewById(R.id.note_meta)).setText(meta);
            v.findViewById(R.id.note_pin).setVisibility(n.pinned ? View.VISIBLE : View.GONE);
            return v;
        }
    }

    // ------------------------------------------------------------------ reminders tab

    private void showReminders(String q) {
        findViewById(R.id.archived_row).setVisibility(View.GONE);
        grid.setVisibility(View.GONE);
        grid.setAdapter(null);
        list.setVisibility(View.VISIBLE);
        findViewById(R.id.label_scroll).setVisibility(View.GONE);
        list.setChoiceMode(ListView.CHOICE_MODE_NONE);
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
        if (r.repeats()) {
            String[] items = {"Open note", "Skip the next one", "Stop repeating", "Delete reminder"};
            new Ui.Popup(this)
                    .setTitle(r.title + "  ·  🔁 " + DateParser.describe(r.repeat))
                    .setItems(items, (d, which) -> {
                        if (which == 0) open(r.noteId);
                        else if (which == 1) ReminderSync.runInBackground(() -> ReminderSync.skip(this, r), this::refresh);
                        else if (which == 2) ReminderSync.runInBackground(() -> ReminderSync.stopRepeating(this, r), this::refresh);
                        else ReminderSync.runInBackground(() -> ReminderSync.ignore(this, r), this::refresh);
                    })
                    .show();
            return;
        }
        String[] items = {"Open note", r.done ? "Mark as not done" : "Mark as done", "Delete reminder"};
        new Ui.Popup(this)
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
            title.setText(r.title);
            ((TextView) v.findViewById(R.id.rem_icon)).setText(ReminderKind.emoji(r.kind));
            Note n = store.note(r.noteId);
            String when = r.hasTime ? DateUtils.formatDateTime(MainActivity.this, r.time, DateUtils.FORMAT_SHOW_TIME) : "Any time";
            if (r.time < System.currentTimeMillis() || r.done) when = Notifier.formatWhen(MainActivity.this, r);
            if (r.repeats()) when = "🔁 " + DateParser.describe(r.repeat) + " · next " + Notifier.formatWhen(MainActivity.this, r);
            int days = Widgets.daysUntil(r.time);
            if (!r.done && days >= 2) when += "  ·  ⏳ in " + days + " days";
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

    @Override
    public void startActivityForResult(Intent intent, int requestCode, Bundle options) {
        if (intent.getComponent() == null || !getPackageName().equals(intent.getComponent().getPackageName())) Lock.leavingForOtherApp();
        super.startActivityForResult(intent, requestCode, options);
    }
}
