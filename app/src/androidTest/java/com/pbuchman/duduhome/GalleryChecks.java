package com.pbuchman.duduhome;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.pbuchman.duduhome.ui.*;
import com.pbuchman.duduhome.automation.*;
import com.pbuchman.duduhome.navigation.*;
import com.pbuchman.duduhome.trip.*;
import com.pbuchman.duduhome.gate.*;
import com.pbuchman.duduhome.roborock.*;
import com.pbuchman.duduhome.location.HomeMonitorService;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import static com.pbuchman.duduhome.automation.DetectionProgress.*;

/** Emulator-only renderer capture. No exported demo UI, network, Binder calls or routine execution. */
final class GalleryChecks {
    private static final List<String> shots=new ArrayList<>();
    static void invoke(Object target,String method,Class<?>[] types,Object... args) {
        try { Method m=target.getClass().getDeclaredMethod(method,types);m.setAccessible(true);m.invoke(target,args); }
        catch(Exception e) { throw new AssertionError(method,e); }
    }
    static void field(Object target,String name,Object value) {
        try { Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value); }
        catch(Exception e) { throw new AssertionError(name,e); }
    }
    static void shell(Instrumentation i,String command) throws Exception {
        try(var stream=new android.os.ParcelFileDescriptor.AutoCloseInputStream(i.getUiAutomation().executeShellCommand(command))) {stream.readAllBytes();}
    }
    static void shot(Instrumentation i,String name) throws Exception { ProgressChecks.capture(i,name+".png");shots.add(name+".png"); }
    static void menu(Instrumentation i,MainActivity a) { i.runOnMainSync(()->{
        View icon=a.findViewById(R.id.status_icon);
        icon.removeCallbacks((Runnable)ProgressChecks.field(a,"finishAfterSuccess"));
        icon.removeCallbacks((Runnable)ProgressChecks.field(a,"finishAfterInformation"));
        invoke(a,"showMenu",new Class<?>[]{});
    });i.waitForIdleSync(); }
    static void action(Instrumentation i,MainActivity a,HomeAction action,String title,String detail,boolean success,boolean error) {
        menu(i,a);
        i.runOnMainSync(()-> {
            field(a,"currentAction",action);invoke(a,"updateActionIllustration",new Class<?>[]{});
            a.findViewById(R.id.menu_content).setVisibility(View.GONE);a.findViewById(R.id.call_status_content).setVisibility(View.VISIBLE);
            ((TextView)a.findViewById(R.id.status_title)).setText(title);((TextView)a.findViewById(R.id.status_description)).setText(detail);
            a.findViewById(R.id.progress).setVisibility(success||error?View.GONE:View.VISIBLE);
            ImageView icon=a.findViewById(R.id.status_icon);icon.setVisibility(success||error?View.VISIBLE:View.GONE);
            icon.setImageResource(success?R.drawable.ic_success:R.drawable.ic_error);icon.setAlpha(1);icon.setScaleX(1);icon.setScaleY(1);
            a.findViewById(R.id.error_actions).setVisibility(error?View.VISIBLE:View.GONE);
            ProgressChecks.check(!HomeActions.busy(),"capture owns no executor");
        });
    }
    static TripController.Snapshot snapshot(String state,PlaceResult place,long fix) {
        return new TripController.Snapshot(state,1,state.equals("OFF") ? 0 : 12840,fix,place,"",true);
    }
    static void run(Instrumentation i) throws Exception {
        Context c=i.getTargetContext();
        // Capture runner clears emulator data and grants no GPS permission: monitor startup is blocked.
        ProgressChecks.check(c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED,"gallery GPS permission disabled");

        c.stopService(new Intent(c,HomeMonitorService.class));SystemClock.sleep(300);
        c.getSharedPreferences("gate_settings",0).edit().putString("gate_number","000000000").commit();
        new NavigationStore(c).save(NavigationConfig.parse("{\"schema_version\":2,\"slots\":[{\"slot\":1,\"label\":\"Cel pokazowy\",\"icon\":\"home\",\"destinations\":[{\"label\":\"Cel pokazowy\",\"icon\":\"pin\",\"latitude\":0,\"longitude\":0}]},{\"slot\":2,\"label\":\"Sport\",\"icon\":\"squash\",\"destinations\":[{\"label\":\"Hala Alfa\",\"icon\":\"pin\",\"latitude\":0,\"longitude\":0},{\"label\":\"Hala Beta\",\"icon\":\"pin\",\"latitude\":0.001,\"longitude\":0},{\"label\":\"Hala Gamma\",\"icon\":\"pin\",\"latitude\":0.002,\"longitude\":0}]}]}"));
        MainActivity a=(MainActivity)i.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        i.waitForIdleSync();
        i.runOnMainSync(()->ProgressChecks.check(((LinearLayout)a.findViewById(R.id.action_tiles)).getChildCount()==4,"four action tiles"));
        shot(i,"home-complete");
        // Navigation render is real. Temporarily remove maintenance only while the monitor has no start callback.

        i.runOnMainSync(()->((LinearLayout)a.findViewById(R.id.navigation_tiles)).getChildAt(1).performClick());
        shot(i,"navigation-chooser");i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);

        new NavigationStore(c).save(NavigationConfig.empty());menu(i,a);shot(i,"navigation-empty-all");
        action(i,a,HomeAction.GATE,"Dzwonię do bramy","Połączenie przez telefon Bluetooth",false,false);shot(i,"gate-executing");
        action(i,a,HomeAction.GATE,"Gotowe","Połączenie zakończone",true,false);shot(i,"gate-success");
        i.runOnMainSync(()->invoke(a,"showError",new Class<?>[]{GateError.class},GateError.PHONE_NOT_CONNECTED));shot(i,"gate-error");
        menu(i,a);i.runOnMainSync(()->{a.findViewById(R.id.menu_content).setVisibility(View.GONE);invoke(a,"showCooldownAndFinish",new Class<?>[]{long.class},45000L);});shot(i,"gate-cooldown");
        for(HomeAction kind:new HomeAction[]{HomeAction.CLEANING,HomeAction.MOP}) {
            menu(i,a);i.runOnMainSync(()->{field(a,"currentAction",kind);invoke(a,"renderCleaningResult",new Class<?>[]{RoborockClient.Result.class},RoborockClient.Result.ACCEPTED);});
            shot(i,kind==HomeAction.MOP?"mop-accepted":"cleaning-accepted");
        }
        menu(i,a);i.runOnMainSync(()->invoke(a,"renderCleaningResult",new Class<?>[]{RoborockClient.Result.class},RoborockClient.Result.NETWORK_UNKNOWN));shot(i,"routine-uncertain");
        menu(i,a);i.runOnMainSync(()->invoke(a,"showRoborockSetup",new Class<?>[]{boolean.class},false));
        i.runOnMainSync(()->a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE));shot(i,"robot-setup-empty");
        menu(i,a);i.runOnMainSync(()->invoke(a,"showNumberSetup",new Class<?>[]{}));
        i.runOnMainSync(()->a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE));shot(i,"gate-setup-empty");
        menu(i,a);i.runOnMainSync(()->a.findViewById(R.id.settings_button).performClick());shot(i,"settings-menu");i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        i.runOnMainSync(()->invoke(a,"showYanosikAccess",new Class<?>[]{}));shot(i,"media-access");i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        menu(i,a);
        ProgressOverlay[] progress={null};
        i.runOnMainSync(()->progress[0]=new ProgressOverlay(c));
        i.runOnMainSync(()->progress[0].close());
        for(Kind kind:new Kind[]{Kind.DEPARTURE,Kind.RETURN,Kind.CLEANING,Kind.YANOSIK}) {
            i.runOnMainSync(()-> {
                ViewGroup host=((ViewGroup)a.findViewById(R.id.automation_progress_host));host.removeAllViews();View view=AutomationBanner.create(a);host.addView(view,new FrameLayout.LayoutParams(Math.round(480*a.getResources().getDisplayMetrics().density),ViewGroup.LayoutParams.WRAP_CONTENT,Gravity.CENTER_HORIZONTAL));
                AutomationBanner.render(view,new ProgressModel.State(1,1,kind,Phase.CANDIDATE,.6,Reason.NONE,Long.MAX_VALUE,Long.MAX_VALUE,1));
                ProgressChecks.check(((TextView)view.findViewById(R.id.banner_title)).length()>0,"banner rendered");
            });shot(i,"detection-"+kind.name().toLowerCase(Locale.ROOT));
        }
        Kind[] media={Kind.YANOSIK,Kind.YANOSIK,Kind.SPOTIFY,Kind.SPOTIFY};
        Phase[] phases={Phase.SUCCEEDED,Phase.SKIPPED,Phase.STARTED,Phase.SUCCEEDED};
        Reason[] reasons={Reason.NONE,Reason.TARGET_ALREADY_RUNNING,Reason.RESUMING,Reason.NONE};
        String[] names={"yanosik-requested","yanosik-already-running","spotify-resuming","spotify-playing"};
        for(int n=0;n<media.length;n++) {
            final int at=n;
            i.runOnMainSync(()->{
                ViewGroup host=((ViewGroup)a.findViewById(R.id.automation_progress_host));host.removeAllViews();View view=AutomationBanner.create(a);host.addView(view,new FrameLayout.LayoutParams(Math.round(480*a.getResources().getDisplayMetrics().density),ViewGroup.LayoutParams.WRAP_CONTENT,Gravity.CENTER_HORIZONTAL));
                AutomationBanner.render(view,new ProgressModel.State(1,1,media[at],phases[at],1,reasons[at],Long.MAX_VALUE,Long.MAX_VALUE,-1));
            });shot(i,names[n]);
        }
        i.runOnMainSync(()->((ViewGroup)a.findViewById(R.id.automation_progress_host)).removeAllViews());
        menu(i,a);i.runOnMainSync(a::finish);
        TripActivity trip=(TripActivity)i.startActivitySync(new Intent(c,TripActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        i.runOnMainSync(()->((Handler)ProgressChecks.field(trip,"handler")).removeCallbacksAndMessages(null));
        PlaceResult place=new PlaceResult("Miejscowość Testowa","Ulica Przykładowa","offline",true,true);
        for(String state:new String[]{"OFF","ACTIVE","PAUSED","FINISHED"}) {
            i.runOnMainSync(()->trip.render(snapshot(state,place,SystemClock.elapsedRealtime())));
            shot(i,"trip-"+state.toLowerCase(Locale.ROOT));
        }
        i.runOnMainSync(()->trip.render(snapshot("ACTIVE",PlaceResult.unknown(false),SystemClock.elapsedRealtime())));shot(i,"trip-no-map");
        i.runOnMainSync(()->trip.render(snapshot("ACTIVE",place,0)));shot(i,"trip-no-gps");
        i.runOnMainSync(()->trip.render(snapshot("ACTIVE",new PlaceResult("Bardzo Długa Nazwa Miejscowości Testowej","Przykładowa Ulica o Bardzo Długiej Nazwie","offline",true,true),SystemClock.elapsedRealtime())));shot(i,"trip-long-names");
        i.runOnMainSync(trip::finish);
        c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));SystemClock.sleep(700);
        // Let previous synthetic automation states expire before rendering the location overlay.
        i.runOnMainSync(()->ProgressBus.reset(c,Reason.RESET));SystemClock.sleep(2200);
        TripPresentation[] panel={null};
        i.runOnMainSync(()->{
            panel[0]=new TripPresentation(c);
            ((Handler)ProgressChecks.field(panel[0],"handler")).removeCallbacksAndMessages(null);
            panel[0].render(snapshot("ACTIVE",place,SystemClock.elapsedRealtime()));
            View overlay=(View)ProgressChecks.field(panel[0],"overlay");ProgressChecks.check(overlay!=null,"location overlay visible");
            WindowManager.LayoutParams params=(WindowManager.LayoutParams)overlay.getLayoutParams();
            ProgressChecks.check((params.flags&WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)!=0,"location cannot steal focus");
            ProgressChecks.check((params.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)==0,"location receives taps within rectangle");
            ProgressChecks.check(overlay.findViewById(R.id.trip_full_name).getVisibility()==View.GONE,"short location hides expansion button");
        });i.waitForIdleSync();SystemClock.sleep(1500);shot(i,"trip-overlay");
        i.runOnMainSync(()->panel[0].render(snapshot("ACTIVE",new PlaceResult("Miejscowość Testowa","Aleja Błękitnych Chmur Testowych","offline",true,true),SystemClock.elapsedRealtime())));
        i.waitForIdleSync();shot(i,"trip-overlay-long-name");
        i.runOnMainSync(()->panel[0].render(snapshot("ACTIVE",new PlaceResult("Miejscowość Testowa","Przykładowa Aleja Bardzo Długich Błękitnych Obłoków Testowych przy Bajkowych Wzgórzach i Srebrzystych Ogrodach","offline",true,true),SystemClock.elapsedRealtime())));
        i.waitForIdleSync();SystemClock.sleep(500);
        i.runOnMainSync(()->{
            View overlay=(View)ProgressChecks.field(panel[0],"overlay");
            View button=overlay.findViewById(R.id.trip_full_name);
            View city=overlay.findViewById(R.id.trip_locality);
            int minimum=Math.round(76*c.getResources().getDisplayMetrics().density);
            ProgressChecks.check(button.getVisibility()==View.VISIBLE && button.getHeight()>=minimum,"overflow action has full touch height in attached window");
            ProgressChecks.check(city.getTop()>=overlay.getPaddingTop(),"locality remains inside attached card");
            ProgressChecks.check(button.getBottom()+overlay.getPaddingBottom()<=overlay.getHeight(),"entire overflow button fits attached card");
            android.graphics.Rect visible=new android.graphics.Rect();
            ProgressChecks.check(button.getGlobalVisibleRect(visible)&&visible.height()==button.getHeight(),"overflow action entirely visible on screen");
        });shot(i,"trip-overlay-overflow");
        shell(i,"cmd statusbar expand-notifications");SystemClock.sleep(1500);shot(i,"trip-notification");
        shell(i,"cmd statusbar collapse");SystemClock.sleep(1500);
        i.runOnMainSync(()->{
            progress[0]=new ProgressOverlay(c);long epoch=ProgressBus.reset(c,Reason.RESET);
            ProgressBus.offer(c,epoch,ProgressBus.HOME,List.of(new DetectionProgress(Kind.RETURN,77,Phase.CANDIDATE,.7,SystemClock.elapsedRealtime()+15000,Reason.NONE,Stage.APPROACHING_GATE)));
            panel[0].render(snapshot("ACTIVE",place,SystemClock.elapsedRealtime()));
            ProgressChecks.check(ProgressChecks.field(panel[0],"overlay")==null,"automation replaces location overlay");
        });shot(i,"trip-automation-priority");
        i.runOnMainSync(()->{panel[0].close();progress[0].close();});

        org.json.JSONArray manifest=new org.json.JSONArray(shots);
        try(var stream=new FileOutputStream(new File(c.getExternalFilesDir(null),"gallery-files.json"))) {stream.write(manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        ProgressChecks.check(!c.getSharedPreferences("gate_settings",0).contains("last_dial_started_at"),"gallery never dials");
    }
}
