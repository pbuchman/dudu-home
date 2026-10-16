package com.pbuchman.duduhome.trip;

import android.content.Context;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** Explicit operator opt-in only. Private bounded evidence never enters logcat or upload. */
final class PlaceTrace {
    private static final long MAX_BYTES=262144;
    private final File directory,flag;
    private final AtomicLong sequence=new AtomicLong(),dropped=new AtomicLong();
    private final long processStarted=SystemClock.elapsedRealtime();
    private final ThreadPoolExecutor writer=new ThreadPoolExecutor(1,1,15,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32),r->{Thread t=new Thread(r,"TripPlaceTrace");t.setDaemon(true);return t;},
            (task,executor)->dropped.incrementAndGet());
    PlaceTrace(Context context) {
        directory=context.getNoBackupFilesDir();flag=new File(directory,"trip-place-trace.enabled");
        writer.allowCoreThreadTimeOut(true);
    }
    private boolean enabled() { return flag.isFile() && !Files.isSymbolicLink(flag.toPath()); }
    synchronized void record(String event,PlaceResolver.Job job,PlaceResult raw,PlaceResult shown,long durationNanos,OfflinePlaces.RoadMatch match) {
        if (!enabled()) return;
        long rowSequence=sequence.incrementAndGet();
        long wall=System.currentTimeMillis(),elapsed=SystemClock.elapsedRealtime();
        writer.execute(()-> {
            if (!enabled()) return;
            try {
                TripFix f=job.fix();
                JSONObject item=new JSONObject().put("schema",1).put("event",event).put("wall_ms",wall)
                        .put("sequence",rowSequence).put("dropped_total",dropped.get())
                        .put("process_id",android.os.Process.myPid()).put("process_started_elapsed_ms",processStarted)
                        .put("elapsed_ms",elapsed).put("generation",job.generation()).put("duration_ms",durationNanos/1000000.0)
                        .put("fix_elapsed_ms",f.elapsed()).put("fix_received_ms",f.received())
                        .put("lat",f.latitude()).put("lon",f.longitude()).put("accuracy_m",number(f.accuracy()))
                        .put("speed_mps",f.hasSpeed() ? number(f.speed()) : JSONObject.NULL)
                        .put("bearing",f.hasBearing() ? number(f.bearing()) : JSONObject.NULL)
                        .put("raw",place(raw)).put("displayed",place(shown));
                if (match!=null) item.put("road",new JSONObject().put("candidate",bounded(match.candidate()))
                        .put("best_score",number(match.best())).put("second_score",number(match.second()))
                        .put("margin",number(match.second()-match.best())).put("threshold",number(match.threshold()))
                        .put("examined",match.examined()).put("outcome",match.outcome())
                        .put("supported_headings_scored",match.supportedScored()).put("best_heading_source",match.bestHeadingSource())
                        .put("best_support_m",number(match.bestSupport())).put("map_generation",match.mapGeneration()));
                File file=new File(directory,"trip-place-trace.jsonl"),previous=new File(directory,"trip-place-trace.previous.jsonl");
                byte[] row=(item+"\n").getBytes(StandardCharsets.UTF_8);
                if (row.length>MAX_BYTES) return;
                if (Files.isSymbolicLink(file.toPath()) || Files.isSymbolicLink(previous.toPath())) return;
                if (file.length()+row.length>MAX_BYTES) {
                    if (previous.exists() && !previous.delete()) return;
                    if (!file.renameTo(previous)) return;
                }
                try (FileOutputStream out=new FileOutputStream(file,true)) {
                    file.setReadable(false,false);file.setWritable(false,false);
                    file.setReadable(true,true);file.setWritable(true,true);
                    out.write(row);
                }
            } catch (Exception ignored) { dropped.incrementAndGet(); /* Never reveal private exception data. */ }
        });
    }
    private static Object number(double value) { return Double.isFinite(value) ? value : JSONObject.NULL; }
    private static String bounded(String value) { return value.length()>256 ? value.substring(0,256) : value; }
    private static Object place(PlaceResult value) throws Exception {
        return value==null ? JSONObject.NULL : new JSONObject().put("locality",bounded(value.locality()))
                .put("street",bounded(value.street())).put("locality_quality",value.localityQuality().name())
                .put("street_quality",value.streetQuality().name()).put("locality_source",value.localitySource())
                .put("street_source",value.streetSource()).put("covered",value.covered());
    }
}
