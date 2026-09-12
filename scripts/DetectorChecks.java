package com.pbuchman.duduhome.location;
import com.pbuchman.duduhome.automation.HomeEvent;

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
        int saved = departure.detector.flags();
        departure.detector = new HomeDetector(GEOMETRY, saved);
        departure.move(point(0, -60));
        departure.expect(HomeEvent.DEPARTURE_STARTED);
        departure.move(point(100, 100)); departure.move(point(400, 100)); departure.move(point(400, 180));
        departure.expect(HomeEvent.DEPARTURE_STARTED, HomeEvent.OUTBOUND_CHECKPOINT, HomeEvent.JOURNEY_EXIT);

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
        System.out.println("PASS: stationary, nearby parking, departure, restart deduplication, return, controls, stale/poor/mock fixes");
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
