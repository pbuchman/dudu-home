package com.pbuchman.duduhome.routebook;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.util.*;

/** Separate FULL-sync database: an old APK can reopen routebook.sqlite version 1 unchanged. */
public final class PowerOutbox implements AutoCloseable {
    private final SQLiteDatabase db;
    private final String device;
    public PowerOutbox(File file,String device) {
        if(!Point.uuid(device))throw new IllegalArgumentException(); this.device=device;
        db=SQLiteDatabase.openOrCreateDatabase(file,null); db.execSQL("PRAGMA synchronous=FULL");
        if(db.getVersion()!=0&&db.getVersion()!=1){db.close();throw new IllegalStateException("power schema");}
        db.beginTransaction();
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS identity(device_id TEXT NOT NULL)");
            db.execSQL("INSERT INTO identity SELECT ? WHERE NOT EXISTS(SELECT 1 FROM identity)",new Object[]{device});
            try(Cursor c=db.rawQuery("SELECT device_id FROM identity",null)){if(!c.moveToFirst()||!device.equals(c.getString(0)))throw new IllegalStateException("power identity");}
            db.execSQL("CREATE TABLE IF NOT EXISTS events(position INTEGER PRIMARY KEY AUTOINCREMENT,event_id TEXT NOT NULL UNIQUE,payload TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'pending',code TEXT)");
            db.execSQL("CREATE INDEX IF NOT EXISTS power_pending ON events(status,position)");
            db.setVersion(1); db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }
    public String device(){return device;}
    public static String existingDevice(File file){
        try(SQLiteDatabase saved=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READONLY);
            Cursor c=saved.rawQuery("SELECT device_id FROM identity",null)){
            if(saved.getVersion()!=1||!c.moveToFirst()||!Point.uuid(c.getString(0)))throw new IllegalStateException("power identity unavailable");
            return c.getString(0);
        }
    }
    public synchronized void insert(PowerEvent event){ContentValues v=new ContentValues();v.put("event_id",event.eventId());v.put("payload",event.json());db.insertOrThrow("events",null,v);}
    public synchronized List<PowerEvent> pending(boolean newest,int limit){
        if(limit<1||limit>200)throw new IllegalArgumentException(); List<PowerEvent> rows=new ArrayList<>();
        try(Cursor c=db.rawQuery("SELECT payload FROM events WHERE status='pending' ORDER BY position "+(newest?"DESC":"ASC")+" LIMIT "+limit,null)){while(c.moveToNext())rows.add(PowerEvent.parse(c.getString(0)));}return rows;
    }
    public synchronized void apply(List<Protocol.Result> results){
        db.beginTransaction();try{for(Protocol.Result r:results){
            if(r.status().equals("accepted")||r.status().equals("duplicate"))db.delete("events","event_id=? AND status='pending'",new String[]{r.eventId()});
            else {ContentValues v=new ContentValues();v.put("status","rejected");v.put("code",r.code());db.update("events",v,"event_id=? AND status='pending'",new String[]{r.eventId()});}
        }db.setTransactionSuccessful();}finally{db.endTransaction();}
    }
    public synchronized String counts(){long pending=0,rejected=0;try(Cursor c=db.rawQuery("SELECT status,count(*) FROM events GROUP BY status",null)){while(c.moveToNext()){if(c.getString(0).equals("pending"))pending=c.getLong(1);else rejected=c.getLong(1);}}return "POWER_PENDING="+pending+" POWER_REJECTED="+rejected;}
    public synchronized void close(){db.close();}
}
