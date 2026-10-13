package com.pbuchman.duduhome.trip;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import java.io.File;
import org.json.JSONArray;
import static com.pbuchman.duduhome.trip.PlaceResult.Quality.*;

/** Invented geography only; exercises Android SQLite and polygon parsing without network/actions. */
final class LocalityChecks {
    private static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
    private static File map(Context c) { return new File(c.getNoBackupFilesDir(),"places.sqlite"); }
    private static TripFix fix(double lon,double lat) {
        long now=SystemClock.elapsedRealtime();return new TripFix(now,now,lat,lon,3,0,false,0,false,false);
    }
    private static PlaceResult resolve(Context c,double lon,double lat) {
        try (OfflinePlaces offline=new OfflinePlaces(c)) { return offline.resolve(fix(lon,lat)); }
    }
    private static SQLiteDatabase fixture(Context c) {
        map(c).delete();SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(map(c),null);
        db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT)");db.execSQL("INSERT INTO metadata VALUES('schema','2')");
        db.execSQL("CREATE TABLE roads(id INTEGER PRIMARY KEY,name TEXT,ref TEXT,city TEXT,a REAL,b REAL,c REAL,d REAL)");
        db.execSQL("CREATE TABLE road_cells(x INTEGER,y INTEGER,id INTEGER)");
        db.execSQL("CREATE TABLE areas(id INTEGER PRIMARY KEY,name TEXT,rings TEXT,kind TEXT,provenance TEXT,admin_level TEXT,feature_id TEXT)");
        db.execSQL("CREATE TABLE area_cells(x INTEGER,y INTEGER,id INTEGER)");
        db.execSQL("CREATE TABLE addresses(city TEXT,lat REAL,lon REAL,x INTEGER,y INTEGER)");
        db.execSQL("CREATE TABLE places(name TEXT,lat REAL,lon REAL,x INTEGER,y INTEGER,kind TEXT,provenance TEXT)");
        db.execSQL("CREATE INDEX road_grid ON road_cells(x,y,id)");db.execSQL("CREATE INDEX area_grid ON area_cells(x,y,id)");
        db.execSQL("CREATE INDEX address_grid ON addresses(x,y)");db.execSQL("CREATE INDEX place_grid ON places(x,y)");
        return db;
    }
    private static void area(SQLiteDatabase db,int id,String name,String kind,String level,String rings) {
        db.execSQL("INSERT INTO areas VALUES(?,?,?,?,?,?,?)",new Object[]{id,name,rings,kind,
                kind.equals("administrative_locality") ? "osm_admin_boundary" : "osm_place_area",level,"relation:"+id});
        for(int x=0;x<=5;x++)for(int y=0;y<=5;y++) db.execSQL("INSERT INTO area_cells VALUES(?,?,?)",new Object[]{x,y,id});
    }
    private static final String SQUARE="[[[0,0],[0.02,0],[0.02,0.02],[0,0.02],[0,0]]]";
    static void run(Context c) throws Exception {
        try(SQLiteDatabase db=fixture(c)) {
            area(db,1,"Synthetic City","administrative_locality","8",SQUARE);
            area(db,2,"Synthetic Hamlet","hamlet","",SQUARE);
            area(db,3,"Synthetic District","suburb","9",SQUARE);
            db.execSQL("INSERT INTO places VALUES('Synthetic Hamlet',0.001,0.001,0,0,'hamlet','osm_place_node')");
            db.execSQL("INSERT INTO addresses VALUES('Synthetic Wrong City',0.001,0.001,0,0)");
        }
        PlaceResult known=resolve(c,.001,.001);
        check(known.locality().equals("Synthetic City") && known.localityQuality()==CONTAINMENT,"admin8 without road or place; nested hamlet/district and address cannot override");
        check(known.street().isEmpty() && known.streetQuality()==UNKNOWN && !known.complete(),"known locality with unknown parking street");
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(map(c).toString(),null,0)) {
            db.execSQL("INSERT INTO roads VALUES(1,'','T1','',0.001,0,0.001,0.02)");
            for(int x=0;x<=2;x++)db.execSQL("INSERT INTO road_cells VALUES(?,0,1)",new Object[]{x});
        }
        check(resolve(c,.001,.001).complete() && resolve(c,.001,.001).street().equals("T1"),"village/trunk road has locality without addr tag");
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(map(c).toString(),null,0)) {
            area(db,4,"Synthetic Other City","administrative_locality","8",SQUARE);
        }
        PlaceResult conflict=resolve(c,.001,.001);
        check(conflict.locality().isEmpty() && conflict.localityQuality()==AMBIGUOUS,"overlapping top-level boundaries are ambiguous");
        PlaceResult online=new PlaceResult("Synthetic Other City","Synthetic Online Road","photon",true,true);
        check(PlaceResult.enrich(conflict,online).localityQuality()==AMBIGUOUS,"online cannot invent certainty for conflicting boundaries");
        try(SQLiteDatabase db=fixture(c)) {
            area(db,1,"Synthetic Village","administrative_locality","8",
                    "[[[0,0],[0.02,0],[0.02,0.02],[0,0.02],[0,0]],[[0.005,0.005],[0.015,0.005],[0.015,0.015],[0.005,0.015],[0.005,0.005]]]");
            area(db,2,"Synthetic Village","administrative_locality","8","[[[0.03,0],[0.04,0],[0.04,0.01],[0.03,0.01],[0.03,0]]]");
        }
        check(resolve(c,.001,.001).certain(),"outer polygon inside");
        check(resolve(c,.01,.01).locality().isEmpty(),"hole is outside locality");
        check(resolve(c,.035,.005).locality().equals("Synthetic Village"),"disjoint polygon retained");
        check(resolve(c,0,.001).localityQuality()==AMBIGUOUS,"outer edge uncertain");
        check(resolve(c,.005,.01).localityQuality()==AMBIGUOUS,"hole edge uncertain");
        check(OfflinePlaces.containment(new JSONArray(SQUARE),.001,.001)==1,"ring parser");
        try(SQLiteDatabase db=fixture(c)) {
            db.execSQL("INSERT INTO places VALUES('Synthetic Village',0.01,0.01,1,1,'village','osm_place_node')");
            db.execSQL("INSERT INTO places VALUES('Synthetic Hamlet',0.001,0.001,0,0,'hamlet','osm_place_node')");
            db.execSQL("INSERT INTO places VALUES('Synthetic Dwelling',0.001,0.001,0,0,'isolated_dwelling','osm_place_node')");
        }
        PlaceResult nearby=resolve(c,.001,.001);
        check(nearby.locality().equals("Okolice Synthetic Village") && nearby.localityQuality()==NEARBY,"nearest hamlet/dwelling cannot hijack primary hint");
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(map(c).toString(),null,0)) {
            db.execSQL("INSERT INTO addresses VALUES('Synthetic Village',0.001,0.001,0,0)");
        }
        check(resolve(c,.001,.001).localityQuality()==ADDRESS,"address locality works without road");
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(map(c).toString(),null,0)) {
            db.execSQL("INSERT INTO addresses VALUES('Synthetic Other Village',0.001,0.0011,0,0)");
        }
        check(resolve(c,.001,.001).localityQuality()==AMBIGUOUS,"conflicting address evidence stays unknown");
        PlaceResult approximate=new PlaceResult("Okolice Synthetic Neighbor","Synthetic Online Road",NEARBY,MATCHED,"photon_place_node","photon_street",true);
        PlaceResult merged=PlaceResult.enrich(known,approximate);
        check(merged.locality().equals("Synthetic City") && merged.localityQuality()==CONTAINMENT
                && merged.street().equals("Synthetic Online Road") && merged.streetSource().equals("photon_street"),"online street enriches independent certain offline city");
        check(PlaceResult.enrich(known,online).street().isEmpty(),"different certain online city cannot attach its road");
        PlaceResult outside=new PlaceResult("Okolice Synthetic Village","","offline",false,true);
        check(PlaceResult.enrich(outside,merged.onlineEvidence()).locality().equals("Okolice Synthetic Village"),
                "cached photon street cannot resurrect offline containment just outside boundary");
        PlaceStabilizer stable=new PlaceStabilizer();
        check(stable.offer(known,1).locality().isEmpty(),"first distinct fix pending");
        check(stable.offer(merged,1).locality().isEmpty(),"two sources same fix are not two confirmations");
        check(stable.offer(known,2).locality().equals("Synthetic City"),"locality stabilizes independently from street");
        check(stable.offer(merged,2).street().isEmpty(),"street first confirmation");
        check(stable.offer(merged,3).complete(),"street stabilizes with distinct fix");
        check(stable.offer(online,4).locality().isEmpty(),"conflicting name hides former certainty");
        check(stable.offer(online,5).locality().equals("Synthetic Other City"),"new coherent distinct fixes stabilize");
        PlaceStabilizer assisted=new PlaceStabilizer();
        PlaceResult localHint=new PlaceResult("Okolice Synthetic Other City","","offline",false,true);
        assisted.offer(localHint,1);assisted.offer(PlaceResult.enrich(localHint,online),1);
        PlaceResult cached=PlaceResult.enrich(localHint,assisted.latest().onlineEvidence());
        check(assisted.offer(cached,2).locality().equals("Synthetic Other City"),"source oscillation keeps independent fix confirmation");
        check(assisted.offer(PlaceResult.enrich(localHint,online),2).locality().equals("Synthetic Other City"),"same-fix online enrichment retains confirmed locality");
        map(c).delete();
        try(OfflinePlaces missing=new OfflinePlaces(c)) {
            check(!missing.resolve(fix(0,0)).covered() && missing.status().equals("missing"),"missing map truthful without internet");
        }
    }
}
