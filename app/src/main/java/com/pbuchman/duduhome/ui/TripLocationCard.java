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
import android.widget.TextView;
import com.pbuchman.duduhome.R;

/** A bounded, readable location card; its only action opens the passive full screen. */
public final class TripLocationCard extends LinearLayout {
    private final TextView city, road;
    private final Button fullName;
    public TripLocationCard(Context context, OnClickListener open) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(dp(16), dp(10), dp(16), dp(10));
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(dp(64));
        setBackgroundResource(R.drawable.progress_panel);
        city = line(24); city.setId(R.id.trip_locality);
        road = line(32); road.setId(R.id.trip_street);
        road.setMaxLines(3); road.setEllipsize(TextUtils.TruncateAt.END);
        road.setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE);
        road.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        road.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        fullName = new Button(context); fullName.setId(R.id.trip_full_name);
        fullName.setText(R.string.trip_full_name); fullName.setAllCaps(false);
        fullName.setTextSize(20); fullName.setMinHeight(dp(76)); fullName.setMinWidth(dp(76));
        fullName.setBackgroundResource(R.drawable.button_secondary);
        fullName.setTextColor(context.getColor(R.color.text_primary));
        fullName.setVisibility(GONE); fullName.setOnClickListener(open);
        addView(city, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(road, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        LayoutParams buttonParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        buttonParams.topMargin = dp(8); addView(fullName, buttonParams);
        setOnClickListener(open); setFocusable(true);
    }
    public void render(String locality, String street) {
        if (!TextUtils.equals(city.getText(), locality)) city.setText(locality);
        if (!TextUtils.equals(road.getText(), street)) road.setText(street);
        setContentDescription(locality + "\n" + street);
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        // Decide expansion before the window consumes our wrap-content height. Changing a
        // child's visibility from onLayout can leave an overlay at its preceding height.
        if (updateOverflow()) super.onMeasure(widthSpec, heightSpec);
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
    private TextView line(int size) {
        TextView view = new TextView(getContext());
        view.setTextSize(size); view.setTextColor(getContext().getColor(R.color.text_primary));
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
