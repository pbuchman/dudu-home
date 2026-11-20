package com.pbuchman.duduhome.location;

import com.pbuchman.duduhome.automation.DetectionProgress;
import static com.pbuchman.duduhome.automation.DetectionProgress.Kind.YANOSIK;

/** Pure sustained-motion evidence. No home coordinates, Android APIs or side effects. */
public final class MotionDetector {
    public record Fix(long time, double x, double y, double accuracy, double speed,
                      boolean hasSpeed, long age, boolean mock) { }
    public enum Source { NONE, SERVICE_RECOVERY, USER_CANCELLED, VERIFIED_BOOT, VERIFIED_WAKE }
    public enum State { WAITING, BASELINE_REQUIRED, BASELINE_WAIT, BASELINE_READY, REJECTED, STOPPED, CANDIDATE, CONFIRMED }
    public enum Reason { NONE, RESET, NEED_STOP, STOP_DWELL, STOP_CONFIRMED, BAD_TIME, STALE_FIX, MOCK_FIX,
        SPEED_MISSING, SPEED_INVALID, ACCURACY_MISSING, ACCURACY_INVALID, ACCURACY_TOO_LOW, POSITION_INVALID,
        NONMONOTONIC, GAP, JUMP, ACCURACY_OVERLAP }
    public record Evidence(State state, Reason reason, Source source, boolean needsStop,
                           long elapsed, long gap, boolean moving) { }

    private Fix anchor, previous, baselinePrevious;
    private boolean stationaryBaselineRequired, displacementBeyondAccuracyRequired;
    private Source source = Source.NONE;
    private long stationarySince = -1, lastValidTime = -1;
    private Evidence evidence = new Evidence(State.WAITING, Reason.NONE, Source.NONE, false, 0, -1, false);
    private final DetectionProgress.Tracker progress = new DetectionProgress.Tracker();
    public synchronized java.util.List<DetectionProgress> progress() { return progress.snapshot(); }
    public synchronized Evidence evidence() { return evidence; }

    public synchronized boolean accept(Fix f) { return accept(f, true); }

    private boolean accept(Fix f, boolean detectMovement) {
        Reason quality = quality(f);
        if (quality != Reason.NONE) {
            clearEvidence(DetectionProgress.Reason.GPS_UNRELIABLE);
            report(State.REJECTED, quality, 0, -1, false);
            return false;
        }
        if (lastValidTime >= 0 && f.time <= lastValidTime) {
            report(State.REJECTED, Reason.NONMONOTONIC, 0, f.time - lastValidTime, false);
            return false;
        }
        long gap = lastValidTime < 0 ? -1 : f.time - lastValidTime;
        lastValidTime = f.time;
        if (stationaryBaselineRequired || (displacementBeyondAccuracyRequired && f.speed < 0.5)) {
            clearMovementEvidence(DetectionProgress.Reason.STOPPED);
            acceptBaseline(f, gap);
            return false;
        }
        if (f.speed < 1) {
            clearEvidence(DetectionProgress.Reason.STOPPED);
            report(State.STOPPED, Reason.NONE, 0, gap, false);
            return false;
        }
        clearStationaryEvidence();
        if (!detectMovement) {
            clearMovementEvidence(DetectionProgress.Reason.RESET);
            report(State.WAITING, Reason.NONE, 0, gap, true);
            return false;
        }
        Reason interruption = discontinuity(previous, f);
        if (interruption != Reason.NONE) clearMovementEvidence(DetectionProgress.Reason.RESET);
        if (anchor == null) anchor = f;
        previous = f;
        long elapsed = f.time - anchor.time;
        double distance = Math.hypot(f.x - anchor.x, f.y - anchor.y);
        boolean meetsMovementThresholds = elapsed >= 10000 && distance >= 15;
        boolean accuracyOverlap = meetsMovementThresholds && displacementBeyondAccuracyRequired
                && distance <= anchor.accuracy + f.accuracy;
        boolean confirmed = meetsMovementThresholds && !accuracyOverlap;
        progress.update(YANOSIK, !confirmed, confirmed, Math.min(elapsed / 10000.0, distance / 15.0),
                f.time + 3000, DetectionProgress.Reason.NONE);
        report(confirmed ? State.CONFIRMED : State.CANDIDATE,
                accuracyOverlap ? Reason.ACCURACY_OVERLAP : interruption, elapsed, gap, true);
        return confirmed;
    }

    private void acceptBaseline(Fix fix, long gap) {
        if (fix.speed >= 0.5) {
            clearStationaryEvidence();
            report(State.BASELINE_WAIT, Reason.NEED_STOP, 0, gap, fix.speed >= 1);
            return;
        }
        Reason interruption = discontinuity(baselinePrevious, fix);
        if (stationarySince < 0 || interruption != Reason.NONE) stationarySince = fix.time;
        baselinePrevious = fix;
        long elapsed = fix.time - stationarySince;
        if (elapsed >= 10000) {
            stationaryBaselineRequired = false;
            displacementBeyondAccuracyRequired = false;
            clearEvidence(DetectionProgress.Reason.RESET);
            report(State.BASELINE_READY, Reason.STOP_CONFIRMED, elapsed, gap, false);
        } else {
            report(State.BASELINE_WAIT, interruption == Reason.NONE ? Reason.STOP_DWELL : interruption,
                    elapsed, gap, false);
        }
    }

    /** Observe a stop while home actions or presentation block generic movement. */
    public synchronized void observeBaseline(Fix fix) { accept(fix, false); }

    public synchronized boolean hasSpatialEvidence() { return previous != null || baselinePrevious != null; }
    public synchronized boolean fresh(long now) { return previous != null && now >= previous.time && now - previous.time <= 3000; }

    public synchronized void cancel() { requireBaseline(Source.USER_CANCELLED); }
    public synchronized void requireStationaryBaseline() { requireBaseline(Source.SERVICE_RECOVERY); }
    private void requireBaseline(Source required) {
        stationaryBaselineRequired = true;
        displacementBeyondAccuracyRequired = false;
        source = required;
        clearEvidence(DetectionProgress.Reason.RESET);
        report(State.BASELINE_REQUIRED, Reason.NEED_STOP, 0, -1, false);
    }

    /** A confirmed cycle can replace recovery's stop requirement with stronger movement evidence. */
    public synchronized void verifiedCycle(Source verified) {
        if (verified != Source.VERIFIED_BOOT && verified != Source.VERIFIED_WAKE)
            throw new IllegalArgumentException("Missing verified cycle");
        clearEvidence(DetectionProgress.Reason.RESET);
        if (stationaryBaselineRequired && source == Source.USER_CANCELLED) {
            report(State.BASELINE_REQUIRED, Reason.NEED_STOP, 0, -1, false);
            return;
        }
        stationaryBaselineRequired = false;
        displacementBeyondAccuracyRequired = true;
        source = verified;
        report(State.BASELINE_READY, Reason.NONE, 0, -1, false);
    }

    private static Reason quality(Fix f) {
        if (f == null || f.time < 0 || f.age < 0) return Reason.BAD_TIME;
        if (f.mock) return Reason.MOCK_FIX;
        if (f.age > 3000) return Reason.STALE_FIX;
        if (!f.hasSpeed) return Reason.SPEED_MISSING;
        if (!Double.isFinite(f.speed) || f.speed < 0) return Reason.SPEED_INVALID;
        if (Double.isNaN(f.accuracy)) return Reason.ACCURACY_MISSING;
        if (!Double.isFinite(f.accuracy) || f.accuracy < 0) return Reason.ACCURACY_INVALID;
        if (f.accuracy > 15) return Reason.ACCURACY_TOO_LOW;
        if (!Double.isFinite(f.x) || !Double.isFinite(f.y)) return Reason.POSITION_INVALID;
        return Reason.NONE;
    }

    private static Reason discontinuity(Fix before, Fix after) {
        if (before == null) return Reason.NONE;
        long gap = after.time - before.time;
        if (gap > 3000) return Reason.GAP;
        if (Math.hypot(after.x - before.x, after.y - before.y)
                > Math.max(after.speed, before.speed) * gap / 1000.0 + after.accuracy + before.accuracy + 15)
            return Reason.JUMP;
        return Reason.NONE;
    }

    private void clearMovementEvidence(DetectionProgress.Reason reason) {
        if (previous != null) progress.clear(reason);
        anchor = previous = null;
    }

    private void clearStationaryEvidence() {
        baselinePrevious = null;
        stationarySince = -1;
    }

    private void clearEvidence(DetectionProgress.Reason reason) {
        anchor = previous = null;
        clearStationaryEvidence();
        progress.clear(reason);
    }

    /** Gaps discard dwell/movement evidence, preserving the recovery or cancellation latch. */
    public synchronized void clear() {
        clearEvidence(DetectionProgress.Reason.RESET);
        report(stationaryBaselineRequired ? State.BASELINE_REQUIRED : State.WAITING, Reason.RESET, 0, -1, false);
    }

    private void report(State state, Reason reason, long elapsed, long gap, boolean moving) {
        evidence = new Evidence(state, reason, source, stationaryBaselineRequired, elapsed, gap, moving);
    }
}
