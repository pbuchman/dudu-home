package com.pbuchman.duduhome.trip;

import android.content.Context;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Process owner. Disk/counter and resolver have separate serial workers; UI only reads snapshots. */
public final class TripController {
    public record Snapshot(String state, long generation, double meters, long fixTime,
            PlaceResult place, String error, boolean loaded) {
        public boolean active() { return "ACTIVE".equals(state); }
        public boolean fresh() { return active() && fixTime > 0 && SystemClock.elapsedRealtime() - fixTime <= 10000; }
        public String title() { return fresh() ? place.title() : active() ? "Brak sygnału GPS" : "Gdzie jestem"; }
        public String subtitle() { return fresh() ? place.subtitle() : active() ? "Oczekiwanie na lokalizację" : ""; }
    }
    private static TripController instance;
    public static synchronized TripController get(Context context) {
        if (instance == null) instance = new TripController(context.getApplicationContext());
        return instance;
    }
    private static Thread background(Runnable work, String name) {
        return new Thread(() -> { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); work.run(); }, name);
    }
    private final ScheduledExecutorService counter = Executors.newSingleThreadScheduledExecutor(r -> background(r, "TripCounter"));
    private final ExecutorService resolver = Executors.newSingleThreadExecutor(r -> background(r, "TripPlaces"));
    private final ArrayBlockingQueue<TripFix> fixes = new ArrayBlockingQueue<>(64);
    private final AtomicBoolean draining = new AtomicBoolean(), resolving = new AtomicBoolean(), gap = new AtomicBoolean();
    private final AtomicReference<Job> latest = new AtomicReference<>();
    private record Job(long generation, TripFix fix) { }
    private final TripStore store;
    private final OfflinePlaces offline;
    private final PhotonPlaces photon;
    private TripDistance distance = new TripDistance(0);
    private String state = "OFF", error = "";
    private long generation, fixTime, savedAt;
    private PlaceResult place = PlaceResult.unknown(false);
    private PlaceStabilizer stabilizer = new PlaceStabilizer();
    private boolean loaded, storageFailed;
    private TripFix currentFix, resolvedFix, enrichmentFix;
    private volatile Snapshot snapshot = new Snapshot("OFF", 0, 0, 0, PlaceResult.unknown(false), "", false);
    private TripController(Context context) {
        store = new TripStore(context); offline = new OfflinePlaces(context); photon = new PhotonPlaces(context);
        resolver.execute(offline::prepare);
        counter.execute(() -> {
            try { TripStore.Saved saved = store.read(); state = saved.state(); generation = saved.generation(); distance = new TripDistance(saved.meters()); }
            catch (Exception invalid) { storageFailed = true; error = "Nie można odczytać zapisanej sesji"; }
            loaded = true; publish();
        });
        counter.scheduleWithFixedDelay(() -> {
            if ("ACTIVE".equals(state) && SystemClock.elapsedRealtime() - savedAt >= 5000) save();
            publish();
        }, 1, 1, TimeUnit.SECONDS);
    }
    public Snapshot snapshot() { return snapshot; }
    public void command(String command) {
        counter.execute(() -> {
            if (!loaded || storageFailed) return;
            String next = switch (command) {
                case "START" -> (state.equals("OFF") || state.equals("FINISHED")) ? "ACTIVE" : state;
                case "PAUSE" -> state.equals("ACTIVE") ? "PAUSED" : state;
                case "RESUME" -> state.equals("PAUSED") ? "ACTIVE" : state;
                case "END" -> (state.equals("ACTIVE") || state.equals("PAUSED")) ? "FINISHED" : state;
                default -> state;
            };
            if (next.equals(state)) return;
            if (command.equals("START")) distance = new TripDistance(0);
            state = next; generation++; distance.breakSegment(); fixes.clear(); latest.set(null);
            currentFix = null; resolvedFix = null; enrichmentFix = null; fixTime = 0; stabilizer = new PlaceStabilizer();
            place = PlaceResult.unknown(false); save(); publish();
        });
    }
    public void breakSegment() { gap.set(true); counter.execute(() -> { distance.breakSegment(); fixTime = 0; publish(); }); }
    public void offer(Location location) {
        if (!snapshot.active()) return;
        TripFix fix = new TripFix(location.getElapsedRealtimeNanos()/1000000, SystemClock.elapsedRealtime(),
                location.getLatitude(), location.getLongitude(), location.hasAccuracy() ? location.getAccuracy() : Double.POSITIVE_INFINITY,
                location.getSpeed(), location.hasSpeed(), location.getBearing(), location.hasBearing(), location.isFromMockProvider());
        if (!fixes.offer(fix)) { fixes.clear(); gap.set(true); fixes.offer(fix); }
        if (draining.compareAndSet(false, true)) counter.execute(this::drain);
    }
    private void drain() {
        try {
            TripFix fix;
            while ((fix = fixes.poll()) != null) {
                if (!state.equals("ACTIVE")) continue;
                if (gap.getAndSet(false)) distance.breakSegment();
                distance.accept(fix);
                if (!fix.valid()) { fixTime = 0; continue; }
                if (currentFix != null && fix.elapsed() <= currentFix.elapsed()) continue;
                currentFix = fix; fixTime = fix.elapsed();
                if (resolvedFix == null || resolvedFix.distance(fix) > 60) {
                    place = PlaceResult.unknown(false); stabilizer = new PlaceStabilizer();
                }
                latest.set(new Job(generation, fix));
                if (resolving.compareAndSet(false, true)) resolver.execute(this::resolve);
            }
        } finally {
            draining.set(false); publish();
            if (!fixes.isEmpty() && draining.compareAndSet(false, true)) counter.execute(this::drain);
        }
    }
    private void resolve() {
        try {
            Job job;
            while ((job = latest.getAndSet(null)) != null) {
                Job selected = job;
                PlaceResult local = offline.resolve(job.fix());
                postPlace(selected, local, false);
                if (!local.complete()) {
                    PlaceResult online = photon.resolve(job.fix());
                    if (online != null) postPlace(selected, PlaceResult.enrich(local, online), true);
                }
            }
        } finally {
            resolving.set(false);
            if (latest.get() != null && resolving.compareAndSet(false, true)) resolver.execute(this::resolve);
        }
    }
    private void postPlace(Job job, PlaceResult result, boolean online) {
        counter.execute(() -> {
            if (!state.equals("ACTIVE") || generation != job.generation() || currentFix == null
                    || currentFix.distance(job.fix()) > 60 || currentFix.elapsed() - job.fix().elapsed() > 10000) return;
            // Preserve nearby recent online enrichment field by field, while new local containment wins.
            PlaceResult evidence=result;
            if (result.source().equals("offline") && stabilizer.latest().source().equals("photon")
                    && enrichmentFix != null && enrichmentFix.distance(job.fix()) < 40
                    && job.fix().elapsed()-enrichmentFix.elapsed() <= 30000)
                evidence=PlaceResult.enrich(result,stabilizer.latest().onlineEvidence());
            place=stabilizer.offer(evidence,job.fix().elapsed());
            if (online) enrichmentFix=job.fix();
            resolvedFix = job.fix(); publish();
        });
    }
    private void save() {
        try { store.write(state, generation, distance.meters()); savedAt = SystemClock.elapsedRealtime(); }
        catch (Exception failed) { storageFailed = true; state = "PAUSED"; distance.breakSegment(); error = "Błąd zapisu sesji. Pomiar zatrzymany"; }
    }
    private void publish() { snapshot = new Snapshot(state, generation, distance.meters(), fixTime, place, error, loaded); }
    public String diagnostic() {
        Snapshot s = snapshot;
        return "trip_state=" + s.state() + " loaded=" + s.loaded() + " gps_fresh=" + s.fresh()
                + " map=" + offline.status() + " map_schema=" + offline.schemaVersion()
                + " locality_quality=" + s.place().localityQuality() + " street_quality=" + s.place().streetQuality()
                + " locality_source=" + s.place().localitySource() + " street_source=" + s.place().streetSource()
                + " storage_ok=" + s.error().isEmpty();
    }
}
