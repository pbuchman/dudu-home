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
    private Context diagnosticContext;
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
        diagnosticContext = context.getApplicationContext();
    }
    public YanosikLauncher(JourneySession session, Launch launch) { this.session = session; this.launch = launch; }
    public void attempt() {
        if (!session.reserve()) return;
        long attempt = diagnosticContext == null ? 0 : ProgressBus.request(diagnosticContext, DetectionProgress.Kind.YANOSIK);
        if (diagnosticContext != null) ProgressBus.update(diagnosticContext, attempt, DetectionProgress.Phase.STARTED, DetectionProgress.Reason.NONE);
        try {
            Intent intent = launch.resolve();
            if (intent == null) {
                record("YANOSIK_UNAVAILABLE"); outcome(attempt, DetectionProgress.Phase.ERROR, DetectionProgress.Reason.TARGET_UNAVAILABLE); return;
            }
            launch.open(intent);
            record("YANOSIK_LAUNCH_REQUESTED"); // not proof BAL allowed it
            outcome(attempt, DetectionProgress.Phase.SUCCEEDED, DetectionProgress.Reason.NONE);
        } catch (RuntimeException denied) {
            record("YANOSIK_LAUNCH_FAILED_NO_RETRY"); outcome(attempt, DetectionProgress.Phase.ERROR, DetectionProgress.Reason.REQUEST_FAILED);
        }
    }
    private void outcome(long attempt, DetectionProgress.Phase phase, DetectionProgress.Reason reason) {
        if (diagnosticContext != null) ProgressBus.update(diagnosticContext, attempt, phase, reason);
    }
    private void record(String category) {
        if (diagnosticContext != null) com.pbuchman.duduhome.diagnostics.Diagnostics.record(diagnosticContext, category);
        else Log.i("DuduHome", category);
    }
}
