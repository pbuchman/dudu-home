package com.pbuchman.duduhome;

import android.Manifest;
import android.app.Instrumentation;
import android.content.*;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import com.pbuchman.duduhome.automation.*;
import com.pbuchman.duduhome.location.HomeMonitorService;
import com.pbuchman.duduhome.trip.*;
import com.pbuchman.duduhome.ui.*;
import java.io.*;

/** Focused emulator-only checks. Run seed, force-stop, then restore (optionally after wm resize). */
final class TripOverlayChecks {
    private static final String LONG_ROAD = "Przykładowa Aleja Bardzo Długich Błękitnych Obłoków Testowych przy Bajkowych Wzgórzach i Srebrzystych Ogrodach";
    static void run(Instrumentation i, String phase) throws Exception {
        Context c=i.getTargetContext();
        TripUiChecks.checkPreferenceFailure(c);
        ProgressChecks.check(c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=android.content.pm.PackageManager.PERMISSION_GRANTED,
                "focused overlay runner requires GPS permission disabled");
        c.stopService(new Intent(c,HomeMonitorService.class));
        SharedPreferences receipt=c.getSharedPreferences("trip_overlay_check",0);
        if(phase.equals("seed")) {
            // Invented durable counter fixture, no location fix/network/resolver or action executor.
            try(var out=new FileOutputStream(new File(c.getNoBackupFilesDir(),"trip-state.json"))) {
                out.write("{\"state\":\"ACTIVE\",\"generation\":73,\"meters\":12840}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            c.getSharedPreferences("trip_overlay_ui",0).edit().clear().commit();
        } else {
            ProgressChecks.check(phase.equals("restore") || phase.equals("visible"),"known overlay phase");
            ProgressChecks.check(receipt.getInt("pid",-1)!=android.os.Process.myPid(),"verification runs in a restarted app process");
        }
        TripController controller=TripController.get(c);
        for(int n=0;n<100&&!controller.snapshot().loaded();n++)SystemClock.sleep(50);
        TripController.Snapshot before=controller.snapshot();
        ProgressChecks.check(before.active() && before.generation()==73 && before.meters()==12840,"durable synthetic trip retained");
        TripOverlayPreferences prefs=new TripOverlayPreferences(c);
        if(!phase.equals("seed")) {
            ProgressChecks.check(prefs.horizontal()==receipt.getFloat("horizontal",-1) && prefs.vertical()==receipt.getFloat("vertical",-1),"drag position survived process restart");
            ProgressChecks.check(prefs.visible()==phase.equals("visible"),"dismiss/restore choice survived process restart");
        }
        main(i,()->ProgressBus.reset(c,DetectionProgress.Reason.RESET));SystemClock.sleep(2200);
        c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(500);
        TripPresentation[] panel={null};
        main(i,()->{ panel[0]=new TripPresentation(c); ((Handler)ProgressChecks.field(panel[0],"handler")).removeCallbacksAndMessages(null);panel[0].render(before); });
        i.waitForIdleSync();
        if(phase.equals("restore")) {
            main(i,()->ProgressChecks.check(overlay(panel[0])==null,"dismissed panel stays hidden in new process"));
            TripActivity trip=(TripActivity)i.startActivitySync(new Intent(c,TripActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            main(i,()->{trip.findViewById(R.id.trip_overlay_toggle).performClick();trip.finish();});i.waitForIdleSync();
            main(i,()->panel[0].render(before));
        }
        main(i,()->{
            View view=overlay(panel[0]);ProgressChecks.check(view!=null,"active overlay restored");
            WindowManager.LayoutParams p=(WindowManager.LayoutParams)view.getLayoutParams();
            ProgressChecks.check((p.flags&WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)!=0,"overlay cannot steal focus");
            ProgressChecks.check((p.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)==0,"bounded overlay remains touchable");
        });
        i.waitForIdleSync();
        main(i,()->{
            if(phase.equals("seed")) checkAtEdge(overlay(panel[0]),false);
            else checkAtEdge(overlay(panel[0]),false);
        });
        var opened=i.addMonitor(TripActivity.class.getName(),null,false);
        if(phase.equals("seed")) {
            int[] initial=new int[2];
            main(i,()->{
                View view=overlay(panel[0]);view.getLocationOnScreen(initial);
                dragBy(view.findViewById(R.id.trip_locality),-view.getWidth()/3f,view.getHeight()/2f);
            });
            i.waitForIdleSync();SystemClock.sleep(400);
            main(i,()->{
                int[] moved=new int[2];overlay(panel[0]).getLocationOnScreen(moved);
                ProgressChecks.check(moved[0]<initial[0]&&moved[1]==initial[1],
                        "interior drag moves card horizontally only: initial="+initial[0]+","+initial[1]+" moved="+moved[0]+","+moved[1]);
            });
            ProgressChecks.check(opened.getHits()==0&&!TripActivity.visible,"interior drag release never opens full screen");
        }
        main(i,()->dragToBottomRight(overlay(panel[0]).findViewById(R.id.trip_locality)));
        i.waitForIdleSync();SystemClock.sleep(400);
        ProgressChecks.check(opened.getHits()==0&&!TripActivity.visible,"drag release never opens full screen");i.removeMonitor(opened);
        main(i,()->panel[0].render(fixture(before,LONG_ROAD)));i.waitForIdleSync();
        // An idle main queue may precede the next WindowManager/Choreographer layout frame.
        // Capture first also preserves synthetic pixels if a geometry assertion fails.
        ProgressChecks.capture(i,"trip-overlay-moved-"+phase+".png");
        main(i,()->checkBounds(overlay(panel[0])));
        float x=prefs.horizontal(),y=prefs.vertical();
        main(i,()->{ProgressChecks.check(HomeActions.begin(),"synthetic suppression lease");panel[0].render(before);ProgressChecks.check(overlay(panel[0])==null,"automation suppression removes overlay");HomeActions.end();panel[0].render(fixture(before,LONG_ROAD));});
        i.waitForIdleSync();
        ProgressChecks.capture(i,"trip-overlay-suppression-"+phase+".png");
        main(i,()->checkBounds(overlay(panel[0])));
        ProgressChecks.check(prefs.horizontal()==x && prefs.vertical()==y,"transient suppression preserves chosen position");
        if(phase.equals("seed")) {
            main(i,()->panel[0].render(fixture(before,"Aleja Błękitnych Chmur Testowych")));i.waitForIdleSync();
            ProgressChecks.capture(i,"trip-overlay-compact-named.png");
            main(i,()->panel[0].render(fixture(before,"")));i.waitForIdleSync();
            ProgressChecks.capture(i,"trip-overlay-compact-unnamed.png");
            main(i,()->ProgressChecks.check(overlay(panel[0]).findViewById(R.id.trip_street).getVisibility()==View.GONE,"typed unnamed actual overlay hides street row"));
            main(i,()->overlay(panel[0]).findViewById(R.id.trip_overlay_close).performClick());
            main(i,()->{panel[0].render(before);ProgressChecks.check(overlay(panel[0])==null,"close remains hidden on next rendering");});
        }
        TripController.Snapshot after=controller.snapshot();
        ProgressChecks.check(after.state().equals(before.state())&&after.generation()==before.generation()&&after.meters()==before.meters(),"overlay controls preserve active counter, generation and nonzero total");
        ProgressChecks.check(receipt.edit().putInt("pid",android.os.Process.myPid()).putFloat("horizontal",prefs.horizontal()).putFloat("vertical",prefs.vertical()).commit(),"restart test receipt saved");
        main(i,panel[0]::close);
        Class<?> trace=Class.forName("com.pbuchman.duduhome.trip.PlaceTrace");var writer=trace.getDeclaredField("writer");writer.setAccessible(true);
        ((java.util.concurrent.ExecutorService)writer.get(null)).submit(()->{}).get(3,java.util.concurrent.TimeUnit.SECONDS);
        boolean shown=false,hidden=false;
        for(String line:java.nio.file.Files.readAllLines(new File(c.getNoBackupFilesDir(),"trip-place-trace.jsonl").toPath())) {
            org.json.JSONObject row=new org.json.JSONObject(line);
            if(!row.optString("event").equals("presentation")||!row.optString("surface").equals("trip_overlay"))continue;
            shown|=row.optBoolean("visible")&&row.optString("title").equals("Miejscowość Testowa")&&row.optString("subtitle").equals(LONG_ROAD)&&row.isNull("fix_reference");
            hidden|=!row.optBoolean("visible")&&row.optString("title").isEmpty()&&row.optString("subtitle").isEmpty();
        }
        ProgressChecks.check(shown&&hidden,"private trace records actual synthetic overlay labels/visibility without invented fix reference");
    }
    private static void main(Instrumentation i,Runnable action) {
        java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        i.runOnMainSync(()->{try { action.run(); } catch(Throwable error) { failure.set(error); }});
        if(failure.get()!=null)throw new AssertionError(failure.get().getMessage(),failure.get());
    }
    private static View overlay(TripPresentation panel) { return (View)ProgressChecks.field(panel,"overlay"); }
    private static TripController.Snapshot fixture(TripController.Snapshot before,String street) {
        return new TripController.Snapshot(before.state(),before.generation(),before.meters(),SystemClock.elapsedRealtime(),
                street.isEmpty()?new PlaceResult("Miejscowość Testowa","",PlaceResult.Quality.CONTAINMENT,PlaceResult.Quality.UNNAMED,"offline_boundary","offline_road",true)
                        :new PlaceResult("Miejscowość Testowa",street,"offline",true,true),"",true);
    }
    private static void dragToBottomRight(View handle) { dragBy(handle,10000,10000); }
    private static void dragBy(View handle,float dx,float dy) {
        View root=handle.getRootView();
        int[] at=new int[2],origin=new int[2];handle.getLocationOnScreen(at);root.getLocationOnScreen(origin);
        long time=SystemClock.uptimeMillis();float x=at[0]-origin[0]+handle.getWidth()/2f,y=at[1]-origin[1]+handle.getHeight()/2f;
        int n=0;
        for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP}) {
            MotionEvent event=MotionEvent.obtain(time,time+(n++==0?0:400),action,action==MotionEvent.ACTION_DOWN?x:x+dx,
                    action==MotionEvent.ACTION_DOWN?y:y+dy,0);
            root.dispatchTouchEvent(event);event.recycle();
        }
    }
    private static void checkAtEdge(View view,boolean bottom) {
        WindowManager wm=view.getContext().getSystemService(WindowManager.class);
        WindowMetrics metrics=wm.getCurrentWindowMetrics();
        Rect bounds=metrics.getBounds();
        Insets inset=metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
        int margin=Math.round(16*view.getResources().getDisplayMetrics().density);
        int[] at=new int[2];view.getLocationOnScreen(at);
        ProgressChecks.check(Math.abs(at[0]+view.getWidth()-(bounds.width()-inset.right-margin))<=1,"initial rendered card restores saved right edge");
        ProgressChecks.check(bottom ? Math.abs(at[1]+view.getHeight()-(bounds.height()-inset.bottom-margin))<=1
                : Math.abs(at[1]-(inset.top+margin))<=1,"initial rendered card restores saved vertical edge before any new drag");
    }
    private static String geometry(View view) {
        int[] at=new int[2];view.getLocationOnScreen(at);
        return "screen("+at[0]+","+at[1]+") size("+view.getWidth()+","+view.getHeight()+") measured("
                +view.getMeasuredWidth()+","+view.getMeasuredHeight()+") local("+view.getLeft()+","+view.getTop()+","+view.getRight()+","+view.getBottom()+")";
    }
    private static void checkBounds(View view) {
        ProgressChecks.check(view!=null,"overlay present for geometry check");
        ProgressChecks.check(view.getWidth()<=Math.round(286*view.getResources().getDisplayMetrics().density),"location card maximum286dp");
        WindowManager wm=view.getContext().getSystemService(WindowManager.class);
        Rect bounds=wm.getCurrentWindowMetrics().getBounds();
        Insets inset=wm.getCurrentWindowMetrics().getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
        Rect safe=new Rect(inset.left,inset.top,bounds.width()-inset.right,bounds.height()-inset.bottom);
        int[] at=new int[2];view.getLocationOnScreen(at);
        ProgressChecks.check(safe.contains(at[0],at[1],at[0]+view.getWidth(),at[1]+view.getHeight()),"whole moved long-name card stays within current bars/cutout bounds: safe="+safe+" card="+geometry(view));
        for(int id:new int[]{R.id.trip_overlay_close}) {
            View control=view.findViewById(id);Rect visible=new Rect();
            boolean shown=control.getGlobalVisibleRect(visible);
            ProgressChecks.check(shown&&visible.height()==control.getHeight()&&visible.width()==control.getWidth(),
                    "move/hide control fully reachable: id="+view.getResources().getResourceEntryName(id)+" visible="+shown+" rect="+visible
                    +" control="+geometry(control)+" parent="+geometry((View)control.getParent())+" card="+geometry(view));
        }
    }
}
