package com.pbuchman.duduhome.ui;

import android.app.*;
import android.content.*;
import android.graphics.PixelFormat;
import android.graphics.Insets;
import android.graphics.Rect;
import android.util.DisplayMetrics;
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
    private WindowManager.LayoutParams layout;
    private final Rect movementBounds = new Rect();
    private boolean dragging;
    private float touchX, touchY;
    private int touchWindowX, touchWindowY;
    private int touchSlop;
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
        touchSlop=ViewConfiguration.get(context).getScaledTouchSlop();
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
        if(!preferences.visible() || TripActivity.visible || HomeActions.visibleActivity()!=null || HomeActions.busy()
                || !HomeActions.allowsExternalLaunch() || ProgressBus.visible()!=null || !Settings.canDrawOverlays(context)) { remove(); return; }
        if(SystemClock.elapsedRealtime()<retryAfter)return;
        try {
            if(overlay==null) {
                TripLocationCard box=new TripLocationCard(context, v -> context.startActivity(open(context)),
                        v -> { if(preferences.setVisible(false))remove(); else saveError(); }, this::drag);
                layout=new WindowManager.LayoutParams(1,ViewGroup.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
                // Use one explicit coordinate space, then keep the entire bounded window inside
                // system bars/cutouts ourselves. No transparent full-screen touch surface.
                if(Build.VERSION.SDK_INT>=30)layout.setFitInsetsTypes(0);
                layout.gravity=Gravity.TOP|Gravity.LEFT;layout.setTitle("Dudu Home location");
                overlay=box;
                box.render(state.title(),state.subtitle());
                place(false);
                box.setOnApplyWindowInsetsListener((v,insets) -> { handler.post(() -> { if(overlay==v)placeSafely(); }); return insets; });
                windows.addView(box,layout);
            }
            ((TripLocationCard)overlay).render(state.title(),state.subtitle());
            place(true);
        } catch(RuntimeException unavailable) { remove(); retryAfter=SystemClock.elapsedRealtime()+5000; }
    }
    private Rect availableBounds() {
        if(Build.VERSION.SDK_INT>=30) {
            WindowMetrics metrics=windows.getCurrentWindowMetrics();
            Rect bounds=metrics.getBounds();
            Insets insets=metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            return new Rect(insets.left,insets.top,bounds.width()-insets.right,bounds.height()-insets.bottom);
        }
        DisplayMetrics metrics=new DisplayMetrics(); windows.getDefaultDisplay().getRealMetrics(metrics);
        WindowInsets insets=overlay==null ? null : overlay.getRootWindowInsets();
        return insets==null ? new Rect(0,0,metrics.widthPixels,metrics.heightPixels)
                : new Rect(insets.getStableInsetLeft(),insets.getStableInsetTop(),
                metrics.widthPixels-insets.getStableInsetRight(),metrics.heightPixels-insets.getStableInsetBottom());
    }
    private void place(boolean attached) {
        if(overlay==null)return;
        Rect available=availableBounds();
        int margin=Math.min(dp(16),Math.max(0,Math.min(available.width(),available.height())/4));
        int width=Math.min(dp(390),Math.max(1,available.width()-2*margin));
        TripLocationCard card=(TripLocationCard)overlay;
        card.setMaximumHeight(Math.max(1,available.height()-2*margin));
        card.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.max(1,available.height()-2*margin),View.MeasureSpec.AT_MOST));
        movementBounds.set(available.left+margin,available.top+margin,
                available.right-margin-width,Math.max(available.top+margin,available.bottom-margin-card.getMeasuredHeight()));
        int x=dragging ? clamp(layout.x,movementBounds.left,movementBounds.right)
                : movementBounds.left+Math.round(preferences.horizontal()*movementBounds.width());
        int y=dragging ? clamp(layout.y,movementBounds.top,movementBounds.bottom)
                : movementBounds.top+Math.round(preferences.vertical()*movementBounds.height());
        boolean changed=layout.width!=width || layout.x!=x || layout.y!=y;
        layout.width=width;layout.x=x;layout.y=y;
        if(attached && changed)windows.updateViewLayout(overlay,layout);
    }
    private void placeSafely() {
        try { place(true); }
        catch(RuntimeException unavailable) { remove();retryAfter=SystemClock.elapsedRealtime()+5000; }
    }
    private boolean drag(View handle, MotionEvent event) {
        if(overlay==null)return true;
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchX=event.getRawX();touchY=event.getRawY();
                touchWindowX=layout.x;touchWindowY=layout.y;dragging=false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx=event.getRawX()-touchX,dy=event.getRawY()-touchY;
                if(!dragging && Math.hypot(dx,dy)<=touchSlop)return true;
                dragging=true;
                layout.x=clamp(Math.round(touchWindowX+dx),movementBounds.left,movementBounds.right);
                layout.y=clamp(Math.round(touchWindowY+dy),movementBounds.top,movementBounds.bottom);
                try { windows.updateViewLayout(overlay,layout); } catch(RuntimeException unavailable) { remove(); }
                return true;
            case MotionEvent.ACTION_UP:
                if(dragging) {
                    // A dimension with no travel retains its previous choice for a later resize.
                    boolean saved=preferences.savePosition(movementBounds.width()==0 ? preferences.horizontal() : (layout.x-movementBounds.left)/(float)movementBounds.width(),
                            movementBounds.height()==0 ? preferences.vertical() : (layout.y-movementBounds.top)/(float)movementBounds.height());
                    dragging=false;
                    if(!saved) { saveError();placeSafely(); }
                } else handle.performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging=false;placeSafely();return true;
            default:return true;
        }
    }
    private void saveError() { Toast.makeText(context,R.string.trip_overlay_save_error,Toast.LENGTH_LONG).show(); }
    private static int clamp(int value,int minimum,int maximum) { return Math.max(minimum,Math.min(maximum,value)); }
    private int dp(int value) { return Math.round(value*context.getResources().getDisplayMetrics().density); }
    private void remove() { if(overlay!=null) { try { windows.removeViewImmediate(overlay); } catch(RuntimeException ignored) { } overlay=null;layout=null;dragging=false; } }
    public void close() { closed=true;handler.removeCallbacksAndMessages(null);remove();notifications.cancel(18); }
}
