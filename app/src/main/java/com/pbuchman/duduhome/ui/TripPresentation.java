package com.pbuchman.duduhome.ui;

import android.app.*;
import android.content.*;
import android.graphics.PixelFormat;
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
        NotificationChannel channel=new NotificationChannel(CHANNEL,"Gdzie jestem",NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null,null); channel.enableVibration(false); notifications.createNotificationChannel(channel);
        handler.post(tick);
    }
    public static Intent open(Context context) {
        return new Intent(context,MainActivity.class).setAction(OPEN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
    public void render(TripController.Snapshot state) {
        if(!state.active()) { remove(); notifications.cancel(18); notified=""; return; }
        String text=state.title()+"\n"+state.subtitle();
        if(!text.equals(notified)) {
            PendingIntent intent=PendingIntent.getActivity(context,18,open(context),PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            notifications.notify(18,new Notification.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle(state.title()).setContentText(state.subtitle()).setContentIntent(intent)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setOnlyAlertOnce(true).setOngoing(true).setShowWhen(false).setVisibility(Notification.VISIBILITY_PRIVATE).build());
            notified=text;
        }
        if(TripActivity.visible || HomeActions.visibleActivity()!=null || HomeActions.busy()
                || !HomeActions.allowsExternalLaunch() || ProgressBus.visible()!=null || !Settings.canDrawOverlays(context)) { remove(); return; }
        if(SystemClock.elapsedRealtime()<retryAfter)return;
        try {
            if(overlay==null) {
                TripLocationCard box=new TripLocationCard(context, v -> context.startActivity(open(context)));
                int available=Build.VERSION.SDK_INT>=30 ? windows.getCurrentWindowMetrics().getBounds().width()
                        : context.getResources().getDisplayMetrics().widthPixels;
                int width=Math.min(dp(390),Math.max(1,available-dp(32)));
                WindowManager.LayoutParams p=new WindowManager.LayoutParams(width,ViewGroup.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);
                p.gravity=Gravity.TOP|Gravity.END;p.x=dp(16);p.y=dp(16);p.setTitle("Dudu Home location");
                overlay=box; windows.addView(box,p);
            }
            ((TripLocationCard)overlay).render(state.title(),state.subtitle());
        } catch(RuntimeException unavailable) { remove(); retryAfter=SystemClock.elapsedRealtime()+5000; }
    }
    private int dp(int value) { return Math.round(value*context.getResources().getDisplayMetrics().density); }
    private void remove() { if(overlay!=null) { try { windows.removeViewImmediate(overlay); } catch(RuntimeException ignored) { } overlay=null; } }
    public void close() { closed=true;handler.removeCallbacksAndMessages(null);remove();notifications.cancel(18); }
}
