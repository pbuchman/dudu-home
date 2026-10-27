package com.pbuchman.duduhome.automation;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.pbuchman.duduhome.config.PrivateImport;
import com.pbuchman.duduhome.diagnostics.Diagnostics;
import com.pbuchman.duduhome.location.HomeDetector.GateArea;
import static com.pbuchman.duduhome.automation.DetectionProgress.*;
import static com.pbuchman.duduhome.automation.AutomationCoordinator.*;

/** Android adapters around the deterministic queue. No persisted commands and no replay. */
public final class AutomationRuntime {
    private final Context context;
    private final java.util.function.LongSupplier dayClock = DailyCleaning::day;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AutomationCoordinator queue = new AutomationCoordinator();
    private final YanosikLauncher yanosik;
    private SpotifyController spotify;
    private long spotifyAttempt;
    private final java.util.function.Function<HomeEvent, java.util.function.BooleanSupplier> reserveHome;
    private final java.util.Set<Long> mediaGroup = new java.util.LinkedHashSet<>();
    private final DepartureContinuation<JourneySession.Cycle> departure = new DepartureContinuation<>();
    private boolean cancellingMedia;
    private final java.util.Set<Long> cleaningAttempts = new java.util.LinkedHashSet<>();

    private long dispatchedHomeAttempt = -1;
    private volatile boolean closed;
    private final GatePrecondition gate = new GatePrecondition();
    private GateArea lastGateArea;
    private boolean homeConfigured = true;
    private long areaFreshUntil;
    private final java.util.function.LongFunction<Runnable> gateSuccessListener = this::gateSuccessCallback;
    private Runnable gateSuccessCallback(long attempt) {
        Runnable success = gate.successCallback();
        long generation = queue.generation();
        JourneySession.Cycle cycle = new JourneySession(context).cycle();
        var binding = departure.capture(attempt);
        boolean[] completed = {false};
        return () -> {
            if (closed || completed[0] || queue.generation() != generation || cycle == null
                    || !cycle.equals(new JourneySession(context).cycle()) || !ProgressBus.acceptsGateSuccess(attempt)) {
                if (binding != null) Diagnostics.recordAttempt(context, attempt, "DEPARTURE_CONTINUATION_REJECTED");
                return;
            }
            completed[0] = true;
            boolean before = gate.allowsMedia();
            success.run();
            if (!before && gate.allowsMedia()) Diagnostics.record(context, "MEDIA_GATE_CALL_COMPLETED");
            if (departure.succeeded(binding, generation, cycle)) {
                enqueueCleaning();
                boolean queued = enqueueMedia();
                Diagnostics.recordAttempt(context, attempt, "DEPARTURE_CONTINUATION_" + (queued ? "CLEANING_AND_MEDIA_QUEUED" : "CLEANING_QUEUED_MEDIA_SUPPRESSED"));
            }
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
        if (closed || !HomeActions.callsGate(event)) return;
        long id = ProgressBus.request(context, ProgressBus.kind(event));
        departure.offered(id, event, queue.generation(), new JourneySession(context).cycle());
        ProgressBus.reserveWith(id, reserveHome.apply(event));
        enqueue(id, Type.GATE, event);
    }
    private void enqueueCleaning() {
        long id = ProgressBus.request(context, Kind.CLEANING);
        cleaningAttempts.add(id);
        long queuedDay = day();
        ProgressBus.reserveWith(id, () -> DailyCleaning.reserveAtSend(context, queuedDay, dayClock), ProgressBus.ReservationPolicy.SEND_ONLY);
        enqueue(id, Type.CLEANING, null, queuedDay);
    }
    public void motion() {
        if (!allowsMotionDetection() || PrivateImport.pending(context)) return;
        departure.motionConfirmed();
        enqueueMedia();
    }
    /** Both triggers use the same per-target durable reservations at actual sending. */
    private boolean enqueueMedia() {
        if (closed || departure.mediaCancelled() || PrivateImport.pending(context)) return false;
        JourneySession session = new JourneySession(context);
        if (session.manualNavigationChosen()) return false;
        if (!mediaGroup.isEmpty()) return false;
        if (session.available(JourneySession.Target.YANOSIK)) addMedia(Type.YANOSIK, Kind.YANOSIK, JourneySession.Target.YANOSIK);
        if (session.available(JourneySession.Target.SPOTIFY)) addMedia(Type.SPOTIFY, Kind.SPOTIFY, JourneySession.Target.SPOTIFY);
        return !mediaGroup.isEmpty();
    }
    private void addMedia(Type type, Kind kind, JourneySession.Target target) {
        long id = ProgressBus.request(context, kind);
        mediaGroup.add(id);
        ProgressBus.reserveWith(id, new JourneySession(context).reservation(target));
        enqueue(id, type, null);
    }
    private void cancelMedia() {
        departure.cancelMedia();
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
    /** Includes detector candidates, which do not yet have queue cancellation handlers. */
    public void cancelled(ProgressModel.State attempt) {
        if (closed) return;
        if (attempt.kind() == Kind.YANOSIK || attempt.kind() == Kind.SPOTIFY) {
            Diagnostics.recordAttempt(context, attempt.id(), "DEPARTURE_CONTINUATION_CANCELLED");
            cancelMedia();
        } else {
            if (departure.capture(attempt.id()) != null)
                Diagnostics.recordAttempt(context, attempt.id(), "DEPARTURE_CONTINUATION_CANCELLED");
            departure.cancelAttempt(attempt.id());
        }
    }
    private void enqueue(long id, Type type, HomeEvent event) { enqueue(id, type, event, day()); }
    private void enqueue(long id, Type type, HomeEvent event, long queuedDay) {
        queue.enqueue(id, type, event, SystemClock.elapsedRealtime(), queuedDay);
        long generation = queue.generation();
        JourneySession.Cycle cycle = new JourneySession(context).cycle();
        Job queued = queue.snapshot().stream().filter(j -> j.id() == id).findFirst().orElseThrow();
        ProgressBus.allowSendWith(id, () -> !closed && queue.generation() == generation
                && (type != Type.CLEANING || (SystemClock.elapsedRealtime() < queued.expiresAt() && day() == queued.day()
                    && cycle != null && cycle.equals(new JourneySession(context).cycle()))));
        ProgressBus.onCancel(id, () -> {
            departure.cancelAttempt(id);
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
            && HomeActions.allowsMediaLaunch() && !queue.hasHomeWaiting() && !cleaningPendingOrFeedback(); }
    private boolean cleaningPendingOrFeedback() {
        long now = SystemClock.elapsedRealtime();
        var states = ProgressBus.snapshot();
        cleaningAttempts.removeIf(id -> states.stream().noneMatch(s -> s.id() == id));
        return states.stream().anyMatch(s -> cleaningAttempts.contains(s.id())
                && (!ProgressModel.terminal(s.phase()) || s.expiresAt() > now));
    }
    private long cleaningFeedbackWake() {
        return ProgressBus.snapshot().stream().filter(s -> cleaningAttempts.contains(s.id())
                && ProgressModel.terminal(s.phase()) && s.expiresAt() > SystemClock.elapsedRealtime())
                .mapToLong(ProgressModel.State::expiresAt).min().orElse(Long.MAX_VALUE);
    }
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
            Diagnostics.queueState(context,j.id(),j.type().name(),"EXPIRED","EXPIRED",0,"NONE");
            Diagnostics.recordAttempt(context, j.id(), "QUEUE_" + j.type() + "_EXPIRED ID=" + j.id());
            ProgressBus.update(context, j.id(), Phase.SKIPPED, Reason.EXPIRED);
        }
        observeQueue(now);
        if (spotify != null) spotify.priorityChanged();
        boolean homeReady=HomeActions.allowsExternalLaunch()
                && (queue.head() == null || queue.head().type() != Type.CLEANING || (HomeActions.allowsMediaLaunch() && !new JourneySession(context).manualNavigationChosen())),
                mediaReady=spotify == null && mediaReady();
        // Observation fsync may take time: an earlier clock sample must never extend a queued deadline.
        now=SystemClock.elapsedRealtime();
        Job job = queue.next(now, homeReady, mediaReady);
        if (job != null) {
            Diagnostics.queueState(context,job.id(),job.type().name(),"STARTED","NONE",0,"NONE");
            Diagnostics.recordAttempt(context, job.id(), "QUEUE_" + job.type() + "_STARTED ID=" + job.id());
            if (job.type() == Type.GATE || job.type() == Type.CLEANING) {
                dispatchedHomeAttempt = job.id();
                if (job.type() == Type.CLEANING) HomeActions.dispatchReserved(context, HomeAction.CLEANING, job.id());
                else HomeActions.dispatchReserved(context, job.event(), job.id());
            } else if (job.type() == Type.YANOSIK) {
                ProgressBus.update(context, job.id(), Phase.STARTED, Reason.NONE);
                YanosikLauncher.Result result = yanosik.launchReserved(() -> ProgressBus.claimSend(context, job.id()));
                Diagnostics.recordAttempt(context, job.id(), "YANOSIK_" + (result == YanosikLauncher.Result.REQUESTED ? "LAUNCH_REQUESTED" : result) + " ID=" + job.id());
                if (result == YanosikLauncher.Result.FAILED)
                    Diagnostics.queueState(context,job.id(),"YANOSIK","FAILED","FAILURE",0,"NONE");
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
            long next = Math.min(queue.nextWake(now), cleaningFeedbackWake());
            if (next != Long.MAX_VALUE) handler.postDelayed(drain, Math.max(1, next - now));
        }
    }
    private void observeQueue(long now) {
        Job head=queue.head();
        for (Job job:queue.snapshot()) {
            boolean media=job.type()==Type.YANOSIK || job.type()==Type.SPOTIFY;
            var causes=new com.pbuchman.duduhome.diagnostics.QueueBlockers();
            causes.add(HomeActions.launchBlocker(media || job.type()==Type.CLEANING));
            if (job.type()==Type.CLEANING && new JourneySession(context).manualNavigationChosen()) causes.add("PRESENTATION_BUSY");
            if (head!=null && head.id()!=job.id()) causes.add("PRIORITY");
            if (media && !gateAllowsMedia()) causes.add("GATE_PRECONDITION");
            if (media && spotify!=null) causes.add("ACTIVE_OPERATION");
            if (media && queue.hasHomeWaiting()) causes.add("PRIORITY");
            if (media && now<queue.mediaNotBefore()) causes.add("YANOSIK_GRACE");
            String reason=causes.reason(),blocker="NONE";
            long blockerId=0;
            if (reason.equals("RESOURCE_CLEANUP") || reason.equals("ACTIVE_OPERATION")) {
                blockerId=HomeActions.busyAttempt();blocker=HomeActions.busyAction();
                if (blockerId==0 && spotify!=null) { blockerId=spotifyAttempt;blocker="SPOTIFY"; }
            } else if (reason.equals("PRIORITY") && head!=null) { blockerId=head.id();blocker=head.type().name(); }
            else if (reason.equals("GATE_PRECONDITION")) blocker="GATE";
            else if (reason.equals("YANOSIK_GRACE")) blocker="YANOSIK";
            Diagnostics.queueState(context,job.id(),job.type().name(),causes.state(),reason,blockerId,blocker,causes.mask());
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
                    if (result != SpotifyController.Result.PLAYING)
                        Diagnostics.queueState(context,job.id(),"SPOTIFY","FAILED","FAILURE",0,"NONE");
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
        departure.invalidate();
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
        cleaningAttempts.clear();
    }
    public String diagnostic() {
        return "queue_home_waiting=" + queue.hasHomeWaiting() + " queue_generation=" + queue.generation()
                + " gate_media_allowed=" + gateAllowsMedia() + " external_launch_allowed=" + HomeActions.allowsExternalLaunch();
    }
    public void close() {
        reset(Reason.SERVICE_STOPPED); closed = true;
        HomeActions.clearSchedulingListener(listener);
        HomeActions.clearConfigurationListener(configurationListener);
        HomeActions.clearGateSuccessListener(gateSuccessListener);
    }
    private long day() { return dayClock.getAsLong(); }
}
