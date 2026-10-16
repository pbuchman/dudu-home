package com.pbuchman.duduhome.ui;

import android.app.Instrumentation;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import com.pbuchman.duduhome.trip.PlaceResult;
import com.pbuchman.duduhome.trip.TripController;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Mock permission/channel availability, with the same text and real native notification delivery. */
public final class TripNotificationChecks {
    private TripNotificationChecks() { }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    private static void main(Instrumentation i,Runnable task) {
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        i.runOnMainSync(()->{try{task.run();}catch(Throwable error){failure.set(error);}});
        if(failure.get()!=null)throw new AssertionError(failure.get());
    }
    private static void notification(NotificationManager manager,boolean present) {
        long until=SystemClock.uptimeMillis()+2000;
        while(SystemClock.uptimeMillis()<until) {
            if(java.util.Arrays.stream(manager.getActiveNotifications()).anyMatch(n->n.getId()==18)==present)return;
            SystemClock.sleep(20);
        }
        throw new AssertionError("mock notification availability did not deliver/remove notice18");
    }
    public static void run(Instrumentation i)throws Exception {
        Context context=i.getTargetContext();NotificationManager manager=context.getSystemService(NotificationManager.class);
        check(manager.areNotificationsEnabled(),"notification fixture requires granted POST_NOTIFICATIONS");
        boolean[] available={true};TripPresentation[] panel={null};
        long generation=999991;
        TripController.Snapshot state=new TripController.Snapshot("ACTIVE",0,0,SystemClock.elapsedRealtime(),
                new PlaceResult("Synthetic City","Synthetic Road","offline",true,true),"",true,0,false,generation,null);
        try {
            main(i,()->{
                panel[0]=new TripPresentation(context,()->available[0]);
                try {var field=TripPresentation.class.getDeclaredField("handler");field.setAccessible(true);((Handler)field.get(panel[0])).removeCallbacksAndMessages(null);}
                catch(ReflectiveOperationException error){throw new AssertionError(error);}
                panel[0].render(state);
            });notification(manager,true);
            main(i,()->{
                available[0]=false;panel[0].render(state);
                // Model the OS removing a notice after permission/channel revocation.
                manager.cancel(18);
            });notification(manager,false);
            main(i,()->{available[0]=true;panel[0].render(state);});notification(manager,true);
            Class<?> trace=Class.forName("com.pbuchman.duduhome.trip.PlaceTrace");var writer=trace.getDeclaredField("writer");writer.setAccessible(true);
            ((ExecutorService)writer.get(null)).submit(()->{}).get(3,TimeUnit.SECONDS);
            ArrayList<Boolean> observed=new ArrayList<>();
            for(String line:Files.readAllLines(new File(context.getNoBackupFilesDir(),"trip-place-trace.jsonl").toPath())) {
                org.json.JSONObject row=new org.json.JSONObject(line);
                if(row.optString("event").equals("presentation")&&row.optString("surface").equals("trip_notification")&&row.optLong("generation")==generation)
                    observed.add(row.getBoolean("visible"));
            }
            check(observed.size()>=3&&observed.subList(observed.size()-3,observed.size()).equals(java.util.List.of(true,false,true)),
                    "identical-label permission/channel transitions must update visibility trace and restore notice");
        } finally {main(i,()->{if(panel[0]!=null)panel[0].close();});}
    }
}
