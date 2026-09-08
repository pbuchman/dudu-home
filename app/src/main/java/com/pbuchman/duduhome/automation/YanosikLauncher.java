package com.pbuchman.duduhome.automation;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Package observed in previous physical DUDU7 location-service captures; resolve launcher at runtime. */
public final class YanosikLauncher {
    public static final String PACKAGE = "pl.neptis.yanosik";
    public interface Launch {
        Intent resolve();
        void open(Intent intent);
    }
    private final JourneySession session;
    private final Launch launch;
    public YanosikLauncher(Context context) {
        this(new JourneySession(context), new Launch() {
            public Intent resolve() { return context.getPackageManager().getLaunchIntentForPackage(PACKAGE); }
            public void open(Intent intent) { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
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
