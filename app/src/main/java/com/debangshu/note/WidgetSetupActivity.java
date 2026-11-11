package com.debangshu.note;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Picks what a Checklist widget (a note) or a Countdown widget (a reminder) shows. */
public class WidgetSetupActivity extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(Ui.wrap(base)); }

    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.install(this);
        setResult(RESULT_CANCELED);
        widgetId = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return; }
        AppWidgetProviderInfo info = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId);
        boolean countdown = info != null && info.provider.getClassName().endsWith("CountdownProvider");
        if (Lock.appLockOn(this)) {
            Lock.verify(this, "Set up widget", ok -> { if (ok) { Lock.markUnlocked(); build(countdown); } else finish(); });
        } else build(countdown);
    }

    private void build(boolean countdown) {
        float dp = getResources().getDisplayMetrics().density;
        Store s = Store.get(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.col(this, R.color.bg));
        root.setPadding((int) (16 * dp), (int) (20 * dp), (int) (16 * dp), (int) (16 * dp));
        TextView title = new TextView(this);
        title.setText(countdown ? "Count down to…" : "Show which checklist?");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Ui.face(this, Ui.EXTRABOLD));
        title.setTextColor(getColor(R.color.text));
        root.addView(title);
        TextView sub = new TextView(this);
        sub.setText(countdown ? "Pick one of your upcoming reminders (exam, trip, birthday…)." : "Pick a note that has ☐ checkboxes. You can tick them on the home screen.");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sub.setTextColor(getColor(R.color.text2));
        sub.setPadding(0, (int) (4 * dp), 0, (int) (14 * dp));
        root.addView(sub);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        int count = 0;
        if (countdown) {
            List<Reminder> rs = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (Reminder r : s.reminders()) if (!r.done && !r.ignored && r.time > now) rs.add(r);
            java.util.Collections.sort(rs, (a, c) -> Long.compare(a.time, c.time));
            for (Reminder r : rs) {
                int days = Widgets.daysUntil(r.time);
                String when = DateFormat.format("EEE, d MMM yyyy", new Date(r.time))
                        + "  ·  " + (days == 0 ? "today" : days == 1 ? "tomorrow" : "in " + days + " days");
                list.addView(row(dp, ReminderKind.emoji(r.kind) + "  " + r.title, when, () -> done(Widgets.P_COUNTDOWN, r.id)));
                count++;
            }
        } else {
            for (Note n : s.notes()) {
                if (n.locked) continue;
                List<Checklist.Item> items = Checklist.items(n);
                if (items.isEmpty()) continue;
                int left = 0;
                for (Checklist.Item it : items) if (!it.done) left++;
                list.addView(row(dp, n.displayTitle(), left + " of " + items.size() + " left", () -> done(Widgets.P_CHECKLIST, n.id)));
                count++;
            }
        }
        if (count == 0) {
            TextView none = new TextView(this);
            none.setText(countdown ? "No upcoming reminders yet.\n\nWrite something like “exam on 15 Dec” in a note, then add this widget again."
                    : "No checklists yet.\n\nIn a note, type [] and a space for a checkbox, then add this widget again.");
            none.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            none.setTextColor(getColor(R.color.text2));
            none.setGravity(Gravity.CENTER);
            none.setPadding(0, (int) (40 * dp), 0, 0);
            list.addView(none);
        }
        setContentView(root);
    }

    private android.view.View row(float dp, String title, String sub, Runnable onPick) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setBackgroundResource(R.drawable.bg_card);
        r.setElevation(dp);
        r.setPadding((int) (16 * dp), (int) (14 * dp), (int) (16 * dp), (int) (14 * dp));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTypeface(Ui.face(this, Ui.BOLD));
        t.setTextColor(getColor(R.color.text));
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        s.setTypeface(Ui.face(this, Ui.REGULAR));
        s.setTextColor(getColor(R.color.text2));
        r.addView(t);
        r.addView(s);
        r.setOnClickListener(v -> onPick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (10 * dp);
        r.setLayoutParams(lp);
        return r;
    }

    private void done(String key, long value) {
        Widgets.prefs(this).edit().putLong(key + widgetId, value).apply();
        Widgets.updateAll(this);
        Intent result = new Intent();
        result.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        setResult(RESULT_OK, result);
        finish();
    }
}
