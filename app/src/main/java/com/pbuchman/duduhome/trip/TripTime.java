package com.pbuchman.duduhome.trip;

import java.util.Locale;

/** Observed GPS continuity only. Receive clocks are captured before the counter queue. */
public final class TripTime {
    public static final long MAX_GAP_MS = 30000;
    private long millis;
    private TripFix anchor;
    private long uptime, lastElapsed;
    private final RadioTimeGate radio;
    private record Pending(TripFix from,TripFix to,long increment) { }
    private final java.util.ArrayDeque<Pending> pending=new java.util.ArrayDeque<>();
    public TripTime(long restored) { this(restored,null); }
    public TripTime(long restored,RadioTimeGate radio) { millis = Math.max(0, restored); this.radio=radio; }
    public long millis() { return millis; }
    public void breakSegment() { anchor = null; flushUnknown(); }
    /** A manual/generation boundary cannot later revise durable totals. Unsettled evidence counts. */
    public void flushUnknown(){while(!pending.isEmpty())add(pending.removeFirst().increment());}
    public void discardPending(){anchor=null;pending.clear();}
    public long boundaryMillis(){long value=millis;for(Pending p:pending)value=value>Long.MAX_VALUE-p.increment()?Long.MAX_VALUE:value+p.increment();return value;}
    public void settle(long now) {
        while(!pending.isEmpty()) {
            Pending p=pending.peekFirst(); RadioTimeGate.Decision d=radio.interval(p.from(),p.to(),now);
            if(d.pending())return;
            add(Math.max(0,p.increment()-Math.min(p.increment(),d.excluded())));pending.removeFirst();
        }
    }
    private void add(long increment){millis=millis>Long.MAX_VALUE-increment?Long.MAX_VALUE:millis+increment;}
    public boolean accept(TripFix fix, long receivedUptime) {
        if (!fix.observed() || receivedUptime < 0 || receivedUptime > fix.received()) { breakSegment(); return false; }
        if (fix.elapsed() <= lastElapsed) return true;
        lastElapsed = fix.elapsed();
        if(radio!=null)radio.observeGps(anchor,fix);
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
                if(radio==null)add(increment);
                else { pending.addLast(new Pending(anchor,fix,increment));if(pending.size()>64)add(pending.removeFirst().increment());settle(fix.received()); }
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
