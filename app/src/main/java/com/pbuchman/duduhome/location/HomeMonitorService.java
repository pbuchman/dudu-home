package com.pbuchman.duduhome.location;

import com.pbuchman.duduhome.R;
import com.pbuchman.duduhome.automation.HomeActions;
import com.pbuchman.duduhome.automation.HomeEvent;
import com.pbuchman.duduhome.config.PrivateImport;
import com.pbuchman.duduhome.ui.MainActivity;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import com.pbuchman.duduhome.automation.JourneySession;
import com.pbuchman.duduhome.automation.AutomationRuntime;
import com.pbuchman.duduhome.automation.MotionHook;
import com.pbuchman.duduhome.diagnostics.Diagnostics;
import com.pbuchman.duduhome.startup.DuduCycle;
import com.pbuchman.duduhome.automation.ProgressBus;
import com.pbuchman.duduhome.automation.DetectionProgress.Reason;
import com.pbuchman.duduhome.ui.ProgressOverlay;

public final class HomeMonitorService extends Service implements LocationListener {
    public static final String CANCEL_ACTION = "com.pbuchman.duduhome.CANCEL_ATTEMPT";
    public static final String CANCEL_SESSION_EXTRA = "cancel_session";
    public static final String CANCEL_ID_EXTRA = "cancel_id";
    private final java.util.function.Consumer<com.pbuchman.duduhome.automation.ProgressModel.State> cancellation = observed -> {
        if (this.automation != null) this.automation.cancelled(observed);
        if (observed.evidenceId() < 0 && observed.kind() != com.pbuchman.duduhome.automation.DetectionProgress.Kind.YANOSIK
                && observed.kind() != com.pbuchman.duduhome.automation.DetectionProgress.Kind.SPOTIFY) return;
        switch (observed.kind()) {
            case YANOSIK, SPOTIFY -> {
                this.motion.cancel();
                this.motionOrigin = null;
                recordMotion("USER_CANCELLED", true);
            }
            case DEPARTURE, RETURN -> { if (this.detector != null) this.detector.cancel(observed.kind()); }
            default -> { }
        }
    };
    private static final String CHANNEL = "home_monitor";
    private com.pbuchman.duduhome.trip.TripController trip;
    private com.pbuchman.duduhome.ui.TripPresentation tripPresentation;
    private com.pbuchman.duduhome.routebook.Routebook routebook;
    private com.pbuchman.duduhome.startup.RadioPowerObserver radioPower;
    private LocationManager locations;
    private HomeConfiguration config;
    private HomeDetector detector;
    private SharedPreferences state;
    private DepartureCycle departureCycle;
    private long startupId;
    private GpsStartupTrace gpsStartup;
    private Boolean initialParking;
    private String lastDepartureDiagnostic;
    private String accuracyBucket = "NO_FIX", ageBucket = "NO_FIX", speedBucket = "NO_FIX";
    private DepartureCycle createDepartureCycle() {
        SharedPreferences saved = getSharedPreferences("departure_cycle", 0);
        return new DepartureCycle(new DepartureCycle.Store() {
            public DepartureCycle.State read() {
                DepartureCycle.Baseline baseline;
                try { baseline = DepartureCycle.Baseline.valueOf(saved.getString("baseline", "UNKNOWN")); }
                catch (IllegalArgumentException invalid) { baseline = DepartureCycle.Baseline.OUTSIDE; }
                return new DepartureCycle.State(saved.getInt("boot", -1), saved.getLong("counter", -1),
                        saved.getLong("generation", 0), baseline, saved.getBoolean("consumed", false), saved.getBoolean("away", false));
            }
            public Object identity() { return saved; }
            public boolean write(DepartureCycle.State next) {
                return saved.edit().putInt("boot", next.boot()).putLong("counter", next.counter())
                        .putLong("generation", next.generation()).putString("baseline", next.baseline().name())
                        .putBoolean("consumed", next.consumed()).putBoolean("away", next.away()).commit();
            }
        });
    }
    private boolean registered;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastFix;
    private long lastSummary;
    private int fixes, poorFixes;
    private boolean destroyed, cycleReady, cyclePending, monitoringStarted;
    private String lastCycleDiagnostic;
    private final java.util.concurrent.ExecutorService cycleReader = java.util.concurrent.Executors.newSingleThreadExecutor();
    private AutomationRuntime automation;
    private ProgressOverlay progressOverlay;
    private long progressEpoch;
    private final MotionHook motion = new MotionHook(() -> automation.motion());
    private Location motionOrigin;
    private JourneySession journey;
    private boolean movingBurst, motionConfirmed;
    private long lastMotionLog = -1;
    private MotionPhase lastMotionPhase;
    private MotionReading motionReading = MotionReading.NO_FIX;
    private volatile MotionTrace motionTrace;
    private record MotionPhase(MotionDetector.State state, MotionDetector.Reason reason,
                               MotionDetector.Source source, boolean needsStop, String blocker) { }
    private record MotionReading(String accuracy, String age, String speed) {
        static final MotionReading NO_FIX = new MotionReading("NO_FIX", "NO_FIX", "NO_FIX");
        static MotionReading of(MotionDetector.Fix fix, String accuracy, String age) {
            String speed = !fix.hasSpeed() ? "MISSING"
                    : !Double.isFinite(fix.speed()) || fix.speed() < 0 ? "INVALID"
                    : fix.speed() < .5 ? "LT_0_5_MPS" : fix.speed() < 1 ? "LT_1_MPS" : "GE_1_MPS";
            return new MotionReading(accuracy, age, speed);
        }
    }
    private record MotionTrace(MotionPhase phase, MotionReading reading, long elapsed, String gap,
                               long cycle, long generation, boolean yanosik, boolean spotify) {
        String baseline() { return phase.needsStop ? "REQUIRED" : "READY"; }
        String dump() {
            return "motion_state=" + phase.state + " motion_reason=" + phase.reason + " motion_blocker=" + phase.blocker
                    + " motion_source=" + phase.source + " motion_baseline=" + baseline()
                    + " motion_gap=" + gap + " motion_elapsed_ms=" + elapsed + " motion_cycle=" + cycle
                    + " motion_generation=" + generation;
        }
    }
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!ready(HomeMonitorService.this) || PrivateImport.pending(HomeMonitorService.this)) {
                Diagnostics.record(HomeMonitorService.this, "MONITOR_STOP_NOT_READY");
                stopSelf();
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (now - lastFix > 15000) {
                if (gpsStartup != null) gpsStartup.current().watchdog(now);
                Diagnostics.record(HomeMonitorService.this, "GPS_GAP_RESUBSCRIBE");
                progressEpoch = ProgressBus.reset(HomeMonitorService.this, Reason.GPS_UNRELIABLE);
                if (detector != null) detector.clearEvidence();
                updateGateArea(0);
                clearMotion("GPS_GAP");
                if (registered) locations.removeUpdates(HomeMonitorService.this);
                registered = false;
                subscribe();
            }
            checkCycle();
            handler.postDelayed(this, 15000);
        }
    };

    public static boolean ready(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                && (Build.VERSION.SDK_INT < 29 || context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED)
                && (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED);
    }

    public static void ensureStarted(Context context) {
        if (!ready(context) || PrivateImport.pending(context)) {
            Diagnostics.record(context, PrivateImport.pending(context) ? "MONITOR_START_MAINTENANCE" : "MONITOR_START_PERMISSIONS");
            return;
        }
        try { context.startForegroundService(new Intent(context, HomeMonitorService.class)); }
        catch (RuntimeException ignored) { Diagnostics.record(context, "MONITOR_START_SYSTEM_BLOCKED"); }
    }

    @Override public void onCreate() {
        super.onCreate();
        startupId = Diagnostics.startup();
        Context application = getApplicationContext();
        gpsStartup = new GpsStartupTrace(startupId, category -> Diagnostics.record(application, category));
        Diagnostics.record(this, "MONITOR_CREATE STARTUP=" + startupId);
        progressEpoch = ProgressBus.reset(this, Reason.RESET);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Automatyzacja domu", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Dudu Home").setContentText("Automatyzacja aktywna")
                .setContentIntent(open).setOngoing(true).build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(17, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(17, notification);
        } catch (RuntimeException denied) { Diagnostics.record(this, "MONITOR_FOREGROUND_DENIED"); stopSelf(); return; }
        trip = com.pbuchman.duduhome.trip.TripController.get(this);
        tripPresentation = new com.pbuchman.duduhome.ui.TripPresentation(this);
        state = getSharedPreferences("home_detector", 0);
        departureCycle = createDepartureCycle();
        if (!departureCycle.ensureBoot(Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1),
                state.getInt("flags", 0))) Diagnostics.record(this, "DEPARTURE_STATE_BLOCKED STARTUP=" + startupId);
        locations = getSystemService(LocationManager.class);
        automation = new AutomationRuntime(this, this::homeReservation, startupId, journey()::wakeCounter,
                () -> gpsStartup == null ? -1 : gpsStartup.current().id());
        motion.requireStationaryBaseline();
        ProgressBus.setCancellationListener(cancellation);
        JourneySession.BootResult boot = journey().observeBoot();
        if (boot == JourneySession.BootResult.NEW_BOOT) motion.verifiedCycle(MotionDetector.Source.VERIFIED_BOOT);
        Diagnostics.record(this, "MOTION_BOOT STARTUP=" + startupId + " STATE=" + boot);
        recordMotion("CYCLE_PENDING", true);
        try { progressOverlay = new ProgressOverlay(this); }
        catch (RuntimeException unavailable) { Diagnostics.record(this, "PROGRESS_OVERLAY_UNAVAILABLE"); }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && CANCEL_ACTION.equals(intent.getAction())) {
            ProgressBus.cancelAttempt(new com.pbuchman.duduhome.automation.AttemptToken(
                    intent.getStringExtra(CANCEL_SESSION_EXTRA), intent.getLongExtra(CANCEL_ID_EXTRA, -1)));
            if (!monitoringStarted) stopSelf(startId);
            return monitoringStarted ? START_STICKY : START_NOT_STICKY;
        }
        monitoringStarted = true;
        HomeConfiguration next = HomeConfiguration.load(this);
        if (state == null || !ready(this) || PrivateImport.pending(this)) {
            Diagnostics.record(this, "MONITOR_STOP_NOT_READY");
            stopSelf(); return START_NOT_STICKY;
        }
        if (routebook == null) routebook = new com.pbuchman.duduhome.routebook.Routebook(this);
        if (radioPower == null) radioPower = new com.pbuchman.duduhome.startup.RadioPowerObserver(this,(event,powerState)->{
            if(routebook!=null)routebook.offerPower(event);
            if(trip!=null)trip.observePower(event,powerState);
        });
        if (next == null || !next.enabled) {
            if (detector != null) {
                automation.reset(Reason.CONFIGURATION);
                progressEpoch = ProgressBus.reset(this, Reason.CONFIGURATION);
            }
            config = next; detector = null;
        }
        else if (config == null || detector == null || !next.revision.equals(config.revision)) {
            automation.reset(Reason.CONFIGURATION);
            progressEpoch = ProgressBus.reset(this, Reason.CONFIGURATION);
            config = next;
            int saved = config.revision.equals(state.getString("revision", "")) ? state.getInt("flags", 0) : 0;
            detector = new HomeDetector(config.geometry, saved, departureCycle);
            detector.requireFreshBaseline();
            if (!state.edit().putString("revision", config.revision).putInt("flags", saved).commit()) {
                stopSelf(); return START_NOT_STICKY;
            }
        }
        Diagnostics.record(this, "MONITOR_START STARTUP=" + startupId + " HOME=" + (detector == null ? 0 : 1)
                + " FLAGS=" + (detector == null ? 0 : detector.committedFlags()));
        if (detector == null) automation.noHomeConfiguration();
        checkCycle();
        subscribe();
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, 15000);
        return START_STICKY;
    }

    private void checkCycle() {
        if (destroyed || cyclePending) return;
        cyclePending = true;
        cycleReader.execute(() -> {
            DuduCycle.Observation cycle = DuduCycle.readObservation();
            handler.post(() -> onCycleRead(cycle));
        });
    }

    private void onCycleRead(DuduCycle.Observation cycle) {
        if (destroyed) return;
        long observedAt = SystemClock.elapsedRealtime();
        cyclePending = false;
        JourneySession session = journey();
        long previousCounter = Math.max(session.wakeCounter(), departureWakeCounter());
        DepartureCycle.Result departureResult = departureCycle == null ? DepartureCycle.Result.UNAVAILABLE
                : departureCycle.observe(cycle);
        if (departureResult == DepartureCycle.Result.REARMED && detector != null) {
            // Independent of JourneySession's manual-Maps rebaseline.
            if (automation != null) automation.reset(Reason.WAKE);
            progressEpoch = ProgressBus.reset(this, Reason.WAKE);
            detector.clearEvidence(); detector.departureCycleChanged();
        }
        if (!cycleReady && initialParking != null && departureCycle != null) departureCycle.firstFix(initialParking);
        String departureCategory = "DEPARTURE_CYCLE_" + departureResult + " STARTUP=" + startupId
                + " GENERATION=" + (departureCycle == null ? 0 : departureCycle.state().generation());
        if (!departureCategory.equals(lastDepartureDiagnostic)) Diagnostics.record(this, departureCategory);
        lastDepartureDiagnostic = departureCategory;
        JourneySession.ObservationResult result = session.observeAwakeCycle(cycle);
        if (gpsStartup != null) {
            long observedCounter = cycle == null ? -1 : cycle.counter();
            boolean verified = observedCounter > previousCounter && (departureResult == DepartureCycle.Result.REARMED
                    || result == JourneySession.ObservationResult.REARMED);
            if (verified) gpsStartup.verifiedWake(observedAt, observedCounter, registered);
            long currentCounter = Math.max(session.wakeCounter(), departureWakeCounter());
            gpsStartup.current().cycleRead(observedAt, verified ? "REARMED" : result.name(), observedCounter, currentCounter);
        }
        if (result == JourneySession.ObservationResult.REARMED) {
            if (automation != null) automation.reset(Reason.WAKE);
            progressEpoch = ProgressBus.reset(this, Reason.WAKE);
            if (trip != null) trip.breakSegment();
            if (routebook != null) routebook.breakSegment();
            // Never carry pre-sleep movement evidence into a new cycle.
            motion.verifiedCycle(MotionDetector.Source.VERIFIED_WAKE);
            motionOrigin = null;
            movingBurst = motionConfirmed = false;
            motionReading = MotionReading.NO_FIX;
            if (detector != null) detector.clearEvidence();
        }
        String category = switch (result) {
            case BASELINE_ZERO -> "VENDOR_BASELINE_ZERO";
            case BASELINE_COUNTER -> "VENDOR_BASELINE_COUNTER CYCLE=" + cycle.counter();
            case REARMED -> "VENDOR_WAKE_REARM CYCLE=" + cycle.counter();
            case UNAVAILABLE -> "VENDOR_CYCLE_UNAVAILABLE";
            case INVALID_BOOT -> "VENDOR_CYCLE_INVALID_BOOT";
            case STATE_WRITE_FAILED -> null;
            case UNCHANGED -> "VENDOR_CYCLE=" + cycle.counter();
        };
        if (category != null) category += " ELIGIBLE=" + (session.available() ? 1 : 0);
        if (session.takeStorageFailureReport()) Diagnostics.record(this, "YANOSIK_SESSION_STATE_WRITE_FAILED");
        if (category != null && !category.equals(lastCycleDiagnostic)) Diagnostics.record(this, category);
        lastCycleDiagnostic = category;
        boolean firstRead = !cycleReady;
        cycleReady = true;
        if (firstRead || result == JourneySession.ObservationResult.REARMED) recordMotion(motionBlocker(), true);
    }

    private long departureWakeCounter() {
        return departureCycle == null || departureCycle.blocked() ? -1 : departureCycle.state().counter();
    }

    @SuppressWarnings("MissingPermission")
    private void subscribe() {
        if (registered || !ready(this)) return;
        try {
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this, Looper.getMainLooper());
            registered = true;
            if (gpsStartup != null) gpsStartup.current().subscription(SystemClock.elapsedRealtime(), true);
            Diagnostics.record(this, "GPS_SUBSCRIBED");
        } catch (RuntimeException denied) {
            if (gpsStartup != null) gpsStartup.current().subscription(SystemClock.elapsedRealtime(), false);
            Diagnostics.record(this, "GPS_SUBSCRIPTION_DENIED");
        }
    }

    @Override public void onLocationChanged(Location location) {
        long callbackMono = SystemClock.elapsedRealtime();
        GpsStartupTrace.Observation observation = destroyed || gpsStartup == null ? null : gpsStartup.current();
        if (observation != null) observation.callback(callbackMono, location != null);
        if (!ready(this) || PrivateImport.pending(this)) { stopSelf(); return; }
        if (location == null) {
            if (observation != null) observation.motionRejected(callbackMono, "EMPTY_CALLBACK");
            return;
        }
        long callbackWall = System.currentTimeMillis();
        lastFix = callbackMono;
        long fixTime = location.getElapsedRealtimeNanos() / 1000000;
        fixes++;
        double accuracy = location.hasAccuracy() ? location.getAccuracy() : Double.NaN;
        long age = lastFix - fixTime;
        accuracyBucket = !location.hasAccuracy() ? "MISSING" : !Double.isFinite(accuracy) || accuracy < 0 ? "INVALID"
                : accuracy <= 5 ? "LE_5M" : accuracy <= 15 ? "LE_15M" : accuracy <= 25 ? "LE_25M" : "OVER_25M";
        ageBucket = age < 0 ? "FUTURE" : age <= 3000 ? "LE_3S" : age <= 15000 ? "LE_15S" : "OVER_15S";
        speedBucket = !location.hasSpeed() ? "MISSING" : !Float.isFinite(location.getSpeed()) || location.getSpeed() < 0 ? "INVALID"
                : location.getSpeed() < .5 ? "STOPPED" : location.getSpeed() < .7 ? "BELOW_MOVEMENT" : "MOVING";
        if (!location.hasAccuracy() || location.getAccuracy() > 15 || lastFix - fixTime > 3000
                || location.isFromMockProvider()) poorFixes++;
        if (lastFix - lastSummary >= 30000) {
            Diagnostics.record(this, "GPS_SUMMARY FIXES=" + fixes + " POOR=" + poorFixes
                    + " HOME=" + (detector == null ? 0 : 1)
                    + " FLAGS=" + (detector == null ? 0 : detector.committedFlags()));
            fixes = poorFixes = 0; lastSummary = lastFix;
        }
        try {
            // Home actions get first opportunity on this fix.
            if (detector != null && !acceptHome(location, fixTime)) return;
            updateGateArea(fixTime + 3000);
            acceptMotion(location, fixTime, accuracy, age, observation);
        } finally {
            try { if (trip != null) trip.offer(location); }
            finally {
                if (routebook != null) routebook.offer(location, callbackMono, callbackWall,
                        observation == null ? null : observation.archiveObserver());
            }
        }
    }

    private void acceptMotion(Location location, long fixTime, double accuracy, long age,
                              GpsStartupTrace.Observation observation) {
        if (motionOrigin == null) motionOrigin = new Location(location);
        float[] distance = new float[2];
        Location.distanceBetween(motionOrigin.getLatitude(), motionOrigin.getLongitude(),
                location.getLatitude(), location.getLongitude(), distance);
        double bearing = Math.toRadians(distance[1]);
        MotionDetector.Fix fix = new MotionDetector.Fix(fixTime, distance[0] * Math.sin(bearing),
                distance[0] * Math.cos(bearing), accuracy, location.getSpeed(),
                location.hasSpeed(), age, location.isFromMockProvider());
        motionReading = MotionReading.of(fix, accuracyBucket, ageBucket);
        String blocker = motionBlocker();
        if (!automation.allowsMotionDetection()) {
            motion.observeBaseline(fix);
            recordMotion(blocker, false, observation);
            ProgressBus.offer(this, progressEpoch, ProgressBus.MOTION, java.util.List.of());
        } else {
            motion.accept(fix, cycleReady && !PrivateImport.pending(this), observed -> {
                recordMotion(blocker, false, observation);
                ProgressBus.offer(this, progressEpoch, ProgressBus.MOTION,
                        (journey().available(JourneySession.Target.SPOTIFY)
                         || (journey().available() && com.pbuchman.duduhome.automation.YanosikPresence.read(this)
                                != com.pbuchman.duduhome.automation.YanosikPresence.State.WORK_DETECTED))
                                ? observed : java.util.List.of());
            });
        }
        // Both the stationary and moving continuity checks need one coordinate origin.
        if (!motion.hasSpatialEvidence()) motionOrigin = null;
    }

    private JourneySession journey() {
        if (journey == null) journey = new JourneySession(this);
        return journey;
    }

    private String motionBlocker() {
        if (!cycleReady) return "CYCLE_PENDING";
        if (automation == null) return "MONITOR_STARTING";
        String block = automation.motionBlocker();
        if (!"NONE".equals(block)) return block;
        if (journey().cycle() == null) return "SESSION_UNAVAILABLE";
        if (journey().manualNavigationChosen()) return "MANUAL_NAVIGATION";
        return journey().available() || journey().available(JourneySession.Target.SPOTIFY) ? "NONE" : "CYCLE_CONSUMED";
    }

    private void clearMotion(String blocker) {
        motion.clear();
        motionOrigin = null;
        movingBurst = motionConfirmed = false;
        motionReading = MotionReading.NO_FIX;
        recordMotion(blocker, true);
    }

    private void recordMotion(String blocker, boolean force) {
        recordMotion(blocker, force, null);
    }

    private void recordMotion(String blocker, boolean force, GpsStartupTrace.Observation observation) {
        MotionDetector.Evidence evidence = motion.evidence();
        if (observation != null) observation.motionFix(SystemClock.elapsedRealtime(), evidence);
        MotionPhase phase = new MotionPhase(evidence.state(), evidence.reason(), evidence.source(),
                evidence.needsStop(), blocker);
        JourneySession.Cycle cycle = journey().cycle();
        long counter = journey().wakeCounter();
        String gap = evidence.reason() == MotionDetector.Reason.NONMONOTONIC ? "NONMONOTONIC"
                : evidence.gap() < 0 ? "NO_PREVIOUS" : evidence.gap() <= 1000 ? "LE_1S"
                : evidence.gap() <= 3000 ? "LE_3S" : "OVER_3S";
        MotionTrace trace = new MotionTrace(phase, motionReading, evidence.elapsed(), gap, counter,
                cycle == null ? -1 : cycle.generation(), journey().available(), journey().available(JourneySession.Target.SPOTIFY));
        motionTrace = trace;
        boolean firstMoving = evidence.moving() && !movingBurst;
        boolean confirmed = evidence.state() == MotionDetector.State.CONFIRMED && "NONE".equals(blocker);
        boolean firstConfirmation = confirmed && !motionConfirmed;
        movingBurst = evidence.moving();
        motionConfirmed = confirmed;
        if (automation != null) {
            if (firstMoving) automation.observeMotionTransition("FIRST_MOVING");
            if (firstConfirmation) automation.observeMotionTransition("MOTION_CONFIRMED");
        }
        long now = SystemClock.elapsedRealtime();
        long sinceLog = lastMotionLog < 0 ? Long.MAX_VALUE : now - lastMotionLog;
        boolean keyTransition = firstConfirmation || (evidence.state() == MotionDetector.State.BASELINE_READY
                && (lastMotionPhase == null || lastMotionPhase.state != phase.state));
        boolean changed = !phase.equals(lastMotionPhase);
        if (!force && !keyTransition && sinceLog < 30000 && (!changed || sinceLog < 5000)) return;
        lastMotionLog = now;
        lastMotionPhase = phase;
        String correlation = " STARTUP=" + startupId + " CYCLE=" + trace.cycle
                + (gpsStartup == null ? "" : " OBSERVATION=" + gpsStartup.current().id());
        Diagnostics.record(this, "MOTION_STATE" + correlation + " GENERATION=" + trace.generation
                + " STATE=" + phase.state + " REASON=" + phase.reason + " SOURCE=" + phase.source
                + " BASELINE=" + trace.baseline() + " BLOCKER=" + blocker + " ELAPSED_MS=" + trace.elapsed);
        Diagnostics.record(this, "MOTION_FIX" + correlation + " ACCURACY=" + trace.reading.accuracy
                + " AGE=" + trace.reading.age + " SPEED=" + trace.reading.speed + " GAP=" + trace.gap
                + " YANOSIK=" + (trace.yanosik ? 1 : 0) + " SPOTIFY=" + (trace.spotify ? 1 : 0));
    }

    private boolean acceptHome(Location location, long fixTime) {
        if (!cycleReady) {
            // The first qualified startup zone is remembered, but movement cannot emit before
            // the initial vendor read has established this boot/wake's departure opportunity.
            double age = lastFix - fixTime;
            if (initialParking == null && location.hasAccuracy() && Float.isFinite(location.getAccuracy())
                    && location.getAccuracy() >= 0 && location.getAccuracy() <= 15 && age >= 0 && age <= 3000
                    && !location.isFromMockProvider() && Double.isFinite(location.getLatitude())
                    && Double.isFinite(location.getLongitude()) && (!location.hasSpeed()
                    || Float.isFinite(location.getSpeed()) && location.getSpeed() >= 0)) {
                HomeDetector.Point point = config.project(location.getLatitude(), location.getLongitude());
                initialParking = Double.isFinite(point.x()) && Double.isFinite(point.y())
                        ? point.distance(config.geometry.parking()) <= 45 : null;
            }
            return true;
        }
        java.util.List<HomeEvent> events = detector.accept(new HomeDetector.Fix(fixTime,
                config.project(location.getLatitude(), location.getLongitude()),
                location.hasAccuracy() ? location.getAccuracy() : Double.POSITIVE_INFINITY,
                location.hasSpeed() ? location.getSpeed() : 0,
                lastFix - fixTime, location.isFromMockProvider(), location.hasSpeed()));
        ProgressBus.offer(this, progressEpoch, ProgressBus.HOME, detector.progress());
        recordDepartureReason();
        synchronized (detector) {
            int flags = detector.committedFlags();
            if (flags != state.getInt("flags", 0) && !state.edit().putInt("flags", flags).commit()) {
                stopSelf(); return false;
            }
        }
        for (HomeEvent event : events) {
            HomeConfiguration current = HomeConfiguration.load(this);
            if (current == null || !current.enabled || !current.revision.equals(config.revision)
                    || PrivateImport.pending(this)) { stopSelf(); return false; }
            automation.home(event);
        }
        return true;
    }
    private java.util.function.BooleanSupplier homeReservation(HomeEvent event) {
        HomeDetector owner = detector;
        HomeConfiguration configuration = config;
        long generation = owner == null ? -1 : owner.departureGeneration();
        return () -> {
            if (owner == null || owner != detector || configuration != config || destroyed || PrivateImport.pending(this)) return false;
            synchronized (owner) {
                if (event == HomeEvent.DEPARTURE_STARTED && owner.departureGeneration() != generation) return false;
                owner.commit(event);
                if (event == HomeEvent.DEPARTURE_STARTED && (owner.committedFlags() & 1) == 0) return false;
                boolean saved = state.edit().putInt("flags", owner.committedFlags()).commit();
                if (!saved) handler.post(this::stopSelf);
                return saved;
            }
        };
    }
    private void recordDepartureReason() {
        String reason = "DEPARTURE_" + detector.departureReason() + " STARTUP=" + startupId
                + " CYCLE=" + detector.departureGeneration()
                + " ACCURACY=" + accuracyBucket + " AGE=" + ageBucket + " SPEED=" + speedBucket;
        if (reason.equals(lastDepartureDiagnostic)) return;
        lastDepartureDiagnostic = reason;
        // Candidate/confirmed occurrence history belongs to its cancellable RAM buffer.
        for (var observed : ProgressBus.snapshot()) {
            if (observed.kind() == com.pbuchman.duduhome.automation.DetectionProgress.Kind.DEPARTURE
                    && ProgressBus.active(observed.id())) {
                Diagnostics.recordAttempt(this, observed.id(), reason + " ID=" + observed.id()
                        + " ELAPSED_MS=" + detector.departureElapsedMs());
                return;
            }
        }
        if (detector.departureReason() == HomeDetector.DepartureReason.CANDIDATE
                || detector.departureReason() == HomeDetector.DepartureReason.CONFIRMED
                || detector.departureReason() == HomeDetector.DepartureReason.CANCELLED_BASELINE) return;
        if (ProgressBus.snapshot().stream().anyMatch(observed -> observed.kind()
                == com.pbuchman.duduhome.automation.DetectionProgress.Kind.DEPARTURE
                && ProgressBus.active(observed.id()))) return;
        Diagnostics.record(this, reason);
    }
    private void updateGateArea(long validUntil) {
        if (automation == null) return;
        if (detector == null) automation.noHomeConfiguration();
        else automation.gateArea(detector.gateArea(), validUntil);
    }
    @Override public void onProviderDisabled(String provider) {
        if (routebook != null) routebook.breakSegment();
        if (trip != null) trip.breakSegment();
        Diagnostics.record(this, "GPS_PROVIDER_DISABLED");
        progressEpoch = ProgressBus.reset(this, Reason.GPS_UNRELIABLE);
        if (detector != null) detector.clearEvidence();
        updateGateArea(0);
        clearMotion("GPS_PROVIDER_DISABLED");
    }
    @Override public void onProviderEnabled(String provider) { Diagnostics.record(this, "GPS_PROVIDER_ENABLED"); }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {
        if (LocationManager.GPS_PROVIDER.equals(provider) && status != android.location.LocationProvider.AVAILABLE) {
            if (trip != null) trip.breakSegment();
            if (routebook != null) routebook.breakSegment();
        }
    }
    @Override public void onDestroy() {
        if (radioPower != null) radioPower.close(()->{if(routebook!=null)routebook.close();});
        else if (routebook != null) routebook.close();
        if (tripPresentation != null) tripPresentation.close();
        if (trip != null) trip.breakSegment();
        if (automation != null) automation.close();
        ProgressBus.clearCancellationListener(cancellation);
        destroyed = true;
        progressEpoch = ProgressBus.reset(this, Reason.SERVICE_STOPPED);
        if (progressOverlay != null) progressOverlay.close();
        cycleReader.shutdownNow();
        Diagnostics.record(this, "MONITOR_DESTROY");
        handler.removeCallbacksAndMessages(null);
        if (registered && locations != null) locations.removeUpdates(this);
        super.onDestroy();
    }
    @Override protected void dump(java.io.FileDescriptor fd, java.io.PrintWriter writer, String[] args) {
        writer.println("monitor_running=" + !destroyed + " registered=" + registered);
        writer.println("action_busy=" + HomeActions.busy());
        writer.println("startup=" + startupId + " cycle_ready=" + cycleReady);
        if (departureCycle != null) writer.println("departure_cycle=" + departureCycle.state().generation()
                + " baseline=" + departureCycle.state().baseline() + " consumed=" + departureCycle.state().consumed()
                + " blocked=" + departureCycle.blocked());
        writer.println("departure_reason=" + (detector == null ? "CONFIG_MISSING_OR_DISABLED" : detector.departureReason())
                + " candidate_elapsed_ms=" + (detector == null ? 0 : detector.departureElapsedMs()));
        writer.println("gps_accuracy=" + accuracyBucket + " gps_age=" + ageBucket + " gps_speed=" + speedBucket);
        MotionTrace trace = motionTrace;
        if (trace != null) writer.println(trace.dump());
        writer.println("diagnostic_pending=" + Diagnostics.pendingWrites() + " dropped=" + Diagnostics.droppedWrites());
        if (automation != null) writer.println(automation.diagnostic());
        if (trip != null) writer.println(trip.diagnostic());
        if (routebook != null) writer.println(routebook.diagnostic());
        if (radioPower != null) writer.println(radioPower.diagnostic());
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
