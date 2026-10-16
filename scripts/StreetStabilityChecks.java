package com.pbuchman.duduhome.trip;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static com.pbuchman.duduhome.trip.PlaceResult.Quality.*;

public final class StreetStabilityChecks {
    private static int checks;
    private static void check(boolean value,String message) { checks++;if (!value) throw new AssertionError(message); }
    private static TripFix fix(long time,double meters) { return new TripFix(time,time,0,meters/111195,1,10,true,90,true,false); }
    private static PlaceResult road(String name) { return new PlaceResult("Synthetic City",name,CONTAINMENT,MATCHED,"offline_boundary","offline_road",true); }
    private static PlaceResult ambiguous() { return new PlaceResult("Synthetic City","",CONTAINMENT,AMBIGUOUS,"offline_boundary","offline_road",true); }
    private static PlaceStabilizer confirmed() {
        PlaceStabilizer s=new PlaceStabilizer();s.offer(road("Synthetic Alpha"),fix(1000,0));
        check(s.offer(road("Synthetic Alpha"),fix(2000,10)).streetQuality()==MATCHED,"two distinct fixes confirm road");return s;
    }
    public static void main(String[] args) throws Exception {
        PlaceStabilizer s=confirmed();
        PlaceResult retained=s.offer(ambiguous(),fix(3000,20));
        check(retained.streetQuality()==LAST_KNOWN && retained.street().equals("Synthetic Alpha"),"one ambiguity retains explicitly uncertain name");
        check(retained.subtitle().equals("Ostatnio: Synthetic Alpha") && !retained.complete(),"retained name is not a confident current match");
        check(retained.localityQuality()==CONTAINMENT,"street uncertainty cannot clear locality");
        check(retained.atStreetDeadline(5000,4999).streetQuality()==LAST_KNOWN,"immutable snapshot keeps unexpired last-known name");
        check(retained.atStreetDeadline(5000,5000).street().isEmpty(),"snapshot reader expires at deadline without publisher tick");
        check(retained.atStreetDeadline(0,3000).street().isEmpty(),"missing snapshot deadline fails closed");
        check(s.offer(road("Synthetic Alpha"),fix(4000,30)).streetQuality()==LAST_KNOWN,"one recovering fix cannot reconfirm");
        check(s.offer(road("Synthetic Alpha"),fix(5000,40)).streetQuality()==MATCHED,"second recovering fix confirms same road");
        s=confirmed();s.offer(ambiguous(),fix(3000,20));
        check(s.current(5001,fix(3000,20)).street().isEmpty(),"timer expires without another GPS fix");
        s=confirmed();check(s.offer(ambiguous(),fix(3000,51)).street().isEmpty(),"40m bound applies before time expires");
        s=confirmed();check(s.offer(road("Synthetic Beta"),fix(3000,20)).street().isEmpty(),"named contradictory road clears old name immediately");
        check(s.offer(ambiguous(),fix(3500,25)).street().isEmpty(),"old street cannot resurrect after contradiction");
        s.offer(road("Synthetic Beta"),fix(4000,30));
        check(s.offer(road("Synthetic Beta"),fix(5000,40)).street().equals("Synthetic Beta"),"changed road needs own distinct confirmations");
        s=confirmed();check(s.offer(PlaceResult.unknown(false),fix(3000,20)).street().isEmpty(),"source failure cannot retain an old street");
        s=confirmed();PlaceResult boundary=new PlaceResult("Synthetic Other City","",CONTAINMENT,AMBIGUOUS,"offline_boundary","offline_road",true);
        check(s.offer(boundary,fix(3000,20)).street().isEmpty(),"new certain locality drops previous street retention");
        s=new PlaceStabilizer();s.offer(road("Synthetic Alpha"),fix(1000,0));
        check(s.offer(road("Synthetic Alpha"),fix(1000,0)).street().isEmpty(),"two sources for same fix still one confirmation");
        check(!PlaceResolver.currentOnline(fix(1000,0),fix(2000,10),fix(2000,10)),"older online response cannot overwrite newer offline fix");
        check(!PlaceResolver.currentOnline(fix(1000,0),fix(1000,0),fix(2000,10)),"older online response cannot race a newer GPS fix still resolving");
        check(!PlaceResolver.currentOnline(fix(1000,0),null,null),"cleared generation has no online target");
        check(PlaceResolver.currentOnline(fix(1000,0),fix(1000,0),fix(1000,0)),"only exact current fix can accept online evidence");
        slowOnlineDoesNotBlockOffline();
        System.out.println("PASS: street stability and independent offline/online resolver ("+checks+" checks)");
    }
    private static void slowOnlineDoesNotBlockOffline() throws Exception {
        CountDownLatch onlineEntered=new CountDownLatch(1),releaseOnline=new CountDownLatch(1),newOffline=new CountDownLatch(1);
        AtomicInteger calls=new AtomicInteger();
        try (PlaceResolver resolver=new PlaceResolver(f->calls.incrementAndGet()==1 ? ambiguous() : road("Synthetic Beta"),f->{
            onlineEntered.countDown();try { releaseOnline.await(5,TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return null;
        },(job,result,online,duration)->{if (!online && job.fix().elapsed()==2000) newOffline.countDown();},Thread::new,Thread::new)) {
            resolver.offer(new PlaceResolver.Job(0,fix(1000,0)));
            check(onlineEntered.await(3,TimeUnit.SECONDS),"test HTTP request entered blocking state");
            resolver.offer(new PlaceResolver.Job(0,fix(2000,10)));
            check(newOffline.await(3,TimeUnit.SECONDS),"new offline road resolves while HTTP remains blocked");
            check(releaseOnline.getCount()==1,"offline completion did not require HTTP release");
            resolver.invalidate(1);resolver.offer(new PlaceResolver.Job(0,fix(3000,20)));
        } finally { releaseOnline.countDown(); }
    }
}
