package com.debangshu.note;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Switches for every smart feature, backup, and "Delete all data". */
public class SettingsActivity extends Activity {

    private static final int REQ_EXPORT = 11, REQ_IMPORT = 12, REQ_PERMS = 13;
    private Store store;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_settings);
        store = Store.get(this);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        bindSwitch(R.id.sw_auto_math, Store.S_AUTO_MATH);
        bindSwitch(R.id.sw_math_suggest, Store.S_MATH_SUGGEST);
        bindSwitch(R.id.sw_smart_lists, Store.S_SMART_LISTS);
        bindSwitch(R.id.sw_auto_reminders, Store.S_AUTO_REMINDERS);
        bindSwitch(R.id.sw_calendar, Store.S_ADD_TO_CALENDAR);

        findViewById(R.id.row_default_time).setOnClickListener(v ->
                new TimePickerDialog(this, (tp, h, m) -> {
                    store.prefs().edit().putInt(Store.S_DEFAULT_HOUR, h).apply();
                    updateTexts();
                }, store.defaultHour(), 0, android.text.format.DateFormat.is24HourFormat(this)).show());

        findViewById(R.id.row_permissions).setOnClickListener(v -> askPermissions());
        findViewById(R.id.row_exact).setOnClickListener(v -> openExactAlarmSettings());
        findViewById(R.id.row_export).setOnClickListener(v -> startExport());
        findViewById(R.id.row_import).setOnClickListener(v -> startImport());
        findViewById(R.id.row_help).setOnClickListener(v -> HelpDialog.show(this));
        findViewById(R.id.row_delete_all).setOnClickListener(v -> confirmDeleteAll());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateTexts();
    }

    private void bindSwitch(int id, String key) {
        Switch sw = findViewById(id);
        sw.setChecked(store.setting(key));
        sw.setOnCheckedChangeListener((b, on) -> {
            store.prefs().edit().putBoolean(key, on).apply();
            if (key.equals(Store.S_ADD_TO_CALENDAR) && on) {
                if (!CalendarHelper.hasPermission(this)) askPermissions();
                else ReminderSync.runInBackground(() -> ReminderSync.addMissingCalendarEvents(this), null);
            }
        });
    }

    private void updateTexts() {
        int h = store.defaultHour();
        String t = android.text.format.DateFormat.getTimeFormat(this)
                .format(new Date(2000, 0, 1, h, 0));
        ((TextView) findViewById(R.id.txt_default_time)).setText(
                "When a note has a day but no time, remind at " + t);

        boolean notif = Notifier.canNotify(this);
        boolean cal = CalendarHelper.hasPermission(this);
        ((TextView) findViewById(R.id.txt_permissions)).setText(
                "Notifications: " + (notif ? "allowed ✓" : "not allowed – tap to allow")
                        + "\nPhone calendar: " + (cal ? "allowed ✓" : "not allowed – tap to allow"));

        boolean exactOk = true;
        if (Build.VERSION.SDK_INT >= 31) {
            AlarmManager am = getSystemService(AlarmManager.class);
            exactOk = am == null || am.canScheduleExactAlarms();
        }
        findViewById(R.id.row_exact).setVisibility(exactOk ? android.view.View.GONE : android.view.View.VISIBLE);

        int notes = store.notes().size();
        int rem = store.reminders().size();
        long bytes = new java.io.File(getFilesDir(), "notes.json").length()
                + new java.io.File(getFilesDir(), "reminders.json").length();
        ((TextView) findViewById(R.id.txt_storage)).setText(String.format(Locale.ROOT,
                "%d notes · %d reminders · %.1f KB, stored only on this phone", notes, rem, bytes / 1024.0));

        String version = "";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        ((TextView) findViewById(R.id.txt_version)).setText("Note " + version + " · works fully offline");
    }

    // ------------------------------------------------------------------ permissions

    private void askPermissions() {
        java.util.List<String> need = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!CalendarHelper.hasPermission(this)) {
            need.add(Manifest.permission.READ_CALENDAR);
            need.add(Manifest.permission.WRITE_CALENDAR);
        }
        if (need.isEmpty()) { Toast.makeText(this, "Everything is already allowed", Toast.LENGTH_SHORT).show(); return; }
        requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        boolean denied = false;
        for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) denied = true;
        if (denied && !shouldShowAny(perms)) {
            // Android stops showing the popup after two "Don't allow": send the user to app settings
            new AlertDialog.Builder(this)
                    .setTitle("Allow in phone settings")
                    .setMessage("Open Settings → Permissions and allow Notifications and Calendar for Note.")
                    .setPositiveButton("Open settings", (d, w) -> startActivity(new Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton("Later", null)
                    .show();
        }
        if (CalendarHelper.hasPermission(this)) {
            ReminderSync.runInBackground(() -> ReminderSync.addMissingCalendarEvents(this), this::updateTexts);
        }
        updateTexts();
    }

    private boolean shouldShowAny(String[] perms) {
        for (String p : perms) if (shouldShowRequestPermissionRationale(p)) return true;
        return false;
    }

    private void openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < 31) return;
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            Toast.makeText(this, "Not available on this phone", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ backup

    private void startExport() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, "note-backup-"
                + new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date()) + ".json");
        startActivityForResult(i, REQ_EXPORT);
    }

    private void startImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_IMPORT);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (req == REQ_EXPORT) {
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                out.write(store.exportJson().getBytes(StandardCharsets.UTF_8));
                Toast.makeText(this, "Backup saved", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "Could not save backup: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else if (req == REQ_IMPORT) {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                int added = store.importJson(buf.toString("UTF-8"));
                for (Note note : store.notes()) ReminderSync.syncAsync(this, note.id, null);
                Toast.makeText(this, added + " notes restored", Toast.LENGTH_LONG).show();
                updateTexts();
            } catch (Exception e) {
                Toast.makeText(this, "That file is not a Note backup", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ------------------------------------------------------------------ delete everything

    private void confirmDeleteAll() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        CheckBox alsoCal = new CheckBox(this);
        alsoCal.setText("Also remove the events this app added to my phone calendar");
        alsoCal.setChecked(true);
        box.addView(alsoCal);

        new AlertDialog.Builder(this)
                .setTitle("Delete all data?")
                .setMessage("Every note, reminder and setting will be erased from this phone. This cannot be undone.\n\nTip: export a backup first if you might want them later.")
                .setView(box)
                .setPositiveButton("Delete everything", (d, w) -> {
                    boolean cal = alsoCal.isChecked();
                    ReminderSync.runInBackground(() -> {
                        ReminderSync.removeAll(this, cal);
                        store.wipeEverything();
                        store.prefs().edit().putBoolean("welcomed", true).apply();
                    }, () -> {
                        Toast.makeText(this, "All data deleted", Toast.LENGTH_LONG).show();
                        updateTexts();
                        Intent home = new Intent(this, MainActivity.class);
                        home.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(home);
                        finish();
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
