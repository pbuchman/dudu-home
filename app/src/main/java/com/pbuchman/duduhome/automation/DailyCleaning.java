package com.pbuchman.duduhome.automation;



import android.content.Context;
import android.content.SharedPreferences;
import java.time.LocalDate;
import java.time.ZoneId;

public final class DailyCleaning {
    public static synchronized boolean available(Context c, long day) {
        return day > c.getSharedPreferences("daily_cleaning", 0).getLong("last_attempt_day", Long.MIN_VALUE);
    }
    public static boolean reserve(Context c) { return reserve(c, LocalDate.now(ZoneId.of("Europe/Warsaw")).toEpochDay()); }
    public static synchronized boolean reserve(Context c, long day) {
        SharedPreferences s = c.getSharedPreferences("daily_cleaning", 0);
        if (day <= s.getLong("last_attempt_day", Long.MIN_VALUE)) return false;
        return s.edit().putLong("last_attempt_day", day).commit();
    }
}
