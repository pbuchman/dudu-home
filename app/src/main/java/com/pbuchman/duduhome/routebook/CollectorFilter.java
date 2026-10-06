package com.pbuchman.duduhome.routebook;

import java.time.Instant;
import java.util.UUID;

/** Worker-confined policy, independent of TripController and action state. */
public final class CollectorFilter {
    public record Fix(long wall,long monotonic,double lat,double lon,double accuracy,Double speed,boolean mock) { }
    public record Limits(long sampleMs,long ageMs,long gapMs,double accuracyM) {
        public Limits { if(sampleMs<1000 || ageMs<1 || ageMs>5000 || gapMs<sampleMs || gapMs>30000
            || !Double.isFinite(accuracyM) || accuracyM<=0 || accuracyM>50) throw new IllegalArgumentException(); }
    }
    public static final Limits DEFAULTS=new Limits(5000,5000,30000,50);
    private final Limits limits;
    private String segment=UUID.randomUUID().toString();
    private long highWater, lastSeen=-1, lastSaved=-1;
    private boolean clockBroken;
    private long previousWallClock;
    public CollectorFilter(long persistedWall, Limits limits) { highWater=persistedWall; this.limits=limits; }
    public void breakSegment() { segment=UUID.randomUUID().toString(); }
    public Point accept(Fix f,long nowMono,long nowWall) {
        if(f.monotonic<=lastSeen) return null;
        if(lastSeen>=0 && f.monotonic-lastSeen>limits.gapMs) breakSegment();
        lastSeen=f.monotonic;
        boolean wallRegression=previousWallClock>0 && nowWall<previousWallClock;
        previousWallClock=Math.max(previousWallClock,nowWall);
        if(wallRegression || nowWall<previousWallClock || f.wall<946684800000L || f.wall>nowWall+300000 || f.wall<highWater) {
            if(!clockBroken) breakSegment(); clockBroken=true; return null;
        }
        if(clockBroken) { breakSegment(); lastSeen=f.monotonic; clockBroken=false; }
        highWater=Math.max(highWater,f.wall);
        if(f.mock || f.monotonic<0 || nowMono-f.monotonic<0 || nowMono-f.monotonic>limits.ageMs
            || !Double.isFinite(f.lat) || Math.abs(f.lat)>90 || !Double.isFinite(f.lon) || Math.abs(f.lon)>180
            || !Double.isFinite(f.accuracy) || f.accuracy<=0 || f.accuracy>limits.accuracyM
            || (f.speed!=null && (!Double.isFinite(f.speed)||f.speed<0||f.speed>80))) return null;
        if(lastSaved>=0 && f.monotonic-lastSaved<limits.sampleMs) return null;
        lastSaved=f.monotonic;
        return new Point(UUID.randomUUID().toString(),segment,Point.TIME.format(Instant.ofEpochMilli(f.wall)),f.lat,f.lon,f.accuracy,f.speed);
    }
}
