package com.pbuchman.duduhome.location;

import com.pbuchman.duduhome.diagnostics.CategoryFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

public final class GpsStartupChecks {
    public static void main(String[] args) {
        delayedCallbackAndWake();
        emptyCallbackAndRejections();
        System.out.println("PASS: GPS startup timing, watchdog evidence, fix qualification and deferred archive attribution");
    }

    private static void delayedCallbackAndWake() {
        List<String> events = new ArrayList<>();
        long start = 200_000;
        GpsStartupTrace trace = new GpsStartupTrace(start, events::add);
        GpsStartupTrace.Observation startup = trace.current();
        startup.subscription(start + 25, false);
        startup.subscription(start + 30, false);
        startup.cycleRead(start + 50, "BASELINE_COUNTER", 4, 4);
        startup.cycleRead(start + 55, "UNAVAILABLE", -1, 4);
        startup.cycleRead(start + 60, "UNAVAILABLE", -1, 4);
        startup.watchdog(start + 15_001);
        startup.watchdog(start + 30_001);
        startup.subscription(start + 30_003, true);

        long delivered = start + 46_000;
        startup.callback(delivered, true);
        startup.callback(delivered + 1_000, true);
        MotionDetector detector = new MotionDetector();
        detector.requireStationaryBaseline();
        detector.observeBaseline(fix(delivered, 5, 0, 0));
        startup.motionFix(delivered + 10, detector.evidence());
        startup.motionFix(delivered + 11, detector.evidence());

        LongConsumer delayedCommit = startup.archiveObserver();
        trace.verifiedWake(start + 50_000, 5, true);
        trace.verifiedWake(start + 50_001, 5, true);
        trace.verifiedWake(start + 50_002, 4, true);
        GpsStartupTrace.Observation wake = trace.current();
        wake.cycleRead(start + 50_000, "REARMED", 5, 5);
        delayedCommit.accept(start + 51_000);
        delayedCommit.accept(start + 51_010);
        check(startup.archiveObserver() == null && wake.archiveObserver() != null, "old queued write leaves wake archive milestone unset");
        wake.callback(start + 52_000, true);
        wake.archiveObserver().accept(start + 52_100);
        check(events.equals(List.of(
                line(0, "STARTED", 0, " REASON=SERVICE_START"),
                line(0, "SUBSCRIBE_DENIED", 25, ""),
                line(0, "CYCLE_READ", 50, " STATE=BASELINE_COUNTER CYCLE=4"),
                line(0, "CYCLE_READ", 55, " STATE=UNAVAILABLE CYCLE=-1"),
                line(0, "NO_CALLBACK", 15_001, " CYCLE=4"),
                line(0, "RESUBSCRIBE", 15_001, " CYCLE=4"),
                line(0, "NO_CALLBACK", 30_001, " CYCLE=4"),
                line(0, "RESUBSCRIBE", 30_001, " CYCLE=4"),
                line(0, "SUBSCRIBED", 30_003, " STATE=ACTIVE CYCLE=4"),
                line(0, "FIRST_CALLBACK", 46_000, " STATE=RECEIVED CYCLE=4"),
                line(0, "FIRST_USABLE_MOTION_FIX", 46_010, " STATE=BASELINE_WAIT CYCLE=4"),
                line(1, "STARTED", 0, " REASON=VERIFIED_WAKE CYCLE=5"),
                line(1, "SUBSCRIBED", 0, " STATE=ALREADY_ACTIVE CYCLE=5"),
                line(1, "CYCLE_READ", 0, " STATE=REARMED CYCLE=5"),
                line(0, "FIRST_ARCHIVE_COMMIT", 51_000, " CYCLE=4"),
                line(1, "FIRST_CALLBACK", 2_000, " STATE=RECEIVED CYCLE=5"),
                line(1, "FIRST_ARCHIVE_COMMIT", 2_100, " CYCLE=5")
        )), "retain the 46 s monotonic startup and both commit windows in completion order");
        check(events.stream().allMatch(CategoryFormat::valid), "allow all typed startup categories");
    }

    private static void emptyCallbackAndRejections() {
        List<String> events = new ArrayList<>();
        long start = 200_000;
        GpsStartupTrace.Observation observation = new GpsStartupTrace(start, events::add).current();
        observation.callback(start + 1, false);
        observation.motionRejected(start + 1, "EMPTY_CALLBACK");
        observation.watchdog(start + 15_002);
        check(events.contains(line(0, "FIRST_CALLBACK", 1, " STATE=EMPTY")), "empty callback proves delivery");
        check(events.stream().noneMatch(event -> event.contains("STAGE=NO_CALLBACK")), "watchdog cannot report no delivery after empty callback");

        MotionDetector detector = new MotionDetector();
        long now = start + 16_000;
        for (int n = 0; n < 12; n++) {
            int accuracy = n < 2 || n % 2 == 1 ? 40 : 5;
            long age = accuracy == 40 ? 0 : 4_000;
            detector.accept(fix(now + n, accuracy, 0, age));
            observation.motionFix(now + n, detector.evidence());
        }
        check(events.stream().filter(event -> event.contains("STAGE=MOTION_FIX_REJECTED")).count() == 8,
                "deduplicate repeated rejection and bound changes");
        check(events.contains(line(0, "MOTION_FIX_REJECTED", 16_000, " REASON=ACCURACY_TOO_LOW"))
                        && !events.contains(line(0, "MOTION_FIX_REJECTED", 16_001, " REASON=ACCURACY_TOO_LOW"))
                        && events.contains(line(0, "MOTION_FIX_REJECTED", 16_002, " REASON=STALE_FIX")),
                "capture the first quality failures and the first reason change");
        check(events.stream().noneMatch(event -> event.contains("STAGE=FIRST_USABLE_MOTION_FIX")), "rejected inputs leave usable fix unset");
        detector.accept(fix(now + 1_000, 5, 0, 0));
        observation.motionFix(now + 1_000, detector.evidence());
        check(events.contains(line(0, "FIRST_USABLE_MOTION_FIX", 17_000, " STATE=STOPPED")), "stopped fixes are usable");

        detector.clear();
        for (int n = 0; n <= 12; n++) {
            long motionTime = now + 2_000 + n * 1_000;
            detector.accept(new MotionDetector.Fix(motionTime, n * 3, 0, 5, 3, true, 0, false));
            observation.motionFix(motionTime, detector.evidence());
        }
        check(events.stream().filter(event -> event.contains("STAGE=MOTION_CONFIRMED")).count() == 1,
                "only actual sustained motion leads to a confirmation");
        check(events.stream().allMatch(CategoryFormat::valid), "allow controlled reasons");
        check(!CategoryFormat.valid(line(0, "STARTED", 0, " COORDINATES=UNKNOWN")), "reject unexpected payload fields");
    }

    private static MotionDetector.Fix fix(long time, double accuracy, double speed, long age) {
        return new MotionDetector.Fix(time, 0, 0, accuracy, speed, true, age, false);
    }

    private static String line(long observation, String stage, long elapsed, String detail) {
        return "GPS_STARTUP STARTUP=200000 OBSERVATION=" + observation + " STAGE=" + stage + " ELAPSED_MS=" + elapsed + detail;
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
