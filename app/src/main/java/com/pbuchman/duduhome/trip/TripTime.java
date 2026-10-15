package com.pbuchman.duduhome.trip;

import java.util.Locale;

/** Observed GPS continuity only. Receive clocks are captured before the counter queue. */
public final class TripTime {
    public static final long MAX_GAP_MS = 30000;
    private long millis;
    private TripFix anchor;
    private long uptime, lastElapsed;
    public TripTime(long restored) { millis = Math.max(0, restored); }
    public long millis() { return millis; }
    public void breakSegment() { anchor = null; }
    public boolean accept(TripFix fix, long receivedUptime) {
        if (!fix.observed() || receivedUptime < 0 || receivedUptime > fix.received()) { breakSegment(); return false; }
        if (fix.elapsed() <= lastElapsed) return true;
        lastElapsed = fix.elapsed();
        boolean continuous = false;
        if (anchor != null) {
            if (fix.received() <= anchor.received() || receivedUptime <= uptime) { breakSegment(); return false; }
            long measured = fix.elapsed() - anchor.elapsed();
            long received = fix.received() - anchor.received();
            long awake = receivedUptime - uptime;
            // elapsedRealtime includes deep sleep; uptime does not. A sleep breaks continuity.
            if (measured <= MAX_GAP_MS && received <= MAX_GAP_MS && received - awake <= 250)
                continuous = true;
            if (continuous) {
                long increment = Math.min(received, awake);
                millis = millis > Long.MAX_VALUE - increment ? Long.MAX_VALUE : millis + increment;
            }
        }
        anchor = fix; uptime = receivedUptime;
        return continuous;
    }
    public static String format(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }
}
