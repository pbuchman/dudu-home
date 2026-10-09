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
        cancellationChecks();
        System.out.println("PASS: sustained motion, quality, stop, gap, jump, deferred hook, cancellation stop baseline, restart and blocked-period baseline");
    }
    private static void cancellationChecks() {
        MotionDetector d = new MotionDetector();
        for (int i = 0; i <= 10; i++) d.accept(fix(i * 1000, i * 2, 2));
        d.cancel();
        for (int i = 11; i <= 40; i++) check(!d.accept(fix(i * 1000, i * 2, 2)));
        d.clear(); // GPS/service gap must preserve the cancellation latch
        for (int i = 41; i <= 60; i++) check(!d.accept(fix(i * 1000, i * 2, 2)));
        for (int i = 61; i <= 70; i++) check(!d.accept(fix(i * 1000, 120, 0)));
        for (int i = 71; i <= 90; i++) check(!d.accept(fix(i * 1000, 120 + (i - 70) * 2, 2)));
        for (int i = 91; i <= 101; i++) check(!d.accept(fix(i * 1000, 160, 0.4)));
        for (int i = 102; i <= 111; i++) check(!d.accept(fix(i * 1000, 160 + (i - 102) * 2, 2)));
        check(d.accept(fix(112000, 180, 2)));

        MotionDetector restart = new MotionDetector();
        restart.requireStationaryBaseline();
        for (int i = 0; i <= 20; i++) check(!restart.accept(fix(i * 1000, i * 2, 2)));
        for (int i = 21; i <= 29; i++) check(!restart.accept(fix(i * 1000, 40, 0)));
        check(!restart.accept(new MotionDetector.Fix(30000,40,0,3,0,false,0,false)));
        for (int i = 31; i <= 39; i++) check(!restart.accept(fix(i * 1000, 40, 0)));
        check(!restart.accept(fix(44000,40,0))); // a gap restarts the stop dwell
        for (int i = 45; i <= 53; i++) check(!restart.accept(fix(i * 1000, 40, 0)));
        for (int i = 54; i <= 70; i++) check(!restart.accept(fix(i * 1000, 40 + (i - 53) * 2, 2)));
        MotionDetector blocked = new MotionDetector();
        blocked.requireStationaryBaseline();
        for (int i = 0; i <= 10; i++) blocked.observeBaseline(fix(i * 1000, 0, 0));
        for (int i = 11; i <= 25; i++) blocked.observeBaseline(fix(i * 1000, (i - 10) * 2, 2));
        check(blocked.progress().isEmpty() || blocked.progress().stream().noneMatch(
                p -> p.phase() == com.pbuchman.duduhome.automation.DetectionProgress.Phase.CANDIDATE));
        for (int i = 26; i <= 35; i++) check(!blocked.accept(fix(i * 1000, 30 + (i - 26) * 2, 2)));
        check(blocked.accept(fix(36000, 50, 2)));
        int[] actions = {0};
        MotionHook hook = new MotionHook(() -> actions[0]++);
        hook.requireStationaryBaseline();
        for (int i = 0; i <= 20; i++) hook.accept(fix(i * 1000, i * 2, 2), true);
        check(actions[0] == 0);
        hook.cancel(); hook.clear();
        for (int i = 21; i <= 31; i++) hook.accept(fix(i * 1000, 40, 0), true);
        for (int i = 32; i <= 41; i++) hook.accept(fix(i * 1000, 40 + (i - 32) * 2, 2), true);
        check(actions[0] == 0);
        hook.accept(fix(42000, 60, 2), true);
        check(actions[0] == 1);
    }
}
