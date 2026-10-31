package com.papyper.homedesk;

import android.app.ActivityManager;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Letter icons for sites, Recents entries and home-screen shortcuts. */
final class Icons {

    private static final int[] COLORS = {
            0xFF2563EB, 0xFF059669, 0xFFDC2626, 0xFF7C3AED,
            0xFFD97706, 0xFF0891B2, 0xFFDB2777, 0xFF4B5563,
    };

    static int color(SiteStore.Site site) {
        return COLORS[(int) Math.floorMod(site.id, (long) COLORS.length)];
    }

    static String letter(SiteStore.Site site) {
        String n = site.name.trim();
        return n.isEmpty() ? "?" : n.substring(0, 1).toUpperCase();
    }

    /** Filled circle (round = true) or full-bleed square for adaptive icons, with the site's letter. */
    static Bitmap bitmap(SiteStore.Site site, int sizePx, boolean round) {
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(color(site));
        if (round)
            c.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, bg);
        else
            c.drawRect(0, 0, sizePx, sizePx, bg);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(0xFFFFFFFF);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        text.setTextAlign(Paint.Align.CENTER);
        // adaptive icons are cropped to the inner 66%, so keep the letter smaller there
        text.setTextSize(sizePx * (round ? 0.5f : 0.36f));
        Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText(letter(site), sizePx / 2f, sizePx / 2f - (fm.ascent + fm.descent) / 2f, text);
        return bmp;
    }

    static int dp(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    static Intent siteIntent(Context context, SiteStore.Site site) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("homedesk://site/" + site.id), context, WebActivity.class);
    }

    /**
     * A new tab of an app: its own window and Recents card (unique data URI, documentLaunchMode=intoExisting),
     * homedesk://site/<id>/tab/<n>, starting at {@code url}.
     */
    static Intent tabIntent(Context context, SiteStore.Site site, String url) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("homedesk://site/" + site.id + "/tab/" + System.nanoTime()),
                context, WebActivity.class).putExtra(WebActivity.EXTRA_TAB_URL, url);
    }

    static void setTaskDescription(Activity activity, SiteStore.Site site) {
        setTaskDescription(activity, site, site.name);
    }

    @SuppressWarnings("deprecation")
    static void setTaskDescription(Activity activity, SiteStore.Site site, String label) {
        activity.setTaskDescription(new ActivityManager.TaskDescription(
                label, bitmap(site, dp(activity, 48), true), color(site)));
    }

    private static ShortcutInfo shortcut(Context context, SiteStore.Site site) {
        return new ShortcutInfo.Builder(context, "site-" + site.id)
                .setShortLabel(site.name.isEmpty() ? site.url : site.name)
                .setIcon(Icon.createWithAdaptiveBitmap(bitmap(site, dp(context, 108), false)))
                .setIntent(siteIntent(context, site))
                .build();
    }

    static void pinShortcut(Context context, SiteStore.Site site) {
        ShortcutManager sm = context.getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) {
            Toast.makeText(context, "Your launcher doesn't support shortcuts", Toast.LENGTH_SHORT).show();
            return;
        }
        sm.requestPinShortcut(shortcut(context, site), null);
    }

    /** Long-press menu on the app icon lists the first sites; pinned shortcuts follow edits/deletes. */
    static void syncShortcuts(Context context, List<SiteStore.Site> sites, List<Long> deletedIds) {
        ShortcutManager sm = context.getSystemService(ShortcutManager.class);
        if (sm == null)
            return;
        List<ShortcutInfo> all = new ArrayList<>();
        for (SiteStore.Site s : sites)
            all.add(shortcut(context, s));
        int max = Math.min(sm.getMaxShortcutCountPerActivity(), 4);
        try {
            sm.setDynamicShortcuts(new ArrayList<>(all.subList(0, Math.min(max, all.size()))));
            sm.updateShortcuts(all); // refresh label/icon of pinned shortcuts
            if (deletedIds != null && !deletedIds.isEmpty()) {
                List<String> ids = new ArrayList<>();
                for (Long id : deletedIds)
                    ids.add("site-" + id);
                sm.disableShortcuts(ids, "This app was removed from HomeDesk");
            }
        } catch (RuntimeException ignored) { // rate limiting / launcher quirks: shortcuts are a nicety
        }
    }
}
