package com.papyper.homedesk;

import android.app.Activity;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.webkit.WebView;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Read-aloud for web pages. Android's WebView has no Web Speech synthesis: speechSynthesis is missing or
 * silent, so "listen" buttons in learning / reading apps play nothing. This bridge replaces
 * window.speechSynthesis + SpeechSynthesisUtterance with Android's TextToSpeech (the phone's TTS engine,
 * e.g. Speech Services by Google) on every page. Speaking needs no permission, like in Chrome.
 * The TTS engine is started only when a page first uses speech, and stopped when the page changes.
 */
final class SpeechBridge implements TextToSpeech.OnInitListener {

    private static final String NAME = "HomeDeskSpeech";
    private static final Set<String> ANY_ORIGIN = Collections.singleton("*");

    private static final String SCRIPT = """
            (() => {
              const bridge = window.HomeDeskSpeech; if (!bridge) return;
              const post = (m) => bridge.postMessage(JSON.stringify(m));
              let voices = [], asked = false, next = 1; const live = new Map();
              const askVoices = () => { if (!asked) { asked = true; post({op: 'voices'}); } };
              const fire = (t, type, extra) => { const ev = new Event(type); Object.assign(ev, extra || {});
                t.dispatchEvent(ev); const h = t['on' + type];
                if (typeof h === 'function') { try { h.call(t, ev); } catch (x) { setTimeout(() => { throw x; }); } } };
              const info = (u, more) => Object.assign({utterance: u, charIndex: 0, charLength: 0, elapsedTime: 0, name: ''}, more || {});
              class SpeechSynthesisVoice {
                constructor(v) { this.name = v.name; this.voiceURI = v.name; this.lang = v.lang;
                  this.localService = v.local; this.default = v.default; } }
              class SpeechSynthesisUtterance extends EventTarget {
                constructor(text) { super(); this.text = text === undefined ? '' : String(text); this.lang = ''; this.voice = null;
                  this.volume = 1; this.rate = 1; this.pitch = 1;
                  this.onstart = this.onend = this.onerror = this.onpause = this.onresume = this.onmark = this.onboundary = null; } }
              const synth = new EventTarget();
              let onvoiceschanged = null;
              Object.defineProperties(synth, {
                speaking: {get: () => [...live.values()].some((x) => x.started)},
                pending: {get: () => [...live.values()].some((x) => !x.started)},
                paused: {get: () => false},
                onvoiceschanged: {get: () => onvoiceschanged, set: (f) => { onvoiceschanged = f; askVoices(); }},
              });
              const add = synth.addEventListener.bind(synth);
              synth.addEventListener = (type, ...rest) => { if (type === 'voiceschanged') askVoices(); return add(type, ...rest); };
              synth.getVoices = () => { askVoices(); return voices.slice(); };
              synth.speak = (u) => {
                if (!u || typeof u !== 'object') throw new TypeError('speak() needs a SpeechSynthesisUtterance');
                askVoices(); const id = next++; live.set(id, {u, started: false});
                post({op: 'speak', id, text: String(u.text || ''),
                  lang: u.lang || (u.voice && u.voice.lang) || document.documentElement.lang || '',
                  voice: u.voice ? String(u.voice.name || '') : '',
                  rate: Number(u.rate) || 1, pitch: u.pitch === undefined ? 1 : Number(u.pitch),
                  volume: u.volume === undefined ? 1 : Number(u.volume)});
              };
              synth.cancel = () => post({op: 'cancel'});
              synth.pause = () => {}; synth.resume = () => {};
              bridge.onmessage = (e) => { const m = JSON.parse(e.data);
                if (m.event === 'voices') { voices = m.voices.map((v) => new SpeechSynthesisVoice(v)); fire(synth, 'voiceschanged'); return; }
                const x = live.get(m.id); if (!x) return;
                if (m.event === 'start') { x.started = true; fire(x.u, 'start', info(x.u)); }
                else if (m.event === 'boundary') fire(x.u, 'boundary', info(x.u, {charIndex: m.start, charLength: m.end - m.start, name: 'word'}));
                else if (m.event === 'end') { live.delete(m.id); fire(x.u, 'end', info(x.u, {charIndex: x.u.text.length})); }
                else if (m.event === 'error') { live.delete(m.id); fire(x.u, 'error', info(x.u, {error: m.error})); }
              };
              const define = (k, v) => Object.defineProperty(window, k, {value: v, configurable: true, writable: true});
              define('speechSynthesis', synth);
              define('SpeechSynthesisUtterance', SpeechSynthesisUtterance);
              define('SpeechSynthesisVoice', SpeechSynthesisVoice);
            })();
            """;

    /** Where an utterance's events go: the page that asked, and its utterance id there. */
    private static final class Target {
        final JavaScriptReplyProxy reply;
        final int id;

        Target(JavaScriptReplyProxy reply, int id) {
            this.reply = reply;
            this.id = id;
        }
    }

    private final Activity activity;
    private TextToSpeech tts;
    private boolean ready, failed;
    private final List<Runnable> waiting = new ArrayList<>();
    private final Map<String, Target> targets = new HashMap<>();   // Android utterance id -> page
    private WebView installedOn;
    private ScriptHandler script;
    private int seq;

    SpeechBridge(Activity activity) {
        this.activity = activity;
    }

    void install(WebView web) {
        uninstall();
        if (!ClipboardBridge.supported())
            return;
        // every page (also tabs on other sites): speaking is harmless and needs no permission
        WebViewCompat.addWebMessageListener(web, NAME, ANY_ORIGIN, (view, message, origin, isMainFrame, reply) -> {
            if (message.getData() != null)
                handle(message.getData(), reply);
        });
        script = WebViewCompat.addDocumentStartJavaScript(web, SCRIPT, ANY_ORIGIN);
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

    /** A new page loads: stop what the old one was saying (as Chrome does). */
    void onPageStarted() {
        if (ready)
            tts.stop();
    }

    void release() {
        uninstall();
        waiting.clear();
        targets.clear();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        ready = false;
    }

    private void handle(String data, JavaScriptReplyProxy reply) {
        JSONObject m;
        try {
            m = new JSONObject(data);
        } catch (JSONException e) {
            return;
        }
        switch (m.optString("op")) {
            case "voices":
                whenReady(() -> send(reply, voicesMessage()));
                break;
            case "speak":
                whenReady(() -> speak(reply, m));
                break;
            case "cancel":
                whenReady(() -> { if (ready) tts.stop(); });
                break;
        }
    }

    /** Runs now, or once the TTS engine is bound (the first page use starts it). */
    private void whenReady(Runnable r) {
        if (ready || failed) {
            r.run();
            return;
        }
        waiting.add(r);
        if (tts == null)
            tts = new TextToSpeech(activity.getApplicationContext(), this);
    }

    @Override
    public void onInit(int status) {
        if (tts == null)
            return; // released meanwhile
        ready = status == TextToSpeech.SUCCESS;
        failed = !ready;
        if (ready) {
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)          // media volume, like page audio
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            tts.setOnUtteranceProgressListener(new Progress());
        }
        List<Runnable> run = new ArrayList<>(waiting);
        waiting.clear();
        for (Runnable r : run)
            r.run();
    }

    private void speak(JavaScriptReplyProxy reply, JSONObject m) {
        int pageId = m.optInt("id");
        if (!ready) {
            event(new Target(reply, pageId), "error", "error", "synthesis-unavailable");
            return;
        }
        String text = m.optString("text");
        if (text.length() > TextToSpeech.getMaxSpeechInputLength()) {
            event(new Target(reply, pageId), "error", "error", "text-too-long");
            return;
        }
        if (!chooseVoice(m.optString("voice"), m.optString("lang"))) {
            event(new Target(reply, pageId), "error", "error", "language-unavailable");
            return;
        }
        tts.setSpeechRate(clamp((float) m.optDouble("rate", 1), 0.1f, 10f));
        tts.setPitch(clamp((float) m.optDouble("pitch", 1), 0.1f, 2f));
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, clamp((float) m.optDouble("volume", 1), 0f, 1f));
        String uid = "u" + (++seq);
        Target t = new Target(reply, pageId);
        targets.put(uid, t);
        if (tts.speak(text, TextToSpeech.QUEUE_ADD, params, uid) != TextToSpeech.SUCCESS) {
            targets.remove(uid);
            event(t, "error", "error", "synthesis-failed");
        }
    }

    /** The page's voice by name, else its language (en-US, then en), else the phone's default. */
    private boolean chooseVoice(String name, String lang) {
        if (!name.isEmpty())
            for (Voice v : voices())
                if (v.getName().equals(name) && tts.setVoice(v) == TextToSpeech.SUCCESS)
                    return true;
        if (lang.isEmpty())
            return tts.setLanguage(Locale.getDefault()) >= TextToSpeech.LANG_AVAILABLE;
        Locale want = Locale.forLanguageTag(lang.replace('_', '-'));
        if (tts.setLanguage(want) >= TextToSpeech.LANG_AVAILABLE)
            return true;
        return tts.setLanguage(new Locale(want.getLanguage())) >= TextToSpeech.LANG_AVAILABLE;
    }

    private List<Voice> voices() {
        List<Voice> out = new ArrayList<>();
        try {
            Set<Voice> all = tts.getVoices();
            if (all != null)
                for (Voice v : all)
                    if (!v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
                        out.add(v);
        } catch (RuntimeException ignored) { // engine went away
        }
        out.sort((a, b) -> a.getName().compareTo(b.getName()));
        return out;
    }

    private JSONObject voicesMessage() {
        JSONArray list = new JSONArray();
        try {
            if (ready) {
                Voice def = tts.getDefaultVoice();
                for (Voice v : voices())
                    list.put(new JSONObject()
                            .put("name", v.getName())
                            .put("lang", v.getLocale().toLanguageTag())
                            .put("local", !v.isNetworkConnectionRequired())
                            .put("default", def != null && def.getName().equals(v.getName())));
            }
            return new JSONObject().put("event", "voices").put("voices", list);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static void send(JavaScriptReplyProxy reply, JSONObject m) {
        try {
            reply.postMessage(m.toString());
        } catch (IllegalStateException ignored) { // the page is gone
        }
    }

    private static void event(Target t, String name, String key, Object value) {
        try {
            JSONObject m = new JSONObject().put("event", name).put("id", t.id);
            if (key != null)
                m.put(key, value);
            send(t.reply, m);
        } catch (JSONException ignored) {
        }
    }

    private static float clamp(float v, float min, float max) {
        return Float.isNaN(v) ? 1f : Math.max(min, Math.min(max, v));
    }

    /** TTS callbacks come on a binder thread; page messages must go out on the UI thread. */
    private final class Progress extends UtteranceProgressListener {
        private void post(String uid, boolean last, String name, String key, Object value) {
            activity.runOnUiThread(() -> {
                Target t = last ? targets.remove(uid) : targets.get(uid);
                if (t != null)
                    event(t, name, key, value);
            });
        }

        @Override
        public void onStart(String uid) {
            post(uid, false, "start", null, null);
        }

        @Override
        public void onDone(String uid) {
            post(uid, true, "end", null, null);
        }

        @Override
        public void onStop(String uid, boolean interrupted) { // cancel() or a new page
            post(uid, true, "end", null, null);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onError(String uid) {
            post(uid, true, "error", "error", "synthesis-failed");
        }

        @Override
        public void onError(String uid, int code) {
            post(uid, true, "error", "error", code == TextToSpeech.ERROR_NETWORK
                    || code == TextToSpeech.ERROR_NETWORK_TIMEOUT ? "network" : "synthesis-failed");
        }

        @Override
        public void onRangeStart(String uid, int start, int end, int frame) {
            activity.runOnUiThread(() -> {
                Target t = targets.get(uid);
                if (t == null)
                    return;
                try {
                    send(t.reply, new JSONObject().put("event", "boundary").put("id", t.id).put("start", start).put("end", end));
                } catch (JSONException ignored) {
                }
            });
        }
    }
}
