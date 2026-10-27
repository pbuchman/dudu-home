package com.pbuchman.duduhome.trip;

import android.app.Instrumentation;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import java.io.File;
import java.util.Arrays;
import org.json.JSONArray;

/** Read-only national-pack compatibility/latency check. Never prints names or coordinates. */
public final class NationalMapChecks {
    public static String run(Instrumentation i) {
        File file=new File(i.getTargetContext().getNoBackupFilesDir(),"places.sqlite");
        OfflinePlaces resolver=new OfflinePlaces(i.getTargetContext());
        long[] durations=new long[64];int covered=0,matched=0,wrong=0,areaSamples=0,areaMatched=0,areaAmbiguous=0;
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(file.toString(),null,SQLiteDatabase.OPEN_READONLY)) {
            try(Cursor c=db.rawQuery("SELECT value FROM metadata WHERE key='schema'",null)) {
                if(!c.moveToFirst() || !java.util.Set.of("2","3").contains(c.getString(0)))throw new AssertionError("schema2/3 national pack required");
            }
            try(Cursor c=db.rawQuery("SELECT count(*) FROM areas WHERE kind='administrative_locality' AND (admin_level!='8' OR provenance!='osm_admin_boundary' OR feature_id='')",null)) {
                c.moveToFirst();if(c.getInt(0)!=0)throw new AssertionError("national boundary provenance invalid");
            }
            long last;
            try(Cursor c=db.rawQuery("SELECT max(id) FROM roads",null)){c.moveToFirst();last=c.getLong(0);}
            if(last<1000000)throw new AssertionError("national pack required");
            for(int n=0;n<durations.length;n++) {
                try(Cursor c=db.rawQuery("SELECT a,b,c,d,name,ref FROM roads WHERE id=?",new String[]{""+(1+last*n/durations.length)})) {
                    if(!c.moveToFirst())throw new AssertionError("sample segment missing");
                    long now=SystemClock.elapsedRealtime();
                    TripFix fix=new TripFix(now,now,(c.getDouble(0)+c.getDouble(2))/2,(c.getDouble(1)+c.getDouble(3))/2,3,10,true,0,false,false);
                    long start=SystemClock.elapsedRealtime();PlaceResult result=resolver.resolve(fix);
                    durations[n]=SystemClock.elapsedRealtime()-start;if(result.covered())covered++;
                    String expected=!c.getString(4).isEmpty()?c.getString(4):!c.getString(5).isEmpty()?c.getString(5):"";
                    if(expected.equals(result.street())&&(expected.isEmpty()?result.streetQuality()==PlaceResult.Quality.UNNAMED:result.streetQuality()==PlaceResult.Quality.MATCHED))matched++;
                    else if(!result.street().isEmpty())wrong++;
                }
            }
            long lastArea;
            try(Cursor c=db.rawQuery("SELECT max(id) FROM areas",null)) { c.moveToFirst();lastArea=c.getLong(0); }
            if(lastArea<=0)throw new AssertionError("national locality areas required");
            for(int n=0;n<32;n++) {
                try(Cursor c=db.rawQuery("SELECT name,rings FROM areas WHERE id>=? AND kind='administrative_locality' ORDER BY id LIMIT 1",new String[]{""+(1+lastArea*n/32)})) {
                    if(!c.moveToFirst())continue;
                    double[] point=interior(c.getString(1));
                    if(point==null)continue;
                    long now=SystemClock.elapsedRealtime();
                    PlaceResult result=resolver.resolve(new TripFix(now,now,point[1],point[0],3,0,false,0,false,false));
                    areaSamples++;
                    if(result.localityQuality()==PlaceResult.Quality.AMBIGUOUS)areaAmbiguous++;
                    else if(result.localityQuality()==PlaceResult.Quality.CONTAINMENT && result.locality().equals(c.getString(0))
                            && result.localitySource().equals("osm_admin_boundary"))areaMatched++;
                    else throw new AssertionError("national interior did not retain locality boundary evidence");
                }
            }
        }
        Arrays.sort(durations);
        if(covered<60)throw new AssertionError("national road sample coverage="+covered+"/64");
        if(matched<45 || wrong>2)throw new AssertionError("national street matches="+matched+" wrong="+wrong);
        if(areaSamples<16 || areaMatched==0)throw new AssertionError("national containment sampling insufficient");
        resolver.close();
        return "national_samples=64 covered="+covered+" street_matches="+matched+" wrong="+wrong+" p95_ms="+durations[60]+" max_ms="+durations[63]
                +" schema="+resolver.schemaVersion()+" area_samples="+areaSamples+" area_matches="+areaMatched+" area_ambiguous="+areaAmbiguous;
    }
    /** Bounded 9x9 interior search; holes and concave polygons are checked with the runtime predicate. */
    private static double[] interior(String geometry) {
        try {
            JSONArray rings=new JSONArray(geometry);
            JSONArray outer=rings.getJSONArray(0);
            double west=180,east=-180,south=90,north=-90;
            for(int n=0;n<outer.length();n++) {
                JSONArray p=outer.getJSONArray(n);west=Math.min(west,p.getDouble(0));east=Math.max(east,p.getDouble(0));
                south=Math.min(south,p.getDouble(1));north=Math.max(north,p.getDouble(1));
            }
            for(int x=1;x<10;x++)for(int y=1;y<10;y++) {
                double lon=west+(east-west)*x/10,lat=south+(north-south)*y/10;
                if(OfflinePlaces.contains(rings,lon,lat))return new double[]{lon,lat};
            }
        } catch(Exception invalid) { throw new AssertionError("national polygon invalid",invalid); }
        return null;
    }
}
