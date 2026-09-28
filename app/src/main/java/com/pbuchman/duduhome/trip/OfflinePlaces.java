package com.pbuchman.duduhome.trip;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import org.json.JSONArray;

/** Read-only, bounded spatial queries. The computer validates and atomically installs this file. */
public final class OfflinePlaces {
    private final File file;
    private SQLiteDatabase db;
    private volatile String status = "missing";
    private long stamp;
    public OfflinePlaces(Context context) { file = new File(context.getNoBackupFilesDir(), "places.sqlite"); }
    public String status() { return status; }
    public void prepare() { open(); }
    private boolean open() {
        try {
            if (!file.isFile()) { status = "missing"; return false; }
            if (db != null && stamp == file.lastModified()) return true;
            if (db != null) db.close();
            db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            try (Cursor c = db.rawQuery("SELECT value FROM metadata WHERE key='schema'", null)) {
                if (!c.moveToFirst() || !"1".equals(c.getString(0))) throw new IllegalArgumentException("Map schema");
            }
            stamp = file.lastModified(); status = "ready"; return true;
        } catch (RuntimeException invalid) { if (db != null) db.close(); db = null; status = "invalid"; return false; }
    }
    public PlaceResult resolve(TripFix fix) {
        if (!open() || !fix.valid()) return PlaceResult.unknown(false);
        try {
            int x = (int)Math.floor(fix.longitude()*100), y = (int)Math.floor(fix.latitude()*100);
            String[] cells = {String.valueOf(x-1), String.valueOf(x+1), String.valueOf(y-1), String.valueOf(y+1)};
            String street = "", city = "";
            double best = 55, second = Double.POSITIVE_INFINITY;
            double latitudeRadius=75.0/111195;
            double longitudeRadius=latitudeRadius/Math.max(.1,Math.cos(Math.toRadians(fix.latitude())));
            double west=fix.longitude()-longitudeRadius,east=fix.longitude()+longitudeRadius;
            double south=fix.latitude()-latitudeRadius,north=fix.latitude()+latitudeRadius;
            String[] roadBounds={""+(int)Math.floor(west*100),""+x,""+(int)Math.floor(east*100),
                    ""+(int)Math.floor(south*100),""+y,""+(int)Math.floor(north*100),
                    ""+west,""+east,""+south,""+north};
            int examined=0;
            try (Cursor c = db.rawQuery("SELECT DISTINCT r.name,r.ref,r.city,r.a,r.b,r.c,r.d FROM roads r JOIN road_cells g ON r.id=g.id WHERE g.x IN (?,?,?) AND g.y IN (?,?,?) AND max(r.b,r.d)>=CAST(? AS REAL) AND min(r.b,r.d)<=CAST(? AS REAL) AND max(r.a,r.c)>=CAST(? AS REAL) AND min(r.a,r.c)<=CAST(? AS REAL) LIMIT 2501", roadBounds)) {
                while (c.moveToNext()) {
                    examined++;
                    double ax = east(c.getDouble(4)-fix.longitude(), fix.latitude()), ay = north(c.getDouble(3)-fix.latitude());
                    double bx = east(c.getDouble(6)-fix.longitude(), fix.latitude()), by = north(c.getDouble(5)-fix.latitude());
                    double dx=bx-ax, dy=by-ay, length=dx*dx+dy*dy;
                    double t = length == 0 ? 0 : Math.max(0, Math.min(1, -(ax*dx+ay*dy)/length));
                    double distance = Math.hypot(ax+t*dx, ay+t*dy);
                    double score = distance;
                    if (fix.hasBearing() && fix.hasSpeed() && fix.speed()>2 && length>25) {
                        double angle = Math.abs((Math.toDegrees(Math.atan2(dx,dy))-fix.bearing()+540)%360-180);
                        angle = Math.min(angle, 180-angle); score += angle/6;
                    }
                    String name = c.getString(0); if (name.isEmpty()) name = c.getString(1);
                    if (name.isEmpty()) name = "Ulica bez nazwy";
                    if (score < best) {
                        if (!name.equals(street)) second = best;
                        best = score; street = name; city = c.getString(2);
                    } else if (!name.equals(street)) second = Math.min(second, score);
                }
            }
            if (examined>2500 || second-best < Math.max(4, fix.accuracy()/2)) { street = ""; city = ""; }
            boolean certain = !city.isEmpty();
            // Only settlement areas, never administrative municipalities, qualify as containment.
            try (Cursor c = db.rawQuery("SELECT DISTINCT a.name,a.rings FROM areas a JOIN area_cells g ON a.id=g.id WHERE g.x=? AND g.y=? LIMIT 100", new String[]{""+x,""+y})) {
                String contained = "";
                while (c.moveToNext()) if (contains(new JSONArray(c.getString(1)), fix.longitude(), fix.latitude())) {
                    if (!contained.isEmpty() && !contained.equals(c.getString(0))) { contained = ""; break; }
                    contained = c.getString(0);
                }
                if (!contained.isEmpty()) { city = contained; certain = true; }
            }
            if (city.isEmpty() && !street.isEmpty()) {
                // An address close to this road can supply its settlement, but conflicting evidence cannot.
                try (Cursor c = db.rawQuery("SELECT city,lat,lon FROM addresses WHERE x BETWEEN ? AND ? AND y BETWEEN ? AND ? LIMIT 2000", cells)) {
                    String candidate = ""; boolean conflict = false;
                    while (c.moveToNext()) if (Math.hypot(east(c.getDouble(2)-fix.longitude(),fix.latitude()),north(c.getDouble(1)-fix.latitude())) < 40) {
                        if (!candidate.isEmpty() && !candidate.equals(c.getString(0))) conflict = true;
                        candidate = c.getString(0);
                    }
                    if (!conflict && !candidate.isEmpty()) { city = candidate; certain = true; }
                }
            }
            if (city.isEmpty()) {
                double nearest = 8000;
                try (Cursor c = db.rawQuery("SELECT name,lat,lon FROM places WHERE x BETWEEN ? AND ? AND y BETWEEN ? AND ? LIMIT 1000", new String[]{""+(x-15),""+(x+15),""+(y-8),""+(y+8)})) {
                    while (c.moveToNext()) {
                        double d = Math.hypot(east(c.getDouble(2)-fix.longitude(),fix.latitude()),north(c.getDouble(1)-fix.latitude()));
                        if (d<nearest) { nearest=d; city="Okolice " + c.getString(0); }
                    }
                }
            }
            return new PlaceResult(city, street, "offline", certain, !city.isEmpty() || !street.isEmpty());
        } catch (Exception invalid) { status = "invalid"; return PlaceResult.unknown(false); }
    }
    private static double east(double lon, double lat) { return lon*111195*Math.cos(Math.toRadians(lat)); }
    private static double north(double lat) { return lat*111195; }
    static boolean contains(JSONArray rings, double x, double y) throws Exception {
        boolean inside=false;
        for (int r=0;r<rings.length();r++) {
            JSONArray ring=rings.getJSONArray(r); boolean hit=false;
            for(int i=0,j=ring.length()-1;i<ring.length();j=i++) {
                JSONArray a=ring.getJSONArray(i), b=ring.getJSONArray(j);
                double ax=a.getDouble(0), ay=a.getDouble(1), bx=b.getDouble(0), by=b.getDouble(1);
                if ((ay>y)!=(by>y) && x<(bx-ax)*(y-ay)/(by-ay)+ax) hit=!hit;
            }
            if(hit) inside=!inside;
        }
        return inside;
    }
}
