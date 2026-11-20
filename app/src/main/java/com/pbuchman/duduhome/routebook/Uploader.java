package com.pbuchman.duduhome.routebook;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class Uploader implements AutoCloseable {
    static final String UNKNOWN_COUNTS="PENDING=-1 REJECTED_INVALID=-1 REJECTED_CONFLICT=-1";
    public record Response(int status,String body,long retryAfter) { }
    public interface Transport { Response post(PrivateConfig config,byte[] body) throws IOException; }
    static final class DeliveryHealth {
        private final String categoryPrefix;
        private final Consumer<String> report;
        private final Supplier<String> readCounts;
        private final String unknownCounts;
        private String state;
        private String counts;
        private int status;
        private long waitMs, accepted, duplicate, terminal;

        DeliveryHealth(String categoryPrefix, PrivateConfig config, boolean configBlocked, Consumer<String> report,
                       Supplier<String> readCounts, String unknownCounts) {
            this.categoryPrefix=categoryPrefix;
            this.report=report;
            this.readCounts=readCounts;
            this.unknownCounts=unknownCounts;
            state=configBlocked?"CONFIG_BLOCKED":config!=null&&config.enabled?"ENABLED":"DISABLED";
            counts=readQueue();
        }

        // Called under the same database monitor as insert/apply, so an older count cannot win a race.
        private String readQueue() {
            try { return readCounts.get(); }
            catch(RuntimeException failure) {
                report.accept(categoryPrefix+"QUEUE_COUNTS_UNAVAILABLE");
                return unknownCounts;
            }
        }

        void inserted() {
            String current=readQueue();
            synchronized(this) { counts=current; }
        }

        synchronized void failure(String state, int status, long waitMs) {
            this.state=state;
            this.status=status;
            this.waitMs=waitMs;
            String http=status>=100&&status<=599?"HTTP_"+status:"NO_HTTP_STATUS";
            report.accept(categoryPrefix+state+" STATE="+http+" WAIT_MS="+waitMs);
        }

        void applied(List<Protocol.Result> results) {
            String current=readQueue();
            synchronized(this) { updateReceipt(results,current); }
        }

        private void updateReceipt(List<Protocol.Result> results,String current) {
            int acceptedNow=0, duplicateNow=0, rejectedNow=0;
            for(Protocol.Result result:results) {
                switch(result.status()) {
                    case "accepted" -> acceptedNow++;
                    case "duplicate" -> duplicateNow++;
                    case "rejected" -> rejectedNow++;
                }
            }
            accepted+=acceptedNow;
            duplicate+=duplicateNow;
            terminal+=rejectedNow;
            counts=current;
            state="UPLOAD_ACK";
            status=200;
            waitMs=0;
            if(acceptedNow>0) report.accept(categoryPrefix+"UPLOAD_ACK STATE=ACCEPTED COUNT="+accepted);
            if(duplicateNow>0) report.accept(categoryPrefix+"UPLOAD_ACK STATE=DUPLICATE COUNT="+duplicate);
            if(rejectedNow>0) report.accept(categoryPrefix+"UPLOAD_REJECTED COUNT="+terminal);
        }

        synchronized String snapshot(String stateKey, String prefix) {
            return counts+" "+stateKey+"="+state
                    +" "+prefix+"_http="+status+" "+prefix+"_wait_ms="+waitMs
                    +" "+prefix+"_accepted="+accepted+" "+prefix+"_duplicate="+duplicate
                    +" "+prefix+"_terminal="+terminal;
        }
    }
    private final Outbox db; private final PrivateConfig config; private final Transport transport;
    private final DeliveryHealth health;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(task -> new Thread(() -> {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run();
    },"routebook-upload"));
    private final AtomicBoolean queued=new AtomicBoolean();
    private volatile boolean closed;
    private int failures; private volatile long eligible;
    public Uploader(Outbox db,PrivateConfig config,Transport transport,Consumer<String> report) {
        this(db,config,transport,report,false);
    }
    Uploader(Outbox db,PrivateConfig config,Transport transport,Consumer<String> report,boolean configBlocked) {
        this.db=db; this.config=config; this.transport=transport;
        health=new DeliveryHealth("",config,configBlocked,report,db::counts,UNKNOWN_COUNTS);
    }
    String diagnostic() { return health.snapshot("upload","gps"); }
    void inserted() { health.inserted(); }
    public void kick() {
        if(closed||config==null||!config.enabled||!queued.compareAndSet(false,true)) return;
        worker.schedule(this::drain,Math.max(0,eligible-System.nanoTime()/1000000),TimeUnit.MILLISECONDS);
    }
    private void drain() {
        boolean more=false; DeliverySelection selection=new DeliverySelection();
        try {
            for(int requests=0;requests<10&&!closed;requests++) {
                List<Point> latest=db.pending(true,1); if(latest.isEmpty()) return;
                boolean live=selection.latest(latest.get(0));
                List<Point> batch=live?latest:db.pending(false,200);
                byte[] body=Protocol.envelope(db.device(),live?"latest":"backfill",batch);
                Response response=transport.post(config,body);
                if(closed) return; // Lost response remains safely pending.
                if(response.status()!=200) {
                    boolean transientFailure=response.status()==429||response.status()>=500;
                    long wait=transientFailure?Protocol.retryMillis(++failures,response.retryAfter(),Math.random()*.2):900000;
                    eligible=System.nanoTime()/1000000+wait;
                    health.failure(transientFailure?"UPLOAD_RETRY":"UPLOAD_BLOCKED_HTTP",response.status(),wait);
                    more=true; return;
                }
                List<Protocol.Result> results;
                try { results=Protocol.ack(response.body(),db.device(),batch); }
                catch(RuntimeException invalid) { health.failure("UPLOAD_INVALID_ACK",200,retry()); more=true; return; }
                synchronized(db) {
                    db.apply(results);
                    health.applied(results);
                }
                for(Point p:batch) selection.attempted(p);
                failures=0; eligible=0;
            }
            more=!closed;
        } catch(IOException network) { health.failure("UPLOAD_NETWORK_RETRY",0,retry()); more=true; }
        catch(RuntimeException storage) { health.failure("UPLOAD_STORAGE_DEGRADED",0,retry()); more=true; }
        finally {
            queued.set(false);
            if(!closed) {
                try { if(more || !db.pending(true,1).isEmpty()) kick(); }
                catch(RuntimeException storage) { health.failure("UPLOAD_STORAGE_DEGRADED",0,retry()); kick(); }
            }
        }
    }
    private long retry() {
        long wait=Protocol.retryMillis(++failures,0,Math.random()*.2);
        eligible=System.nanoTime()/1000000+wait;
        return wait;
    }
    @Override public void close() {
        closed=true; worker.shutdownNow();
        try { worker.awaitTermination(12,TimeUnit.SECONDS); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    public boolean stopped() { return worker.isTerminated(); }
    public static Response https(PrivateConfig config,byte[] body) throws IOException {
        HttpsURLConnection connection=(HttpsURLConnection)new URL(config.endpoint).openConnection();
        // Total deadline also closes stalled/trickling sockets. Scheduler is separate from disk/HTTP workers.
        ScheduledExecutorService deadline=Executors.newSingleThreadScheduledExecutor();
        Future<?> abort=deadline.schedule(connection::disconnect,10,TimeUnit.SECONDS);
        try {
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
            connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setFixedLengthStreamingMode(body.length);
            connection.setRequestProperty("Content-Type","application/json"); connection.setRequestProperty("Authorization","Bearer "+config.token);
            try(OutputStream out=connection.getOutputStream()) { out.write(body); }
            int status=connection.getResponseCode(); long after=0;
            String header=connection.getHeaderField("Retry-After");
            if(header!=null&&header.matches("[0-9]{1,9}")) after=Long.parseLong(header);
            if(status!=200) return new Response(status,"",after);
            try(InputStream in=connection.getInputStream()) { return new Response(status,PrivateConfig.utf8(PrivateConfig.bounded(in,262144)),after); }
        } finally { abort.cancel(false); deadline.shutdownNow(); connection.disconnect(); }
    }
}
