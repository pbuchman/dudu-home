package com.pbuchman.duduhome.location;

import com.pbuchman.duduhome.automation.DetectionProgress;
import static com.pbuchman.duduhome.automation.DetectionProgress.Kind.YANOSIK;
import static com.pbuchman.duduhome.automation.DetectionProgress.Reason.*;

/** Pure sustained-motion evidence. No home coordinates, Android APIs or side effects. */
public final class MotionDetector {
    public record Fix(long time, double x, double y, double accuracy, double speed,
                      boolean hasSpeed, long age, boolean mock) { }
    private Fix anchor, previous;
    private final DetectionProgress.Tracker progress = new DetectionProgress.Tracker();
    public java.util.List<DetectionProgress> progress() { return progress.snapshot(); }

    public boolean accept(Fix f) {
        if (f == null || f.mock || f.time < 0 || f.age < 0 || f.age > 3000 || !f.hasSpeed
                || !Double.isFinite(f.speed) || f.speed < 1 || !Double.isFinite(f.accuracy)
                || f.accuracy < 0 || f.accuracy > 15 || !Double.isFinite(f.x) || !Double.isFinite(f.y)) {
            clear();
            progress.clear(f != null && f.hasSpeed && f.speed >= 0 && f.speed < 1 ? STOPPED : GPS_UNRELIABLE);
            return false;
        }
        if (previous != null && f.time <= previous.time) return false;
        if (previous != null && (f.time - previous.time > 3000
                || Math.hypot(f.x - previous.x, f.y - previous.y)
                > Math.max(f.speed, previous.speed) * (f.time - previous.time) / 1000.0
                    + f.accuracy + previous.accuracy + 15)) clear();
        if (anchor == null) anchor = f;
        previous = f;
        boolean confirmed = f.time - anchor.time >= 10000 && Math.hypot(f.x - anchor.x, f.y - anchor.y) >= 15;
        progress.update(YANOSIK, !confirmed, confirmed, Math.min((f.time - anchor.time) / 10000.0,
                Math.hypot(f.x - anchor.x, f.y - anchor.y) / 15.0), f.time + 3000, NONE);
        return confirmed;
    }

    public boolean fresh(long now) { return previous != null && now >= previous.time && now - previous.time <= 3000; }
    public void clear() { anchor = previous = null; progress.clear(RESET); }
}
