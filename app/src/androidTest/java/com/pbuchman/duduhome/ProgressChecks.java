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
    /** Keep failed assertions on the instrumentation thread instead of crashing the app. */
    static void main(Instrumentation i, Runnable work) {
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        i.runOnMainSync(() -> { try { work.run(); } catch (Throwable problem) { failure.set(problem); } });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    private static void awaitMain(Instrumentation i, java.util.function.BooleanSupplier condition,
            java.util.function.Supplier<String> diagnostic, String label) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        var ready = new java.util.concurrent.atomic.AtomicBoolean();
        var last = new java.util.concurrent.atomic.AtomicReference<String>();
        do {
            main(i, () -> { ready.set(condition.getAsBoolean()); if (!ready.get()) last.set(diagnostic.get()); });
            if (ready.get()) return;
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError(label + ": " + last.get());
    }
    private static void stopMonitorAndAwait(Instrumentation i, Context context) {
        context.stopService(new Intent(context, HomeMonitorService.class));
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            i.waitForIdleSync();
            boolean running = context.getSystemService(android.app.ActivityManager.class).getRunningServices(Integer.MAX_VALUE)
                    .stream().anyMatch(info -> info.service.getClassName().equals(HomeMonitorService.class.getName()));
            if (!running) return;
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("isolated progress presenter requires stopped monitor");
    }
    private static String presentationState(MainActivity activity, Context context) {
        var host = activity.progressHost(); var progress = ProgressBus.visible();
        return "host=" + (host != null) + " children=" + (host == null ? -1 : host.getChildCount())
                + " focus=" + activity.hasWindowFocus() + " owner=" + (HomeActions.visibleActivity() == activity)
                + " allowed=" + HomeActions.allowsExternalLaunch() + " busy=" + HomeActions.busy()
                + " monitor_ready=" + HomeMonitorService.ready(context) + " progress=" + (progress == null ? "none" : progress.phase());
    }
    private static void checkFloatingGestures(Instrumentation i,Context c,ProgressOverlay panel)throws Exception {
        var original=new java.util.concurrent.atomic.AtomicReference<List<ProgressModel.State>>();
        main(i,()->original.set(ProgressBus.snapshot()));
        final long heldAt=SystemClock.uptimeMillis();
        main(i,()->{
            View root=(View)field(panel,"overlay");MotionEvent e=MotionEvent.obtain(heldAt,heldAt,MotionEvent.ACTION_DOWN,8,8,0);
            root.dispatchTouchEvent(e);e.recycle();
        });SystemClock.sleep(400);
        main(i,()->{
            View root=(View)field(panel,"overlay");check(root.isActivated()&&root.getAlpha()<1f,"actual350ms timer arms visible hold feedback");
            MotionEvent e=MotionEvent.obtain(heldAt,SystemClock.uptimeMillis(),MotionEvent.ACTION_CANCEL,8,8,0);root.dispatchTouchEvent(e);e.recycle();
        });
        final int[] start={0,0};
        main(i,()->{
            View root=(View)field(panel,"overlay");root.getLocationOnScreen(start);
            nativeDrag(root,root.findViewById(R.id.banner_title),-90,0,349);
        });i.waitForIdleSync();
        main(i,()->{
            View root=(View)field(panel,"overlay");int[] at=new int[2];root.getLocationOnScreen(at);
            check(at[0]==start[0]&&at[1]==start[1],"349ms horizontal movement cannot drag or act");
            nativeDrag(root,root.findViewById(R.id.banner_title),-90,90,350);
        });i.waitForIdleSync();SystemClock.sleep(100);
        main(i,()->{
            View root=(View)field(panel,"overlay");int[] at=new int[2];root.getLocationOnScreen(at);
            check(at[0]<start[0]&&at[1]==start[1],"whole automation panel holds then drags horizontally");
            check(root.getAlpha()==1f&&!root.isActivated(),"release removes drag feedback");
            View title=root.findViewById(R.id.banner_title),cancel=root.findViewById(R.id.banner_cancel_attempt);
            int[] titleAt=new int[2],cancelAt=new int[2];title.getLocationOnScreen(titleAt);cancel.getLocationOnScreen(cancelAt);
            nativeDrag(root,title,cancelAt[0]+cancel.getWidth()/2f-titleAt[0]-title.getWidth()/2f,
                    cancelAt[1]+cancel.getHeight()/2f-titleAt[1]-title.getHeight()/2f,350);
        });i.waitForIdleSync();
        main(i,()->check(ProgressBus.snapshot().equals(original.get()),"non-button hold350 moveoverCancel release cannot cancel automation"));
        check(c.getSharedPreferences("progress_overlay_ui",0).contains("horizontal"),"automation position persisted separately");
        int[] saved=new int[2];
        main(i,()->{
            ((View)field(panel,"overlay")).getLocationOnScreen(saved);
            try {
                var remove=ProgressOverlay.class.getDeclaredMethod("removeOverlay");remove.setAccessible(true);remove.invoke(panel);
                var render=ProgressOverlay.class.getDeclaredMethod("render");render.setAccessible(true);render.invoke(panel);
            }catch(ReflectiveOperationException e){throw new AssertionError(e);}
        });i.waitForIdleSync();
        main(i,()->{
            int[] restored=new int[2];((View)field(panel,"overlay")).getLocationOnScreen(restored);
            check(restored[0]==saved[0]&&restored[1]==saved[1],"new automation window restores persisted bounded position");
        });
        capture(i,"progress-overlay-drag.png");
        checkNativeButtonStreams(i,((View)field(panel,"overlay")).getContext());
    }
    private static void checkNativeButtonStreams(Instrumentation i,Context context) throws Exception {
        int[] taps={0},drags={0};PanelDragLayout[] root={null};android.widget.Button[] button={null};
        WindowManager windows=context.getSystemService(WindowManager.class);
        main(i,()->{
            root[0]=new PanelDragLayout(context);button[0]=new android.widget.Button(context);button[0].setText("Synthetic button");
            button[0].setOnClickListener(v->taps[0]++);root[0].addView(button[0],new android.widget.LinearLayout.LayoutParams(200,80));
            root[0].setPanelDragListener((v,e)->{drags[0]++;return true;});
            WindowManager.LayoutParams p=new WindowManager.LayoutParams(240,100,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,android.graphics.PixelFormat.TRANSLUCENT);
            p.gravity=Gravity.TOP|Gravity.LEFT;p.setTitle("Synthetic button fixture");windows.addView(root[0],p);
        });i.waitForIdleSync();
        try {
            main(i,()->nativeDrag(root[0],button[0],0,0,20));i.waitForIdleSync();
            main(i,()->{
                check(taps[0]==1&&drags[0]==0,"attached native button tap stays native and never arms drag");
                nativeDrag(root[0],button[0],0,0,400);
            });i.waitForIdleSync();
            main(i,()->{
                check(taps[0]==2&&drags[0]==0,"button hold stays native and never becomes panel drag");
                nativeDrag(root[0],button[0],250,0,400);
            });i.waitForIdleSync();
            main(i,()->{
                check(taps[0]==2&&drags[0]==0,"button-origin move outside keeps native cancellation without panel drag");
                root[0].removeView(button[0]);root[0].setOnClickListener(v->taps[0]++);
                nativeDrag(root[0],root[0],-50,0,349);
            });i.waitForIdleSync();
            main(i,()->{
                check(taps[0]==2&&drags[0]==0,"349ms horizontal movement suppresses accidental passive open");
                nativeDrag(root[0],root[0],-50,0,350);
            });i.waitForIdleSync();
            main(i,()->check(taps[0]==2&&drags[0]>0,"exact350ms drag owns release and suppresses click"));
        } finally {main(i,()->windows.removeViewImmediate(root[0]));}
    }
    private static void nativeDrag(View root,View target,float dx,float dy,long hold) {
        int[] at=new int[2],origin=new int[2];target.getLocationOnScreen(at);root.getLocationOnScreen(origin);
        float x=at[0]-origin[0]+target.getWidth()/2f,y=at[1]-origin[1]+target.getHeight()/2f;
        long down=SystemClock.uptimeMillis();
        for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP}) {
            MotionEvent e=MotionEvent.obtain(down,down+(action==MotionEvent.ACTION_DOWN?0:hold),action,
                    action==MotionEvent.ACTION_DOWN?x:x+dx,action==MotionEvent.ACTION_DOWN?y:y+dy,0);
            root.dispatchTouchEvent(e);e.recycle();
            if(action==MotionEvent.ACTION_MOVE&&hold>=350&&!(target instanceof android.widget.Button))
                check(root.getAlpha()<1f&&root.isActivated(),"long press has visible drag feedback");
        }
    }
    static void run(Instrumentation i) throws Exception {
        Context c=i.getTargetContext();
        stopMonitorAndAwait(i, c);
        check(c.getSharedPreferences("progress_overlay_ui",0).edit().clear().commit(),"clear synthetic panel position");
        var prefs=c.getSharedPreferences("gate_settings",0);
        check(prefs.edit().putString("gate_number","000000000").remove("configured_at")
                .remove("last_dial_started_at").commit(),"synthetic settings");
        MainActivity activity=(MainActivity)i.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        i.waitForIdleSync();
        // The regular monitor owns production presentation. This test owns one isolated presenter.
        stopMonitorAndAwait(i, c);
        awaitMain(i, () -> activity.progressHost() != null && HomeActions.visibleActivity() == activity
                && HomeActions.allowsExternalLaunch() && !HomeActions.busy(),
                () -> presentationState(activity, c), "focused menu ready for isolated progress");
        ProgressOverlay[] panel={null};long[] epoch={0};
        Runnable broken=()->{throw new IllegalStateException("synthetic observer failure");};
        main(i,()->{
            epoch[0]=ProgressBus.reset(c,Reason.RESET);
            ProgressBus.subscribe(broken);
            panel[0]=new ProgressOverlay(c);
            ProgressBus.offer(c,epoch[0],ProgressBus.HOME,List.of(new DetectionProgress(Kind.DEPARTURE,1,
                    Phase.CANDIDATE,.6,SystemClock.elapsedRealtime()+15000,Reason.NONE)));
        });
        awaitMain(i, () -> activity.progressHost() != null && activity.progressHost().getChildCount() == 1
                && field(panel[0], "inline") != null,
                () -> presentationState(activity, c), "one inline banner becomes ready");
        main(i,()->{
            check(activity.progressHost()!=null&&activity.progressHost().getChildCount()==1,"one inline banner");
            check(field(panel[0],"overlay")==null,"no duplicate overlay");
            check(HomeActions.allowsExternalLaunch(),"inline banner does not block actions");
            check(!HomeActions.busy(),"banner holds no action lock");
            var view=(View)field(panel[0],"inline");
            check(((ProgressBar)view.findViewById(R.id.banner_progress)).getProgress()==600,"detector fill rendered");
        });
        capture(i,"progress-menu.png");
        prefs.edit().putLong("configured_at",System.currentTimeMillis()).commit();
        main(i,()->{
            activity.findViewById(R.id.open_gate_button).performClick();
            check(field(panel[0],"inline")==null&&field(panel[0],"overlay")==null,"manual action takes presentation priority");
            check(!prefs.contains("last_dial_started_at"),"manual cooldown cannot dial");
        });
        SystemClock.sleep(2800);
        prefs.edit().remove("configured_at").commit();
        main(i,()->{
            check(activity.progressHost()!=null,"manual result returns to menu");
            ProgressBus.offer(c,epoch[0],ProgressBus.HOME,List.of(new DetectionProgress(Kind.DEPARTURE,2,
                    Phase.CANDIDATE,.6,SystemClock.elapsedRealtime()+15000,Reason.NONE)));
        });
        main(i,activity::finish);
        c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        SystemClock.sleep(700);
        main(i,()->{
            View view=(View)field(panel[0],"overlay");check(view!=null&&view.isAttachedToWindow(),"overlay attached above external app");
            check(field(panel[0],"inline")==null,"inline removed");
            WindowManager.LayoutParams p=(WindowManager.LayoutParams)view.getLayoutParams();
            check(p.type==WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,"overlay type");
            float density=view.getResources().getDisplayMetrics().density;
            check(view.getWidth()<=Math.round(350*density)&&view.getHeight()>=Math.round(91*density),"compact action350dp/min91dp");
            check(((android.widget.TextView)view.findViewById(R.id.banner_cancel_attempt)).getText().toString().equals("Anuluj"),"floating action uses short native cancellation label");
            check((p.flags&WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)!=0,"no focus");
            check((p.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)==0,"bounded overlay receives cancellation touches");
            check((p.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)!=0,"touches outside pass through");
            check((p.flags&WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)==0,"no screen wake");
            check(p.alpha<=.8f,"touch obscuring limit");
            check(HomeActions.allowsExternalLaunch(),"overlay cannot change action eligibility");
        });
        capture(i,"progress-overlay.png");
        checkFloatingGestures(i,c,panel[0]);
        main(i,()-> {
            epoch[0]=ProgressBus.reset(c,Reason.RESET);
            ProgressBus.offer(c,epoch[0],ProgressBus.HOME,List.of(new DetectionProgress(Kind.RETURN,3,
                    Phase.CANDIDATE,.08,SystemClock.elapsedRealtime()+15000,Reason.NONE,Stage.APPROACHING_JUNCTION)));
            var view=(View)field(panel[0],"overlay");
            check(((android.widget.TextView)view.findViewById(R.id.banner_detail)).getText().toString()
                    .equals(c.getString(R.string.progress_return_checking_route)),"early route copy");
        });
        capture(i,"progress-return-early.png");
        main(i,()-> {
            ProgressBus.offer(c,epoch[0],ProgressBus.HOME,List.of(new DetectionProgress(Kind.RETURN,3,
                    Phase.CANDIDATE,.68,SystemClock.elapsedRealtime()+15000,Reason.NONE,Stage.APPROACHING_GATE)));
            var view=(View)field(panel[0],"overlay");
            check(((android.widget.TextView)view.findViewById(R.id.banner_detail)).getText().toString()
                    .equals(c.getString(R.string.progress_return_detail)),"inbound route copy");
            check(((ProgressBar)view.findViewById(R.id.banner_progress)).getProgress()==680,"inbound fill");
        });
        capture(i,"progress-return-inbound.png");
        main(i,()->{
            epoch[0]=ProgressBus.reset(c,Reason.GPS_UNRELIABLE);
            ProgressBus.offer(c,epoch[0]-1,ProgressBus.HOME,List.of(new DetectionProgress(Kind.DEPARTURE,1,
                    Phase.CONFIRMED,1,SystemClock.elapsedRealtime()+15000,Reason.NONE)));
            check(ProgressBus.visible().phase()==Phase.CANCELLED,"late observation discarded after reset");
        });
        capture(i,"progress-cancelled.png");
        SystemClock.sleep(2100);
        main(i,()->{
            check(ProgressBus.visible()==null,"cancelled banner expires");
            check(field(panel[0],"overlay")==null,"window removed after cancellation");
            ProgressBus.unsubscribe(broken);
        });
        // Suppress actual Activity delivery; the real token/timeout path must terminate without dial.
        Intent[] captured={null};
        Context sink=new ContextWrapper(c){@Override public void startActivity(Intent intent){captured[0]=intent;}};
        main(i,()->HomeActions.dispatch(sink,HomeEvent.RETURN_APPROACH));
        check(captured[0]!=null,"request dispatched to test sink");
        SystemClock.sleep(5300);
        main(i,()->{
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
        main(i,()->{
            preview.findViewById(R.id.menu_content).setVisibility(View.GONE);
            preview.findViewById(R.id.call_status_content).setVisibility(View.VISIBLE);
            ((android.widget.TextView)preview.findViewById(R.id.status_title)).setText("Dzwonię do bramy");
            ((android.widget.TextView)preview.findViewById(R.id.status_description)).setText("Połączenie przez telefon Bluetooth");
        });
        capture(i,"progress-action.png");
        main(i,preview::finish);
        Diagnostics.record(c,"PROGRESS_JOURNAL_TEST");
        check(Diagnostics.awaitWrites(),"journal final drain");
        File journal=new File(c.getNoBackupFilesDir(),"diagnostics.txt");
        check(journal.length()>0 && journal.length()<=1024*1024,"one MiB segment bound; rotation checked by host journal suite");
        long size=journal.length();Diagnostics.record(c,"invalid payload\nphone or coordinates");
        check(journal.length()==size,"unsafe diagnostic payload rejected");
    }
}
