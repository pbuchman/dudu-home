package com.pbuchman.duduhome.automation;

import android.content.Context;
import android.app.ActivityOptions;
import android.content.Intent;
import android.util.Log;

/** Package verified on the physical DUDU7; resolve its launcher at runtime. */
public final class YanosikLauncher {
    public static final String PACKAGE = "pl.neptis.yanosik.mobi.android";
    public interface Launch {
        Intent resolve();
        void open(Intent intent);
    }
    private final JourneySession session;
    private final Launch launch;
    public YanosikLauncher(Context context) {
        this(new JourneySession(context), new Launch() {
            public Intent resolve() { return context.getPackageManager().getLaunchIntentForPackage(PACKAGE); }
            public void open(Intent intent) {
                // Request Android's behind-task launch; no delayed HOME press or UI automation.
                // Hardware acceptance must check the target's own subsequent activities too.
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_NEW_DOCUMENT),
                        ActivityOptions.makeTaskLaunchBehind().toBundle());
            }
        });
    }
    public YanosikLauncher(JourneySession session, Launch launch) { this.session = session; this.launch = launch; }
    public void attempt() {
        if (!session.reserve()) return;
        try {
            Intent intent = launch.resolve();
            if (intent == null) { Log.w("DuduHome", "YANOSIK unavailable; session consumed"); return; }
            launch.open(intent);
            Log.i("DuduHome", "YANOSIK launch requested"); // not proof BAL allowed it
        } catch (RuntimeException denied) { Log.w("DuduHome", "YANOSIK launch failed; no retry"); }
    }
}
