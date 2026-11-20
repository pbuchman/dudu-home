package com.pbuchman.duduhome.routebook;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Own scheduler/backoff/ACK domain; the GPS uploader cannot block power-only delivery. */
public final class PowerUploader implements AutoCloseable {
    static final String UNKNOWN_COUNTS="POWER_PENDING=-1 POWER_REJECTED=-1";
    private final PowerOutbox db; private final PrivateConfig config; private final Uploader.Transport transport;
    private final Uploader.DeliveryHealth health;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->new Thread(()->{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);r.run();},"power-upload"));
    private final AtomicBoolean queued=new AtomicBoolean();
    private volatile boolean closed;
    private volatile long eligible; private int failures;
    private boolean newest=true;
    public PowerUploader(PowerOutbox db,PrivateConfig config,Uploader.Transport transport,Consumer<String> report) {
        this(db,config,transport,report,false);
    }
    PowerUploader(PowerOutbox db,PrivateConfig config,Uploader.Transport transport,Consumer<String> report,boolean configBlocked) {
        this.db=db;this.config=config;this.transport=transport;
        health=new Uploader.DeliveryHealth("POWER_",config,configBlocked,report,db::counts,UNKNOWN_COUNTS);
    }
    String diagnostic() { return health.snapshot("power_upload","power"); }
    void inserted() { health.inserted(); }
    public void kick(){if(!closed&&config!=null&&config.enabled&&queued.compareAndSet(false,true))worker.schedule(this::drain,Math.max(0,eligible-System.nanoTime()/1000000),TimeUnit.MILLISECONDS);}
    private void drain(){boolean more=false;
        try{for(int n=0;n<4&&!closed;n++){
            List<PowerEvent> batch=db.pending(newest,newest?1:200);if(batch.isEmpty())return;
            Uploader.Response response=transport.post(config,PowerProtocol.envelope(db.device(),newest?"latest":"backfill",batch));
            if(closed)return;
            if(response.status()!=200) {
                boolean retry=response.status()==429||response.status()>=500;
                long wait=retry?Protocol.retryMillis(++failures,response.retryAfter(),Math.random()*.2):900000;
                eligible=System.nanoTime()/1000000+wait;
                health.failure(retry?"UPLOAD_RETRY":"UPLOAD_BLOCKED_HTTP",response.status(),wait);
                more=true;return;
            }
            List<Protocol.Result> ack;
            try{ack=PowerProtocol.ack(response.body(),db.device(),batch);}
            catch(RuntimeException invalid){health.failure("UPLOAD_INVALID_ACK",200,retry());more=true;return;}
            synchronized(db) {
                db.apply(ack);
                health.applied(ack);
            }
            newest=!newest;failures=0;eligible=0;
        }more=!closed;eligible=System.nanoTime()/1000000+100;
        }catch(IOException failure){health.failure("UPLOAD_NETWORK_RETRY",0,retry());more=true;}
        catch(RuntimeException failure){health.failure("UPLOAD_STORAGE_DEGRADED",0,retry());more=true;}
        finally{queued.set(false);if(!closed){try{if(more||!db.pending(true,1).isEmpty())kick();}catch(RuntimeException failure){health.failure("UPLOAD_STORAGE_DEGRADED",0,retry());kick();}}}
    }
    private long retry() {
        long wait=Protocol.retryMillis(++failures,0,Math.random()*.2);
        eligible=System.nanoTime()/1000000+wait;
        return wait;
    }
    public void close(){closed=true;worker.shutdownNow();try{worker.awaitTermination(12,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    public boolean stopped(){return worker.isTerminated();}
}
