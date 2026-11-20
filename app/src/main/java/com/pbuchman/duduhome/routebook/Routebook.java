package com.pbuchman.duduhome.routebook;

import android.content.Context;
import android.location.Location;
import android.os.SystemClock;
import com.pbuchman.duduhome.diagnostics.Diagnostics;
import java.io.File;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;

/** Service-scoped collector. No UI, location subscription, automation lease or manual trip dependency. */
public final class Routebook implements AutoCloseable {
    private static final long JOURNAL_INTERVAL_MS=30000;
    private final Context context;
    private final ThreadPoolExecutor disk=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(256), task -> new Thread(() -> {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run();
    },"routebook-disk"));
    private final AtomicBoolean missed=new AtomicBoolean();
    private volatile String diagnostic="STARTING", device="";
    private final Map<String,Long> lastJournal=new HashMap<>();
    private final Map<CollectorFilter.Outcome,Long> outcomes=new EnumMap<>(CollectorFilter.Outcome.class);
    private long callbacks, committed, discarded, dropped;
    private String lastOutcome="NONE";
    private volatile boolean closed;
    private Outbox db;
    private CollectorFilter filter;
    private volatile Uploader uploader;
    private volatile PowerArchive power;
    private final Object powerLock=new Object();
    private final ArrayBlockingQueue<PowerEvent> powerStarting=new ArrayBlockingQueue<>(128);
    public Routebook(Context context) {
        this.context=context.getApplicationContext();
        disk.execute(()-> {
            try {
                db=new Outbox(new File(this.context.getNoBackupFilesDir(),"routebook.sqlite"));
                device=db.device();
            } catch(RuntimeException failure) {
                report("STORAGE_DEGRADED");
                // A previously established power identity can drain independently of a failed GPS DB.
                try{device=PowerOutbox.existingDevice(new File(this.context.getNoBackupFilesDir(),"radio-power.sqlite"));}
                catch(RuntimeException unavailable){journal("POWER_IDENTITY_UNAVAILABLE");}
            }
            if(Point.uuid(device)) {
                PrivateConfig config=null;
                boolean configBlocked=false;
                try { config=PrivateConfig.load(this.context.getNoBackupFilesDir(),device); }
                catch(Exception bad) { configBlocked=true; journal("CONFIG_BLOCKED"); }
                synchronized(powerLock){
                    power=new PowerArchive(this.context.getNoBackupFilesDir(),device,config,configBlocked,this::journal);
                    PowerEvent event;while((event=powerStarting.poll())!=null)power.offer(event);
                }
                if(db==null)return;
                try {
                    filter=new CollectorFilter(db.highWater(),config==null?CollectorFilter.DEFAULTS:config.limits);
                    uploader=new Uploader(db,config,Uploader::https,this::journal,configBlocked);
                    report("COLLECTION_READY");
                    uploader.kick();
                } catch(RuntimeException failure) { report("STORAGE_DEGRADED"); }
            }
        });
    }
    private void report(String category) { diagnostic=category; journal(category); }

    private void journal(String category) {
        String key=category.split(" ",2)[0];
        if(key.endsWith("UPLOAD_ACK")) {
            key=category.substring(0,category.lastIndexOf(" COUNT="));
        }
        journal(key,category);
    }

    private synchronized void journal(String key,String category) {
        long now=SystemClock.elapsedRealtime();
        Long previous=lastJournal.get(key);
        if(previous!=null && now-previous<JOURNAL_INTERVAL_MS)return;
        lastJournal.put(key,now);
        Diagnostics.record(context,"ROUTEBOOK_"+category);
    }

    private synchronized void callback() {
        callbacks++;
        journal("GPS_CALLBACK","GPS_CALLBACK COUNT="+callbacks);
    }

    private synchronized void filtered(CollectorFilter.Outcome outcome) {
        lastOutcome=outcome.name();
        if(outcome!=CollectorFilter.Outcome.ACCEPTED)discarded++;
        long total=outcomes.merge(outcome,1L,Long::sum);
        String key="GPS_FILTER REASON="+outcome;
        journal(key,key+" COUNT="+total);
    }

    private synchronized void stored() {
        committed++;
        journal("GPS_COMMIT","GPS_COMMIT COUNT="+committed);
    }

    private synchronized void dropped() {
        dropped++;
        lastOutcome="QUEUE_FULL";
        journal("GPS_DROPPED","GPS_DROPPED COUNT="+dropped);
    }

    public void offer(Location location) {
        offer(location,SystemClock.elapsedRealtime(),System.currentTimeMillis());
    }

    public void offer(Location location,long nowMono,long nowWall) {
        offer(location,nowMono,nowWall,null);
    }

    /** The optional observer receives the monotonic completion time on the SQLite worker. */
    public void offer(Location location,long nowMono,long nowWall,LongConsumer commitObserver) {
        if(closed)return;
        callback();
        // Copy values on callback thread. Persist and validate on disk worker after automation opportunity.
        CollectorFilter.Fix fix=new CollectorFilter.Fix(location.getTime(),location.getElapsedRealtimeNanos()/1000000,
            location.getLatitude(),location.getLongitude(),location.hasAccuracy()?location.getAccuracy():Double.NaN,
            location.hasSpeed()?(double)location.getSpeed():null,location.isFromMockProvider());

        if(!enqueue(()-> {
            if(filter==null||db==null) { report("STORAGE_DEGRADED"); return; }
            if(missed.getAndSet(false)) { filter.breakSegment(); report("COLLECTION_QUEUE_DEGRADED"); }
            long committedAt=-1;
            try {
                Point p=filter.accept(fix,nowMono,nowWall);
                filtered(filter.outcome());
                if(p!=null) {
                    synchronized(db) {
                        db.insert(p);
                        if(commitObserver!=null)committedAt=SystemClock.elapsedRealtime();
                        stored();
                        if(uploader!=null)uploader.inserted();
                    }
                    diagnostic="COLLECTION_READY";
                }
                if(uploader!=null) uploader.kick();
            } catch(RuntimeException failure) { filter.breakSegment(); report("STORAGE_DEGRADED"); }
            finally {
                if(commitObserver!=null&&committedAt>=0) {
                    try { commitObserver.accept(committedAt); }
                    catch(RuntimeException ignored) { /* Diagnostics cannot change a durable write. */ }
                }
            }
        }) && !closed)dropped();
    }
    private boolean enqueue(Runnable task) {
        if(closed) return false;
        try {
            disk.execute(task);
            return true;
        } catch(RejectedExecutionException full) {
            if(!closed) {
                missed.set(true);
                report("COLLECTION_QUEUE_DEGRADED");
            }
            return false;
        }
    }
    public void breakSegment() { enqueue(()-> { if(filter!=null) filter.breakSegment(); }); }
    public void offerPower(PowerEvent event) {
        synchronized(powerLock){
            if(closed)return;
            if(power!=null)power.offer(event);
            else if(!powerStarting.offer(event))journal("POWER_QUEUE_DEGRADED");
        }
    }

    private synchronized String filterDiagnostic() {
        return "routebook_filter=ACTIVE routebook_reason="+lastOutcome
                +" routebook_callbacks="+callbacks+" routebook_committed="+committed
                +" routebook_discarded="+discarded+" routebook_dropped="+dropped;
    }

    public String diagnostic() {
        Uploader upload=uploader;
        PowerArchive powerArchive=power;
        return "routebook="+diagnostic+" "
                +(upload==null?"upload=UNAVAILABLE "+Uploader.UNKNOWN_COUNTS:upload.diagnostic())
                +"\n"+filterDiagnostic()
                +"\n"+(powerArchive==null?"routebook_power=STARTING power_upload=UNAVAILABLE":powerArchive.diagnostic())
                // The standalone provisioning line is excluded from the safe diagnostic exporter.
                +"\nroutebook_identity device_id="+device;
    }
    @Override public void close() {
        closed=true;
        // Finish queued durable writes; uploader must stop before the shared SQLite handle closes.
        disk.shutdown();
        Thread cleanup=new Thread(()-> {
            try { disk.awaitTermination(15,TimeUnit.SECONDS); }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            if(uploader!=null) uploader.close();
            if(power!=null) power.close();
            if(disk.isTerminated() && (uploader==null||uploader.stopped()) && db!=null) db.close();
        },"routebook-cleanup"); cleanup.start();
    }
}
