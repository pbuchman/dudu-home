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
    private static PlaceResult unnamed() { return new PlaceResult("Synthetic City","",CONTAINMENT,UNNAMED,"offline_boundary","offline_road",true); }
    private static PlaceResult ambiguous() { return new PlaceResult("Synthetic City","",CONTAINMENT,AMBIGUOUS,"offline_boundary","offline_road",true); }
    private static PlaceStabilizer confirmed() {
        PlaceStabilizer s=new PlaceStabilizer();s.offer(road("Synthetic Alpha"),fix(1000,0));
        check(s.offer(road("Synthetic Alpha"),fix(2000,10)).streetQuality()==MATCHED,"two distinct fixes confirm road");return s;
    }
    public static void main(String[] args) throws Exception {
        PlaceStabilizer s=confirmed();
        PlaceResult retained=s.offer(ambiguous(),fix(3000,20));
        check(retained.streetQuality()==LAST_KNOWN && retained.street().equals("Synthetic Alpha"),"ambiguity retains confirmed display");
        check(retained.subtitle().equals("Synthetic Alpha") && !retained.complete(),"retained text has no prefix and is not raw match");
        check(s.latest().streetQuality()==AMBIGUOUS && s.latest().street().isEmpty(),"retained display cannot rewrite raw ambiguity");
        check(retained.localityQuality()==CONTAINMENT,"street uncertainty cannot clear locality");
        check(s.offer(PlaceResult.unknown(false),fix(20000,2000)).street().equals("Synthetic Alpha"),"missing coverage retains name without 3s/40m display limit");
        check(s.current().streetQuality()==LAST_KNOWN && s.latest().streetQuality()==UNKNOWN,"raw unknown distinct from held label");
        check(s.offer(road("Synthetic Beta"),fix(21000,2010)).street().equals("Synthetic Alpha"),"first new road retains previous name");
        check(s.offer(road("Synthetic Beta"),fix(21000,2010)).street().equals("Synthetic Alpha"),"two sources on one fix cannot change street");
        check(s.offer(road("Synthetic Beta"),fix(22000,2020)).street().equals("Synthetic Beta"),"second distinct fix changes street");
        check(s.offer(unnamed(),fix(23000,2030)).street().equals("Synthetic Beta"),"first unnamed fix cannot erase confirmed road");
        PlaceResult noName=s.offer(unnamed(),fix(24000,2040));
        check(noName.streetQuality()==UNNAMED && noName.street().isEmpty() && noName.subtitle().isEmpty(),"confirmed typed unnamed has city only");
        check(noName.complete(),"certain unnamed road needs no online invented name");
        check(PlaceResult.enrich(noName,new PlaceResult("Synthetic City","Synthetic Photon","photon",true,true)).streetQuality()==UNNAMED,"Photon cannot replace certain unnamed road");
        check(s.offer(ambiguous(),fix(25000,2050)).subtitle().isEmpty(),"uncertainty after unnamed cannot resurrect named road");
        s=confirmed();
        TripFix noisy=new TripFix(3000,3000,0,0,200,0,false,0,false,false);
        check(s.offer(road("Synthetic Beta"),noisy).street().equals("Synthetic Alpha"),"noisy fix cannot confirm change or erase display");
        check(s.offer(road("Synthetic Beta"),fix(4000,30)).street().equals("Synthetic Alpha"),"noisy fix was not a first confirmation");
        check(s.offer(road("Synthetic Beta"),fix(5000,40)).street().equals("Synthetic Beta"),"two valid new fixes confirm after noise");
        s=new PlaceStabilizer();
        check(s.current().street().isEmpty() && s.latest().streetQuality()==UNKNOWN,"new journey/GPS generation starts empty");
        s.offer(road("Synthetic Alpha"),fix(1000,0));
        check(s.offer(road("Synthetic Alpha"),fix(1000,0)).street().isEmpty(),"same GPS fix cannot confirm");
        check(!PlaceResolver.currentOnline(fix(1000,0),fix(2000,10),fix(2000,10)),"older online response cannot overwrite newer offline fix");
        check(!PlaceResolver.currentOnline(fix(1000,0),fix(1000,0),fix(2000,10)),"online response cannot race newer resolving fix");
        check(!PlaceResolver.currentOnline(fix(1000,0),null,null),"cleared generation has no online target");
        check(PlaceResolver.currentOnline(fix(1000,0),fix(1000,0),fix(1000,0)),"only exact current fix accepts online evidence");
        slowOnlineDoesNotBlockOffline();
        System.out.println("PASS: retained street display and independent raw resolver ("+checks+" checks)");
    }
    private static void slowOnlineDoesNotBlockOffline() throws Exception {
        CountDownLatch onlineEntered=new CountDownLatch(1),releaseOnline=new CountDownLatch(1),newOffline=new CountDownLatch(1);
        AtomicInteger calls=new AtomicInteger();
        try (PlaceResolver resolver=new PlaceResolver(f->calls.incrementAndGet()==1 ? ambiguous() : road("Synthetic Beta"),f->{
            onlineEntered.countDown();try { releaseOnline.await(5,TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return null;
        },(job,result,online,duration)->{if (!online && job.fix().elapsed()==2000) newOffline.countDown();},Thread::new,Thread::new)) {
            resolver.offer(new PlaceResolver.Job(0,fix(1000,0)));
            check(onlineEntered.await(3,TimeUnit.SECONDS),"HTTP entered blocking state");
            resolver.offer(new PlaceResolver.Job(0,fix(2000,10)));
            check(newOffline.await(3,TimeUnit.SECONDS),"offline resolves while HTTP blocked");
            check(releaseOnline.getCount()==1,"offline did not wait for HTTP");
            resolver.invalidate(1);resolver.offer(new PlaceResolver.Job(0,fix(3000,20)));
        } finally { releaseOnline.countDown(); }
    }
}
