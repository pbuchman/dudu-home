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
    private static final String CHANNEL = "home_monitor";
    private LocationManager locations;
    private HomeConfiguration config;
    private HomeDetector detector;
    private SharedPreferences state;
    private boolean registered;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastFix;
    private long lastSummary;
    private int fixes, poorFixes;
    private boolean destroyed, cycleReady, cyclePending;
    private String lastCycleDiagnostic;
    private final java.util.concurrent.ExecutorService cycleReader = java.util.concurrent.Executors.newSingleThreadExecutor();
    private AutomationRuntime automation;
    private ProgressOverlay progressOverlay;
    private long progressEpoch;
    private final MotionHook motion = new MotionHook(() -> automation.motion());
    private Location motionOrigin;
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!ready(HomeMonitorService.this) || PrivateImport.pending(HomeMonitorService.this)) {
                Diagnostics.record(HomeMonitorService.this, "MONITOR_STOP_NOT_READY");
                stopSelf();
                return;
            }
            if (SystemClock.elapsedRealtime() - lastFix > 15000) {
                Diagnostics.record(HomeMonitorService.this, "GPS_GAP_RESUBSCRIBE");
                progressEpoch = ProgressBus.reset(HomeMonitorService.this, Reason.GPS_UNRELIABLE);
                if (detector != null) detector.clearEvidence();
                updateGateArea(0);
                motion.clear(); motionOrigin = null;
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
                == PackageManager.PERMISSION_GRANTED) && Settings.canDrawOverlays(context)
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
        Diagnostics.record(this, "MONITOR_CREATE");
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
        state = getSharedPreferences("home_detector", 0);
        locations = getSystemService(LocationManager.class);
        automation = new AutomationRuntime(this);
        new JourneySession(this).ensureBoot();
        try { progressOverlay = new ProgressOverlay(this); }
        catch (RuntimeException unavailable) { Diagnostics.record(this, "PROGRESS_OVERLAY_UNAVAILABLE"); }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        HomeConfiguration next = HomeConfiguration.load(this);
        if (state == null || !ready(this) || PrivateImport.pending(this)) {
            Diagnostics.record(this, "MONITOR_STOP_NOT_READY");
            stopSelf(); return START_NOT_STICKY;
        }
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
            detector = new HomeDetector(config.geometry, saved);
            if (!state.edit().putString("revision", config.revision).putInt("flags", saved).commit()) {
                stopSelf(); return START_NOT_STICKY;
            }
        }
        Diagnostics.record(this, "MONITOR_START HOME=" + (detector == null ? 0 : 1)
                + " FLAGS=" + (detector == null ? 0 : detector.flags()));
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
        cyclePending = false;
        JourneySession session = new JourneySession(this);
        JourneySession.ObservationResult result = session.observeAwakeCycle(cycle);
        if (result == JourneySession.ObservationResult.REARMED) {
            if (automation != null) automation.reset(Reason.WAKE);
            progressEpoch = ProgressBus.reset(this, Reason.WAKE);
            // Never carry pre-sleep movement evidence into a new cycle.
            motion.clear(); motionOrigin = null;
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
        cycleReady = true;
    }

    @SuppressWarnings("MissingPermission")
    private void subscribe() {
        if (registered || !ready(this)) return;
        try {
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this, Looper.getMainLooper());
            registered = true;
            Diagnostics.record(this, "GPS_SUBSCRIBED");
        } catch (RuntimeException denied) { Diagnostics.record(this, "GPS_SUBSCRIPTION_DENIED"); }
    }

    @Override public void onLocationChanged(Location location) {
        if (!ready(this) || PrivateImport.pending(this)) { stopSelf(); return; }
        lastFix = SystemClock.elapsedRealtime();
        long fixTime = location.getElapsedRealtimeNanos() / 1000000;
        fixes++;
        if (!location.hasAccuracy() || location.getAccuracy() > 15 || lastFix - fixTime > 3000
                || location.isFromMockProvider()) poorFixes++;
        if (lastFix - lastSummary >= 30000) {
            Diagnostics.record(this, "GPS_SUMMARY FIXES=" + fixes + " POOR=" + poorFixes
                    + " HOME=" + (detector == null ? 0 : 1)
                    + " FLAGS=" + (detector == null ? 0 : detector.flags()));
            fixes = poorFixes = 0; lastSummary = lastFix;
        }
        if (motionOrigin == null) motionOrigin = new Location(location);
        float[] distance = new float[2];
        Location.distanceBetween(motionOrigin.getLatitude(), motionOrigin.getLongitude(),
                location.getLatitude(), location.getLongitude(), distance);
        double bearing = Math.toRadians(distance[1]);
        MotionDetector.Fix motionFix = new MotionDetector.Fix(fixTime, distance[0] * Math.sin(bearing),
                distance[0] * Math.cos(bearing), location.hasAccuracy() ? location.getAccuracy() : Double.POSITIVE_INFINITY,
                location.getSpeed(), location.hasSpeed(), lastFix - fixTime, location.isFromMockProvider());
        // Home actions get first opportunity on the same fix; Yanosik never masks their result.
        if (detector != null && !acceptHome(location, fixTime)) return;
        updateGateArea(fixTime + 3000);
        if (!automation.allowsMotionDetection()) {
            motion.clear(); motionOrigin = null;
            ProgressBus.offer(this, progressEpoch, ProgressBus.MOTION, java.util.List.of());
            return;
        }
        motion.accept(motionFix, cycleReady && !PrivateImport.pending(this),
                observed -> ProgressBus.offer(this, progressEpoch, ProgressBus.MOTION,
                        (new JourneySession(this).available(JourneySession.Target.SPOTIFY)
                                || (new JourneySession(this).available()
                                && com.pbuchman.duduhome.automation.YanosikPresence.read(this)
                                   != com.pbuchman.duduhome.automation.YanosikPresence.State.WORK_DETECTED))
                                ? observed : java.util.List.of()));
        if (!motion.fresh(lastFix)) motionOrigin = null;
    }

    private boolean acceptHome(Location location, long fixTime) {
        java.util.List<HomeEvent> events = detector.accept(new HomeDetector.Fix(fixTime,
                config.project(location.getLatitude(), location.getLongitude()),
                location.hasAccuracy() ? location.getAccuracy() : Double.POSITIVE_INFINITY,
                location.hasSpeed() ? location.getSpeed() : 0,
                lastFix - fixTime, location.isFromMockProvider()));
        ProgressBus.offer(this, progressEpoch, ProgressBus.HOME, detector.progress());
        int flags = detector.flags();
        if (flags != state.getInt("flags", 0) && !state.edit().putInt("flags", flags).commit()) {
            stopSelf(); return false; // Persist consumed events before any side effect.
        }
        for (HomeEvent event : events) {
            HomeConfiguration current = HomeConfiguration.load(this);
            if (current == null || !current.enabled || !current.revision.equals(config.revision)
                    || PrivateImport.pending(this)) { stopSelf(); return false; }
            Diagnostics.record(this, "EVENT " + event.name());
            automation.home(event);
        }
        return true;
    }
    private void updateGateArea(long validUntil) {
        if (automation == null) return;
        if (detector == null) automation.noHomeConfiguration();
        else automation.gateArea(detector.gateArea(), validUntil);
    }
    @Override public void onProviderDisabled(String provider) {
        Diagnostics.record(this, "GPS_PROVIDER_DISABLED");
        progressEpoch = ProgressBus.reset(this, Reason.GPS_UNRELIABLE);
        if (detector != null) detector.clearEvidence();
        updateGateArea(0);
        motion.clear(); motionOrigin = null;
    }
    @Override public void onProviderEnabled(String provider) { Diagnostics.record(this, "GPS_PROVIDER_ENABLED"); }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    @Override public void onDestroy() {
        if (automation != null) automation.close();
        destroyed = true;
        progressEpoch = ProgressBus.reset(this, Reason.SERVICE_STOPPED);
        if (progressOverlay != null) progressOverlay.close();
        cycleReader.shutdownNow();
        Diagnostics.record(this, "MONITOR_DESTROY");
        handler.removeCallbacksAndMessages(null);
        if (registered && locations != null) locations.removeUpdates(this);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
