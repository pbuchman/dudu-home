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
    public record Fix(long elapsedMs, Point point, double accuracy, double speed, long ageMs, boolean mock) { }
    private final Geometry geometry;
    private boolean departureConsumed, returnConsumed, checkpointConsumed, exitConsumed, wasAway;
    private boolean homeArmed, roadApproach, visitedJunction;
    private Point anchor, previous;
    private double anchorGateDistance;
    private long lastTime = -1, stationarySince = -1, movingSince = -1, junctionTime = -1;
    private long awaySince = -1;
    private final DetectionProgress.Tracker progress = new DetectionProgress.Tracker();
    public List<DetectionProgress> progress() { return progress.snapshot(); }

    public HomeDetector(Geometry geometry, int persistedFlags) {
        this.geometry = geometry;
        departureConsumed = (persistedFlags & 1) != 0;
        returnConsumed = (persistedFlags & 2) != 0;
        checkpointConsumed = (persistedFlags & 4) != 0;
        exitConsumed = (persistedFlags & 8) != 0;
        wasAway = (persistedFlags & 16) != 0;
    }

    public int flags() {
        return (departureConsumed ? 1 : 0) | (returnConsumed ? 2 : 0)
                | (checkpointConsumed ? 4 : 0) | (exitConsumed ? 8 : 0) | (wasAway ? 16 : 0);
    }

    public List<HomeEvent> accept(Fix fix) {
        List<HomeEvent> events = new ArrayList<>();
        if (fix.mock || fix.ageMs < 0 || fix.ageMs > 3000 || !Double.isFinite(fix.accuracy)
                || fix.accuracy < 0 || fix.accuracy > 15 || !Double.isFinite(fix.speed)
                || fix.speed < 0 || fix.point == null || !Double.isFinite(fix.point.x)
                || !Double.isFinite(fix.point.y) || fix.elapsedMs < 0) {
            clearEvidence();
            progress.clear(GPS_UNRELIABLE);
            return events;
        }
        if (lastTime >= 0 && fix.elapsedMs <= lastTime) return events;
        if (lastTime >= 0 && (fix.elapsedMs - lastTime > 5000
                || (previous != null && previous.distance(fix.point) > 40))) clearEvidence();
        lastTime = fix.elapsedMs;
        Point p = fix.point;
        double parkingDistance = p.distance(geometry.parking);
        double gateDistance = p.distance(geometry.gate);
        double junctionDistance = p.distance(geometry.junction);
        if (parkingDistance > 100) {
            if (awaySince < 0) awaySince = fix.elapsedMs;
            if (fix.elapsedMs - awaySince >= 5000) wasAway = true;
        } else awaySince = -1;

        if (parkingDistance <= 45 && fix.speed < 0.5) {
            if (stationarySince < 0) stationarySince = fix.elapsedMs;
            if (fix.elapsedMs - stationarySince >= 5000) {
                if (wasAway) {
                    homeArmed = false;
                    visitedJunction = roadApproach = false;
                    junctionTime = -1;
                    departureConsumed = false;
                    returnConsumed = false;
                    checkpointConsumed = false;
                    exitConsumed = false;
                    wasAway = false;
                }
                if (!homeArmed) {
                    anchor = p;
                    anchorGateDistance = gateDistance;
                    homeArmed = true;
                }
            }
        } else stationarySince = -1;

        boolean movingTowardGate = homeArmed && !departureConsumed && fix.speed >= 0.7
                && p.distance(anchor) >= Math.max(12, fix.accuracy * 2)
                && anchorGateDistance - gateDistance >= 12 && parkingDistance < 85;
        if (movingTowardGate) {
            if (movingSince < 0) movingSince = fix.elapsedMs;
            if (fix.elapsedMs - movingSince >= 3000) {
                departureConsumed = true;
                events.add(HomeEvent.DEPARTURE_STARTED);
            }
        } else movingSince = -1;

        // Outside approaches: north/south road or its eastern continuation. The western
        // home road cannot arm a return by itself. Expire approach evidence if the car leaves.
        double jx = p.x - geometry.junction.x;
        double jy = p.y - geometry.junction.y;
        if (junctionDistance > 300) roadApproach = false;
        if (junctionDistance <= 300 && ((Math.abs(jx) <= 45 && Math.abs(jy) >= 70)
                || (jx >= 70 && Math.abs(jy) <= 60))) roadApproach = true;
        if (roadApproach && junctionDistance <= 35) {
            visitedJunction = true;
            junctionTime = fix.elapsedMs;
            roadApproach = false;
        }
        if (visitedJunction && (fix.elapsedMs - junctionTime > 300000
                || (junctionDistance > 100 && jx >= -45))) visitedJunction = false;
        double approachDistance = p.distance(geometry.approach);
        if (visitedJunction && !returnConsumed && approachDistance <= 35 && fix.speed >= 0.7
                && previous != null && p.distance(geometry.gate) < previous.distance(geometry.gate)) {
            returnConsumed = true;
            events.add(HomeEvent.RETURN_APPROACH);
        }
        if (departureConsumed && !checkpointConsumed && approachDistance <= 35
                && previous != null && gateDistance > previous.distance(geometry.gate) && fix.speed >= 0.7) {
            checkpointConsumed = true;
            events.add(HomeEvent.OUTBOUND_CHECKPOINT);
        }
        // A departure arrives at the junction from the gate road; any onward direction is exit.
        if (departureConsumed && checkpointConsumed && junctionDistance <= 35) junctionTime = fix.elapsedMs;
        if (departureConsumed && checkpointConsumed && !exitConsumed && junctionTime >= 0
                && fix.elapsedMs - junctionTime < 120000 && junctionDistance >= 45 && jx >= -30) {
            exitConsumed = true;
            events.add(HomeEvent.JOURNEY_EXIT);
        }
        // Observe the decisions above without introducing a new event condition.
        DetectionProgress.Reason lost = fix.speed < 0.7 ? STOPPED : CONDITIONS_CHANGED;
        progress.update(DEPARTURE, movingTowardGate && !departureConsumed,
                events.contains(HomeEvent.DEPARTURE_STARTED),
                movingSince < 0 ? 0 : (fix.elapsedMs - movingSince) / 3000.0,
                fix.elapsedMs + 3000, lost);
        progress.update(RETURN, !returnConsumed && visitedJunction && previous != null
                        && gateDistance < previous.distance(geometry.gate) && fix.speed >= 0.7,
                events.contains(HomeEvent.RETURN_APPROACH), 0.5, fix.elapsedMs + 3000, lost);
        progress.update(CLEANING, departureConsumed && !checkpointConsumed && previous != null
                        && gateDistance > previous.distance(geometry.gate) && fix.speed >= 0.7,
                events.contains(HomeEvent.OUTBOUND_CHECKPOINT), 0.5, fix.elapsedMs + 3000, lost);
        previous = p;
        return events;
    }

    public void clearEvidence() {
        progress.clear(RESET);
        homeArmed = false;
        anchor = previous = null;
        stationarySince = movingSince = junctionTime = -1;
        awaySince = -1;
        roadApproach = visitedJunction = false;
        lastTime = -1;
    }
}
