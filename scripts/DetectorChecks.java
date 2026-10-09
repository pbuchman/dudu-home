package com.pbuchman.duduhome.location;
import com.pbuchman.duduhome.automation.HomeEvent;
import com.pbuchman.duduhome.automation.DetectionProgress;

import java.util.ArrayList;
import java.util.List;

/** Synthetic local-metre geometry; these are not geographic coordinates. */
public final class DetectorChecks {
    private static final HomeDetector.Geometry GEOMETRY = new HomeDetector.Geometry(
            point(0, 0), point(0, -70), point(100, 100), point(400, 100));
    private HomeDetector detector = new HomeDetector(GEOMETRY, 0);
    private final List<HomeEvent> events = new ArrayList<>();
    private long time;
    private HomeDetector.Point position = point(0, 0);

    public static void main(String[] args) {
        DetectorChecks stationary = new DetectorChecks();
        for (int n = 0; n < 200; n++) stationary.fix(point(n % 3, n % 2), 0, 1, 0, false);
        stationary.expect();

        DetectorChecks departure = new DetectorChecks();
        departure.park(); departure.move(point(0, -40));
        departure.expect(HomeEvent.DEPARTURE_STARTED);
        departure.detector.commit(HomeEvent.DEPARTURE_STARTED);
        int saved = departure.detector.committedFlags();
        departure.detector = new HomeDetector(GEOMETRY, saved);
        departure.move(point(0, -60));
        departure.expect(HomeEvent.DEPARTURE_STARTED);
        departure.move(point(100, 100)); departure.move(point(400, 100)); departure.move(point(400, 180));
        departure.expect(HomeEvent.DEPARTURE_STARTED); // restart has no fresh outward-drive baseline
        departure.move(point(0, 0)); departure.park(); departure.move(point(0, -40));
        departure.move(point(100, 100)); departure.move(point(400, 100)); departure.move(point(400, 180));
        departure.expect(HomeEvent.DEPARTURE_STARTED, HomeEvent.DEPARTURE_STARTED,
                HomeEvent.OUTBOUND_CHECKPOINT, HomeEvent.JOURNEY_EXIT);

        DetectorChecks alternative = new DetectorChecks();
        alternative.position = point(20, 15);
        alternative.park(); alternative.move(point(20, -30));
        alternative.expect(HomeEvent.DEPARTURE_STARTED);

        DetectorChecks maneuver = new DetectorChecks();
        maneuver.park(); maneuver.move(point(0, 20)); maneuver.move(point(0, 0)); maneuver.park();
        maneuver.expect();

        DetectorChecks returning = new DetectorChecks();
        returning.position = point(400, 220);
        returning.move(point(400, 100)); returning.move(point(100, 100));
        returning.expect(HomeEvent.RETURN_APPROACH);
        returning.move(point(120, 100)); returning.move(point(100, 100));
        returning.expect(HomeEvent.RETURN_APPROACH);

        DetectorChecks passing = new DetectorChecks();
        passing.position = point(400, 220);
        passing.move(point(400, -20)); passing.expect();
        DetectorChecks east = new DetectorChecks();
        east.position = point(520, 100);
        east.move(point(400, 100)); east.move(point(100, 100)); east.expect(HomeEvent.RETURN_APPROACH);
        east.move(point(120, 100)); east.move(point(100, 100)); east.expect(HomeEvent.RETURN_APPROACH);
        DetectorChecks eastThrough = new DetectorChecks();
        eastThrough.position = point(520, 100);
        eastThrough.move(point(400, 100)); eastThrough.move(point(400, 220)); eastThrough.expect();
        DetectorChecks fromHomeRoad = new DetectorChecks();
        fromHomeRoad.position = point(100, 100);
        fromHomeRoad.move(point(400, 100)); fromHomeRoad.move(point(100, 100)); fromHomeRoad.expect();

        DetectorChecks startupMoving = new DetectorChecks();
        startupMoving.move(point(0, -40)); startupMoving.expect();
        DetectorChecks poor = new DetectorChecks();
        poor.park();
        for (int n = 0; n < 20; n++) poor.fix(point(0, -n*2), 2, 30, 0, false);
        poor.expect();
        DetectorChecks stale = new DetectorChecks(); stale.park();
        for (int n = 0; n < 20; n++) stale.fix(point(0, -n*2), 2, 1, 5000, false);
        stale.expect();
        DetectorChecks mock = new DetectorChecks(); mock.park();
        for (int n = 0; n < 20; n++) mock.fix(point(0, -n*2), 2, 1, 0, true);
        mock.expect();
        cancellationChecks();
        System.out.println("PASS: stationary, nearby parking, departure, restart deduplication, return, controls, stale/poor/mock fixes, cancellation isolation, committed flags, qualified rearm");
    }

    private static void cancellationChecks() {
        DetectorChecks pending = new DetectorChecks();
        pending.park(); pending.move(point(0, -40));
        check((pending.detector.flags() & 1) != 0, "departure pending");
        check((pending.detector.committedFlags() & 15) == 0, "pending detection is not durable");
        HomeDetector pendingRestart = new HomeDetector(GEOMETRY, pending.detector.committedFlags());
        check(pendingRestart.flags() == 0, "pending departure is not restored");
        check(pendingRestart.accept(new HomeDetector.Fix(1000, point(0, -40), 1, 2, 0, false)).isEmpty(),
                "restart driving is not departure");
        pending.detector.cancel(HomeEvent.DEPARTURE_STARTED);
        pending.detector.commit(HomeEvent.DEPARTURE_STARTED);
        check((pending.detector.committedFlags() & 1) == 0, "cancelled departure cannot commit");
        pending.move(point(100, 100));
        pending.expect(HomeEvent.DEPARTURE_STARTED, HomeEvent.OUTBOUND_CHECKPOINT);
        pending.detector.commit(HomeEvent.OUTBOUND_CHECKPOINT);
        check((pending.detector.committedFlags() & 15) == 4, "only actual cleaning commit durable");
        pending.detector.cancel(HomeEvent.OUTBOUND_CHECKPOINT);
        check((pending.detector.committedFlags() & 4) != 0, "commit is irrevocable");

        DetectorChecks candidate = new DetectorChecks();
        candidate.park(); candidate.move(point(0, -14)); candidate.expect();
        candidate.detector.cancel(DetectionProgress.Kind.DEPARTURE);
        candidate.move(point(0, -40)); candidate.expect();
        candidate.move(point(100, 100)); candidate.expect(HomeEvent.OUTBOUND_CHECKPOINT);

        DetectorChecks departure = new DetectorChecks();
        departure.park(); departure.move(point(0, -14));
        departure.detector.cancel(DetectionProgress.Kind.DEPARTURE);
        departure.detector.clearEvidence();
        departure.move(point(0, -40)); departure.move(point(0, 0)); departure.expect();
        for (int i = 0; i < 5; i++) departure.fix(point(0, 0), 0, 1, 0, false);
        departure.move(point(0, -40)); departure.expect(); // under five seconds stays ignored
        departure.move(point(0, 0)); departure.park(); departure.move(point(0, -40));
        departure.expect(HomeEvent.DEPARTURE_STARTED);

        DetectorChecks cleaning = new DetectorChecks();
        cleaning.park(); cleaning.move(point(0, -40));
        cleaning.detector.cancel(DetectionProgress.Kind.CLEANING);
        cleaning.move(point(100, 100)); cleaning.expect(HomeEvent.DEPARTURE_STARTED);
        cleaning.move(point(0, 0)); cleaning.park(); cleaning.move(point(0, -40));
        cleaning.move(point(100, 100));
        cleaning.expect(HomeEvent.DEPARTURE_STARTED, HomeEvent.DEPARTURE_STARTED, HomeEvent.OUTBOUND_CHECKPOINT);

        DetectorChecks returning = new DetectorChecks();
        returning.position = point(400, 220);
        returning.move(point(400, 180));
        returning.detector.cancel(DetectionProgress.Kind.RETURN);
        returning.fix(point(400, 178), 2, 1, 0, false);
        check(returning.detector.gateArea() == HomeDetector.GateArea.RETURN,
                "cancelled return retains broad gate area");
        returning.detector.clearEvidence();
        returning.move(point(400, 100)); returning.move(point(100, 100)); returning.expect();
        returning.move(point(400, 100));
        for (int y = 140; y <= 400; y += 20) returning.fix(point(400, y), 2, 1, 0, false);
        returning.fix(point(400, 410), 2, 1, 0, false);
        returning.fix(point(400, 390), 2, 1, 0, false);
        returning.move(point(400, 100)); returning.move(point(100, 100)); returning.expect();
        returning.move(point(400, 450));
        for (int i = 0; i < 6; i++) returning.fix(point(400, 450), 0, 1, 0, false);
        returning.move(point(400, 100)); returning.move(point(100, 100));
        returning.expect(HomeEvent.RETURN_APPROACH);
        returning.detector.commit(HomeEvent.RETURN_APPROACH);
        check((returning.detector.committedFlags() & 2) != 0, "return commitment durable");
        DetectorChecks freshReturn = new DetectorChecks();
        freshReturn.detector.requireFreshBaseline();
        freshReturn.position = point(400, 220);
        freshReturn.move(point(400, 100)); freshReturn.move(point(100, 100)); freshReturn.expect();
        freshReturn.move(point(400, 450)); freshReturn.park();
        freshReturn.move(point(400, 100)); freshReturn.move(point(100, 100));
        freshReturn.expect(HomeEvent.RETURN_APPROACH);
        HomeDetector restart = new HomeDetector(GEOMETRY, returning.detector.committedFlags());
        check(restart.committedFlags() == returning.detector.committedFlags(), "historic commitments preserved");
        DetectorChecks committedReturn = new DetectorChecks();
        committedReturn.detector = restart;
        restart.requireFreshBaseline();
        committedReturn.position = point(400, 450);
        committedReturn.park(); committedReturn.move(point(400, 100)); committedReturn.move(point(100, 100));
        committedReturn.expect();
        check((restart.flags() & 2) != 0, "fresh baseline does not unconsume historic return");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private void park() { for (int i = 0; i < 10; i++) fix(position, 0, 1, 0, false); }
    private void move(HomeDetector.Point end) {
        HomeDetector.Point start = position;
        int steps = (int) Math.ceil(start.distance(end) / 2);
        for (int i = 1; i <= steps; i++) fix(point(start.x()+(end.x()-start.x())*i/steps,
                start.y()+(end.y()-start.y())*i/steps), 2, 1, 0, false);
    }
    private void fix(HomeDetector.Point p, double speed, double accuracy, long age, boolean mock) {
        time += 1000;
        events.addAll(detector.accept(new HomeDetector.Fix(time, p, accuracy, speed, age, mock)));
        position = p;
    }
    private void expect(HomeEvent... expected) {
        if (!events.equals(List.of(expected))) throw new AssertionError("Expected " + List.of(expected) + ", got " + events);
    }
    private static HomeDetector.Point point(double x, double y) { return new HomeDetector.Point(x, y); }
}
