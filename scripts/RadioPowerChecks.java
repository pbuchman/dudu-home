package com.pbuchman.duduhome.trip;

public final class RadioPowerChecks {
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private static TripFix fix(long t,double speed,double meters,double accuracy){return new TripFix(t,t,0,meters/111195,accuracy,speed,true,0,false,false);}
    private static TripFix fix(long t){return fix(t,0,0,5);}
    private static String profile(){return "{\"version\":1,\"profile_id\":\"synthetic-audit-v1\",\"firmware_id\":\"synthetic-build\",\"validation_id\":\"synthetic-physical-record\",\"physically_verified\":true,\"mappings\":[{\"acc_state\":0,\"module1_state\":0,\"radio_state\":\"OFF\"},{\"acc_state\":0,\"module1_state\":1,\"radio_state\":\"ON\"}]}";}
    public static void main(String[] args){
        RadioProfile diagnostic=RadioProfile.diagnostic("synthetic-build");
        check(!diagnostic.validated()&&diagnostic.interpret(0,0)==RadioProfile.State.UNKNOWN,"candidate defaults UNKNOWN");
        RadioProfile audited=RadioProfile.parse(profile(),"synthetic-build");
        check(audited.interpret(0,1)==RadioProfile.State.ON,"waiting with ACC off and radio on counts");
        check(audited.interpret(1,0)==RadioProfile.State.UNKNOWN&&audited.interpret(null,0)==RadioProfile.State.UNKNOWN,"unmapped or missing raw pair unknown");
        try{RadioProfile.parse(profile(),"another-build");throw new AssertionError("firmware gate");}catch(IllegalArgumentException expected){}
        try{RadioProfile.parse(profile().replace("true","false"),"synthetic-build");throw new AssertionError("audit gate");}catch(IllegalArgumentException expected){}
        RadioTimeGate gate=new RadioTimeGate();TripTime time=new TripTime(0,gate);
        gate.observe("epoch",1000,RadioProfile.State.ON);time.accept(fix(1000),1000);time.accept(fix(2000),2000);
        gate.observe("epoch",2500,RadioProfile.State.OFF);time.accept(fix(3000),3000);time.accept(fix(4000),4000);
        check(time.millis()==1000,"pending OFF never commits totals needing subtraction");
        gate.observe("epoch",5000,RadioProfile.State.ON);time.settle(5000);
        check(time.millis()==3000,"false OFF shorter than three seconds counts");
        gate.observe("epoch",6000,RadioProfile.State.OFF);time.accept(fix(7000),7000);time.accept(fix(8000),8000);time.settle(9000);
        check(time.millis()==5000,"confirmed OFF clips from first OFF only, no retrospective initial snapshot");
        time.accept(fix(10000,2,20,5),10000);check(time.millis()==7000,"motion contradicts OFF");
        time.accept(fix(11000,0,20,30),11000);check(time.millis()==8000,"poor accuracy cannot establish stationary exclusion");
        time.accept(fix(12000),12000);check(time.millis()==9000,"two qualified stationary fixes required");
        time.accept(fix(13000),13000);check(time.millis()==10000,"stationary after movement cannot reuse contradicted OFF lease");
        gate.observe("epoch",13100,RadioProfile.State.OFF);time.accept(fix(14000),14000);time.settle(16100);
        check(time.millis()==10100,"new trusted OFF observation must debounce again after movement");
        gate.lost("epoch",16500);time.accept(fix(17000),17000);check(time.millis()==10600,"binder loss clips OFF and immediately becomes unknown");
        gate.observe("next",18000,RadioProfile.State.UNKNOWN);time.accept(fix(18000),18000);check(time.millis()==11600,"new epoch cannot bridge old OFF");
        RadioTimeGate expiry=new RadioTimeGate();expiry.observe("e",1000,RadioProfile.State.OFF);
        check(expiry.state(46001)==RadioProfile.State.UNKNOWN,"lost ON cannot cause unbounded exclusion");
        check(expiry.interval(fix(45000),fix(47000),50000).excluded()==1000,"only fresh coverage before expiry excluded");
        check(expiry.interval(fix(1000),fix(17000),20000).excluded()==0,"stationary pair over fifteen seconds never excludes");
        check(expiry.interval(fix(1000),fix(2000,0,16,5),5000).excluded()==0,"displacement contradicts OFF");
        RadioTimeGate paused=new RadioTimeGate();TripTime old=new TripTime(10,paused);paused.observe("e",1000,RadioProfile.State.OFF);old.accept(fix(1000),1000);old.accept(fix(2000),2000);old.flushUnknown();
        check(old.millis()==1010,"pause commits unsettled candidate conservatively");
        TripTime reset=new TripTime(0,paused);reset.accept(fix(3000),3000);reset.accept(fix(4000),4000);reset.settle(5000);
        check(reset.millis()==0&&old.millis()==1010,"OFF confirmation cannot subtract pre-reset generation");
        RadioTimeGate boundary=new RadioTimeGate();boundary.observe("e",1000,RadioProfile.State.OFF);
        TripTime beforeReset=new TripTime(0,boundary);beforeReset.accept(fix(1000),1000);beforeReset.accept(fix(2000),2000);
        boundary.boundary(2000);TripTime afterReset=new TripTime(0,boundary);afterReset.accept(fix(2100),2100);afterReset.accept(fix(3100),3100);
        boundary.observe("e",1500,RadioProfile.State.OFF);afterReset.settle(5000);
        check(afterReset.millis()==1000&&boundary.state(5000)==RadioProfile.State.UNKNOWN,"unconfirmed pre-reset OFF and delayed old event cannot affect new timer");
        boundary.observe("e",5100,RadioProfile.State.OFF);afterReset.accept(fix(6100),6100);afterReset.settle(8100);
        check(afterReset.millis()==3000,"post-reset OFF starts its own confirmation and clips only its new interval");
        TripTime restarted=new TripTime(123,new RadioTimeGate());restarted.accept(fix(100000),100000);restarted.accept(fix(101000),101000);
        check(restarted.millis()==1123,"restart totals preserved, starts UNKNOWN without bridging");
        RadioTimeGate unknown=new RadioTimeGate();unknown.observe("e",1000,diagnostic.interpret(0,0));TripTime untrusted=new TripTime(0,unknown);untrusted.accept(fix(1000),1000);untrusted.accept(fix(2000),2000);
        check(untrusted.millis()==1000,"unvalidated mapping cannot subtract locally");
        System.out.println("PASS: diagnostic/profile gate, OFF debounce, stationary evidence, moving/poor GPS, loss/expiry/epoch, pause/reset/restart");
    }
}
