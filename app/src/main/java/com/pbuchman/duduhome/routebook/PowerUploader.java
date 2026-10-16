package com.pbuchman.duduhome.routebook;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Own scheduler/backoff/ACK domain; the GPS uploader cannot block power-only delivery. */
public final class PowerUploader implements AutoCloseable {
    private final PowerOutbox db; private final PrivateConfig config; private final Uploader.Transport transport;
    private final Consumer<String> report;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->new Thread(()->{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);r.run();},"power-upload"));
    private final AtomicBoolean queued=new AtomicBoolean();
    private volatile boolean closed;
    private long eligible; private int failures;
    private boolean newest=true;
    public PowerUploader(PowerOutbox db,PrivateConfig config,Uploader.Transport transport,Consumer<String> report){this.db=db;this.config=config;this.transport=transport;this.report=report;}
    public void kick(){if(!closed&&config!=null&&config.enabled&&queued.compareAndSet(false,true))worker.schedule(this::drain,Math.max(0,eligible-System.nanoTime()/1000000),TimeUnit.MILLISECONDS);}
    private void drain(){boolean more=false;
        try{for(int n=0;n<4&&!closed;n++){
            List<PowerEvent> batch=db.pending(newest,newest?1:200);if(batch.isEmpty())return;
            Uploader.Response response=transport.post(config,PowerProtocol.envelope(db.device(),newest?"latest":"backfill",batch));
            if(closed)return;
            if(response.status()!=200){boolean retry=response.status()==429||response.status()>=500;report.accept(retry?"POWER_UPLOAD_RETRY":"POWER_UPLOAD_BLOCKED_HTTP");eligible=System.nanoTime()/1000000+(retry?Protocol.retryMillis(++failures,response.retryAfter(),Math.random()*.2):900000);more=true;return;}
            List<Protocol.Result> ack;
            try{ack=PowerProtocol.ack(response.body(),db.device(),batch);}catch(RuntimeException invalid){report.accept("POWER_UPLOAD_INVALID_ACK");retry();more=true;return;}
            db.apply(ack);newest=!newest;failures=0;eligible=0;
            if(ack.stream().anyMatch(r->r.status().equals("rejected")))report.accept("POWER_UPLOAD_REJECTED");
        }more=!closed;eligible=System.nanoTime()/1000000+100;
        }catch(IOException failure){report.accept("POWER_UPLOAD_NETWORK_RETRY");retry();more=true;}
        catch(RuntimeException failure){report.accept("POWER_UPLOAD_STORAGE_DEGRADED");retry();more=true;}
        finally{queued.set(false);if(!closed){try{if(more||!db.pending(true,1).isEmpty())kick();}catch(RuntimeException failure){retry();kick();}}}
    }
    private void retry(){eligible=System.nanoTime()/1000000+Protocol.retryMillis(++failures,0,Math.random()*.2);}
    public void close(){closed=true;worker.shutdownNow();try{worker.awaitTermination(12,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    public boolean stopped(){return worker.isTerminated();}
}
