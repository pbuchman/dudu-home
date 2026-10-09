package com.pbuchman.duduhome.location;

import com.pbuchman.duduhome.automation.DetectionProgress;
import static com.pbuchman.duduhome.automation.DetectionProgress.Kind.YANOSIK;
import static com.pbuchman.duduhome.automation.DetectionProgress.Reason.*;

/** Pure sustained-motion evidence. No home coordinates, Android APIs or side effects. */
public final class MotionDetector {
    public record Fix(long time, double x, double y, double accuracy, double speed,
                      boolean hasSpeed, long age, boolean mock) { }
    private Fix anchor, previous, baselinePrevious;
    private boolean stationaryBaselineRequired;
    private long stationarySince = -1;
    private final DetectionProgress.Tracker progress = new DetectionProgress.Tracker();
    public synchronized java.util.List<DetectionProgress> progress() { return progress.snapshot(); }

    public synchronized boolean accept(Fix f) {
        if (f == null || f.mock || f.time < 0 || f.age < 0 || f.age > 3000 || !f.hasSpeed
                || !Double.isFinite(f.speed) || f.speed < 0 || !Double.isFinite(f.accuracy)
                || f.accuracy < 0 || f.accuracy > 15 || !Double.isFinite(f.x) || !Double.isFinite(f.y)) {
            clear();
            progress.clear(f != null && f.hasSpeed && f.speed >= 0 && f.speed < 1 ? STOPPED : GPS_UNRELIABLE);
            return false;
        }
        if (stationaryBaselineRequired) {
            if (baselinePrevious != null && f.time <= baselinePrevious.time) return false;
            if (baselinePrevious != null && discontinuous(baselinePrevious, f)) stationarySince = -1;
            baselinePrevious = f;
            if (f.speed < 0.5) {
                if (stationarySince < 0) stationarySince = f.time;
                if (f.time - stationarySince >= 10000) {
                    stationaryBaselineRequired = false;
                    clear();
                }
            } else stationarySince = -1;
            return false;
        }
        if (f.speed < 1) {
            clear();
            progress.clear(STOPPED);
            return false;
        }
        if (previous != null && f.time <= previous.time) return false;
        if (previous != null && discontinuous(previous, f)) clear();
        if (anchor == null) anchor = f;
        previous = f;
        boolean confirmed = f.time - anchor.time >= 10000 && Math.hypot(f.x - anchor.x, f.y - anchor.y) >= 15;
        progress.update(YANOSIK, !confirmed, confirmed, Math.min((f.time - anchor.time) / 10000.0,
                Math.hypot(f.x - anchor.x, f.y - anchor.y) / 15.0), f.time + 3000, NONE);
        return confirmed;
    }

    /** Observe only the required stop while home actions block generic movement. */
    public synchronized void observeBaseline(Fix fix) {
        clearMovementEvidence();
        if (stationaryBaselineRequired) accept(fix);
    }
    public synchronized void clearMovementEvidence() {
        anchor = previous = null;
        progress.clear(RESET);
    }
    public synchronized boolean fresh(long now) { return previous != null && now >= previous.time && now - previous.time <= 3000; }
    /** Cancellation and process recovery both require a new observed stop before movement. */
    public synchronized void cancel() { requireStationaryBaseline(); }
    public synchronized void requireStationaryBaseline() {
        stationaryBaselineRequired = true;
        clear();
    }
    private static boolean discontinuous(Fix before, Fix after) {
        return after.time - before.time > 3000
                || Math.hypot(after.x - before.x, after.y - before.y)
                > Math.max(after.speed, before.speed) * (after.time - before.time) / 1000.0
                    + after.accuracy + before.accuracy + 15;
    }
    /** GPS gaps discard dwell/movement evidence, but never release the cancellation latch. */
    public synchronized void clear() {
        anchor = previous = baselinePrevious = null;
        stationarySince = -1;
        progress.clear(RESET);
    }
}
