package com.papyper.homedesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/**
 * Touchpad settings for keyboards with a touchpad. Android has them (Settings.System touchpad_*, read by the
 * input system right away) but HyperOS removed the Settings page, and apps may not change these keys.
 * HomeDesk changes them as the shell user through Shizuku (see ShizukuShell).
 */
public class TouchpadActivity extends Activity {

    private static final int REQUEST_SHIZUKU = 7;
    private static final int MENU_RESET = 1;
    private static final String SPEED = "touchpad_pointer_speed";
    private static final int SPEED_MIN = -7, SPEED_MAX = 7;

    /** key, title, summary, Android's default when the key is unset */
    private static final String[][] TOGGLES = {
            {"touchpad_tap_to_click", "Tap to click",
                    "A light tap clicks. Two-finger tap right-clicks.", "1"},
            {"touchpad_tap_dragging", "Tap dragging",
                    "Tap, then tap again and keep the finger down to drag or select text.", "0"},
            {"touchpad_natural_scrolling", "Natural scrolling",
                    "The page moves with your fingers. Off: scrolls the other way, like a mouse wheel.", "1"},
            {"touchpad_right_click_zone", "Bottom-right corner right-clicks",
                    "Pressing the bottom-right corner of the touchpad is a right click.", "0"},
            {"touchpad_system_gestures", "Three and four finger gestures",
                    "Swipe with three or four fingers for Home, Back and Recent apps.", "1"},
            {"touchpad_acceleration_enabled", "Pointer acceleration",
                    "Moving a finger faster moves the pointer further.", "1"},
    };

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Switch> switches = new HashMap<>();
    private TextView status, speedLabel;
    private Button action;
    private SeekBar speed;
    private LinearLayout controls;
    private ShizukuShell.State state;
    /** true while values are being shown, so the change listeners don't write them back */
    private boolean showing;

    private final Shizuku.OnBinderReceivedListener binderReceived = this::refresh;
    private final Shizuku.OnBinderDeadListener binderDead = this::refresh;
    private final Shizuku.OnRequestPermissionResultListener permissionResult = (code, result) -> refresh();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Touchpad");
        if (getActionBar() != null)
            getActionBar().setDisplayHomeAsUpEnabled(true);

        int pad = Icons.dp(this, 16);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, Icons.dp(this, 8), pad, pad * 2);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        content.addView(status);
        action = new Button(this);
        action.setAllCaps(false);
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        actionLp.topMargin = Icons.dp(this, 8);
        content.addView(action, actionLp);

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(0, Icons.dp(this, 16), 0, 0);
        content.addView(controls);

        for (String[] t : TOGGLES) {
            Switch s = new Switch(this);
            s.setText(t[1]);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            s.setPadding(0, Icons.dp(this, 14), 0, 0);
            s.setOnCheckedChangeListener((b, on) -> {
                if (!showing)
                    put(t[0], on ? "1" : "0");
            });
            controls.addView(s, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            controls.addView(summary(t[2]));
            switches.put(t[0], s);
        }

        speedLabel = new TextView(this);
        speedLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        speedLabel.setPadding(0, Icons.dp(this, 20), 0, 0);
        controls.addView(speedLabel);
        speed = new SeekBar(this);
        speed.setMax(SPEED_MAX - SPEED_MIN);
        speed.setPadding(Icons.dp(this, 8), Icons.dp(this, 12), Icons.dp(this, 8), Icons.dp(this, 4));
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean fromUser) { showSpeed(p + SPEED_MIN); }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) {
                if (!showing)
                    put(SPEED, String.valueOf(b.getProgress() + SPEED_MIN));
            }
        });
        controls.addView(speed);
        controls.addView(summary("Pointer speed for the touchpad. 0 is Android's default."));

        Button tutorial = new Button(this);
        tutorial.setAllCaps(false);
        tutorial.setText("Touchpad gestures tutorial");
        tutorial.setOnClickListener(v -> openTutorial());
        LinearLayout.LayoutParams tutLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tutLp.topMargin = Icons.dp(this, 20);
        content.addView(tutorial, tutLp);

        TextView note = new TextView(this);
        note.setText("Changes apply right away and stay after a restart. If one doesn't take effect, turn the "
                + "keyboard off and on.\n\nFrom a PC with ADB instead of Shizuku, for example:\n"
                + "adb shell settings put system touchpad_tap_dragging 1");
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        note.setAlpha(0.55f);
        note.setTextIsSelectable(true);
        note.setPadding(0, Icons.dp(this, 24), 0, 0);
        content.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);

        Shizuku.addBinderReceivedListenerSticky(binderReceived, main);
        Shizuku.addBinderDeadListener(binderDead, main);
        Shizuku.addRequestPermissionResultListener(permissionResult, main);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh(); // back from Shizuku's app
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived);
        Shizuku.removeBinderDeadListener(binderDead);
        Shizuku.removeRequestPermissionResultListener(permissionResult);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_RESET, 0, "Reset to defaults");
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(MENU_RESET).setEnabled(state == ShizukuShell.State.READY);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        if (item.getItemId() == MENU_RESET) {
            new AlertDialog.Builder(this)
                    .setTitle("Reset touchpad settings?")
                    .setMessage("All touchpad options go back to Android's defaults (tap dragging off).")
                    .setPositiveButton("Reset", (d, w) -> reset())
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private TextView summary(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setAlpha(0.65f);
        t.setPadding(0, Icons.dp(this, 2), Icons.dp(this, 56), 0);
        return t;
    }

    private void showSpeed(int v) {
        speedLabel.setText("Pointer speed: " + (v > 0 ? "+" + v : String.valueOf(v)));
    }

    /** Shows what Shizuku needs next, then loads the current values. */
    private void refresh() {
        if (isFinishing() || isDestroyed())
            return;
        state = ShizukuShell.state(this);
        action.setVisibility(View.VISIBLE);
        switch (state) {
            case NOT_INSTALLED:
                status.setText("Android hides these settings from apps. HomeDesk can change them with Shizuku, a free "
                        + "app that gives other apps ADB rights. Install Shizuku, start it, then come back.");
                action.setText("Get Shizuku");
                action.setOnClickListener(v -> openUri("market://details?id=" + ShizukuShell.PACKAGE,
                        "https://play.google.com/store/apps/details?id=" + ShizukuShell.PACKAGE));
                break;
            case NOT_RUNNING:
                status.setText("Shizuku is installed but not running. Open it and tap Start (with Wireless debugging "
                        + "on). It has to be started again after each restart of the tablet.");
                action.setText("Open Shizuku");
                action.setOnClickListener(v -> openShizuku());
                break;
            case TOO_OLD:
                status.setText("This Shizuku is too old. Update it from Play Store.");
                action.setText("Update Shizuku");
                action.setOnClickListener(v -> openUri("market://details?id=" + ShizukuShell.PACKAGE,
                        "https://play.google.com/store/apps/details?id=" + ShizukuShell.PACKAGE));
                break;
            case NO_PERMISSION:
                status.setText("Shizuku is running. Allow HomeDesk to use it.");
                action.setText("Allow HomeDesk");
                action.setOnClickListener(v -> Shizuku.requestPermission(REQUEST_SHIZUKU));
                break;
            case DENIED:
                status.setText("HomeDesk was denied in Shizuku. Open Shizuku and allow HomeDesk in its app list.");
                action.setText("Open Shizuku");
                action.setOnClickListener(v -> openShizuku());
                break;
            case READY:
                status.setText("Ready. Changes apply right away.");
                action.setVisibility(View.GONE);
                break;
        }
        setControlsEnabled(state == ShizukuShell.State.READY);
        invalidateOptionsMenu();
        load();
    }

    private void setControlsEnabled(boolean on) {
        for (Switch s : switches.values())
            s.setEnabled(on);
        speed.setEnabled(on);
        controls.setAlpha(on ? 1f : 0.5f);
    }

    /** Reads the touchpad_* values: through Shizuku when allowed, else directly (Android may refuse that). */
    private void load() {
        boolean viaShell = state == ShizukuShell.State.READY;
        io.execute(() -> {
            Map<String, String> values = new HashMap<>();
            try {
                if (viaShell) {
                    for (String line : ShizukuShell.run("settings", "list", "system").split("\n")) {
                        int eq = line.indexOf('=');
                        if (line.startsWith("touchpad_") && eq > 0)
                            values.put(line.substring(0, eq), line.substring(eq + 1).trim());
                    }
                } else {
                    for (String[] t : TOGGLES)
                        values.put(t[0], Settings.System.getString(getContentResolver(), t[0]));
                    values.put(SPEED, Settings.System.getString(getContentResolver(), SPEED));
                }
            } catch (Exception e) {
                if (viaShell)
                    main.post(() -> toast("Couldn't read the touchpad settings: " + e.getMessage()));
            }
            main.post(() -> show(values));
        });
    }

    private void show(Map<String, String> values) {
        if (isFinishing() || isDestroyed())
            return;
        showing = true;
        for (String[] t : TOGGLES) {
            String v = values.get(t[0]);
            if (v == null || v.isEmpty() || v.equals("null"))
                v = t[3];
            switches.get(t[0]).setChecked(!v.equals("0"));
        }
        int s = 0;
        try {
            String v = values.get(SPEED);
            if (v != null && !v.equals("null"))
                s = Math.max(SPEED_MIN, Math.min(SPEED_MAX, Integer.parseInt(v.trim())));
        } catch (NumberFormatException ignored) {
        }
        speed.setProgress(s - SPEED_MIN);
        showSpeed(s);
        showing = false;
    }

    private void put(String key, String value) {
        io.execute(() -> {
            try {
                ShizukuShell.run("settings", "put", "system", key, value);
            } catch (Exception e) {
                main.post(() -> {
                    toast("Couldn't change it: " + e.getMessage());
                    refresh();
                });
            }
        });
    }

    private void reset() {
        io.execute(() -> {
            try {
                for (String[] t : TOGGLES)
                    ShizukuShell.run("settings", "delete", "system", t[0]);
                ShizukuShell.run("settings", "delete", "system", SPEED);
            } catch (Exception e) {
                main.post(() -> toast("Couldn't reset: " + e.getMessage()));
            }
            main.post(this::refresh);
        });
    }

    private void openTutorial() {
        try {
            startActivity(new Intent("com.android.systemui.action.TOUCHPAD_TUTORIAL"));
        } catch (ActivityNotFoundException | SecurityException e) {
            toast("This tablet doesn't have Android's touchpad tutorial.");
        }
    }

    private void openShizuku() {
        Intent i = ShizukuShell.launchIntent(this);
        if (i != null)
            startActivity(i);
        else
            toast("Couldn't open Shizuku.");
    }

    private void openUri(String uri, String fallback) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(fallback)));
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
}
