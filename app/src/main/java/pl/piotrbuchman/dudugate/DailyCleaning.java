package pl.piotrbuchman.dudugate;

import android.content.Context;
import android.content.SharedPreferences;
import java.time.LocalDate;
import java.time.ZoneId;

final class DailyCleaning {
    static boolean reserve(Context c) { return reserve(c, LocalDate.now(ZoneId.of("Europe/Warsaw")).toEpochDay()); }
    static synchronized boolean reserve(Context c, long day) {
        SharedPreferences s = c.getSharedPreferences("daily_cleaning", 0);
        if (day <= s.getLong("last_attempt_day", Long.MIN_VALUE)) return false;
        return s.edit().putLong("last_attempt_day", day).commit();
    }
}
