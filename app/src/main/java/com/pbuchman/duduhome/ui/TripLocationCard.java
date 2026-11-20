package com.pbuchman.duduhome.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
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
    private final ScrollView scroll;
    private final TextView city, road, tail;
    private final LinearLayout streetRows;
    private final String dragDescription;
    private int maximumHeight = Integer.MAX_VALUE;
    private String street = "";
    private int roadWidth = -1;
    private float measuredTextSize;

    public TripLocationCard(Context context, OnClickListener open) {
        this(context, open, v -> { }, null);
    }

    public TripLocationCard(Context context, OnClickListener open, OnClickListener hide, OnTouchListener drag) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(18), dp(14), dp(12), dp(14));
        setMinimumHeight(dp(112));
        setBackgroundResource(R.drawable.compact_panel);
        setPanelDragListener(drag);
        dragDescription = context.getString(R.string.trip_overlay_drag_description);

        scroll = new ScrollView(context);
        LinearLayout address = new LinearLayout(context);
        address.setOrientation(VERTICAL);
        address.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        address.setOnClickListener(open);
        city = line(16);
        city.setId(R.id.trip_locality);
        city.setTextColor(Color.rgb(178, 195, 215));
        address.addView(city, rowParams());

        streetRows = new LinearLayout(context);
        streetRows.setOrientation(VERTICAL);
        road = streetLine(R.id.trip_street);
        tail = streetLine(R.id.trip_street_tail);
        tail.setMarqueeRepeatLimit(-1);
        // Keep the chosen text size even if TextView would squeeze a slight overflow.
        tail.setTextScaleX(1f);
        streetRows.addView(road, rowParams());
        streetRows.addView(tail, rowParams());
        LayoutParams streetParams = rowParams();
        streetParams.topMargin = dp(5);
        address.addView(streetRows, streetParams);

        scroll.addView(address, new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        Button close = new Button(context);
        close.setId(R.id.trip_overlay_close);
        close.setText("×");
        close.setTextSize(28);
        close.setMinWidth(0);
        close.setPadding(0, 0, 0, 0);
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setTextColor(context.getColor(R.color.text_primary));
        close.setContentDescription(context.getString(R.string.trip_overlay_close));
        close.setOnClickListener(hide);
        LayoutParams closeParams = new LayoutParams(dp(48), dp(48));
        closeParams.leftMargin = dp(8);
        closeParams.gravity = Gravity.TOP;
        addView(close, closeParams);
        setOnClickListener(open);
        setFocusable(true);
    }

    public void setMaximumHeight(int height) {
        if (maximumHeight != height) {
            maximumHeight = height;
            requestLayout();
        }
    }

    public void render(String locality, String street) {
        boolean localityChanged = !TextUtils.equals(city.getText(), locality);
        boolean streetChanged = !this.street.equals(street);
        if (!localityChanged && !streetChanged) return;
        if (localityChanged) city.setText(locality);
        if (streetChanged) {
            this.street = street;
            roadWidth = -1;
            requestLayout();
        }
        String address = street.isEmpty() ? locality : locality + "\n" + street;
        setContentDescription(address + ". " + dragDescription);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int limit = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED
                ? maximumHeight : Math.min(maximumHeight, MeasureSpec.getSize(heightSpec));
        int bounded = limit == Integer.MAX_VALUE ? heightSpec : MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST);
        super.onMeasure(widthSpec, bounded);
        int width = Math.max(0, scroll.getMeasuredWidth() - scroll.getPaddingLeft() - scroll.getPaddingRight()
                - road.getCompoundPaddingLeft() - road.getCompoundPaddingRight());
        if (width != roadWidth || road.getTextSize() != measuredTextSize) {
            roadWidth = width;
            measuredTextSize = road.getTextSize();
            layoutStreet(width);
            super.onMeasure(widthSpec, bounded);
        }
    }

    private void layoutStreet(int width) {
        String text = street.trim().replace('\n', ' ').replace('\r', ' ');
        int prefixEnd = fittingPrefixEnd(text, width);
        String first = text.substring(0, prefixEnd);
        String second = text.substring(prefixEnd).trim();
        boolean moving = !second.isEmpty() && Layout.getDesiredWidth(second, tail.getPaint()) > width;
        // A new street or viewport restarts even when the second row's text is unchanged.
        if (tail.isSelected()) tail.setSelected(false);
        TextUtils.TruncateAt ellipsize = moving ? TextUtils.TruncateAt.MARQUEE : null;
        if (tail.getEllipsize() != ellipsize) tail.setEllipsize(ellipsize);
        setRow(road, first);
        setRow(tail, second);
        streetRows.setVisibility(text.isEmpty() ? GONE : VISIBLE);
        // Selection drives the platform marquee without granting the floating window focus.
        if (moving) tail.setSelected(true);
    }

    private int fittingPrefixEnd(String text, int width) {
        if (Layout.getDesiredWidth(text, road.getPaint()) <= width) return text.length();
        int prefixEnd = 0;
        for (int index = 1; index < text.length(); index++) {
            if (Character.isWhitespace(text.charAt(index)) && !Character.isWhitespace(text.charAt(index - 1))) {
                if (Layout.getDesiredWidth(text, 0, index, road.getPaint()) > width) break;
                prefixEnd = index;
            }
        }
        return prefixEnd;
    }

    private static void setRow(TextView row, String text) {
        if (!TextUtils.equals(row.getText(), text)) row.setText(text);
        row.setVisibility(text.isEmpty() ? GONE : VISIBLE);
    }

    private LayoutParams rowParams() {
        return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
    }

    private TextView streetLine(int id) {
        TextView row = line(18.75f);
        row.setId(id);
        row.setSingleLine(true);
        row.setTextDirection(View.TEXT_DIRECTION_LTR);
        row.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return row;
    }

    private TextView line(float size) {
        TextView row = new TextView(getContext());
        row.setTextSize(size);
        row.setTextColor(getContext().getColor(R.color.text_primary));
        row.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        return row;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
