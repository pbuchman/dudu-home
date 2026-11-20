package com.pbuchman.duduhome.speedqa;

import android.content.Context;
import android.content.ContextWrapper;
import android.location.Location;
import android.os.SystemClock;

import com.pbuchman.duduhome.automation.AutomationRuntime;
import com.pbuchman.duduhome.automation.DetectionProgress.Kind;
import com.pbuchman.duduhome.automation.DetectionProgress.Phase;
import com.pbuchman.duduhome.automation.DetectionProgress.Reason;
import com.pbuchman.duduhome.automation.JourneySession;
import com.pbuchman.duduhome.automation.ProgressBus;
import com.pbuchman.duduhome.location.DepartureCycle;
import com.pbuchman.duduhome.location.HomeConfiguration;
import com.pbuchman.duduhome.location.HomeDetector;
import com.pbuchman.duduhome.location.HomeMonitorService;
import com.pbuchman.duduhome.startup.DuduCycle;

import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ExecutorService;

/** Supplies invented service input while a real speed test owns the foreground. */
final class SpeedDriveReplay {
    private static final double METRES_PER_DEGREE = 111_320;

    static void moving(Context context, AutomationRuntime runtime) {
        replay(context, runtime, false);
    }

    static void parking(Context context, AutomationRuntime runtime) {
        replay(context, runtime, true);
    }

    private static void replay(Context context, AutomationRuntime runtime, boolean parking) {
        File configuration = new File(context.getNoBackupFilesDir(), "home-config.json");
        check(!HomeMonitorService.ready(context) && !configuration.exists(),
                "Replay requires a fresh emulator with real GPS monitoring disabled");
        var state = context.getSharedPreferences("speed_replay_detector", 0);
        check(state.edit().clear().commit(), "clear synthetic detector flags");
        HomeMonitorService service = new HomeMonitorService();
        boolean created = false;
        try {
            try (var output = Files.newOutputStream(configuration.toPath(), StandardOpenOption.CREATE_NEW)) {
                created = true;
                output.write(configJson().getBytes(StandardCharsets.UTF_8));
            }
            var attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
            attach.setAccessible(true);
            attach.invoke(service, context);
            HomeConfiguration config = HomeConfiguration.load(context);
            check(config != null && config.enabled, "load invented route geometry");
            DepartureCycle cycle = DepartureCycle.memory(0);
            HomeDetector detector = new HomeDetector(config.geometry, 0, cycle);
            detector.requireFreshBaseline();
            set(service, "config", config);
            set(service, "departureCycle", cycle);
            set(service, "detector", detector);
            set(service, "state", state);
            set(service, "automation", runtime);
            set(service, "progressEpoch", ProgressBus.reset(context, Reason.RESET));

            var session = new JourneySession(context);
            check(session.ensureBoot(), "synthetic boot is available");
            Method onCycleRead = method("onCycleRead", DuduCycle.Observation.class);
            long baseline = Math.max(0, session.wakeCounter());
            onCycleRead.invoke(service, observation(baseline));
            onCycleRead.invoke(service, observation(baseline + 1));
            check(session.wakeCounter() == baseline + 1 && cycle.state().counter() == baseline + 1,
                    "the service observed a newer verified wake");

            Method acceptHome = method("acceptHome", Location.class, long.class);
            Method gateArea = method("updateGateArea", long.class);
            Method acceptMotion = method("acceptMotion", Location.class, long.class, double.class, long.class,
                    Class.forName("com.pbuchman.duduhome.location.GpsStartupTrace$Observation"));
            int seconds = parking ? 4 : 10; // The parking replay starts with one stationary fix.
            long now = SystemClock.elapsedRealtime();
            for (int second = 0; second <= seconds; second++) {
                // Replay callback-relative age and one-second monotonic spacing without delaying
                // delivery long enough for the speed-test fixture to complete first.
                long fixTime = now - (seconds - second) * 1_000L;
                float speed = parking && second == 0 ? 0f : 5f;
                Location fix = location(fixTime, (parking ? 10 : 1_500) + second * 5, speed);
                set(service, "lastFix", fixTime);
                check((boolean) acceptHome.invoke(service, fix, fixTime), "service accepted synthetic home fix");
                gateArea.invoke(service, now + 3_000L);
                acceptMotion.invoke(service, fix, fixTime, 3.0, 0L, null);
                if (second < seconds) {
                    check(!waiting(Kind.DEPARTURE) && !waiting(Kind.YANOSIK) && !waiting(Kind.SPOTIFY),
                            "no automation dispatched before the movement threshold");
                }
            }
            check(cycle.state().baseline() == (parking ? DepartureCycle.Baseline.PARKING : DepartureCycle.Baseline.OUTSIDE),
                    "first good fix established the intended departure baseline");
            if (parking) {
                check(waiting(Kind.DEPARTURE) && !waiting(Kind.YANOSIK) && !waiting(Kind.SPOTIFY),
                        "parking motion queued a gate call ahead of generic media");
            } else {
                check(!waiting(Kind.DEPARTURE) && waiting(Kind.YANOSIK) && waiting(Kind.SPOTIFY),
                        "first motion outside the gate queued both media targets without a gate call");
            }
        } catch (Exception failure) {
            throw new AssertionError("Synthetic service replay failed", failure);
        } finally {
            ((ExecutorService) get(service, "cycleReader")).shutdownNow();
            check(state.edit().clear().commit(), "remove synthetic detector flags");
            if (created) check(configuration.delete(), "remove invented route geometry");
        }
    }

    private static boolean waiting(Kind kind) {
        return ProgressBus.snapshot().stream().anyMatch(state -> state.kind() == kind && state.phase() == Phase.WAITING);
    }

    private static Location location(long fixTime, double east, float speed) {
        Location result = new Location("synthetic");
        result.setLatitude(0);
        result.setLongitude(east / METRES_PER_DEGREE);
        result.setAccuracy(3);
        result.setSpeed(speed);
        result.setElapsedRealtimeNanos(fixTime * 1_000_000);
        return result;
    }

    private static String configJson() throws org.json.JSONException {
        JSONObject points = new JSONObject();
        points.put("parking", point(0));
        points.put("gate", point(30));
        points.put("approach", point(150));
        points.put("junction", point(400));
        return new JSONObject().put("schema_version", 1).put("automation_enabled", true)
                .put("points", points).toString();
    }

    private static JSONObject point(double east) throws org.json.JSONException {
        return new JSONObject().put("lat", 0).put("lon", east / METRES_PER_DEGREE);
    }

    private static DuduCycle.Observation observation(long counter) {
        return new DuduCycle.Observation(DuduCycle.Kind.AWAKE_COUNTER, counter);
    }

    private static Method method(String name, Class<?>... parameters) throws ReflectiveOperationException {
        Method method = HomeMonitorService.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static void set(HomeMonitorService service, String field, Object value) throws ReflectiveOperationException {
        var declared = HomeMonitorService.class.getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(service, value);
    }

    private static Object get(HomeMonitorService service, String field) {
        try {
            var declared = HomeMonitorService.class.getDeclaredField(field);
            declared.setAccessible(true);
            return declared.get(service);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static void check(boolean pass, String description) {
        if (!pass) throw new AssertionError(description);
    }
}
