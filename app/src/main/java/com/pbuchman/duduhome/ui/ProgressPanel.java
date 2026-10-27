package com.pbuchman.duduhome.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import com.pbuchman.duduhome.R;

/** Presentation only: keep the message wide, and its cancellation control inside short windows. */
public final class ProgressPanel extends PanelDragLayout {
    private int maximumHeight = Integer.MAX_VALUE;
    public ProgressPanel(Context context, AttributeSet attrs) { super(context, attrs); }
    public void setMaximumHeight(int height) {
        if (maximumHeight != height) { maximumHeight = height; requestLayout(); }
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        View body = findViewById(R.id.banner_body);
        View cancel = findViewById(R.id.banner_cancel_attempt);
        int width = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        int limit = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED
                ? maximumHeight : Math.min(maximumHeight, MeasureSpec.getSize(heightSpec));
        int bounded = limit == Integer.MAX_VALUE ? heightSpec : MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST);
        cancel.measure(MeasureSpec.makeMeasureSpec(Math.max(1, width), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int actionWidth = cancel.getVisibility() == GONE ? 0 : cancel.getMeasuredWidth() + dp(16);
        // Text follows Android's font setting. Move the action below before it squeezes the message.
        boolean stacked = actionWidth > 0 && width - actionWidth < dp(260) * getResources().getConfiguration().fontScale;
        setOrientation(stacked ? VERTICAL : HORIZONTAL);
        LinearLayout.LayoutParams action = (LinearLayout.LayoutParams) cancel.getLayoutParams();
        action.setMarginStart(stacked ? 0 : dp(16)); action.topMargin = stacked ? dp(10) : 0;
        action.gravity = stacked ? Gravity.END : Gravity.CENTER_VERTICAL;
        LinearLayout.LayoutParams content = (LinearLayout.LayoutParams) body.getLayoutParams();
        content.width = stacked ? LayoutParams.MATCH_PARENT : 0; content.weight = stacked ? 0 : 1;
        content.height = LayoutParams.WRAP_CONTENT;
        if (stacked && limit != Integer.MAX_VALUE) {
            int remaining = Math.max(1, limit - getPaddingTop() - getPaddingBottom()
                    - cancel.getMeasuredHeight() - action.topMargin);
            body.measure(MeasureSpec.makeMeasureSpec(Math.max(1, width), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(remaining, MeasureSpec.AT_MOST));
            content.height = body.getMeasuredHeight();
        }
        super.onMeasure(widthSpec, bounded);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
