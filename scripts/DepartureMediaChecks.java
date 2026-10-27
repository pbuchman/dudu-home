package com.pbuchman.duduhome.automation;

import com.pbuchman.duduhome.location.HomeDetector;
import com.pbuchman.duduhome.location.MotionDetector;
import static com.pbuchman.duduhome.automation.AutomationCoordinator.Type.*;
import static com.pbuchman.duduhome.location.HomeDetector.GateArea.*;

/** Synthetic local metres and clocks; no Android, apps, calls or network. */
public final class DepartureMediaChecks {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        var continuation = new DepartureContinuation<Long>();
        var queue = new AutomationCoordinator();
        var gate = new GatePrecondition();
        var home = new HomeDetector(new HomeDetector.Geometry(new HomeDetector.Point(0,0),
                new HomeDetector.Point(20,0), new HomeDetector.Point(100,0), new HomeDetector.Point(300,0)),0);
        var motion = new MotionDetector(); motion.requireStationaryBaseline();
        int departures = 0;
        // First good parking fix starts departure without any standstill or media baseline.
        for (int n=0; n<4; n++) {
            var fix = new HomeDetector.Fix(n*1000L,new HomeDetector.Point(n,0),3,1,0,false);
            var events = home.accept(fix);
            gate.observe(home.gateArea());
            if (events.contains(HomeEvent.DEPARTURE_STARTED)) {
                departures++;
                continuation.offered(10,HomeEvent.DEPARTURE_STARTED,queue.generation(),1L);
            }
            check(!motion.accept(fix(n*1000L,n,1)),"restart media baseline is still unqualified");
        }
        check(home.accept(new HomeDetector.Fix(3000,new HomeDetector.Point(3,0),3,1,0,false)).isEmpty(),"duplicate GPS does not recognize another departure");
        check(departures==1 && !gate.allowsMedia(),"one departure, still blocked before successful call");
        var bound = continuation.capture(10);
        var release = gate.successCallback();
        check(continuation.succeeded(bound,queue.generation(),1L),"success recognizes automatic departure origin");
        release.run();
        queue.enqueue(20,YANOSIK,null,4000,0); queue.enqueue(21,SPOTIFY,null,4000,0);
        check(!motion.accept(fix(4000,3,0)),"car is stationary at successful completion");
        check(!continuation.succeeded(bound,queue.generation(),1L),"duplicate completion is single-use");
        check(continuation.capture(10)==null,"recaptured completed attempt cannot replay");
        check(queue.next(4000,false,false)==null,"cleanup/result/manual presentation blocks launches");
        queue.enqueue(22,CLEANING,HomeEvent.OUTBOUND_CHECKPOINT,4000,0);
        check(queue.next(9000,true,true).type()==CLEANING,"cleaning remains above departure media");
        check(queue.next(9000,true,gate.allowsMedia()).type()==YANOSIK,"stationary success starts Yanosik first");
        queue.yanosikLaunched(9000);
        check(queue.next(18999,true,true)==null,"requested Yanosik keeps ten-second allowance");
        check(queue.next(19000,true,true).type()==SPOTIFY,"Spotify follows once, without further movement");

        for (HomeEvent origin : new HomeEvent[]{null,HomeEvent.RETURN_APPROACH,HomeEvent.OUTBOUND_CHECKPOINT}) {
            var other = new DepartureContinuation<Long>();
            other.offered(1,origin,0,1L);
            check(!other.succeeded(other.capture(1),0,1L),"manual/return/cleaning cannot continue departure media");
        }
        var stale = new DepartureContinuation<Long>();
        stale.offered(1,HomeEvent.DEPARTURE_STARTED,0,1L);
        check(stale.capture(2)==null,"wrong attempt is inert");
        var old = stale.capture(1); stale.cancelAttempt(1);
        check(!stale.succeeded(old,0,1L),"cancelled gate cannot continue");
        stale.offered(2,HomeEvent.DEPARTURE_STARTED,0,1L); old=stale.capture(2); stale.invalidate();
        check(!stale.succeeded(old,0,1L),"runtime reset invalidates pending continuation");
        stale.offered(3,HomeEvent.DEPARTURE_STARTED,0,1L); old=stale.capture(3);
        check(!stale.succeeded(old,1,1L),"old generation cannot continue");
        stale.offered(4,HomeEvent.DEPARTURE_STARTED,1,1L); old=stale.capture(4);
        check(!stale.succeeded(old,1,2L),"old journey cycle cannot continue");
        stale.offered(5,HomeEvent.DEPARTURE_STARTED,1,null);
        check(stale.capture(5)==null,"unavailable/failed cycle has no continuation");

        var independent = new DepartureContinuation<Long>();
        independent.offered(7,HomeEvent.DEPARTURE_STARTED,0,1L);
        var independentBinding=independent.capture(7); independent.cancelMedia();
        check(independent.succeeded(independentBinding,0,1L) && independent.mediaCancelled(),"media cancellation preserves successful departure cleaning without rearming media");
        check(home.progress().stream().noneMatch(p -> p.kind()==DetectionProgress.Kind.CLEANING),"checkpoint geometry has no cleaning detection countdown");
        cancellationBaseline();
        var expiry = new AutomationCoordinator();
        expiry.enqueue(1,YANOSIK,null,1000,0); expiry.enqueue(2,SPOTIFY,null,1000,0);
        check(expiry.expire(120999,0).isEmpty(),"media retains its bounded opportunity");
        check(expiry.expire(121000,0).size()==2 && expiry.next(121000,true,true)==null,"120-second expiry never retries");
        gate.observe(UNKNOWN); check(!gate.allowsMedia(),"successful call does not bypass missing GPS");
        gate.observe(DEPARTURE); check(gate.allowsMedia(),"fresh same-area observation restores completed-call barrier");
        gate.observe(NONE); gate.observe(DEPARTURE);
        check(!gate.allowsMedia(),"new visit still requires its own gate success");
        System.out.println("PASS: stationary departure continuation, no initial standstill, duplicate GPS/callback, origin, cancellation baseline, generations/cycles, priority, grace, expiry and GPS barrier");
    }
    private static MotionDetector.Fix fix(long time,double x,double speed) {
        return new MotionDetector.Fix(time,x,0,3,speed,true,0,false);
    }
    private static void cancellationBaseline() {
        var continuation = new DepartureContinuation<Long>();
        continuation.offered(1,HomeEvent.DEPARTURE_STARTED,0,1L);
        var old=continuation.capture(1);
        continuation.cancelMedia(); continuation.invalidate();
        check(continuation.mediaCancelled() && !continuation.succeeded(old,0,1L),"candidate cancellation survives reset and late gate success");
        var motion = new MotionHook(continuation::motionConfirmed); motion.cancel();
        for (int n=0;n<=15;n++) motion.accept(fix(n*1000L,n*2,2),true);
        check(continuation.mediaCancelled(),"movement alone cannot rearm cancellation");
        for (int n=16;n<=26;n++) motion.observeBaseline(fix(n*1000L,30,0));
        check(continuation.mediaCancelled(),"qualified stop alone cannot rearm cancellation");
        continuation.offered(2,HomeEvent.DEPARTURE_STARTED,0,1L);
        check(continuation.capture(2)!=null && continuation.mediaCancelled(),"media cancellation preserves independent departure cleaning provenance");
        for (int n=27;n<37;n++) motion.accept(fix(n*1000L,30+(n-27)*2,2),true);
        check(continuation.mediaCancelled(),"a complete new motion test is required");
        motion.accept(fix(37000,50,2),true);
        check(!continuation.mediaCancelled(),"only fresh stop followed by qualified movement rearms");
        check(!continuation.succeeded(old,0,1L),"rearm cannot revive an older captured callback");
    }
}
