package com.pbuchman.duduhome.trip;

/** Pure, independent accumulator. No relationship to home trigger thresholds or reservations. */
public final class TripDistance {
    public static final long MAX_AGE_MS = 5000, MAX_GAP_MS = 10000;
    public static final double MAX_ACCURACY_M = 25, MAX_SPEED_MPS = 80;
    private TripFix anchor;
    private long lastTime;
    private double meters;
    public TripDistance(double restored) { meters = Double.isFinite(restored) && restored >= 0 ? restored : 0; }
    public double meters() { return meters; }
    public void breakSegment() { anchor = null; lastTime = 0; }
    public void accept(TripFix fix) {
        if (!fix.valid()) { breakSegment(); return; }
        if (fix.elapsed() <= lastTime) return;
        if (lastTime != 0 && fix.elapsed() - lastTime > MAX_GAP_MS) anchor = null;
        lastTime = fix.elapsed();
        if (anchor == null) { anchor = fix; return; }
        double distance = anchor.distance(fix);
        double seconds = (fix.elapsed() - anchor.elapsed()) / 1000.0;
        if (seconds <= 0 || distance / seconds > MAX_SPEED_MPS
                || (fix.hasSpeed() && (!Double.isFinite(fix.speed()) || fix.speed() > MAX_SPEED_MPS))) {
            anchor = null; return;
        }
        // GPS speed suppresses stationary jitter. Without speed require movement above uncertainty.
        if (fix.hasSpeed() && fix.speed() < 0.7) { anchor = fix; return; }
        double threshold = fix.hasSpeed() ? 2 : Math.max(5, (anchor.accuracy() + fix.accuracy()) / 2);
        if (distance < threshold) return;
        meters += distance; anchor = fix;
    }
}
