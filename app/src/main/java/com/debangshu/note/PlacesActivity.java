package com.debangshu.note;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/** Saved places for "when I reach office…" reminders. A place is saved where you are standing. */
public class PlacesActivity extends Screen {

    private static final int REQ_FINE = 7801, REQ_BG = 7802;
    private String pendingName;

    @Override
    protected void onCreate(android.os.Bundle b) {
        super.onCreate(b);
        topButton("＋", "Save this place", true, v -> askName(null));
    }

    @Override
    protected void build() {
        body.removeAllViews();
        List<Geo.Place> places = Geo.places(this);
        List<Geo.Item> items = Geo.items(this);
        setTitles("Places", places.isEmpty() ? "For “when I reach…” reminders" : places.size() + (places.size() == 1 ? " place" : " places"));

        boolean perms = Geo.hasLocation(this) && Geo.hasBackground(this);
        if (!perms && (!places.isEmpty() || !items.isEmpty())) {
            LinearLayout w = card("⚠️", "Location is off for Note", "Place reminders can't work yet");
            w.addView(hint("Android needs to let Note see where the phone is — also when Note is closed — to remind you when "
                    + "you arrive. Pick “Allow all the time”. Your location never leaves the phone."));
            TextView fix = pill("Allow location", true);
            fix.setOnClickListener(v -> askPermissions());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = px(10);
            w.addView(fix, lp);
        }

        LinearLayout pc = card("📍", "Your places", places.isEmpty() ? "None yet" : "Tap one to change it");
        if (places.isEmpty()) {
            pc.addView(hint("Go to the place (home, office, college…), tap ＋ at the top and give it a name. "
                    + "Then write in any note: “when I reach office call Rahul”, “buy milk when I get to the market”, "
                    + "“when I leave office call mom”, or “office pahunch ke mail bhejna”."));
        }
        for (Geo.Place p : places) {
            int n = 0;
            for (Geo.Item it : items) if (it.place.equals(p.name.toLowerCase(Locale.ROOT))) n++;
            pc.addView(row("📍  " + cap(p.name), "Within " + p.radius + " m · " + (n == 0 ? "no reminders" : n + (n == 1 ? " reminder" : " reminders")),
                    "›", v -> placeMenu(p)));
        }

        if (!items.isEmpty()) {
            LinearLayout ic = card("🔔", "Waiting for you", items.size() + (items.size() == 1 ? " place reminder" : " place reminders"));
            List<String> names = Geo.placeNames(this);
            for (Geo.Item it : items) {
                boolean known = names.contains(it.place);
                ic.addView(row(it.task, (it.leaving ? "When you leave " : "When you reach ") + it.place
                                + (known ? "" : "  ·  ⚠ not saved yet — tap to save it here"), null,
                        v -> { if (!known) askName(it.place); else openNote(it.noteId); }));
            }
        }
    }

    private static String cap(String s) { return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1); }

    private void placeMenu(Geo.Place p) {
        String[] items = {"📍  Move it to where I am now", "↔  Size: 100 m", "↔  Size: 200 m", "↔  Size: 500 m", "🗺  Show on a map", "🗑  Forget this place"};
        new Ui.Popup(this).setTitle(cap(p.name)).setItems(items, (d, w) -> {
            List<Geo.Place> all = Geo.places(this);
            for (Geo.Place x : all) {
                if (!x.name.equals(p.name)) continue;
                if (w == 0) { pendingName = p.name; saveHere(p.name); return; }
                if (w == 1) x.radius = 100;
                if (w == 2) x.radius = 200;
                if (w == 3) x.radius = 500;
                if (w == 4) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + x.lat + "," + x.lng + "?q=" + x.lat + "," + x.lng))); }
                    catch (Exception e) { Toast.makeText(this, "No maps app found", Toast.LENGTH_SHORT).show(); }
                    return;
                }
            }
            if (w == 5) all.removeIf(x -> x.name.equals(p.name));
            Geo.savePlaces(this, all);
            build();
            Ui.fonts(root);
        }).show();
    }

    private void askName(String suggestion) {
        EditText et = new EditText(this);
        et.setHint("Name (e.g. office)");
        if (suggestion != null) et.setText(suggestion);
        et.setSingleLine(true);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(20), px(4), px(20), 0);
        box.addView(et);
        LinearLayout quick = new LinearLayout(this);
        quick.setPadding(0, px(8), 0, 0);
        for (String q : new String[]{"home", "office", "college", "gym", "market"}) {
            TextView t = pill(q, false);
            t.setTextSize(13);
            t.setPadding(px(12), px(6), px(12), px(6));
            t.setOnClickListener(v -> { et.setText(q); et.setSelection(q.length()); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = px(6);
            quick.addView(t, lp);
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.addView(quick);
        box.addView(hs);
        Ui.fonts(box);
        new Ui.Popup(this).setTitle("Save where you are now").setView(box)
                .setMessage("Stand at the place, then save it. Write this name in your notes: “when I reach office…”.")
                .setPositiveButton("Save", (d, w) -> {
                    String n = GeoParse.normalise(et.getText().toString());
                    if (n.isEmpty()) return;
                    pendingName = n;
                    saveHere(n);
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void saveHere(String name) {
        if (!Geo.hasLocation(this)) { askPermissions(); return; }
        Toast.makeText(this, "Finding where you are…", Toast.LENGTH_SHORT).show();
        Geo.here(this, loc -> {
            if (loc == null) {
                Toast.makeText(this, "Couldn't find the location. Turn on Location (GPS) and try again.", Toast.LENGTH_LONG).show();
                return;
            }
            List<Geo.Place> all = Geo.places(this);
            all.removeIf(x -> x.name.equalsIgnoreCase(name));
            Geo.Place p = new Geo.Place();
            p.name = name;
            p.lat = loc.getLatitude();
            p.lng = loc.getLongitude();
            p.radius = loc.getAccuracy() > 120 ? 250 : 150;
            all.add(p);
            Geo.savePlaces(this, all);
            pendingName = null;
            Toast.makeText(this, "Saved “" + name + "”", Toast.LENGTH_SHORT).show();
            if (!Geo.hasBackground(this)) askPermissions();
            build();
            Ui.fonts(root);
        });
    }

    private void askPermissions() {
        if (!Geo.hasLocation(this)) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_FINE);
            return;
        }
        if (!Geo.hasBackground(this) && Build.VERSION.SDK_INT >= 29) {
            new Ui.Popup(this).setTitle("One more step")
                    .setMessage("To remind you when you arrive, even with Note closed, choose “Allow all the time” on the next screen.")
                    .setPositiveButton("Continue", (d, w) -> {
                        if (Build.VERSION.SDK_INT >= 30) {
                            try {
                                requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQ_BG);
                            } catch (Exception e) {
                                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                            }
                        } else requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQ_BG);
                    })
                    .setNegativeButton("Not now", null).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_FINE && Geo.hasLocation(this)) {
            if (pendingName != null) saveHere(pendingName);
            else askPermissions();
        }
        Geo.syncAsync(this);
        build();
        Ui.fonts(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Geo.syncAsync(this);
    }
}
