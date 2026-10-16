package com.pbuchman.duduhome.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

/** A held non-button area owns its entire drag, including release; child taps stay native. */
public class PanelDragLayout extends LinearLayout {
    public static final long HOLD_MS=350;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final int slop;
    private OnTouchListener drag;
    private MotionEvent down;
    private boolean held, suppressed;
    private final Runnable arm=this::arm;
    public PanelDragLayout(Context context) { this(context,null); }
    public PanelDragLayout(Context context,AttributeSet attrs) {
        super(context,attrs);slop=ViewConfiguration.get(context).getScaledTouchSlop();
    }
    public void setPanelDragListener(OnTouchListener listener) { drag=listener; }
    private void arm() {
        if(down==null || drag==null || held)return;
        held=true;
        MotionEvent cancel=MotionEvent.obtain(down);cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.dispatchTouchEvent(cancel);cancel.recycle();
        setAlpha(.72f);setActivated(true);performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        drag.onTouch(this,down);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN) {
            reset();
            if(drag!=null && !buttonAt(this,event.getX(),event.getY())) {
                down=MotionEvent.obtain(event);handler.postDelayed(arm,HOLD_MS);
            }
        } else if(down!=null && !held && action==MotionEvent.ACTION_MOVE) {
            // Event time also makes synthetic native input deterministic.
            if(event.getEventTime()-down.getEventTime()>=HOLD_MS)arm();
            else {
                float dx=event.getX()-down.getX(),dy=event.getY()-down.getY();
                if(Math.abs(dx)>slop && Math.abs(dx)>=Math.abs(dy)) {
                    MotionEvent cancel=MotionEvent.obtain(event);cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);cancel.recycle();reset();suppressed=true;
                } else if(Math.hypot(dx,dy)>slop)reset();
            }
        }
        if(suppressed) {
            if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL)reset();
            return true;
        }
        if(held) {
            if(action!=MotionEvent.ACTION_DOWN)drag.onTouch(this,event);
            if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL)reset();
            return true;
        }
        boolean handled=super.dispatchTouchEvent(event);
        if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL)reset();
        return handled || down!=null;
    }
    private static boolean buttonAt(View view,float x,float y) {
        if(view.getVisibility()!=VISIBLE || x<0 || y<0 || x>=view.getWidth() || y>=view.getHeight())return false;
        if(view instanceof Button)return true;
        if(view instanceof ViewGroup group)for(int n=group.getChildCount()-1;n>=0;n--) {
            View child=group.getChildAt(n);
            if(buttonAt(child,x+group.getScrollX()-child.getLeft(),y+group.getScrollY()-child.getTop()))return true;
        }
        return false;
    }
    private void reset() {
        handler.removeCallbacks(arm);held=false;suppressed=false;setAlpha(1f);setActivated(false);
        if(down!=null){down.recycle();down=null;}
    }
    @Override protected void onDetachedFromWindow() { reset();super.onDetachedFromWindow(); }
}
