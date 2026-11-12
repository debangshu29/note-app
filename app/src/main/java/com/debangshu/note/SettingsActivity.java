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
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
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

    /** Open with the backup-password box showing (from the Features page). */
    public static final String EXTRA_BACKUP_PASSWORD = "openBackupPassword";


    @Override
    protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(Ui.wrap(base)); }

    private static final int REQ_EXPORT = 11, REQ_IMPORT = 12, REQ_PERMS = 13, REQ_STORAGE = 14;
    private Store store;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.install(this);
        setContentView(R.layout.activity_settings);
        Tips.applyAll(findViewById(android.R.id.content));
        setupSections();
        store = Store.get(this);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        bindSwitch(R.id.sw_auto_math, Store.S_AUTO_MATH);
        bindSwitch(R.id.sw_math_suggest, Store.S_MATH_SUGGEST);
        bindSwitch(R.id.sw_smart_lists, Store.S_SMART_LISTS);
        bindSwitch(R.id.sw_list_totals, Store.S_LIST_TOTALS);
        bindSwitch(R.id.sw_auto_reminders, Store.S_AUTO_REMINDERS);
        bindSwitch(R.id.sw_calendar, Store.S_ADD_TO_CALENDAR);
        bindSwitch(R.id.sw_auto_update, Store.S_AUTO_UPDATE);
        bindSwitch(R.id.sw_auto_backup, Store.S_AUTO_BACKUP);
        findViewById(R.id.row_download_latest).setOnClickListener(v -> Updater.startUpdate(this));
        findViewById(R.id.row_copy_link).setOnClickListener(v -> Updater.copyLink(this));
        findViewById(R.id.row_install_with).setOnClickListener(v -> {
            String app = Updater.installWithLabel(this);
            if (app == null) {
                Toast.makeText(this, "Note will ask which app to use when an update is downloaded", Toast.LENGTH_LONG).show();
                return;
            }
            new Ui.Popup(this).setTitle("Open updates with")
                    .setMessage("Updates open with " + app + ". Ask which app to use next time?")
                    .setPositiveButton("Ask next time", (d, w) -> { Updater.forgetInstallApp(this); updateTexts(); })
                    .setNegativeButton("Keep " + app, null).show();
        });
        findViewById(R.id.row_delete_apks).setOnClickListener(v -> Updater.pickOldFilesToDelete(this));
        findViewById(R.id.row_update_help).setOnClickListener(v -> Updater.showHelp(this));
        findViewById(R.id.row_folder).setOnClickListener(v -> openFolder(null));
        findViewById(R.id.row_backups).setOnClickListener(v -> openFolder(AppFolder.BACKUPS));
        findViewById(R.id.row_exports).setOnClickListener(v -> openFolder(AppFolder.EXPORTS));
        findViewById(R.id.row_imports).setOnClickListener(v -> openFolder(AppFolder.IMPORTS));
        findViewById(R.id.row_backup_now).setOnClickListener(v -> {
            if (!AppFolder.canWrite(this)) { AppFolder.askPermissionIfNeeded(this, REQ_STORAGE); return; }
            ReminderSync.runInBackground(() -> AppFolder.autoBackup(this, true), () -> {
                Toast.makeText(this, "Backup saved in " + AppFolder.displayPath(AppFolder.BACKUPS), Toast.LENGTH_LONG).show();
                updateTexts();
            });
        });
        findViewById(R.id.row_check_update).setOnClickListener(v -> Updater.maybePrompt(this, true));

        findViewById(R.id.row_default_time).setOnClickListener(v ->
                new TimePickerDialog(this, (tp, h, m) -> {
                    store.prefs().edit().putInt(Store.S_DEFAULT_HOUR, h).apply();
                    updateTexts();
                }, store.defaultHour(), 0, android.text.format.DateFormat.is24HourFormat(this)).show());

        findViewById(R.id.row_permissions).setOnClickListener(v -> askPermissions());
        findViewById(R.id.row_exact).setOnClickListener(v -> openExactAlarmSettings());
        findViewById(R.id.row_fullscreen).setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT < 34) return;
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                Toast.makeText(this, "Not available on this phone", Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.row_export).setOnClickListener(v -> startExport());
        findViewById(R.id.row_import).setOnClickListener(v -> startImport());
        findViewById(R.id.row_backup_pw).setOnClickListener(v -> backupPasswordDialog());
        if (getIntent().getBooleanExtra(EXTRA_BACKUP_PASSWORD, false)) {
            getIntent().removeExtra(EXTRA_BACKUP_PASSWORD);
            findViewById(R.id.row_backup_pw).post(this::backupPasswordDialog);
        }
        findViewById(R.id.row_places).setOnClickListener(v -> startActivity(new Intent(this, PlacesActivity.class)));
        findViewById(R.id.row_help).setOnClickListener(v -> HelpDialog.show(this));
        findViewById(R.id.row_delete_all).setOnClickListener(v -> confirmDeleteAll());
        findViewById(R.id.row_trash).setOnClickListener(v -> startActivity(new Intent(this, TrashActivity.class)));
        setupLook();
        setupLock();
        setupBrief();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.refreshTheme(this)) return;
        if (Lock.guard(this)) return;
        updateTexts();
    }

    private void bindSwitch(int id, String key) {
        Switch sw = findViewById(id);
        sw.setChecked(store.setting(key));
        sw.setOnCheckedChangeListener((b, on) -> {
            store.prefs().edit().putBoolean(key, on).apply();
            if (key.equals(Store.S_AUTO_UPDATE)) Updater.scheduleBackgroundChecks(this);
            if (key.equals(Store.S_ADD_TO_CALENDAR) && on) {
                if (!CalendarHelper.hasPermission(this)) askPermissions();
                else ReminderSync.runInBackground(() -> ReminderSync.addMissingCalendarEvents(this), null);
            }
        });
    }

    // ------------------------------------------------------------------ look & feel

    private void setupLook() {
        findViewById(R.id.row_theme).setOnClickListener(v -> pickChoice("Theme", Ui.THEME_NAMES,
                store.prefs().getInt(Ui.P_THEME, 0), w -> { store.prefs().edit().putInt(Ui.P_THEME, w).apply(); restyle(); }));
        findViewById(R.id.row_text_size).setOnClickListener(v -> {
            float cur = store.prefs().getFloat(Ui.P_TEXT, 1f);
            int sel = 1;
            for (int i = 0; i < Ui.TEXT_SCALES.length; i++) if (Math.abs(Ui.TEXT_SCALES[i] - cur) < 0.01f) sel = i;
            pickChoice("Text size", Ui.TEXT_NAMES, sel, w -> { store.prefs().edit().putFloat(Ui.P_TEXT, Ui.TEXT_SCALES[w]).apply(); restyle(); });
        });
        android.widget.LinearLayout row = findViewById(R.id.accent_swatches);
        row.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        int cur = store.prefs().getInt(Ui.P_ACCENT, 0);
        for (int i = 0; i < Ui.ACCENT_PREVIEW.length; i++) {
            final int k = i;
            android.view.View sw = new android.view.View(this);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(Ui.ACCENT_PREVIEW[i]);
            if (i == cur) g.setStroke((int) (3 * d), getColor(R.color.text));
            sw.setBackground(Ui.ripple(g));
            sw.setContentDescription(Ui.ACCENT_NAMES[i]);
            Tips.set(sw, Ui.ACCENT_NAMES[i]);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams((int) (36 * d), (int) (36 * d));
            lp.setMargins(0, 0, (int) (10 * d), 0);
            sw.setOnClickListener(v -> { store.prefs().edit().putInt(Ui.P_ACCENT, k).apply(); restyle(); });
            row.addView(sw, lp);
        }
    }

    /** Apply a new theme / accent / text size: this screen rebuilds now, the others when you go back. */
    private void restyle() {
        Ui.themeChanged();
        recreate();
    }

    private interface Pick { void picked(int which); }

    private void pickChoice(String title, String[] names, int selected, Pick p) {
        CharSequence[] items = new CharSequence[names.length];
        for (int i = 0; i < names.length; i++) items[i] = (i == selected ? "✓   " : "      ") + names[i];
        new Ui.Popup(this).setTitle(title).setItems(items, (dd, w) -> p.picked(w)).show();
    }

    // ------------------------------------------------------------------ morning brief, voice language

    private static final String[] VOICE_NAMES = {"Same as phone", "English (India)", "English (US)", "हिन्दी (Hindi)", "বাংলা (Bengali)"};
    private static final String[] VOICE_CODES = {"", "en-IN", "en-US", "hi-IN", "bn-IN"};

    private void setupBrief() {
        Switch b = findViewById(R.id.sw_brief);
        b.setChecked(Brief.on(this));
        b.setOnCheckedChangeListener((x, on) -> {
            store.prefs().edit().putBoolean(Brief.P_ON, on).apply();
            Brief.schedule(this);
        });
        Switch ro = findViewById(R.id.sw_rollover);
        ro.setChecked(RollOver.on(this));
        ro.setOnCheckedChangeListener((x, on) -> {
            store.prefs().edit().putBoolean(RollOver.P_ON, on).apply();
            if (on) ReminderSync.runInBackground(() -> RollOver.run(this), null);
        });
        findViewById(R.id.row_brief_time).setOnClickListener(v -> new TimePickerDialog(this, (tp, h, m) -> {
            store.prefs().edit().putInt(Brief.P_HOUR, h).putInt(Brief.P_MIN, m).apply();
            Brief.schedule(this);
            updateTexts();
        }, Brief.hour(this), Brief.minute(this), android.text.format.DateFormat.is24HourFormat(this)).show());
        findViewById(R.id.row_voice_lang).setOnClickListener(v -> {
            String cur = store.prefs().getString(EditorActivity.P_VOICE_LANG, "");
            int sel = 0;
            for (int i = 0; i < VOICE_CODES.length; i++) if (VOICE_CODES[i].equals(cur)) sel = i;
            pickChoice("Voice typing language", VOICE_NAMES, sel, w -> {
                store.prefs().edit().putString(EditorActivity.P_VOICE_LANG, VOICE_CODES[w]).apply();
                updateTexts();
            });
        });
    }

    // ------------------------------------------------------------------ privacy & lock

    private boolean settingLock = false;

    private void setupLock() {
        Switch lockSw = findViewById(R.id.sw_app_lock);
        lockSw.setChecked(Lock.appLockOn(this));
        lockSw.setOnCheckedChangeListener((b, on) -> {
            if (settingLock) return;
            if (on) {
                if (Lock.hasPin(this)) {
                    store.prefs().edit().putBoolean(Lock.P_ON, true).apply();
                    Lock.markUnlocked();
                    Toast.makeText(this, "App lock is on", Toast.LENGTH_SHORT).show();
                    updateTexts();
                } else {
                    Lock.showPad(this, Lock.CREATE, "Choose a PIN", ok -> {
                        if (ok) {
                            store.prefs().edit().putBoolean(Lock.P_ON, true).apply();
                            Lock.markUnlocked();
                            Toast.makeText(this, "App lock is on", Toast.LENGTH_SHORT).show();
                        } else setSwitch(lockSw, false);
                        updateTexts();
                    });
                }
            } else {
                Lock.showPad(this, Lock.VERIFY, "Turn off app lock", ok -> {
                    if (ok) store.prefs().edit().putBoolean(Lock.P_ON, false).apply();
                    else setSwitch(lockSw, true);
                    updateTexts();
                });
            }
        });
        Switch bio = findViewById(R.id.sw_lock_bio);
        bio.setChecked(store.prefs().getBoolean(Lock.P_BIO, true));
        bio.setOnCheckedChangeListener((b, on) -> store.prefs().edit().putBoolean(Lock.P_BIO, on).apply());
        findViewById(R.id.row_lock_after).setOnClickListener(v -> pickChoice("Lock again", Lock.AFTER_NAMES,
                Lock.afterIndex(this), w -> { store.prefs().edit().putInt(Lock.P_AFTER, Lock.AFTER_SECONDS[w]).apply(); updateTexts(); }));
        findViewById(R.id.row_change_pin).setOnClickListener(v -> {
            if (!Lock.hasPin(this)) {
                Lock.showPad(this, Lock.CREATE, "Choose a PIN", ok -> updateTexts());
                return;
            }
            Lock.showPad(this, Lock.VERIFY, "Enter your current PIN", ok -> {
                if (ok) Lock.showPad(this, Lock.CREATE, "Choose a new PIN", ok2 -> {
                    if (ok2) Toast.makeText(this, "PIN changed", Toast.LENGTH_SHORT).show();
                    updateTexts();
                });
            });
        });
    }

    private void setSwitch(Switch s, boolean on) {
        settingLock = true;
        s.setChecked(on);
        settingLock = false;
    }

    @Override
    public void startActivityForResult(Intent intent, int requestCode, Bundle options) {
        if (intent.getComponent() == null || !getPackageName().equals(intent.getComponent().getPackageName())) Lock.leavingForOtherApp();
        super.startActivityForResult(intent, requestCode, options);
    }

    private void updateTexts() {
        ((TextView) findViewById(R.id.txt_backup_pw)).setText(store.backupProtected()
                ? "On — backups (.note files) open only with your password"
                : "Off — anyone with a backup file can read it. Tap to set a password.");
        java.util.Calendar bt = java.util.Calendar.getInstance();
        bt.set(java.util.Calendar.HOUR_OF_DAY, Brief.hour(this));
        bt.set(java.util.Calendar.MINUTE, Brief.minute(this));
        ((TextView) findViewById(R.id.txt_brief_time)).setText(android.text.format.DateFormat.getTimeFormat(this).format(bt.getTime())
                + " every morning");
        String vl = store.prefs().getString(EditorActivity.P_VOICE_LANG, "");
        String vname = VOICE_NAMES[0];
        for (int i = 0; i < VOICE_CODES.length; i++) if (VOICE_CODES[i].equals(vl)) vname = VOICE_NAMES[i];
        ((TextView) findViewById(R.id.txt_voice_lang)).setText(vname + " · tap 🎤 on the formatting bar to speak");
        String installApp = Updater.installWithLabel(this);
        ((TextView) findViewById(R.id.txt_install_with)).setText(installApp == null
                ? "Ask each time (pick your phone's Files app — not Drive)" : installApp + " · tap to change");
        ((TextView) findViewById(R.id.txt_theme)).setText(Ui.THEME_NAMES[Math.max(0, Math.min(3, store.prefs().getInt(Ui.P_THEME, 0)))]);
        ((TextView) findViewById(R.id.txt_accent)).setText(Ui.ACCENT_NAMES[Math.max(0, Math.min(Ui.ACCENT_NAMES.length - 1, store.prefs().getInt(Ui.P_ACCENT, 0)))]);
        float sc = store.prefs().getFloat(Ui.P_TEXT, 1f);
        String size = "Normal";
        for (int i = 0; i < Ui.TEXT_SCALES.length; i++) if (Math.abs(Ui.TEXT_SCALES[i] - sc) < 0.01f) size = Ui.TEXT_NAMES[i];
        ((TextView) findViewById(R.id.txt_text_size)).setText(size);
        ((TextView) findViewById(R.id.txt_lock_after)).setText(Lock.AFTER_NAMES[Lock.afterIndex(this)] + " after you leave Note");
        ((TextView) findViewById(R.id.txt_pin)).setText(Lock.hasPin(this) ? "A 4-digit PIN is set" : "No PIN yet — tap to choose one");
        findViewById(R.id.sw_lock_bio).setVisibility(Lock.bioAvailable(this) ? android.view.View.VISIBLE : android.view.View.GONE);
        int bin = store.trash().size();
        ((TextView) findViewById(R.id.txt_trash)).setText(bin == 0 ? "Empty · deleted notes are kept for 30 days"
                : bin + (bin == 1 ? " note" : " notes") + " · kept for 30 days, then deleted");
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
        findViewById(R.id.row_fullscreen).setVisibility(
                Notifier.canFullScreen(this) ? android.view.View.GONE : android.view.View.VISIBLE);

        int notes = store.notes().size();
        int rem = store.reminders().size();
        long bytes = new java.io.File(getFilesDir(), "notes.json").length()
                + new java.io.File(getFilesDir(), "reminders.json").length();
        ((TextView) findViewById(R.id.txt_storage)).setText(String.format(Locale.ROOT,
                "%d notes · %d reminders · %.1f KB\nKept in Note's private storage (only Note can open it):\n%s",
                notes, rem, bytes / 1024.0, AppFolder.privatePath(this)));
        ((TextView) findViewById(R.id.txt_folder)).setText(AppFolder.displayPath(null)
                + (AppFolder.canWrite(this) ? "" : "\nTap to allow Note to use this folder"));
        ReminderSync.runInBackground(() -> {
            final String b = folderInfo(AppFolder.BACKUPS, "Automatic and manual backups of all notes");
            final String e = folderInfo(AppFolder.EXPORTS, "Copies of images and PDFs you share");
            final String i = folderInfo(AppFolder.IMPORTS, "Copies of photos, PDFs and files you turned into notes");
            runOnUiThread(() -> {
                if (isFinishing()) return;
                ((TextView) findViewById(R.id.txt_backups)).setText(b);
                ((TextView) findViewById(R.id.txt_exports)).setText(e);
                ((TextView) findViewById(R.id.txt_imports)).setText(i);
            });
        }, null);
        ((TextView) findViewById(R.id.txt_link)).setText(Updater.APK_URL);

        String version = "";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        ((TextView) findViewById(R.id.txt_version)).setText("Note " + version + " · your notes never leave this phone");
        ((TextView) findViewById(R.id.txt_update)).setText("You have version " + version + ". Tap to look for a newer one.");
    }

    private String folderInfo(String section, String what) {
        int count = AppFolder.list(this, section).size();
        long kb = AppFolder.size(this, section) / 1024;
        return what + "\n" + AppFolder.displayPath(section) + "\n" + count + (count == 1 ? " file · " : " files · ")
                + (kb >= 1024 ? String.format(Locale.ROOT, "%.1f MB", kb / 1024.0) : kb + " KB");
    }

    private void openFolder(String section) {
        if (!AppFolder.canWrite(this)) { AppFolder.askPermissionIfNeeded(this, REQ_STORAGE); return; }
        AppFolder.open(this, section);
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
            new Ui.Popup(this)
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
        i.setType(store.backupMime());
        i.putExtra(Intent.EXTRA_TITLE, "note-backup-"
                + new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date()) + "." + store.backupExtension());
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
        Updater.onCleanupResult(this, req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (req == REQ_EXPORT) {
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                store.exportBackup(out);
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
                byte[] bytes = buf.toByteArray();
                if (BackupCrypt.isEncrypted(bytes)) askBackupPassword(bytes);
                else restore(bytes);
            } catch (Exception e) {
                Toast.makeText(this, "That file is not a Note backup", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void restore(byte[] data) {
        try {
            int added = store.importBackup(data);
            for (Note note : store.notes()) ReminderSync.syncAsync(this, note.id, null);
            Toast.makeText(this, added + " notes restored", Toast.LENGTH_LONG).show();
            updateTexts();
        } catch (Exception e) {
            Toast.makeText(this, "That file is not a Note backup", Toast.LENGTH_LONG).show();
        }
    }

    private EditText passwordField(String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setSingleLine(true);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return et;
    }

    private void askBackupPassword(byte[] data) {
        EditText pw = passwordField("Backup password");
        LinearLayout box = new LinearLayout(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, 0, pad, 0);
        box.addView(pw, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new Ui.Popup(this).setTitle("🔐 Protected backup").setMessage("This backup is locked. Type the password it was saved with.")
                .setView(box)
                .setPositiveButton("Open", (d, w) -> {
                    String p = pw.getText().toString();
                    Toast.makeText(this, "Opening…", Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        byte[] plain = null;
                        String err = null;
                        try { plain = BackupCrypt.decrypt(data, p.toCharArray()); }
                        catch (BackupCrypt.WrongPassword e) { err = "Wrong password"; }
                        catch (Exception e) { err = "That file is not a Note backup"; }
                        final byte[] pl = plain;
                        final String er = err;
                        runOnUiThread(() -> {
                            if (pl != null) restore(pl);
                            else if ("Wrong password".equals(er)) {
                                Toast.makeText(this, "Wrong password — try again", Toast.LENGTH_LONG).show();
                                askBackupPassword(data);
                            } else Toast.makeText(this, er, Toast.LENGTH_LONG).show();
                        });
                    }).start();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void backupPasswordDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, 0, pad, 0);
        EditText a = passwordField("New password (6 or more letters)");
        EditText b = passwordField("Type it again");
        box.addView(a);
        box.addView(b);
        Ui.Popup p = new Ui.Popup(this);
        p.setTitle(store.backupProtected() ? "Change backup password" : "Protect backups with a password")
                .setMessage("Backups — the automatic ones and the ones you export — will be locked (AES-256). "
                        + "You need this password to restore them, also on a new phone.\n\n⚠ If you forget it, nobody can open those backups.")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String x = a.getText().toString(), y = b.getText().toString();
                    if (x.length() < 6) { Toast.makeText(this, "Use at least 6 letters or numbers", Toast.LENGTH_LONG).show(); return; }
                    if (!x.equals(y)) { Toast.makeText(this, "The two passwords are different", Toast.LENGTH_LONG).show(); return; }
                    try {
                        store.setBackupPassword(x);
                        Toast.makeText(this, "Backups are now password-protected", Toast.LENGTH_LONG).show();
                        ReminderSync.runInBackground(() -> AppFolder.autoBackup(this, true), null);
                    } catch (Exception e) {
                        Toast.makeText(this, "Couldn't set it: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                    updateTexts();
                })
                .setNegativeButton("Cancel", null);
        if (store.backupProtected()) p.setNeutralButton("Turn off", (d, w) -> {
            try { store.setBackupPassword(null); } catch (Exception ignored) { }
            Toast.makeText(this, "Backups are no longer protected", Toast.LENGTH_SHORT).show();
            updateTexts();
        });
        p.show();
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
        CheckBox alsoFolder = new CheckBox(this);
        alsoFolder.setText("Also delete the backups, exports and imports in " + AppFolder.displayPath(null));
        alsoFolder.setChecked(true);
        box.addView(alsoFolder);

        new Ui.Popup(this)
                .setTitle("Delete all data?")
                .setMessage("Every note, reminder and setting will be erased from this phone. This cannot be undone.\n\nTip: export a backup first if you might want them later.")
                .setView(box)
                .setPositiveButton("Delete everything", (d, w) -> {
                    boolean cal = alsoCal.isChecked();
                    boolean folder = alsoFolder.isChecked();
                    ReminderSync.runInBackground(() -> {
                        ReminderSync.removeAll(this, cal);
                        if (folder) AppFolder.clearAll(this);
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

    // ------------------------------------------------------------------ look

    private static final java.util.Set<Integer> openSections = new java.util.HashSet<>();

    /** Each settings section is a card: tap its header to open or close the list under it. */
    private void setupSections() {
        android.widget.LinearLayout list = findViewById(R.id.settings_list);
        android.animation.LayoutTransition lt = new android.animation.LayoutTransition();
        lt.enableTransitionType(android.animation.LayoutTransition.CHANGING);
        list.setLayoutTransition(lt);
        for (int i = 0; i < list.getChildCount(); i++) {
            android.view.View c = list.getChildAt(i);
            if (!"section".equals(c.getTag()) || !(c instanceof android.view.ViewGroup)) continue;
            android.view.ViewGroup card = (android.view.ViewGroup) c;
            android.animation.LayoutTransition t = new android.animation.LayoutTransition();
            t.enableTransitionType(android.animation.LayoutTransition.CHANGING);
            card.setLayoutTransition(t);
            android.view.View head = card.getChildAt(0), body = card.getChildAt(1);
            android.view.View chevron = head.findViewWithTag("chevron");
            final int index = i;
            if (openSections.contains(index)) {
                body.setVisibility(android.view.View.VISIBLE);
                if (chevron != null) chevron.setRotation(180f);
            }
            head.setOnClickListener(v -> {
                boolean open = body.getVisibility() != android.view.View.VISIBLE;
                body.setVisibility(open ? android.view.View.VISIBLE : android.view.View.GONE);
                if (open) openSections.add(index); else openSections.remove(index);
                if (chevron != null) chevron.animate().rotation(open ? 180f : 0f).setDuration(200).start();
            });
            prettySwitches(body);
        }
    }

    /** "Title\n(explanation)" on a switch becomes a bold title with a smaller grey line under it. */
    private void prettySwitches(android.view.View v) {
        if (v instanceof Switch) {
            Switch sw = (Switch) v;
            String t = sw.getText().toString();
            int nl = t.indexOf('\n');
            if (nl > 0) {
                String title = t.substring(0, nl);
                String sub = t.substring(nl + 1).trim();
                if (sub.startsWith("(") && sub.endsWith(")")) sub = sub.substring(1, sub.length() - 1);
                android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder(title);
                b.setSpan(new android.text.style.TypefaceSpan("sans-serif-medium") {
                    @Override public void updateDrawState(android.text.TextPaint p) { p.setTypeface(Ui.face(SettingsActivity.this, Ui.SEMIBOLD)); }
                    @Override public void updateMeasureState(android.text.TextPaint p) { p.setTypeface(Ui.face(SettingsActivity.this, Ui.SEMIBOLD)); }
                }, 0, title.length(), 0);
                int at = b.length();
                b.append("\n").append(sub);
                b.setSpan(new android.text.style.RelativeSizeSpan(0.87f), at, b.length(), 0);
                b.setSpan(new android.text.style.ForegroundColorSpan(getColor(R.color.text2)), at, b.length(), 0);
                sw.setText(b);
                sw.setLineSpacing(Ui.dp(this, 2), 1f);
            }
            return;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) prettySwitches(g.getChildAt(i));
        }
    }
}
