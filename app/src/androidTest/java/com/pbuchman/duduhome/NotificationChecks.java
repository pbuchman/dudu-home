package com.pbuchman.duduhome;

import android.app.Instrumentation;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import com.pbuchman.duduhome.automation.YanosikPresence;

/** Invoked only behind SafetyChecks' physical-device refusal. Never reads notification contents. */
final class NotificationChecks {
    static void run(Instrumentation i) throws Exception {
        boolean previouslyGranted = YanosikPresence.accessGranted(i.getTargetContext());
        try {
            permission(i, false);
            await(i, YanosikPresence.State.UNKNOWN);
            permission(i, true);
            await(i, YanosikPresence.State.NO_WORK_SIGNAL);
            permission(i, false);
            await(i, YanosikPresence.State.UNKNOWN);
            permission(i, true);
            await(i, YanosikPresence.State.NO_WORK_SIGNAL);
        } finally { permission(i, previouslyGranted); }
    }
    private static void permission(Instrumentation i, boolean granted) throws Exception {
        String command = "cmd notification " + (granted ? "allow_listener " : "disallow_listener ")
                + "com.pbuchman.duduhome/com.pbuchman.duduhome.automation.YanosikPresence";
        try (var stream = new ParcelFileDescriptor.AutoCloseInputStream(i.getUiAutomation().executeShellCommand(command))) {
            stream.readAllBytes();
        }
    }
    private static void await(Instrumentation i, YanosikPresence.State expected) {
        long end = SystemClock.elapsedRealtime() + 5000;
        do {
            if (YanosikPresence.read(i.getTargetContext()) == expected) return;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < end);
        throw new AssertionError("Notification listener lifecycle: expected " + expected);
    }
}
