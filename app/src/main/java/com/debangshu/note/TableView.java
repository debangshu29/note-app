package com.debangshu.note;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** A table you can type in. The ⋯ button opens add / delete row and column. */
public class TableView extends LinearLayout {

    public interface Listener {
        void onTableEdited();
        void onTableMenu(TableView t);
    }

    private final TableLayout table;
    private final Listener listener;
    private final List<List<EditText>> cells = new ArrayList<>();
    private boolean header = true;
    public int focusRow = 0, focusCol = 0;
    private final float dp;

    public TableView(Context ctx, Listener l) {
        super(ctx);
        listener = l;
        dp = ctx.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        int pad = (int) (8 * dp);
        setPadding(0, pad, 0, pad);

        FrameLayout top = new FrameLayout(ctx);
        TextView label = new TextView(ctx);
        label.setText("▦ Table");
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setTextColor(ctx.getColor(R.color.hint));
        top.addView(label, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL));
        TextView more = new TextView(ctx);
        more.setText("⋯  rows & columns");
        more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        more.setTextColor(ctx.getColor(R.color.accent));
        more.setPadding((int) (12 * dp), (int) (6 * dp), (int) (4 * dp), (int) (6 * dp));
        more.setOnClickListener(v -> listener.onTableMenu(this));
        top.addView(more, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL));
        addView(top);

        HorizontalScrollView hs = new HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(true);
        table = new TableLayout(ctx);
        hs.addView(table);
        addView(hs);
    }

    public void bind(Block b) {
        header = b.header;
        List<List<String>> rows = b.rows.isEmpty() ? Block.table(2, 2).rows : b.rows;
        int cols = Math.max(1, b.columns());
        table.removeAllViews();
        cells.clear();
        for (int r = 0; r < rows.size(); r++) {
            List<String> src = rows.get(r);
            List<String> full = new ArrayList<>(src);
            while (full.size() < cols) full.add("");
            addRowViews(r, full);
        }
        styleAll();
    }

    private void addRowViews(int index, List<String> values) {
        TableRow tr = new TableRow(getContext());
        List<EditText> row = new ArrayList<>();
        for (String v : values) row.add(makeCell(tr, v));
        table.addView(tr, index);
        cells.add(index, row);
    }

    private EditText makeCell(TableRow tr, String value) {
        EditText e = new EditText(getContext());
        e.setText(value);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setTextColor(getContext().getColor(R.color.text));
        e.setMinWidth((int) (110 * dp));
        e.setMaxWidth((int) (260 * dp));
        int p = (int) (8 * dp);
        e.setPadding(p, p, p, p);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        e.setOnFocusChangeListener((v, has) -> {
            if (!has) return;
            for (int r = 0; r < cells.size(); r++) {
                int c = cells.get(r).indexOf(v);
                if (c >= 0) { focusRow = r; focusCol = c; }
            }
        });
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { listener.onTableEdited(); }
        });
        tr.addView(e);
        return e;
    }

    private Typeface base;

    /** The note's font for every cell (header row in bold). */
    public void setBaseTypeface(Typeface t) { base = t; styleAll(); }

    private void styleAll() {
        for (int r = 0; r < cells.size(); r++) {
            for (EditText e : cells.get(r)) {
                boolean head = header && r == 0;
                e.setBackgroundResource(head ? R.drawable.bg_cell_head : R.drawable.bg_cell);
                e.setTypeface(base, head ? Typeface.BOLD : Typeface.NORMAL);
            }
        }
    }

    /** Current cell texts. */
    public List<List<String>> read() {
        List<List<String>> out = new ArrayList<>();
        for (List<EditText> row : cells) {
            List<String> r = new ArrayList<>();
            for (EditText e : row) r.add(e.getText().toString());
            out.add(r);
        }
        return out;
    }

    public boolean header() { return header; }
    public int rows() { return cells.size(); }
    public int cols() { return cells.isEmpty() ? 0 : cells.get(0).size(); }

    public List<EditText> allCells() {
        List<EditText> out = new ArrayList<>();
        for (List<EditText> r : cells) out.addAll(r);
        return out;
    }

    public void setHeader(boolean h) { header = h; styleAll(); listener.onTableEdited(); }

    public void addRow(int after) {
        List<String> empty = new ArrayList<>();
        for (int i = 0; i < Math.max(1, cols()); i++) empty.add("");
        addRowViews(Math.min(after + 1, cells.size()), empty);
        styleAll();
        listener.onTableEdited();
    }

    public void addColumn(int after) {
        int at = Math.min(after + 1, cols());
        for (int r = 0; r < cells.size(); r++) {
            TableRow tr = (TableRow) table.getChildAt(r);
            EditText e = makeCell(tr, "");
            tr.removeView(e);
            tr.addView(e, at);
            cells.get(r).add(at, e);
        }
        styleAll();
        listener.onTableEdited();
    }

    public void deleteRow(int r) {
        if (cells.size() <= 1 || r < 0 || r >= cells.size()) return;
        table.removeViewAt(r);
        cells.remove(r);
        focusRow = Math.min(focusRow, cells.size() - 1);
        styleAll();
        listener.onTableEdited();
    }

    public void deleteColumn(int c) {
        if (cols() <= 1 || c < 0 || c >= cols()) return;
        for (int r = 0; r < cells.size(); r++) {
            TableRow tr = (TableRow) table.getChildAt(r);
            tr.removeViewAt(c);
            cells.get(r).remove(c);
        }
        focusCol = Math.min(focusCol, cols() - 1);
        listener.onTableEdited();
    }
}
