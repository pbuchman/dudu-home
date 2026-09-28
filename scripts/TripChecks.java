package com.pbuchman.duduhome.trip;

public final class TripChecks {
    static TripFix fix(long time, double meters, double speed) {
        return new TripFix(time,time,0,meters/111195,3,speed,true,90,true,false);
    }
    static void check(boolean value,String reason) { if(!value)throw new AssertionError(reason); }
    public static void main(String[] args) {
        TripDistance trip=new TripDistance(0);
        for(int i=0;i<=100;i++)trip.accept(fix(1000+i*1000,i*10,10));
        check(Math.abs(trip.meters()-1000)<1,"known one-kilometer route");
        double before=trip.meters();
        trip.breakSegment();trip.accept(fix(200000,10000,10));
        check(trip.meters()==before,"pause/gap must not bridge");
        trip.accept(fix(201000,10010,10));check(Math.abs(trip.meters()-before-10)<.1,"resume starts new segment");
        before=trip.meters();
        for(int i=0;i<100;i++)trip.accept(fix(202000+i*1000,10010+(i%3)*2,0));
        check(trip.meters()==before,"stationary jitter cannot accumulate");
        trip.accept(fix(400000,20000,10));check(trip.meters()==before,"long gap cannot bridge");
        trip.accept(fix(401000,100000,10));check(trip.meters()==before,"GPS teleport rejected");
        trip.accept(fix(402000,20010,10));check(trip.meters()==before,"teleport recovery new baseline");
        trip.accept(fix(401000,30000,10));check(trip.meters()==before,"out of order rejected");
        TripFix stale=new TripFix(403000,409000,0,0,3,10,true,90,true,false);
        trip.accept(stale);check(trip.meters()==before,"stale fix rejected");
        check(!new TripFix(1,1,Double.NaN,0,3,0,false,0,false,false).valid(),"NaN coordinate");
        check(!new TripFix(1,1,0,0,26,0,false,0,false,false).valid(),"poor accuracy");
        check(!new TripFix(1,1,0,0,3,0,false,0,false,true).valid(),"mock rejected");
        TripDistance restored=new TripDistance(1234);restored.accept(fix(500000,100000,20));
        check(restored.meters()==1234,"restart cannot connect previous route");
        TripDistance curved=new TripDistance(0);
        curved.accept(new TripFix(1000,1000,0,0,3,10,true,0,true,false));
        curved.accept(new TripFix(2000,2000,10.0/111195,0,3,10,true,0,true,false));
        curved.accept(new TripFix(3000,3000,10.0/111195,10.0/111195,3,10,true,90,true,false));
        check(Math.abs(curved.meters()-20)<.1,"turn retained rather than latest-only diagonal");
        System.out.println("PASS: trip distance, stationary drift, turns, restart, gaps, invalid and stale GPS");
    }
}
