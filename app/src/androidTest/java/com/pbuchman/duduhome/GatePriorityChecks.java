package com.pbuchman.duduhome;

import android.app.Instrumentation;
import com.pbuchman.duduhome.automation.*;
import static com.pbuchman.duduhome.location.HomeDetector.GateArea.*;

/** Exercises production scheduling/call-result adapters, with no physical executors. */
final class GatePriorityChecks {
    static void run(Instrumentation i) {
        i.runOnMainSync(() -> {
            var context = i.getTargetContext();
            var prefs = context.getSharedPreferences("journey_session",0);
            check(prefs.edit().clear().commit(),"isolated media session");
            AutomationRuntime runtime = new AutomationRuntime(context);
            JourneySession session = new JourneySession(context);
            check(session.ensureBoot(),"initial session");
            long freshUntil = android.os.SystemClock.elapsedRealtime() + 3000;
            try {
                runtime.gateArea(DEPARTURE,freshUntil);
                runtime.motion();
                check(session.available() && session.available(JourneySession.Target.SPOTIFY),
                        "gate context must not reserve or expire media before the call");
                check(!runtime.allowsMotionDetection(),"no generic progress before gate");
                check(HomeActions.begin(),"fake call lock");
                Runnable success = HomeActions.gateSuccessCallback();
                success.run();
                check(!runtime.allowsMotionDetection(),"success still waits for executor cleanup");
                HomeActions.end();
                check(runtime.allowsMotionDetection(),"call completion releases motion after cleanup");
                runtime.motion();
                check(session.available() && session.available(JourneySession.Target.SPOTIFY),
                        "both pending attempts remain uncommitted until external work starts");
                // Reset synchronously, before any posted drain can run a media executor.
                runtime.reset(DetectionProgress.Reason.WAKE);
                runtime.gateArea(DEPARTURE,freshUntil); success.run();
                check(!runtime.allowsMotionDetection(),"old completion cannot release new wake");
                runtime.gateArea(NONE,freshUntil);
                check(runtime.allowsMotionDetection(),"no home config/outside remains independent");
                try {
                    var expiry = AutomationRuntime.class.getDeclaredField("areaFreshUntil");
                    expiry.setAccessible(true); expiry.setLong(runtime,0);
                    check(!runtime.allowsMotionDetection(),"delayed launch needs fresh area proof, not just old outside");
                    runtime.noHomeConfiguration(); expiry.setLong(runtime,0);
                    check(runtime.allowsMotionDetection(),"disabled home configuration has no area barrier");
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                runtime.gateArea(UNKNOWN,0);
                check(!runtime.allowsMotionDetection(),"missing location proof blocks");
            } finally {
                runtime.close(); HomeActions.end(); prefs.edit().clear().commit();
            }
        });
    }
    private static void check(boolean pass,String message) { if(!pass) throw new AssertionError(message); }
}
