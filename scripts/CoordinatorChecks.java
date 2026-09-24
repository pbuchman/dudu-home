package com.pbuchman.duduhome.automation;

import static com.pbuchman.duduhome.automation.AutomationCoordinator.Type.*;

public final class CoordinatorChecks {
    private static void check(boolean ok) { if (!ok) throw new AssertionError("coordinator regression"); }
    public static void main(String[] args) {
        var c = new AutomationCoordinator();
        c.enqueue(1,SPOTIFY,null,0,10); c.enqueue(2,YANOSIK,null,0,10);
        c.enqueue(3,CLEANING,HomeEvent.OUTBOUND_CHECKPOINT,0,10);
        c.enqueue(4,GATE,HomeEvent.DEPARTURE_STARTED,0,10);
        c.enqueue(4,GATE,HomeEvent.DEPARTURE_STARTED,4000,10); // Cannot extend deadline.
        check(c.next(0,false,false)==null);
        check(c.next(0,true,true).type()==GATE);
        check(c.next(1,false,false)==null); // Phone cleanup/result still owns UI.
        check(c.next(1,true,true).type()==CLEANING);
        check(c.next(2,true,true).type()==YANOSIK);
        c.yanosikLaunched(2);
        check(c.next(10001,true,true)==null);
        c.enqueue(5,GATE,HomeEvent.RETURN_APPROACH,10001,10);
        check(c.next(10001,true,false).type()==GATE); // Does not wait for media grace.
        check(c.next(10002,false,false)==null);
        check(c.next(15000,true,true).type()==SPOTIFY);
        check(c.next(15000,true,true)==null);
        c.enqueue(6,GATE,null,0,10); c.enqueue(7,CLEANING,null,0,10);
        check(c.expire(5000,10).size()==1);
        check(c.expire(120000,10).size()==1);
        check(c.next(120001,true,true)==null);
        c.enqueue(8,CLEANING,null,0,10); check(c.expire(100,11).size()==1);
        c.reset(); c.enqueue(9,SPOTIFY,null,0,10);
        var old=c.next(0,true,true); check(c.current(old));
        c.reset(); check(!c.current(old));
        c.enqueue(10,SPOTIFY,null,0,10); c.reset();
        check(c.next(0,true,true)==null);
        c.enqueue(11,SPOTIFY,null,0,10);
        check(c.next(0,true,false)==null); // Manual menu, error or configuration.
        check(c.next(0,true,true).id()==11);
        check(new AutomationCoordinator().next(0,true,true)==null);
        System.out.println("PASS: priorities, grace, expiry, presentation lock, generations, no replay");
    }
}
