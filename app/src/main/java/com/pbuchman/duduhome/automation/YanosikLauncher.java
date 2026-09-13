package com.pbuchman.duduhome.automation;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** Package verified on the physical DUDU7; resolve its launcher at runtime. */
public final class YanosikLauncher {
    public static final String PACKAGE = "pl.neptis.yanosik.mobi.android";
    // Physical cold start opened its dashboard just after the previous 5 s callback.
    // One bounded grace period only; never repeatedly hide a later manual launch.
    public static final long HOME_DELAY_MS = 10000;
    public interface Launch {
        Intent resolve();
        void open(Intent intent);
        void home();
    }
    public interface Delay { void post(Runnable callback, long delayMs); }
    private final JourneySession session;
    private final Launch launch;
    private final Delay delay;
    private final java.util.function.Supplier<YanosikPresence.State> presence;
    private long generation;
    private boolean pendingHome;
    private Context diagnosticContext;
    public YanosikLauncher(Context context) {
        this(new JourneySession(context), new Launch() {
            public Intent resolve() { return context.getPackageManager().getLaunchIntentForPackage(PACKAGE); }
            public void open(Intent intent) {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            public void home() {
                // Owner-approved one-shot return to the desktop, not a simulated key press.
                context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        }, (callback, delayMs) -> new Handler(Looper.getMainLooper()).postDelayed(callback, delayMs),
                () -> YanosikPresence.read(context));
        diagnosticContext = context.getApplicationContext();
    }
    public YanosikLauncher(JourneySession session, Launch launch, Delay delay,
                           java.util.function.Supplier<YanosikPresence.State> presence) {
        this.session = session; this.launch = launch; this.delay = delay; this.presence = presence;
    }
    public void attempt() {
        if (!session.reserve()) return;
        YanosikPresence.State observed;
        try { observed = presence.get(); } catch (RuntimeException unavailable) { observed = YanosikPresence.State.UNKNOWN; }
        if (observed == YanosikPresence.State.WORK_DETECTED) {
            record("YANOSIK_ALREADY_RUNNING");
            return; // No STARTED banner, Activity or delayed HOME for an existing service.
        }
        long attempt = diagnosticContext == null ? 0 : ProgressBus.request(diagnosticContext, DetectionProgress.Kind.YANOSIK);
        if (observed != YanosikPresence.State.NO_WORK_SIGNAL) {
            record("YANOSIK_STATUS_UNKNOWN_NO_RETRY");
            outcome(attempt, DetectionProgress.Phase.SKIPPED, DetectionProgress.Reason.TARGET_STATUS_UNKNOWN);
            return;
        }
        if (diagnosticContext != null) ProgressBus.update(diagnosticContext, attempt, DetectionProgress.Phase.STARTED, DetectionProgress.Reason.NONE);
        try {
            Intent intent = launch.resolve();
            if (intent == null) {
                record("YANOSIK_UNAVAILABLE"); outcome(attempt, DetectionProgress.Phase.ERROR, DetectionProgress.Reason.TARGET_UNAVAILABLE); return;
            }
            launch.open(intent);
            record("YANOSIK_LAUNCH_REQUESTED"); // not proof BAL allowed it
            long requestGeneration = ++generation;
            pendingHome = true;
            delay.post(() -> {
                if (!pendingHome || generation != requestGeneration) return;
                pendingHome = false;
                try {
                    launch.home();
                    record("YANOSIK_HOME_REQUESTED"); // request only, not proof the target service runs
                    outcome(attempt, DetectionProgress.Phase.SUCCEEDED, DetectionProgress.Reason.NONE);
                } catch (RuntimeException denied) {
                    record("YANOSIK_HOME_FAILED_NO_RETRY");
                    outcome(attempt, DetectionProgress.Phase.ERROR, DetectionProgress.Reason.REQUEST_FAILED);
                }
            }, HOME_DELAY_MS);
        } catch (RuntimeException denied) {
            pendingHome = false;
            record("YANOSIK_LAUNCH_FAILED_NO_RETRY"); outcome(attempt, DetectionProgress.Phase.ERROR, DetectionProgress.Reason.REQUEST_FAILED);
        }
    }
    /** A stopped monitor/new ignition must not leave a delayed desktop navigation behind. */
    public void cancelPendingHome() {
        if (pendingHome) record("YANOSIK_HOME_CANCELLED");
        pendingHome = false;
        generation++;
    }
    private void outcome(long attempt, DetectionProgress.Phase phase, DetectionProgress.Reason reason) {
        if (diagnosticContext != null) ProgressBus.update(diagnosticContext, attempt, phase, reason);
    }
    private void record(String category) {
        if (diagnosticContext != null) com.pbuchman.duduhome.diagnostics.Diagnostics.record(diagnosticContext, category);
        else Log.i("DuduHome", category);
    }
}
