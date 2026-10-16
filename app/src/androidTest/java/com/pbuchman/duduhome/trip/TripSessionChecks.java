package com.pbuchman.duduhome.trip;

import android.content.Context;
import android.content.ContextWrapper;
import android.location.Location;
import android.os.SystemClock;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.lang.reflect.*;

/** Deterministic session persistence and stale-work checks; never supplies GPS to automation. */
final class TripSessionChecks {
    private static void check(boolean ok,String why) { if(!ok)throw new AssertionError(why); }
    private static Object field(Object target,String name)throws Exception {
        Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);
    }
    private static void settle(TripController c)throws Exception {
        ((ExecutorService)field(c,"counter")).submit(()->{}).get(3,TimeUnit.SECONDS);
    }
    private static void close(TripController c)throws Exception {
        ((ExecutorService)field(c,"counter")).shutdownNow();((AutoCloseable)field(c,"resolver")).close();
    }
    private static com.pbuchman.duduhome.routebook.PowerEvent power(String kind){
        return new com.pbuchman.duduhome.routebook.PowerEvent(new java.util.UUID(1,1).toString(),new java.util.UUID(1,2).toString(),0,
                "2026-10-16T10:00:00.000Z",SystemClock.elapsedRealtime(),new java.util.UUID(1,3).toString(),kind,0,0,"synthetic-audit-v1","synthetic-build");
    }
    static void run(Context context)throws Exception {
        File directory=new File(context.getCacheDir(),"trip-session-checks");directory.mkdirs();
        Context isolated=new ContextWrapper(context) { @Override public File getNoBackupFilesDir(){return directory;} };
        File file=new File(directory,"trip-state.json");file.delete();
        TripStore disk=new TripStore(isolated);
        check(disk.read().state().equals("PAUSED") && !disk.read().partialTime(),"missing state is paused zero complete");
        for(String old:new String[]{"OFF","PAUSED","FINISHED","ACTIVE"}) {
            Files.write(file.toPath(),("{\"state\":\""+old+"\",\"generation\":7,\"meters\":1234.5}").getBytes(StandardCharsets.UTF_8));
            TripStore.Saved migrated=disk.read();
            check(migrated.state().equals(old.equals("ACTIVE")?"ACTIVE":"PAUSED") && migrated.meters()==1234.5
                    && migrated.millis()==0 && migrated.partialTime(),"legacy migration preserves distance/activity and marks unknown time");
            disk.write(migrated.state(),8,migrated.meters(),2500,migrated.partialTime());
            check(disk.read().millis()==2500 && disk.read().partialTime(),"partial time flag survives checkpoint/restart");
        }
        class MemoryStore extends TripStore {
            Saved saved=new Saved("ACTIVE",4,1200,60000,true);
            boolean fail;
            MemoryStore(){super(isolated);}
            @Override Saved read(){return saved;}
            @Override void write(String state,long generation,double meters,long millis,boolean partial)throws Exception {
                if(fail)throw new java.io.IOException("synthetic disk failure");
                saved=new Saved(state,generation,meters,millis,partial);
            }
        }
        MemoryStore memory=new MemoryStore();TripController controller=new TripController(isolated,memory);
        try {
            settle(controller);
            check(controller.snapshot().active() && controller.snapshot().partialTime(),"active legacy restores active");
            // Hold the counter so a pre-reset GPS envelope cannot be processed until Reset is queued.
            CountDownLatch blocked=new CountDownLatch(1), release=new CountDownLatch(1);
            ((ExecutorService)field(controller,"counter")).execute(()->{blocked.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            check(blocked.await(3,TimeUnit.SECONDS),"counter barrier");
            Location location=new Location("gps");location.setLatitude(0);location.setLongitude(0);location.setAccuracy(3);
            location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());controller.offer(location);
            long oldGeneration=(long)field(controller,"placeGeneration");
            long oldEpoch=((java.util.concurrent.atomic.AtomicLong)field(controller,"inputEpoch")).get();
            controller.observePower(power("snapshot"),RadioProfile.State.OFF);
            controller.command("RESET");
            check(controller.snapshot().meters()==1200 && controller.snapshot().millis()==60000,"pending reset cannot claim success before disk");
            release.countDown();settle(controller);
            check(controller.snapshot().active() && controller.snapshot().meters()==0 && controller.snapshot().millis()==0
                    && !controller.snapshot().partialTime() && memory.saved.meters()==0 && memory.saved.millis()==0,"active reset durable and queued sample discarded");
            check(((RadioTimeGate)field(controller,"radioTimeGate")).state(SystemClock.elapsedRealtime())==RadioProfile.State.UNKNOWN,"queued OFF from previous generation cannot alter reset session");
            // A producer that captured an old epoch can finish enqueueing after Reset clears the queue.
            Class<?> inputType=Class.forName(TripController.class.getName()+"$Input");
            Constructor<?> inputConstructor=inputType.getDeclaredConstructors()[0];inputConstructor.setAccessible(true);
            long received=SystemClock.elapsedRealtime();
            Object lateInput=inputConstructor.newInstance(oldEpoch,new TripFix(received,received,0,0,3,0,false,0,false,false),SystemClock.uptimeMillis());
            Method enqueue=TripController.class.getDeclaredMethod("enqueue",inputType);enqueue.setAccessible(true);enqueue.invoke(controller,lateInput);settle(controller);
            check(controller.snapshot().fixTime()==0,"late pre-reset producer envelope discarded");
            // A resolver result computed before Reset must not revive the previous place.
            Class<?> jobType=PlaceResolver.Job.class;
            Constructor<?> constructor=jobType.getDeclaredConstructors()[0];constructor.setAccessible(true);
            long now=SystemClock.elapsedRealtime();
            Object job=constructor.newInstance(oldGeneration,new TripFix(now,now,0,0,3,0,false,0,false,false));
            Method post=TripController.class.getDeclaredMethod("postPlace",jobType,PlaceResult.class,boolean.class,TripFix.class);post.setAccessible(true);
            ((ExecutorService)field(controller,"counter")).submit(()->{
                try {Field current=TripController.class.getDeclaredField("currentFix");current.setAccessible(true);
                    current.set(controller,new TripFix(now,now,0,0,3,0,false,0,false,false));
                }catch(Exception failure){throw new AssertionError(failure);}
            }).get(3,TimeUnit.SECONDS);
            post.invoke(controller,job,new PlaceResult("Stara Miejscowość Testowa","Stara Ulica Testowa","offline",true,true),false,null);settle(controller);
            check(controller.snapshot().place().locality().isEmpty() && controller.snapshot().fixTime()==0
                    && controller.snapshot().meters()==0 && controller.snapshot().millis()==0,"stale resolver cannot restore pre-reset state");
            controller.command("PAUSE");settle(controller);controller.command("RESET");settle(controller);
            check(!controller.snapshot().active() && memory.saved.state().equals("PAUSED"),"paused reset retains paused");
            controller.observePower(power("snapshot"),RadioProfile.State.ON);settle(controller);
            check(!controller.snapshot().active()&&controller.snapshot().millis()==0,"known ON never resumes explicitly paused manual session");
            controller.command("START");settle(controller);check(controller.snapshot().active(),"Start after Pause");
        } finally {close(controller);}
        for(String command:new String[]{"RESET","PAUSE"}) {
            MemoryStore failed=new MemoryStore();TripController c=new TripController(isolated,failed);
            try {
                settle(c);failed.fail=true;c.command(command);settle(c);
                check(c.snapshot().active() && c.snapshot().meters()==1200 && c.snapshot().millis()==60000
                        && c.snapshot().partialTime() && !c.snapshot().error().isEmpty(),"failed "+command+" preserves old totals/state and reports error");
                check(failed.saved.generation()==4,"failed transition cannot change durable state");
            }finally{close(c);}
        }
        file.delete();directory.delete();
    }
}
