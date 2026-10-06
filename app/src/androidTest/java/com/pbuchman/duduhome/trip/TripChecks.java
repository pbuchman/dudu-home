package com.pbuchman.duduhome.trip;

import android.app.Instrumentation;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.location.Location;
import android.os.SystemClock;
import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

public final class TripChecks {
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    public static void fixture(Context context) {
        File target=new File(context.getNoBackupFilesDir(),"places.sqlite");
        target.delete();
        try(SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(target,null)) {
            db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT)");
            db.execSQL("INSERT INTO metadata VALUES('schema','1')");
            db.execSQL("CREATE TABLE roads(id INTEGER PRIMARY KEY,name TEXT,ref TEXT,city TEXT,a REAL,b REAL,c REAL,d REAL)");
            db.execSQL("INSERT INTO roads VALUES(1,'Ulica Przykładowa','','Miejscowość Testowa',0,0,0,0.01)");
            db.execSQL("CREATE TABLE road_cells(x INTEGER,y INTEGER,id INTEGER)");
            db.execSQL("INSERT INTO road_cells VALUES(0,0,1)");db.execSQL("INSERT INTO road_cells VALUES(1,0,1)");
            db.execSQL("CREATE TABLE areas(id INTEGER,name TEXT,rings TEXT)");
            db.execSQL("CREATE TABLE area_cells(x INTEGER,y INTEGER,id INTEGER)");
            db.execSQL("CREATE TABLE addresses(city TEXT,lat REAL,lon REAL,x INTEGER,y INTEGER)");
            db.execSQL("CREATE TABLE places(name TEXT,lat REAL,lon REAL,x INTEGER,y INTEGER)");
            db.execSQL("INSERT INTO places VALUES('Miejscowość Testowa',0,0,0,0)");
        }
    }
    private static TripFix fix(double lon) { long now=SystemClock.elapsedRealtime();return new TripFix(now,now,0,lon,3,10,true,90,true,false); }
    private static Location location(double lon) { Location l=new Location("gps");l.setLatitude(0);l.setLongitude(lon);l.setAccuracy(3);l.setSpeed(10);l.setBearing(90);l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());return l; }
    private static void waitState(TripController c,String state) {
        for(int n=0;n<40;n++) { if(c.snapshot().loaded() && c.snapshot().state().equals(state))return;SystemClock.sleep(50); }
        throw new AssertionError("trip state "+state);
    }
    public static void run(Instrumentation i) throws Exception {
        Context c=i.getTargetContext();fixture(c);
        OfflinePlaces offline=new OfflinePlaces(c);
        PlaceResult place=offline.resolve(fix(.001));
        check(place.complete() && place.street().equals("Ulica Przykładowa"),"offline on unvisited road");
        check(!offline.resolve(fix(1)).covered(),"no coverage not nearest distant settlement");
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(new File(c.getNoBackupFilesDir(),"places.sqlite").toString(),null,0)) {
            db.execSQL("UPDATE roads SET city='' ");
        }
        check(new OfflinePlaces(c).resolve(fix(.001)).locality().startsWith("Okolice "),"point settlement marked uncertain");
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(new File(c.getNoBackupFilesDir(),"places.sqlite").toString(),null,0)) {
            db.execSQL("UPDATE roads SET city='Miejscowość Testowa'");
        }
        JSONObject response=new JSONObject("{\"features\":[{\"geometry\":{\"coordinates\":[0.001,0]},\"properties\":{\"city\":\"Miejscowość Testowa\",\"street\":\"Ulica Przykładowa\"}}]}");
        check(PhotonPlaces.parse(response,fix(.001)).complete(),"Photon structured result");
        check(PhotonPlaces.parse(response,fix(1))==null,"Photon distant point rejected");
        JSONObject pointResponse=new JSONObject(response.toString());
        pointResponse.getJSONArray("features").getJSONObject(0).put("properties",new JSONObject("{\"osm_key\":\"place\",\"osm_value\":\"village\",\"name\":\"Miejscowość Testowa\"}"));
        PlaceResult nearby=PhotonPlaces.parse(pointResponse,fix(.001));
        check(!nearby.certain() && nearby.locality().startsWith("Okolice "),"Photon point is not settlement containment");
        check(PhotonPlaces.parse(new JSONObject("{\"features\":[]}"),fix(0))==null,"empty response");
        var quota=c.getSharedPreferences("trip_photon_quota",0);quota.edit().clear().commit();
        AtomicInteger calls=new AtomicInteger();
        PhotonPlaces throttled=new PhotonPlaces(c,url -> { calls.incrementAndGet();return new HttpURLConnection(url) {
            public void disconnect(){}public boolean usingProxy(){return false;}public void connect(){}
            public int getResponseCode(){return 429;}public String getHeaderField(String key){return "3600";}
        }; });
        check(throttled.resolve(fix(0))==null,"429 fallback");throttled.resolve(fix(1));check(calls.get()==1,"429 cooldown");
        new PhotonPlaces(c,url->{throw new AssertionError("restart Retry-After bypass");}).resolve(fix(1));
        quota.edit().clear().putLong("day",System.currentTimeMillis()/86400000).putInt("used",300).commit();
        new PhotonPlaces(c,url->{throw new AssertionError("quota network request");}).resolve(fix(0));
        quota.edit().clear().commit();
        new PhotonPlaces(c,url->{throw new SocketTimeoutException();}).resolve(fix(0));
        check(quota.getInt("used",0)==1,"failed request reserves quota");
        new PhotonPlaces(c,url->{throw new AssertionError("restart rate bypass");}).resolve(fix(1));
        quota.edit().clear().commit();
        TripController controller=TripController.get(c);
        for(int n=0;n<40 && !controller.snapshot().loaded();n++)SystemClock.sleep(50);
        check(controller.snapshot().loaded(),"trip loaded");
        if(controller.snapshot().active() || controller.snapshot().state().equals("PAUSED")) {
            controller.command("END");waitState(controller,"FINISHED");
        }
        controller.command("START");waitState(controller,"ACTIVE");
        controller.offer(location(.001));SystemClock.sleep(1050);controller.offer(location(.0011));
        SystemClock.sleep(1050);controller.offer(location(.0012));SystemClock.sleep(400);
        check(controller.snapshot().meters()>20 && controller.snapshot().place().complete(),"independent distance and offline resolver");
        controller.command("PAUSE");waitState(controller,"PAUSED");double meters=controller.snapshot().meters();
        controller.offer(location(.009));SystemClock.sleep(200);check(controller.snapshot().meters()==meters,"pause ignores fixes");
        TripStore.Saved saved=new TripStore(c).read();check(saved.state().equals("PAUSED") && saved.meters()==meters,"pause acknowledged after durable save");
        controller.command("RESUME");waitState(controller,"ACTIVE");controller.offer(location(.005));SystemClock.sleep(200);
        check(controller.snapshot().meters()==meters,"resume no bridge");
        controller.command("END");waitState(controller,"FINISHED");
        check(new TripStore(c).read().state().equals("FINISHED"),"durable end");
        controller.command("START");waitState(controller,"ACTIVE");check(controller.snapshot().meters()==0,"new session zero");
        controller.command("END");waitState(controller,"FINISHED");
        check(!com.pbuchman.duduhome.automation.HomeActions.busy(),"trip never acquires action lease");
    }
}
