package com.pbuchman.duduhome.location;

import com.pbuchman.duduhome.automation.HomeEvent;
import com.pbuchman.duduhome.automation.DetectionProgress;
import static com.pbuchman.duduhome.automation.DetectionProgress.Kind.*;
import static com.pbuchman.duduhome.automation.DetectionProgress.Reason.*;

import java.util.ArrayList;
import java.util.List;

/** Pure detector: metres in a local east/north plane. No Android, coordinates or call APIs. */
public final class HomeDetector {
    public record Point(double x, double y) {
        double distance(Point other) { return Math.hypot(x - other.x, y - other.y); }
    }
    public record Geometry(Point parking, Point gate, Point approach, Point junction) { }
    public record Fix(long elapsedMs, Point point, double accuracy, double speed, long ageMs, boolean mock, boolean speedPresent) {
        public Fix(long elapsedMs, Point point, double accuracy, double speed, long ageMs, boolean mock) {
            this(elapsedMs, point, accuracy, speed, ageMs, mock, true);
        }
    }
    public enum GateArea { UNKNOWN, NONE, DEPARTURE, RETURN }
    private static final double DEPARTURE_RADIUS = 85;
    private GateArea gateArea = GateArea.UNKNOWN;
    /** Broad precondition only, independent of whether the one-shot event was consumed. */
    public synchronized GateArea gateArea() { return gateArea; }
    private final Geometry geometry;
    private final DepartureCycle departureCycle;
    public enum DepartureReason { NO_FIX, MOCK, STALE, ACCURACY, NONFINITE, OUT_OF_ORDER, JUMP,
        GAP, SPEED_MISSING, STOPPED, OUTSIDE_PARKING_BASELINE, OUTSIDE_DEPARTURE,
        CONSUMED, CANCELLED_BASELINE, STATE_BLOCKED, CANDIDATE, CONFIRMED }
    private DepartureReason departureReason = DepartureReason.NO_FIX;
    private boolean departureWasAway;
    private long departureAwaySince = -1, departureParkedSince = -1;
    public synchronized DepartureReason departureReason() { return departureReason; }
    public synchronized long departureElapsedMs() { return movingSince < 0 ? 0 : Math.max(0, lastTime - movingSince); }
    public synchronized long departureGeneration() { return departureCycle.state().generation(); }
    public synchronized void departureCycleChanged() {
        departureConsumed = departureCycle.state().consumed();
        committedEvents = (committedEvents & ~1) | (departureConsumed ? 1 : 0);
        departureIgnored = homeArmed = departureWasAway = false;
        movingSince = departureRearmSince = departureAwaySince = departureParkedSince = -1;
        departureReason = DepartureReason.NO_FIX;
    }
    private boolean departureConsumed, returnConsumed, checkpointConsumed, exitConsumed, wasAway;
    private boolean homeArmed, roadApproach, visitedJunction;
    // Broad route geometry must survive action cancellation for media priority.
    private boolean broadRoadApproach, broadVisitedJunction;
    private long broadJunctionTime = -1;
    private boolean departureIgnored, returnIgnored, cleaningIgnored;
    // Cleaning observes the outward drive independently of the gate-call decision.
    private boolean outboundArmed, outboundDeparture;
    private Point outboundAnchor;
    private double outboundAnchorGateDistance;
    private long outboundMovingSince = -1, exitJunctionTime = -1;
    private long departureRearmSince = -1, cleaningRearmSince = -1, returnRearmSince = -1;
    private int committedEvents;
    private Point previous;
    private long lastTime = -1, stationarySince = -1, movingSince = -1, junctionTime = -1;
    private long awaySince = -1;
    private final DetectionProgress.Tracker progress = new DetectionProgress.Tracker();
    public synchronized List<DetectionProgress> progress() { return progress.snapshot(); }

    public HomeDetector(Geometry geometry, int persistedFlags) {
        this(geometry, persistedFlags, DepartureCycle.memory(persistedFlags));
    }
    public HomeDetector(Geometry geometry, int persistedFlags, DepartureCycle departureCycle) {
        this.geometry = geometry;
        this.departureCycle = departureCycle;
        committedEvents = persistedFlags & 15;
        departureConsumed = departureCycle.state().consumed();
        committedEvents = (committedEvents & ~1) | (departureConsumed ? 1 : 0);
        departureWasAway = departureCycle.state().away();
        returnConsumed = (persistedFlags & 2) != 0;
        checkpointConsumed = (persistedFlags & 4) != 0;
        exitConsumed = (persistedFlags & 8) != 0;
        wasAway = (persistedFlags & 16) != 0;
    }

    public synchronized int flags() {
        return (departureConsumed ? 1 : 0) | (returnConsumed ? 2 : 0)
                | (checkpointConsumed ? 4 : 0) | (exitConsumed ? 8 : 0) | (wasAway ? 16 : 0);
    }

    /** Persist only actions committed by the runtime, never pending detector observations. */
    public synchronized int committedFlags() { return committedEvents | (wasAway ? 16 : 0); }

    public synchronized void commit(HomeEvent event) {
        if (event == null) return;
        int bit = eventBit(event);
        if ((flags() & bit) != 0 && !ignored(event)) {
            if (event == HomeEvent.DEPARTURE_STARTED && (committedEvents & 1) == 0) {
                if (!departureCycle.consume(departureGeneration())) return;
                // Old broad-route away evidence must not rearm this newly sent departure.
                departureWasAway = false; departureAwaySince = -1;
            }
            committedEvents |= bit;
        }
    }

    public synchronized void cancel(HomeEvent event) {
        if (event == null || (committedEvents & eventBit(event)) != 0) return;
        switch (event) {
            case DEPARTURE_STARTED -> cancel(DEPARTURE);
            case RETURN_APPROACH -> cancel(RETURN);
            case OUTBOUND_CHECKPOINT -> cancel(CLEANING);
            case JOURNEY_EXIT -> { }
        }
    }

    /** Ignore this occurrence until a new qualified baseline for this detector is observed. */
    public synchronized void cancel(DetectionProgress.Kind kind) {
        if (kind == null) return;
        switch (kind) {
            case DEPARTURE -> {
                if ((committedEvents & 1) != 0) return;
                departureIgnored = true;
                departureConsumed = false;
                homeArmed = false;
                departureReason = DepartureReason.CANCELLED_BASELINE;
                movingSince = departureRearmSince = -1;
            }
            case RETURN -> {
                if ((committedEvents & 2) != 0) return;
                returnIgnored = true;
                returnConsumed = false;
                roadApproach = visitedJunction = false;
                junctionTime = returnRearmSince = -1;
            }
            case CLEANING -> {
                if ((committedEvents & 4) != 0) return;
                cleaningIgnored = true;
                checkpointConsumed = false;
                cleaningRearmSince = -1;
            }
            default -> { return; }
        }
        progress.update(kind, false, false, 0, Math.max(0, lastTime) + 3000, RESET);
    }

    private boolean ignored(HomeEvent event) {
        return switch (event) {
            case DEPARTURE_STARTED -> departureIgnored;
            case RETURN_APPROACH -> returnIgnored;
            case OUTBOUND_CHECKPOINT -> cleaningIgnored;
            case JOURNEY_EXIT -> false;
        };
    }

    private static int eventBit(HomeEvent event) {
        return switch (event) {
            case DEPARTURE_STARTED -> 1;
            case RETURN_APPROACH -> 2;
            case OUTBOUND_CHECKPOINT -> 4;
            case JOURNEY_EXIT -> 8;
        };
    }

    /** Service recovery observes a full new route baseline without undoing historic commits. */
    public synchronized void requireFreshBaseline() {
        clearEvidence();
        outboundDeparture = false;
        returnIgnored = true;
    }

    public synchronized List<HomeEvent> accept(Fix fix) {
        List<HomeEvent> events = new ArrayList<>();
        DepartureReason rejected = fix.mock ? DepartureReason.MOCK
                : fix.ageMs < 0 || fix.ageMs > 3000 ? DepartureReason.STALE
                : !Double.isFinite(fix.accuracy) || fix.accuracy < 0 || fix.accuracy > 15 ? DepartureReason.ACCURACY
                : fix.point == null || !Double.isFinite(fix.point.x) || !Double.isFinite(fix.point.y)
                    || fix.elapsedMs < 0 || !Double.isFinite(fix.speed) || fix.speed < 0 ? DepartureReason.NONFINITE : null;
        if (rejected != null) {
            clearEvidence(); departureReason = rejected;
            progress.clear(GPS_UNRELIABLE); return events;
        }
        if (lastTime >= 0 && fix.elapsedMs <= lastTime) {
            movingSince = -1; departureReason = DepartureReason.OUT_OF_ORDER; return events;
        }
        boolean jump = lastTime >= 0 && previous != null && previous.distance(fix.point) > 40;
        if (jump) clearEvidence();
        boolean departureGap = lastTime >= 0 && fix.elapsedMs - lastTime > 2000;
        if (departureGap) movingSince = departureRearmSince = departureAwaySince = departureParkedSince = -1;
        if (lastTime >= 0 && fix.elapsedMs - lastTime > 5000) clearEvidence();
        lastTime = fix.elapsedMs;
        Point p = fix.point;
        double parkingDistance = p.distance(geometry.parking);
        double gateDistance = p.distance(geometry.gate);
        double junctionDistance = p.distance(geometry.junction);
        if (!jump && !departureCycle.firstFix(parkingDistance <= 45)) departureReason = DepartureReason.STATE_BLOCKED;
        if (parkingDistance > 100) {
            if (departureAwaySince < 0) departureAwaySince = fix.elapsedMs;
            if (fix.elapsedMs - departureAwaySince >= 5000 && departureCycle.markAway()) departureWasAway = true;
            if (awaySince < 0) awaySince = fix.elapsedMs;
            if (fix.elapsedMs - awaySince >= 5000) wasAway = true;
        } else { awaySince = -1; departureAwaySince = -1; }

        boolean parked = parkingDistance <= 45 && fix.speed < 0.5;
        boolean departureParked = parked && fix.speedPresent;
        departureParkedSince = departureParked ? since(departureParkedSince, fix.elapsedMs) : -1;
        if (departureIgnored) {
            departureRearmSince = departureParked ? since(departureRearmSince, fix.elapsedMs) : -1;
            if (departureRearmSince >= 0 && fix.elapsedMs - departureRearmSince >= 5000) {
                departureIgnored = false;
                departureConsumed = false;
            }
        }
        if (cleaningIgnored) {
            cleaningRearmSince = parked ? since(cleaningRearmSince, fix.elapsedMs) : -1;
            if (cleaningRearmSince >= 0 && fix.elapsedMs - cleaningRearmSince >= 5000) {
                cleaningIgnored = false;
                checkpointConsumed = false;
                outboundDeparture = false;
                outboundArmed = false;
                outboundMovingSince = -1;
            }
        }
        if (returnIgnored) {
            returnRearmSince = junctionDistance > 300 ? since(returnRearmSince, fix.elapsedMs) : -1;
            if (returnRearmSince >= 0 && fix.elapsedMs - returnRearmSince >= 5000) {
                returnIgnored = false;
            }
        }

        if (parked) {
            if (stationarySince < 0) stationarySince = fix.elapsedMs;
            if (fix.elapsedMs - stationarySince >= 5000) {
                if (departureWasAway && departureParkedSince >= 0
                        && fix.elapsedMs - departureParkedSince >= 5000 && departureCycle.rearmAfterReturn()) {
                    departureConsumed = false; committedEvents &= ~1;
                    departureWasAway = false; departureIgnored = false;
                    homeArmed = false; movingSince = -1;
                }
                if (wasAway) {
                    visitedJunction = roadApproach = false;
                    broadRoadApproach = broadVisitedJunction = false;
                    broadJunctionTime = -1;
                    junctionTime = -1;
                    returnConsumed = false;
                    checkpointConsumed = false;
                    exitConsumed = false;
                    wasAway = false;
                    committedEvents &= 1;
                    outboundArmed = outboundDeparture = false;
                    outboundMovingSince = exitJunctionTime = -1;
                }
                if (!outboundArmed && !cleaningIgnored) {
                    outboundAnchor = p;
                    outboundAnchorGateDistance = gateDistance;
                    outboundArmed = true;
                }
            }
        } else stationarySince = -1;

        // Once this cycle established a fresh parking baseline, a stop/quality break loses
        // only the timer. Recovery may start fresh movement anywhere in the departure zone.
        if (!homeArmed && !departureIgnored && departureCycle.available() && parkingDistance <= DEPARTURE_RADIUS)
            homeArmed = true;
        boolean movingDeparture = !jump && homeArmed && !departureConsumed && !departureIgnored
                && departureCycle.available() && fix.speedPresent && fix.speed >= 0.7 && parkingDistance <= DEPARTURE_RADIUS;
        if (movingDeparture) {
            if (movingSince < 0) movingSince = fix.elapsedMs;
            if (fix.elapsedMs - movingSince >= 3000) {
                departureConsumed = true; events.add(HomeEvent.DEPARTURE_STARTED);
            }
        } else movingSince = -1;
        departureReason = jump ? DepartureReason.JUMP : departureCycle.blocked() ? DepartureReason.STATE_BLOCKED
                : departureIgnored ? DepartureReason.CANCELLED_BASELINE
                : departureConsumed ? events.contains(HomeEvent.DEPARTURE_STARTED) ? DepartureReason.CONFIRMED : DepartureReason.CONSUMED
                : departureCycle.state().baseline() == DepartureCycle.Baseline.OUTSIDE ? DepartureReason.OUTSIDE_PARKING_BASELINE
                : !homeArmed ? DepartureReason.OUTSIDE_PARKING_BASELINE
                : parkingDistance > DEPARTURE_RADIUS ? DepartureReason.OUTSIDE_DEPARTURE
                : !fix.speedPresent ? DepartureReason.SPEED_MISSING
                : fix.speed < 0.7 ? DepartureReason.STOPPED
                : departureGap ? DepartureReason.GAP : DepartureReason.CANDIDATE;

        boolean movingOutbound = outboundArmed && !outboundDeparture && fix.speed >= 0.7
                && p.distance(outboundAnchor) >= Math.max(12, fix.accuracy * 2)
                && outboundAnchorGateDistance - gateDistance >= 12 && parkingDistance < DEPARTURE_RADIUS;
        if (movingOutbound) {
            if (outboundMovingSince < 0) outboundMovingSince = fix.elapsedMs;
            if (fix.elapsedMs - outboundMovingSince >= 3000) outboundDeparture = true;
        } else outboundMovingSince = -1;

        // Outside approaches: north/south road or its eastern continuation. The western
        // home road cannot arm a return by itself. Expire approach evidence if the car leaves.
        double jx = p.x - geometry.junction.x;
        double jy = p.y - geometry.junction.y;
        if (junctionDistance > 300) broadRoadApproach = false;
        if (junctionDistance <= 300 && ((Math.abs(jx) <= 45 && Math.abs(jy) >= 70)
                || (jx >= 70 && Math.abs(jy) <= 60))) broadRoadApproach = true;
        if (broadRoadApproach && junctionDistance <= 35) {
            broadVisitedJunction = true;
            broadJunctionTime = fix.elapsedMs;
            broadRoadApproach = false;
        }
        if (broadVisitedJunction && (fix.elapsedMs - broadJunctionTime > 300000
                || (junctionDistance > 100 && jx >= -45))) broadVisitedJunction = false;
        if (junctionDistance > 300 || returnIgnored) roadApproach = false;
        if (!returnIgnored && junctionDistance <= 300 && ((Math.abs(jx) <= 45 && Math.abs(jy) >= 70)
                || (jx >= 70 && Math.abs(jy) <= 60))) roadApproach = true;
        if (roadApproach && junctionDistance <= 35) {
            visitedJunction = true;
            junctionTime = fix.elapsedMs;
            roadApproach = false;
        }
        if (visitedJunction && (fix.elapsedMs - junctionTime > 300000
                || (junctionDistance > 100 && jx >= -45))) visitedJunction = false;
        double approachDistance = p.distance(geometry.approach);
        if (visitedJunction && !returnConsumed && !returnIgnored && approachDistance <= 35 && fix.speed >= 0.7
                && previous != null && p.distance(geometry.gate) < previous.distance(geometry.gate)) {
            returnConsumed = true;
            events.add(HomeEvent.RETURN_APPROACH);
        }
        if (outboundDeparture && !checkpointConsumed && !cleaningIgnored && approachDistance <= 35
                && previous != null && gateDistance > previous.distance(geometry.gate) && fix.speed >= 0.7) {
            checkpointConsumed = true;
            events.add(HomeEvent.OUTBOUND_CHECKPOINT);
        }
        // A departure arrives at the junction from the gate road; any onward direction is exit.
        if (outboundDeparture && checkpointConsumed && junctionDistance <= 35) exitJunctionTime = fix.elapsedMs;
        if (outboundDeparture && checkpointConsumed && !exitConsumed && exitJunctionTime >= 0
                && fix.elapsedMs - exitJunctionTime < 120000 && junctionDistance >= 45 && jx >= -30) {
            exitConsumed = true;
            events.add(HomeEvent.JOURNEY_EXIT);
        }
        // Reuse the departure envelope and return route evidence. Include uncertainty at the
        // departure boundary; no position, consumed flag or timer alone may release media here.
        boolean inward = previous == null || gateDistance < previous.distance(geometry.gate);
        boolean towardJunction = previous == null || junctionDistance < previous.distance(geometry.junction);
        boolean possibleReturn = (broadRoadApproach && (fix.speed < 0.7 || towardJunction))
                || (broadVisitedJunction && (junctionDistance <= 35
                    || (jx < -35 && (fix.speed < 0.7 || inward))));
        gateArea = parkingDistance - fix.accuracy < DEPARTURE_RADIUS ? GateArea.DEPARTURE
                : possibleReturn ? GateArea.RETURN : GateArea.NONE;
        // Observe the decisions above without introducing a new event condition.
        DetectionProgress.Reason lost = fix.speed < 0.7 ? STOPPED : CONDITIONS_CHANGED;
        progress.update(DEPARTURE, movingDeparture && !departureConsumed,
                events.contains(HomeEvent.DEPARTURE_STARTED),
                movingSince < 0 ? 0 : (fix.elapsedMs - movingSince) / 3000.0,
                fix.elapsedMs + 3000, lost);
        observeReturn(fix, events.contains(HomeEvent.RETURN_APPROACH), junctionDistance,
                approachDistance, gateDistance, jx, lost);
        progress.update(CLEANING, outboundDeparture && !checkpointConsumed && !cleaningIgnored && previous != null
                        && gateDistance > previous.distance(geometry.gate) && fix.speed >= 0.7,
                events.contains(HomeEvent.OUTBOUND_CHECKPOINT), 0.5, fix.elapsedMs + 3000, lost);
        previous = p;
        return events;
    }

    /** Presentation only: never writes route evidence, consumed flags or action thresholds. */
    private void observeReturn(Fix fix, boolean confirmed, double junctionDistance,
                               double approachDistance, double gateDistance, double jx,
                               DetectionProgress.Reason lost) {
        DetectionProgress old = progress.snapshot().stream().filter(p -> p.kind() == RETURN
                && p.phase() == DetectionProgress.Phase.CANDIDATE).findFirst().orElse(null);
        boolean inward = previous != null && gateDistance < previous.distance(geometry.gate);
        boolean towardJunction = previous != null && junctionDistance < previous.distance(geometry.junction);
        boolean beforeTurn = (roadApproach || (visitedJunction && junctionDistance <= 35))
                && (towardJunction || (visitedJunction && junctionDistance <= 35 && inward));
        boolean afterTurn = visitedJunction && jx < -35 && inward;
        boolean candidate = !returnConsumed && !returnIgnored && fix.speed >= 0.7 && (beforeTurn || afterTurn);
        DetectionProgress.Stage stage = afterTurn ? DetectionProgress.Stage.APPROACHING_GATE
                : DetectionProgress.Stage.APPROACHING_JUNCTION;
        double value = afterTurn
                ? .3 + .65 * clamp((geometry.junction.distance(geometry.approach) - approachDistance)
                    / Math.max(1, geometry.junction.distance(geometry.approach) - 35))
                : .3 * clamp((300 - junctionDistance) / (300 - 35.0));
        // A traffic stop holds observed progress; time and GPS drift cannot fill the bar.
        if (!returnConsumed && !returnIgnored && fix.speed < .7 && old != null && (roadApproach || visitedJunction)) {
            candidate = true; value = old.value(); stage = old.stage();
        }
        progress.update(RETURN, candidate, confirmed, value, fix.elapsedMs + 3000, lost,
                confirmed ? DetectionProgress.Stage.APPROACHING_GATE : stage);
    }

    private static long since(long start, long now) { return start < 0 ? now : start; }

    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }

    public synchronized void clearEvidence() {
        gateArea = GateArea.UNKNOWN;
        departureReason = DepartureReason.NO_FIX;
        progress.clear(RESET);
        homeArmed = outboundArmed = false;
        outboundAnchor = previous = null;
        outboundMovingSince = exitJunctionTime = -1;
        departureRearmSince = cleaningRearmSince = returnRearmSince = departureAwaySince = departureParkedSince = -1;
        stationarySince = movingSince = junctionTime = -1;
        awaySince = -1;
        roadApproach = visitedJunction = broadRoadApproach = broadVisitedJunction = false;
        broadJunctionTime = -1;
        lastTime = -1;
    }
}
