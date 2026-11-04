package com.papyper.homedesk;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.webkit.PermissionRequest;

/**
 * Microphone for web pages (getUserMedia({audio}) — voice recording, Guacamole audio input, calls).
 * WebView denies every media request unless the app grants it in WebChromeClient.onPermissionRequest,
 * and the app itself needs Android's RECORD_AUDIO. Like Chrome, HomeDesk asks once per app and remembers
 * the answer (the app's Microphone setting in Edit: Ask / Allow / Block); pages on other addresses (tabs)
 * are asked every time and never remembered. Camera and other media are refused (not asked for).
 *
 * Sound playback needs no permission: autoplay is already on (setMediaPlaybackRequiresUserGesture(false)).
 */
final class MediaPermissions {

    static final int REQ_PERMISSION = 3;

    private final Activity activity;
    private final ClipboardBridge.SiteHolder holder;
    private PermissionRequest pending;          // the page's request being answered (one at a time)
    private AlertDialog asking;
    private boolean notNowThisVisit;

    MediaPermissions(Activity activity, ClipboardBridge.SiteHolder holder) {
        this.activity = activity;
        this.holder = holder;
    }

    void onAppReopened() {
        notNowThisVisit = false;
    }

    /** From WebChromeClient.onPermissionRequest (UI thread). */
    void onRequest(PermissionRequest request) {
        if (!wantsMicrophone(request)) {
            request.deny();
            return;
        }
        if (pending != null) {                  // a second request while one is open: answer the newest
            pending.deny();
            dismiss();
        }
        pending = request;
        if (!ownOrigin(request.getOrigin())) {
            ask(request.getOrigin().getHost(), false);
            return;
        }
        String mode = holder.site().microphone;
        if (ClipboardBridge.ALLOW.equals(mode))
            askAndroid();
        else if (ClipboardBridge.BLOCK.equals(mode) || notNowThisVisit)
            finish(false);
        else
            ask(holder.site().name, true);
    }

    /** From WebChromeClient.onPermissionRequestCanceled: the page gave up (e.g. navigated away). */
    void onCanceled(PermissionRequest request) {
        if (request == pending) {
            pending = null;
            dismiss();
        }
    }

    /** From WebActivity.onRequestPermissionsResult. */
    void onAndroidAnswer() {
        boolean ok = androidAllows();
        if (!ok && !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO))
            offerSettings();
        finish(ok);
    }

    void release() {
        dismiss();
        if (pending != null) {
            pending.deny();
            pending = null;
        }
    }

    private static boolean wantsMicrophone(PermissionRequest request) {
        for (String r : request.getResources())
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r))
                return true;
        return false;
    }

    private boolean ownOrigin(Uri origin) {
        Uri app = Uri.parse(holder.site().url);
        return origin != null && app.getScheme() != null && app.getHost() != null
                && app.getScheme().equalsIgnoreCase(origin.getScheme())
                && app.getHost().equalsIgnoreCase(origin.getHost())
                && port(app) == port(origin);
    }

    private static int port(Uri u) {
        if (u.getPort() != -1)
            return u.getPort();
        return "http".equalsIgnoreCase(u.getScheme()) ? 80 : 443;
    }

    private void ask(String who, boolean remember) {
        if (activity.isFinishing()) {
            finish(false);
            return;
        }
        asking = new AlertDialog.Builder(activity)
                .setTitle("Allow " + who + " to use the microphone?")
                .setMessage(remember
                        ? "It can then record sound from this phone's microphone while it is open. "
                          + "You can change this later in the app's Edit box."
                        : "This page is outside the app's own address, so HomeDesk will ask again next time.")
                .setPositiveButton("Allow", (d, w) -> decide(remember ? ClipboardBridge.ALLOW : null, true))
                .setNegativeButton("Block", (d, w) -> decide(remember ? ClipboardBridge.BLOCK : null, false))
                .setNeutralButton("Not now", (d, w) -> { notNowThisVisit = remember; decide(null, false); })
                .setOnCancelListener(d -> { notNowThisVisit = remember; decide(null, false); })
                .show();
    }

    private void decide(String saveMode, boolean allow) {
        asking = null;
        if (saveMode != null) {
            SiteStore.Site site = holder.site();
            site.microphone = saveMode;
            holder.save(site);
        }
        if (allow)
            askAndroid();
        else
            finish(false);
    }

    /** Android asks once for HomeDesk itself; after "Don't allow" twice only Settings can turn it on. */
    private void askAndroid() {
        if (androidAllows())
            finish(true);
        else
            activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERMISSION);
    }

    private boolean androidAllows() {
        return activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void finish(boolean grant) {
        PermissionRequest r = pending;
        pending = null;
        if (r == null)
            return;
        if (grant)
            r.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        else
            r.deny();
    }

    private void dismiss() {
        if (asking != null) {
            AlertDialog d = asking;
            asking = null;
            d.setOnCancelListener(null);
            d.dismiss();
        }
    }

    private void offerSettings() {
        if (activity.isFinishing())
            return;
        new AlertDialog.Builder(activity)
                .setTitle("Microphone is off for HomeDesk")
                .setMessage("Android has the microphone turned off for HomeDesk. Turn it on in Settings › Permissions "
                        + "so " + holder.site().name + " can record sound.")
                .setPositiveButton("Open settings", (d, w) -> activity.startActivity(
                        new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", activity.getPackageName(), null))))
                .setNegativeButton("Not now", null)
                .show();
    }
}
