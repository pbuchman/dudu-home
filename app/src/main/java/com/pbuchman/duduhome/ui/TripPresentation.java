package com.pbuchman.duduhome.ui;

import android.app.*;
import android.content.*;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import com.pbuchman.duduhome.R;
import com.pbuchman.duduhome.automation.*;
import com.pbuchman.duduhome.trip.TripController;

/** Only presentation belongs to the service; closing it cannot end or reset a trip. */
public final class TripPresentation implements AutoCloseable {
    public static final String OPEN = "com.pbuchman.duduhome.OPEN_TRIP";
    private static final String CHANNEL="where_am_i";
    private final Context context;
    private final WindowManager windows;
    private final NotificationManager notifications;
    private final TripController controller;
    private final TripOverlayPreferences preferences;
    private final FloatingPanelPosition position;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private View overlay;
    private String notified="";
    private boolean closed;
    private long retryAfter;
    private final Runnable tick=new Runnable() { public void run() {
        if(closed)return;
        try { render(controller.snapshot()); } catch(RuntimeException unavailable) { remove(); }
        handler.postDelayed(this,500);
    }};
    public TripPresentation(Context service) {
        Context window=Build.VERSION.SDK_INT>=30 ? service.createDisplayContext(service.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY))
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null) : service;
        context=new ContextThemeWrapper(window,R.style.Theme_DuduGate);
        windows=window.getSystemService(WindowManager.class); notifications=service.getSystemService(NotificationManager.class);
        controller=TripController.get(service);
        preferences=new TripOverlayPreferences(service);
        position=new FloatingPanelPosition(context,windows,preferences,286,this::remove);
        NotificationChannel channel=new NotificationChannel(CHANNEL,"Gdzie jestem",NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null,null); channel.enableVibration(false); notifications.createNotificationChannel(channel);
        handler.post(tick);
    }
    public static Intent open(Context context) {
        return new Intent(context,MainActivity.class).setAction(OPEN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
    public void render(TripController.Snapshot state) {
        if(!state.active()) { remove(); hideNotification(); return; }
        String title=state.title(),subtitle=state.subtitle(),text=title+"\n"+subtitle;
        if(!text.equals(notified)) {
            PendingIntent intent=PendingIntent.getActivity(context,18,open(context),PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            notifications.notify(18,new Notification.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle(title).setContentText(subtitle).setContentIntent(intent)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setOnlyAlertOnce(true).setOngoing(true).setShowWhen(false).setVisibility(Notification.VISIBILITY_PRIVATE).build());
            notified=text;
            boolean allowed=notifications.areNotificationsEnabled()&&notifications.getNotificationChannel(CHANNEL).getImportance()!=NotificationManager.IMPORTANCE_NONE;
            controller.presented("trip_notification",allowed,state,title,subtitle);
        }
        if(!preferences.visible() || TripActivity.visible || HomeActions.visibleActivity()!=null || HomeActions.busy()
                || !HomeActions.allowsExternalLaunch() || ProgressBus.visible()!=null || !Settings.canDrawOverlays(context)) { remove(); return; }
        if(SystemClock.elapsedRealtime()<retryAfter)return;
        try {
            if(overlay==null) {
                TripLocationCard box=new TripLocationCard(context, v -> context.startActivity(open(context)),
                        v -> { if(preferences.setVisible(false))remove(); else saveError(); }, null);
                overlay=box;box.render(title,subtitle);
                position.attach(box,"Dudu Home location",1f);
            }
            ((TripLocationCard)overlay).render(title,subtitle);
            position.refresh();
            controller.presented("trip_overlay",overlay.isAttachedToWindow()&&overlay.isShown(),state,title,subtitle);
        } catch(RuntimeException unavailable) { remove(); retryAfter=SystemClock.elapsedRealtime()+5000; }
    }
    private void saveError() { Toast.makeText(context,R.string.trip_overlay_save_error,Toast.LENGTH_LONG).show(); }
    private void remove() {
        position.remove();overlay=null;
        controller.presented("trip_overlay",false,controller.snapshot(),"","");
    }
    private void hideNotification() {
        notifications.cancel(18);notified="";
        controller.presented("trip_notification",false,controller.snapshot(),"","");
    }
    public void close() { closed=true;handler.removeCallbacksAndMessages(null);remove();hideNotification(); }
}
