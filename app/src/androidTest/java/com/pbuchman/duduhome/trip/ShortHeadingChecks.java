package com.pbuchman.duduhome.trip;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import java.io.File;
import java.util.Map;

/** Invented geography. Android SQLite/reader integration; no device calls, GPS provider or HTTP. */
final class ShortHeadingChecks {
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    private static File file(Context c) { return new File(c.getNoBackupFilesDir(),"places.sqlite"); }
    private static TripFix fix(double speed,boolean speedKnown,double bearing,boolean bearingKnown,double accuracy) {
        long now=SystemClock.elapsedRealtime();return new TripFix(now,now,0,0,accuracy,speed,speedKnown,bearing,bearingKnown,false);
    }
    private static void road(SQLiteDatabase db,int id,String name,double ax,double ay,double bx,double by) {
        db.execSQL("INSERT INTO roads VALUES(?,?, '', '',?,?,?,?)",new Object[]{id,name,ay/111195,ax/111195,by/111195,bx/111195});
        for(int x=(int)Math.floor(Math.min(ax,bx)/1111.95);x<=(int)Math.floor(Math.max(ax,bx)/1111.95);x++)
            for(int y=(int)Math.floor(Math.min(ay,by)/1111.95);y<=(int)Math.floor(Math.max(ay,by)/1111.95);y++)
                db.execSQL("INSERT INTO road_cells VALUES(?,?,?)",new Object[]{x,y,id});
    }
    private static SQLiteDatabase fixture(Context c,int schema) {
        file(c).delete();SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(file(c),null);
        db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT)");db.execSQL("INSERT INTO metadata VALUES('schema',?)",new Object[]{""+schema});
        String source="1".repeat(64);
        for(Map.Entry<String,String> entry:Map.of("source_sha256",source,"short_heading_source_sha256",source,
                "short_heading_policy",RoadHeading.POLICY,"short_heading_policy_sha256",RoadHeading.POLICY_HASH,
                "map_generation",RoadHeading.generation(source)).entrySet())db.execSQL("INSERT INTO metadata VALUES(?,?)",new Object[]{entry.getKey(),entry.getValue()});
        db.execSQL("CREATE TABLE roads(id INTEGER PRIMARY KEY,name TEXT,ref TEXT,city TEXT,a REAL,b REAL,c REAL,d REAL)");
        db.execSQL("CREATE TABLE road_cells(x INTEGER,y INTEGER,id INTEGER)");db.execSQL("CREATE INDEX road_grid ON road_cells(x,y,id)");
        db.execSQL("CREATE TABLE road_short_headings(road_id INTEGER PRIMARY KEY,heading REAL,support_length REAL)");
        db.execSQL("CREATE TABLE areas(id INTEGER,name TEXT,rings TEXT,kind TEXT,provenance TEXT,admin_level TEXT)");
        db.execSQL("CREATE TABLE area_cells(x INTEGER,y INTEGER,id INTEGER)");
        db.execSQL("INSERT INTO areas VALUES(1,'Synthetic City','[[[-0.001,-0.001],[0.001,-0.001],[0.001,0.001],[-0.001,0.001],[-0.001,-0.001]]]','administrative_locality','osm_admin_boundary','8')");
        for(int x=-1;x<=0;x++)for(int y=-1;y<=0;y++)db.execSQL("INSERT INTO area_cells VALUES(?,?,1)",new Object[]{x,y});
        db.execSQL("CREATE TABLE addresses(city TEXT,lat REAL,lon REAL,x INTEGER,y INTEGER)");
        db.execSQL("CREATE TABLE places(name TEXT,lat REAL,lon REAL,x INTEGER,y INTEGER,kind TEXT,provenance TEXT)");
        road(db,1,"Synthetic Main",-30,0,30,0);road(db,2,"",0,0,0,4);road(db,3,"",0,4,0,24);
        db.execSQL("INSERT INTO road_short_headings VALUES(2,0,14)");return db;
    }
    private static PlaceResult resolve(Context c,TripFix f) { try(OfflinePlaces offline=new OfflinePlaces(c)) {return offline.resolve(f);} }
    static void run(Context c) {
        try(SQLiteDatabase db=fixture(c,2)) { }
        check(resolve(c,fix(10,true,90,true,3)).streetQuality()==PlaceResult.Quality.AMBIGUOUS,"schema2 ignores side table and retains short-fragment score");
        try(SQLiteDatabase db=fixture(c,3)) { }
        try(OfflinePlaces offline=new OfflinePlaces(c)) {
            PlaceResult result=offline.resolve(fix(10,true,90,true,3));
            check(result.street().equals("Synthetic Main") && offline.roadMatch().supportedScored()==1,"supported short fragment resolves straight travel");
            check(result.locality().equals("Synthetic City") && result.localityQuality()==PlaceResult.Quality.CONTAINMENT
                    && result.localitySource().equals("osm_admin_boundary"),"schema3 retains independent schema2 locality");
        }
        check(resolve(c,fix(10,true,270,true,3)).street().equals("Synthetic Main"),"reverse travel");
        check(resolve(c,fix(10,true,0,true,3)).streetQuality()==PlaceResult.Quality.UNNAMED,"actual turn into unnamed road remains possible");
        check(resolve(c,fix(10,true,180,true,3)).streetQuality()==PlaceResult.Quality.UNNAMED,"reverse unnamed travel");
        for(TripFix f:new TripFix[]{fix(0,true,90,true,3),fix(2,true,90,true,3),fix(10,false,90,true,3),fix(10,true,0,false,3)})
            check(resolve(c,f).streetQuality()==PlaceResult.Quality.AMBIGUOUS,"stationary/no bearing/no speed keeps old ambiguity");
        check(!resolve(c,fix(10,true,90,true,30)).covered(),"poor GPS rejected");
        for(String mutation:new String[]{"UPDATE metadata SET value='wrong' WHERE key='short_heading_policy'",
                "DELETE FROM metadata WHERE key='short_heading_policy'","UPDATE road_short_headings SET support_length=26",
                "UPDATE road_short_headings SET heading=360","UPDATE road_short_headings SET heading='invalid'","DELETE FROM road_short_headings"}) {
            try(SQLiteDatabase db=fixture(c,3)) { db.execSQL(mutation); }
            PlaceResult result=resolve(c,fix(10,true,90,true,3));
            check(result.streetQuality()==PlaceResult.Quality.AMBIGUOUS && result.localitySource().equals("osm_admin_boundary"),"invalid support/policy falls back without locality downgrade");
        }
        try(SQLiteDatabase db=fixture(c,3)) { road(db,4,"Synthetic Parallel",-30,1,30,1); }
        check(resolve(c,fix(10,true,90,true,3)).streetQuality()==PlaceResult.Quality.AMBIGUOUS,"parallel road remains ambiguous");
        try(SQLiteDatabase db=fixture(c,3)) {
            for(int n=4;n<=2504;n++)road(db,n,"Synthetic Crowded "+n,-30,0,30,0);
        }
        try(OfflinePlaces offline=new OfflinePlaces(c)) {
            check(offline.resolve(fix(10,true,90,true,3)).streetQuality()==PlaceResult.Quality.AMBIGUOUS
                    && offline.roadMatch().outcome().equals("QUERY_LIMIT"),"same 2501 row query guard");
        }
        file(c).delete();
    }
}
