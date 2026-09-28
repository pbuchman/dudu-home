package com.pbuchman.duduhome.trip;

import android.app.Instrumentation;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import java.io.File;
import java.util.Arrays;

/** Read-only national-pack compatibility/latency check. Never prints names or coordinates. */
public final class NationalMapChecks {
    public static String run(Instrumentation i) {
        File file=new File(i.getTargetContext().getNoBackupFilesDir(),"places.sqlite");
        OfflinePlaces resolver=new OfflinePlaces(i.getTargetContext());
        long[] durations=new long[64];int covered=0,matched=0,wrong=0;
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(file.toString(),null,SQLiteDatabase.OPEN_READONLY)) {
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
                    String expected=!c.getString(4).isEmpty()?c.getString(4):!c.getString(5).isEmpty()?c.getString(5):"Ulica bez nazwy";
                    if(expected.equals(result.street()))matched++;
                    else if(!result.street().isEmpty())wrong++;
                }
            }
        }
        Arrays.sort(durations);
        if(covered<60)throw new AssertionError("national road sample coverage="+covered+"/64");
        if(matched<45 || wrong>2)throw new AssertionError("national street matches="+matched+" wrong="+wrong);
        return "national_samples=64 covered="+covered+" street_matches="+matched+" wrong="+wrong+" p95_ms="+durations[60]+" max_ms="+durations[63];
    }
}
