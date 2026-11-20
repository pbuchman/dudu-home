package com.pbuchman.duduhome.routebook;

import java.io.File;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Own bounded disk queue and handle; failures here cannot mutate or pause the GPS outbox. */
final class PowerArchive implements AutoCloseable {
    private final ThreadPoolExecutor disk=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(128),r->new Thread(()->{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);r.run();},"power-disk"));
    private final Consumer<String> report;
    private PowerOutbox db;private volatile PowerUploader uploader;
    private volatile boolean closed;
    private volatile String state="STARTING";
    PowerArchive(File dir,String device,PrivateConfig config,boolean configBlocked,Consumer<String> report) {
        this.report=report;
        disk.execute(()->{
            try {
                db=new PowerOutbox(new File(dir,"radio-power.sqlite"),device);
                uploader=new PowerUploader(db,config,Uploader::https,report,configBlocked);
                state="READY";
                uploader.kick();
            } catch(RuntimeException failure) {degraded("STORAGE_DEGRADED");}
        });
    }

    void offer(PowerEvent event) {
        if(closed)return;
        try {
            disk.execute(()->{
                try {
                    if(db==null)return;
                    synchronized(db) {
                        db.insert(event);
                        if(uploader!=null)uploader.inserted();
                    }
                    state="READY";
                    if(uploader!=null)uploader.kick();
                } catch(RuntimeException failure) {degraded("STORAGE_DEGRADED");}
            });
        } catch(RejectedExecutionException full) {if(!closed)degraded("QUEUE_DEGRADED");}
    }

    private void degraded(String reason) {
        state=reason;
        report.accept("POWER_"+reason);
    }

    String diagnostic() {
        PowerUploader upload=uploader;
        return "routebook_power="+state+" "
                +(upload==null?"power_upload=UNAVAILABLE "+PowerUploader.UNKNOWN_COUNTS:upload.diagnostic());
    }
    public void close(){closed=true;disk.shutdown();new Thread(()->{try{disk.awaitTermination(15,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}if(uploader!=null)uploader.close();if(disk.isTerminated()&&(uploader==null||uploader.stopped())&&db!=null)db.close();},"power-cleanup").start();}
}
