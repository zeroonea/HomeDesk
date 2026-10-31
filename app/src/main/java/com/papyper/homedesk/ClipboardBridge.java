package com.papyper.homedesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.net.Uri;
import android.webkit.WebView;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Clipboard for web apps. Android's WebView never shows Chrome's "Allow clipboard?" prompt; it just
 * refuses navigator.clipboard.read/readText. On the app's own origin only, this replaces
 * navigator.clipboard (readText, writeText, read, write) and the clipboard permission query with
 * versions that go through HomeDesk, which asks once per app (Allow / Block) like Chrome does.
 * Writing (copy from the page to the phone) needs no prompt, as in Chrome, unless the app is blocked.
 */
final class ClipboardBridge {

    static final String ASK = "ask", ALLOW = "allow", BLOCK = "block";
    private static final String NAME = "HomeDeskClipboard";

    private static final String SCRIPT = "(() => {"
            + " const bridge = window." + NAME + ";"
            + " if (!bridge || !navigator.clipboard) return;"
            + " let next = 1; const pending = new Map();"
            + " bridge.onmessage = (e) => {"
            + "   const m = JSON.parse(e.data); const p = pending.get(m.id); if (!p) return; pending.delete(m.id);"
            + "   if (m.ok) p.resolve(m.value); else p.reject(new DOMException(m.error || 'Clipboard access denied', 'NotAllowedError'));"
            + " };"
            + " const call = (op, text) => new Promise((resolve, reject) => {"
            + "   const id = next++; pending.set(id, {resolve, reject});"
            + "   bridge.postMessage(JSON.stringify({id, op, text: text == null ? null : String(text)}));"
            + " });"
            + " const readText = () => call('read');"
            + " const writeText = (t) => call('write', t).then(() => undefined);"
            + " const read = async () => [new ClipboardItem({'text/plain': new Blob([await readText()], {type: 'text/plain'})})];"
            + " const write = async (items) => {"
            + "   for (const it of items || []) {"
            + "     const type = it.types.includes('text/plain') ? 'text/plain' : it.types.includes('text/html') ? 'text/html' : null;"
            + "     if (!type) continue;"
            + "     let t = await (await it.getType(type)).text();"
            + "     if (type === 'text/html') { const d = document.createElement('div'); d.innerHTML = t; t = d.innerText; }"
            + "     return writeText(t);"
            + "   }"
            + "   throw new DOMException('Only text can be copied', 'NotAllowedError');"
            + " };"
            + " const cb = navigator.clipboard;"
            + " for (const [k, f] of Object.entries({readText, writeText, read, write}))"
            + "   Object.defineProperty(cb, k, {value: f, configurable: true, writable: true});"
            + " if (navigator.permissions && navigator.permissions.query) {"
            + "   const query = navigator.permissions.query.bind(navigator.permissions);"
            + "   navigator.permissions.query = (d) => {"
            + "     const n = d && d.name;"
            + "     if (n !== 'clipboard-read' && n !== 'clipboard-write') return query(d);"
            + "     return call('state', n).then((state) => { const s = new EventTarget(); s.name = n; s.state = state; s.onchange = null; return s; });"
            + "   };"
            + " }"
            + "})();";

    private final Activity activity;
    private final SiteHolder holder;
    private ScriptHandler script;
    private WebView installedOn;
    private AlertDialog asking;
    private boolean notNowThisVisit;           // "Not now": don't ask again until the app is reopened
    private final List<Runnable> waiting = new ArrayList<>();

    /** Gives the bridge the current app settings (they can change while the page is open). */
    interface SiteHolder {
        SiteStore.Site site();

        void save(SiteStore.Site site);
    }

    ClipboardBridge(Activity activity, SiteHolder holder) {
        this.activity = activity;
        this.holder = holder;
    }

    static boolean supported() {
        return WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
                && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
    }

    /** "https://host[:port]" of the app's address, the only origin that gets the bridge. */
    private static String origin(String url) {
        Uri u = Uri.parse(url);
        if (u.getScheme() == null || u.getHost() == null)
            return null;
        return u.getScheme().toLowerCase() + "://" + u.getHost().toLowerCase() + (u.getPort() != -1 ? ":" + u.getPort() : "");
    }

    /** (Re)install on a WebView for the app's current address. Call before loading the page. */
    void install(WebView web) {
        uninstall();
        String origin = origin(holder.site().url);
        if (!supported() || origin == null)
            return;
        WebViewCompat.addWebMessageListener(web, NAME, Collections.singleton(origin),
                (view, message, sourceOrigin, isMainFrame, reply) -> {
                    if (isMainFrame && message.getData() != null)
                        handle(message.getData(), reply);
                });
        script = WebViewCompat.addDocumentStartJavaScript(web, SCRIPT, Collections.singleton(origin));
        installedOn = web;
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
    }

    void onAppReopened() {
        notNowThisVisit = false;
    }

    private void handle(String data, JavaScriptReplyProxy reply) {
        int id;
        String op, text;
        try {
            JSONObject m = new JSONObject(data);
            id = m.getInt("id");
            op = m.getString("op");
            text = m.isNull("text") ? null : m.getString("text");
        } catch (JSONException e) {
            return;
        }
        String mode = holder.site().clipboard;
        switch (op) {
            case "state":
                String state = BLOCK.equals(mode) ? "denied"
                        : "clipboard-write".equals(text) || ALLOW.equals(mode) ? "granted" : "prompt";
                answer(reply, id, true, state, null);
                break;
            case "write":
                if (BLOCK.equals(mode)) {
                    answer(reply, id, false, null, "Clipboard is blocked for this app");
                    break;
                }
                clipboard().setPrimaryClip(ClipData.newPlainText(holder.site().name, text == null ? "" : text));
                answer(reply, id, true, null, null);
                break;
            case "read":
                if (ALLOW.equals(mode))
                    answer(reply, id, true, readClipboard(), null);
                else if (BLOCK.equals(mode) || notNowThisVisit)
                    answer(reply, id, false, null, "Clipboard is blocked for this app");
                else
                    askThen(() -> handle(data, reply));
                break;
        }
    }

    /** Chrome-style prompt; requests that arrive meanwhile wait for the same answer. */
    private void askThen(Runnable retry) {
        waiting.add(retry);
        if (asking != null || activity.isFinishing())
            return;
        SiteStore.Site site = holder.site();
        asking = new AlertDialog.Builder(activity)
                .setTitle("Allow " + site.name + " to use the clipboard?")
                .setMessage("It can then read text you copy on this phone, so you can paste it into "
                        + site.name + ". You can change this later in the app's Edit box.")
                .setPositiveButton("Allow", (d, w) -> decide(ALLOW))
                .setNegativeButton("Block", (d, w) -> decide(BLOCK))
                .setNeutralButton("Not now", (d, w) -> { notNowThisVisit = true; decide(null); })
                .setOnCancelListener(d -> { notNowThisVisit = true; decide(null); })
                .show();
    }

    private void decide(String mode) {
        asking = null;
        if (mode != null) {
            SiteStore.Site site = holder.site();
            site.clipboard = mode;
            holder.save(site);
        }
        List<Runnable> run = new ArrayList<>(waiting);
        waiting.clear();
        for (Runnable r : run)
            r.run();
    }

    private ClipboardManager clipboard() {
        return activity.getSystemService(ClipboardManager.class);
    }

    private String readClipboard() {
        ClipData clip = clipboard().getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0)
            return "";
        CharSequence t = clip.getItemAt(0).coerceToText(activity);
        return t == null ? "" : t.toString();
    }

    private static void answer(JavaScriptReplyProxy reply, int id, boolean ok, String value, String error) {
        try {
            JSONObject m = new JSONObject().put("id", id).put("ok", ok);
            if (value != null)
                m.put("value", value);
            if (error != null)
                m.put("error", error);
            reply.postMessage(m.toString());
        } catch (JSONException ignored) {
        }
    }
}
