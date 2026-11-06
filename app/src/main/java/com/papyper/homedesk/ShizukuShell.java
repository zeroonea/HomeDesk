package com.papyper.homedesk;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import rikka.shizuku.Shizuku;

/**
 * Runs shell commands with ADB-shell rights through Shizuku (https://shizuku.rikka.app).
 * HomeDesk needs this for settings Android keeps hidden from apps (e.g. touchpad_* in Settings.System):
 * the settings provider only lets system, root and shell change non-public keys.
 */
final class ShizukuShell {

    static final String PACKAGE = "moe.shizuku.privileged.api";

    enum State { NOT_INSTALLED, NOT_RUNNING, TOO_OLD, NO_PERMISSION, DENIED, READY }

    private ShizukuShell() {}

    static State state(Context c) {
        try {
            c.getPackageManager().getPackageInfo(PACKAGE, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return State.NOT_INSTALLED;
        }
        if (!Shizuku.pingBinder())
            return State.NOT_RUNNING;
        if (Shizuku.isPreV11())
            return State.TOO_OLD;
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
            return State.READY;
        // true once the user picked "Deny" (Shizuku then stops showing its prompt)
        return Shizuku.shouldShowRequestPermissionRationale() ? State.DENIED : State.NO_PERMISSION;
    }

    /** Shizuku's own app (to start it or change its app list), or null. */
    static Intent launchIntent(Context c) {
        return c.getPackageManager().getLaunchIntentForPackage(PACKAGE);
    }

    /**
     * Runs cmd as the shell user and returns its output. Throws with the command's error text when it fails.
     * Call off the main thread. Shizuku.newProcess is private in API 13 (they point to UserService, which is
     * overkill for a few `settings` calls), so it is called by reflection.
     */
    static String run(String... cmd) throws Exception {
        Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
        m.setAccessible(true);
        Process p;
        try {
            p = (Process) m.invoke(null, cmd, null, null);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause instanceof Exception ? (Exception) cause : new Exception(cause);
        }
        String out = read(p.getInputStream());
        String err = read(p.getErrorStream());
        int code = p.waitFor();
        if (code != 0)
            throw new IOException(err.trim().isEmpty() ? "exit code " + code : err.trim());
        return out;
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = s.read(b)) > 0)
                buf.write(b, 0, n);
            return buf.toString(StandardCharsets.UTF_8.name());
        }
    }
}
