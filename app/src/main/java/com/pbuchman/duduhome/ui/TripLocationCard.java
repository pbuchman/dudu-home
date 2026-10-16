package com.pbuchman.duduhome.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.text.LineBreaker;
import android.text.Layout;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.pbuchman.duduhome.R;

/** Compact address card. All non-button space supports the held horizontal gesture. */
public final class TripLocationCard extends PanelDragLayout {
    private final TextView city,road;
    private final Button fullName;
    private int maximumHeight=Integer.MAX_VALUE;
    public TripLocationCard(Context context,OnClickListener open) { this(context,open,v->{},null); }
    public TripLocationCard(Context context,OnClickListener open,OnClickListener hide,OnTouchListener drag) {
        super(context);setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(18),dp(14),dp(12),dp(14));setMinimumHeight(dp(112));
        setBackgroundResource(R.drawable.compact_panel);setPanelDragListener(drag);
        ScrollView scroll=new ScrollView(context);
        LinearLayout address=new LinearLayout(context);address.setOrientation(VERTICAL);
        city=line(16);city.setId(R.id.trip_locality);city.setTextColor(Color.rgb(178,195,215));
        road=line(25);road.setId(R.id.trip_street);road.setMaxLines(3);road.setEllipsize(TextUtils.TruncateAt.END);
        road.setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE);road.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        road.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        fullName=button(R.id.trip_full_name,R.string.trip_full_name);fullName.setVisibility(GONE);fullName.setOnClickListener(open);
        address.addView(city,new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT));
        LayoutParams roadParams=new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT);roadParams.topMargin=dp(5);
        address.addView(road,roadParams);
        LayoutParams moreParams=new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT);moreParams.topMargin=dp(8);
        address.addView(fullName,moreParams);address.setOnClickListener(open);
        scroll.addView(address,new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT));
        addView(scroll,new LayoutParams(0,LayoutParams.WRAP_CONTENT,1));
        Button close=button(R.id.trip_overlay_close,R.string.trip_overlay_close);close.setText("×");close.setTextSize(28);
        close.setPadding(0,0,0,0);close.setBackgroundColor(Color.TRANSPARENT);
        close.setContentDescription(context.getString(R.string.trip_overlay_close));close.setOnClickListener(hide);
        LayoutParams closeParams=new LayoutParams(dp(48),dp(48));closeParams.leftMargin=dp(8);closeParams.gravity=Gravity.TOP;
        addView(close,closeParams);setOnClickListener(open);setFocusable(true);
    }
    public void setMaximumHeight(int height) { if(maximumHeight!=height){maximumHeight=height;requestLayout();} }
    public void render(String locality,String street) {
        if(!TextUtils.equals(city.getText(),locality))city.setText(locality);
        if(!TextUtils.equals(road.getText(),street))road.setText(street);
        road.setVisibility(street.isEmpty()?GONE:VISIBLE);
        setContentDescription(locality+"\n"+street+". "+getContext().getString(R.string.trip_overlay_drag_description));
    }
    @Override protected void onMeasure(int widthSpec,int heightSpec) {
        int limit=MeasureSpec.getMode(heightSpec)==MeasureSpec.UNSPECIFIED?maximumHeight:Math.min(maximumHeight,MeasureSpec.getSize(heightSpec));
        int bounded=limit==Integer.MAX_VALUE?heightSpec:MeasureSpec.makeMeasureSpec(limit,MeasureSpec.AT_MOST);
        super.onMeasure(widthSpec,bounded);if(updateOverflow())super.onMeasure(widthSpec,bounded);
    }
    private boolean updateOverflow() {
        Layout layout=road.getLayout();if(layout==null)return false;
        boolean overflow=road.getVisibility()==VISIBLE&&layout.getLineCount()>3;
        for(int n=0;n<layout.getLineCount();n++)overflow|=road.getVisibility()==VISIBLE&&layout.getEllipsisCount(n)>0;
        int visibility=overflow?VISIBLE:GONE;if(fullName.getVisibility()==visibility)return false;fullName.setVisibility(visibility);return true;
    }
    private Button button(int id,int text) {
        Button b=new Button(getContext());b.setId(id);b.setText(text);b.setAllCaps(false);b.setTextSize(16);
        b.setMinHeight(dp(48));b.setMinWidth(0);b.setBackgroundResource(R.drawable.button_secondary);
        b.setTextColor(getContext().getColor(R.color.text_primary));return b;
    }
    private TextView line(int size) {
        TextView v=new TextView(getContext());v.setTextSize(size);v.setTextColor(getContext().getColor(R.color.text_primary));
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return v;
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
