package com.pbuchman.duduhome.automation;

import android.content.Context;
import android.content.Intent;
import java.util.function.Supplier;

/** One request only. Reservation, delay and screen ownership belong to the coordinator. */
public final class YanosikLauncher {
    public static final String PACKAGE = "pl.neptis.yanosik.mobi.android";
    public enum Result { REQUESTED, ALREADY_RUNNING, UNKNOWN, MISSING, FAILED }
    public interface Launch { Intent resolve(); void open(Intent intent); }
    private final Launch launch;
    private final Supplier<YanosikPresence.State> presence;
    public YanosikLauncher(Context context) {
        this(new Launch() {
            public Intent resolve() { return context.getPackageManager().getLaunchIntentForPackage(PACKAGE); }
            public void open(Intent intent) { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        }, () -> YanosikPresence.read(context));
    }
    public YanosikLauncher(Launch launch, Supplier<YanosikPresence.State> presence) {
        this.launch = launch; this.presence = presence;
    }
    public Result launchReserved() {
        try {
            YanosikPresence.State state = presence.get();
            if (state == YanosikPresence.State.WORK_DETECTED) return Result.ALREADY_RUNNING;
            if (state != YanosikPresence.State.NO_WORK_SIGNAL) return Result.UNKNOWN;
            Intent intent = launch.resolve();
            if (intent == null) return Result.MISSING;
            launch.open(intent);
            return Result.REQUESTED;
        } catch (RuntimeException denied) { return Result.FAILED; }
    }
}
