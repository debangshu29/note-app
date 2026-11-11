package com.debangshu.note;

import android.app.Activity;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Recycle bin: deleted notes are kept for 30 days and can be restored. */
public class TrashActivity extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(Ui.wrap(base)); }

    private Store store;
    private ListView list;
    private TextView empty;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.install(this);
        setContentView(R.layout.activity_trash);
        Tips.applyAll(findViewById(android.R.id.content));
        store = Store.get(this);
        list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_empty).setOnClickListener(v -> emptyBin());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.refreshTheme(this)) return;
        if (Lock.guard(this)) return;
        refresh();
    }

    private void refresh() {
        List<Note> notes = store.trash();
        empty.setVisibility(notes.isEmpty() ? View.VISIBLE : View.GONE);
        findViewById(R.id.btn_empty).setVisibility(notes.isEmpty() ? View.GONE : View.VISIBLE);
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return notes.size(); }
            @Override public Object getItem(int p) { return notes.get(p); }
            @Override public long getItemId(int p) { return notes.get(p).id; }
            @Override public View getView(int pos, View v, ViewGroup parent) {
                if (v == null) v = LayoutInflater.from(TrashActivity.this).inflate(R.layout.item_note, parent, false);
                Note n = notes.get(pos);
                ((TextView) v.findViewById(R.id.note_title)).setText(n.displayTitle());
                TextView pv = v.findViewById(R.id.note_preview);
                String preview = n.locked ? "🔒 Locked note" : n.body == null ? "" : n.body.trim();
                pv.setText(preview);
                pv.setVisibility(preview.isEmpty() ? View.GONE : View.VISIBLE);
                long left = Store.TRASH_DAYS - (System.currentTimeMillis() - n.deleted) / DateUtils.DAY_IN_MILLIS;
                ((TextView) v.findViewById(R.id.note_meta)).setText("Deleted "
                        + DateUtils.getRelativeTimeSpanString(n.deleted, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                        + "  ·  " + Math.max(0, left) + (left == 1 ? " day left" : " days left"));
                return v;
            }
        });
        list.setOnItemClickListener((p, v, pos, id) -> noteMenu(notes.get(pos)));
    }

    private void noteMenu(Note n) {
        String[] items = {"↩  Restore", "🗑  Delete forever"};
        new Ui.Popup(this).setTitle(n.displayTitle()).setItems(items, (d, w) -> {
            if (w == 0) {
                store.restoreNote(n.id);
                ReminderSync.syncAsync(this, n.id, null);
                Toast.makeText(this, "Restored", Toast.LENGTH_SHORT).show();
                refresh();
            } else {
                new Ui.Popup(this).setTitle("Delete forever?")
                        .setMessage("\"" + n.displayTitle() + "\" and its pictures will be erased. This can't be undone.")
                        .setPositiveButton("Delete", (dd, ww) -> { store.removeNote(n.id); refresh(); })
                        .setNegativeButton("Cancel", null).show();
            }
        }).show();
    }

    private void emptyBin() {
        List<Note> notes = store.trash();
        if (notes.isEmpty()) return;
        new Ui.Popup(this).setTitle("Empty the recycle bin?")
                .setMessage(notes.size() + (notes.size() == 1 ? " note" : " notes") + " will be erased for good. This can't be undone.")
                .setPositiveButton("Delete", (d, w) -> {
                    for (Note n : notes) store.removeNote(n.id);
                    refresh();
                })
                .setNegativeButton("Cancel", null).show();
    }
}
