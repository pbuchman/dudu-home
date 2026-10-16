package com.pbuchman.duduhome.trip;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Latest-only offline work never waits for optional HTTP. Each lane has at most one queued fix. */
final class PlaceResolver implements AutoCloseable {
    record Job(long generation, TripFix fix) { }
    interface Completion { void accept(Job job, PlaceResult result, boolean online, long durationNanos); }
    private final ExecutorService offlineWorker, onlineWorker;
    private final AtomicReference<Job> latest = new AtomicReference<>(), latestOnline = new AtomicReference<>();
    private final AtomicBoolean draining = new AtomicBoolean(), enriching = new AtomicBoolean();
    private final Function<TripFix, PlaceResult> offline, online;
    private final Completion completion;
    private volatile long minimumGeneration;
    private volatile boolean closed;

    PlaceResolver(Function<TripFix, PlaceResult> offline, Function<TripFix, PlaceResult> online,
            Completion completion, ThreadFactory offlineThreads, ThreadFactory onlineThreads) {
        this.offline=offline; this.online=online; this.completion=completion;
        offlineWorker=Executors.newSingleThreadExecutor(offlineThreads);
        onlineWorker=Executors.newSingleThreadExecutor(onlineThreads);
    }
    void offer(Job job) {
        if (closed || job.generation()<minimumGeneration) return;
        latest.set(job);
        if (draining.compareAndSet(false,true)) offlineWorker.execute(this::drain);
    }
    private void drain() {
        try {
            Job job;
            while (!closed && (job=latest.getAndSet(null))!=null) {
                if (job.generation()<minimumGeneration) continue;
                long started=System.nanoTime();
                PlaceResult result=offline.apply(job.fix());
                completion.accept(job,result,false,System.nanoTime()-started);
                if (!closed && job.generation()>=minimumGeneration && !result.complete()) {
                    latestOnline.set(job);
                    if (enriching.compareAndSet(false,true)) onlineWorker.execute(this::enrich);
                }
            }
        } finally {
            draining.set(false);
            if (!closed && latest.get()!=null && draining.compareAndSet(false,true)) offlineWorker.execute(this::drain);
        }
    }
    private void enrich() {
        try {
            Job job;
            while (!closed && (job=latestOnline.getAndSet(null))!=null) {
                if (job.generation()<minimumGeneration) continue;
                long started=System.nanoTime();
                PlaceResult result=online.apply(job.fix());
                completion.accept(job,result,true,System.nanoTime()-started);
            }
        } finally {
            enriching.set(false);
            if (!closed && latestOnline.get()!=null && enriching.compareAndSet(false,true)) onlineWorker.execute(this::enrich);
        }
    }
    void invalidate(long generation) { minimumGeneration=generation;latest.set(null);latestOnline.set(null); }
    static boolean currentOnline(TripFix response,TripFix latestLocal,TripFix currentFix) {
        return latestLocal!=null && currentFix!=null && response.elapsed()==latestLocal.elapsed()
                && response.elapsed()==currentFix.elapsed();
    }
    @Override public void close() { closed=true;offlineWorker.shutdownNow();onlineWorker.shutdownNow(); }
}
