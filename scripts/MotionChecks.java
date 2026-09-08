package com.pbuchman.duduhome.location;

import com.pbuchman.duduhome.automation.MotionHook;

public final class MotionChecks {
    private static MotionDetector.Fix fix(long t, double x, double speed) {
        return new MotionDetector.Fix(t, x, 0, 3, speed, true, 0, false);
    }
    private static void check(boolean ok) { if (!ok) throw new AssertionError("motion regression"); }
    public static void main(String[] args) {
        MotionDetector d = new MotionDetector();
        for (int i=0;i<10;i++) check(!d.accept(fix(i*1000,i*2,2)));
        check(d.accept(fix(10000,20,2)));
        check(!d.accept(fix(11000,20,0)));
        check(!d.accept(fix(12000,22,2)));
        check(!d.accept(new MotionDetector.Fix(13000,24,0,3,2,false,0,false)));
        check(!d.accept(new MotionDetector.Fix(14000,24,0,3,2,true,4000,false)));
        check(!d.accept(new MotionDetector.Fix(15000,24,0,3,2,true,0,true)));
        check(!d.accept(new MotionDetector.Fix(16000,24,0,20,2,true,0,false)));
        for(int i=0;i<10;i++) check(!d.accept(fix(20000+i*1000,30+i*2,2)));
        check(!d.accept(fix(34000,58,2))); // gap discards evidence
        check(!d.accept(fix(35000,10000,2))); // jump cannot create movement event
        d.clear();
        for(int i=0;i<=15;i++) check(!d.accept(fix(i*1000,0,2))); // reported speed alone
        int[] attempts={0}; MotionHook h=new MotionHook(()->attempts[0]++);
        for(int i=0;i<=11;i++) h.accept(fix(i*1000,i*2,2),false);
        check(attempts[0]==0);
        h.accept(fix(12000,24,2),true); check(attempts[0]==1);
        h.clear(); attempts[0]=0;
        for(int i=0;i<=11;i++) h.accept(fix(i*1000,i*2,2),false);
        h.accept(fix(12000,22,0),true); h.accept(fix(13000,24,2),true);
        check(attempts[0]==0); check(!h.fresh(17000));
        System.out.println("PASS: sustained motion, quality, stop, gap, jump, deferred/cancelled hook");
    }
}
