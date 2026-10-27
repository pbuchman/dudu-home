package com.pbuchman.duduhome.automation;



import android.content.Context;
import android.content.SharedPreferences;
import java.time.LocalDate;
import java.time.ZoneId;

public final class DailyCleaning {
    public static synchronized boolean available(Context c, long day) {
        return day > c.getSharedPreferences("daily_cleaning", 0).getLong("last_attempt_day", Long.MIN_VALUE);
    }
    public static long day() { return LocalDate.now(ZoneId.of("Europe/Warsaw")).toEpochDay(); }
    public static boolean reserve(Context c) { return reserve(c, day()); }
    /** Recheck the same queued Warsaw day inside the serialized persistence boundary. */
    public static synchronized boolean reserveAtSend(Context c, long expectedDay, java.util.function.LongSupplier clock) {
        return clock.getAsLong() == expectedDay && reserve(c, expectedDay);
    }
    public static synchronized boolean reserve(Context c, long day) {
        SharedPreferences s = c.getSharedPreferences("daily_cleaning", 0);
        long previous = s.getLong("last_attempt_day", Long.MIN_VALUE);
        if (day <= previous) return false;
        if (s.edit().putLong("last_attempt_day", day).commit()) return true;
        // Failed disk commits still update Android's preference cache. This unsent write may
        // be rolled back, but an earlier admitted-send marker must always be preserved.
        SharedPreferences.Editor restore = s.edit();
        if (previous == Long.MIN_VALUE) restore.remove("last_attempt_day");
        else restore.putLong("last_attempt_day", previous);
        restore.commit();
        return false;
    }
}
