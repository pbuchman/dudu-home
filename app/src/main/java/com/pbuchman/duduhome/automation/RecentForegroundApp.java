package com.pbuchman.duduhome.automation;

import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.os.Process;
import com.pbuchman.duduhome.navigation.MapsLauncher;

/** Optional recent lifecycle evidence. Never supplies an automation admission decision. */
final class RecentForegroundApp {
    private static final long WINDOW_MS = 30000;
    private static final int MAX_EVENTS = 256;

    record Observation(String state, String action, String age, String reason) {
        static Observation unknown(String state, String reason) {
            return new Observation(state, "UNKNOWN", "UNKNOWN", reason);
        }

        String fields() {
            return " STATE=" + state + " ACTION=" + action + " AGE=" + age + " REASON=" + reason;
        }
    }

    static Observation observe(Context context) {
        try {
            AppOpsManager ops = context.getSystemService(AppOpsManager.class);
            if (ops == null) return Observation.unknown("UNAVAILABLE", "APP_OPS_UNAVAILABLE");
            if (ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName())
                    != AppOpsManager.MODE_ALLOWED) return Observation.unknown("DENIED", "NO_USAGE_ACCESS");

            KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
            PowerManager power = context.getSystemService(PowerManager.class);
            UsageStatsManager usage = context.getSystemService(UsageStatsManager.class);
            if (keyguard == null || power == null || usage == null)
                return Observation.unknown("GRANTED", "SYSTEM_UNAVAILABLE");
            if (keyguard.isKeyguardLocked()) return Observation.unknown("GRANTED", "LOCKED");
            if (!power.isInteractive()) return Observation.unknown("GRANTED", "SCREEN_OFF");

            long now = System.currentTimeMillis();
            UsageEvents events = usage.queryEvents(now - WINDOW_MS, now);
            if (events == null) return Observation.unknown("GRANTED", "EVENTS_UNAVAILABLE");
            Observation result = latest(context, events, now);
            if (keyguard.isKeyguardLocked()) return Observation.unknown("GRANTED", "LOCKED");
            if (!power.isInteractive()) return Observation.unknown("GRANTED", "SCREEN_OFF");
            return result;
        } catch (SecurityException denied) {
            return Observation.unknown("DENIED", "NO_USAGE_ACCESS");
        } catch (RuntimeException unavailable) {
            return Observation.unknown("UNAVAILABLE", "SYSTEM_UNAVAILABLE");
        }
    }

    // MOVE_TO_FOREGROUND/BACKGROUND have the same codes as ACTIVITY_RESUMED/PAUSED on API 29+.
    @SuppressWarnings("deprecation")
    private static Observation latest(Context context, UsageEvents events, long now) {
        String candidate = null;
        long resumedAt = -1;
        boolean ambiguous = false;
        boolean paused = false;
        UsageEvents.Event event = new UsageEvents.Event();
        for (int read = 0; events.hasNextEvent(); read++) {
            if (read >= MAX_EVENTS) return Observation.unknown("GRANTED", "EVENT_LIMIT");
            if (!events.getNextEvent(event)) return Observation.unknown("GRANTED", "EVENTS_UNAVAILABLE");
            int type = event.getEventType();
            if (type != UsageEvents.Event.MOVE_TO_FOREGROUND && type != UsageEvents.Event.MOVE_TO_BACKGROUND) continue;
            String name = event.getPackageName();
            long time = event.getTimeStamp();
            if (name == null || time < now - WINDOW_MS || time > now) continue;
            if (type == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                if (time > resumedAt) {
                    candidate = name;
                    resumedAt = time;
                    ambiguous = paused = false;
                } else if (time == resumedAt) {
                    if (!name.equals(candidate)) ambiguous = true;
                    else paused = false;
                }
            } else if (name.equals(candidate) && time >= resumedAt) {
                paused = true;
            }
        }
        if (ambiguous) return Observation.unknown("GRANTED", "AMBIGUOUS");
        if (paused) return Observation.unknown("GRANTED", "PAUSED");
        if (candidate == null) return Observation.unknown("GRANTED", "NO_RECENT_RESUME");
        long age = now - resumedAt;
        String bucket = age <= 1000 ? "LE_1S" : age <= 5000 ? "LE_5S" : age <= 15000 ? "LE_15S" : "LE_30S";
        return new Observation("GRANTED", app(context, candidate), bucket, "RESUMED");
    }

    private static String app(Context context, String name) {
        if (context.getPackageName().equals(name)) return "DUDU_HOME";
        if (YanosikLauncher.PACKAGE.equals(name)) return "YANOSIK";
        if (SpotifyController.PACKAGE.equals(name)) return "SPOTIFY";
        if (MapsLauncher.PACKAGE.equals(name)) return "MAPS";
        var home = context.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
        return home != null && home.activityInfo != null && !"android".equals(name)
                && name.equals(home.activityInfo.packageName) ? "LAUNCHER" : "OTHER";
    }
}
