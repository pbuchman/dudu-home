package com.pbuchman.duduhome;

import android.app.*;
import android.content.*;
import android.os.SystemClock;
import android.view.View;
import com.pbuchman.duduhome.ui.*;
import com.pbuchman.duduhome.trip.*;
import com.pbuchman.duduhome.automation.*;

final class TripUiChecks {
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    static void state(TripController c,String target) {
        for(int n=0;n<50;n++){if(c.snapshot().state().equals(target))return;SystemClock.sleep(50);}throw new AssertionError(target);
    }
    static void run(Instrumentation i)throws Exception {
        Context c=i.getTargetContext();TripController controller=TripController.get(c);
        controller.command("START");state(controller,"ACTIVE");
        i.runOnMainSync(()->ProgressBus.reset(c,DetectionProgress.Reason.RESET));SystemClock.sleep(2200);
        c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(500);
        TripPresentation[] presentation={null};
        var monitor=i.addMonitor(TripActivity.class.getName(),null,false);
        i.runOnMainSync(()->presentation[0]=new TripPresentation(c));SystemClock.sleep(600);
        i.runOnMainSync(()->{
            View overlay=(View)ProgressChecks.field(presentation[0],"overlay");check(overlay!=null,"background trip overlay");overlay.performClick();
        });
        TripActivity trip=(TripActivity)i.waitForMonitorWithTimeout(monitor,5000);check(trip!=null,"overlay tap opens trip");
        i.waitForIdleSync();
        i.runOnMainSync(()->check(HomeActions.allowsMediaLaunch(),"passive trip screen allows media automation"));
        var notices=c.getSystemService(NotificationManager.class).getActiveNotifications();
        PendingIntent open=null;
        for(var notice:notices)if(notice.getId()==18)open=notice.getNotification().contentIntent;
        check(open!=null,"ongoing trip notification has content intent");
        i.runOnMainSync(()->trip.findViewById(R.id.trip_primary).performClick());state(controller,"PAUSED");
        i.runOnMainSync(trip::finish);SystemClock.sleep(300);
        monitor=i.addMonitor(TripActivity.class.getName(),null,false);
        i.runOnMainSync(()->check(HomeActions.begin(),"synthetic protected action"));
        open.send();SystemClock.sleep(700);
        check(!TripActivity.visible,"notification cannot cover protected action");
        i.runOnMainSync(HomeActions::end);
        TripActivity deferred=(TripActivity)i.waitForMonitorWithTimeout(monitor,5000);check(deferred!=null,"notification opens after action cleanup");
        i.runOnMainSync(()->deferred.findViewById(R.id.trip_end).performClick());state(controller,"FINISHED");
        i.runOnMainSync(()->{deferred.finish();presentation[0].close();});i.removeMonitor(monitor);
    }
}
