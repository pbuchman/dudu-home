package com.pbuchman.duduhome.ui;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.*;
import android.widget.Toast;
import com.pbuchman.duduhome.R;

/** Window-only positioning. It cannot access sessions, collection, attempts or action dispatch. */
final class FloatingPanelPosition {
    private final Context context;
    private final WindowManager windows;
    private final TripOverlayPreferences preferences;
    private final int maximumWidth;
    private final Runnable failed;
    private final Rect movementBounds=new Rect();
    private WindowManager.LayoutParams layout;
    private View view;
    private boolean dragging;
    private float touchX;
    private int touchWindowX;
    FloatingPanelPosition(Context context,WindowManager windows,TripOverlayPreferences preferences,int maximumWidth,Runnable failed) {
        this.context=context;this.windows=windows;this.preferences=preferences;this.maximumWidth=maximumWidth;this.failed=failed;
    }
    void attach(PanelDragLayout panel,String title,float opacity) {
        view=panel;
        layout=new WindowManager.LayoutParams(1,ViewGroup.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        |WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        if(Build.VERSION.SDK_INT>=30)layout.setFitInsetsTypes(0);
        layout.gravity=Gravity.TOP|Gravity.LEFT;layout.alpha=opacity;layout.setTitle(title);
        panel.setPanelDragListener(this::drag);
        place(false);
        panel.setOnApplyWindowInsetsListener((v,insets)->{v.post(()->{if(view==v)try{place(true);}catch(RuntimeException unavailable){failed.run();}});return insets;});
        windows.addView(panel,layout);
    }
    void refresh() { if(view!=null)place(true); }
    private Rect availableBounds() {
        if(Build.VERSION.SDK_INT>=30) {
            WindowMetrics metrics=windows.getCurrentWindowMetrics();Rect bounds=metrics.getBounds();
            Insets insets=metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            return new Rect(insets.left,insets.top,bounds.width()-insets.right,bounds.height()-insets.bottom);
        }
        DisplayMetrics metrics=new DisplayMetrics();windows.getDefaultDisplay().getRealMetrics(metrics);
        WindowInsets insets=view==null?null:view.getRootWindowInsets();
        return insets==null?new Rect(0,0,metrics.widthPixels,metrics.heightPixels):new Rect(insets.getStableInsetLeft(),insets.getStableInsetTop(),
                metrics.widthPixels-insets.getStableInsetRight(),metrics.heightPixels-insets.getStableInsetBottom());
    }
    private void place(boolean attached) {
        if(view==null)return;
        Rect available=availableBounds();
        int margin=Math.min(dp(16),Math.max(0,Math.min(available.width(),available.height())/4));
        int width=Math.min(dp(maximumWidth),Math.max(1,available.width()-2*margin));
        int height=Math.max(1,available.height()-2*margin);
        if(view instanceof TripLocationCard card)card.setMaximumHeight(height);
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.AT_MOST));
        movementBounds.set(available.left+margin,available.top+margin,available.right-margin-width,
                Math.max(available.top+margin,available.bottom-margin-view.getMeasuredHeight()));
        int x=dragging?clamp(layout.x,movementBounds.left,movementBounds.right)
                :movementBounds.left+Math.round(preferences.horizontal()*movementBounds.width());
        int y=movementBounds.top+Math.round(preferences.vertical()*movementBounds.height());
        boolean changed=layout.width!=width || layout.x!=x || layout.y!=y;
        layout.width=width;layout.x=x;layout.y=y;
        if(attached&&changed)windows.updateViewLayout(view,layout);
    }
    private boolean drag(View ignored,MotionEvent event) {
        if(view==null)return true;
        try { switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {touchX=event.getRawX();touchWindowX=layout.x;dragging=true;}
            case MotionEvent.ACTION_MOVE -> {
                layout.x=clamp(Math.round(touchWindowX+event.getRawX()-touchX),movementBounds.left,movementBounds.right);
                windows.updateViewLayout(view,layout);
            }
            case MotionEvent.ACTION_UP -> {
                boolean saved=preferences.savePosition(movementBounds.width()==0?preferences.horizontal():
                        (layout.x-movementBounds.left)/(float)movementBounds.width(),preferences.vertical());
                dragging=false;
                if(!saved){Toast.makeText(context,R.string.trip_overlay_save_error,Toast.LENGTH_LONG).show();place(true);}
            }
            case MotionEvent.ACTION_CANCEL -> {dragging=false;place(true);}
            default -> { }
        }
        } catch(RuntimeException unavailable) { failed.run(); }
        return true;
    }
    void remove() {
        if(view!=null)try{windows.removeViewImmediate(view);}catch(RuntimeException ignored){ }
        view=null;layout=null;dragging=false;
    }
    private static int clamp(int value,int minimum,int maximum){return Math.max(minimum,Math.min(maximum,value));}
    private int dp(int value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
}
