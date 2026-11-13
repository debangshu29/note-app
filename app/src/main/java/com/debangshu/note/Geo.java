package com.debangshu.note;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingEvent;
import com.google.android.gms.location.GeofencingRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Location reminders: your saved places (kept on the phone) and the "when I reach office…" lines in notes.
 * Android watches the places for us (Google Play services geofencing, light on battery); when you arrive
 * at or leave a place, the matching lines show as a notification.
 */
public final class Geo {

    private Geo() {}

    public static final class Place {
        public String name;
        public double lat, lng;
        public int radius = 150;
    }

    public static final class Item {
        public long noteId;
        public String line, task, place;
        public boolean leaving;
        public String key() { return noteId + "|" + Integer.toHexString(line.trim().hashCode()); }
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("places", Context.MODE_PRIVATE); }

    public static List<Place> places(Context c) {
        List<Place> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Place p = new Place();
                p.name = o.getString("name");
                p.lat = o.getDouble("lat");
                p.lng = o.getDouble("lng");
                p.radius = o.optInt("r", 150);
                out.add(p);
            }
        } catch (Exception ignored) { }
        return out;
    }

    public static void savePlaces(Context c, List<Place> list) {
        JSONArray a = new JSONArray();
        try {
            for (Place p : list) {
                JSONObject o = new JSONObject();
                o.put("name", p.name);
                o.put("lat", p.lat);
                o.put("lng", p.lng);
                o.put("r", p.radius);
                a.put(o);
            }
        } catch (Exception ignored) { }
        prefs(c).edit().putString("list", a.toString()).putString("sig", "").apply();
        sync(c);
    }

    public static List<String> placeNames(Context c) {
        List<String> n = new ArrayList<>();
        for (Place p : places(c)) n.add(p.name.toLowerCase(Locale.ROOT));
        return n;
    }

    /** Every open location line in the notes. */
    public static List<Item> items(Context c) {
        List<Item> out = new ArrayList<>();
        List<String> names = placeNames(c);
        java.util.Set<String> done = prefs(c).getStringSet("done", new java.util.HashSet<>());
        for (Note n : Store.get(c).notes()) {
            if (n.deleted > 0 || n.body == null) continue;
            for (String l : n.body.split("\n")) {
                GeoParse.Hit h = GeoParse.parse(l, names);
                if (h == null) continue;
                Item it = new Item();
                it.noteId = n.id;
                it.line = l.trim();
                it.task = h.task;
                it.place = h.place;
                it.leaving = h.leaving;
                if (done.contains(it.key())) continue;
                out.add(it);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ permissions

    public static boolean hasLocation(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean hasBackground(Context c) {
        return Build.VERSION.SDK_INT < 29
                || c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    // ------------------------------------------------------------------ geofences

    private static PendingIntent pi(Context c) {
        Intent i = new Intent(c, Receiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        return PendingIntent.getBroadcast(c, 7700, i, flags);
    }

    /** Watches the places that have reminders. Cheap to call often: only does work when something changed. */
    @SuppressWarnings("MissingPermission")
    public static void sync(Context c) {
        Context app = c.getApplicationContext();
        Map<String, Place> byName = new LinkedHashMap<>();
        for (Place p : places(app)) byName.put(p.name.toLowerCase(Locale.ROOT), p);
        Map<String, Integer> want = new LinkedHashMap<>();   // place → transitions
        for (Item it : items(app)) {
            if (!byName.containsKey(it.place)) continue;
            int t = it.leaving ? Geofence.GEOFENCE_TRANSITION_EXIT : Geofence.GEOFENCE_TRANSITION_ENTER;
            want.put(it.place, (want.containsKey(it.place) ? want.get(it.place) : 0) | t);
        }
        StringBuilder sig = new StringBuilder();
        for (Map.Entry<String, Integer> e : want.entrySet()) {
            Place p = byName.get(e.getKey());
            sig.append(e.getKey()).append(':').append(e.getValue()).append(':').append(p.lat).append(',').append(p.lng).append(',').append(p.radius).append(';');
        }
        SharedPreferences sp = prefs(app);
        boolean can = hasLocation(app) && hasBackground(app);
        String s = (can ? "1" : "0") + sig;
        if (s.equals(sp.getString("sig", null))) return;
        try {
            LocationServices.getGeofencingClient(app).removeGeofences(pi(app));
            if (!can || want.isEmpty()) { sp.edit().putString("sig", s).apply(); return; }
            List<Geofence> fences = new ArrayList<>();
            for (Map.Entry<String, Integer> e : want.entrySet()) {
                Place p = byName.get(e.getKey());
                fences.add(new Geofence.Builder()
                        .setRequestId(e.getKey())
                        .setCircularRegion(p.lat, p.lng, Math.max(100, p.radius))
                        .setExpirationDuration(Geofence.NEVER_EXPIRE)
                        .setTransitionTypes(e.getValue())
                        .setNotificationResponsiveness(60_000)
                        .build());
                if (fences.size() >= 90) break;
            }
            GeofencingRequest req = new GeofencingRequest.Builder().setInitialTrigger(0).addGeofences(fences).build();
            LocationServices.getGeofencingClient(app).addGeofences(req, pi(app))
                    .addOnSuccessListener(x -> sp.edit().putString("sig", s).apply());
        } catch (Throwable ignored) { }
    }

    /** After a restart or an update: register everything again. */
    public static void resync(Context c) {
        prefs(c).edit().putString("sig", "").apply();
        syncAsync(c);
    }

    public static void deleteAll(Context c) {
        try { LocationServices.getGeofencingClient(c.getApplicationContext()).removeGeofences(pi(c.getApplicationContext())); } catch (Throwable ignored) { }
        prefs(c).edit().clear().apply();
    }

    public static void syncAsync(Context c) {
        Context app = c.getApplicationContext();
        ReminderSync.runInBackground(() -> sync(app), null);
    }

    /** Gets where the phone is now. */
    @SuppressWarnings("MissingPermission")
    public static void here(Activity a, java.util.function.Consumer<android.location.Location> got) {
        try {
            LocationServices.getFusedLocationProviderClient(a).getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener(got::accept)
                    .addOnFailureListener(e -> got.accept(null));
        } catch (Throwable t) {
            got.accept(null);
        }
    }

    // ------------------------------------------------------------------ arriving / leaving

    private static final String CHANNEL = "places";
    static final String ACTION_DONE = "com.debangshu.note.PLACE_DONE";

    public static class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            if (ACTION_DONE.equals(i.getAction())) {
                String key = i.getStringExtra("key");
                if (key != null) {
                    java.util.Set<String> done = new java.util.HashSet<>(prefs(c).getStringSet("done", new java.util.HashSet<>()));
                    done.add(key);
                    prefs(c).edit().putStringSet("done", done).putString("sig", "").apply();
                }
                NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(i.getIntExtra("nid", 0));
                syncAsync(c);
                return;
            }
            GeofencingEvent ev;
            try { ev = GeofencingEvent.fromIntent(i); } catch (Throwable t) { return; }
            if (ev == null || ev.hasError() || ev.getTriggeringGeofences() == null) return;
            boolean leaving = ev.getGeofenceTransition() == Geofence.GEOFENCE_TRANSITION_EXIT;
            List<String> placesHit = new ArrayList<>();
            for (Geofence g : ev.getTriggeringGeofences()) placesHit.add(g.getRequestId());
            for (Item it : items(c)) {
                if (it.leaving != leaving || !placesHit.contains(it.place)) continue;
                show(c, it);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static void show(Context c, Item it) {
        if (!Notifier.canNotify(c)) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, L.t("Place reminders"), NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription(L.t("“When I reach office…” reminders"));
            nm.createNotificationChannel(ch);
        }
        int nid = 770000 + (it.key().hashCode() & 0xFFFF);
        Intent open = new Intent(c, EditorActivity.class);
        open.putExtra(EditorActivity.EXTRA_NOTE_ID, it.noteId);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Intent done = new Intent(c, Receiver.class);
        done.setAction(ACTION_DONE);
        done.putExtra("key", it.key());
        done.putExtra("nid", nid);
        String place = it.place.isEmpty() ? it.place : it.place.substring(0, 1).toUpperCase(Locale.ROOT) + it.place.substring(1);
        String where = L.f(it.leaving ? "Leaving {0}" : "At {0}", place);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL)
                : new Notification.Builder(c).setDefaults(Notification.DEFAULT_ALL).setPriority(Notification.PRIORITY_HIGH);
        b.setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("📍 " + it.task)
                .setContentText(where)
                .setColor(Ui.col(c, R.color.accent))
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, nid, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .addAction(new Notification.Action.Builder(null, L.t("Done ✓"),
                        PendingIntent.getBroadcast(c, nid + 1, done, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build());
        PendingIntent call = Notifier.callIntent(c, fake(it));
        if (call != null) b.addAction(new Notification.Action.Builder(null, L.t("📞 Call"), call).build());
        nm.notify(nid, b.build());
    }

    private static Reminder fake(Item it) {
        Reminder r = new Reminder();
        r.noteId = it.noteId;
        r.sourceLine = it.line;
        r.title = it.task;
        return r;
    }

    /** Places screen: forget "done" marks for a line (when you edit it, it becomes new anyway). */
    public static void clearDone(Context c) { prefs(c).edit().remove("done").putString("sig", "").apply(); }
}
