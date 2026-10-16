package com.pbuchman.duduhome.trip;

import java.util.*;

/** Bounded monotonic power coverage. Never infers OFF from ACC, screen, GPS gaps or history. */
public final class RadioTimeGate {
    public static final long EXPIRY_MS=45000,CONFIRM_MS=3000;
    public record Decision(boolean pending,long excluded) { }
    private static final class Span {long start,end;boolean closed;Span(long start){this.start=start;end=start+EXPIRY_MS;}}
    private final List<Span> off=new ArrayList<>();
    private String epoch="";
    private long last=-1;
    private long contradictedAt=-1;
    private long boundaryAt=-1;
    private RadioProfile.State state=RadioProfile.State.UNKNOWN;
    private Span active;
    public void observe(String epoch,long now,RadioProfile.State next){
        if(now<0||now<=contradictedAt||now<=boundaryAt)return;
        if(!this.epoch.equals(epoch)){close(now);this.epoch=epoch;last=-1;state=RadioProfile.State.UNKNOWN;}
        if(now<=last)return;
        if(last>=0&&now-last>EXPIRY_MS){close(last+EXPIRY_MS);state=RadioProfile.State.UNKNOWN;}
        if(next!=state){close(now);if(next==RadioProfile.State.OFF){active=new Span(now);off.add(active);}state=next;}
        if(active!=null)active.end=now+EXPIRY_MS;
        last=now;
        // TripTime settles every second; retaining two minutes is more than its bounded GPS gap.
        off.removeIf(s->s.closed&&s.end<now-120000);
        while(off.size()>256)off.remove(0); // Dropping evidence can only reduce subtraction.
    }
    private void close(long now){if(active!=null){active.end=Math.min(active.end,now);active.closed=true;active=null;}}
    public void lost(String epoch,long now){observe(epoch,now,RadioProfile.State.UNKNOWN);}
    public RadioProfile.State state(long now){return last>=0&&now>=last&&now-last<=EXPIRY_MS?state:RadioProfile.State.UNKNOWN;}
    public void boundary(long now){
        boundaryAt=Math.max(boundaryAt,now);
        if(active!=null&&now-active.start<CONFIRM_MS){close(now);state=RadioProfile.State.UNKNOWN;}
    }
    public void observeGps(TripFix from,TripFix to){
        boolean moving=to.valid()&&to.hasSpeed()&&to.speed()>.7;
        if(from!=null&&from.valid()&&to.valid()&&to.elapsed()>from.elapsed()&&to.elapsed()-from.elapsed()<=15000
                &&from.distance(to)>15)moving=true;
        if(moving){close(to.received());state=RadioProfile.State.UNKNOWN;contradictedAt=Math.max(contradictedAt,to.received());}
    }
    public Decision interval(TripFix from,TripFix to,long now){
        if(!stationary(from,to))return new Decision(false,0);
        long excluded=0;
        for(Span span:off){
            long start=Math.max(from.received(),span.start),end=Math.min(to.received(),span.end);
            if(end<=start)continue;
            if(span.closed&&span.end-span.start<CONFIRM_MS)continue;
            if(now<span.start+CONFIRM_MS)return new Decision(true,0);
            excluded+=end-start;
        }
        return new Decision(false,excluded);
    }
    public static boolean stationary(TripFix a,TripFix b){
        return a.observed()&&b.observed()&&b.elapsed()>a.elapsed()&&b.elapsed()-a.elapsed()<=15000
                &&b.received()>a.received()&&b.received()-a.received()<=15000
                &&a.accuracy()<=25&&b.accuracy()<=25&&a.hasSpeed()&&b.hasSpeed()
                &&a.speed()<=.7&&b.speed()<=.7&&a.distance(b)<=15;
    }
}
