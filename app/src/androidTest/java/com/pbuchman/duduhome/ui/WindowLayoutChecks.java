package com.pbuchman.duduhome.ui;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.pbuchman.duduhome.R;
import com.pbuchman.duduhome.automation.*;
import com.pbuchman.duduhome.gate.GateNumberStore;
import com.pbuchman.duduhome.location.HomeMonitorService;
import com.pbuchman.duduhome.navigation.*;
import java.io.*;
import org.json.*;
import static com.pbuchman.duduhome.automation.DetectionProgress.*;

/** Actual platform Views/windows with invented text. The guarded runner never requests an action. */
public final class WindowLayoutChecks {
    private final Instrumentation test;
    private final Context target;
    private final JSONArray files = new JSONArray(), dimensions = new JSONArray();
    private WindowLayoutChecks(Instrumentation test) { this.test=test; target=test.getTargetContext(); }
    public static void run(Instrumentation test) throws Exception { new WindowLayoutChecks(test).run(); }
    private void main(Runnable work) {
        java.util.concurrent.atomic.AtomicReference<Throwable> error=new java.util.concurrent.atomic.AtomicReference<>();
        test.runOnMainSync(()->{try{work.run();}catch(Throwable failed){error.set(failed);}});
        if(error.get()!=null)throw new AssertionError(error.get());
        test.waitForIdleSync();
    }
    private void shot(String name) throws Exception {
        test.waitForIdleSync(); SystemClock.sleep(200);
        Bitmap bitmap=test.getUiAutomation().takeScreenshot(); check(bitmap!=null,"screenshot exists");
        try(var output=new FileOutputStream(new File(target.getExternalFilesDir(null),name+".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG,100,output);
        }
        bitmap.recycle(); files.put(name+".png");
    }
    private void run() throws Exception {
        check(target.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)!=android.content.pm.PackageManager.PERMISSION_GRANTED,"GPS permission must stay denied");
        check(!HomeMonitorService.ready(target),"monitor cannot start");
        check(!target.getSharedPreferences("gate_settings",0).contains("gate_number"),"fresh synthetic emulator data required");
        MainActivity screen=(MainActivity)test.startActivitySync(new Intent(target,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        // Only this empty synthetic form is capturable; production configuration stays protected.
        main(()->screen.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE));
        shot("window-first-run");
        main(()->{
            check(new GateNumberStore(target).save("000000000"),"save invented non-action fixture");
            field(screen,"configurationSaved",true); invoke(screen,"showMenu");
        }); shot("window-menu");
        main(()->invoke(screen,"showNumberSetup"));
        main(()->screen.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE)); shot("window-number-setup");
        main(()->invoke(screen,"showMenu"));
        main(()->invoke(screen,"showRoborockSetup",new Class<?>[]{boolean.class},false));
        main(()->screen.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE)); shot("window-robot-setup");
        main(()->invoke(screen,"showMenu"));
        // Renderer-only action/error states: no manualAction, executor, Binder or transport is called.
        main(()->{
            screen.findViewById(R.id.menu_content).setVisibility(View.GONE);
            screen.findViewById(R.id.call_status_content).setVisibility(View.VISIBLE);
            ((TextView)screen.findViewById(R.id.status_title)).setText("Nie udało się wykonać czynności");
            ((TextView)screen.findViewById(R.id.status_description)).setText("Przykładowy długi komunikat: sprawdź połączenie telefonu i konfigurację. Nie wysłano żadnego polecenia.");
            screen.findViewById(R.id.progress).setVisibility(View.GONE);
            screen.findViewById(R.id.error_actions).setVisibility(View.VISIBLE);
        }); shot("window-error");
        ScrollView actionScroll=(ScrollView)screen.findViewById(R.id.call_status_content).getParent().getParent();
        main(()->{
            boolean compactNormal=target.getResources().getConfiguration().screenHeightDp<600 && getFontScale()<=1.01f;
            if(compactNormal) {
                touch(screen.findViewById(R.id.retry_button),screen.getWindow().getDecorView());
                touch(screen.findViewById(R.id.close_button),screen.getWindow().getDecorView());
            }
            actionScroll.fullScroll(View.FOCUS_DOWN);
        });
        shot("window-error-bottom");
        main(()->{
            touch(screen.findViewById(R.id.retry_button),screen.getWindow().getDecorView());
            touch(screen.findViewById(R.id.close_button),screen.getWindow().getDecorView());
            actionScroll.fullScroll(View.FOCUS_UP);
        });
        main(()->invoke(screen,"showMenu"));
        AlertDialog[] alert={null};
        main(()->alert[0]=ContentDialogs.show(screen,new AlertDialog.Builder(screen).setTitle(R.string.settings)
                .setItems(new String[]{"Numer bramy","Dane dostępowe Roborock","Lokalizacja",target.getString(R.string.yanosik_notification_access),target.getString(R.string.navigation_import),"Dane map OSM"},null)));
        shot("window-settings"); main(()->{bounded(alert[0].getWindow().getDecorView(),ContentDialogs.available(screen));alert[0].dismiss();});
        main(()->alert[0]=ContentDialogs.show(screen,new AlertDialog.Builder(screen).setTitle(R.string.yanosik_notification_access)
                .setMessage(target.getString(R.string.yanosik_access_missing)+"\n\n"+target.getString(R.string.yanosik_access_explanation))
                .setNegativeButton(R.string.close,null).setPositiveButton(R.string.yanosik_access_settings,null)));
        shot("window-media-access"); main(()->{touch(alert[0].getButton(AlertDialog.BUTTON_NEGATIVE),alert[0].getWindow().getDecorView());alert[0].dismiss();});
        main(()->alert[0]=ContentDialogs.show(screen,new AlertDialog.Builder(screen).setMessage("Wyzerować czas i dystans?")
                .setNegativeButton("Anuluj",null).setPositiveButton("Reset",null)));
        shot("window-trip-reset"); main(()->{touch(alert[0].getButton(AlertDialog.BUTTON_POSITIVE),alert[0].getWindow().getDecorView());alert[0].dismiss();});
        JSONObject fixture=new JSONObject(NavigationGroupChecks.fixture(12));
        JSONObject group=fixture.getJSONArray("slots").getJSONObject(0);
        group.put("label","Bardzo długa przykładowa grupa celów podróży");
        for(int n=0;n<12;n++)group.getJSONArray("destinations").getJSONObject(n)
                .put("label","Cel pokazowy "+(n+1)+": Hala Błękitnych Obłoków")
                .put("address","Przykładowa Aleja Srebrzystych Ogrodów 123, Miejscowość Testowa");
        new NavigationStore(target).save(NavigationConfig.parse(fixture.toString()));
        main(()->invoke(screen,"showMenu"));
        NavigationPanel navigation=(NavigationPanel)get(screen,"navigation");
        main(()->screen.findViewById(R.id.navigation_slot_2).performClick());
        Dialog chooser=(Dialog)get(navigation,"selector");
        check(chooser!=null,"real destination chooser visible"); shot("window-navigation-long");
        main(()->{
            View root=chooser.getWindow().getDecorView(); bounded(root,ContentDialogs.available(screen));
            View close=findDescription(root,target.getString(R.string.navigation_close));touch(close,root);
            ScrollView scroll=findScroll(root);check(scroll!=null&&scroll.canScrollVertically(1),"12 long destinations remain scrollable");
            scroll.fullScroll(View.FOCUS_DOWN);
        }); shot("window-navigation-last"); main(navigation::destroy);
        FrameLayout host=screen.findViewById(R.id.automation_progress_host);
        View[] banner={null};
        main(()->{
            banner[0]=AutomationBanner.create(screen);host.removeAllViews();
            host.addView(banner[0],new FrameLayout.LayoutParams(Math.min(dp(screen,720),host.getWidth()),-2,Gravity.CENTER_HORIZONTAL));
            AutomationBanner.render(banner[0],state(Kind.YANOSIK,Phase.CANDIDATE,Reason.NONE));
        }); shot("window-inline-detection"); main(()->inspectBanner(banner[0],false,"inline-detection"));
        main(()->{host.removeAllViews();screen.finish();});
        SystemClock.sleep(200);
        Context display=target.createDisplayContext(target.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY));
        Context window=Build.VERSION.SDK_INT>=30?display.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null):display;
        Context themed=new ContextThemeWrapper(window,R.style.Theme_DuduGate);
        WindowManager windows=window.getSystemService(WindowManager.class);
        FloatingPanelPosition[] position={null};
        String[] names={"detection","yanosik-start","spotify-start","spotify-wait","media-error","media-success","cancelling"};
        Kind[] kinds={Kind.YANOSIK,Kind.YANOSIK,Kind.SPOTIFY,Kind.SPOTIFY,Kind.SPOTIFY,Kind.SPOTIFY,Kind.SPOTIFY};
        Phase[] phases={Phase.CANDIDATE,Phase.STARTED,Phase.STARTED,Phase.WAITING,Phase.SKIPPED,Phase.SUCCEEDED,Phase.CANCELLING};
        Reason[] reasons={Reason.NONE,Reason.NONE,Reason.NONE,Reason.PRIORITY,Reason.MEDIA_NO_ACCESS,Reason.NONE,Reason.NONE};
        try {
            main(()->{
                banner[0]=AutomationBanner.createFloating(themed);
                position[0]=new FloatingPanelPosition(themed,windows,new TripOverlayPreferences(target,"window_fixture_progress",.5f),560,()->{throw new AssertionError("overlay attach failed");});
                AutomationBanner.render(banner[0],state(kinds[0],phases[0],reasons[0]));
                position[0].attach((PanelDragLayout)banner[0],"Synthetic progress preview",.8f);
            });
            for(int n=0;n<names.length;n++) {
                final int at=n;
                main(()->{AutomationBanner.render(banner[0],state(kinds[at],phases[at],reasons[at]));position[0].refresh();});
                shot("window-floating-"+names[n]);
                main(()->{inspectBanner(banner[0],true,names[at]);record(names[at],banner[0]);});
            }
            // Deliberately exhaust height: full message scrolls while cancel remains reachable.
            main(()->{
                AutomationBanner.render(banner[0],state(Kind.SPOTIFY,Phase.STARTED,Reason.NONE));
                ((TextView)banner[0].findViewById(R.id.banner_detail)).setText(("Bardzo długi przykładowy komunikat po polsku. ").repeat(35));
                position[0].refresh();
            }); shot("window-floating-long-scroll");
            main(()->{inspectBanner(banner[0],true,"long-scroll");check(((ScrollView)banner[0].findViewById(R.id.banner_body)).canScrollVertically(1),"long text scrolls instead of hiding cancellation");});
        } finally {main(()->{if(position[0]!=null)position[0].remove();});}
        int[] opened={0},hidden={0};
        main(()->{
            TripLocationCard card=new TripLocationCard(themed,v->opened[0]++,v->hidden[0]++,null);banner[0]=card;
            card.render("Miejscowość Testowa","Przykładowa Aleja Bardzo Długich Błękitnych Obłoków Testowych przy Srebrzystych Ogrodach");
            position[0]=new FloatingPanelPosition(themed,windows,new TripOverlayPreferences(target,"window_fixture_street",1),286,()->{throw new AssertionError("street attach failed");});
            position[0].attach(card,"Synthetic street preview",1f);
        }); shot("window-street-preserved");
        main(()->{
            touch(banner[0].findViewById(R.id.trip_overlay_close),banner[0]);
            banner[0].findViewById(R.id.trip_overlay_close).performClick();
            check(hidden[0]==1&&opened[0]==0,"X only hides; it never opens/cancels an action");
            position[0].remove();
            check(!HomeActions.busy(),"preview never owns an action executor");
            check(!target.getSharedPreferences("gate_settings",0).contains("last_dial_started_at"),"preview never reserves/sends a call");
        });
        JSONObject result=new JSONObject().put("files",files).put("measurements",dimensions).put("synthetic",true)
                .put("font_scale",target.getResources().getConfiguration().fontScale)
                .put("width_px",target.getResources().getDisplayMetrics().widthPixels)
                .put("height_px",target.getResources().getDisplayMetrics().heightPixels)
                .put("density",target.getResources().getDisplayMetrics().densityDpi);
        try(var output=new FileOutputStream(new File(target.getExternalFilesDir(null),"window-layout-files.json"))) {
            output.write(result.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    private static ProgressModel.State state(Kind kind,Phase phase,Reason reason) {
        return new ProgressModel.State(7001,1,kind,phase,.65,reason,Long.MAX_VALUE,Long.MAX_VALUE,7001);
    }
    private void inspectBanner(View root,boolean floating,String scenario) {
        check(root.getWidth()>0&&root.getHeight()>0,"banner measured");
        Button cancel=root.findViewById(R.id.banner_cancel_attempt);
        if(cancel.getVisibility()==View.VISIBLE)touch(cancel,root);
        for(int id:new int[]{R.id.banner_title,R.id.banner_detail}) {
            TextView text=root.findViewById(id); android.text.Layout layout=text.getLayout();
            check(layout!=null&&layout.getLineCount()>0,"message has measured lines");
            check(layout.getLineEnd(layout.getLineCount()-1)==text.length(),"full text retained in scroll content");
            for(int line=0;line<layout.getLineCount();line++) {
                check(layout.getEllipsisCount(line)==0,"message is never ellipsized");
                check(layout.getLineMax(line)<=layout.getWidth()+1,
                        scenario+" "+text.getResources().getResourceEntryName(id)+" line="+line
                        +" visibleWidth="+layout.getLineMax(line)+" withWhitespace="+layout.getLineWidth(line)
                        +" column="+layout.getWidth()+" font="+getFontScale()+" text="+text.getText());
            }
        }
        if(floating) {
            WindowManager.LayoutParams p=(WindowManager.LayoutParams)root.getLayoutParams();
            check(p.height==root.getHeight(),"native window matches measured content height");
            check((p.flags&WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)!=0,"overlay does not steal focus");
            check((p.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)!=0,"outside touches pass through");
            check(root.getWidth()<=dp(root.getContext(),560),"progress width stays bounded");
        }
    }
    private void record(String name,View root) {
        try { dimensions.put(new JSONObject().put("scenario",name).put("width",root.getWidth()).put("height",root.getHeight())
                .put("title_lines",((TextView)root.findViewById(R.id.banner_title)).getLineCount())
                .put("detail_lines",((TextView)root.findViewById(R.id.banner_detail)).getLineCount())
                .put("stacked",((LinearLayout)root).getOrientation()==LinearLayout.VERTICAL)); }
        catch(JSONException error){throw new AssertionError(error);}
    }
    private static void bounded(View root,Rect available) {
        check(root.getWidth()<=available.width()&&root.getHeight()<=available.height(),"dialog stays within available window");
    }
    private static void touch(View button,View root) {
        check(button!=null,"control exists");int minimum=dp(root.getContext(),48);
        check(button.getWidth()>=minimum&&button.getHeight()>=minimum,"control has at least48dp touch target");
        Rect visible=new Rect();check(button.getGlobalVisibleRect(visible)&&visible.width()==button.getWidth()&&visible.height()==button.getHeight(),"entire control visible");
    }
    private static View findDescription(View view,String description) {
        if(description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;
        if(view instanceof ViewGroup group)for(int n=0;n<group.getChildCount();n++){View found=findDescription(group.getChildAt(n),description);if(found!=null)return found;}return null;
    }
    private static ScrollView findScroll(View view) {
        if(view instanceof ScrollView scroll && scroll.getChildAt(0) instanceof LinearLayout list && list.getChildCount()==12)return scroll;
        if(view instanceof ViewGroup group)for(int n=0;n<group.getChildCount();n++){ScrollView found=findScroll(group.getChildAt(n));if(found!=null)return found;}return null;
    }
    private float getFontScale(){return target.getResources().getConfiguration().fontScale;}
    private static int dp(Context context,int value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
    private static Object get(Object object,String name){try{var field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}catch(Exception error){throw new AssertionError(error);}}
    private static void field(Object object,String name,Object value){try{var field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);}catch(Exception error){throw new AssertionError(error);}}
    private static void invoke(Object object,String method){invoke(object,method,new Class<?>[]{});}
    private static void invoke(Object object,String method,Class<?>[] types,Object...args){try{var call=object.getClass().getDeclaredMethod(method,types);call.setAccessible(true);call.invoke(object,args);}catch(Exception error){throw new AssertionError(error);}}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
