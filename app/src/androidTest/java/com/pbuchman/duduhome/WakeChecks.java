package com.pbuchman.duduhome;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import com.pbuchman.duduhome.startup.HomeWakeActivity;

/** Emulator only via SafetyChecks. No GPS, quota reset or external executor. */
final class WakeChecks {
    static void run(Instrumentation i) throws Exception {
        var context = i.getTargetContext();
        var info = context.getPackageManager().getActivityInfo(new ComponentName(context, HomeWakeActivity.class), 0);
        if ((info.flags & ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS) != 0)
            throw new AssertionError("Wake task must survive concurrent recent-task trimming before onCreate");
        if (info.launchMode != ActivityInfo.LAUNCH_SINGLE_INSTANCE)
            throw new AssertionError("Wake must remain isolated from the user's menu");
        var prefs = context.getSharedPreferences("journey_session", 0);
        var before = new java.util.HashMap<>(prefs.getAll());
        var activity = i.startActivitySync(new Intent(context, HomeWakeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        i.waitForIdleSync();
        if (!activity.isFinishing()) throw new AssertionError("Wake entry must remove its own task");
        if (!before.equals(prefs.getAll())) throw new AssertionError("Wake entry must not rearm a journey");
    }
}
