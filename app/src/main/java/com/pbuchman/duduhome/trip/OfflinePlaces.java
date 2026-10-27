package com.pbuchman.duduhome.trip;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import org.json.JSONArray;
import static com.pbuchman.duduhome.trip.PlaceResult.Quality.*;

/** Read-only, bounded spatial queries. The computer validates and atomically installs this file. */
public final class OfflinePlaces implements AutoCloseable {
    private final File file;
    private SQLiteDatabase db;
    private volatile String status = "missing";
    private volatile int schema;
    private boolean headingPolicy;
    private String mapGeneration="";
    private long stamp, bytes;
    record RoadMatch(String candidate,double best,double second,double threshold,int examined,String outcome,
                     int supportedScored,String bestHeadingSource,double bestSupport,String mapGeneration) { }
    private volatile RoadMatch roadMatch=unavailable(4);
    private RoadMatch unavailable(double threshold) { return new RoadMatch("",55,Double.POSITIVE_INFINITY,threshold,0,"UNAVAILABLE",0,"NONE",0,mapGeneration); }
    RoadMatch roadMatch() { return roadMatch; }
    private record RoadAddress(String street,String city,double score) { }
    public OfflinePlaces(Context context) { file = new File(context.getNoBackupFilesDir(), "places.sqlite"); }
    public String status() { return status; }
    public int schemaVersion() { return schema; }
    public void prepare() { open(); }
    public void close() { if (db != null) db.close(); db = null; headingPolicy=false;mapGeneration=""; }
    private boolean open() {
        try {
            if (!file.isFile()) { close(); schema = 0; status = "missing"; return false; }
            if (db != null && stamp == file.lastModified() && bytes == file.length()) return true;
            close();
            db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            try (Cursor c = db.rawQuery("SELECT value FROM metadata WHERE key='schema'", null)) {
                if (!c.moveToFirst()) throw new IllegalArgumentException("Map schema");
                schema = Integer.parseInt(c.getString(0));
                if (schema != 1 && schema != 2 && schema != 3) throw new IllegalArgumentException("Map schema");
            }
            if (schema==3) {
                java.util.HashMap<String,String> metadata=new java.util.HashMap<>();
                try (Cursor c=db.rawQuery("SELECT key,value FROM metadata",null)) {
                    while(c.moveToNext())metadata.put(c.getString(0),c.getString(1));
                }
                headingPolicy=RoadHeading.policy(metadata);
                if(headingPolicy)mapGeneration=metadata.get("map_generation");
            }
            stamp = file.lastModified(); bytes = file.length(); status = "ready"; return true;
        } catch (RuntimeException invalid) { close(); schema = 0; status = "invalid"; return false; }
    }
    public PlaceResult resolve(TripFix fix) {
        roadMatch=unavailable(Math.max(4,fix.accuracy()/2));
        if (!fix.valid() || !open()) return PlaceResult.unknown(false);
        try {
            int x = (int)Math.floor(fix.longitude()*100), y = (int)Math.floor(fix.latitude()*100);
            String street = "", roadCity = "";
            boolean roadFound=false;
            double best = 55, second = Double.POSITIVE_INFINITY;
            double latitudeRadius = 75.0/111195;
            double longitudeRadius = latitudeRadius/Math.max(.1,Math.cos(Math.toRadians(fix.latitude())));
            double west=fix.longitude()-longitudeRadius,east=fix.longitude()+longitudeRadius;
            double south=fix.latitude()-latitudeRadius,north=fix.latitude()+latitudeRadius;
            String[] roadBounds={""+(int)Math.floor(west*100),""+x,""+(int)Math.floor(east*100),
                    ""+(int)Math.floor(south*100),""+y,""+(int)Math.floor(north*100),
                    ""+west,""+east,""+south,""+north};
            int examined=0,supportedScored=0;String bestHeadingSource="NONE";double bestSupport=0;
            java.util.ArrayList<RoadAddress> roadAddresses=new java.util.ArrayList<>();
            String supportColumns=headingPolicy ? ",h.heading,h.support_length" : ",NULL,NULL";
            String supportJoin=headingPolicy ? " LEFT JOIN road_short_headings h ON h.road_id=r.id" : "";
            try (Cursor c = db.rawQuery("SELECT DISTINCT r.id,r.name,r.ref,r.city,r.a,r.b,r.c,r.d"+supportColumns+" FROM roads r JOIN road_cells g ON r.id=g.id"+supportJoin+" WHERE g.x IN (?,?,?) AND g.y IN (?,?,?) AND max(r.b,r.d)>=CAST(? AS REAL) AND min(r.b,r.d)<=CAST(? AS REAL) AND max(r.a,r.c)>=CAST(? AS REAL) AND min(r.a,r.c)<=CAST(? AS REAL) ORDER BY r.id LIMIT 2501", roadBounds)) {
                while (c.moveToNext()) {
                    examined++;
                    double ax = east(c.getDouble(5)-fix.longitude(), fix.latitude()), ay = north(c.getDouble(4)-fix.latitude());
                    double bx = east(c.getDouble(7)-fix.longitude(), fix.latitude()), by = north(c.getDouble(6)-fix.latitude());
                    double dx=bx-ax, dy=by-ay, length=dx*dx+dy*dy;
                    double t = length == 0 ? 0 : Math.max(0, Math.min(1, -(ax*dx+ay*dy)/length));
                    double score = Math.hypot(ax+t*dx, ay+t*dy);
                    String headingSource="NONE";double support=0;
                    if (fix.hasBearing() && fix.hasSpeed() && fix.speed()>2) {
                        if(length>25) {
                            score+=RoadHeading.penalty(Math.toDegrees(Math.atan2(dx,dy)),fix.bearing());headingSource="SEGMENT";
                        } else if(length>0 && numeric(c,8) && numeric(c,9) && RoadHeading.valid(c.getDouble(8),c.getDouble(9))) {
                            score+=RoadHeading.penalty(c.getDouble(8),fix.bearing());headingSource="SHORT_SAME_OSM_WAY";
                            support=c.getDouble(9);supportedScored++;
                        }
                    }
                    String name = c.getString(1); if (name.isEmpty()) name = c.getString(2);
                    String taggedCity=c.getString(3);
                    if (!taggedCity.isEmpty()) roadAddresses.add(new RoadAddress(name,taggedCity,score));
                    if (score < best) {
                        if (roadFound && !name.equals(street)) second = best;
                        roadFound=true;best=score; street=name; roadCity=taggedCity;bestHeadingSource=headingSource;bestSupport=support;
                    } else if (!roadFound || !name.equals(street)) second=Math.min(second,score);
                }
            }
            boolean roadConflict = examined>2500 || second-best<Math.max(4,fix.accuracy()/2);
            roadMatch=new RoadMatch(street,best,second,Math.max(4,fix.accuracy()/2),examined,
                    examined>2500 ? "QUERY_LIMIT" : roadConflict ? "MARGIN_AMBIGUOUS" : !roadFound ? "NO_MATCH" : street.isEmpty() ? "UNNAMED" : "MATCHED",
                    supportedScored,bestHeadingSource,bestSupport,mapGeneration);
            if (roadConflict) { street=""; roadCity=""; }
            boolean roadAddressConflict=false;
            if (roadFound && !roadConflict) {
                roadCity="";
                for (RoadAddress address:roadAddresses) if (address.street.equals(street)
                        && address.score-best<Math.max(4,fix.accuracy()/2)) {
                    if (!roadCity.isEmpty() && !roadCity.equals(address.city)) roadAddressConflict=true;
                    roadCity=address.city;
                }
            }
            PlaceResult.Quality streetQuality=roadConflict ? AMBIGUOUS : !roadFound ? UNKNOWN : street.isEmpty() ? UNNAMED : MATCHED;

            // Locality containment runs even in a parking area with no matched road.
            String city="", citySource="offline", contained="";
            PlaceResult.Quality cityQuality=UNKNOWN;
            int priority=0, areaCount=0; boolean boundaryConflict=false;
            String columns=schema>=2 ? "a.kind,a.provenance,a.admin_level" : "'legacy','offline_legacy_area',''";
            try (Cursor c=db.rawQuery("SELECT DISTINCT a.id,a.name,a.rings,"+columns+" FROM areas a JOIN area_cells g ON a.id=g.id WHERE g.x=? AND g.y=? ORDER BY a.id LIMIT 101",new String[]{""+x,""+y})) {
                while (c.moveToNext()) {
                    areaCount++;
                    String kind=c.getString(3);
                    int rank=kind.equals("administrative_locality") && c.getString(5).equals("8") ? 2
                            : java.util.Set.of("city","town","village","legacy").contains(kind) ? 1 : 0;
                    if (rank==0) continue;
                    int containment=containment(new JSONArray(c.getString(2)),fix.longitude(),fix.latitude());
                    if (containment==0 || rank<priority) continue;
                    if (rank>priority) { priority=rank; contained=""; boundaryConflict=false; }
                    if (containment==2 || (!contained.isEmpty() && !contained.equals(c.getString(1)))) boundaryConflict=true;
                    if (containment==1) { contained=c.getString(1); citySource=c.getString(4); }
                }
            }
            if (areaCount>100 || boundaryConflict) cityQuality=AMBIGUOUS;
            else if (!contained.isEmpty()) { city=contained; cityQuality=CONTAINMENT; }
            if (cityQuality==UNKNOWN) {
                String address=roadCity; boolean conflict=roadAddressConflict; int count=0;
                try (Cursor c=db.rawQuery("SELECT rowid,city,lat,lon FROM addresses WHERE x BETWEEN ? AND ? AND y BETWEEN ? AND ? ORDER BY rowid LIMIT 2001",new String[]{""+(x-1),""+(x+1),""+(y-1),""+(y+1)})) {
                    while (c.moveToNext()) {
                        count++;
                        if (Math.hypot(east(c.getDouble(3)-fix.longitude(),fix.latitude()),north(c.getDouble(2)-fix.latitude()))>=40) continue;
                        String name=c.getString(1);
                        if (!address.isEmpty() && !address.equals(name)) conflict=true;
                        address=name;
                    }
                }
                if (count>2000 || conflict) cityQuality=AMBIGUOUS;
                else if (!address.isEmpty()) { city=address; cityQuality=ADDRESS; citySource=roadCity.isEmpty() ? "offline_address" : "offline_road_address"; }
            }
            if (cityQuality==UNKNOWN) {
                double nearest=8000; int count=0; boolean tie=false;
                String kindFilter=schema>=2 ? " AND kind IN ('city','town','village')" : "";
                try (Cursor c=db.rawQuery("SELECT rowid,name,lat,lon FROM places WHERE x BETWEEN ? AND ? AND y BETWEEN ? AND ?"+kindFilter+" ORDER BY rowid LIMIT 1001",new String[]{""+(x-15),""+(x+15),""+(y-8),""+(y+8)})) {
                    while (c.moveToNext()) {
                        count++;
                        double d=Math.hypot(east(c.getDouble(3)-fix.longitude(),fix.latitude()),north(c.getDouble(2)-fix.latitude()));
                        if (d<nearest-1) { nearest=d; city="Okolice "+c.getString(1); tie=false; }
                        else if (Math.abs(d-nearest)<=1 && !city.equals("Okolice "+c.getString(1))) tie=true;
                    }
                }
                if (count>1000 || tie) { city=""; cityQuality=AMBIGUOUS; }
                else if (!city.isEmpty()) { cityQuality=NEARBY; citySource=schema>=2 ? "offline_place_node" : "offline_legacy_point"; }
            }
            return new PlaceResult(city,street,cityQuality,streetQuality,citySource,"offline_road",!city.isEmpty() || roadFound || cityQuality==AMBIGUOUS || roadConflict);
        } catch (Exception invalid) { status="invalid"; return PlaceResult.unknown(false); }
    }
    private static double east(double lon,double lat) { return lon*111195*Math.cos(Math.toRadians(lat)); }
    private static boolean numeric(Cursor cursor,int column) {
        return cursor.getType(column)==Cursor.FIELD_TYPE_FLOAT || cursor.getType(column)==Cursor.FIELD_TYPE_INTEGER;
    }
    private static double north(double lat) { return lat*111195; }
    static boolean contains(JSONArray rings,double x,double y) throws Exception { return containment(rings,x,y)==1; }
    /** 0 outside, 1 inside, 2 on an edge. First ring is outer; subsequent rings are holes. */
    static int containment(JSONArray rings,double x,double y) throws Exception {
        if (rings.length()==0) return 0;
        int outer=ring(rings.getJSONArray(0),x,y);
        if (outer!=1) return outer;
        for (int r=1;r<rings.length();r++) {
            int hole=ring(rings.getJSONArray(r),x,y);
            if (hole==2) return 2;
            if (hole==1) return 0;
        }
        return 1;
    }
    private static int ring(JSONArray ring,double x,double y) throws Exception {
        boolean hit=false;
        for(int i=0,j=ring.length()-1;i<ring.length();j=i++) {
            JSONArray a=ring.getJSONArray(i), b=ring.getJSONArray(j);
            double ax=a.getDouble(0),ay=a.getDouble(1),bx=b.getDouble(0),by=b.getDouble(1);
            double cross=(x-ax)*(by-ay)-(y-ay)*(bx-ax);
            if (Math.abs(cross)<=1e-12 && x>=Math.min(ax,bx)-1e-10 && x<=Math.max(ax,bx)+1e-10
                    && y>=Math.min(ay,by)-1e-10 && y<=Math.max(ay,by)+1e-10) return 2;
            if ((ay>y)!=(by>y) && x<(bx-ax)*(y-ay)/(by-ay)+ax) hit=!hit;
        }
        return hit ? 1 : 0;
    }
}
