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
        TripTime time = new TripTime(0);
        for(int n=0;n<=660;n++) time.accept(new TripFix(1000+n*1000,1000+n*1000,0,0,200,0,true,0,false,false),1000+n*1000);
        check(time.millis()==660000,"eleven minute stationary poor-accuracy observation counts time");
        time.accept(new TripFix(691000,691000,0,0,3,0,false,0,false,false),691000);
        check(time.millis()==690000,"exactly thirty second gap counts");
        time.accept(new TripFix(721001,721001,0,0,3,0,false,0,false,false),721001);
        check(time.millis()==690000,"over thirty second gap establishes anchor only");
        time.accept(new TripFix(721001,721001,0,0,3,0,false,0,false,false),721001);
        check(time.millis()==690000,"duplicate does not count");
        time.accept(new TripFix(722001,722001,0,0,3,0,false,0,false,false),721101);
        check(time.millis()==690000,"sleep breaks anchor rather than counting suspended time");
        time.accept(new TripFix(723001,723001,0,0,3,0,false,0,false,false),722101);
        check(time.millis()==691000,"awake observations resume after sleep");
        time.breakSegment(); time.accept(new TripFix(724001,724001,0,0,3,0,false,0,false,false),723101);
        check(time.millis()==691000,"GPS loss wake pause reset break continuity");
        time.accept(new TripFix(725001,731001,0,0,3,0,false,0,false,false),730101);
        check(time.millis()==691000,"stale sample breaks time anchor");
        time.accept(new TripFix(732001,732001,0,0,3,0,false,0,false,true),731101);
        check(time.millis()==691000,"mock sample cannot count time");
        TripTime restarted = new TripTime(time.millis()); restarted.accept(fix(800000,0,0),799000);
        check(restarted.millis()==691000,"restart retains total without bridging");
        check(TripTime.format(100L*3600000+61000).equals("100:01:01"),"duration exceeds 24 hours");
        check(TripTime.format(0).equals("00:00:00"),"zero formatting");
        TripTime jitter = new TripTime(0);
        jitter.accept(fix(1000,0,0),1000);
        jitter.accept(new TripFix(2000,2200,0,0,3,0,false,0,false,false),2200);
        jitter.accept(new TripFix(3000,3000,0,0,3,0,false,0,false,false),3000);
        check(jitter.millis()==2000,"receive jitter does not systematically discard observed time");
        jitter.accept(new TripFix(3500,3000,0,0,3,0,false,0,false,false),3000);
        jitter.accept(new TripFix(4000,4000,0,0,3,0,false,0,false,false),4000);
        check(jitter.millis()==2000,"invalid receive clock breaks anchor");
        TripTime invalidClock = new TripTime(0);
        invalidClock.accept(new TripFix(1000,2000,0,0,3,0,false,0,false,false),2000);
        invalidClock.accept(new TripFix(1500,2000,0,0,3,0,false,0,false,false),2000);
        invalidClock.accept(new TripFix(2500,2500,0,0,3,0,false,0,false,false),2500);
        check(invalidClock.millis()==0,"nonincreasing receive/uptime discards anchor");
        TripTime saturated = new TripTime(Long.MAX_VALUE-100);
        saturated.accept(fix(1000,0,0),1000);saturated.accept(fix(2000,0,0),2000);
        check(saturated.millis()==Long.MAX_VALUE,"persisted long duration cannot overflow");
        System.out.println("PASS: trip distance and session time, stationary poor accuracy, sleep/clocks/jitter, gaps, invalid GPS and 100h formatting");
    }
}
