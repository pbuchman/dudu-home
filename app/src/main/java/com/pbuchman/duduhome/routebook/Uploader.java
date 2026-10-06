package com.pbuchman.duduhome.routebook;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class Uploader implements AutoCloseable {
    public record Response(int status,String body,long retryAfter) { }
    public interface Transport { Response post(PrivateConfig config,byte[] body) throws IOException; }
    private final Outbox db; private final PrivateConfig config; private final Transport transport;
    private final Consumer<String> report;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(task -> new Thread(() -> {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run();
    },"routebook-upload"));
    private final AtomicBoolean queued=new AtomicBoolean();
    private volatile boolean closed;
    private int failures; private long eligible;
    public Uploader(Outbox db,PrivateConfig config,Transport transport,Consumer<String> report) {
        this.db=db; this.config=config; this.transport=transport; this.report=report;
    }
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
                    report.accept(transientFailure?"UPLOAD_RETRY":"UPLOAD_BLOCKED_HTTP");
                    eligible=System.nanoTime()/1000000+(transientFailure?Protocol.retryMillis(++failures,response.retryAfter(),Math.random()*.2):900000);
                    more=true; return;
                }
                List<Protocol.Result> results;
                try { results=Protocol.ack(response.body(),db.device(),batch); }
                catch(RuntimeException invalid) { report.accept("UPLOAD_INVALID_ACK"); retry(0); more=true; return; }
                db.apply(results); for(Point p:batch) selection.attempted(p);
                failures=0; eligible=0;
                long rejected=results.stream().filter(r->r.status().equals("rejected")).count();
                if(rejected>0) report.accept("UPLOAD_REJECTED COUNT="+rejected);
            }
            more=!closed;
        } catch(IOException network) { report.accept("UPLOAD_NETWORK_RETRY"); retry(0); more=true; }
        catch(RuntimeException storage) { report.accept("UPLOAD_STORAGE_DEGRADED"); retry(0); more=true; }
        finally {
            queued.set(false);
            if(!closed) {
                try { if(more || !db.pending(true,1).isEmpty()) kick(); }
                catch(RuntimeException storage) { report.accept("UPLOAD_STORAGE_DEGRADED"); retry(0); kick(); }
            }
        }
    }
    private void retry(long after) { eligible=System.nanoTime()/1000000+Protocol.retryMillis(++failures,after,Math.random()*.2); }
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
