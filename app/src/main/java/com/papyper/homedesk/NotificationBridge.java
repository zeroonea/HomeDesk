package com.papyper.homedesk;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.webkit.WebView;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Web notifications for apps. Android's WebView has no Notification API: pages see permission "denied"
 * and nothing is ever shown. On the app's own origin this replaces window.Notification (plus
 * registration.showNotification and the "notifications" permission query) with a version that posts
 * real Android notifications. Asking works like Chrome: HomeDesk asks Allow / Block once per app, then
 * Android asks for its own notification permission once. Tapping a notification brings back the
 * window that sent it and fires the page's onclick.
 * Like in a browser tab, this works while the page is open (also in the background), not after the
 * window is closed: WebView has no push messages.
 */
final class NotificationBridge {

    static final String EXTRA_NOTIFICATION = "notification_id";
    static final int REQ_PERMISSION = 2;
    private static final String NAME = "HomeDeskNotify";
    private static final String CHANNEL = "web";

    private static final String SCRIPT = "(() => {"
            + " const bridge = window." + NAME + "; if (!bridge) return;"
            + " let permission = '__STATE__';"
            + " let next = 1; const pending = new Map(); const live = new Map();"
            + " const post = (m) => bridge.postMessage(JSON.stringify(m));"
            + " const fire = (n, type) => { const ev = new Event(type); n.dispatchEvent(ev);"
            + "   const h = n['on' + type]; if (typeof h === 'function') { try { h.call(n, ev); } catch (x) { setTimeout(() => { throw x; }); } } };"
            + " bridge.onmessage = (e) => { const m = JSON.parse(e.data);"
            + "   if (m.event === 'permission') { permission = m.state; return; }"
            + "   if (m.event === 'click') { const n = live.get(m.id); if (n) fire(n, 'click'); return; }"
            + "   const p = pending.get(m.id); if (p) { pending.delete(m.id); p(m.state); } };"
            + " class HomeDeskNotification extends EventTarget {"
            + "   constructor(title, options) { super(); options = options || {};"
            + "     this.title = String(title); this.body = options.body ? String(options.body) : ''; this.tag = options.tag ? String(options.tag) : '';"
            + "     this.data = options.data === undefined ? null : options.data; this.icon = options.icon || ''; this.silent = !!options.silent;"
            + "     this.requireInteraction = !!options.requireInteraction; this.onclick = this.onclose = this.onerror = this.onshow = null;"
            + "     const id = next++; this._id = id;"
            + "     if (permission !== 'granted') { setTimeout(() => fire(this, 'error')); return; }"
            + "     live.set(id, this); post({op: 'show', id, title: this.title, body: this.body, tag: this.tag, silent: this.silent});"
            + "     setTimeout(() => fire(this, 'show')); }"
            + "   close() { if (live.delete(this._id)) { post({op: 'close', id: this._id}); fire(this, 'close'); } }"
            + "   static get permission() { return permission; }"
            + "   static get maxActions() { return 0; }"
            + "   static requestPermission(cb) { return new Promise((resolve) => { const id = next++;"
            + "     pending.set(id, (s) => { permission = s; if (typeof cb === 'function') cb(s); resolve(s); }); post({op: 'request', id}); }); }"
            + " }"
            + " Object.defineProperty(window, 'Notification', {value: HomeDeskNotification, configurable: true, writable: true});"
            + " if (window.ServiceWorkerRegistration) ServiceWorkerRegistration.prototype.showNotification ="
            + "   function (title, options) { new HomeDeskNotification(title, options); return Promise.resolve(); };"
            + " if (navigator.permissions && navigator.permissions.query) {"
            + "   const query = navigator.permissions.query.bind(navigator.permissions);"
            + "   navigator.permissions.query = (d) => { const n = d && d.name;"
            + "     if (n !== 'notifications' && n !== 'push') return query(d);"
            + "     const s = new EventTarget(); s.name = n; s.state = permission === 'default' ? 'prompt' : permission; s.onchange = null;"
            + "     return Promise.resolve(s); };"
            + " }"
            + "})();";

    private final Activity activity;
    private final ClipboardBridge.SiteHolder holder;
    private ScriptHandler script;
    private WebView installedOn;
    private String origin;
    private JavaScriptReplyProxy page;              // last page that talked to us (for click events)
    private AlertDialog asking;
    private boolean notNowThisVisit;
    private final List<Runnable> waitingForAnswer = new ArrayList<>();
    private final List<Runnable> waitingForAndroid = new ArrayList<>();
    private final Map<Integer, Integer> androidToPage = new HashMap<>(); // Android notification id -> page id

    NotificationBridge(Activity activity, ClipboardBridge.SiteHolder holder) {
        this.activity = activity;
        this.holder = holder;
    }

    private static String originOf(String url) {
        Uri u = Uri.parse(url);
        if (u.getScheme() == null || u.getHost() == null)
            return null;
        return u.getScheme().toLowerCase() + "://" + u.getHost().toLowerCase() + (u.getPort() != -1 ? ":" + u.getPort() : "");
    }

    void install(WebView web) {
        uninstall();
        origin = originOf(holder.site().url);
        if (!ClipboardBridge.supported() || origin == null)
            return;
        WebViewCompat.addWebMessageListener(web, NAME, Collections.singleton(origin),
                (view, message, sourceOrigin, isMainFrame, reply) -> {
                    if (isMainFrame && message.getData() != null) {
                        page = reply;
                        handle(message.getData(), reply);
                    }
                });
        installedOn = web;
        installScript();
    }

    /** The script carries Notification.permission for the next page load, so renew it when that changes. */
    private void installScript() {
        if (script != null)
            script.remove();
        script = installedOn == null ? null
                : WebViewCompat.addDocumentStartJavaScript(installedOn, SCRIPT.replace("__STATE__", state()), Collections.singleton(origin));
    }

    void uninstall() {
        if (installedOn != null) {
            WebViewCompat.removeWebMessageListener(installedOn, NAME);
            installedOn = null;
        }
        if (script != null) {
            script.remove();
            script = null;
        }
        page = null;
    }

    void onAppReopened() {
        notNowThisVisit = false;
        installScript(); // Android permission may have been changed in Settings meanwhile
        tellPage(state());
    }

    private boolean androidAllows() {
        return activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    /** What the page sees as Notification.permission. */
    private String state() {
        String mode = holder.site().notifications;
        if (ClipboardBridge.BLOCK.equals(mode))
            return "denied";
        if (ClipboardBridge.ALLOW.equals(mode) && androidAllows())
            return "granted";
        return "default";
    }

    private void tellPage(String state) {
        if (page == null)
            return;
        try {
            page.postMessage(new JSONObject().put("event", "permission").put("state", state).toString());
        } catch (JSONException | IllegalStateException ignored) {
        }
    }

    private void handle(String data, JavaScriptReplyProxy reply) {
        JSONObject m;
        try {
            m = new JSONObject(data);
        } catch (JSONException e) {
            return;
        }
        int id = m.optInt("id");
        switch (m.optString("op")) {
            case "request":
                request(() -> answer(reply, id, state()));
                break;
            case "show":
                if ("granted".equals(state()))
                    show(id, m.optString("title"), m.optString("body"), m.optString("tag"), m.optBoolean("silent"));
                break;
            case "close":
                for (Map.Entry<Integer, Integer> e : new ArrayList<>(androidToPage.entrySet()))
                    if (e.getValue() == id) {
                        manager().cancel(e.getKey());
                        androidToPage.remove(e.getKey());
                    }
                break;
        }
    }

    /** Notification.requestPermission(): our Allow/Block, then Android's permission. */
    private void request(Runnable done) {
        String mode = holder.site().notifications;
        if (ClipboardBridge.BLOCK.equals(mode) || (ClipboardBridge.ASK.equals(mode) && notNowThisVisit)) {
            done.run();
        } else if (ClipboardBridge.ALLOW.equals(mode)) {
            askAndroid(done);
        } else {
            waitingForAnswer.add(done);
            if (asking != null || activity.isFinishing())
                return;
            SiteStore.Site site = holder.site();
            asking = new AlertDialog.Builder(activity)
                    .setTitle("Allow " + site.name + " to show notifications?")
                    .setMessage("It can then show notifications on this phone while it is open, also in the background. "
                            + "You can change this later in the app's Edit box.")
                    .setPositiveButton("Allow", (d, w) -> decide(ClipboardBridge.ALLOW))
                    .setNegativeButton("Block", (d, w) -> decide(ClipboardBridge.BLOCK))
                    .setNeutralButton("Not now", (d, w) -> { notNowThisVisit = true; decide(null); })
                    .setOnCancelListener(d -> { notNowThisVisit = true; decide(null); })
                    .show();
        }
    }

    private void decide(String mode) {
        asking = null;
        if (mode != null) {
            SiteStore.Site site = holder.site();
            site.notifications = mode;
            holder.save(site);
        }
        List<Runnable> run = new ArrayList<>(waitingForAnswer);
        waitingForAnswer.clear();
        if (ClipboardBridge.ALLOW.equals(mode)) {
            for (Runnable r : run)
                askAndroid(r);
        } else {
            installScript();
            for (Runnable r : run)
                r.run();
        }
    }

    /** Android 13+ asks the user once for HomeDesk; after "Don't allow" only Settings can turn it on. */
    private void askAndroid(Runnable done) {
        if (androidAllows()) {
            installScript();
            done.run();
            return;
        }
        waitingForAndroid.add(done);
        if (waitingForAndroid.size() == 1)
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_PERMISSION);
    }

    /** From WebActivity.onRequestPermissionsResult. */
    void onAndroidAnswer() {
        installScript();
        if (!androidAllows() && !activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
            offerSettings();
        List<Runnable> run = new ArrayList<>(waitingForAndroid);
        waitingForAndroid.clear();
        for (Runnable r : run)
            r.run();
    }

    private void offerSettings() {
        new AlertDialog.Builder(activity)
                .setTitle("Notifications are off for HomeDesk")
                .setMessage("Android has notifications turned off for HomeDesk. Turn them on in Settings to get notifications from "
                        + holder.site().name + ".")
                .setPositiveButton("Open settings", (d, w) -> activity.startActivity(
                        new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName())))
                .setNegativeButton("Not now", null)
                .show();
    }

    private NotificationManager manager() {
        NotificationManager nm = activity.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "App notifications",
                    NotificationManager.IMPORTANCE_DEFAULT));
        return nm;
    }

    private void show(int pageId, String title, String body, String tag, boolean silent) {
        SiteStore.Site site = holder.site();
        // same tag replaces the earlier notification, like in Chrome
        int androidId = tag.isEmpty() ? (int) (System.nanoTime() & 0x7fffffff) : (site.id + "|" + tag).hashCode();
        androidToPage.put(androidId, pageId);

        // tapping brings back this very window (app or tab) and tells the page
        Intent open = new Intent(activity.getIntent()).putExtra(EXTRA_NOTIFICATION, androidId);
        PendingIntent tap = PendingIntent.getActivity(activity, androidId, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = new Notification.Builder(activity, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setLargeIcon(Icons.bitmap(site, Icons.dp(activity, 48), true))
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setSubText(site.name)
                .setColor(Icons.color(site))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setShowWhen(true)
                .setGroup("site-" + site.id);
        if (silent)
            b.setOnlyAlertOnce(true);
        try {
            manager().notify(androidId, b.build());
        } catch (SecurityException ignored) { // permission withdrawn meanwhile
        }
    }

    /** From WebActivity when a notification was tapped. */
    void onNotificationTapped(int androidId) {
        Integer pageId = androidToPage.remove(androidId);
        if (pageId == null || page == null)
            return;
        try {
            page.postMessage(new JSONObject().put("event", "click").put("id", pageId).toString());
        } catch (JSONException | IllegalStateException ignored) {
        }
    }

    private static void answer(JavaScriptReplyProxy reply, int id, String state) {
        try {
            reply.postMessage(new JSONObject().put("id", id).put("state", state).toString());
        } catch (JSONException | IllegalStateException ignored) {
        }
    }
}
