package com.pbuchman.duduhome.routebook;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.util.*;

/** All methods are short local transactions; the HTTP worker never holds this lock over I/O. */
public final class Outbox implements AutoCloseable {
    private final SQLiteDatabase db;
    private final String device;
    public Outbox(File file) {
        db=SQLiteDatabase.openOrCreateDatabase(file,null);
        db.execSQL("PRAGMA synchronous=FULL");
        int v=db.getVersion(); if(v!=0&&v!=1) { db.close(); throw new IllegalStateException("unsupported schema"); }
        db.beginTransaction();
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY,value TEXT NOT NULL)");
            db.execSQL("CREATE TABLE IF NOT EXISTS points (event_id TEXT PRIMARY KEY,segment_id TEXT NOT NULL,measured_at TEXT NOT NULL,lat REAL NOT NULL,lon REAL NOT NULL,accuracy REAL NOT NULL,speed REAL,status TEXT NOT NULL DEFAULT 'pending',code TEXT)");
            db.execSQL("CREATE INDEX IF NOT EXISTS pending_order ON points(status,measured_at,event_id)");
            db.execSQL("INSERT OR IGNORE INTO meta VALUES('device_id',?)",new Object[]{UUID.randomUUID().toString()});
            db.setVersion(1); db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        try(Cursor c=db.rawQuery("SELECT value FROM meta WHERE key='device_id'",null)) { c.moveToFirst(); device=c.getString(0); }
        if(!Point.uuid(device)) throw new IllegalStateException("invalid identity");
    }
    public String device() { return device; }
    public synchronized long highWater() {
        try(Cursor c=db.rawQuery("SELECT value FROM meta WHERE key='wall_high_water'",null)) { return c.moveToFirst()?Long.parseLong(c.getString(0)):0; }
    }
    public synchronized void insert(Point p) {
        db.beginTransaction();
        try {
            ContentValues v=new ContentValues(); v.put("event_id",p.eventId); v.put("segment_id",p.segmentId);
            v.put("measured_at",p.measuredAt); v.put("lat",p.lat); v.put("lon",p.lon); v.put("accuracy",p.accuracy);
            if(p.speed==null) v.putNull("speed"); else v.put("speed",p.speed);
            db.insertOrThrow("points",null,v);
            db.execSQL("INSERT OR IGNORE INTO meta VALUES('wall_high_water','0')");
            db.execSQL("UPDATE meta SET value=CAST(max(CAST(value AS INTEGER),?) AS TEXT) WHERE key='wall_high_water'",new Object[]{java.time.Instant.parse(p.measuredAt).toEpochMilli()});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    public synchronized List<Point> pending(boolean newest,int limit) {
        if(limit<1||limit>200) throw new IllegalArgumentException();
        List<Point> rows=new ArrayList<>(); String order=newest?"DESC":"ASC";
        try(Cursor c=db.rawQuery("SELECT event_id,segment_id,measured_at,lat,lon,accuracy,speed FROM points WHERE status='pending' ORDER BY measured_at "+order+",event_id "+order+" LIMIT "+limit,null)) {
            while(c.moveToNext()) rows.add(new Point(c.getString(0),c.getString(1),c.getString(2),c.getDouble(3),c.getDouble(4),c.getDouble(5),c.isNull(6)?null:c.getDouble(6)));
        } return rows;
    }
    public synchronized void apply(List<Protocol.Result> results) {
        db.beginTransaction();
        try {
            for(Protocol.Result r:results) {
                if(r.status().equals("accepted")||r.status().equals("duplicate")) db.delete("points","event_id=? AND status='pending'",new String[]{r.eventId()});
                else { ContentValues v=new ContentValues(); v.put("status","rejected"); v.put("code",r.code());
                    db.update("points",v,"event_id=? AND status='pending'",new String[]{r.eventId()}); }
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    public synchronized String counts() {
        long pending=0,invalid=0,conflict=0;
        try(Cursor c=db.rawQuery("SELECT status,code,count(*) FROM points GROUP BY status,code",null)) {
            while(c.moveToNext()) { if(c.getString(0).equals("pending")) pending+=c.getLong(2);
                else if("invalid_point".equals(c.getString(1))) invalid+=c.getLong(2); else conflict+=c.getLong(2); }
        }
        return "PENDING="+pending+" REJECTED_INVALID="+invalid+" REJECTED_CONFLICT="+conflict;
    }
    @Override public synchronized void close() { db.close(); }
}
