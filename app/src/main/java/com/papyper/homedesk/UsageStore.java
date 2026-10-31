package com.papyper.homedesk;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * On-screen time per app and day, plus how often each app was opened. Only time the app is actually
 * on screen counts (a card waiting in Recents doesn't). Kept on the phone in private SharedPreferences:
 *   t|<id>|<yyyyMMdd> = milliseconds on screen, n|<id>|<yyyyMMdd> = times opened, last|<id> = last use.
 */
final class UsageStore {

    private static final int KEEP_DAYS = 90;
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final SharedPreferences prefs;

    UsageStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("usage", Context.MODE_PRIVATE);
    }

    static LocalDate today() {
        return LocalDate.now();
    }

    private static String day(LocalDate d) {
        return d.format(DAY);
    }

    /** Adds on-screen time between two wall-clock times, split at midnight. */
    void addTime(long appId, long fromMs, long toMs) {
        if (toMs <= fromMs || toMs - fromMs > 24L * 3600_000 * 2) // clock jumps: ignore
            return;
        ZoneId zone = ZoneId.systemDefault();
        SharedPreferences.Editor e = prefs.edit();
        long from = fromMs;
        while (from < toMs) {
            ZonedDateTime start = Instant.ofEpochMilli(from).atZone(zone);
            long nextMidnight = start.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            long to = Math.min(toMs, nextMidnight);
            String key = "t|" + appId + "|" + day(start.toLocalDate());
            e.putLong(key, prefs.getLong(key, 0) + (to - from));
            from = to;
        }
        e.putLong("last|" + appId, toMs);
        e.apply();
    }

    void countOpen(long appId) {
        String key = "n|" + appId + "|" + day(today());
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1)
                .putLong("last|" + appId, System.currentTimeMillis()).apply();
    }

    long time(long appId, LocalDate d) {
        return prefs.getLong("t|" + appId + "|" + day(d), 0);
    }

    int opens(long appId, LocalDate d) {
        return prefs.getInt("n|" + appId + "|" + day(d), 0);
    }

    /** Sum over the last {@code days} days including today. */
    long time(long appId, int days) {
        long sum = 0;
        LocalDate d = today();
        for (int i = 0; i < days; i++)
            sum += time(appId, d.minusDays(i));
        return sum;
    }

    int opens(long appId, int days) {
        int sum = 0;
        LocalDate d = today();
        for (int i = 0; i < days; i++)
            sum += opens(appId, d.minusDays(i));
        return sum;
    }

    long lastUsed(long appId) {
        return prefs.getLong("last|" + appId, 0);
    }

    void forget(long appId) {
        SharedPreferences.Editor e = prefs.edit();
        for (String key : prefs.getAll().keySet())
            if (key.contains("|" + appId + "|") || key.equals("last|" + appId))
                e.remove(key);
        e.apply();
    }

    void clearAll() {
        prefs.edit().clear().apply();
    }

    /** Drops days older than KEEP_DAYS. */
    void prune() {
        String oldest = day(today().minusDays(KEEP_DAYS));
        SharedPreferences.Editor e = prefs.edit();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String[] parts = entry.getKey().split("\\|");
            if (parts.length == 3 && parts[2].compareTo(oldest) < 0)
                e.remove(entry.getKey());
        }
        e.apply();
    }

    // ---- formatting ----

    /** 0 -> "0m", 65 min -> "1h 5m", 40 s -> "<1m". */
    static String duration(long ms) {
        long min = ms / 60_000;
        if (ms > 0 && min == 0)
            return "<1m";
        return min >= 60 ? (min / 60) + "h " + (min % 60) + "m" : min + "m";
    }

    /** "just now", "5 min ago", "3 h ago", "yesterday", "12 Oct". */
    static String ago(long when) {
        if (when == 0)
            return "never";
        long min = (System.currentTimeMillis() - when) / 60_000;
        if (min < 1)
            return "just now";
        if (min < 60)
            return min + " min ago";
        LocalDate d = Instant.ofEpochMilli(when).atZone(ZoneId.systemDefault()).toLocalDate();
        long days = ChronoUnit.DAYS.between(d, today());
        if (days == 0)
            return (min / 60) + " h ago";
        if (days == 1)
            return "yesterday";
        return d.format(DateTimeFormatter.ofPattern("d MMM"));
    }
}
