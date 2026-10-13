package com.pbuchman.duduhome.location;

import com.pbuchman.duduhome.automation.HomeEvent;
import com.pbuchman.duduhome.automation.DetectionProgress;
import com.pbuchman.duduhome.startup.DuduCycle;
import java.util.ArrayList;
import java.util.List;

/** Invented metre-plane positions, in-memory durable store; no Android or external commands. */
public final class DepartureChecks {
    static final HomeDetector.Geometry SHORT = new HomeDetector.Geometry(new HomeDetector.Point(0,0),
            new HomeDetector.Point(20,0),new HomeDetector.Point(100,0),new HomeDetector.Point(300,0));
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static List<HomeEvent> fix(HomeDetector d,long t,double x,double y,double speed) {
        return d.accept(new HomeDetector.Fix(t,new HomeDetector.Point(x,y),15,speed,0,false));
    }
    static final class Memory implements DepartureCycle.Store {
        DepartureCycle.State saved = new DepartureCycle.State(-1,-1,0,DepartureCycle.Baseline.UNKNOWN,false,false);
        boolean fail;
        public DepartureCycle.State read() { return saved; }
        public boolean write(DepartureCycle.State value) { saved=value; return !fail; }
    }
    static DuduCycle.Observation cycle(long n) { return new DuduCycle.Observation(DuduCycle.Kind.AWAKE_COUNTER,n); }
    static void trigger(HomeDetector d,long start,double direction) {
        for(int n=0;n<4;n++) {
            var events=fix(d,start+n*1000,n*direction,0,.7);
            check(events.contains(HomeEvent.DEPARTURE_STARTED)==(n==3),"exact three seconds, any direction");
        }
    }
    public static void main(String[] args) {
        for(double direction:new double[]{1,-1,0}) trigger(new HomeDetector(SHORT,0),0,direction);
        var stopped=new HomeDetector(SHORT,0);fix(stopped,0,0,0,.7);fix(stopped,1000,1,0,.7);
        fix(stopped,2000,1,0,0);trigger(stopped,3000,-1);
        var gap=new HomeDetector(SHORT,0);fix(gap,0,0,0,.7);fix(gap,1000,1,0,.7);trigger(gap,4001,-1);
        var threshold=new HomeDetector(SHORT,0);fix(threshold,0,0,0,.7);fix(threshold,2000,1,0,.7);
        check(fix(threshold,3000,2,0,.7).contains(HomeEvent.DEPARTURE_STARTED),"two-second gap allowed");
        for(int control=0;control<9;control++) {
            var d=new HomeDetector(SHORT,0);fix(d,0,0,0,.7);fix(d,1000,1,0,.7);
            HomeDetector.Fix bad=switch(control) {
                case 0 -> new HomeDetector.Fix(2000,new HomeDetector.Point(2,0),15,.69,0,false);
                case 1 -> new HomeDetector.Fix(2000,new HomeDetector.Point(2,0),16,1,0,false);
                case 2 -> new HomeDetector.Fix(2000,new HomeDetector.Point(2,0),15,1,3001,false);
                case 3 -> new HomeDetector.Fix(2000,new HomeDetector.Point(2,0),15,1,0,true);
                case 4 -> new HomeDetector.Fix(2000,new HomeDetector.Point(2,0),15,0,0,false,false);
                case 5 -> new HomeDetector.Fix(2000,new HomeDetector.Point(Double.NaN,0),15,1,0,false);
                case 6 -> new HomeDetector.Fix(2000,new HomeDetector.Point(60,0),15,1,0,false);
                case 7 -> new HomeDetector.Fix(2000,new HomeDetector.Point(2,0),15,Double.NaN,0,false);
                default -> new HomeDetector.Fix(2000,new HomeDetector.Point(86,0),15,1,0,false);
            };
            check(d.accept(bad).isEmpty(),"rejected control cannot trigger");
            check(fix(d,3000,2,0,.7).isEmpty(),"rejected fix breaks timer");
        }
        for(int recovery=0;recovery<2;recovery++) {
            var d=new HomeDetector(SHORT,0);fix(d,0,0,0,.7);
            fix(d,1000,30,0,.7);fix(d,2000,50,0,.7);
            if(recovery==0)d.accept(new HomeDetector.Fix(3000,new HomeDetector.Point(52,0),30,1,0,false));
            long start=recovery==0?4000:8000;
            for(int n=0;n<4;n++)check(fix(d,start+n*1000,52+n,0,.7).contains(HomeEvent.DEPARTURE_STARTED)==(n==3),
                    "poor/gap recovery restarts timer within85m using this cycle's parking baseline");
        }
        var outside=new HomeDetector(SHORT,0);
        for(int n=0;n<8;n++)check(fix(outside,n*1000,50+n,0,.7).isEmpty(),"first fix outside45m cannot use recovery rule");
        var cancelled=new HomeDetector(SHORT,0);fix(cancelled,0,0,0,.7);fix(cancelled,1000,1,0,.7);
        cancelled.cancel(DetectionProgress.Kind.DEPARTURE);
        for(int n=2;n<12;n++)check(fix(cancelled,n*1000,n,0,.7).isEmpty(),"cancelled motion stays ignored");
        for(int n=12;n<18;n++)check(fix(cancelled,n*1000,0,0,0).isEmpty(),"five-second parked cancellation baseline");
        trigger(cancelled,18000,-1);
        Memory disk=new Memory();DepartureCycle session=new DepartureCycle(disk);
        check(session.ensureBoot(10,1)&&session.state().consumed(),"legacy sent departure remains consumed");
        check(session.observe(cycle(5))==DepartureCycle.Result.BASELINE&&session.state().consumed(),"first observation does not rearm");
        check(new DepartureCycle(disk).ensureBoot(10,0)&&!session.available(),"restart/update/menu cannot rearm");
        check(session.observe(cycle(6))==DepartureCycle.Result.REARMED,"only increasing vendor wake rearms");
        check(session.firstFix(false)&&!session.available(),"outside first fix suppresses startup home departure");
        check(new DepartureCycle(disk).firstFix(true)&&!new DepartureCycle(disk).available(),"return-to-parking and restart cannot replace outside baseline");
        check(session.rearmAfterReturn()&&session.available(),"qualified genuine return may rearm");
        long oldGeneration=session.state().generation();
        check(session.consume(oldGeneration)&&!session.consume(oldGeneration),"durable consumption once before send");
        check(new DepartureCycle(disk).state().consumed(),"consumed survives process restart");
        check(session.observe(cycle(7))==DepartureCycle.Result.REARMED&&session.firstFix(true),"next wake parking arms immediately");
        check(!session.consume(oldGeneration)&&session.available(),"old queued reservation cannot consume a new cycle");
        check(session.ensureBoot(11,1)&&session.firstFix(true)&&session.available(),"real boot rearms independently");
        check(session.observe(cycle(8))==DepartureCycle.Result.BASELINE&&session.available(),"new boot first vendor baseline preserves eligibility");
        var existingSibling=new DepartureCycle(disk);
        disk.fail=true;check(!session.consume(session.state().generation())&&session.blocked(),"uncertain write fails closed");
        disk.fail=false;check(!session.firstFix(true)&&!session.available(),"failed store stays blocked");
        check(existingSibling.blocked() && !existingSibling.available(),"already-existing instance observes store failure latch");
        for(int invalidBoot:new int[]{-1,9}) {
            Memory invalidDisk=new Memory();DepartureCycle valid=new DepartureCycle(invalidDisk);
            check(valid.ensureBoot(10,0)&&valid.firstFix(true)&&valid.available(),"setup valid boot");
            check(!valid.ensureBoot(invalidBoot,0)&&valid.blocked()&&!valid.available(),"invalid/regressed boot latches closed");
            check(new DepartureCycle(invalidDisk).blocked(),"same process store remains closed across instances");
        }
        // New-cycle consumed departure cannot inherit historical broad was-away on restart.
        Memory awayDisk=new Memory();DepartureCycle awayCycle=new DepartureCycle(awayDisk);
        check(awayCycle.ensureBoot(10,16)&&awayCycle.observe(cycle(5))==DepartureCycle.Result.BASELINE,"away migration baseline");
        check(awayCycle.observe(cycle(6))==DepartureCycle.Result.REARMED,"new wake removes historic away evidence");
        HomeDetector afterWake=new HomeDetector(SHORT,16,awayCycle);trigger(afterWake,0,1);
        afterWake.commit(HomeEvent.DEPARTURE_STARTED);
        HomeDetector restarted=new HomeDetector(SHORT,afterWake.committedFlags(),new DepartureCycle(awayDisk));
        for(int n=4;n<14;n++)fix(restarted,n*1000,0,0,0);
        check((restarted.committedFlags()&1)!=0&&awayDisk.saved.consumed(),"restart parked cannot resurrect broad historic away");
        // Legacy was-away must never clear a newly sent departure on the next parked fix.
        var legacyAway=new HomeDetector(SHORT,16);trigger(legacyAway,0,1);legacyAway.commit(HomeEvent.DEPARTURE_STARTED);
        for(int n=4;n<14;n++)fix(legacyAway,n*1000,0,0,0);
        check((legacyAway.committedFlags()&1)!=0,"parked broad-route reset retains newly committed departure");
        for(int n=14;n<24;n++)check(fix(legacyAway,n*1000,n-14,0,.7).isEmpty(),"second departure cannot duplicate without actual away/return");
        System.out.println("PASS: short gate/15 m accuracy, any direction/reverse/start moving, strict three seconds, stop/gap/quality controls, cancellation baseline, migration/restart/boot/wake/outside baseline, stale reservation, fail closed, no parked duplicate");
    }
}
