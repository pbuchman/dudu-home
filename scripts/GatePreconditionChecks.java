package com.pbuchman.duduhome.automation;

import com.pbuchman.duduhome.location.HomeDetector;
import com.pbuchman.duduhome.location.MotionDetector;
import static com.pbuchman.duduhome.location.HomeDetector.GateArea.*;

/** Invented local metres, no device, GPS injection or physical actions. */
public final class GatePreconditionChecks {
    static void check(boolean value, String why) { if (!value) throw new AssertionError(why); }
    public static void main(String[] args) {
        var gate = new GatePrecondition();
        check(!gate.allowsMedia(), "unknown location is not outside home");
        gate.observe(DEPARTURE);
        for (int i=0; i<200; i++) gate.observe(DEPARTURE);
        check(!gate.allowsMedia(), "waiting has no timeout that bypasses gate");
        Runnable success = gate.successCallback();
        check(!gate.allowsMedia(), "request, reservation or failure is not success");
        success.run(); check(gate.allowsMedia(), "actual call completion releases same area");
        gate.observe(UNKNOWN); check(!gate.allowsMedia(), "lost GPS blocks even after call");
        gate.observe(DEPARTURE); check(gate.allowsMedia(), "fresh same-area fix preserves completed call");
        gate.observe(NONE); check(gate.allowsMedia(), "outside never waits for gate");
        gate.observe(DEPARTURE); success.run();
        check(!gate.allowsMedia(), "old call cannot release a new visit");
        success = gate.successCallback(); gate.reset(); gate.observe(DEPARTURE); success.run();
        check(!gate.allowsMedia(), "wake/configuration reset rejects old executor callback");
        gate.observe(RETURN); check(!gate.allowsMedia(), "return gets equal precondition priority");
        gate.successCallback().run(); check(gate.allowsMedia(), "return completion releases media");

        var geometry = new HomeDetector.Geometry(new HomeDetector.Point(0,0),
                new HomeDetector.Point(0,-70), new HomeDetector.Point(100,100), new HomeDetector.Point(400,100));
        var home = new HomeDetector(geometry, 0);
        var moving = new MotionDetector();
        gate.reset();
        // Generic motion qualifies while the car is manoeuvring within the departure envelope.
        for(int n=0;n<20;n++) {
            var events = home.accept(new HomeDetector.Fix(n*1000L,new HomeDetector.Point(n*2,0),3,2,0,false));
            gate.observe(home.gateArea());
            boolean drive = moving.accept(new MotionDetector.Fix(n*1000L,n*2,0,3,2,true,0,false));
            check(events.isEmpty(), "unarmed movement is not an invented gate call");
            check(!gate.allowsMedia(), "gate envelope blocks generic driving before any candidate");
            if(n>=10) check(drive,"regression really covers ready generic movement");
        }
        home.accept(new HomeDetector.Fix(20000,new HomeDetector.Point(86,0),3,2,0,false));
        check(home.gateArea()==DEPARTURE,"GPS uncertainty at departure boundary holds media");
        home.accept(new HomeDetector.Fix(21000,new HomeDetector.Point(100,0),3,2,0,false));
        gate.observe(home.gateArea()); check(gate.allowsMedia(),"fresh outside evidence releases without calling");
        home.accept(new HomeDetector.Fix(22000,new HomeDetector.Point(100,0),30,2,0,false));
        check(home.gateArea()==UNKNOWN,"poor GPS cannot declare outside");
        home.clearEvidence(); check(home.gateArea()==UNKNOWN,"watchdog clears area proof");

        var staleFlags = new HomeDetector(geometry,29);
        staleFlags.accept(new HomeDetector.Fix(0,new HomeDetector.Point(20,15),3,0,0,false));
        check(staleFlags.gateArea()==DEPARTURE,"old consumed flags cannot bypass new parked precondition");
        var returning = new HomeDetector(geometry,0);
        returning.accept(new HomeDetector.Fix(0,new HomeDetector.Point(400,220),3,2,0,false));
        check(returning.gateArea()==RETURN,"external approach blocks before turn event");
        returning.accept(new HomeDetector.Fix(1000,new HomeDetector.Point(400,215),3,0,0,false));
        check(returning.gateArea()==RETURN,"traffic stop keeps gate priority");
        returning.accept(new HomeDetector.Fix(2000,new HomeDetector.Point(400,230),3,2,0,false));
        check(returning.gateArea()==NONE,"driving away on the external road is not a gate return");
        returning.accept(new HomeDetector.Fix(3000,new HomeDetector.Point(400,225),3,2,0,false));
        check(returning.gateArea()==RETURN,"turning back toward junction restores gate precedence");
        System.out.println("PASS: gate before generic motion, area uncertainty, success-only release, stale callbacks, return and reset");
    }
}
