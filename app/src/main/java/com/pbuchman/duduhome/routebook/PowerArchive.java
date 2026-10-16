package com.pbuchman.duduhome.routebook;

import java.io.File;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Own bounded disk queue and handle; failures here cannot mutate or pause the GPS outbox. */
final class PowerArchive implements AutoCloseable {
    private final ThreadPoolExecutor disk=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(128),r->new Thread(()->{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);r.run();},"power-disk"));
    private final Consumer<String> report;
    private PowerOutbox db;private PowerUploader uploader;
    private volatile boolean closed;
    private volatile String counts="POWER_STARTING";
    PowerArchive(File dir,String device,PrivateConfig config,Consumer<String> report){this.report=report;disk.execute(()->{try{
        db=new PowerOutbox(new File(dir,"radio-power.sqlite"),device);uploader=new PowerUploader(db,config,Uploader::https,report);counts=db.counts();uploader.kick();
    }catch(RuntimeException failure){report.accept("POWER_STORAGE_DEGRADED");}});}
    void offer(PowerEvent event){if(closed)return;try{disk.execute(()->{try{if(db!=null){db.insert(event);counts=db.counts();if(uploader!=null)uploader.kick();}}catch(RuntimeException failure){report.accept("POWER_STORAGE_DEGRADED");}});}catch(RejectedExecutionException full){report.accept("POWER_QUEUE_DEGRADED");}}
    String diagnostic(){return counts;}
    public void close(){closed=true;disk.shutdown();new Thread(()->{try{disk.awaitTermination(15,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}if(uploader!=null)uploader.close();if(disk.isTerminated()&&(uploader==null||uploader.stopped())&&db!=null)db.close();},"power-cleanup").start();}
}
