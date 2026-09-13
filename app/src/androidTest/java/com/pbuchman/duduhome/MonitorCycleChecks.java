package com.pbuchman.duduhome;

import android.app.Instrumentation;
import android.content.ContextWrapper;
import android.content.Intent;
import android.location.Location;
import android.os.SystemClock;
import com.pbuchman.duduhome.automation.*;
import com.pbuchman.duduhome.location.*;
import com.pbuchman.duduhome.startup.DuduCycle;
import java.util.ArrayList;
import java.util.HashMap;

/** Main-thread callbacks on isolated service objects, no registered GPS or real executors. */
final class MonitorCycleChecks {
    static void run(Instrumentation i) {
        i.runOnMainSync(() -> {
            var context = i.getTargetContext();
            var prefs = context.getSharedPreferences("journey_session", 0);
            check(prefs.edit().clear().commit(), "isolated emulator session");
            try {
                var attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", android.content.Context.class);
                attach.setAccessible(true);
                var callback = HomeMonitorService.class.getDeclaredMethod("onCycleRead", DuduCycle.Observation.class);
                callback.setAccessible(true);
                HomeMonitorService old = new HomeMonitorService(); attach.invoke(old, context);
                JourneySession session = new JourneySession(context); check(session.ensureBoot(), "initial boot");
                check(!(boolean) get(old,"cycleReady"), "initial barrier");
                callback.invoke(old, DuduCycle.unavailable());
                check((boolean) get(old,"cycleReady") && session.available(), "unknown completes cold-boot barrier");
                var motion = (MotionHook) get(old,"motion");
                long now = SystemClock.elapsedRealtime();
                motion.accept(new MotionDetector.Fix(now,0,0,3,2,true,0,false),false);
                set(old,"motionOrigin",new Location("synthetic"));
                var movement = motion.progress();
                long epoch = ProgressBus.reset(context, DetectionProgress.Reason.RESET);
                set(old,"progressEpoch",epoch);
                callback.invoke(old, FirstWakeChecks.cold());
                check(movement.equals(motion.progress()) && get(old,"motionOrigin") != null
                        && (long)get(old,"progressEpoch") == epoch, "late baseline preserves movement and UI");
                int[] homes = {0};
                var delayed = new ArrayList<Runnable>();
                YanosikLauncher target = new YanosikLauncher(session,new YanosikLauncher.Launch() {
                    public Intent resolve() { return new Intent(); }
                    public void open(Intent intent) { }
                    public void home() { homes[0]++; }
                },(task,delay)->delayed.add(task),()->YanosikPresence.State.NO_WORK_SIGNAL);
                set(old,"yanosik",target); target.attempt();
                check((boolean)get(target,"pendingHome"), "fake pending HOME");
                HomeDetector detector = new HomeDetector(new HomeDetector.Geometry(new HomeDetector.Point(0,0),
                        new HomeDetector.Point(0,60),new HomeDetector.Point(0,160),new HomeDetector.Point(100,200)),29);
                set(old,"detector",detector);
                callback.invoke(old, FirstWakeChecks.cycle(1));
                delayed.get(0).run();
                check(homes[0] == 0 && !(boolean)get(target,"pendingHome"), "new wake cancels HOME");
                check(!motion.fresh(now) && get(old,"motionOrigin") == null
                        && (long)get(old,"progressEpoch") != epoch && detector.flags()==29, "wake clears evidence not flags");
                check(session.available(), "wake rearms without dispatch");
                target.attempt(); // Fake executor only, used to prove late callback cannot cancel it.
                HomeMonitorService current = new HomeMonitorService(); attach.invoke(current,context);
                set(current,"yanosik",target);
                callback.invoke(current, FirstWakeChecks.cycle(1));
                set(old,"destroyed",true);
                var saved = new HashMap<>(prefs.getAll()); var snapshots = ProgressBus.snapshot();
                callback.invoke(old, FirstWakeChecks.cycle(2));
                check(saved.equals(prefs.getAll()) && snapshots.equals(ProgressBus.snapshot())
                        && (boolean)get(target,"pendingHome"), "old callback cannot affect current session or HOME");
                delayed.get(1).run(); check(homes[0] == 1,"current callback still runs once");
                ((java.util.concurrent.ExecutorService)get(old,"cycleReader")).shutdownNow();
                ((java.util.concurrent.ExecutorService)get(current,"cycleReader")).shutdownNow();
                check(prefs.edit().clear().commit(), "test cleanup");
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        });
    }
    private static Object get(Object object,String name) throws ReflectiveOperationException {
        var field=object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static void set(Object object,String name,Object value) throws ReflectiveOperationException {
        var field=object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object,value);
    }
    private static void check(boolean pass,String message) { if(!pass)throw new AssertionError(message); }
}
