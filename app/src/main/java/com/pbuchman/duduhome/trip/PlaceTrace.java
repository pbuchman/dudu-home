package com.pbuchman.duduhome.trip;

import android.content.Context;
import android.os.SystemClock;
import java.io.File;
import com.pbuchman.duduhome.diagnostics.RollingJournal;
import com.pbuchman.duduhome.diagnostics.Diagnostics;
import java.nio.file.Files;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** Default-active, owner-private evidence. Never enters logcat, Routebook or automatic upload. */
final class PlaceTrace {
    private final File directory,flag;
    private final RollingJournal journal;
    private final com.pbuchman.duduhome.diagnostics.PresentationCoalescer presentations=new com.pbuchman.duduhome.diagnostics.PresentationCoalescer();
    private static final java.util.concurrent.ConcurrentHashMap<String,RollingJournal> journals=new java.util.concurrent.ConcurrentHashMap<>();
    private static final AtomicLong sequence=new AtomicLong(),dropped=new AtomicLong();
    private final long processStarted=SystemClock.elapsedRealtime();
    private static final ThreadPoolExecutor writer=new ThreadPoolExecutor(1,1,15,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32),r->{Thread t=new Thread(r,"TripPlaceTrace");t.setDaemon(true);return t;},
            (task,executor)->dropped.incrementAndGet());
    PlaceTrace(Context context) {
        directory=context.getNoBackupFilesDir();flag=new File(directory,"trip-place-trace.disabled");
        journal=journals.computeIfAbsent(directory.getAbsolutePath(),ignored -> new RollingJournal(directory,"trip-place-trace.jsonl","trip-place-trace.previous.jsonl",8,4L*1024*1024,16384));
        writer.allowCoreThreadTimeOut(true);
    }
    private boolean enabled() { return !flag.exists() && !Files.isSymbolicLink(flag.toPath()); }
    synchronized void record(String event,PlaceResolver.Job job,PlaceResult raw,PlaceResult shown,long durationNanos,OfflinePlaces.RoadMatch match) {
        if (!enabled()) return;
        long rowSequence=sequence.incrementAndGet();
        long wall=System.currentTimeMillis(),elapsed=SystemClock.elapsedRealtime();
        writer.execute(()-> {
            if (!enabled()) return;
            try {
                TripFix f=job.fix();
                JSONObject item=new JSONObject().put("schema",2).put("event",event).put("wall_ms",wall)
                        .put("sequence",rowSequence).put("dropped_total",dropped.get())
                        .put("process_id",Diagnostics.process()).put("android_pid",android.os.Process.myPid()).put("process_started_elapsed_ms",processStarted)
                        .put("elapsed_ms",elapsed).put("generation",job.generation()).put("duration_ms",durationNanos/1000000.0)
                        .put("fix_elapsed_ms",f.elapsed()).put("fix_received_ms",f.received())
                        .put("fix_wall_estimated_ms",wall-(elapsed-f.elapsed())).put("fix_source","gps")
                        .put("fix_valid",f.valid()).put("fix_observed",f.observed()).put("mock",f.mock())
                        .put("lat",number(f.latitude())).put("lon",number(f.longitude())).put("accuracy_m",number(f.accuracy()))
                        .put("speed_mps",f.hasSpeed() ? number(f.speed()) : JSONObject.NULL)
                        .put("bearing",f.hasBearing() ? number(f.bearing()) : JSONObject.NULL)
                        .put("raw",place(raw)).put("displayed",place(shown));
                if (match!=null) item.put("road",new JSONObject().put("candidate",bounded(match.candidate()))
                        .put("best_score",number(match.best())).put("second_score",number(match.second()))
                        .put("margin",number(match.second()-match.best())).put("threshold",number(match.threshold()))
                        .put("examined",match.examined()).put("outcome",match.outcome())
                        .put("supported_headings_scored",match.supportedScored()).put("best_heading_source",match.bestHeadingSource())
                        .put("best_support_m",number(match.bestSupport())).put("map_generation",match.mapGeneration()));
                if (!journal.append(wall,item.toString())) dropped.incrementAndGet();
            } catch (Exception ignored) { dropped.incrementAndGet(); /* Never reveal private exception data. */ }
        });
    }
    /** Actual renderer/reset transitions, separate from resolver raw/shown decisions. */
    synchronized void presentation(String surface,boolean visible,String title,String subtitle,long generation,TripFix optionalFix) {
        if(!enabled() || surface==null || !surface.matches("[a-z0-9_]{1,64}"))return;
        String first=bounded(title),second=bounded(subtitle);
        if(!presentations.changed(surface,visible,first,second,generation))return;
        long rowSequence=sequence.incrementAndGet(),wall=System.currentTimeMillis(),elapsed=SystemClock.elapsedRealtime();
        writer.execute(()-> {
            if(!enabled())return;
            try {
                JSONObject item=new JSONObject().put("schema",2).put("event","presentation").put("surface",surface)
                        .put("visible",visible).put("title",first).put("subtitle",second).put("generation",generation)
                        .put("wall_ms",wall).put("elapsed_ms",elapsed).put("sequence",rowSequence)
                        .put("process_id",Diagnostics.process()).put("dropped_total",dropped.get());
                Object reference=JSONObject.NULL;
                if(optionalFix!=null)reference=new JSONObject().put("elapsed_ms",optionalFix.elapsed())
                        .put("received_ms",optionalFix.received()).put("lat",number(optionalFix.latitude()))
                        .put("lon",number(optionalFix.longitude())).put("accuracy_m",number(optionalFix.accuracy()))
                        .put("source","gps").put("valid",optionalFix.valid()).put("observed",optionalFix.observed());
                item.put("fix_reference",reference);
                if(!journal.append(wall,item.toString()))dropped.incrementAndGet();
            }catch(Exception ignored){dropped.incrementAndGet();}
        });
    }
    private static Object number(double value) { return Double.isFinite(value) ? value : JSONObject.NULL; }
    private static String bounded(String value) { return value==null ? "" : value.length()>256 ? value.substring(0,256) : value; }
    private static Object place(PlaceResult value) throws Exception {
        return value==null ? JSONObject.NULL : new JSONObject().put("locality",bounded(value.locality()))
                .put("street",bounded(value.street())).put("locality_quality",value.localityQuality().name())
                .put("street_quality",value.streetQuality().name()).put("title",bounded(value.title()))
                .put("subtitle",bounded(value.subtitle())).put("locality_source",bounded(value.localitySource()))
                .put("street_source",bounded(value.streetSource())).put("covered",value.covered());
    }
}
