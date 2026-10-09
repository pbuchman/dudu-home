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
        checkLocationCard(i, c);
        TripController controller=TripController.get(c);
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
        i.runOnMainSync(()->{
            check(HomeActions.allowsMediaLaunch(),"passive trip screen allows media automation");
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
                layout(card[0], themed, 390);
            });
            i.waitForIdleSync();
            i.runOnMainSync(() -> {
                TextView road = card[0].findViewById(R.id.trip_street);
                check(road.getMaxLines() == 3, "road bounded to three readable lines");
                check(road.getLayout().getLineCount() >= 2, "long road naturally wraps");
                check(card[0].getContentDescription().toString().endsWith(road.getText().toString()), "accessibility retains full name");
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
                card[0].render("Miejscowość Testowa", "Testowa"); layout(card[0], themed, 390);
            });
            i.waitForIdleSync();
            i.runOnMainSync(() -> check(card[0].findViewById(R.id.trip_full_name).getVisibility() == View.GONE, "short name removes expansion"));
        }
    }
    private static void layout(View view, Context c, int widthDp) {
        int width = Math.round(widthDp * c.getResources().getDisplayMetrics().density);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, width, view.getMeasuredHeight());
    }

}
