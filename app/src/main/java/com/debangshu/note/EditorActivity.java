package com.debangshu.note;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.CalendarContract;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.Layout;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writing screen. A note is a list of blocks (text, pictures, sticky notes, tables, page breaks).
 * Text blocks keep all smart features (auto math, lists, price totals, reminders) and get
 * Word-style formatting from the bar above the keyboard.
 */
public class EditorActivity extends Activity implements TextBlockController.Host, TableView.Listener {

    public static final String EXTRA_NOTE_ID = "noteId";
    public static final String EXTRA_TITLE = "noteTitle";
    public static final String EXTRA_IMAGES = "noteImages";   // picture files already in Media (from import)

    private static final int REQ_PERMS = 41, REQ_CAMERA = 61, REQ_GALLERY = 62, REQ_PDF = 63, REQ_DRAW = 64;

    private Store store;
    private Note note;                 // null until something is written
    private String lastSaved = "";

    private EditText titleEt;
    private TextView metaTv;
    private View chipsScroll;
    private LinearLayout chipsRow, blocksBox, page;
    private ScrollView scroll;
    private ImageButton pinBtn;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean askedPermissions = false;
    private boolean loading = false;

    // ---- blocks on screen
    static final class Holder {
        Block block;
        View view;
        TextBlockController tc;   // TEXT
        EditText sticky;          // STICKY
        TableView table;          // TABLE
        ImageView image;          // IMAGE
        TextView pageLabel;       // PAGE
    }
    private final List<Holder> holders = new ArrayList<>();
    private TextBlockController current;   // text box with the cursor

    // ---- toolbar buttons that show on/off
    private View tBold, tItalic, tUnder, tStrike, tLeft, tCenter, tRight;
    private TextView tSize;
    private View inkBar, markBar;

    // ---- camera
    private File pendingCapture;
    private int pendingDrawIndex = -1;

    private final Runnable saveTask = this::saveNow;
    private final Runnable chipsTask = this::refreshChips;
    private final Runnable syncTask = () -> {
        if (note != null && store.note(note.id) != null) ReminderSync.syncAsync(this, note.id, this::refreshChips);
    };

    // ======================================================================================= setup

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_editor);
        store = Store.get(this);

        titleEt = findViewById(R.id.title);
        metaTv = findViewById(R.id.meta);
        chipsScroll = findViewById(R.id.chips_scroll);
        chipsRow = findViewById(R.id.chips_row);
        blocksBox = findViewById(R.id.blocks);
        page = findViewById(R.id.page);
        scroll = findViewById(R.id.scroll);
        pinBtn = findViewById(R.id.btn_pin);

        long id = getIntent().getLongExtra(EXTRA_NOTE_ID, -1);
        if (id > 0) note = store.note(id);

        loading = true;
        List<Block> start;
        if (note != null) {
            titleEt.setText(note.title);
            start = new ArrayList<>();
            for (Block bl : note.blocksOrText()) start.add(bl.copy());
            lastSaved = signature(note.title, note.blocksOrText());
        } else {
            start = new ArrayList<>();
            ArrayList<String> imgs = getIntent().getStringArrayListExtra(EXTRA_IMAGES);
            if (imgs != null) for (String f : imgs) start.add(Block.image(f));
            String shared = getIntent().getStringExtra(Intent.EXTRA_TEXT);
            start.add(Block.text(shared == null ? "" : shared));
            String sharedTitle = getIntent().getStringExtra(EXTRA_TITLE);
            if (sharedTitle != null) titleEt.setText(sharedTitle);
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        renderBlocks(start);
        loading = false;
        if (note == null) focusFirstText();

        updateMeta();
        updatePin();
        applyStyle();
        buildToolbar();

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        pinBtn.setOnClickListener(v -> togglePin());
        findViewById(R.id.btn_share).setOnClickListener(v -> share());
        findViewById(R.id.btn_more).setOnClickListener(v -> moreMenu());
        findViewById(R.id.btn_insert).setOnClickListener(v -> insertMenu());
        Tips.applyAll(findViewById(R.id.editor_root));
        findViewById(R.id.tap_end).setOnClickListener(v -> focusLastText());
        titleEt.addTextChangedListener(new SimpleWatcher() {
            @Override public void afterTextChanged(Editable e) { if (!loading) onEdited(); }
        });
        setupFind();

        if (note == null && (getIntent().getStringExtra(Intent.EXTRA_TEXT) != null
                || getIntent().getStringArrayListExtra(EXTRA_IMAGES) != null)) onEdited();
        refreshChips();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(saveTask);
        handler.removeCallbacks(syncTask);
        saveNow();
        if (note != null) {
            if (note.isEmpty()) store.removeNote(note.id);
            ReminderSync.syncAsync(this, note.id, null);
        }
        final Context app = getApplicationContext();
        ReminderSync.runInBackground(() -> AppFolder.autoBackup(app, false), null);
    }

    // ======================================================================================= blocks

    private void renderBlocks(List<Block> list) {
        blocksBox.removeAllViews();
        holders.clear();
        current = null;
        for (Block bl : list) addHolder(holders.size(), bl);
        ensureTrailingText();
        refreshBlockDecor();
    }

    private Holder addHolder(int index, Block bl) {
        Holder h = new Holder();
        h.block = bl;
        LayoutInflater inf = LayoutInflater.from(this);
        switch (bl.type) {
            case Block.IMAGE: {
                View v = inf.inflate(R.layout.item_block_image, blocksBox, false);
                h.view = v;
                h.image = v.findViewById(R.id.block_image);
                h.image.setOnClickListener(x -> imageMenu(h));
                loadImage(h);
                break;
            }
            case Block.STICKY: {
                View v = inf.inflate(R.layout.item_block_sticky, blocksBox, false);
                h.view = v;
                h.sticky = v.findViewById(R.id.sticky_text);
                h.sticky.setText(bl.text);
                v.findViewById(R.id.sticky_box).getBackground().mutate().setTint(NoteStyle.stickyColor(this, bl.color));
                if (NoteStyle.dark(this)) {
                    h.sticky.setTextColor(0xFFECEBF0);
                    h.sticky.setHintTextColor(0x80FFFFFF);
                    ((TextView) v.findViewById(R.id.sticky_more)).setTextColor(0x99FFFFFF);
                }
                h.sticky.addTextChangedListener(new SimpleWatcher() {
                    @Override public void afterTextChanged(Editable e) { if (!loading) onEdited(); }
                });
                h.sticky.setOnFocusChangeListener((x, has) -> { if (has) { current = null; updateToolbar(); } });
                v.findViewById(R.id.sticky_more).setOnClickListener(x -> stickyMenu(h));
                break;
            }
            case Block.TABLE: {
                TableView t = new TableView(this, this);
                t.bind(bl);
                h.table = t;
                h.view = t;
                for (EditText cell : t.allCells()) {
                    cell.setOnFocusChangeListener(chain(cell.getOnFocusChangeListener(), (x, has) -> {
                        if (has) { current = null; updateToolbar(); }
                    }));
                }
                break;
            }
            case Block.PAGE: {
                View v = inf.inflate(R.layout.item_block_page, blocksBox, false);
                h.view = v;
                h.pageLabel = v.findViewById(R.id.page_label);
                v.setOnClickListener(x -> pageBreakMenu(h));
                break;
            }
            default: {
                bl.type = Block.TEXT;
                SmartEditText et = (SmartEditText) inf.inflate(R.layout.item_block_text, blocksBox, false);
                h.view = et;
                et.setText(RichText.build(bl.text, bl.spans));
                h.tc = new TextBlockController(et, store, this);
                et.setOnFocusChangeListener((x, has) -> {
                    if (has) { current = h.tc; updateToolbar(); h.tc.updateSuggestion(); }
                });
                break;
            }
        }
        holders.add(index, h);
        blocksBox.addView(h.view, index);
        return h;
    }

    private static View.OnFocusChangeListener chain(View.OnFocusChangeListener a, View.OnFocusChangeListener b) {
        return (v, has) -> { if (a != null) a.onFocusChange(v, has); b.onFocusChange(v, has); };
    }

    private void loadImage(Holder h) {
        final String file = h.block.file;
        final int target = getResources().getDisplayMetrics().widthPixels;
        new Thread(() -> {
            Bitmap bmp = Media.load(this, file, target);
            runOnUiThread(() -> {
                if (bmp != null) h.image.setImageBitmap(bmp);
                else h.image.setImageResource(R.drawable.ic_import);
                applyImageWidth(h);
            });
        }).start();
    }

    private void applyImageWidth(Holder h) {
        h.view.post(() -> {
            int w = blocksBox.getWidth();
            if (w <= 0) return;
            ViewGroup.LayoutParams lp = h.image.getLayoutParams();
            lp.width = Math.max(80, Math.round(w * Math.max(0.2f, Math.min(1f, h.block.width))));
            h.image.setLayoutParams(lp);
        });
    }

    /** A text box always follows the last picture / table, so you can keep typing. */
    private void ensureTrailingText() {
        if (holders.isEmpty() || holders.get(holders.size() - 1).tc == null) addHolder(holders.size(), Block.text(""));
    }

    /** Page numbers, hint text, and extra space under the last text box (for the grey total). */
    private void refreshBlockDecor() {
        int pageNo = 1;
        Holder firstText = null, lastText = null;
        for (Holder h : holders) {
            if (h.pageLabel != null) { pageNo++; h.pageLabel.setText("Page " + pageNo + "  ·  tap for options"); }
            if (h.tc != null) { if (firstText == null) firstText = h; lastText = h; }
        }
        float d = getResources().getDisplayMetrics().density;
        for (Holder h : holders) {
            if (h.tc == null) continue;
            SmartEditText et = h.tc.et;
            et.setHint(null);
            et.setPadding(et.getPaddingLeft(), et.getPaddingTop(), et.getPaddingRight(), (int) ((h == lastText ? 44 : 8) * d));
        }
        if (firstText != null && holders.size() == 1) {
            firstText.tc.et.setHint("Start writing…\n\nTry: 120+80\nor: call mom tomorrow at 5pm\nor type [] and a space for a checkbox\n\nUse ＋ below for photos, scans, drawings, sticky notes and tables");
            firstText.tc.et.setMinHeight((int) (320 * d));
        } else if (firstText != null) {
            firstText.tc.et.setMinHeight(0);
        }
    }

    private List<Block> collectBlocks() {
        List<Block> out = new ArrayList<>();
        for (Holder h : holders) {
            Block b = h.block;
            if (h.tc != null) {
                Editable e = h.tc.et.getText();
                b.text = e.toString();
                b.spans = RichText.toJson(e);
            } else if (h.sticky != null) {
                b.text = h.sticky.getText().toString();
            } else if (h.table != null) {
                b.rows = h.table.read();
                b.header = h.table.header();
            }
            out.add(b);
        }
        // drop empty text boxes between other blocks (keep the last one)
        List<Block> clean = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            Block b = out.get(i);
            boolean emptyText = b.isText() && b.text.isEmpty() && i != out.size() - 1 && out.size() > 1;
            if (!emptyText) clean.add(b);
        }
        return clean;
    }

    private Holder focusedTextHolder() {
        for (Holder h : holders) if (h.tc != null && h.tc == current) return h;
        return null;
    }

    /** Puts blocks where the cursor is (splitting the text there), or at the end. */
    private void insertBlocks(List<Block> blocks) {
        if (blocks.isEmpty()) return;
        Holder cur = focusedTextHolder();
        int idx;
        SpannableStringBuilder tail = null;
        if (cur != null) {
            Editable e = cur.tc.et.getText();
            int pos = Math.max(0, cur.tc.et.getSelectionStart());
            idx = holders.indexOf(cur) + 1;
            if (pos == 0 && e.length() > 0) {
                idx = holders.indexOf(cur);
            } else if (pos < e.length()) {
                tail = new SpannableStringBuilder(e, pos, e.length());
                int p = pos;
                cur.tc.quiet(() -> e.delete(p, e.length()));
            }
        } else {
            idx = holders.size();
            if (!holders.isEmpty()) {
                Holder last = holders.get(holders.size() - 1);
                if (last.tc != null && last.tc.et.length() == 0) idx = holders.size() - 1;
            }
        }
        Holder lastAdded = null;
        for (Block b : blocks) lastAdded = addHolder(idx++, b);
        if (tail != null) {
            Block t = Block.text(tail.toString());
            t.spans = RichText.toJson(tail);
            addHolder(idx, t);
        } else if (idx >= holders.size() || holders.get(idx).tc == null) {
            addHolder(idx, Block.text(""));
        }
        ensureTrailingText();
        refreshBlockDecor();
        if (lastAdded != null) {
            final Holder show = lastAdded;
            if (show.sticky != null) show.sticky.requestFocus();
            else if (show.table != null && !show.table.allCells().isEmpty()) show.table.allCells().get(0).requestFocus();
            scroll.postDelayed(() -> scroll.smoothScrollTo(0, Math.max(0, show.view.getTop() + blocksBox.getTop() - 80)), 250);
        }
        onEdited();
    }

    private void removeHolder(Holder h) {
        int i = holders.indexOf(h);
        if (i < 0) return;
        holders.remove(i);
        blocksBox.removeView(h.view);
        if (h == focusedTextHolder()) current = null;
        // join the text boxes on both sides
        if (i > 0 && i < holders.size() && holders.get(i - 1).tc != null && holders.get(i).tc != null) {
            Holder a = holders.get(i - 1), b2 = holders.get(i);
            Editable ea = a.tc.et.getText();
            CharSequence eb = b2.tc.et.getText();
            a.tc.quiet(() -> {
                if (ea.length() > 0 && eb.length() > 0) ea.append("\n");
                ea.append(new SpannableStringBuilder(eb));
            });
            holders.remove(b2);
            blocksBox.removeView(b2.view);
        }
        ensureTrailingText();
        refreshBlockDecor();
        onEdited();
    }

    private void moveHolder(Holder h, int dir) {
        int i = holders.indexOf(h);
        int j = i + dir;
        if (i < 0 || j < 0 || j >= holders.size()) return;
        holders.remove(i);
        holders.add(j, h);
        blocksBox.removeView(h.view);
        blocksBox.addView(h.view, j);
        ensureTrailingText();
        refreshBlockDecor();
        onEdited();
    }

    private void focusFirstText() {
        for (Holder h : holders) if (h.tc != null) { h.tc.et.requestFocus(); return; }
    }

    private void focusLastText() {
        for (int i = holders.size() - 1; i >= 0; i--) {
            Holder h = holders.get(i);
            if (h.tc != null) {
                h.tc.et.requestFocus();
                h.tc.et.setSelection(h.tc.et.length());
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(h.tc.et, 0);
                return;
            }
        }
    }

    // ---- TextBlockController.Host / TableView.Listener

    @Override public void onTextEdited(TextBlockController c) { if (!loading) onEdited(); }
    @Override public void onCursorMoved(TextBlockController c) { if (c == current) updateToolbar(); }
    @Override public void onTableEdited() { if (!loading) onEdited(); }
    @Override public void onTableMenu(TableView t) {
        for (Holder h : holders) if (h.table == t) tableMenu(h);
    }

    // ======================================================================================= saving

    private void onEdited() {
        handler.removeCallbacks(saveTask);
        handler.postDelayed(saveTask, 800);
        handler.removeCallbacks(chipsTask);
        handler.postDelayed(chipsTask, 500);
        handler.removeCallbacks(syncTask);
        handler.postDelayed(syncTask, 2500);
    }

    private static String signature(String title, List<Block> blocks) {
        StringBuilder sb = new StringBuilder(title == null ? "" : title);
        for (Block b : blocks) {
            try { sb.append('\u0001').append(b.toJson().toString()); } catch (Exception ignored) { }
        }
        return sb.toString();
    }

    private void saveNow() {
        String t = titleEt.getText().toString();
        List<Block> blocks = collectBlocks();
        String sig = signature(t, blocks);
        if (note == null) {
            Note probe = new Note();
            probe.title = t;
            probe.setBlocks(blocks);
            if (probe.isEmpty()) return;
            note = new Note();
            note.id = store.newId();
            note.created = System.currentTimeMillis();
        } else if (sig.equals(lastSaved)) {
            return;
        }
        note.title = t;
        note.setBlocks(blocks);
        note.updated = System.currentTimeMillis();
        store.saveNote(note);
        lastSaved = sig;
        updateMeta();
    }

    private Note workingNote() {
        saveNow();
        if (note != null) return note;
        Note n = new Note();
        n.title = titleEt.getText().toString();
        n.setBlocks(collectBlocks());
        return n;
    }

    private void updateMeta() {
        if (note == null) { metaTv.setText("New note"); return; }
        String s = "Edited " + DateUtils.getRelativeTimeSpanString(note.updated, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS);
        int pages = note.count(Block.PAGE) + 1;
        if (pages > 1) s += "  ·  " + pages + " pages";
        metaTv.setText(s);
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
        Note n = workingNote();
        if (n.isEmpty()) { Toast.makeText(this, "Write something first", Toast.LENGTH_SHORT).show(); return; }
        List<Note> one = new ArrayList<>();
        one.add(n);
        ShareHelper.show(this, one);
    }

    // ======================================================================================= formatting bar

    private View tool(LinearLayout bar, String label, int icon, String desc, View.OnClickListener l) {
        float d = getResources().getDisplayMetrics().density;
        View v;
        if (icon != 0) {
            ImageButton b = new ImageButton(this);
            b.setImageResource(icon);
            b.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.text)));
            b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            b.setPadding((int) (9 * d), (int) (9 * d), (int) (9 * d), (int) (9 * d));
            v = b;
        } else {
            TextView t = new TextView(this);
            t.setText(label);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(getColor(R.color.text));
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            v = t;
        }
        v.setBackgroundResource(R.drawable.bg_tool);
        v.setContentDescription(desc);
        v.setOnClickListener(l);
        Tips.set(v, desc);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (42 * d), (int) (42 * d));
        lp.setMargins((int) (1 * d), 0, (int) (1 * d), 0);
        bar.addView(v, lp);
        return v;
    }

    /** "A" / highlighter icon with a coloured bar under it (shows the colour you last picked). */
    private View colorTool(LinearLayout bar, String label, int icon, String desc, View.OnClickListener l, View[] barOut) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        if (icon != 0) {
            ImageView iv = new ImageView(this);
            iv.setImageResource(icon);
            iv.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.text)));
            box.addView(iv, new LinearLayout.LayoutParams((int) (20 * d), (int) (20 * d)));
        } else {
            TextView t = new TextView(this);
            t.setText(label);
            t.setTypeface(null, Typeface.BOLD);
            t.setTextColor(getColor(R.color.text));
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            t.setGravity(Gravity.CENTER);
            t.setIncludeFontPadding(false);
            box.addView(t);
        }
        View strip = new View(this);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams((int) (22 * d), (int) (5 * d));
        sl.topMargin = (int) (2 * d);
        box.addView(strip, sl);
        barOut[0] = strip;
        box.setBackgroundResource(R.drawable.bg_tool);
        box.setContentDescription(desc);
        box.setOnClickListener(l);
        Tips.set(box, desc);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (42 * d), (int) (42 * d));
        lp.setMargins((int) d, 0, (int) d, 0);
        bar.addView(box, lp);
        return box;
    }

    private void paintBars() {
        if (inkBar == null) return;
        int ink = store.prefs().getInt("lastInk", 0);
        int mark = store.prefs().getInt("lastMark", 0xFFFFFF00);
        inkBar.setBackgroundColor(ink == 0 ? getColor(R.color.text) : ink);
        if (mark == 0) {
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setStroke((int) getResources().getDisplayMetrics().density, getColor(R.color.hint));
            markBar.setBackground(g);
        } else {
            markBar.setBackgroundColor(mark);
        }
    }

    private void separator(LinearLayout bar) {
        float d = getResources().getDisplayMetrics().density;
        View s = new View(this);
        s.setBackgroundColor(getColor(R.color.divider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) d, (int) (24 * d));
        lp.setMargins((int) (5 * d), 0, (int) (5 * d), 0);
        bar.addView(s, lp);
    }

    private void buildToolbar() {
        LinearLayout bar = findViewById(R.id.format_bar);
        bar.removeAllViews();
        tBold = tool(bar, "B", 0, "Bold", v -> fmt(c -> c.toggle(RichText.Bold.class)));
        ((TextView) tBold).setTypeface(null, Typeface.BOLD);
        tItalic = tool(bar, "I", 0, "Italic", v -> fmt(c -> c.toggle(RichText.Italic.class)));
        ((TextView) tItalic).setTypeface(Typeface.SERIF, Typeface.ITALIC);
        tUnder = tool(bar, "U", 0, "Underline", v -> fmt(c -> c.toggle(RichText.Under.class)));
        ((TextView) tUnder).setPaintFlags(((TextView) tUnder).getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        tStrike = tool(bar, "S", 0, "Strikethrough", v -> fmt(c -> c.toggle(RichText.Strike.class)));
        ((TextView) tStrike).setPaintFlags(((TextView) tStrike).getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        separator(bar);
        tSize = (TextView) tool(bar, "17", 0, "Font size", v -> pickSize());
        tSize.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tSize.setTypeface(null, Typeface.BOLD);
        View font = tool(bar, "F", 0, "Font style", v -> pickFont());
        ((TextView) font).setTypeface(Typeface.SERIF, Typeface.BOLD_ITALIC);
        View[] out = new View[1];
        colorTool(bar, "A", 0, "Font colour", v -> pickInk(), out);
        inkBar = out[0];
        colorTool(bar, null, R.drawable.ic_highlighter, "Highlighter colour", v -> pickMark(), out);
        markBar = out[0];
        paintBars();
        separator(bar);
        tLeft = tool(bar, null, R.drawable.ic_align_left, "Align left", v -> fmt(c -> c.align("l")));
        tCenter = tool(bar, null, R.drawable.ic_align_center, "Center", v -> fmt(c -> c.align("c")));
        tRight = tool(bar, null, R.drawable.ic_align_right, "Align right", v -> fmt(c -> c.align("r")));
        separator(bar);
        tool(bar, null, R.drawable.ic_bullet, "Bullet list", v -> fmt(c -> c.toggleLinePrefix("• ")));
        tool(bar, null, R.drawable.ic_numbered, "Numbered list", v -> fmt(c -> c.toggleLinePrefix("1. ")));
        tool(bar, null, R.drawable.ic_checklist, "Checklist", v -> fmt(c -> c.toggleLinePrefix("☐ ")));
        tool(bar, null, R.drawable.ic_event, "Add a date and time (makes a reminder)", v -> pickDateTime());
        separator(bar);
        View clear = tool(bar, "Tx", 0, "Clear formatting", v -> fmt(TextBlockController::clearFormatting));
        ((TextView) clear).setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tool(bar, null, R.drawable.ic_undo, "Undo", v -> fmt(TextBlockController::undo));
        tool(bar, null, R.drawable.ic_redo, "Redo", v -> fmt(TextBlockController::redo));
        updateToolbar();
    }

    private interface Fmt { void run(TextBlockController c); }

    private void fmt(Fmt f) {
        TextBlockController c = current;
        if (c == null) {
            Holder last = null;
            for (Holder h : holders) if (h.tc != null) last = h;
            if (last == null) return;
            c = last.tc;
            c.et.requestFocus();
        }
        f.run(c);
        updateToolbar();
    }

    private void updateToolbar() {
        if (tBold == null) return;
        TextBlockController c = current;
        boolean on = c != null;
        tBold.setSelected(on && c.isOn(RichText.Bold.class));
        tItalic.setSelected(on && c.isOn(RichText.Italic.class));
        tUnder.setSelected(on && c.isOn(RichText.Under.class));
        tStrike.setSelected(on && c.isOn(RichText.Strike.class));
        String a = on ? c.alignment() : "l";
        tLeft.setSelected(on && "l".equals(a));
        tCenter.setSelected(on && "c".equals(a));
        tRight.setSelected(on && "r".equals(a));
        if (tSize != null) tSize.setText(String.valueOf(on ? c.sizeNow() : RichText.NORMAL_SIZE));
    }

    private void pickSize() {
        int now = current != null ? current.sizeNow() : RichText.NORMAL_SIZE;
        CharSequence[] items = new CharSequence[RichText.SIZES.length + 1];
        for (int i = 0; i < RichText.SIZES.length; i++) {
            int sp = RichText.SIZES[i];
            String t = sp + (sp == RichText.NORMAL_SIZE ? "   (normal)" : "") + (sp == now ? "   ✓" : "");
            SpannableString s = new SpannableString(t);
            s.setSpan(new android.text.style.AbsoluteSizeSpan(Math.min(sp, 30), true), 0, String.valueOf(sp).length(), 0);
            items[i] = s;
        }
        items[RichText.SIZES.length] = "Custom size…";
        new AlertDialog.Builder(this).setTitle("Font size").setItems(items, (d, w) -> {
            if (w == RichText.SIZES.length) { customSize(now); return; }
            applySize(RichText.SIZES[w]);
        }).show();
    }

    private void customSize(int now) {
        EditText in = new EditText(this);
        in.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        in.setText(String.valueOf(now));
        in.setSelectAllOnFocus(true);
        int p = (int) (24 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(p, p / 2, p, 0);
        box.addView(in, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this).setTitle("Font size (6 – 120)").setView(box)
                .setPositiveButton("OK", (d, w) -> {
                    try {
                        int sp = Integer.parseInt(in.getText().toString().trim());
                        applySize(Math.max(6, Math.min(120, sp)));
                    } catch (NumberFormatException ignored) { }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void applySize(int sp) {
        fmt(c -> c.setValue(RichText.Size.class, sp == RichText.NORMAL_SIZE ? null : sp));
        if (tSize != null) tSize.setText(String.valueOf(sp));
    }

    private void pickFont() {
        String now = current != null ? current.fontNow() : "sans-serif";
        List<CharSequence> items = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        int sec = 0;
        for (int i = 0; i < Fonts.IDS.length; i++) {
            if (sec < Fonts.SECTION_AT.length && Fonts.SECTION_AT[sec] == i) {
                SpannableString h = new SpannableString("— " + Fonts.SECTION_NAMES[sec] + " —");
                h.setSpan(new ForegroundColorSpan(getColor(R.color.accent)), 0, h.length(), 0);
                h.setSpan(new android.text.style.RelativeSizeSpan(0.8f), 0, h.length(), 0);
                items.add(h);
                ids.add(null);
                sec++;
            }
            SpannableString s = new SpannableString(Fonts.LABELS[i] + (Fonts.IDS[i].equals(now) ? "   ✓" : ""));
            s.setSpan(new RichText.Font(Fonts.IDS[i]), 0, Fonts.LABELS[i].length(), 0);
            s.setSpan(new android.text.style.AbsoluteSizeSpan(19, true), 0, Fonts.LABELS[i].length(), 0);
            items.add(s);
            ids.add(Fonts.IDS[i]);
        }
        new AlertDialog.Builder(this).setTitle("Font style").setItems(items.toArray(new CharSequence[0]), (d, w) -> {
            String id = ids.get(w);
            if (id == null) { pickFont(); return; }      // tapped a section title
            fmt(c -> c.setValue(RichText.Font.class, "sans-serif".equals(id) ? null : id));
        }).show();
    }

    private void pickInk() {
        int cur = current != null ? current.colorNow(RichText.Ink.class) : 0;
        ColorPicker.show(this, "Font colour", false, cur != 0 ? cur : store.prefs().getInt("lastInk", 0), c -> {
            store.prefs().edit().putInt("lastInk", c).apply();
            paintBars();
            fmt(tc -> tc.setValue(RichText.Ink.class, c == 0 ? null : c));
        });
    }

    private void pickMark() {
        int cur = current != null ? current.colorNow(RichText.Mark.class) : 0;
        ColorPicker.show(this, "Highlighter", true, cur != 0 ? cur : store.prefs().getInt("lastMark", 0xFFFFFF00), c -> {
            store.prefs().edit().putInt("lastMark", c).apply();
            paintBars();
            fmt(tc -> tc.setValue(RichText.Mark.class, c == 0 ? null : c));
        });
    }

    private void pickDateTime() {
        final java.util.Calendar c = java.util.Calendar.getInstance();
        c.add(java.util.Calendar.HOUR_OF_DAY, 1);
        new android.app.DatePickerDialog(this, (dp, y, mo, d) -> {
            c.set(y, mo, d);
            new android.app.TimePickerDialog(this, (tp, h, mi) -> {
                c.set(java.util.Calendar.HOUR_OF_DAY, h);
                c.set(java.util.Calendar.MINUTE, mi);
                String day = new java.text.SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(c.getTime());
                String time = new java.text.SimpleDateFormat("h:mm a", Locale.ENGLISH).format(c.getTime()).toLowerCase(Locale.ENGLISH);
                fmt(tc -> {
                    Editable e = tc.et.getText();
                    int cur = Math.max(0, tc.et.getSelectionStart());
                    boolean space = cur == 0 || Character.isWhitespace(e.charAt(cur - 1));
                    tc.insertAtCursor((space ? "" : " ") + "on " + day + " at " + time);
                });
            }, c.get(java.util.Calendar.HOUR_OF_DAY), 0, android.text.format.DateFormat.is24HourFormat(this)).show();
        }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)).show();
    }

    // ======================================================================================= insert menu

    private void insertMenu() {
        String[] items = {"📷  Take photo", "🖼  Photo from gallery", "🧾  Scan document", "📄  PDF pages",
                "✏️  Drawing", "🗒  Sticky note", "▦  Table", "⤵  New page (page break)"};
        new AlertDialog.Builder(this).setTitle("Insert").setItems(items, (d, w) -> {
            switch (w) {
                case 0: takePhoto(); break;
                case 1: pickFromGallery(); break;
                case 2: DocScan.start(this); break;
                case 3: pickPdf(); break;
                case 4: openDrawing(null, -1); break;
                case 5: pickStickyColor(-1, c -> { List<Block> l = new ArrayList<>(); l.add(Block.sticky(c)); insertBlocks(l); }); break;
                case 6: pickTableSize(); break;
                case 7: { List<Block> l = new ArrayList<>(); l.add(Block.pageBreak()); insertBlocks(l); break; }
            }
        }).show();
    }

    private void takePhoto() {
        try {
            File dir = new File(getCacheDir(), "capture");
            if (!dir.exists()) dir.mkdirs();
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) f.delete();
            pendingCapture = new File(dir, "photo_" + System.currentTimeMillis() + ".jpg");
            Uri out = ShareProvider.captureUri(pendingCapture);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, out);
            i.setClipData(ClipData.newRawUri("", out));
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQ_CAMERA);
        } catch (Exception e) {
            Toast.makeText(this, "No camera app found", Toast.LENGTH_LONG).show();
        }
    }

    private void pickFromGallery() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(Intent.createChooser(i, "Choose photos"), REQ_GALLERY); }
        catch (Exception e) { Toast.makeText(this, "No gallery app found", Toast.LENGTH_LONG).show(); }
    }

    private void pickPdf() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/pdf");
        try { startActivityForResult(i, REQ_PDF); }
        catch (Exception e) { Toast.makeText(this, "No file picker found", Toast.LENGTH_LONG).show(); }
    }

    private void openDrawing(String strokes, int index) {
        Intent i = new Intent(this, DrawingActivity.class);
        if (strokes != null) i.putExtra(DrawingActivity.EXTRA_STROKES, strokes);
        i.putExtra(DrawingActivity.EXTRA_INDEX, index);
        pendingDrawIndex = index;
        startActivityForResult(i, REQ_DRAW);
    }

    private interface IntPick { void pick(int v); }

    private void pickStickyColor(int currentColor, IntPick p) {
        CharSequence[] items = new CharSequence[NoteStyle.STICKY_NAMES.length];
        for (int i = 0; i < items.length; i++) {
            SpannableString s = new SpannableString("  " + NoteStyle.STICKY_NAMES[i] + (i == currentColor ? "  ✓" : "") + "  ");
            s.setSpan(new BackgroundColorSpan(NoteStyle.stickyPrintColor(i)), 0, s.length(), 0);
            s.setSpan(new ForegroundColorSpan(0xFF1D1C21), 0, s.length(), 0);
            items[i] = s;
        }
        new AlertDialog.Builder(this).setTitle("Sticky note colour").setItems(items, (d, w) -> p.pick(w)).show();
    }

    private void pickTableSize() {
        String[] items = {"2 × 2", "3 × 3", "4 × 3 (4 rows, 3 columns)", "5 × 2 (list with prices)", "6 × 4"};
        int[][] sizes = {{2, 2}, {3, 3}, {4, 3}, {5, 2}, {6, 4}};
        new AlertDialog.Builder(this).setTitle("Table size (rows × columns)").setItems(items, (d, w) -> {
            List<Block> l = new ArrayList<>();
            l.add(Block.table(sizes[w][0], sizes[w][1]));
            insertBlocks(l);
        }).show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == DocScan.REQ_SCAN) {
            List<Uri> pages = DocScan.pages(res, data);
            if (!pages.isEmpty()) importPictures(pages, true);
            return;
        }
        if (res != RESULT_OK) return;
        if (req == REQ_CAMERA && pendingCapture != null) {
            List<Uri> l = new ArrayList<>();
            l.add(Uri.fromFile(pendingCapture));
            importPictures(l, false);
        } else if (req == REQ_GALLERY && data != null) {
            List<Uri> l = new ArrayList<>();
            if (data.getClipData() != null)
                for (int k = 0; k < data.getClipData().getItemCount(); k++) l.add(data.getClipData().getItemAt(k).getUri());
            else if (data.getData() != null) l.add(data.getData());
            importPictures(l, false);
        } else if (req == REQ_PDF && data != null && data.getData() != null) {
            importPdf(data.getData());
        } else if (req == REQ_DRAW && data != null) {
            String img = data.getStringExtra(DrawingActivity.RESULT_IMAGE);
            String strokes = data.getStringExtra(DrawingActivity.RESULT_STROKES);
            int index = data.getIntExtra(DrawingActivity.EXTRA_INDEX, -1);
            if (img == null) return;
            if (index >= 0 && index < holders.size() && holders.get(index).image != null) {
                Holder h = holders.get(index);
                h.block.file = img;
                h.block.drawing = strokes;
                loadImage(h);
                onEdited();
            } else {
                Block b = Block.image(img);
                b.drawing = strokes;
                List<Block> l = new ArrayList<>();
                l.add(b);
                insertBlocks(l);
            }
        }
    }

    private void importPictures(List<Uri> uris, boolean scanned) {
        Toast.makeText(this, uris.size() == 1 ? "Adding the picture…" : "Adding " + uris.size() + " pictures…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<Block> blocks = new ArrayList<>();
            List<String> files = new ArrayList<>();
            String error = null;
            for (Uri u : uris) {
                try {
                    String f = Media.importImage(this, u);
                    files.add(f);
                    blocks.add(Block.image(f));
                } catch (Exception e) {
                    error = e.getMessage();
                }
            }
            if (pendingCapture != null) { pendingCapture.delete(); pendingCapture = null; }
            final String err = error;
            runOnUiThread(() -> {
                if (blocks.isEmpty()) {
                    Toast.makeText(this, "Couldn't add the picture" + (err != null ? ": " + err : ""), Toast.LENGTH_LONG).show();
                    return;
                }
                insertBlocks(blocks);
                if (scanned) {
                    new AlertDialog.Builder(this)
                            .setTitle("Scanned " + files.size() + (files.size() == 1 ? " page" : " pages"))
                            .setMessage("Also add the text from the scan, so you can edit and search it?")
                            .setPositiveButton("Add text", (d, w) -> ocrIntoNote(files))
                            .setNegativeButton("Pictures only", null)
                            .show();
                }
            });
        }).start();
    }

    private void importPdf(Uri uri) {
        int count = Media.pdfPageCount(this, uri);
        String pages = count > 30 ? " (first 30 of " + count + " pages)" : count > 0 ? " (" + count + (count == 1 ? " page)" : " pages)") : "";
        String[] items = {"Pages as pictures" + pages, "Text only (editable)", "Both"};
        new AlertDialog.Builder(this).setTitle("Add PDF").setItems(items, (d, w) -> {
            Toast.makeText(this, "Adding the PDF…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                List<Block> blocks = new ArrayList<>();
                String error = null;
                try {
                    if (w == 0 || w == 2) for (String f : Media.importPdfPages(this, uri, 30)) blocks.add(Block.image(f));
                    if (w == 1 || w == 2) {
                        TextImporter.Result r = TextImporter.read(this, uri, "application/pdf");
                        if (!r.text.isEmpty()) blocks.add(Block.text(r.text));
                    }
                } catch (Exception e) {
                    error = e.getMessage();
                }
                final String err = error;
                runOnUiThread(() -> {
                    if (blocks.isEmpty()) Toast.makeText(this, "Couldn't add the PDF" + (err != null ? ": " + err : ""), Toast.LENGTH_LONG).show();
                    else insertBlocks(blocks);
                });
            }).start();
        }).show();
    }

    /** Reads the text in pictures (on the phone) and adds it after them. */
    private void ocrIntoNote(List<String> files) {
        Toast.makeText(this, "Reading the text…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            String error = null;
            for (String f : files) {
                try {
                    TextImporter.Result r = TextImporter.read(this, Uri.fromFile(Media.file(this, f)), "image/jpeg");
                    if (!r.text.isEmpty()) { if (sb.length() > 0) sb.append("\n\n"); sb.append(r.text); }
                } catch (Exception e) {
                    error = e.getMessage();
                }
            }
            final String err = error;
            runOnUiThread(() -> {
                if (sb.length() == 0) {
                    Toast.makeText(this, err != null ? err : "No text found in the picture", Toast.LENGTH_LONG).show();
                    return;
                }
                List<Block> l = new ArrayList<>();
                l.add(Block.text(sb.toString()));
                insertBlocks(l);
            });
        }).start();
    }

    // ======================================================================================= block menus

    private void imageMenu(Holder h) {
        List<String> items = new ArrayList<>();
        final String S = "Small", M = "Medium", L = "Large", F = "Full width", EDIT = "✏️ Edit drawing",
                OCR = "🔤 Get the text from this picture", OPEN = "🔍 Open full screen",
                UP = "↑ Move up", DOWN = "↓ Move down", DEL = "🗑 Delete picture";
        items.add(S); items.add(M); items.add(L); items.add(F);
        if (h.block.drawing != null && !h.block.drawing.isEmpty()) items.add(EDIT);
        items.add(OCR); items.add(OPEN); items.add(UP); items.add(DOWN); items.add(DEL);
        new AlertDialog.Builder(this).setTitle("Picture").setItems(items.toArray(new String[0]), (d, w) -> {
            String p = items.get(w);
            switch (p) {
                case S: setImageWidth(h, 0.35f); break;
                case M: setImageWidth(h, 0.55f); break;
                case L: setImageWidth(h, 0.78f); break;
                case F: setImageWidth(h, 1f); break;
                case EDIT: openDrawing(h.block.drawing, holders.indexOf(h)); break;
                case OCR: { List<String> f = new ArrayList<>(); f.add(h.block.file); ocrIntoNote(f); break; }
                case OPEN: openFullScreen(h.block.file); break;
                case UP: moveHolder(h, -1); break;
                case DOWN: moveHolder(h, 1); break;
                case DEL:
                    new AlertDialog.Builder(this).setMessage("Delete this picture?")
                            .setPositiveButton("Delete", (dd, ww) -> removeHolder(h))
                            .setNegativeButton("Cancel", null).show();
                    break;
            }
        }).show();
    }

    private void setImageWidth(Holder h, float w) {
        h.block.width = w;
        applyImageWidth(h);
        onEdited();
    }

    private void openFullScreen(String file) {
        try {
            File f = Media.file(this, file);
            File copy = new File(ShareProvider.dir(this), "view_" + file);
            try (java.io.InputStream in = new java.io.FileInputStream(f);
                 java.io.OutputStream out = new java.io.FileOutputStream(copy)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            Uri u = ShareProvider.uriFor(copy);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, file.endsWith(".png") ? "image/png" : "image/jpeg");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "No app found to show pictures", Toast.LENGTH_LONG).show();
        }
    }

    private void stickyMenu(Holder h) {
        String[] items = {"🎨 Colour", "↑ Move up", "↓ Move down", "🗑 Delete sticky note"};
        new AlertDialog.Builder(this).setTitle("Sticky note").setItems(items, (d, w) -> {
            if (w == 0) pickStickyColor(h.block.color, c -> {
                h.block.color = c;
                h.view.findViewById(R.id.sticky_box).getBackground().mutate().setTint(NoteStyle.stickyColor(this, c));
                onEdited();
            });
            else if (w == 1) moveHolder(h, -1);
            else if (w == 2) moveHolder(h, 1);
            else new AlertDialog.Builder(this).setMessage("Delete this sticky note?")
                        .setPositiveButton("Delete", (dd, ww) -> removeHolder(h))
                        .setNegativeButton("Cancel", null).show();
        }).show();
    }

    private void tableMenu(Holder h) {
        TableView t = h.table;
        String[] items = {"Add row below", "Add column to the right", "Delete this row", "Delete this column",
                t.header() ? "Header row: on (turn off)" : "Header row: off (turn on)",
                "↑ Move table up", "↓ Move table down", "🗑 Delete table"};
        new AlertDialog.Builder(this)
                .setTitle("Table · row " + (t.focusRow + 1) + ", column " + (t.focusCol + 1))
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0: t.addRow(t.focusRow); break;
                        case 1: t.addColumn(t.focusCol); break;
                        case 2: t.deleteRow(t.focusRow); break;
                        case 3: t.deleteColumn(t.focusCol); break;
                        case 4: t.setHeader(!t.header()); break;
                        case 5: moveHolder(h, -1); break;
                        case 6: moveHolder(h, 1); break;
                        case 7:
                            new AlertDialog.Builder(this).setMessage("Delete this table?")
                                    .setPositiveButton("Delete", (dd, ww) -> removeHolder(h))
                                    .setNegativeButton("Cancel", null).show();
                            break;
                    }
                    // new cells need the focus hook
                    for (EditText cell : t.allCells()) {
                        cell.setOnFocusChangeListener(chain(null, (x, has) -> {
                            if (!has) return;
                            current = null;
                            updateToolbar();
                            List<EditText> all = t.allCells();
                            int k = all.indexOf(x);
                            if (k >= 0 && t.cols() > 0) { t.focusRow = k / t.cols(); t.focusCol = k % t.cols(); }
                        }));
                    }
                }).show();
    }

    private void pageBreakMenu(Holder h) {
        String[] items = {"↑ Move up", "↓ Move down", "🗑 Remove this page break", "📑 Page sorter…"};
        new AlertDialog.Builder(this).setTitle("Page break").setItems(items, (d, w) -> {
            if (w == 0) moveHolder(h, -1);
            else if (w == 1) moveHolder(h, 1);
            else if (w == 2) removeHolder(h);
            else pageSorter();
        }).show();
    }

    // ======================================================================================= ⋮ menu

    private void moreMenu() {
        String[] items = {"🔍  Find & replace", "📑  Page sorter", "🎨  Note style", "📐  Page settings", "🗑  Delete note"};
        new AlertDialog.Builder(this).setItems(items, (d, w) -> {
            switch (w) {
                case 0: openFind(); break;
                case 1: pageSorter(); break;
                case 2: noteStyleDialog(); break;
                case 3: pageSettingsDialog(); break;
                case 4: confirmDelete(); break;
            }
        }).show();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("Delete this note?")
                .setMessage("The note, its pictures and its reminders will be removed from this phone.")
                .setPositiveButton("Delete", (d, w) -> {
                    handler.removeCallbacks(saveTask);
                    if (note != null) {
                        long id = note.id;
                        store.removeNote(id);
                        ReminderSync.syncAsync(this, id, null);
                    }
                    note = null;
                    loading = true;
                    titleEt.setText("");
                    renderBlocks(new ArrayList<>());
                    loading = false;
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- note style

    private void applyStyle() {
        int bg = note == null ? 0 : NoteStyle.noteColor(this, note.color);
        int color = bg != 0 ? bg : getColor(R.color.bg);
        findViewById(R.id.editor_root).setBackgroundColor(color);
        getWindow().setStatusBarColor(color);
        int paper = note == null ? 0 : note.paper;
        float lh = 17 * getResources().getDisplayMetrics().scaledDensity * 1.17f + 5 * getResources().getDisplayMetrics().density;
        int line = (getColor(R.color.text) & 0x00FFFFFF) | 0x1E000000;
        page.setBackground(paper == 0 ? null : new NoteStyle.Paper(paper, lh, lh * 4, line));
    }

    private void noteStyleDialog() {
        saveNow();
        if (note == null) { Toast.makeText(this, "Write something first", Toast.LENGTH_SHORT).show(); return; }
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (24 * d), (int) (8 * d), (int) (24 * d), 0);
        box.addView(label("Colour (also shows on the home screen)"));
        RadioGroup colors = new RadioGroup(this);
        for (int i = 0; i < NoteStyle.COLOR_NAMES.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setId(View.generateViewId());
            SpannableString s = new SpannableString("●  " + NoteStyle.COLOR_NAMES[i]);
            int c = NoteStyle.noteColor(this, i);
            if (c != 0) s.setSpan(new ForegroundColorSpan(c | 0xFF000000), 0, 1, 0);
            r.setText(s);
            r.setTag(i);
            colors.addView(r);
            if (i == note.color) r.setChecked(true);
        }
        box.addView(colors);
        box.addView(label("Paper"));
        RadioGroup paper = group(NoteStyle.PAPER_NAMES, note.paper);
        box.addView(paper);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this).setTitle("Note style").setView(sv)
                .setPositiveButton("Apply", (dd, w) -> {
                    note.color = checked(colors);
                    note.paper = checked(paper);
                    store.saveNote(note);
                    applyStyle();
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ---- page settings

    private static final String[] PAGE_SIZES = {"ENDLESS", "A4", "LETTER", "A5"};
    private static final String[] PAGE_SIZE_NAMES = {"Endless (one long page; A4 when printed)", "A4", "Letter", "A5"};

    private void pageSettingsDialog() {
        saveNow();
        if (note == null) { Toast.makeText(this, "Write something first", Toast.LENGTH_SHORT).show(); return; }
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (24 * d), (int) (8 * d), (int) (24 * d), 0);
        int sizeIdx = 1;
        for (int i = 0; i < PAGE_SIZES.length; i++) if (PAGE_SIZES[i].equals(note.pageSize)) sizeIdx = i;
        box.addView(label("Page size (used for PDF)"));
        RadioGroup size = group(PAGE_SIZE_NAMES, sizeIdx);
        box.addView(size);
        box.addView(label("Orientation"));
        RadioGroup orient = group(new String[]{"Portrait", "Landscape"}, note.landscape ? 1 : 0);
        box.addView(orient);
        box.addView(label("Margins"));
        RadioGroup margins = group(new String[]{"Narrow", "Normal", "Wide"}, note.margins);
        box.addView(margins);
        TextView tip = new TextView(this);
        tip.setText("Tip: ＋ → New page starts a new page. ⋮ → Page sorter reorders pages.");
        tip.setTextColor(getColor(R.color.text2));
        tip.setPadding(0, (int) (12 * d), 0, 0);
        box.addView(tip);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this).setTitle("Page settings").setView(sv)
                .setPositiveButton("Apply", (dd, w) -> {
                    note.pageSize = PAGE_SIZES[checked(size)];
                    note.landscape = checked(orient) == 1;
                    note.margins = checked(margins);
                    store.saveNote(note);
                    Toast.makeText(this, "Saved — used when you share as PDF", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ---- page sorter

    private void pageSorter() {
        saveNow();
        List<List<Block>> pages = splitPages(collectBlocks());
        if (pages.size() < 2) {
            new AlertDialog.Builder(this).setTitle("Page sorter")
                    .setMessage("This note has 1 page.\n\nTo make more pages, tap ＋ → New page where a page should start.")
                    .setPositiveButton("Add a page at the end", (d, w) -> {
                        focusLastText();
                        List<Block> l = new ArrayList<>();
                        l.add(Block.pageBreak());
                        insertBlocks(l);
                    })
                    .setNegativeButton("Close", null).show();
            return;
        }
        float dp = getResources().getDisplayMetrics().density;
        ListView lv = new ListView(this);
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return pages.size(); }
            @Override public Object getItem(int p) { return pages.get(p); }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(int pos, View v, ViewGroup parent) {
                LinearLayout row = new LinearLayout(EditorActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding((int) (20 * dp), (int) (8 * dp), (int) (8 * dp), (int) (8 * dp));
                TextView t = new TextView(EditorActivity.this);
                t.setText("Page " + (pos + 1) + "\n" + preview(pages.get(pos)));
                t.setTextColor(getColor(R.color.text));
                t.setMaxLines(3);
                row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                row.addView(mini(R.drawable.ic_up, "Move up", x -> { swap(pages, pos, pos - 1); notifyDataSetChanged(); }));
                row.addView(mini(R.drawable.ic_down, "Move down", x -> { swap(pages, pos, pos + 1); notifyDataSetChanged(); }));
                row.addView(mini(R.drawable.ic_delete, "Delete page", x -> new AlertDialog.Builder(EditorActivity.this)
                        .setMessage("Delete page " + (pos + 1) + " and everything on it?")
                        .setPositiveButton("Delete", (d, w) -> { if (pages.size() > 1) { pages.remove(pos); notifyDataSetChanged(); } })
                        .setNegativeButton("Cancel", null).show()));
                return row;
            }
        };
        lv.setAdapter(adapter);
        new AlertDialog.Builder(this).setTitle("Page sorter").setView(lv)
                .setPositiveButton("Done", (d, w) -> {
                    List<Block> all = new ArrayList<>();
                    for (int i = 0; i < pages.size(); i++) {
                        if (i > 0) all.add(Block.pageBreak());
                        all.addAll(pages.get(i));
                    }
                    loading = true;
                    renderBlocks(all);
                    loading = false;
                    onEdited();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private ImageButton mini(int icon, String desc, View.OnClickListener l) {
        float d = getResources().getDisplayMetrics().density;
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.text)));
        b.setBackgroundResource(R.drawable.bg_round_ripple);
        b.setContentDescription(desc);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams((int) (42 * d), (int) (42 * d)));
        return b;
    }

    private static void swap(List<List<Block>> l, int a, int b) {
        if (a < 0 || b < 0 || a >= l.size() || b >= l.size()) return;
        List<Block> t = l.get(a);
        l.set(a, l.get(b));
        l.set(b, t);
    }

    static List<List<Block>> splitPages(List<Block> blocks) {
        List<List<Block>> pages = new ArrayList<>();
        List<Block> cur = new ArrayList<>();
        for (Block b : blocks) {
            if (Block.PAGE.equals(b.type)) { pages.add(cur); cur = new ArrayList<>(); }
            else cur.add(b);
        }
        pages.add(cur);
        return pages;
    }

    private static String preview(List<Block> page) {
        StringBuilder sb = new StringBuilder();
        int pics = 0, tables = 0, stickies = 0;
        for (Block b : page) {
            if (Block.IMAGE.equals(b.type)) pics++;
            else if (Block.TABLE.equals(b.type)) tables++;
            else if (Block.STICKY.equals(b.type)) stickies++;
            if (sb.length() < 80 && (b.isText() || Block.STICKY.equals(b.type))) {
                String t = b.text.trim().replace('\n', ' ');
                if (!t.isEmpty()) { if (sb.length() > 0) sb.append(" "); sb.append(t); }
            }
        }
        String text = sb.length() > 80 ? sb.substring(0, 80) + "…" : sb.toString();
        StringBuilder extra = new StringBuilder();
        if (pics > 0) extra.append("🖼 ").append(pics).append("  ");
        if (tables > 0) extra.append("▦ ").append(tables).append("  ");
        if (stickies > 0) extra.append("🗒 ").append(stickies);
        if (text.isEmpty() && extra.length() == 0) return "(empty)";
        return (text + (extra.length() > 0 ? "\n" + extra.toString().trim() : "")).trim();
    }

    // ---- small dialog helpers

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(getColor(R.color.accent));
        t.setPadding(0, (int) (14 * getResources().getDisplayMetrics().density), 0, 0);
        return t;
    }

    private RadioGroup group(String[] options, int checked) {
        RadioGroup g = new RadioGroup(this);
        for (int i = 0; i < options.length; i++) {
            RadioButton b = new RadioButton(this);
            b.setId(View.generateViewId());
            b.setText(options[i]);
            b.setTag(i);
            g.addView(b);
            if (i == checked) b.setChecked(true);
        }
        return g;
    }

    private static int checked(RadioGroup g) {
        View v = g.findViewById(g.getCheckedRadioButtonId());
        return v == null ? 0 : (Integer) v.getTag();
    }

    // ======================================================================================= find & replace

    private static final class Match {
        final EditText et; final int s, e;
        Match(EditText et, int s, int e) { this.et = et; this.s = s; this.e = e; }
    }
    private final List<Match> matches = new ArrayList<>();
    private int matchIndex = -1;
    private EditText findQuery, findReplace;
    private TextView findCount;

    private void setupFind() {
        findQuery = findViewById(R.id.find_query);
        findReplace = findViewById(R.id.find_replace);
        findCount = findViewById(R.id.find_count);
        findQuery.addTextChangedListener(new SimpleWatcher() {
            @Override public void afterTextChanged(Editable e) { runFind(true); }
        });
        findViewById(R.id.find_next).setOnClickListener(v -> stepMatch(1));
        findViewById(R.id.find_prev).setOnClickListener(v -> stepMatch(-1));
        findViewById(R.id.find_close).setOnClickListener(v -> closeFind());
        findViewById(R.id.find_replace_one).setOnClickListener(v -> replaceOne());
        findViewById(R.id.find_replace_all).setOnClickListener(v -> replaceAll());
    }

    private void openFind() {
        findViewById(R.id.find_bar).setVisibility(View.VISIBLE);
        findQuery.requestFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(findQuery, 0);
        runFind(true);
    }

    private void closeFind() {
        clearFindMarks();
        matches.clear();
        findViewById(R.id.find_bar).setVisibility(View.GONE);
    }

    private List<EditText> searchable() {
        List<EditText> l = new ArrayList<>();
        l.add(titleEt);
        for (Holder h : holders) {
            if (h.tc != null) l.add(h.tc.et);
            else if (h.sticky != null) l.add(h.sticky);
            else if (h.table != null) l.addAll(h.table.allCells());
        }
        return l;
    }

    private void clearFindMarks() {
        for (EditText et : searchable()) {
            Editable e = et.getText();
            for (RichText.FindMark m : e.getSpans(0, e.length(), RichText.FindMark.class)) e.removeSpan(m);
        }
    }

    private void runFind(boolean jump) {
        clearFindMarks();
        matches.clear();
        String q = findQuery.getText().toString();
        if (q.isEmpty()) { findCount.setText(""); matchIndex = -1; return; }
        String ql = q.toLowerCase(Locale.ROOT);
        for (EditText et : searchable()) {
            String t = et.getText().toString().toLowerCase(Locale.ROOT);
            int from = 0, i;
            while ((i = t.indexOf(ql, from)) >= 0) {
                matches.add(new Match(et, i, i + q.length()));
                from = i + Math.max(1, q.length());
            }
        }
                if (matches.isEmpty()) { matchIndex = -1; findCount.setText("0"); return; }
        if (matchIndex < 0 || matchIndex >= matches.size() || jump) matchIndex = 0;
        showMatch();
    }

    private void stepMatch(int dir) {
        if (matches.isEmpty()) return;
        matchIndex = (matchIndex + dir + matches.size()) % matches.size();
        showMatch();
    }

    private static final int FIND_ALL = 0x66FFEB3B, FIND_CURRENT = 0xFFFFB74D;

    private void showMatch() {
        findCount.setText((matchIndex + 1) + " of " + matches.size());
        clearFindMarks();
        for (int i = 0; i < matches.size(); i++) {
            Match m = matches.get(i);
            m.et.getText().setSpan(new RichText.FindMark(i == matchIndex ? FIND_CURRENT : FIND_ALL),
                    m.s, m.e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        Match m = matches.get(matchIndex);
        scrollTo(m.et, m.s);
    }

    private void scrollTo(EditText et, int offset) {
        scroll.post(() -> {
            int y = 0;
            View v = et;
            while (v != null && v != page) {
                y += v.getTop();
                v = v.getParent() instanceof View ? (View) v.getParent() : null;
            }
            Layout l = et.getLayout();
            int lineTop = l == null ? 0 : l.getLineTop(l.getLineForOffset(Math.min(offset, et.length())));
            scroll.smoothScrollTo(0, Math.max(0, y + et.getTotalPaddingTop() + lineTop - scroll.getHeight() / 3));
        });
    }

    private void replaceOne() {
        if (matchIndex < 0 || matchIndex >= matches.size()) return;
        Match m = matches.get(matchIndex);
        String rep = findReplace.getText().toString();
        m.et.getText().replace(m.s, m.e, rep);
        int keep = matchIndex;
        runFind(false);
        if (!matches.isEmpty()) { matchIndex = Math.min(keep, matches.size() - 1); showMatch(); }
    }

    private void replaceAll() {
        if (matches.isEmpty()) return;
        String rep = findReplace.getText().toString();
        int n = matches.size();
        for (int i = matches.size() - 1; i >= 0; i--) {
            Match m = matches.get(i);
            m.et.getText().replace(m.s, m.e, rep);
        }
        runFind(false);
        Toast.makeText(this, n == 1 ? "Replaced 1" : "Replaced " + n, Toast.LENGTH_SHORT).show();
    }

    // ======================================================================================= reminder chips

    private String currentPlain() {
        StringBuilder sb = new StringBuilder();
        for (Holder h : holders) {
            String t = h.tc != null ? h.tc.et.getText().toString()
                    : h.sticky != null ? h.sticky.getText().toString()
                    : h.table != null ? tablePlain(h.table) : "";
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(t);
        }
        return sb.toString();
    }

    private static String tablePlain(TableView t) {
        Block b = new Block();
        b.type = Block.TABLE;
        b.rows = t.read();
        return b.plain();
    }

    private void refreshChips() {
        List<ReminderSync.Found> found = ReminderSync.detect(this, titleEt.getText().toString(), currentPlain());
        Map<String, Reminder> stored = new HashMap<>();
        if (note != null) for (Reminder r : store.remindersFor(note.id)) stored.put(r.key, r);

        chipsRow.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);
        long noteId = note == null ? -1 : note.id;
        for (ReminderSync.Found f : found) {
            Reminder r = stored.get(Reminder.makeKey(noteId, f.result.title));
            boolean same = r != null && f.line.equals(r.sourceLine);
            long time = same ? r.time : f.result.timeMillis;
            boolean hasTime = same ? r.hasTime : f.result.hasTime;
            int kind = r != null && (same || r.kindLocked) ? r.kind : f.kind;
            View chip = inf.inflate(R.layout.item_chip, chipsRow, false);
            TextView tv = chip.findViewById(R.id.chip_text);
            tv.setText(ReminderKind.emoji(kind) + " " + f.result.title + " · " + friendlyWhen(time, hasTime));
            if (r != null && (r.ignored || r.done)) {
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
            Toast.makeText(this, ReminderKind.label(f.kind) + " set for "
                    + friendlyWhen(f.result.timeMillis, f.result.hasTime), Toast.LENGTH_SHORT).show();
            return;
        }
        final String OFF = "Don't remind me", ON = "Turn reminder back on",
                DONE = "Mark as done", UNDONE = "Mark as not done", CAL = "Open in phone calendar";
        final String[] KIND_ITEMS = {"⏰  Alarm (rings, no calendar)", "📅  Calendar + one notification", "🔔  Just one notification"};
        final int[] KINDS = {ReminderKind.ALARM, ReminderKind.EVENT, ReminderKind.NOTIFY};
        List<String> items = new ArrayList<>();
        if (!r.ignored && !r.done) {
            for (int i = 0; i < KINDS.length; i++) {
                if (KINDS[i] == ReminderKind.ALARM && !r.hasTime) continue;
                items.add((r.kind == KINDS[i] ? "✓ " : "    ") + KIND_ITEMS[i]);
            }
        }
        items.add(r.ignored ? ON : OFF);
        if (!r.ignored) items.add(r.done ? UNDONE : DONE);
        if (r.calendarEventId > 0) items.add(CAL);
        new AlertDialog.Builder(this)
                .setTitle(ReminderKind.emoji(r.kind) + " " + r.title + "\n" + Notifier.formatWhen(this, r))
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    String pick = items.get(which);
                    if (pick.equals(CAL)) {
                        Uri uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, r.calendarEventId);
                        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                        catch (Exception e) { Toast.makeText(this, "No calendar app found", Toast.LENGTH_SHORT).show(); }
                        return;
                    }
                    int newKind = -1;
                    for (int i = 0; i < KINDS.length; i++) if (pick.endsWith(KIND_ITEMS[i])) newKind = KINDS[i];
                    final int chosen = newKind;
                    if (chosen == ReminderKind.EVENT && !CalendarHelper.hasPermission(this)) {
                        requestPermissions(new String[]{Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR}, REQ_PERMS);
                    }
                    ReminderSync.runInBackground(() -> {
                        if (chosen >= 0) ReminderSync.setKind(this, r, chosen);
                        else if (pick.equals(OFF)) ReminderSync.ignore(this, r);
                        else if (pick.equals(ON)) ReminderSync.unignore(this, r);
                        else if (pick.equals(DONE)) ReminderSync.setDone(this, r, true);
                        else if (pick.equals(UNDONE)) ReminderSync.setDone(this, r, false);
                    }, this::refreshChips);
                })
                .show();
    }

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

    // ======================================================================================= misc

    private abstract static class SimpleWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void afterTextChanged(Editable e) { }
    }
}
