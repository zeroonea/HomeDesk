package com.papyper.homedesk;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** The saved sites, kept as one JSON array in the app's private SharedPreferences. */
final class SiteStore {

    static final class Site {
        long id;
        String name = "";
        String url = "";
        boolean fullScreen = false;      // hide status + navigation bars (toggle button on the page)
        boolean keepScreenOn = true;
        boolean keyboardOverlay = false; // true: keyboard covers the page; false: page shrinks above it
        String clipboard = ClipboardBridge.ASK; // ask / allow / block reading the phone's clipboard
        String notifications = ClipboardBridge.ASK; // ask / allow / block web notifications
        String microphone = ClipboardBridge.ASK;    // ask / allow / block recording (MediaPermissions)

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("id", id).put("name", name).put("url", url)
                    .put("fullScreen", fullScreen).put("keepScreenOn", keepScreenOn)
                    .put("keyboardOverlay", keyboardOverlay).put("clipboard", clipboard)
                    .put("notifications", notifications).put("microphone", microphone);
        }

        static Site fromJson(JSONObject o) {
            Site s = new Site();
            s.id = o.optLong("id");
            s.name = o.optString("name");
            s.url = o.optString("url");
            s.fullScreen = o.optBoolean("fullScreen", false);
            s.keepScreenOn = o.optBoolean("keepScreenOn", true);
            s.keyboardOverlay = o.optBoolean("keyboardOverlay", false);
            s.clipboard = o.optString("clipboard", ClipboardBridge.ASK);
            s.notifications = o.optString("notifications", ClipboardBridge.ASK);
            s.microphone = o.optString("microphone", ClipboardBridge.ASK);
            return s;
        }
    }

    private static final String PREFS = "sites";
    private static final String KEY_SITES = "sites";
    private static final String KEY_START = "startSiteId";   // site opened when the app starts, 0 = none
    private static final String KEY_SCHEMA = "schema";
    private static final int SCHEMA = 2;

    private final SharedPreferences prefs;

    SiteStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    List<Site> load() {
        String raw = prefs.getString(KEY_SITES, null);
        if (raw == null) {
            List<Site> sites = new ArrayList<>(); // first start: empty list, the user adds their apps
            save(sites);
            prefs.edit().putInt(KEY_SCHEMA, SCHEMA).apply();
            return sites;
        }
        List<Site> sites = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++)
                sites.add(Site.fromJson(arr.getJSONObject(i)));
        } catch (JSONException ignored) {
        }
        if (prefs.getInt(KEY_SCHEMA, 1) < SCHEMA)
            migrate(sites);
        return sites;
    }

    /** v1 -> v2: full screen became opt-in. */
    private void migrate(List<Site> sites) {
        for (Site s : sites)
            s.fullScreen = false;
        save(sites);
        prefs.edit().putInt(KEY_SCHEMA, SCHEMA).apply();
    }

    long startSiteId() {
        return prefs.getLong(KEY_START, 0);
    }

    void setStartSiteId(long id) {
        prefs.edit().putLong(KEY_START, id).apply();
    }

    /** Saves one changed site (e.g. full screen toggled on the page). */
    void update(Site site) {
        List<Site> sites = load();
        for (int i = 0; i < sites.size(); i++)
            if (sites.get(i).id == site.id)
                sites.set(i, site);
        save(sites);
    }

    void save(List<Site> sites) {
        JSONArray arr = new JSONArray();
        try {
            for (Site s : sites)
                arr.put(s.toJson());
        } catch (JSONException ignored) {
        }
        prefs.edit().putString(KEY_SITES, arr.toString()).apply();
    }

    Site find(long id) {
        for (Site s : load())
            if (s.id == id)
                return s;
        return null;
    }

    static long newId() {
        return System.currentTimeMillis();
    }

    /** "example.com" -> "https://example.com". */
    static String normalizeUrl(String url) {
        url = url.trim();
        if (!url.isEmpty() && !url.matches("(?i)^[a-z][a-z0-9+.-]*://.*"))
            url = "https://" + url;
        return url;
    }
}
