package com.pbuchman.duduhome;

import android.app.*;
import android.content.*;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;
import android.content.res.Configuration;
import com.pbuchman.duduhome.ui.*;
import com.pbuchman.duduhome.trip.*;
import com.pbuchman.duduhome.automation.*;

final class TripUiChecks {
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    static void state(TripController c,String target) {
        for(int n=0;n<50;n++){if(c.snapshot().state().equals(target))return;SystemClock.sleep(50);}throw new AssertionError(target);
    }
    static void run(Instrumentation i)throws Exception {
        Context c=i.getTargetContext();
        TripOverlayPreferences overlayPreferences=new TripOverlayPreferences(c);
        check(overlayPreferences.setVisible(true)&&overlayPreferences.savePosition(1f,0f),"initial UI preferences durable");
        checkPreferenceFailure(c);
        checkLocationCard(i, c);
        TripController controller=TripController.get(c);
        controller.command("START");state(controller,"ACTIVE");
        android.location.Location stationary=new android.location.Location("gps");
        stationary.setLatitude(0);stationary.setLongitude(0);stationary.setAccuracy(200);
        stationary.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());controller.offer(stationary);
        SystemClock.sleep(1100);stationary.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());controller.offer(stationary);SystemClock.sleep(200);
        check(controller.snapshot().millis()>900,"stationary GPS duration available for reset UI");
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
        i.runOnMainSync(()->{
            check(HomeActions.allowsMediaLaunch(),"passive trip screen allows media automation");
            TripController.Snapshot before=controller.snapshot();
            trip.findViewById(R.id.trip_overlay_toggle).performClick();
            check(!overlayPreferences.visible(),"passive screen can hide panel");
            trip.findViewById(R.id.trip_overlay_toggle).performClick();
            check(overlayPreferences.visible(),"passive screen restores panel");
            TripController.Snapshot after=controller.snapshot();
            check(after.state().equals(before.state())&&after.generation()==before.generation()&&after.meters()==before.meters(),"panel toggle leaves trip unchanged");
            check(((TextView)trip.findViewById(R.id.trip_street)).getMaxLines()==Integer.MAX_VALUE,"full screen has unlimited road lines");
        });
        var notices=c.getSystemService(NotificationManager.class).getActiveNotifications();
        PendingIntent open=null;
        for(var notice:notices)if(notice.getId()==18) {
            open=notice.getNotification().contentIntent;
            CharSequence full=notice.getNotification().extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
            check(full!=null && full.toString().contains("\n"),"expanded notification keeps both complete address fields");
        }
        check(open!=null,"ongoing trip notification has content intent");
        i.runOnMainSync(()->trip.findViewById(R.id.trip_primary).performClick());state(controller,"PAUSED");
        SystemClock.sleep(600);ProgressChecks.capture(i,"trip-session-time.png");
        i.runOnMainSync(trip::finish);SystemClock.sleep(300);
        monitor=i.addMonitor(TripActivity.class.getName(),null,false);
        i.runOnMainSync(()->check(HomeActions.begin(),"synthetic protected action"));
        open.send();SystemClock.sleep(700);
        check(!TripActivity.visible,"notification cannot cover protected action");
        i.runOnMainSync(HomeActions::end);
        TripActivity deferred=(TripActivity)i.waitForMonitorWithTimeout(monitor,5000);check(deferred!=null,"notification opens after action cleanup");
        i.runOnMainSync(()->{
            check(((TextView)deferred.findViewById(R.id.trip_primary)).getText().toString().equals("Start"),"paused primary is Start");
            check(((TextView)deferred.findViewById(R.id.trip_reset)).getText().toString().equals("Reset"),"reset replaces end");
            check(((TextView)deferred.findViewById(R.id.trip_time)).getText().toString().matches("[0-9]{2,}:[0-9]{2}:[0-9]{2}"),"session duration rendered");
        });
        long retainedTime=controller.snapshot().millis();double retainedMeters=controller.snapshot().meters();long retainedGeneration=controller.snapshot().generation();
        i.runOnMainSync(()->{
            deferred.findViewById(R.id.trip_reset).performClick();
            AlertDialog dialog=(AlertDialog)ProgressChecks.field(deferred,"resetDialog");
            check(dialog!=null && dialog.isShowing(),"nonzero reset requires confirmation");
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        });SystemClock.sleep(200);
        check(controller.snapshot().millis()==retainedTime && controller.snapshot().meters()==retainedMeters
                && controller.snapshot().generation()==retainedGeneration,"cancel reset is inert");
        i.runOnMainSync(()->{
            deferred.findViewById(R.id.trip_reset).performClick();
            ((AlertDialog)ProgressChecks.field(deferred,"resetDialog")).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        });SystemClock.sleep(200);
        check(controller.snapshot().millis()==0 && controller.snapshot().meters()==0 && !controller.snapshot().active(),"confirmed paused reset zeros both");
        i.runOnMainSync(()->{
            deferred.render(new TripController.Snapshot("PAUSED",1,0,0,PlaceResult.unknown(false),"",true,100L*3600000+61000,true));
            check(((TextView)deferred.findViewById(R.id.trip_time)).getText().toString().equals("100:01:01"),"long session format on main screen");
            check(((TextView)deferred.findViewById(R.id.trip_time_label)).getText().toString().equals("Czas od aktualizacji"),"legacy partial time label");
            deferred.finish();presentation[0].close();
        });i.removeMonitor(monitor);
    }
    /** Simulates SharedPreferences' changed memory cache plus a failed disk acknowledgement. */
    static void checkPreferenceFailure(Context context) {
        android.content.SharedPreferences backing=context.getSharedPreferences("trip_overlay_failure_fixture",0);
        check(backing.edit().clear().commit(),"clear preference-failure fixture");
        android.content.SharedPreferences failing=(android.content.SharedPreferences)java.lang.reflect.Proxy.newProxyInstance(
                android.content.SharedPreferences.class.getClassLoader(),new Class<?>[]{android.content.SharedPreferences.class},(proxy,method,args)->{
            if(!method.getName().equals("edit"))return method.invoke(backing,args);
            android.content.SharedPreferences.Editor editor=backing.edit();
            return java.lang.reflect.Proxy.newProxyInstance(android.content.SharedPreferences.Editor.class.getClassLoader(),
                    new Class<?>[]{android.content.SharedPreferences.Editor.class},(editorProxy,write,values)->{
                if(write.getName().equals("apply"))throw new AssertionError("completed UI choice must not use asynchronous apply");
                Object result=write.invoke(editor,values);
                if(write.getName().equals("commit"))return false;
                return result instanceof android.content.SharedPreferences.Editor ? editorProxy : result;
            });
        });
        Context wrapper=new android.content.ContextWrapper(context) {
            @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode) { return failing; }
        };
        TripOverlayPreferences preferences=new TripOverlayPreferences(wrapper);
        check(!preferences.setVisible(false),"failed visibility write reports failure");
        check(preferences.visible(),"failed visibility write restores prior memory choice");
        check(!preferences.savePosition(.25f,.75f),"failed position write reports failure");
        check(preferences.horizontal()==1f&&preferences.vertical()==0f,"failed position write restores prior memory position");
    }
    /** Uses invented names and different font scales without touching the GPS resolver. */
    private static void checkLocationCard(Instrumentation i, Context context) {
        for (float scale : new float[]{1f, 1.4f}) {
            Configuration configuration = new Configuration(context.getResources().getConfiguration());
            configuration.fontScale = scale;
            Context themed = new android.view.ContextThemeWrapper(context.createConfigurationContext(configuration), R.style.Theme_DuduGate);
            TripLocationCard[] card = {null};
            i.runOnMainSync(() -> {
                card[0] = new TripLocationCard(themed, v -> { });
                card[0].render("Miejscowość Testowa", "Aleja Błękitnych Chmur Testowych");
                layout(card[0], themed, 286);
            });
            i.waitForIdleSync();
            i.runOnMainSync(() -> {
                TextView road = card[0].findViewById(R.id.trip_street);
                check(card[0].findViewById(R.id.trip_overlay_drag)==null,"compact card has no separate handle/instruction row");
                check(card[0].getHeight()>=Math.round(112*themed.getResources().getDisplayMetrics().density),"compact location min112dp");
                check(card[0].findViewById(R.id.trip_overlay_close).getWidth()==Math.round(48*themed.getResources().getDisplayMetrics().density),"adjacent close target48dp");
                check(Math.abs(road.getTextSize()-android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,25,themed.getResources().getDisplayMetrics()))<.01,"street25sp");
                check(Math.abs(((TextView)card[0].findViewById(R.id.trip_locality)).getTextSize()-android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,16,themed.getResources().getDisplayMetrics()))<.01,"locality16sp");
                check(road.getMaxLines() == 3, "road bounded to three readable lines");
                check(road.getLayout().getLineCount() >= 2, "long road naturally wraps");
                check(card[0].getContentDescription().toString().contains(road.getText().toString()), "accessibility retains full name");
                if (scale == 1f) {
                    check(card[0].findViewById(R.id.trip_full_name).getVisibility() == View.GONE, "normal long name needs no expansion");
                    for (int n = 0; n < road.getLayout().getLineCount(); n++) check(road.getLayout().getEllipsisCount(n) == 0, "normal long name fully visible");
                }
                card[0].render("Miejscowość Testowa", "Przykładowa Aleja Bardzo Długich Błękitnych Obłoków Testowych przy Bajkowych Wzgórzach i Srebrzystych Ogrodach");
                layout(card[0], themed, 260);
            });
            i.waitForIdleSync();
            i.runOnMainSync(() -> {
                check(card[0].findViewById(R.id.trip_full_name).getVisibility() == View.VISIBLE, "overflow has visible full-name action");
                card[0].setMaximumHeight(Math.round(180 * themed.getResources().getDisplayMetrics().density));
                layout(card[0], themed, 260);
                for(int id:new int[]{R.id.trip_overlay_close}) {
                    View control=card[0].findViewById(id);
                    View row=(View)control.getParent();
                    check(control.getTop()>=0 && control.getBottom()<=row.getHeight(),"whole control remains inside short-viewport header");
                    if(control instanceof android.widget.Button button)check(button.getWidth()-button.getCompoundPaddingLeft()-button.getCompoundPaddingRight()>=button.getPaint().measureText(button.getText().toString()),"hide glyph fits");
                }
                android.widget.ScrollView address=(android.widget.ScrollView)card[0].findViewById(R.id.trip_street).getParent().getParent();
                check(address.canScrollVertically(1),"short viewport scrolls full address and expansion control");
                card[0].setMaximumHeight(Integer.MAX_VALUE);
                card[0].render("Miejscowość Testowa", "Testowa"); layout(card[0], themed, 286);
            });
            i.waitForIdleSync();
            i.runOnMainSync(() -> {
                check(card[0].findViewById(R.id.trip_full_name).getVisibility()==View.GONE,"short name removes expansion");
                card[0].render("Miejscowość Testowa","");layout(card[0],themed,286);
                check(card[0].findViewById(R.id.trip_street).getVisibility()==View.GONE,"unnamed road hides street row");
                check(card[0].findViewById(R.id.trip_full_name).getVisibility()==View.GONE,"unnamed hides expansion");
            });
        }
    }
    private static void layout(View view, Context c, int widthDp) {
        int width = Math.round(widthDp * c.getResources().getDisplayMetrics().density);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, width, view.getMeasuredHeight());
    }

}
