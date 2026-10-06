package com.pbuchman.duduhome.routebook;

import android.content.Context;
import android.location.Location;
import android.os.SystemClock;
import com.pbuchman.duduhome.diagnostics.Diagnostics;
import java.io.File;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Service-scoped collector. No UI, location subscription, automation lease or manual trip dependency. */
public final class Routebook implements AutoCloseable {
    private final Context context;
    private final ThreadPoolExecutor disk=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(256), task -> new Thread(() -> {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run();
    },"routebook-disk"));
    private final AtomicBoolean missed=new AtomicBoolean();
    private volatile String diagnostic="ROUTEBOOK_STARTING", uploadState="DISABLED", counts="", device="";
    private volatile boolean closed;
    private Outbox db; private CollectorFilter filter; private Uploader uploader;
    public Routebook(Context context) {
        this.context=context.getApplicationContext();
        disk.execute(()-> {
            try {
                db=new Outbox(new File(this.context.getNoBackupFilesDir(),"routebook.sqlite"));
                device=db.device();
                PrivateConfig config=null;
                try { config=PrivateConfig.load(this.context.getNoBackupFilesDir(),db.device()); }
                catch(Exception bad) { uploadState="CONFIG_BLOCKED"; report("CONFIG_BLOCKED"); }
                if(config!=null) uploadState=config.enabled?"ENABLED":"DISABLED";
                filter=new CollectorFilter(db.highWater(),config==null?CollectorFilter.DEFAULTS:config.limits);
                uploader=new Uploader(db,config,Uploader::https,category->{ uploadState=category; report(category); });
                diagnostic="COLLECTION_READY UPLOAD="+(config!=null&&config.enabled?"ENABLED":"DISABLED");
                uploader.kick();
            } catch(RuntimeException failure) { report("STORAGE_DEGRADED"); }
        });
    }
    private void report(String category) { diagnostic=category; Diagnostics.record(context,"ROUTEBOOK_"+category); }
    public void offer(Location location) {
        // Copy values on callback thread. Persist and validate on disk worker after automation opportunity.
        CollectorFilter.Fix fix=new CollectorFilter.Fix(location.getTime(),location.getElapsedRealtimeNanos()/1000000,
            location.getLatitude(),location.getLongitude(),location.hasAccuracy()?location.getAccuracy():Double.NaN,
            location.hasSpeed()?(double)location.getSpeed():null,location.isFromMockProvider());

        enqueue(()-> {
            if(filter==null||db==null) { report("STORAGE_DEGRADED"); return; }
            if(missed.getAndSet(false)) { filter.breakSegment(); report("COLLECTION_QUEUE_DEGRADED"); }
            try {
                Point p=filter.accept(fix,SystemClock.elapsedRealtime(),System.currentTimeMillis());
                if(p!=null) { db.insert(p); diagnostic="COLLECTION_READY"; counts=db.counts(); }
                if(uploader!=null) uploader.kick();
            } catch(RuntimeException failure) { filter.breakSegment(); report("STORAGE_DEGRADED"); }
        });
    }
    private void enqueue(Runnable task) {
        if(closed) return;
        try { disk.execute(task); } catch(RejectedExecutionException full) { missed.set(true); diagnostic="COLLECTION_QUEUE_DEGRADED"; }
    }
    public void breakSegment() { enqueue(()-> { if(filter!=null) filter.breakSegment(); }); }
    public String diagnostic() { return "routebook="+diagnostic+" upload="+uploadState+" "+counts+" device_id="+device; }
    @Override public void close() {
        closed=true;
        // Finish queued durable writes; uploader must stop before the shared SQLite handle closes.
        disk.shutdown();
        Thread cleanup=new Thread(()-> {
            try { disk.awaitTermination(15,TimeUnit.SECONDS); }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            if(uploader!=null) uploader.close();
            if(disk.isTerminated() && (uploader==null||uploader.stopped()) && db!=null) db.close();
        },"routebook-cleanup"); cleanup.start();
    }
}
