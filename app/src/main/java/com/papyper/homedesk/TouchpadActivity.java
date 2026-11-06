package com.papyper.homedesk;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
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

/**
 * Touchpad settings for keyboards with a touchpad. Android has them (Settings.System touchpad_*, read by the
 * input system right away) but HyperOS removed the Settings page, and apps may not change these keys.
 * HomeDesk changes them as the shell user through this tablet's own Wireless debugging (AdbShell).
 */
public class TouchpadActivity extends Activity {

    private enum State { NOT_PAIRED, CONNECTING, WIRELESS_OFF, READY, ERROR }

    private static final int REQUEST_NOTIFICATIONS = 8;
    private static final int MENU_RESET = 1, MENU_PAIR = 2;
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
    private Button action, action2;
    private SeekBar speed;
    private LinearLayout controls;
    private State state = State.CONNECTING;
    /** true while values are being shown, so the change listeners don't write them back */
    private boolean showing;
    private boolean loading;

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
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, Icons.dp(this, 8), 0, 0);
        action = new Button(this);
        action.setAllCaps(false);
        buttons.addView(action);
        action2 = new Button(this);
        action2.setAllCaps(false);
        buttons.addView(action2);
        content.addView(buttons);

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
                + "keyboard off and on.\n\nHomeDesk changes these through this tablet's Wireless debugging, like "
                + "ADB on a PC, so Wireless debugging only has to be on while you change something. "
                + "From a PC instead, for example:\nadb shell settings put system touchpad_tap_dragging 1");
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        note.setAlpha(0.55f);
        note.setTextIsSelectable(true);
        note.setPadding(0, Icons.dp(this, 24), 0, 0);
        content.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh(); // also when coming back from Wireless debugging
    }

    @Override
    protected void onDestroy() {
        io.shutdown(); // AdbShell keeps no connection open between changes
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_RESET, 0, "Reset to defaults");
        menu.add(0, MENU_PAIR, 1, "Pair again");
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(MENU_RESET).setEnabled(state == State.READY);
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
        if (item.getItemId() == MENU_PAIR) {
            startPairing();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_NOTIFICATIONS)
            return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startPairing();
        } else {
            toast("Without notifications, type the pairing code here instead.");
            askCode();
        }
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

    // ---- state -------------------------------------------------------------------------------------------

    /** Connects (when paired) and loads the current values; the result decides what the top of the screen says. */
    private void refresh() {
        if (isFinishing() || isDestroyed() || loading)
            return;
        if (!AdbShell.isPaired(this)) {
            show(State.NOT_PAIRED, null);
            loadDirect();
            return;
        }
        loading = true;
        show(State.CONNECTING, null);
        io.execute(() -> {
            Map<String, String> values = new HashMap<>();
            State result;
            String error = null;
            try {
                for (String line : AdbShell.run(this, "settings", "list", "system").split("\n")) {
                    int eq = line.indexOf('=');
                    if (line.startsWith("touchpad_") && eq > 0)
                        values.put(line.substring(0, eq), line.substring(eq + 1).trim());
                }
                result = State.READY;
            } catch (AdbShell.WirelessDebuggingOffException e) {
                result = State.WIRELESS_OFF;
            } catch (AdbShell.NotPairedException e) {
                result = State.NOT_PAIRED;
            } catch (Exception e) {
                result = State.ERROR;
                error = e.getMessage();
            }
            State r = result;
            String err = error;
            main.post(() -> {
                loading = false;
                if (isFinishing() || isDestroyed())
                    return;
                show(r, err);
                if (r == State.READY)
                    showValues(values);
                else
                    loadDirect();
            });
        });
    }

    private void show(State s, String error) {
        state = s;
        action.setVisibility(View.VISIBLE);
        action2.setVisibility(View.GONE);
        switch (s) {
            case NOT_PAIRED:
                status.setText("Android hides these settings from apps. HomeDesk changes them through this tablet's "
                        + "Wireless debugging, like ADB on a PC. Pair it once:\n"
                        + "1. Tap Pair. Developer options open; turn on Wireless debugging and tap it.\n"
                        + "2. Tap \"Pair device with pairing code\".\n"
                        + "3. Pull down the notifications, tap \"Enter pairing code\" on HomeDesk's notification "
                        + "and type the 6 digits.");
                action.setText("Pair");
                action.setOnClickListener(v -> startPairing());
                action2.setVisibility(View.VISIBLE);
                action2.setText("Type the code here");
                action2.setOnClickListener(v -> askCode());
                break;
            case CONNECTING:
                status.setText("Connecting to Wireless debugging…");
                action.setVisibility(View.GONE);
                break;
            case WIRELESS_OFF:
                status.setText("Turn on Wireless debugging (Developer options, needs Wi-Fi) to change these, then "
                        + "come back. You can turn it off again when you're done.");
                action.setText("Open Wireless debugging");
                action.setOnClickListener(v -> AdbPairing.openWirelessDebugging(this));
                action2.setVisibility(View.VISIBLE);
                action2.setText("Try again");
                action2.setOnClickListener(v -> refresh());
                break;
            case READY:
                status.setText("Connected through Wireless debugging. Changes apply right away.");
                action.setVisibility(View.GONE);
                break;
            case ERROR:
                status.setText("Couldn't connect: " + error);
                action.setText("Try again");
                action.setOnClickListener(v -> refresh());
                action2.setVisibility(View.VISIBLE);
                action2.setText("Pair again");
                action2.setOnClickListener(v -> startPairing());
                break;
        }
        boolean on = s == State.READY;
        for (Switch sw : switches.values())
            sw.setEnabled(on);
        speed.setEnabled(on);
        controls.setAlpha(on ? 1f : 0.5f);
        invalidateOptionsMenu();
    }

    // ---- pairing -------------------------------------------------------------------------------------------

    private void startPairing() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // Android answers at once (denied) when the user refused for good; onRequestPermissionsResult then
            // falls back to typing the code in HomeDesk.
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        AdbPairing.showCodeNotification(this, "In Wireless debugging tap \"Pair device with pairing code\", "
                + "then tap \"Enter pairing code\" here and type the 6 digits it shows.");
        AdbPairing.openWirelessDebugging(this);
    }

    /** For a floating Settings window next to HomeDesk: type the code into HomeDesk itself. */
    private void askCode() {
        EditText code = new EditText(this);
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setHint("6-digit pairing code");
        int pad = Icons.dp(this, 20);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(pad, Icons.dp(this, 8), pad, 0);
        box.addView(code, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Pairing code")
                .setMessage("Keep Wireless debugging › Pair device with pairing code open (for example in a "
                        + "floating window) and type the code it shows.")
                .setView(box)
                .setPositiveButton("Pair", (d, w) -> pairWith(code.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void pairWith(String code) {
        status.setText("Pairing…");
        io.execute(() -> {
            try {
                AdbShell.pair(this, code);
                main.post(() -> {
                    AdbPairing.cancel(this);
                    toast("HomeDesk is paired.");
                    refresh();
                });
            } catch (Exception e) {
                main.post(() -> {
                    toast("Pairing failed: " + e.getMessage());
                    refresh();
                });
            }
        });
    }

    // ---- values --------------------------------------------------------------------------------------------

    /** Without a connection: try reading directly (Android may refuse hidden keys) so the switches aren't blank. */
    private void loadDirect() {
        io.execute(() -> {
            Map<String, String> values = new HashMap<>();
            try {
                for (String[] t : TOGGLES)
                    values.put(t[0], Settings.System.getString(getContentResolver(), t[0]));
                values.put(SPEED, Settings.System.getString(getContentResolver(), SPEED));
            } catch (Exception ignored) {
            }
            main.post(() -> showValues(values));
        });
    }

    private void showValues(Map<String, String> values) {
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
                AdbShell.run(this, "settings", "put", "system", key, value);
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
                    AdbShell.run(this, "settings", "delete", "system", t[0]);
                AdbShell.run(this, "settings", "delete", "system", SPEED);
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

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
}
