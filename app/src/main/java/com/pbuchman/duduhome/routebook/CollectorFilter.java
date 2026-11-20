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
    enum Outcome {
        ACCEPTED, SAMPLED, DUPLICATE, MISSING_DATA, MOCK, FUTURE_MONOTONIC, STALE,
        INVALID_POSITION, ACCURACY, INVALID_SPEED, WALL_TIME, CLOCK_REGRESSION
    }
    private final Limits limits;
    private String segment=UUID.randomUUID().toString();
    private long highWater, lastSeen=-1, lastSaved=-1;
    private boolean clockBroken;
    private long previousWallClock;
    private Outcome outcome;
    public CollectorFilter(long persistedWall, Limits limits) { highWater=persistedWall; this.limits=limits; }
    public void breakSegment() { segment=UUID.randomUUID().toString(); }
    Outcome outcome() { return outcome; }

    public Point accept(Fix f,long nowMono,long nowWall) {
        // An invalid callback must not advance either timestamp watermark.
        if(f.mock) return reject(Outcome.MOCK);
        if(f.monotonic<=0 || f.wall<=0 || nowMono<0 || nowWall<=0 || Double.isNaN(f.accuracy))
            return reject(Outcome.MISSING_DATA);
        if(f.monotonic>nowMono) return reject(Outcome.FUTURE_MONOTONIC);
        if(nowMono-f.monotonic>limits.ageMs) return reject(Outcome.STALE);
        if(!Double.isFinite(f.lat) || Math.abs(f.lat)>90 || !Double.isFinite(f.lon) || Math.abs(f.lon)>180)
            return reject(Outcome.INVALID_POSITION);
        if(!Double.isFinite(f.accuracy) || f.accuracy<=0 || f.accuracy>limits.accuracyM)
            return reject(Outcome.ACCURACY);
        if(f.speed!=null && (!Double.isFinite(f.speed)||f.speed<0||f.speed>80))
            return reject(Outcome.INVALID_SPEED);
        if(f.monotonic<=lastSeen) return reject(Outcome.DUPLICATE);
        if(f.wall<946684800000L || nowWall<f.wall-300000)
            return reject(Outcome.WALL_TIME);

        boolean wallRegression=previousWallClock>0 && nowWall<previousWallClock;
        previousWallClock=Math.max(previousWallClock,nowWall);
        if(wallRegression || f.wall<highWater) {
            if(!clockBroken) breakSegment();
            clockBroken=true;
            return reject(Outcome.CLOCK_REGRESSION);
        }
        if(clockBroken || (lastSeen>=0 && f.monotonic-lastSeen>limits.gapMs)) breakSegment();
        lastSeen=f.monotonic;
        clockBroken=false;
        highWater=f.wall;
        if(lastSaved>=0 && f.monotonic-lastSaved<limits.sampleMs) return reject(Outcome.SAMPLED);
        lastSaved=f.monotonic;
        outcome=Outcome.ACCEPTED;
        return new Point(UUID.randomUUID().toString(),segment,Point.TIME.format(Instant.ofEpochMilli(f.wall)),f.lat,f.lon,f.accuracy,f.speed);
    }

    private Point reject(Outcome reason) {
        outcome=reason;
        return null;
    }
}
