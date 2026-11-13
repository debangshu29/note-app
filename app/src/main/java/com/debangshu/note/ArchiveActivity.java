package com.debangshu.note;

import android.text.format.DateUtils;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Archived notes (like WhatsApp's Archived chats): off the home list, kept safe, still found by search. */
public class ArchiveActivity extends Screen {

    @Override
    protected void build() {
        body.removeAllViews();
        List<Note> notes = new ArrayList<>();
        for (Note n : store.notes()) if (n.archived && n.deleted == 0 && !n.tags().contains(WalletActivity.TAG)) notes.add(n);
        Collections.sort(notes, (a, b) -> Long.compare(b.updated, a.updated));
        setTitles("📦 Archived", notes.isEmpty() ? "Nothing archived" : notes.size() + (notes.size() == 1 ? " note" : " notes"));

        LinearLayout info = card(null, null, null);
        info.addView(hint("Archived notes stay off the home screen but nothing changes in them — their reminders still work "
                + "and search on the home screen still finds them. Tap a note to open it; tap ⋮ (or long-press) to bring it back."));
        if (notes.isEmpty()) {
            info.addView(hint("To archive: on the home screen long-press a note → Archive, or in a note tap ⋮ → Archive."));
            return;
        }
        LinearLayout c = card(null, null, null);
        for (Note n : notes) {
            String when = DateUtils.getRelativeTimeSpanString(n.updated, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString();
            String preview = n.locked ? "🔒 Locked note" : firstLine(n);
            LinearLayout r = row(n.displayTitle().isEmpty() ? "Untitled" : n.displayTitle(),
                    (preview.isEmpty() ? "" : preview + "  ·  ") + when, "⋮", v -> openNote(n.id));
            r.getChildAt(r.getChildCount() - 1).setOnClickListener(v -> menu(n));
            r.setOnLongClickListener(v -> { menu(n); return true; });
            c.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    /** The first line of text that isn't the title. */
    private static String firstLine(Note n) {
        if (n.body == null) return "";
        String title = n.displayTitle().trim();
        for (String l : n.body.split("\n")) {
            String t = l.trim();
            if (t.isEmpty() || t.equals(title)) continue;
            return t.length() > 80 ? t.substring(0, 80) + "…" : t;
        }
        return "";
    }

    private void menu(Note n) {
        String[] items = {"↩  Unarchive (back to the home screen)", "📝  Open", "🗑  Delete (to the recycle bin)"};
        new Ui.Popup(this).setTitle(n.displayTitle().isEmpty() ? "Note" : n.displayTitle()).setItems(items, (d, w) -> {
            if (w == 0) {
                n.archived = false;
                store.saveNote(n);
                Ui.toast(this, "Back on the home screen", Toast.LENGTH_SHORT).show();
            } else if (w == 1) {
                openNote(n.id);
                return;
            } else {
                store.trashNote(n.id);
                ReminderSync.syncAsync(this, n.id, null);
                Ui.toast(this, "Moved to the recycle bin", Toast.LENGTH_SHORT).show();
            }
            build();
            Ui.fonts(root);
        }).show();
    }
}
