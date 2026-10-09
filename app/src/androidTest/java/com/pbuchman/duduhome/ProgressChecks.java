package com.pbuchman.duduhome;

import android.app.Instrumentation;
import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.ProgressBar;
import com.pbuchman.duduhome.automation.*;
import com.pbuchman.duduhome.diagnostics.Diagnostics;
import com.pbuchman.duduhome.location.HomeMonitorService;
import com.pbuchman.duduhome.ui.*;
import java.io.*;
import java.util.*;
import static com.pbuchman.duduhome.automation.DetectionProgress.*;

/** Called only by the emulator-guarded instrumentation. Synthetic observations never dispatch. */
final class ProgressChecks {
    static void check(boolean ok, String message) { if(!ok)throw new AssertionError(message); }
    static Object field(Object object,String name) {
        try { var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object); }
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    static void capture(Instrumentation i,String name) throws IOException {
        SystemClock.sleep(300);
        android.graphics.Bitmap b=i.getUiAutomation().takeScreenshot();
        check(b!=null,"screenshot available");
        try(var out=new FileOutputStream(new File(i.getTargetContext().getExternalFilesDir(null),name))){
            b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);
        }
        b.recycle();
    }
    static void run(Instrumentation i) throws Exception {
        Context c=i.getTargetContext();
        c.stopService(new Intent(c,HomeMonitorService.class));
        SystemClock.sleep(300);
        var prefs=c.getSharedPreferences("gate_settings",0);
        check(prefs.edit().putString("gate_number","000000000").remove("configured_at")
                .remove("last_dial_started_at").commit(),"synthetic settings");
        MainActivity activity=(MainActivity)i.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        i.waitForIdleSync();
        // The regular monitor owns production presentation. This test owns one isolated presenter.
        c.stopService(new Intent(c,HomeMonitorService.class));SystemClock.sleep(300);
        ProgressOverlay[] panel={null};long[] epoch={0};
        Runnable broken=()->{throw new IllegalStateException("synthetic observer failure");};
        i.runOnMainSync(()->{
            epoch[0]=ProgressBus.reset(c,Reason.RESET);
            ProgressBus.subscribe(broken);
            panel[0]=new ProgressOverlay(c);
            ProgressBus.offer(c,epoch[0],ProgressBus.HOME,List.of(new DetectionProgress(Kind.DEPARTURE,1,
                    Phase.CANDIDATE,.6,SystemClock.elapsedRealtime()+15000,Reason.NONE)));
        });
        SystemClock.sleep(400);
        i.runOnMainSync(()->{
            check(activity.progressHost()!=null&&activity.progressHost().getChildCount()==1,"one inline banner");
            check(field(panel[0],"overlay")==null,"no duplicate overlay");
            check(HomeActions.allowsExternalLaunch(),"inline banner does not block actions");
            check(!HomeActions.busy(),"banner holds no action lock");
            var view=(View)field(panel[0],"inline");
            check(((ProgressBar)view.findViewById(R.id.banner_progress)).getProgress()==600,"detector fill rendered");
        });
        capture(i,"progress-menu.png");
        prefs.edit().putLong("configured_at",System.currentTimeMillis()).commit();
        i.runOnMainSync(()->{
            activity.findViewById(R.id.open_gate_button).performClick();
            check(field(panel[0],"inline")==null&&field(panel[0],"overlay")==null,"manual action takes presentation priority");
            check(!prefs.contains("last_dial_started_at"),"manual cooldown cannot dial");
        });
        SystemClock.sleep(2800);
        prefs.edit().remove("configured_at").commit();
        i.runOnMainSync(()->{
            check(activity.progressHost()!=null,"manual result returns to menu");
            ProgressBus.offer(c,epoch[0],ProgressBus.HOME,List.of(new DetectionProgress(Kind.DEPARTURE,2,
                    Phase.CANDIDATE,.6,SystemClock.elapsedRealtime()+15000,Reason.NONE)));
        });
        i.runOnMainSync(activity::finish);
        c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        SystemClock.sleep(700);
        i.runOnMainSync(()->{
            View view=(View)field(panel[0],"overlay");check(view!=null&&view.isAttachedToWindow(),"overlay attached above external app");
            check(field(panel[0],"inline")==null,"inline removed");
            WindowManager.LayoutParams p=(WindowManager.LayoutParams)view.getLayoutParams();
            check(p.type==WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,"overlay type");
            check((p.flags&WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)!=0,"no focus");
            check((p.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)==0,"bounded overlay receives cancellation touches");
            check((p.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)!=0,"touches outside pass through");
            check((p.flags&WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)==0,"no screen wake");
            check(p.alpha<=.8f,"touch obscuring limit");
            check(HomeActions.allowsExternalLaunch(),"overlay cannot change action eligibility");
        });
        capture(i,"progress-overlay.png");
        i.runOnMainSync(()-> {
            epoch[0]=ProgressBus.reset(c,Reason.RESET);
            ProgressBus.offer(c,epoch[0],ProgressBus.HOME,List.of(new DetectionProgress(Kind.RETURN,3,
                    Phase.CANDIDATE,.08,SystemClock.elapsedRealtime()+15000,Reason.NONE,Stage.APPROACHING_JUNCTION)));
            var view=(View)field(panel[0],"overlay");
            check(((android.widget.TextView)view.findViewById(R.id.banner_detail)).getText().toString()
                    .equals(c.getString(R.string.progress_return_checking_route)),"early route copy");
        });
        capture(i,"progress-return-early.png");
        i.runOnMainSync(()-> {
            ProgressBus.offer(c,epoch[0],ProgressBus.HOME,List.of(new DetectionProgress(Kind.RETURN,3,
                    Phase.CANDIDATE,.68,SystemClock.elapsedRealtime()+15000,Reason.NONE,Stage.APPROACHING_GATE)));
            var view=(View)field(panel[0],"overlay");
            check(((android.widget.TextView)view.findViewById(R.id.banner_detail)).getText().toString()
                    .equals(c.getString(R.string.progress_return_detail)),"inbound route copy");
            check(((ProgressBar)view.findViewById(R.id.banner_progress)).getProgress()==680,"inbound fill");
        });
        capture(i,"progress-return-inbound.png");
        i.runOnMainSync(()->{
            epoch[0]=ProgressBus.reset(c,Reason.GPS_UNRELIABLE);
            ProgressBus.offer(c,epoch[0]-1,ProgressBus.HOME,List.of(new DetectionProgress(Kind.DEPARTURE,1,
                    Phase.CONFIRMED,1,SystemClock.elapsedRealtime()+15000,Reason.NONE)));
            check(ProgressBus.visible().phase()==Phase.CANCELLED,"late observation discarded after reset");
        });
        capture(i,"progress-cancelled.png");
        SystemClock.sleep(2100);
        i.runOnMainSync(()->{
            check(ProgressBus.visible()==null,"cancelled banner expires");
            check(field(panel[0],"overlay")==null,"window removed after cancellation");
            ProgressBus.unsubscribe(broken);
        });
        // Suppress actual Activity delivery; the real token/timeout path must terminate without dial.
        Intent[] captured={null};
        Context sink=new ContextWrapper(c){@Override public void startActivity(Intent intent){captured[0]=intent;}};
        i.runOnMainSync(()->HomeActions.dispatch(sink,HomeEvent.RETURN_APPROACH));
        check(captured[0]!=null,"request dispatched to test sink");
        SystemClock.sleep(5300);
        i.runOnMainSync(()->{
            check(HomeActions.consumeRequest(c,captured[0])==null,"expired intent cannot execute");
            check(ProgressBus.snapshot().stream().anyMatch(s->s.kind()==Kind.RETURN&&s.phase()==Phase.SKIPPED
                    &&s.reason()==Reason.UI_UNAVAILABLE),"nondelivery has correlated outcome");
            check(!prefs.contains("last_dial_started_at"),"snapshots and timeout never dial");
            panel[0].close();
            try {
                HomeMonitorService stopped=new HomeMonitorService();
                var destroyed=HomeMonitorService.class.getDeclaredField("destroyed");destroyed.setAccessible(true);destroyed.setBoolean(stopped,true);
                var before=ProgressBus.snapshot();
                var journey=c.getSharedPreferences("journey_session",0);
                var saved=new java.util.HashMap<>(journey.getAll());
                var callback=HomeMonitorService.class.getDeclaredMethod("onCycleRead",com.pbuchman.duduhome.startup.DuduCycle.Observation.class);
                callback.setAccessible(true);
                callback.invoke(stopped,new com.pbuchman.duduhome.startup.DuduCycle.Observation(
                        com.pbuchman.duduhome.startup.DuduCycle.Kind.AWAKE_COUNTER,999));
                check(before.equals(ProgressBus.snapshot()),"late cycle callback cannot reset UI after stop");
                check(saved.equals(journey.getAll()),"late cycle callback cannot update session");
            } catch(ReflectiveOperationException e){throw new AssertionError(e);}
        });
        // Screenshot of the existing execution view only. No Bluetooth or HTTP worker is started.
        MainActivity preview=(MainActivity)i.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        i.runOnMainSync(()->{
            preview.findViewById(R.id.menu_content).setVisibility(View.GONE);
            preview.findViewById(R.id.call_status_content).setVisibility(View.VISIBLE);
            ((android.widget.TextView)preview.findViewById(R.id.status_title)).setText("Dzwonię do bramy");
            ((android.widget.TextView)preview.findViewById(R.id.status_description)).setText("Połączenie przez telefon Bluetooth");
        });
        capture(i,"progress-action.png");
        i.runOnMainSync(preview::finish);
        String category="PROGRESS_TEST_"+"A".repeat(170);
        for(int n=0;n<1800;n++)Diagnostics.record(c,category);
        File journal=new File(c.getNoBackupFilesDir(),"diagnostics.txt");
        File previous=new File(c.getNoBackupFilesDir(),"diagnostics.previous.txt");
        check(previous.exists()&&previous.length()<=128*1024+300&&journal.length()<=128*1024+300,"existing two-file rotation bound");
        long size=journal.length();Diagnostics.record(c,"invalid payload\nphone or coordinates");
        check(journal.length()==size,"unsafe diagnostic payload rejected");
    }
}
