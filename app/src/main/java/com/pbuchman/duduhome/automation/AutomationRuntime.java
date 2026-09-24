package com.pbuchman.duduhome.automation;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.pbuchman.duduhome.config.PrivateImport;
import com.pbuchman.duduhome.diagnostics.Diagnostics;
import java.time.LocalDate;
import java.time.ZoneId;
import static com.pbuchman.duduhome.automation.DetectionProgress.*;
import static com.pbuchman.duduhome.automation.AutomationCoordinator.*;

/** Android adapters around the deterministic queue. No persisted commands and no replay. */
public final class AutomationRuntime {
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AutomationCoordinator queue = new AutomationCoordinator();
    private final YanosikLauncher yanosik;
    private SpotifyController spotify;
    private long spotifyAttempt;
    private long dispatchedHomeAttempt = -1;
    private boolean closed;
    private final Runnable drain = this::drain;
    private final Runnable listener = this::changed;
    private final Runnable configurationListener = () -> reset(Reason.CONFIGURATION);
    public AutomationRuntime(Context context) {
        this.context = context.getApplicationContext(); yanosik = new YanosikLauncher(this.context);
        HomeActions.setSchedulingListener(listener);
        HomeActions.setConfigurationListener(configurationListener);
    }
    public void home(HomeEvent event) {
        if (closed || (!HomeActions.callsGate(event) && event != HomeEvent.OUTBOUND_CHECKPOINT)) return;
        long id = ProgressBus.request(context, ProgressBus.kind(event));
        if (event == HomeEvent.OUTBOUND_CHECKPOINT && !DailyCleaning.reserve(context)) {
            ProgressBus.update(context, id, Phase.SKIPPED, Reason.DAILY_LIMIT_OR_STORAGE); return;
        }
        enqueue(id, HomeActions.callsGate(event) ? Type.GATE : Type.CLEANING, event);
    }
    public void motion() {
        if (closed || PrivateImport.pending(context)) return;
        JourneySession session = new JourneySession(context);
        if (session.reserve(JourneySession.Target.YANOSIK))
            enqueue(ProgressBus.request(context, Kind.YANOSIK), Type.YANOSIK, null);
        if (session.reserve(JourneySession.Target.SPOTIFY))
            enqueue(ProgressBus.request(context, Kind.SPOTIFY), Type.SPOTIFY, null);
    }
    private void enqueue(long id, Type type, HomeEvent event) {
        queue.enqueue(id, type, event, SystemClock.elapsedRealtime(), day());
        Diagnostics.record(context, "QUEUE_" + type + "_WAITING ID=" + id);
        ProgressBus.update(context, id, Phase.WAITING, Reason.PRIORITY);
        changed();
    }
    public void changed() {
        if (closed) return;
        handler.removeCallbacks(drain); handler.post(drain);
    }
    private boolean mediaReady() { return HomeActions.allowsMediaLaunch() && !queue.hasHomeWaiting(); }
    private void drain() {
        if (closed) return;
        if (PrivateImport.pending(context)) { reset(Reason.CONFIGURATION); return; }
        long now = SystemClock.elapsedRealtime();
        for (Job j : queue.expire(now, day())) {
            Diagnostics.record(context, "QUEUE_" + j.type() + "_EXPIRED ID=" + j.id());
            ProgressBus.update(context, j.id(), Phase.SKIPPED, Reason.EXPIRED);
        }
        if (spotify != null) spotify.priorityChanged();
        Job job = queue.next(now, HomeActions.allowsExternalLaunch(), spotify == null && mediaReady());
        if (job != null) {
            Diagnostics.record(context, "QUEUE_" + job.type() + "_STARTED ID=" + job.id());
            if (job.type() == Type.GATE || job.type() == Type.CLEANING) {
                dispatchedHomeAttempt = job.id();
                HomeActions.dispatchReserved(context, job.event(), job.id());
            } else if (job.type() == Type.YANOSIK) {
                ProgressBus.update(context, job.id(), Phase.STARTED, Reason.NONE);
                YanosikLauncher.Result result = yanosik.launchReserved();
                Diagnostics.record(context, "YANOSIK_" + (result == YanosikLauncher.Result.REQUESTED ? "LAUNCH_REQUESTED" : result) + " ID=" + job.id());
                if (result == YanosikLauncher.Result.REQUESTED) queue.yanosikLaunched(now);
                Reason reason = switch (result) {
                    case REQUESTED -> Reason.NONE;
                    case ALREADY_RUNNING -> Reason.TARGET_ALREADY_RUNNING;
                    case MISSING -> Reason.TARGET_UNAVAILABLE;
                    case FAILED -> Reason.REQUEST_FAILED;
                    case UNKNOWN -> Reason.TARGET_STATUS_UNKNOWN;
                };
                ProgressBus.update(context, job.id(), result == YanosikLauncher.Result.REQUESTED ? Phase.SUCCEEDED : Phase.SKIPPED, reason);
            } else startSpotify(job);
            changed();
        } else {
            long next = queue.nextWake(now);
            if (next != Long.MAX_VALUE) handler.postDelayed(drain, Math.max(1, next - now));
        }
    }
    private void startSpotify(Job job) {
        spotifyAttempt = job.id();
        ProgressBus.update(context, job.id(), Phase.STARTED, Reason.NONE);
        spotify = new SpotifyController(context, () -> !closed && queue.current(job) && mediaReady(),
                () -> ProgressBus.update(context, job.id(), Phase.STARTED, Reason.RESUMING), result -> {
                    if (!queue.current(job) || closed) return;
                    spotify = null; spotifyAttempt = 0;
                    Diagnostics.record(context, "SPOTIFY_" + result + " ID=" + job.id());
                    Reason reason = switch (result) {
                        case PLAYING -> Reason.NONE;
                        case NO_ACCESS -> Reason.MEDIA_NO_ACCESS;
                        case MISSING -> Reason.TARGET_UNAVAILABLE;
                        case REMOTE -> Reason.MEDIA_REMOTE;
                        case NO_SESSION -> Reason.MEDIA_NO_SESSION;
                        case TIMEOUT -> Reason.MEDIA_TIMEOUT;
                        default -> Reason.TARGET_STATUS_UNKNOWN;
                    };
                    ProgressBus.update(context, job.id(), result == SpotifyController.Result.PLAYING ? Phase.SUCCEEDED : Phase.SKIPPED, reason);
                    changed();
                });
        spotify.start();
    }
    public void reset(Reason reason) {
        handler.removeCallbacksAndMessages(null);
        for (Job j : queue.reset()) ProgressBus.update(context, j.id(), Phase.SKIPPED, reason);
        if (spotify != null) {
            ProgressBus.update(context, spotifyAttempt, Phase.SKIPPED, reason);
            spotify.cancel(); spotify = null; spotifyAttempt = 0;
        }
        HomeActions.cancelPending(context, reason, dispatchedHomeAttempt);
        dispatchedHomeAttempt = -1;
    }
    public void close() {
        reset(Reason.SERVICE_STOPPED); closed = true;
        HomeActions.clearSchedulingListener(listener);
        HomeActions.clearConfigurationListener(configurationListener);
    }
    private static long day() { return LocalDate.now(ZoneId.of("Europe/Warsaw")).toEpochDay(); }
}
