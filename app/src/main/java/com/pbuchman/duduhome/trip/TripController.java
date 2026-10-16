package com.pbuchman.duduhome.trip;

import android.content.Context;
import android.location.Location;
import android.os.SystemClock;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Process owner. Disk/counter and resolver have separate serial workers; UI only reads snapshots. */
public final class TripController {
    public record Snapshot(String state, long generation, double meters, long fixTime,
            PlaceResult place, String error, boolean loaded, long millis, boolean partialTime, long placeGeneration, TripFix sourceFix) {
        public Snapshot(String state, long generation, double meters, long fixTime, PlaceResult place, String error, boolean loaded) {
            this(state, generation, meters, fixTime, place, error, loaded, 0, false);
        }
        public Snapshot(String state,long generation,double meters,long fixTime,PlaceResult place,String error,boolean loaded,long millis,boolean partialTime) {
            this(state,generation,meters,fixTime,place,error,loaded,millis,partialTime,-1,null);
        }
        public boolean active() { return "ACTIVE".equals(state); }
        public boolean fresh() { return active() && fixTime > 0 && SystemClock.elapsedRealtime() >= fixTime && SystemClock.elapsedRealtime() - fixTime <= 10000; }
        public String title() { return fresh() ? place().title() : active() ? "Brak sygnału GPS" : "Gdzie jestem"; }
        public String subtitle() { return fresh() ? place().subtitle() : active() ? "Oczekiwanie na lokalizację" : ""; }
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
    private final ArrayBlockingQueue<Input> fixes = new ArrayBlockingQueue<>(64);
    private final AtomicBoolean draining = new AtomicBoolean(), gap = new AtomicBoolean();
    private record Input(long epoch, TripFix fix, long uptime) { }
    private final AtomicLong inputEpoch = new AtomicLong();
    private final AtomicLong observationFloor = new AtomicLong(SystemClock.elapsedRealtime());
    private final TripStore store;
    private final OfflinePlaces offline;
    private final PhotonPlaces photon;
    private final PlaceResolver resolver;
    private final PlaceTrace trace;
    private TripDistance distance = new TripDistance(0);
    private final RadioTimeGate radioTimeGate = new RadioTimeGate();
    private TripTime time = new TripTime(0,radioTimeGate);
    private boolean partialTime;
    private String state = "PAUSED", error = "";
    private long generation, fixTime, savedAt;
    private PlaceResult place = PlaceResult.unknown(false);
    private PlaceStabilizer stabilizer = new PlaceStabilizer();
    private boolean loaded, storageFailed;
    private TripFix currentFix, resolvedFix, enrichmentFix;
    private volatile PlaceResult rawLocal = PlaceResult.unknown(false), onlineEvidence;
    private long placeGeneration;
    private volatile Snapshot snapshot = new Snapshot("PAUSED", 0, 0, 0, PlaceResult.unknown(false), "", false);
    private TripController(Context context) {
        this(context, new TripStore(context));
    }
    TripController(Context context, TripStore persistence) {
        store = persistence; offline = new OfflinePlaces(context); photon = new PhotonPlaces(context);
        trace = new PlaceTrace(context);
        resolver = new PlaceResolver(offline::resolve, photon::resolve, (job, result, online, duration) -> {
            trace.record(online ? result == null ? "online_no_result" : "online_result" : "offline_result",
                    job, result, null, duration, online ? null : offline.roadMatch());
            if (result != null) postPlace(job, result, online, online ? photon.evidenceFix() : null);
        }, r -> background(r, "TripPlaces"), r -> background(r, "TripPhoton"));
        counter.execute(() -> {
            try { TripStore.Saved saved = store.read(); state = saved.state(); generation = saved.generation(); distance = new TripDistance(saved.meters()); time = new TripTime(saved.millis(),radioTimeGate); partialTime = saved.partialTime(); }
            catch (Exception invalid) { storageFailed = true; error = "Nie można odczytać zapisanej sesji"; }
            loaded = true; publish();
        });
        counter.scheduleWithFixedDelay(() -> {
            if(!storageFailed) time.settle(SystemClock.elapsedRealtime());
            if (!storageFailed && "ACTIVE".equals(state) && SystemClock.elapsedRealtime() - savedAt >= 5000) save();
            publish();
        }, 1, 1, TimeUnit.SECONDS);
    }
    public Snapshot snapshot() { return snapshot; }
    /** Records only what a presentation surface rendered; it cannot change session state. */
    public void presented(String surface,boolean visible,Snapshot rendered,String title,String subtitle) {
        trace.presentation(surface,visible,visible?title:"",visible?subtitle:"",rendered.placeGeneration(),visible&&rendered.fresh()?rendered.sourceFix():null);
    }
    public void command(String command) {
        if (!java.util.Set.of("START", "PAUSE", "RESET").contains(command)) return;
        long boundary=SystemClock.elapsedRealtime();observationFloor.set(boundary); inputEpoch.incrementAndGet();
        counter.execute(() -> {
            if (!loaded || storageFailed) return;
            radioTimeGate.boundary(boundary);time.settle(boundary);
            String next = command.equals("RESET") ? state : command.equals("START") ? "ACTIVE" : "PAUSED";
            double nextMeters = command.equals("RESET") ? 0 : distance.meters();
            long nextMillis = command.equals("RESET") ? 0 : time.boundaryMillis();
            boolean nextPartial = !command.equals("RESET") && partialTime;
            // Publish the new state/totals only after all three values are durable together.
            try {
                store.write(next, generation + 1, nextMeters, nextMillis, nextPartial);
                state = next; generation++; distance = new TripDistance(nextMeters); time = new TripTime(nextMillis,radioTimeGate);
                partialTime = nextPartial; savedAt = SystemClock.elapsedRealtime();
                invalidate(command.toLowerCase(java.util.Locale.ROOT));
            } catch (Exception failed) { storageFailure(); }
            publish();
        });
    }
    private void invalidate(String reason) {
        distance.breakSegment(); if(storageFailed)time.discardPending();else time.breakSegment(); fixes.clear(); gap.set(false);
        clearPlaces(reason);
    }
    private void clearPlaces(String reason) {
        placeGeneration++; resolver.invalidate(placeGeneration);
        currentFix = null; resolvedFix = null; enrichmentFix = null; fixTime = 0;
        rawLocal = PlaceResult.unknown(false); onlineEvidence = null;
        stabilizer = new PlaceStabilizer(); place = PlaceResult.unknown(false);
        trace.presentation("controller_reset_"+reason,false,"","",placeGeneration,null);
    }
    public void breakSegment() {
        long boundary=SystemClock.elapsedRealtime();observationFloor.set(boundary); inputEpoch.incrementAndGet(); gap.set(true);
        counter.execute(() -> { radioTimeGate.boundary(boundary);generation++; invalidate("segment"); publish(); });
    }
    public void observePower(com.pbuchman.duduhome.routebook.PowerEvent event,RadioProfile.State observed) {
        long epoch=inputEpoch.get();
        counter.execute(()->{
            if(storageFailed||epoch!=inputEpoch.get()||event.elapsedRealtimeMs()<observationFloor.get())return;
            radioTimeGate.observe(event.observerEpoch(),event.elapsedRealtimeMs(),observed);
            time.settle(SystemClock.elapsedRealtime()); publish();
        });
    }
    public void offer(Location location) {
        if (!snapshot.active()) return;
        long epoch = inputEpoch.get();
        long uptime = SystemClock.uptimeMillis(), received = SystemClock.elapsedRealtime();
        TripFix fix = new TripFix(location.getElapsedRealtimeNanos()/1000000, received,
                location.getLatitude(), location.getLongitude(), location.hasAccuracy() ? location.getAccuracy() : Double.POSITIVE_INFINITY,
                location.getSpeed(), location.hasSpeed(), location.getBearing(), location.hasBearing(), location.isFromMockProvider());
        enqueue(new Input(epoch, fix, uptime));
    }
    private void enqueue(Input input) {
        if (!fixes.offer(input)) { fixes.clear(); gap.set(true); fixes.offer(input); }
        if (draining.compareAndSet(false, true)) counter.execute(this::drain);
    }
    private void drain() {
        try {
            Input input;
            while ((input = fixes.poll()) != null) {
                if (!state.equals("ACTIVE") || storageFailed || input.epoch() != inputEpoch.get()) continue;
                TripFix fix = input.fix();
                if (fix.elapsed() < observationFloor.get()) continue;
                if (gap.getAndSet(false)) { distance.breakSegment(); time.breakSegment(); }
                if (!time.accept(fix, input.uptime())) distance.breakSegment();
                distance.accept(fix);
                expirePlaces(SystemClock.elapsedRealtime());
                if (!fix.valid()) continue;
                if (currentFix != null && fix.elapsed() <= currentFix.elapsed()) continue;
                currentFix = fix; fixTime = fix.elapsed();
                resolver.offer(new PlaceResolver.Job(placeGeneration, fix));
            }
        } finally {
            draining.set(false); publish();
            if (!fixes.isEmpty() && draining.compareAndSet(false, true)) counter.execute(this::drain);
        }
    }
    private void postPlace(PlaceResolver.Job job, PlaceResult result, boolean online, TripFix evidenceFix) {
        counter.execute(() -> {
            expirePlaces(SystemClock.elapsedRealtime());
            if (!state.equals("ACTIVE") || storageFailed || placeGeneration != job.generation() || currentFix == null) {
                trace.record("discard_session", job, result, null, 0, null); return;
            }
            if (currentFix.distance(job.fix()) > 60 || currentFix.elapsed() - job.fix().elapsed() > 10000) {
                trace.record("discard_stale_position", job, result, null, 0, null); return;
            }
            if (online) {
                if (!PlaceResolver.currentOnline(job.fix(), resolvedFix, currentFix)) {
                    trace.record("discard_online_newer_fix", job, result, null, 0, null); return;
                }
                onlineEvidence = result.onlineEvidence(); enrichmentFix = evidenceFix;
            } else {
                if (resolvedFix != null && job.fix().elapsed() < resolvedFix.elapsed()) return;
                rawLocal = result; resolvedFix = job.fix();
            }
            PlaceResult evidence = rawLocal;
            if (onlineEvidence != null && enrichmentFix != null && enrichmentFix.distance(job.fix()) < 40
                    && job.fix().elapsed() >= enrichmentFix.elapsed() && job.fix().elapsed() - enrichmentFix.elapsed() <= 30000)
                evidence = PlaceResult.enrich(rawLocal, onlineEvidence);
            place = stabilizer.offer(evidence, job.fix()); publish();
            trace.record("applied", job, evidence, place, 0, null);
        });
    }
    private void save() {
        try { store.write(state, generation, distance.meters(), time.millis(), partialTime); savedAt = SystemClock.elapsedRealtime(); }
        catch (Exception failed) { storageFailure(); }
    }
    private void storageFailure() {
        storageFailed = true; inputEpoch.incrementAndGet(); generation++; invalidate("storage_failure");
        error = "Błąd zapisu sesji. Pomiar zatrzymany";
    }
    private void expirePlaces(long now) {
        if (fixTime>0 && now-fixTime>10000) {
            clearPlaces("gps_expired");
        }
    }
    private void publish() {
        expirePlaces(SystemClock.elapsedRealtime());
        place=stabilizer.current();
        snapshot = new Snapshot(state, generation, distance.meters(), fixTime, place, error, loaded,
                time.millis(), partialTime, placeGeneration, currentFix);
    }
    public String diagnostic() {
        Snapshot s = snapshot;PlaceResult raw=rawLocal;
        return "trip_state=" + s.state() + " loaded=" + s.loaded() + " gps_fresh=" + s.fresh()
                + " map=" + offline.status() + " map_schema=" + offline.schemaVersion()
                + " locality_quality=" + s.place().localityQuality() + " street_quality=" + raw.streetQuality() + " display_street_quality=" + s.place().streetQuality()
                + " locality_source=" + s.place().localitySource() + " street_source=" + raw.streetSource() + " display_street_source=" + s.place().streetSource()
                + " storage_ok=" + s.error().isEmpty();
    }
}
