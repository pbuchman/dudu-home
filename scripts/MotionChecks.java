package com.pbuchman.duduhome.location;

import com.pbuchman.duduhome.automation.MotionHook;

public final class MotionChecks {
    private static MotionDetector.Fix fix(long t, double x, double speed) {
        return fix(t, x, speed, 3);
    }
    private static MotionDetector.Fix fix(long t, double x, double speed, double accuracy) {
        return new MotionDetector.Fix(t, x, 0, accuracy, speed, true, 0, false);
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
        for(int i=36;i<=51;i++) check(!d.accept(fix(i*1000,0,2))); // reported speed alone
        int[] attempts={0}; MotionHook h=new MotionHook(()->attempts[0]++);
        for(int i=0;i<=11;i++) h.accept(fix(i*1000,i*2,2),false);
        check(attempts[0]==0);
        h.accept(fix(12000,24,2),true); check(attempts[0]==1);
        h.clear(); attempts[0]=0;
        for(int i=13;i<=24;i++) h.accept(fix(i*1000,i*2,2),false);
        h.accept(fix(25000,48,0),true); h.accept(fix(26000,50,2),true);
        check(attempts[0]==0); check(!h.fresh(30000));
        cancellationChecks();
        verifiedCycleChecks();
        verifiedCycleDisplacementChecks();
        verifiedCycleStopChecks();
        continuityChecks();
        System.out.println("PASS: sustained motion, GPS quality, stop, continuity, pre-dispatch gate, verified-cycle displacement, restart and cancellation baselines");
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

    private static void verifiedCycleChecks() {
        for (MotionDetector.Source source : new MotionDetector.Source[] {
                MotionDetector.Source.VERIFIED_BOOT, MotionDetector.Source.VERIFIED_WAKE }) {
            int[] sent = {0};
            MotionHook hook = new MotionHook(() -> sent[0]++);
            hook.requireStationaryBaseline();
            for (int i = 0; i <= 15; i++) hook.accept(fix(i * 1000, i * 2, 2), true);
            check(sent[0] == 0 && hook.evidence().needsStop());
            hook.verifiedCycle(source);
            check(hook.evidence().state() == MotionDetector.State.BASELINE_READY
                    && hook.evidence().source() == source && !hook.evidence().needsStop());
            for (int i = 16; i <= 25; i++) hook.accept(fix(i * 1000, i * 2, 2), true);
            check(sent[0] == 0);
            hook.accept(fix(26000, 52, 2), true);
            check(sent[0] == 1);

            hook.cancel();
            hook.verifiedCycle(source);
            check(hook.evidence().needsStop() && hook.evidence().source() == MotionDetector.Source.USER_CANCELLED);
            for (int i = 27; i <= 40; i++) hook.accept(fix(i * 1000, i * 2, 2), true);
            check(sent[0] == 1);
        }
    }

    private static void verifiedCycleDisplacementChecks() {
        for (MotionDetector.Source source : new MotionDetector.Source[] {
                MotionDetector.Source.VERIFIED_BOOT, MotionDetector.Source.VERIFIED_WAKE }) {
            int[] sent = {0};
            MotionHook hook = new MotionHook(() -> sent[0]++);
            hook.requireStationaryBaseline();
            hook.verifiedCycle(source);
            for (int i = 0; i < 10; i++) hook.accept(fix(i * 1000, i, 1.6, 15), true);
            hook.accept(fix(10000, 16, 1.6, 15), true);
            check(sent[0] == 0);
            checkAccuracyOverlap(hook.evidence(), 10000);
            // Use the movement anchor, so even reaching 30 m after the wait is insufficient.
            hook.accept(fix(11000, 30, 1.6, 15), true);
            check(sent[0] == 0);
            checkAccuracyOverlap(hook.evidence(), 11000);
            hook.accept(fix(12000, 31, 1.6, 15), true);
            check(sent[0] == 1 && hook.evidence().state() == MotionDetector.State.CONFIRMED);
        }

        for (double initialAccuracy : new double[] {0, 15}) {
            MotionDetector detector = new MotionDetector();
            detector.verifiedCycle(MotionDetector.Source.VERIFIED_WAKE);
            check(!detector.accept(fix(0, 0, 1.5, initialAccuracy)));
            for (int i = 1; i < 10; i++) check(!detector.accept(fix(i * 1000, i, 1.5, 1)));
            check(!detector.accept(fix(10000, 15, 1.5, 15 - initialAccuracy)));
            checkAccuracyOverlap(detector.evidence(), 10000);
            check(detector.accept(fix(11000, 16, 1.5, 15 - initialAccuracy)));
        }

        MotionDetector driving = new MotionDetector();
        driving.verifiedCycle(MotionDetector.Source.VERIFIED_WAKE);
        for (int i = 0; i < 10; i++) check(!driving.accept(fix(i * 1000, i * 7, 7, 15)));
        check(driving.accept(fix(10000, 70, 7, 15)));

        MotionDetector minimumDistance = new MotionDetector();
        minimumDistance.verifiedCycle(MotionDetector.Source.VERIFIED_BOOT);
        for (int i = 0; i <= 10; i++) check(!minimumDistance.accept(fix(i * 1000, i, 1.5, 0)));
        check(minimumDistance.evidence().reason() == MotionDetector.Reason.NONE);
        check(minimumDistance.accept(fix(11000, 15, 1.5, 0)));
    }

    private static void checkAccuracyOverlap(MotionDetector.Evidence evidence, long elapsed) {
        check(evidence.state() == MotionDetector.State.CANDIDATE
                && evidence.reason() == MotionDetector.Reason.ACCURACY_OVERLAP
                && evidence.elapsed() == elapsed && evidence.moving() && !evidence.needsStop());
    }

    private static void verifiedCycleStopChecks() {
        for (boolean blocked : new boolean[] {false, true}) {
            int[] sent = {0};
            MotionHook hook = new MotionHook(() -> sent[0]++);
            hook.verifiedCycle(MotionDetector.Source.VERIFIED_WAKE);
            for (int i = 0; i <= 10; i++) {
                MotionDetector.Fix stopped = fix(i * 1000, 0, 0.4, 15);
                if (blocked) hook.observeBaseline(stopped);
                else hook.accept(stopped, true);
            }
            check(hook.evidence().reason() == MotionDetector.Reason.STOP_CONFIRMED
                    && !hook.hasSpatialEvidence() && sent[0] == 0);
            for (int i = 0; i < 10; i++) hook.accept(fix(11000 + i * 1000, i, 1.6, 15), true);
            check(sent[0] == 0);
            hook.accept(fix(21000, 16, 1.6, 15), true);
            check(sent[0] == 1);
        }

        MotionDetector incompleteStop = new MotionDetector();
        incompleteStop.verifiedCycle(MotionDetector.Source.VERIFIED_WAKE);
        for (int i = 0; i < 10; i++) incompleteStop.observeBaseline(fix(i * 1000, 0, 0, 15));
        incompleteStop.observeBaseline(fix(10000, 0, 0.5, 15));
        for (int i = 0; i < 10; i++) check(!incompleteStop.accept(fix(11000 + i * 1000, i, 1.6, 15)));
        check(!incompleteStop.accept(fix(21000, 16, 1.6, 15)));
        checkAccuracyOverlap(incompleteStop.evidence(), 10000);

        MotionDetector sameCycleRecovery = new MotionDetector();
        sameCycleRecovery.requireStationaryBaseline();
        for (int i = 0; i <= 10; i++) check(!sameCycleRecovery.accept(fix(i * 1000, i * 7, 7, 15)));
        check(sameCycleRecovery.evidence().needsStop());
        for (int i = 11; i <= 21; i++) sameCycleRecovery.observeBaseline(fix(i * 1000, 70, 0, 15));
        for (int i = 0; i < 10; i++) check(!sameCycleRecovery.accept(fix(22000 + i * 1000, 70 + i, 1.6, 15)));
        check(sameCycleRecovery.accept(fix(32000, 86, 1.6, 15)));
    }

    private static void continuityChecks() {
        MotionDetector detector = new MotionDetector();
        detector.requireStationaryBaseline();
        for (int i = 0; i <= 9; i++) detector.observeBaseline(fix(i * 1000, 0, 0));
        check(detector.hasSpatialEvidence());
        detector.observeBaseline(fix(10000, 10000, 0));
        check(detector.evidence().reason() == MotionDetector.Reason.JUMP
                && detector.evidence().needsStop() && detector.evidence().elapsed() == 0);
        detector.observeBaseline(fix(9000, 0, 0));
        check(detector.evidence().reason() == MotionDetector.Reason.NONMONOTONIC && detector.hasSpatialEvidence());
        for (int i = 11; i <= 20; i++) detector.observeBaseline(fix(i * 1000, 10000, 0));
        check(detector.evidence().state() == MotionDetector.State.BASELINE_READY && !detector.hasSpatialEvidence());
        for (int i = 21; i <= 30; i++) check(!detector.accept(fix(i * 1000, 10000 + i * 2, 2)));
        check(!detector.accept(fix(22000, 10044, 0)));
        check(detector.evidence().reason() == MotionDetector.Reason.NONMONOTONIC);
        check(detector.accept(fix(31000, 10062, 2)));
    }
}
