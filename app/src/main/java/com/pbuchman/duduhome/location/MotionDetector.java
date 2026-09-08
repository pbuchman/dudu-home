package com.pbuchman.duduhome.location;

/** Pure sustained-motion evidence. No home coordinates, Android APIs or side effects. */
public final class MotionDetector {
    public record Fix(long time, double x, double y, double accuracy, double speed,
                      boolean hasSpeed, long age, boolean mock) { }
    private Fix anchor, previous;

    public boolean accept(Fix f) {
        if (f == null || f.mock || f.time < 0 || f.age < 0 || f.age > 3000 || !f.hasSpeed
                || !Double.isFinite(f.speed) || f.speed < 1 || !Double.isFinite(f.accuracy)
                || f.accuracy < 0 || f.accuracy > 15 || !Double.isFinite(f.x) || !Double.isFinite(f.y)) {
            clear(); return false;
        }
        if (previous != null && f.time <= previous.time) return false;
        if (previous != null && (f.time - previous.time > 3000
                || Math.hypot(f.x - previous.x, f.y - previous.y)
                > Math.max(f.speed, previous.speed) * (f.time - previous.time) / 1000.0
                    + f.accuracy + previous.accuracy + 15)) clear();
        if (anchor == null) anchor = f;
        previous = f;
        return f.time - anchor.time >= 10000 && Math.hypot(f.x - anchor.x, f.y - anchor.y) >= 15;
    }

    public boolean fresh(long now) { return previous != null && now >= previous.time && now - previous.time <= 3000; }
    public void clear() { anchor = previous = null; }
}
