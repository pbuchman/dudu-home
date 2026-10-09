package com.pbuchman.duduhome.automation;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.pbuchman.duduhome.config.PrivateImport;
import com.pbuchman.duduhome.diagnostics.Diagnostics;
import com.pbuchman.duduhome.location.HomeDetector.GateArea;
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
    private final java.util.function.Function<HomeEvent, java.util.function.BooleanSupplier> reserveHome;
    private final java.util.Set<Long> mediaGroup = new java.util.LinkedHashSet<>();
    private boolean cancellingMedia;

    private long dispatchedHomeAttempt = -1;
    private volatile boolean closed;
    private final GatePrecondition gate = new GatePrecondition();
    private GateArea lastGateArea;
    private boolean homeConfigured = true;
    private long areaFreshUntil;
    private final java.util.function.Supplier<Runnable> gateSuccessListener = this::gateSuccessCallback;
    private Runnable gateSuccessCallback() {
        Runnable success = gate.successCallback();
        return () -> {
            if (closed) return;
            boolean before = gate.allowsMedia();
            success.run();
            if (!before && gate.allowsMedia()) Diagnostics.record(context, "MEDIA_GATE_CALL_COMPLETED");
            changed();
        };
    }
    private final Runnable drain = this::drain;
    private final Runnable listener = this::changed;
    private final Runnable configurationListener = () -> reset(Reason.CONFIGURATION);
    public AutomationRuntime(Context context) { this(context, event -> () -> true); }
    public AutomationRuntime(Context context, java.util.function.Function<HomeEvent, java.util.function.BooleanSupplier> reserveHome) {
        this.reserveHome = reserveHome;
        this.context = context.getApplicationContext(); yanosik = new YanosikLauncher(this.context);
        HomeActions.setSchedulingListener(listener);
        HomeActions.setConfigurationListener(configurationListener);
        HomeActions.setGateSuccessListener(gateSuccessListener);
    }
    public void gateArea(GateArea area, long validUntil) {
        if (closed) return;
        boolean before = gateAllowsMedia();
        homeConfigured = true;
        areaFreshUntil = validUntil;
        gate.observe(area);
        if (lastGateArea != area) {
            Diagnostics.record(context, "MEDIA_GATE_AREA_" + area);
            lastGateArea = area;
            changed();
        }
        if (before != gateAllowsMedia()) changed();
    }
    public void noHomeConfiguration() {
        gateArea(GateArea.NONE, 0);
        homeConfigured = false;
    }
    private boolean gateAllowsMedia() {
        return gate.allowsMedia() && (!homeConfigured || SystemClock.elapsedRealtime() < areaFreshUntil);
    }
    public boolean allowsMotionDetection() {
        return !closed && gateAllowsMedia() && HomeActions.allowsExternalLaunch();
    }
    public void home(HomeEvent event) {
        if (closed || (!HomeActions.callsGate(event) && event != HomeEvent.OUTBOUND_CHECKPOINT)) return;
        if (event == HomeEvent.OUTBOUND_CHECKPOINT && queue.contains(Type.CLEANING)) return;
        long id = ProgressBus.request(context, ProgressBus.kind(event));
        long day = day();
        java.util.function.BooleanSupplier homeReservation = reserveHome.apply(event);
        ProgressBus.reserveWith(id, () -> (event != HomeEvent.OUTBOUND_CHECKPOINT || DailyCleaning.reserve(context, day))
                && homeReservation.getAsBoolean());
        if (event == HomeEvent.OUTBOUND_CHECKPOINT && (!DailyCleaning.available(context, day) || queue.contains(Type.CLEANING))) {
            ProgressBus.update(context, id, Phase.SKIPPED, Reason.DAILY_LIMIT_OR_STORAGE); return;
        }
        enqueue(id, HomeActions.callsGate(event) ? Type.GATE : Type.CLEANING, event);
    }
    public void motion() {
        if (!allowsMotionDetection() || PrivateImport.pending(context)) return;
        JourneySession session = new JourneySession(context);
        if (!mediaGroup.isEmpty()) return;
        if (session.available(JourneySession.Target.YANOSIK)) addMedia(Type.YANOSIK, Kind.YANOSIK, JourneySession.Target.YANOSIK);
        if (session.available(JourneySession.Target.SPOTIFY)) addMedia(Type.SPOTIFY, Kind.SPOTIFY, JourneySession.Target.SPOTIFY);
    }
    private void addMedia(Type type, Kind kind, JourneySession.Target target) {
        long id = ProgressBus.request(context, kind);
        mediaGroup.add(id);
        ProgressBus.reserveWith(id, new JourneySession(context).reservation(target));
        enqueue(id, type, null);
    }
    private void cancelMedia() {
        if (cancellingMedia) return;
        cancellingMedia = true;
        boolean hadSent = mediaGroup.stream().anyMatch(ProgressBus::sent);
        for (long id : java.util.List.copyOf(mediaGroup)) {
            if (hadSent) ProgressBus.markGroupSent(id);
            ProgressBus.cancelAttempt(ProgressBus.token(id));
        }
        if (spotify != null) { SpotifyController active = spotify; spotify = null; spotifyAttempt = 0; active.cancel(); }
        mediaGroup.clear(); cancellingMedia = false;
    }
    private void enqueue(long id, Type type, HomeEvent event) {
        queue.enqueue(id, type, event, SystemClock.elapsedRealtime(), day());
        long generation = queue.generation();
        ProgressBus.allowSendWith(id, () -> !closed && queue.generation() == generation);
        ProgressBus.onCancel(id, () -> {
            queue.cancel(id);
            HomeActions.cancelPending(context, Reason.USER_CANCELLED, id);
            if (type == Type.YANOSIK || type == Type.SPOTIFY) cancelMedia();
            changed();
            return false;
        });
        Diagnostics.recordAttempt(context, id, "QUEUE_" + type + "_WAITING ID=" + id);
        ProgressBus.update(context, id, Phase.WAITING, Reason.PRIORITY);
        changed();
    }
    public void changed() {
        if (closed) return;
        handler.removeCallbacks(drain); handler.post(drain);
    }
    private boolean mediaReady() { return gateAllowsMedia() && !new JourneySession(context).manualNavigationChosen()
            && HomeActions.allowsMediaLaunch() && !queue.hasHomeWaiting(); }
    private void drain() {
        if (closed) return;
        if (PrivateImport.pending(context)) { reset(Reason.CONFIGURATION); return; }
        if (new JourneySession(context).manualNavigationChosen()) {
            for (Job j : queue.discardMedia()) ProgressBus.update(context, j.id(), Phase.SKIPPED, Reason.UI_BUSY);
            if (spotify != null) {
                spotify.cancel(); spotify = null;
                ProgressBus.update(context, spotifyAttempt, Phase.SKIPPED, Reason.UI_BUSY); spotifyAttempt = 0;
            }
        }
        long now = SystemClock.elapsedRealtime();
        for (Job j : queue.expire(now, day())) {
            Diagnostics.recordAttempt(context, j.id(), "QUEUE_" + j.type() + "_EXPIRED ID=" + j.id());
            ProgressBus.update(context, j.id(), Phase.SKIPPED, Reason.EXPIRED);
        }
        if (spotify != null) spotify.priorityChanged();
        Job job = queue.next(now, HomeActions.allowsExternalLaunch(), spotify == null && mediaReady());
        if (job != null) {
            Diagnostics.recordAttempt(context, job.id(), "QUEUE_" + job.type() + "_STARTED ID=" + job.id());
            if (job.type() == Type.GATE || job.type() == Type.CLEANING) {
                dispatchedHomeAttempt = job.id();
                HomeActions.dispatchReserved(context, job.event(), job.id());
            } else if (job.type() == Type.YANOSIK) {
                ProgressBus.update(context, job.id(), Phase.STARTED, Reason.NONE);
                YanosikLauncher.Result result = yanosik.launchReserved(() -> ProgressBus.claimSend(context, job.id()));
                Diagnostics.recordAttempt(context, job.id(), "YANOSIK_" + (result == YanosikLauncher.Result.REQUESTED ? "LAUNCH_REQUESTED" : result) + " ID=" + job.id());
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
            if (mediaGroup.stream().noneMatch(id -> queueContainsOrRunning(id))) mediaGroup.clear();
            changed();
        } else {
            long next = queue.nextWake(now);
            if (next != Long.MAX_VALUE) handler.postDelayed(drain, Math.max(1, next - now));
        }
    }
    private boolean queueContainsOrRunning(long id) {
        return (spotify != null && spotifyAttempt == id) || ProgressBus.active(id);
    }
    private void startSpotify(Job job) {
        spotifyAttempt = job.id();
        ProgressBus.update(context, job.id(), Phase.STARTED, Reason.NONE);
        spotify = new SpotifyController(context, () -> !closed && queue.current(job) && !ProgressBus.cancelled(job.id()) && mediaReady(),
                () -> ProgressBus.claimSend(context, job.id()),
                () -> ProgressBus.update(context, job.id(), Phase.STARTED, Reason.RESUMING), result -> {
                    if (!queue.current(job) || closed || ProgressBus.cancelled(job.id())) return;
                    spotify = null; spotifyAttempt = 0;
                    Diagnostics.recordAttempt(context, job.id(), "SPOTIFY_" + result + " ID=" + job.id());
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
                    mediaGroup.remove(job.id());
                    if (mediaGroup.stream().noneMatch(ProgressBus::active)) mediaGroup.clear();
                    changed();
                });
        spotify.start();
    }
    public void reset(Reason reason) {
        gate.reset(); lastGateArea = null; homeConfigured = true; areaFreshUntil = 0;
        handler.removeCallbacksAndMessages(null);
        for (Job j : queue.reset()) ProgressBus.update(context, j.id(), Phase.SKIPPED, reason);
        if (spotify != null) {
            ProgressBus.update(context, spotifyAttempt, Phase.SKIPPED, reason);
            spotify.cancel(); spotify = null; spotifyAttempt = 0;
        }
        HomeActions.cancelPending(context, reason, dispatchedHomeAttempt);
        dispatchedHomeAttempt = -1;
        mediaGroup.clear();
    }
    public void close() {
        reset(Reason.SERVICE_STOPPED); closed = true;
        HomeActions.clearSchedulingListener(listener);
        HomeActions.clearConfigurationListener(configurationListener);
        HomeActions.clearGateSuccessListener(gateSuccessListener);
    }
    private static long day() { return LocalDate.now(ZoneId.of("Europe/Warsaw")).toEpochDay(); }
}
