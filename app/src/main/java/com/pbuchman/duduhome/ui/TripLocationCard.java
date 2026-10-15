package com.pbuchman.duduhome.ui;

import android.content.Context;
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

/** Readable address with independent move/hide controls; opening remains passive. */
public final class TripLocationCard extends LinearLayout {
    private final TextView city, road;
    private final Button fullName;
    private int maximumHeight = Integer.MAX_VALUE;
    public TripLocationCard(Context context, OnClickListener open) {
        this(context, open, v -> { }, (v, event) -> true);
    }
    public TripLocationCard(Context context, OnClickListener open, OnClickListener hide, OnTouchListener drag) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(dp(16), dp(10), dp(16), dp(10));
        setGravity(Gravity.CENTER_VERTICAL);
        setBackgroundResource(R.drawable.progress_panel);
        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(HORIZONTAL);
        controls.setBaselineAligned(false); controls.setGravity(Gravity.CENTER_VERTICAL);
        Button handle = button(R.id.trip_overlay_drag, R.string.trip_overlay_drag);
        handle.setContentDescription(context.getString(R.string.trip_overlay_drag_description));
        handle.setOnTouchListener(drag);
        // A handle tap is intentionally inert, including accessibility clicks.
        handle.setOnClickListener(v -> { });
        controls.addView(handle, new LayoutParams(0, dp(48), 1));
        Button close = button(R.id.trip_overlay_close, R.string.trip_overlay_close);
        close.setText("×"); close.setTextSize(28);
        close.setPadding(0,0,0,0);
        close.setContentDescription(context.getString(R.string.trip_overlay_close));
        close.setOnClickListener(hide);
        LayoutParams closeParams = new LayoutParams(dp(56), dp(48)); closeParams.leftMargin = dp(8);
        controls.addView(close, closeParams);
        addView(controls, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        ScrollView scroll = new ScrollView(context);
        LinearLayout address = new LinearLayout(context); address.setOrientation(VERTICAL);
        city = line(24); city.setId(R.id.trip_locality);
        road = line(32); road.setId(R.id.trip_street);
        road.setMaxLines(3); road.setEllipsize(TextUtils.TruncateAt.END);
        road.setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE);
        road.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        road.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        fullName = button(R.id.trip_full_name, R.string.trip_full_name);
        fullName.setMinHeight(dp(76)); fullName.setMinWidth(dp(76));
        fullName.setVisibility(GONE); fullName.setOnClickListener(open);
        address.addView(city, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        address.addView(road, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        LayoutParams buttonParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        buttonParams.topMargin = dp(8); address.addView(fullName, buttonParams);
        address.setOnClickListener(open);
        scroll.addView(address, new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        setOnClickListener(open); setFocusable(true);
    }
    public void setMaximumHeight(int height) {
        if (maximumHeight != height) { maximumHeight = height; requestLayout(); }
    }
    public void render(String locality, String street) {
        if (!TextUtils.equals(city.getText(), locality)) city.setText(locality);
        if (!TextUtils.equals(road.getText(), street)) road.setText(street);
        setContentDescription(locality + "\n" + street);
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int limit = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED ? maximumHeight
                : Math.min(maximumHeight, MeasureSpec.getSize(heightSpec));
        int boundedHeight = limit == Integer.MAX_VALUE ? heightSpec : MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST);
        super.onMeasure(widthSpec, boundedHeight);
        // Resolve before wrap-content window height is consumed, including a short viewport.
        if (updateOverflow()) super.onMeasure(widthSpec, boundedHeight);
    }
    private boolean updateOverflow() {
        Layout layout = road.getLayout();
        if (layout == null) return false;
        boolean overflow = layout.getLineCount() > 3;
        for (int n = 0; n < layout.getLineCount(); n++) overflow |= layout.getEllipsisCount(n) > 0;
        int visibility = overflow ? VISIBLE : GONE;
        if (fullName.getVisibility() == visibility) return false;
        fullName.setVisibility(visibility);
        return true;
    }
    private Button button(int id, int text) {
        Button button = new Button(getContext()); button.setId(id); button.setText(text);
        button.setAllCaps(false); button.setTextSize(20); button.setMinHeight(dp(48)); button.setMinWidth(0);
        button.setBackgroundResource(R.drawable.button_secondary);
        button.setTextColor(getContext().getColor(R.color.text_primary));
        return button;
    }
    private TextView line(int size) {
        TextView view = new TextView(getContext());
        view.setTextSize(size); view.setTextColor(getContext().getColor(R.color.text_primary));
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
