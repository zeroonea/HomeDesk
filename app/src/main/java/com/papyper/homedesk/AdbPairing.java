package com.papyper.homedesk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;

/**
 * Pairing HomeDesk with Wireless debugging. Android's "Pair device with pairing code" dialog closes (and stops its
 * pairing service) when another app comes to the front, so the code is typed into a notification instead: open the
 * dialog, pull down the notification shade, reply with the 6 digits. The receiver then pairs in the background.
 */
public class AdbPairing extends BroadcastReceiver {

    private static final String CHANNEL = "adb_pairing";
    private static final int NOTIFICATION_ID = 7301;
    private static final String KEY_CODE = "code";
    private static final String ACTION_PAIR = "com.papyper.homedesk.ADB_PAIR";

    /** Posts the "enter pairing code" notification. Needs Android's notification permission. */
    static void showCodeNotification(Context c, String message) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Wireless debugging pairing",
                    NotificationManager.IMPORTANCE_HIGH));
        Intent reply = new Intent(c, AdbPairing.class).setAction(ACTION_PAIR);
        // RemoteInput needs a mutable PendingIntent (Android fills in the typed text)
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, reply,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        RemoteInput input = new RemoteInput.Builder(KEY_CODE).setLabel("6-digit pairing code").build();
        Notification.Action action = new Notification.Action.Builder(
                Icon.createWithResource(c, R.drawable.ic_launcher_foreground), "Enter pairing code", pi)
                .addRemoteInput(input).build();
        nm.notify(NOTIFICATION_ID, new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Pair HomeDesk with Wireless debugging")
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .addAction(action)
                .build());
    }

    private static void showResult(Context c, String title, String text) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        Intent open = new Intent(c, TouchpadActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent tap = PendingIntent.getActivity(c, 1, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(NOTIFICATION_ID, new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setTimeoutAfter(60_000)
                .build());
    }

    static void cancel(Context c) {
        c.getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
    }

    /** Opens Developer options at Wireless debugging (Android highlights the entry). */
    static void openWirelessDebugging(Context c) {
        Bundle args = new Bundle();
        args.putString(":settings:fragment_args_key", "toggle_adb_wireless");
        Intent i = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                .putExtra(":settings:show_fragment_args", args)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (ActivityNotFoundException e) {
            c.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_PAIR.equals(intent.getAction()))
            return;
        Bundle results = RemoteInput.getResultsFromIntent(intent);
        CharSequence code = results == null ? null : results.getCharSequence(KEY_CODE);
        if (code == null)
            return;
        Context app = context.getApplicationContext();
        showCodeNotification(app, "Pairing…");
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                AdbShell.pair(app, code.toString());
                showResult(app, "HomeDesk is paired",
                        "You can close Wireless debugging. Open Touchpad settings in HomeDesk to change them.");
            } catch (Exception e) {
                showCodeNotification(app, "Pairing failed: " + e.getMessage()
                        + "\nOpen Pair device with pairing code again and enter the new code.");
            } finally {
                pending.finish();
            }
        }, "adb-pair").start();
    }
}
