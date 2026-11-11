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
        PictureView pic;          // IMAGE
        TextView pageLabel;       // PAGE
    }
    private final List<Holder> holders = new ArrayList<>();
    private TextBlockController current;   // text box with the cursor
    private Holder selectedPic;            // picture with resize handles showing
    private Holder pendingReplace;         // picture being retaken / replaced / rescanned

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
        Ui.install(this);
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
            if (imgs != null) for (String f : imgs) start.add(Block.image(f, "import"));
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
                PictureView pv = new PictureView(this, pictureListener(h));
                pv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                pv.setShape(bl.width, bl.aspect, bl.x);
                h.view = pv;
                h.pic = pv;
                loadImage(h);
                break;
            }
            case Block.STICKY: {
                View v = inf.inflate(R.layout.item_block_sticky, blocksBox, false);
                h.view = v;
                h.sticky = v.findViewById(R.id.sticky_text);
                h.sticky.setText(bl.text);
                applyStickyColor(h);
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
        styleContent(h);
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
            runOnUiThread(() -> h.pic.setBitmap(bmp));
        }).start();
    }

    private void applyStickyColor(Holder h) {
        int bg = h.block.argb != 0 ? h.block.argb : NoteStyle.stickyColor(this, h.block.color);
        h.view.findViewById(R.id.sticky_box).getBackground().mutate().setTint(bg);
        boolean darkBg = ColorPicker.luminance(bg) < 0.4;
        h.sticky.setTextColor(darkBg ? 0xFFF2F1F5 : 0xFF1D1C21);
        h.sticky.setHintTextColor(darkBg ? 0x99FFFFFF : 0x80000000);
        ((TextView) h.view.findViewById(R.id.sticky_more)).setTextColor(darkBg ? 0xB3FFFFFF : 0x99000000);
    }

    // ---- pictures: select, resize, drag, long-press

    private PictureView.Listener pictureListener(Holder h) {
        return new PictureView.Listener() {
            @Override public void onSelect(PictureView v) { selectPicture(h); }
            @Override public void onLongPress(PictureView v) { selectPicture(h); imageMenu(h); }
            @Override public void onChanged(PictureView v) {
                h.block.width = v.widthFrac();
                h.block.aspect = v.aspect();
                h.block.x = v.xFrac();
                onEdited();
            }
            @Override public int scrollPage(int dy) {
                int before = scroll.getScrollY();
                scroll.scrollBy(0, dy);
                return scroll.getScrollY() - before;
            }
            @Override public void onDrop(PictureView v, float centerY) {
                h.block.width = v.widthFrac();
                h.block.aspect = v.aspect();
                h.block.x = v.xFrac();
                dropBlock(h, centerY);
            }
            @Override public int edgeSpeed(float rawY) {
                int[] loc = new int[2];
                scroll.getLocationOnScreen(loc);
                float edge = 72 * getResources().getDisplayMetrics().density;
                float top = loc[1] + edge, bottom = loc[1] + scroll.getHeight() - edge;
                if (rawY < top) return -(int) Math.min(40, 4 + (top - rawY) / 3);
                if (rawY > bottom) return (int) Math.min(40, 4 + (rawY - bottom) / 3);
                return 0;
            }
        };
    }

    private void selectPicture(Holder h) {
        if (selectedPic != null && selectedPic != h && selectedPic.pic != null) selectedPic.pic.setSelectedPicture(false);
        selectedPic = h;
        h.pic.setSelectedPicture(true);
        View f = getCurrentFocus();
        if (f != null) {
            f.clearFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
        }
        current = null;
        updateToolbar();
    }

    private void deselectPicture() {
        if (selectedPic != null && selectedPic.pic != null) selectedPic.pic.setSelectedPicture(false);
        selectedPic = null;
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (ev.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && selectedPic != null) {
            int[] loc = new int[2];
            selectedPic.view.getLocationOnScreen(loc);
            float x = ev.getRawX(), y = ev.getRawY();
            if (x < loc[0] || x > loc[0] + selectedPic.view.getWidth()
                    || y < loc[1] || y > loc[1] + selectedPic.view.getHeight()) deselectPicture();
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void onBackPressed() {
        if (selectedPic != null) { deselectPicture(); return; }
        super.onBackPressed();
    }

    /** Moves a block to where it was dropped: between blocks, or splitting a text box at that line. */
    private void dropBlock(Holder h, float y) {
        if (holders.indexOf(h) < 0) return;
        Holder anchor = null;
        boolean after = false;
        int split = -1;
        for (Holder o : holders) {
            if (o == h) continue;
            int top = o.view.getTop(), bottom = o.view.getBottom();
            if (y < top) { anchor = o; after = false; break; }
            if (y <= bottom) {
                anchor = o;
                if (o.tc != null && o.tc.et.getLayout() != null && o.tc.et.length() > 0) {
                    android.text.Layout lay = o.tc.et.getLayout();
                    int line = lay.getLineForVertical((int) (y - top - o.tc.et.getTotalPaddingTop()));
                    int off = lay.getLineStart(line);
                    if (line == 0 || off <= 0) after = false;
                    else if (off >= o.tc.et.length()) after = true;
                    else split = off;
                } else {
                    after = y > (top + bottom) / 2f;
                }
                break;
            }
            anchor = o;
            after = true;
        }
        if (anchor == null) return;
        int from = holders.indexOf(h);
        Holder before = from > 0 ? holders.get(from - 1) : null;
        Holder behind = from + 1 < holders.size() ? holders.get(from + 1) : null;
        holders.remove(h);
        blocksBox.removeView(h.view);
        int idx;
        if (split > 0) {
            Editable e = anchor.tc.et.getText();
            SpannableStringBuilder tail = new SpannableStringBuilder(e, split, e.length());
            final int cut = split > 0 && e.charAt(split - 1) == '\n' ? split - 1 : split;
            anchor.tc.quiet(() -> e.delete(cut, e.length()));
            idx = holders.indexOf(anchor) + 1;
            Block t = Block.text(tail.toString());
            t.spans = RichText.toJson(tail);
            holders.add(idx, h);
            blocksBox.addView(h.view, idx);
            addHolder(idx + 1, t);
        } else {
            idx = holders.indexOf(anchor) + (after ? 1 : 0);
            holders.add(idx, h);
            blocksBox.addView(h.view, idx);
        }
        mergeText(before, behind);
        ensureTrailingText();
        refreshBlockDecor();
        onEdited();
        if (h.pic != null) selectPicture(h);
    }

    /** If text boxes a and b are now next to each other (something between them moved away), they become one. */
    private void mergeText(Holder a, Holder b2) {
        if (a == null || b2 == null || a.tc == null || b2.tc == null) return;
        int i = holders.indexOf(a);
        if (i < 0 || holders.indexOf(b2) != i + 1) return;
        Editable ea = a.tc.et.getText();
        CharSequence eb = b2.tc.et.getText();
        a.tc.quiet(() -> {
            if (ea.length() > 0 && eb.length() > 0) ea.append("\n");
            ea.append(new SpannableStringBuilder(eb));
        });
        if (current == b2.tc) current = null;
        holders.remove(b2);
        blocksBox.removeView(b2.view);
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
            firstText.tc.et.setHint("Start writing…\n\nTry: 120+80\nor: call mom tomorrow at 5pm\nor type [] and a space for a checkbox\n\nUse ＋ at the top for photos, scans, drawings, sticky notes and tables");
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
        if (h == selectedPic) selectedPic = null;
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
        boolean on = note != null && note.pinned;
        pinBtn.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(on ? R.color.accent : R.color.text2)));
        pinBtn.setBackground(on ? getDrawable(R.drawable.bg_pin) : getDrawable(R.drawable.bg_round_ripple));
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
        strip.setClipToOutline(true);
        strip.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getHeight() / 2f);
            }
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams((int) (20 * d), (int) (4 * d));
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) Math.max(1, d), (int) (22 * d));
        lp.setMargins((int) (6 * d), 0, (int) (6 * d), 0);
        bar.addView(s, lp);
    }

    private void buildToolbar() {
        LinearLayout bar = findViewById(R.id.format_bar);
        bar.removeAllViews();
        tBold = tool(bar, "B", 0, "Bold", v -> fmt(c -> c.toggle(RichText.Bold.class)));
        ((TextView) tBold).setTypeface(null, Typeface.BOLD);
        tItalic = tool(bar, "I", 0, "Italic", v -> fmt(c -> c.toggle(RichText.Italic.class)));
        ((TextView) tItalic).setTypeface(Typeface.SERIF, Typeface.ITALIC);
        tItalic.setTag(Ui.KEEP_FONT);
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
        font.setTag(Ui.KEEP_FONT);
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
        Ui.fonts(bar);
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
        new Ui.Popup(this).setTitle("Font size").setItems(items, (d, w) -> {
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
        new Ui.Popup(this).setTitle("Font size (6 – 120)").setView(box)
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
        new Ui.Popup(this).setTitle("Font style").setItems(items.toArray(new CharSequence[0]), (d, w) -> {
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
        new Ui.Popup(this).setTitle("Insert").setItems(items, (d, w) -> {
            pendingReplace = null;
            switch (w) {
                case 0: takePhoto(); break;
                case 1: pickFromGallery(); break;
                case 2: DocScan.start(this); break;
                case 3: pickPdf(); break;
                case 4: openDrawing(null, -1); break;
                case 5: pickStickyColor(0, c -> {
                    Block sb = Block.sticky(0);
                    sb.argb = c;
                    List<Block> l = new ArrayList<>();
                    l.add(sb);
                    insertBlocks(l);
                }); break;
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

    /** Any colour for a sticky note (Word-style palette); {@code current} is an ARGB colour or 0. */
    private void pickStickyColor(int current, IntPick p) {
        ColorPicker.showFill(this, "Sticky note colour", current, c -> { if (c != 0) p.pick(c); });
    }

    private void pickTableSize() {
        String[] items = {"2 × 2", "3 × 3", "4 × 3 (4 rows, 3 columns)", "5 × 2 (list with prices)", "6 × 4"};
        int[][] sizes = {{2, 2}, {3, 3}, {4, 3}, {5, 2}, {6, 4}};
        new Ui.Popup(this).setTitle("Table size (rows × columns)").setItems(items, (d, w) -> {
            List<Block> l = new ArrayList<>();
            l.add(Block.table(sizes[w][0], sizes[w][1]));
            insertBlocks(l);
        }).show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        Holder replace = pendingReplace != null && holders.contains(pendingReplace) ? pendingReplace : null;
        pendingReplace = null;
        if (req == DocScan.REQ_SCAN) {
            List<Uri> pages = DocScan.pages(res, data);
            if (!pages.isEmpty()) importPictures(pages, "scan", replace);
            return;
        }
        if (res != RESULT_OK) return;
        if (req == REQ_CAMERA && pendingCapture != null) {
            List<Uri> l = new ArrayList<>();
            l.add(Uri.fromFile(pendingCapture));
            importPictures(l, "camera", replace);
        } else if (req == REQ_GALLERY && data != null) {
            List<Uri> l = new ArrayList<>();
            if (data.getClipData() != null)
                for (int k = 0; k < data.getClipData().getItemCount(); k++) l.add(data.getClipData().getItemAt(k).getUri());
            else if (data.getData() != null) l.add(data.getData());
            importPictures(l, "gallery", replace);
        } else if (req == REQ_PDF && data != null && data.getData() != null) {
            importPdf(data.getData());
        } else if (req == REQ_DRAW && data != null) {
            String img = data.getStringExtra(DrawingActivity.RESULT_IMAGE);
            String strokes = data.getStringExtra(DrawingActivity.RESULT_STROKES);
            int index = data.getIntExtra(DrawingActivity.EXTRA_INDEX, -1);
            if (img == null) return;
            if (index >= 0 && index < holders.size() && holders.get(index).pic != null) {
                Holder h = holders.get(index);
                h.block.file = img;
                h.block.drawing = strokes;
                loadImage(h);
                onEdited();
            } else {
                Block b = Block.image(img, "drawing");
                b.drawing = strokes;
                List<Block> l = new ArrayList<>();
                l.add(b);
                insertBlocks(l);
            }
        }
    }

    private void importPictures(List<Uri> uris, String source, Holder replace) {
        final boolean scanned = "scan".equals(source);
        Toast.makeText(this, uris.size() == 1 ? "Adding the picture…" : "Adding " + uris.size() + " pictures…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<Block> blocks = new ArrayList<>();
            List<String> files = new ArrayList<>();
            String error = null;
            for (Uri u : uris) {
                try {
                    String f = Media.importImage(this, u);
                    files.add(f);
                    blocks.add(Block.image(f, source));
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
                if (replace != null) {
                    Block first = blocks.remove(0);
                    replace.block.file = first.file;
                    replace.block.aspect = 0f;
                    replace.block.original = "";
                    replace.pic.setShape(replace.block.width, 0f, replace.block.x);
                    loadImage(replace);
                    int at = holders.indexOf(replace) + 1;
                    for (Block extra : blocks) addHolder(at++, extra);
                    ensureTrailingText();
                    refreshBlockDecor();
                    onEdited();
                    Toast.makeText(this, "Picture replaced", Toast.LENGTH_SHORT).show();
                    return;
                }
                insertBlocks(blocks);
                if (scanned) {
                    new Ui.Popup(this)
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
        new Ui.Popup(this).setTitle("Add PDF").setItems(items, (d, w) -> {
            Toast.makeText(this, "Adding the PDF…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                List<Block> blocks = new ArrayList<>();
                String error = null;
                try {
                    if (w == 0 || w == 2) for (String f : Media.importPdfPages(this, uri, 30)) blocks.add(Block.image(f, "pdf"));
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
    private void ocrIntoNote(List<String> files) { ocrAfter(null, files); }

    /** Reads the text in pictures and puts it right under {@code after} (or at the cursor when null). */
    private void ocrAfter(Holder after, List<String> files) {
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
                if (after != null && holders.contains(after)) {
                    int at = holders.indexOf(after) + 1;
                    Holder t = addHolder(at, Block.text(sb.toString()));
                    if (at + 1 < holders.size()) mergeText(t, holders.get(at + 1));
                    ensureTrailingText();
                    refreshBlockDecor();
                    onEdited();
                    Toast.makeText(this, "Text added under the picture", Toast.LENGTH_SHORT).show();
                    return;
                }
                List<Block> l = new ArrayList<>();
                l.add(Block.text(sb.toString()));
                insertBlocks(l);
            });
        }).start();
    }

    // ======================================================================================= block menus

    /**
     * Long-press menu for a picture. Every picture has the same basics (full screen, text, full width,
     * delete); the extra options depend on where it came from.
     */
    private void imageMenu(Holder h) {
        final Block b = h.block;
        String src = b.drawing != null && !b.drawing.isEmpty() ? "drawing" : b.source == null ? "" : b.source;
        final String FULL = "🔍  Full screen", OCR = "🔤  Get text from picture", WIDE = "↔  Full width",
                SHAPE = "◻  Original shape", CENTER = "⇔  Centre on the line",
                ROTATE = "🔄  Rotate", RETAKE = "📷  Retake photo", REPLACE = "🖼  Replace with another picture",
                BW = "⚫  Black & white (clearer text)", COLOUR = "🎨  Back to original colours",
                RESCAN = "🧾  Rescan this page", PDF = "📤  Share as PDF", SHARE = "📤  Share picture",
                GALLERY = "💾  Save to phone gallery", EDIT = "✏️  Edit drawing", DUP = "⧉  Duplicate",
                DEL = "🗑  Delete";
        List<String> items = new ArrayList<>();
        items.add(FULL);
        items.add(OCR);
        if (b.width < 0.999f) items.add(WIDE);
        if (b.aspect > 0) items.add(SHAPE);
        if (b.width < 0.999f && Math.abs(b.x - 0.5f) > 0.01f) items.add(CENTER);
        String title;
        switch (src) {
            case "camera":
                title = "Photo";
                items.add(ROTATE); items.add(RETAKE); items.add(GALLERY); items.add(SHARE);
                break;
            case "gallery":
                title = "Picture from gallery";
                items.add(ROTATE); items.add(REPLACE); items.add(SHARE);
                break;
            case "scan":
                title = "Scanned page";
                items.add(ROTATE); items.add(b.original != null && !b.original.isEmpty() ? COLOUR : BW);
                items.add(RESCAN); items.add(PDF);
                break;
            case "pdf":
                title = "PDF page";
                items.add(ROTATE); items.add(b.original != null && !b.original.isEmpty() ? COLOUR : BW);
                items.add(SHARE);
                break;
            case "drawing":
                title = "Drawing";
                items.add(EDIT); items.add(DUP); items.add(GALLERY); items.add(SHARE);
                break;
            default:
                title = "Picture";
                items.add(ROTATE); items.add(GALLERY); items.add(SHARE);
                break;
        }
        items.add(DEL);
        new Ui.Popup(this).setTitle(title).setItems(items.toArray(new String[0]), (d, w) -> {
            switch (items.get(w)) {
                case FULL: PhotoViewer.show(this, b.file); break;
                case OCR: { List<String> f = new ArrayList<>(); f.add(b.file); ocrAfter(h, f); break; }
                case WIDE: setShape(h, 1f, b.aspect, 0.5f); break;
                case SHAPE: setShape(h, b.width, 0f, b.x); break;
                case CENTER: setShape(h, b.width, b.aspect, 0.5f); break;
                case ROTATE: editPicture(h, false); break;
                case BW: editPicture(h, true); break;
                case COLOUR:
                    b.file = b.original;
                    b.original = "";
                    loadImage(h);
                    onEdited();
                    break;
                case RETAKE: pendingReplace = h; takePhoto(); break;
                case REPLACE: pendingReplace = h; pickFromGallery(); break;
                case RESCAN: pendingReplace = h; DocScan.start(this); break;
                case PDF: sharePicture(b.file, true); break;
                case SHARE: sharePicture(b.file, false); break;
                case GALLERY: saveToGallery(b.file); break;
                case EDIT: openDrawing(b.drawing, holders.indexOf(h)); break;
                case DUP: {
                    int at = holders.indexOf(h) + 1;
                    addHolder(at, b.copy());
                    ensureTrailingText();
                    refreshBlockDecor();
                    onEdited();
                    break;
                }
                case DEL:
                    new Ui.Popup(this).setMessage("Delete this " + title.toLowerCase(Locale.ROOT) + "?")
                            .setPositiveButton("Delete", (dd, ww) -> removeHolder(h))
                            .setNegativeButton("Cancel", null).show();
                    break;
            }
        }).show();
    }

    private void setShape(Holder h, float width, float aspect, float x) {
        h.block.width = width;
        h.block.aspect = aspect;
        h.block.x = x;
        h.pic.setShape(width, aspect, x);
        onEdited();
    }

    /** Rotate a quarter turn, or make a black & white copy (the original is kept so it can be undone). */
    private void editPicture(Holder h, boolean blackWhite) {
        final String file = h.block.file;
        new Thread(() -> {
            String out = null;
            try { out = blackWhite ? Media.blackAndWhite(this, file) : Media.rotate(this, file); }
            catch (Exception ignored) { }
            final String done = out;
            runOnUiThread(() -> {
                if (done == null) { Toast.makeText(this, "Couldn't change the picture", Toast.LENGTH_LONG).show(); return; }
                if (blackWhite) h.block.original = file;
                else {
                    h.block.original = "";
                    if (h.block.aspect > 0) h.block.aspect = 1f / h.block.aspect;
                    h.pic.setShape(h.block.width, h.block.aspect, h.block.x);
                }
                h.block.file = done;
                loadImage(h);
                onEdited();
            });
        }).start();
    }

    private void sharePicture(String file, boolean asPdf) {
        try {
            String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd HHmm", Locale.ROOT).format(new java.util.Date());
            File f = asPdf ? Media.pagePdf(this, file, "Scan " + stamp + ".pdf")
                    : Media.shareCopy(this, file, "Picture " + stamp + (file.endsWith(".png") ? ".png" : ".jpg"));
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(asPdf ? "application/pdf" : file.endsWith(".png") ? "image/png" : "image/jpeg");
            Uri u = ShareProvider.uriFor(f);
            i.putExtra(Intent.EXTRA_STREAM, u);
            i.setClipData(ClipData.newRawUri("", u));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, asPdf ? "Share scan as PDF" : "Share picture"));
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't share: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void saveToGallery(String file) {
        if (!AppFolder.canWrite(this)) {
            AppFolder.askPermissionIfNeeded(this, REQ_PERMS);
            Toast.makeText(this, "Allow storage, then tap Save again", Toast.LENGTH_LONG).show();
            return;
        }
        new Thread(() -> {
            boolean ok = Media.saveToGallery(this, file);
            runOnUiThread(() -> Toast.makeText(this, ok ? "Saved to Pictures/Note in your gallery" : "Couldn't save the picture",
                    Toast.LENGTH_LONG).show());
        }).start();
    }

    private void stickyMenu(Holder h) {
        String[] items = {"🎨 Colour", "↑ Move up", "↓ Move down", "🗑 Delete sticky note"};
        new Ui.Popup(this).setTitle("Sticky note").setItems(items, (d, w) -> {
            if (w == 0) pickStickyColor(h.block.argb != 0 ? h.block.argb : NoteStyle.stickyPrintColor(h.block.color), c -> {
                h.block.argb = c;
                applyStickyColor(h);
                onEdited();
            });
            else if (w == 1) moveHolder(h, -1);
            else if (w == 2) moveHolder(h, 1);
            else new Ui.Popup(this).setMessage("Delete this sticky note?")
                        .setPositiveButton("Delete", (dd, ww) -> removeHolder(h))
                        .setNegativeButton("Cancel", null).show();
        }).show();
    }

    private void tableMenu(Holder h) {
        TableView t = h.table;
        String[] items = {"Add row below", "Add column to the right", "Delete this row", "Delete this column",
                t.header() ? "Header row: on (turn off)" : "Header row: off (turn on)",
                "↑ Move table up", "↓ Move table down", "🗑 Delete table"};
        new Ui.Popup(this)
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
                            new Ui.Popup(this).setMessage("Delete this table?")
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
        new Ui.Popup(this).setTitle("Page break").setItems(items, (d, w) -> {
            if (w == 0) moveHolder(h, -1);
            else if (w == 1) moveHolder(h, 1);
            else if (w == 2) removeHolder(h);
            else pageSorter();
        }).show();
    }

    // ======================================================================================= ⋮ menu

    private void moreMenu() {
        String[] items = {"🔍  Find & replace", "📑  Page sorter", "🎨  Note style", "📐  Page settings", "🗑  Delete note"};
        new Ui.Popup(this).setItems(items, (d, w) -> {
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
        new Ui.Popup(this)
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

    private int ink = 0;   // text colour that reads well on the note's background

    private void applyStyle() {
        int base = NoteStyle.noteColor(this, note);
        int color = base != 0 ? base : getColor(R.color.bg);
        int second = NoteStyle.noteColor2(this, note);
        View root = findViewById(R.id.editor_root);
        android.graphics.drawable.Drawable bg = NoteStyle.background(this, note, 0);
        if (bg != null) root.setBackground(bg); else root.setBackgroundColor(color);
        boolean lightBg = ColorPicker.luminance(color) > 0.5;
        Ui.bars(this, color, lightBg);
        if (second != 0) getWindow().setNavigationBarColor(second);
        ink = base == 0 ? getColor(R.color.text) : (lightBg ? 0xFF14151A : 0xFFF2F3F7);
        titleEt.setTextColor(ink);
        titleEt.setHintTextColor((ink & 0x00FFFFFF) | 0x59000000);
        titleEt.setTypeface(NoteStyle.font(this, note, true));
        metaTv.setTextColor((ink & 0x00FFFFFF) | 0x8C000000);
        for (Holder h : holders) styleContent(h);
        int paper = note == null ? 0 : note.paper;
        float lh = 17 * getResources().getDisplayMetrics().scaledDensity * 1.17f + 5 * getResources().getDisplayMetrics().density;
        page.setBackground(paper == 0 ? null : new NoteStyle.Paper(paper, lh, lh * 4, NoteStyle.lineColor(this, note, ink)));
    }

    /** Note font and text colour for one block (sticky notes keep their own colours). */
    private void styleContent(Holder h) {
        if (titleEt == null) return;
        android.graphics.Typeface tf = NoteStyle.font(this, note, false);
        int textInk = ink != 0 ? ink : getColor(R.color.text);
        if (h.tc != null) {
            h.tc.et.setTypeface(tf);
            h.tc.et.setTextColor(textInk);
            h.tc.et.setHintTextColor((textInk & 0x00FFFFFF) | 0x59000000);
        } else if (h.sticky != null) {
            h.sticky.setTypeface(tf);
        } else if (h.table != null) {
            h.table.setBaseTypeface(tf);
        }
    }

    private void noteStyleDialog() {
        saveNow();
        if (note == null) { Toast.makeText(this, "Write something first", Toast.LENGTH_SHORT).show(); return; }
        final int oColor = note.color, oCustom = note.customColor, oPaper = note.paper, oLine = note.lineColor;
        final String oFont = note.font;
        final float d = getResources().getDisplayMetrics().density;
        final List<Runnable> refresh = new ArrayList<>();
        final Runnable changed = () -> { applyStyle(); for (Runnable r : refresh) r.run(); };

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (20 * d), 0, (int) (20 * d), (int) (8 * d));

        // ---- colour
        TextView colorLabel = styleLabel(box, "Colour");
        refresh.add(() -> colorLabel.setText(styleHeading("Colour", note.customColor != 0 ? "Custom"
                : NoteStyle.COLOR_NAMES[Math.max(0, Math.min(NoteStyle.COLOR_NAMES.length - 1, note.color))])));
        LinearLayout row = null;
        int perRow = 7, n = NoteStyle.COLOR_NAMES.length + 1;
        for (int k = 0; k < n; k++) {
            if (k % perRow == 0) { row = new LinearLayout(this); box.addView(row); }
            final int i = k;
            View sw = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) (40 * d), 1);
            lp.setMargins((int) (3 * d), (int) (3 * d), (int) (3 * d), (int) (3 * d));
            row.addView(sw, lp);
            if (i < NoteStyle.COLOR_NAMES.length) {
                int[] sc = NoteStyle.swatch(this, i);
                refresh.add(() -> sw.setBackground(swatch(sc[0], sc[1], note.customColor == 0 && note.color == i)));
                sw.setOnClickListener(v -> { note.color = i; note.customColor = 0; changed.run(); });
                Tips.set(sw, NoteStyle.COLOR_NAMES[i]);
            } else {
                refresh.add(() -> {
                    android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
                    g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                    if (note.customColor != 0) g.setColor(NoteStyle.noteColor(this, note));
                    else {
                        g.setGradientType(android.graphics.drawable.GradientDrawable.SWEEP_GRADIENT);
                        g.setColors(new int[]{0xFFFF5F6D, 0xFFFFC371, 0xFFFFF176, 0xFF7CF29C, 0xFF64B5F6, 0xFFB388FF, 0xFFFF5F6D});
                    }
                    g.setStroke((int) ((note.customColor != 0 ? 3 : 1) * d), note.customColor != 0 ? getColor(R.color.accent) : getColor(R.color.divider));
                    sw.setBackground(g);
                });
                sw.setOnClickListener(v -> ColorPicker.showFill(this, "Any colour", note.customColor, col -> {
                    if (col != 0) { note.customColor = col; changed.run(); }
                }));
                Tips.set(sw, "Any colour…");
            }
        }
        if (row != null) while (row.getChildCount() < perRow) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));

        // ---- paper
        TextView paperLabel = styleLabel(box, "Paper");
        refresh.add(() -> paperLabel.setText(styleHeading("Paper", NoteStyle.PAPER_NAMES[Math.max(0, Math.min(NoteStyle.PAPER_NAMES.length - 1, note.paper))])));
        row = null;
        int cols = 4;
        for (int k = 0; k < NoteStyle.PAPER_ORDER.length; k++) {
            if (k % cols == 0) { row = new LinearLayout(this); box.addView(row); }
            final int kind = NoteStyle.PAPER_ORDER[k];
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            android.widget.FrameLayout prev = new android.widget.FrameLayout(this);
            prev.setClipToOutline(true);
            View lines = new View(this);
            prev.addView(lines, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            tile.addView(prev, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (58 * d)));
            TextView name = new TextView(this);
            name.setText(NoteStyle.PAPER_NAMES[kind]);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            name.setTextColor(getColor(R.color.text2));
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(1);
            name.setPadding(0, (int) (4 * d), 0, (int) (6 * d));
            tile.addView(name);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.setMargins((int) (4 * d), (int) (2 * d), (int) (4 * d), (int) (2 * d));
            row.addView(tile, lp);
            refresh.add(() -> {
                int base = NoteStyle.noteColor(this, note);
                int fill = base != 0 ? base : getColor(R.color.surface);
                boolean sel = note.paper == kind;
                android.graphics.drawable.GradientDrawable g = Ui.round(fill, 12 * d);
                g.setStroke((int) ((sel ? 3 : 1) * d), sel ? getColor(R.color.accent) : getColor(R.color.divider));
                prev.setBackground(g);
                int inkNow = ColorPicker.luminance(fill) > 0.5 ? 0xFF14151A : 0xFFF2F3F7;
                lines.setBackground(new NoteStyle.Paper(kind, 11 * d, 9 * d, NoteStyle.lineColor(this, note, inkNow)));
                name.setTextColor(getColor(sel ? R.color.accent : R.color.text2));
            });
            tile.setOnClickListener(v -> { note.paper = kind; changed.run(); });
        }

        // ---- line colour
        TextView lineLabel = styleLabel(box, "Line colour");
        refresh.add(() -> {
            int idx = 0;
            for (int i = 0; i < NoteStyle.LINE_COLORS.length; i++) if (NoteStyle.LINE_COLORS[i] == note.lineColor) idx = i;
            lineLabel.setText(styleHeading("Line colour", NoteStyle.LINE_NAMES[idx]));
        });
        LinearLayout lineRow = new LinearLayout(this);
        box.addView(lineRow);
        for (int i = 0; i < NoteStyle.LINE_COLORS.length; i++) {
            final int lc = NoteStyle.LINE_COLORS[i];
            TextView sw = new TextView(this);
            sw.setGravity(Gravity.CENTER);
            sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            if (lc == 0) { sw.setText("A"); sw.setTextColor(getColor(R.color.text2)); }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (34 * d), (int) (34 * d));
            lp.setMargins((int) (3 * d), (int) (3 * d), (int) (6 * d), (int) (3 * d));
            lineRow.addView(sw, lp);
            refresh.add(() -> sw.setBackground(swatch(lc == 0 ? getColor(R.color.surface2) : lc, lc == 0 ? getColor(R.color.surface2) : lc,
                    note.lineColor == lc)));
            sw.setOnClickListener(v -> { note.lineColor = lc; changed.run(); });
            Tips.set(sw, NoteStyle.LINE_NAMES[i] + " lines");
        }

        // ---- font
        TextView fontLabel = styleLabel(box, "Font");
        refresh.add(() -> {
            int idx = 0;
            for (int i = 0; i < NoteStyle.FONT_IDS.length; i++) if (NoteStyle.FONT_IDS[i].equals(note.font)) idx = i;
            fontLabel.setText(styleHeading("Font", NoteStyle.FONT_NAMES[idx]));
        });
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout fontRow = new LinearLayout(this);
        hs.addView(fontRow);
        box.addView(hs);
        for (int i = 0; i < NoteStyle.FONT_IDS.length; i++) {
            final String id = NoteStyle.FONT_IDS[i];
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.VERTICAL);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding((int) (10 * d), (int) (8 * d), (int) (10 * d), (int) (8 * d));
            TextView aa = new TextView(this);
            aa.setText("Aa");
            aa.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            aa.setTextColor(getColor(R.color.text));
            Note probe = new Note();
            probe.font = id;
            aa.setTypeface(NoteStyle.font(this, probe, false));
            aa.setTag(Ui.KEEP_FONT);
            TextView nm = new TextView(this);
            nm.setText(NoteStyle.FONT_NAMES[i].replace(" (default)", ""));
            nm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            nm.setTextColor(getColor(R.color.text2));
            nm.setMaxLines(1);
            chip.addView(aa);
            chip.addView(nm);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (84 * d), (int) (72 * d));
            lp.setMargins(0, (int) (2 * d), (int) (8 * d), (int) (2 * d));
            fontRow.addView(chip, lp);
            refresh.add(() -> {
                boolean sel = id.equals(note.font == null ? "" : note.font);
                android.graphics.drawable.GradientDrawable g = Ui.round(getColor(sel ? R.color.accent_soft : R.color.surface2), 14 * d);
                if (sel) g.setStroke((int) (2 * d), getColor(R.color.accent));
                chip.setBackground(Ui.ripple(g));
            });
            chip.setOnClickListener(v -> { note.font = id; changed.run(); });
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        for (Runnable r : refresh) r.run();

        Runnable restore = () -> {
            note.color = oColor; note.customColor = oCustom; note.paper = oPaper; note.lineColor = oLine; note.font = oFont;
            applyStyle();
        };
        AlertDialog dlg = new Ui.Popup(this).sheet().setTitle("Note style").setView(Ui.capHeight(sv, 0.5f))
                .setPositiveButton("Done", (dd, w) -> store.saveNote(note))
                .setNegativeButton("Cancel", (dd, w) -> restore.run())
                .setNeutralButton("Reset", null)
                .create();
        dlg.setOnCancelListener(x -> restore.run());
        dlg.show();
        android.widget.Button reset = dlg.getButton(android.content.DialogInterface.BUTTON_NEUTRAL);
        if (reset != null) reset.setOnClickListener(v -> {
            note.color = 0; note.customColor = 0; note.paper = 0; note.lineColor = 0; note.font = "";
            changed.run();
        });
    }

    private TextView styleLabel(LinearLayout box, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(getColor(R.color.text));
        float d = getResources().getDisplayMetrics().density;
        t.setPadding((int) (4 * d), (int) (16 * d), 0, (int) (8 * d));
        box.addView(t);
        return t;
    }

    private CharSequence styleHeading(String name, String value) {
        SpannableStringBuilder b = new SpannableStringBuilder(name);
        b.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, name.length(), 0);
        int at = b.length();
        b.append("   ").append(value);
        b.setSpan(new ForegroundColorSpan(getColor(R.color.text2)), at, b.length(), 0);
        return b;
    }

    /** Round colour swatch (two colours = gradient); selected ones get an accent ring. */
    private android.graphics.drawable.Drawable swatch(int a, int b, boolean selected) {
        float d = getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable g = a == b ? new android.graphics.drawable.GradientDrawable()
                : new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{a, b});
        if (a == b) g.setColor(a);
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setStroke((int) ((selected ? 3 : 1) * d), selected ? getColor(R.color.accent) : getColor(R.color.divider));
        return Ui.ripple(g);
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
        new Ui.Popup(this).setTitle("Page settings").setView(sv)
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
            new Ui.Popup(this).setTitle("Page sorter")
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
                row.addView(mini(R.drawable.ic_delete, "Delete page", x -> new Ui.Popup(EditorActivity.this)
                        .setMessage("Delete page " + (pos + 1) + " and everything on it?")
                        .setPositiveButton("Delete", (d, w) -> { if (pages.size() > 1) { pages.remove(pos); notifyDataSetChanged(); } })
                        .setNegativeButton("Cancel", null).show()));
                return row;
            }
        };
        lv.setAdapter(adapter);
        new Ui.Popup(this).setTitle("Page sorter").setView(lv)
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
        new Ui.Popup(this)
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
