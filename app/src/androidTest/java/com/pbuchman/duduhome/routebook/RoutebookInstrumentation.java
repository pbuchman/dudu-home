package com.pbuchman.duduhome.routebook;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Build;
import android.os.Bundle;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Separate emulator-only runner. No calls, robots, geocoder, GPS injection or production endpoint. */
public final class RoutebookInstrumentation extends Instrumentation {
    private String phase; private int assertions;
    @Override public void onCreate(Bundle args) { super.onCreate(args); phase=args.getString("phase","checks"); start(); }
    private void check(boolean value,String reason) {assertions++;if(!value)throw new AssertionError(reason);}
    private void invalid(Runnable task) {try {task.run();}catch(RuntimeException e){assertions++;return;}throw new AssertionError("invalid accepted");}
    private Point point(int n) {return new Point(new UUID(0,n+1).toString(),new UUID(0,999).toString(),Point.TIME.format(Instant.ofEpochMilli(1791187200000L+n*5000L)),0,0,5,0.0);}
    private Outbox fresh(String name) {
        File f=new File(getTargetContext().getNoBackupFilesDir(),"routebook-test-"+name+".sqlite");
        // Only dedicated synthetic test DBs are replaced; never the collector DB.
        android.database.sqlite.SQLiteDatabase.deleteDatabase(f);return new Outbox(f);
    }
    private String configText(String device) {
        return "{\"version\":1,\"device_id\":"+Json.quote(device)+",\"endpoint\":\"https://synthetic.invalid/v1/ingest\",\"token\":\"synthetic-token-00000000000000000000\",\"enabled\":true,\"sample_ms\":5000,\"max_age_ms\":5000,\"gap_ms\":30000,\"accuracy_m\":50}";
    }
    private PrivateConfig config(String device) throws Exception {return PrivateConfig.parse(configText(device).getBytes(StandardCharsets.UTF_8),device);}
    private String ack(Map<?,?> envelope,String status,String code) {
        StringJoiner results=new StringJoiner(",");int index=0;
        for(Object row:(List<?>)envelope.get("points")) {Map<?,?> p=(Map<?,?>)row;
            results.add("{\"index\":"+(index++)+",\"event_id\":"+Json.quote((String)p.get("event_id"))+",\"status\":"+Json.quote(status)+",\"code\":"+(code==null?"null":Json.quote(code))+"}");}
        return "{\"version\":1,\"device_id\":"+Json.quote((String)envelope.get("device_id"))+",\"received_at\":\"2026-10-05T08:10:00.000Z\",\"revision\":1,\"results\":["+results+"]}";
    }
    private void waitEmpty(Outbox db) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while(!db.pending(true,1).isEmpty()&&System.nanoTime()<end) Thread.sleep(20);
        check(db.pending(true,1).isEmpty(),"queue drained");
    }
    private void checks() throws Exception {
        try(Outbox db=fresh("storage")) {
            Point a=point(0),b=point(1);db.insert(a);db.insert(b);
            check(db.pending(true,1).get(0).eventId.equals(b.eventId),"newest order");
            invalid(()->db.insert(a));check(db.pending(false,200).size()==2,"failed transaction preserves rows");
            String text=ack((Map<?,?>)Json.parse(new String(Protocol.envelope(db.device(),"backfill",List.of(a,b)),StandardCharsets.UTF_8)),"accepted",null);
            invalid(()->Protocol.ack(text.replace("\"index\":1","\"index\":0"),db.device(),List.of(a,b)));
            invalid(()->Protocol.ack(text.replace("\"status\":\"accepted\"","\"status\":\"unknown\""),db.device(),List.of(a,b)));
            invalid(()->Protocol.ack(text.replace(a.eventId,point(4).eventId),db.device(),List.of(a,b)));
            check(db.pending(false,200).size()==2,"invalid ack makes no mutation");
            String mixed=text.replace("\"status\":\"accepted\",\"code\":null}]", "\"status\":\"rejected\",\"code\":\"event_conflict\"}]").replace("\"status\":\"accepted\"", "\"status\":\"duplicate\"");
            db.apply(Protocol.ack(mixed,db.device(),List.of(a,b)));
            check(db.pending(false,200).isEmpty(),"terminal rows excluded");check(db.counts().contains("REJECTED_CONFLICT=1"),"rejected retained");
            PrivateConfig cfg=config(db.device());check(cfg.enabled,"synthetic config");
            for(String endpoint:List.of("http://synthetic.invalid/v1/ingest","https://synthetic.invalid/v1/day","https://synthetic.invalid/v1/ingest?q=1","https://user@synthetic.invalid/v1/ingest","https://synthetic.invalid:444/v1/ingest")) {
                try {PrivateConfig.parse(configText(db.device()).replace("https://synthetic.invalid/v1/ingest",endpoint).getBytes(StandardCharsets.UTF_8),db.device());throw new AssertionError("bad endpoint");}
                catch(IllegalArgumentException expected){assertions++;}
            }
            File dir=new File(getTargetContext().getNoBackupFilesDir(),"routebook-test-config");check(dir.mkdirs()||dir.isDirectory(),"test directory");
            try(FileOutputStream out=new FileOutputStream(new File(dir,"routebook-import.json"))) {out.write(configText(db.device()).getBytes(StandardCharsets.UTF_8));}
            check(PrivateConfig.load(dir,db.device()).enabled,"private import");check(!new File(dir,"routebook-import.json").exists(),"staging removed");
            check(PrivateConfig.load(dir,db.device()).enabled,"config survives reload");
            try {PrivateConfig.utf8(new byte[]{(byte)0xff});throw new AssertionError("utf8");}catch(java.nio.charset.CharacterCodingException expected){assertions++;}
        }
        try(Outbox db=fresh("full")) {
            var sqlite=(android.database.sqlite.SQLiteDatabase)get(db,"db");
            long pages;
            try(var c=sqlite.rawQuery("PRAGMA page_count",null)) {c.moveToFirst();pages=c.getLong(0);}
            try(var c=sqlite.rawQuery("PRAGMA max_page_count="+pages,null)) {c.moveToFirst();}
            int inserted=0;boolean full=false;
            for(int n=0;n<10000;n++) {
                try {db.insert(point(n));inserted++;}
                // Android may report rollback-after-FULL as SQLiteException (no active transaction).
                catch(android.database.sqlite.SQLiteException expected) {full=true;break;}
            }
            check(full,"SQLite full is a failure, never a successful insert");
            check(db.counts().contains("PENDING="+inserted+" "),"full transaction leaves prior pending intact");
        }
        try(Outbox db=fresh("lost")) {
            db.insert(point(0));AtomicInteger requests=new AtomicInteger();List<String> ids=new CopyOnWriteArrayList<>();
            Uploader.Transport lost=(cfg,bytes)-> {
                Map<?,?> body=(Map<?,?>)Json.parse(new String(bytes,StandardCharsets.UTF_8));
                ids.add((String)((Map<?,?>)((List<?>)body.get("points")).get(0)).get("event_id"));
                if(requests.incrementAndGet()==1) throw new IOException("synthetic lost ack");
                return new Uploader.Response(200,ack(body,"duplicate",null),0);
            };
            try(Uploader up=new Uploader(db,config(db.device()),lost,c->{})) {up.kick();waitEmpty(db);}
            check(ids.size()==2&&ids.get(0).equals(ids.get(1)),"lost ack retries stable ID");
        }
        try(Outbox db=fresh("scheduler")) {
            for(int i=0;i<240;i++)db.insert(point(i));
            List<String> lanes=new CopyOnWriteArrayList<>();List<Integer> sizes=new CopyOnWriteArrayList<>();AtomicInteger n=new AtomicInteger();
            Uploader.Transport capture=(cfg,bytes)-> {
                Map<?,?> body=(Map<?,?>)Json.parse(new String(bytes,StandardCharsets.UTF_8));
                lanes.add((String)body.get("lane"));sizes.add(((List<?>)body.get("points")).size());
                if(n.incrementAndGet()==2) db.insert(point(300)); // A fresh fix arrives during backfill.
                return new Uploader.Response(200,ack(body,"accepted",null),0);
            };
            try(Uploader up=new Uploader(db,config(db.device()),capture,c->{})) {up.kick();waitEmpty(db);}
            check(lanes.equals(List.of("latest","backfill","latest","backfill")),"latest before backlog and before next backfill");
            check(sizes.equals(List.of(1,200,1,39)),"bounded growing backlog");
        }
        for(int status:new int[]{401,403,422,302,429,503}) {
            try(Outbox db=fresh("http"+status)) {
                db.insert(point(0));CountDownLatch request=new CountDownLatch(1);
                try(Uploader up=new Uploader(db,config(db.device()),(cfg,bytes)->{request.countDown();return new Uploader.Response(status,"",30);},c->{})) {
                    up.kick();check(request.await(2,TimeUnit.SECONDS),"HTTP tried");Thread.sleep(100);
                    check(db.pending(true,1).size()==1,"HTTP failure retains pending "+status);
                }
            }
        }
        try(Outbox db=fresh("invalid-ack")) {
            db.insert(point(0));CountDownLatch request=new CountDownLatch(1);
            try(Uploader up=new Uploader(db,config(db.device()),(cfg,bytes)->{request.countDown();return new Uploader.Response(200,"{}",0);},c->{})) {
                up.kick();check(request.await(2,TimeUnit.SECONDS),"invalid ack request");Thread.sleep(100);check(db.pending(true,1).size()==1,"invalid ack retains pending");
            }
        }
        try(Outbox db=fresh("partial")) {
            db.insert(point(0));db.insert(point(1));
            try(Uploader up=new Uploader(db,config(db.device()),(cfg,bytes)-> {
                Map<?,?> body=(Map<?,?>)Json.parse(new String(bytes,StandardCharsets.UTF_8));
                return new Uploader.Response(200,ack(body,"rejected","invalid_point"),0);
            },c->{})) {up.kick();waitEmpty(db);}
            check(db.counts().contains("REJECTED_INVALID=2"),"rejection retained in SQLite");
        }
    }
    private static void set(Object object,String field,Object value) throws Exception {
        var f=object.getClass().getDeclaredField(field);f.setAccessible(true);f.set(object,value);
    }
    private static Object get(Object object,String field) throws Exception {
        var f=object.getClass().getDeclaredField(field);f.setAccessible(true);return f.get(object);
    }
    private void integration() throws Exception {
        File dir=new File(getTargetContext().getNoBackupFilesDir(),"routebook-test-integration");
        check(dir.mkdirs()||dir.isDirectory(),"integration directory");
        android.database.sqlite.SQLiteDatabase.deleteDatabase(new File(dir,"routebook.sqlite"));
        new File(dir,"trip-state.json").delete();
        new File(dir,"trip-state.json.bak").delete();
        android.content.Context ctx=new android.content.ContextWrapper(getTargetContext()) {
            @Override public android.content.Context getApplicationContext() {return this;}
            @Override public File getNoBackupFilesDir() {return dir;}
        };
        var service=new com.pbuchman.duduhome.location.HomeMonitorService();
        var attach=android.content.ContextWrapper.class.getDeclaredMethod("attachBaseContext",android.content.Context.class);
        attach.setAccessible(true);attach.invoke(service,ctx);
        Routebook collector=new Routebook(ctx);
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(get(collector,"filter")==null&&System.nanoTime()<end)Thread.sleep(10);
        check(get(collector,"filter")!=null,"collector initialized without upload config");
        check(com.pbuchman.duduhome.location.HomeMonitorService.ready(ctx),"existing service permission guards");
        var trip=com.pbuchman.duduhome.trip.TripController.get(ctx);
        while(!trip.snapshot().loaded())Thread.sleep(10);
        check(trip.snapshot().state().equals("OFF"),"manual trip off");
        set(service,"routebook",collector);set(service,"trip",trip);
        AtomicReference<com.pbuchman.duduhome.automation.AutomationRuntime> automation=new AtomicReference<>();
        runOnMainSync(()-> {
            automation.set(new com.pbuchman.duduhome.automation.AutomationRuntime(ctx));
            check(com.pbuchman.duduhome.automation.HomeActions.begin(),"synthetic busy lease, no external action possible");
        });
        set(service,"automation",automation.get());
        // detector stays null: disabled/missing home automation still uses the same GPS callback.
        android.location.Location first=new android.location.Location("synthetic");
        first.setTime(System.currentTimeMillis());first.setElapsedRealtimeNanos(android.os.SystemClock.elapsedRealtimeNanos());
        first.setLatitude(0);first.setLongitude(0);first.setAccuracy(5);first.setSpeed(0);
        runOnMainSync(()->service.onLocationChanged(first));
        Outbox db=(Outbox)get(collector,"db");
        end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(db.pending(true,1).isEmpty()&&System.nanoTime()<end)Thread.sleep(10);
        check(db.pending(true,1).size()==1,"finally stores while trip OFF and automation returns early");
        Point firstPoint=db.pending(true,1).get(0);
        trip.command("START");while(!trip.snapshot().active())Thread.sleep(10);
        trip.command("PAUSE");while(!trip.snapshot().state().equals("PAUSED"))Thread.sleep(10);
        runOnMainSync(()->service.onProviderDisabled("gps"));
        Thread.sleep(5100);
        android.location.Location second=new android.location.Location(first);
        second.setTime(System.currentTimeMillis());second.setElapsedRealtimeNanos(android.os.SystemClock.elapsedRealtimeNanos());
        runOnMainSync(()->service.onLocationChanged(second));
        end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(db.pending(false,200).size()<2&&System.nanoTime()<end)Thread.sleep(10);
        check(db.pending(false,200).size()==2,"collects while manual PAUSED");
        check(!db.pending(true,1).get(0).segmentId.equals(firstPoint.segmentId),"provider loss starts new segment");
        check(com.pbuchman.duduhome.automation.HomeActions.busy(),"collector does not touch action lease");
        runOnMainSync(()-> {automation.get().close();com.pbuchman.duduhome.automation.HomeActions.end();});
        ((ExecutorService)get(service,"cycleReader")).shutdownNow();collector.close();
    }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            check(Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"),"emulator only");
            File file=new File(getTargetContext().getNoBackupFilesDir(),"routebook-test-restart.sqlite");
            if(phase.equals("seed")) {
                android.database.sqlite.SQLiteDatabase.deleteDatabase(file);
                try(Outbox db=new Outbox(file)) {db.insert(point(7));check(db.pending(true,1).size()==1,"seed");
                    getTargetContext().getSharedPreferences("routebook-test",0).edit().putString("device",db.device()).commit();}
            } else if(phase.equals("resume")) {
                try(Outbox db=new Outbox(file)) {check(db.device().equals(getTargetContext().getSharedPreferences("routebook-test",0).getString("device","")),"identity survives process restart");
                    check(db.pending(true,1).get(0).json().equals(point(7).json()),"pending ID and payload survive process restart");}
            } else if(phase.equals("integration")) integration();
            else if(phase.equals("staged")) {
                File dir=getTargetContext().getNoBackupFilesDir();
                PrivateConfig cfg=PrivateConfig.load(dir,"00000000-0000-4000-8000-000000000001");
                check(cfg!=null&&cfg.enabled,"stdin staging imported");
                check(!new File(dir,"routebook-import.json").exists(),"staging erased");
                new File(dir,"routebook-config.json").delete();
            } else checks();
            result.putString("result","PASS: Routebook "+phase+" "+assertions+" assertions; synthetic only");finish(Activity.RESULT_OK,result);
        } catch(Throwable e) {result.putString("result","FAIL: Routebook "+e.getClass().getSimpleName()+" "+e.getMessage());finish(Activity.RESULT_CANCELED,result);}
    }
}
