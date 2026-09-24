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
                AutomationRuntime target = new AutomationRuntime(context);
                AutomationCoordinator queue = (AutomationCoordinator)get(target,"queue");
                queue.enqueue(0,AutomationCoordinator.Type.SPOTIFY,null,now,0);
                queue.yanosikLaunched(now);
                set(old,"automation",target);
                check(session.reserve(), "consume old cycle");
                HomeDetector detector = new HomeDetector(new HomeDetector.Geometry(new HomeDetector.Point(0,0),
                        new HomeDetector.Point(0,60),new HomeDetector.Point(0,160),new HomeDetector.Point(100,200)),29);
                set(old,"detector",detector);
                callback.invoke(old, FirstWakeChecks.cycle(1));
                check(queue.next(now+20000,true,true)==null, "new wake cancels queued media");
                check(!motion.fresh(now) && get(old,"motionOrigin") == null
                        && (long)get(old,"progressEpoch") != epoch && detector.flags()==29, "wake clears evidence not flags");
                check(session.available(), "wake rearms without dispatch");
                queue.enqueue(0,AutomationCoordinator.Type.SPOTIFY,null,now,0);
                HomeMonitorService current = new HomeMonitorService(); attach.invoke(current,context);
                set(current,"automation",target);
                callback.invoke(current, FirstWakeChecks.cycle(1));
                set(old,"destroyed",true);
                var saved = new HashMap<>(prefs.getAll()); var snapshots = ProgressBus.snapshot();
                callback.invoke(old, FirstWakeChecks.cycle(2));
                check(saved.equals(prefs.getAll()) && snapshots.equals(ProgressBus.snapshot()), "old callback cannot affect current session");
                check(queue.next(now,true,true)!=null,"current queue remains intact");
                target.close();
                // Production enqueue adapter, while a synthetic executor owns the shared lock.
                var daily = context.getSharedPreferences("daily_cleaning", 0);
                check(daily.edit().clear().commit(), "isolated daily quota");
                check(HomeActions.begin(), "synthetic executor ownership");
                AutomationRuntime waiting = new AutomationRuntime(context);
                waiting.home(HomeEvent.OUTBOUND_CHECKPOINT);
                AutomationCoordinator waitingQueue = (AutomationCoordinator)get(waiting,"queue");
                check(waitingQueue.hasHomeWaiting() && !DailyCleaning.reserve(context),
                        "busy gate queues cleaning and reserves quota immediately");
                waiting.home(HomeEvent.OUTBOUND_CHECKPOINT);
                check(((java.util.Map<?,?>)get(waitingQueue,"queued")).size() == 1,
                        "duplicate cleaning cannot enqueue twice");
                HomeActions.configurationChanged();
                check(!waitingQueue.hasHomeWaiting() && !DailyCleaning.reserve(context),
                        "configuration invalidates pending work without restoring quota");
                waiting.close();
                AutomationRuntime replacement = new AutomationRuntime(context);
                check(!DailyCleaning.reserve(context) &&
                        ((AutomationCoordinator)get(replacement,"queue")).next(now,true,true)==null,
                        "new runtime neither restores quota nor replays cleaning");
                replacement.close(); HomeActions.end();
                check(daily.edit().clear().commit(), "daily test cleanup");
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
