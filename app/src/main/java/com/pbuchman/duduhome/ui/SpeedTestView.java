package com.pbuchman.duduhome.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.pbuchman.duduhome.R;
import com.pbuchman.duduhome.speed.SpeedConnection;
import java.text.NumberFormat;
import java.util.Locale;

// Constructed programmatically with its button callbacks.
@SuppressLint("ViewConstructor")
final class SpeedTestView extends FrameLayout {
    private static final int LIGHT = 0xfff4f7fc;
    private static final int MUTED = 0xffb4c2d2;
    private static final int SKY = 0xffbddaf4;
    private static final int TEAL = 0xff84d3c1;
    private static final int INK = 0xff172b36;
    private static final int RAISED = 0xff1c3042;
    private static final int SEGMENT = 0xff34495b;
    private static final int DIVIDER = 0xff425465;
    private static final int EMPTY = 0xff96a8b8;
    private static final int MATCH = LayoutParams.MATCH_PARENT;
    private static final int WRAP = LayoutParams.WRAP_CONTENT;
    private static final int NONE = 0;

    private final NumberFormat speedFormat = NumberFormat.getNumberInstance(Locale.forLanguageTag("pl"));
    private final NumberFormat pingFormat = NumberFormat.getIntegerInstance(Locale.forLanguageTag("pl"));
    private final boolean narrow;
    private final boolean compact;
    private final View[] segments = new View[4];
    private final Metric download;
    private final Metric upload;
    private final Metric ping;
    private final TextView stage;
    private final TextView network;
    private final ImageView networkIcon;
    private final TextView errorTitle;
    private final TextView errorCopy;
    private final ImageView errorIcon;
    private final LinearLayout readings;
    private final LinearLayout error;
    private final Button cancel;
    private final Button retry;
    private final Button back;

    SpeedTestView(Context context, Runnable onCancel, Runnable onRetry, Runnable onBack) {
        super(context);
        int width = getResources().getConfiguration().screenWidthDp;
        compact = width < 580;
        narrow = width < 430;
        int inset = dp(compact ? 16 : 26);
        speedFormat.setMinimumFractionDigits(1);
        speedFormat.setMaximumFractionDigits(1);
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xff111e2b, 0xff172c3a}));
        setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 35) {
                var safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            }
            return insets;
        });

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        addView(scroll, new LayoutParams(MATCH, MATCH));
        FrameLayout holder = new FrameLayout(context);
        holder.setPadding(inset, inset, inset, inset);
        scroll.addView(holder, new ScrollView.LayoutParams(MATCH, WRAP));

        LinearLayout content = column();
        int contentWidth = width > 880 ? dp(820) : MATCH;
        holder.addView(content, new LayoutParams(contentWidth, WRAP, Gravity.CENTER));
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(narrow ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout brand = row();
        header.addView(brand, narrow ? new LinearLayout.LayoutParams(MATCH, WRAP)
                : new LinearLayout.LayoutParams(0, WRAP, 1f));
        ImageView gauge = new ImageView(context);
        showIcon(gauge, R.drawable.speed_screen_heading_gauge, INK, SKY, compact ? 34 : 40, 11);
        brand.addView(gauge, new LinearLayout.LayoutParams(dp(compact ? 34 : 40), dp(compact ? 34 : 40)));
        TextView title = label(compact ? 23 : 30, LIGHT, true);
        title.setText(R.string.speed_title);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        spaceLeft(brand, title, 10);
        LinearLayout connection = row();
        addSpaced(header, connection, WRAP, narrow ? 8 : 0);
        networkIcon = new ImageView(context);
        networkIcon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        connection.addView(networkIcon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        network = label(compact ? 13 : 16, MUTED, false);
        network.setId(R.id.speed_network);
        spaceLeft(connection, network, 6);

        readings = column();
        addSpaced(content, readings, MATCH, compact ? 18 : 26);
        LinearLayout status = row();
        readings.addView(status, new LinearLayout.LayoutParams(MATCH, WRAP));
        stage = label(compact ? 19 : 25, LIGHT, true);
        stage.setId(R.id.speed_stage);
        stage.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        status.addView(stage, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView provider = label(compact ? 12 : 14, MUTED, false);
        provider.setText(R.string.speed_provider);
        spaceLeft(status, provider, 8);

        LinearLayout timeline = row();
        addSpaced(readings, timeline, MATCH, 10);
        timeline.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        for (int i = 0; i < segments.length; i++) {
            segments[i] = new View(context);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(4), 1f);
            size.setMarginStart(i == 0 ? 0 : dp(6));
            timeline.addView(segments[i], size);
        }

        LinearLayout table = new LinearLayout(context);
        table.setOrientation(narrow ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        table.setBackground(shape(RAISED, 0, 16));
        int tablePadding = dp(compact ? 13 : 22);
        table.setPadding(narrow ? tablePadding : 0, narrow ? 0 : tablePadding,
                narrow ? tablePadding : 0, narrow ? 0 : tablePadding);
        addSpaced(readings, table, MATCH, 16);
        download = metric(table, R.string.speed_download, R.drawable.speed_screen_download,
                R.id.speed_download_value, R.string.speed_mbps);
        divider(table);
        upload = metric(table, R.string.speed_upload, R.drawable.speed_screen_upload,
                R.id.speed_upload_value, R.string.speed_mbps);
        divider(table);
        ping = metric(table, R.string.speed_ping, R.drawable.speed_screen_ping,
                R.id.speed_ping_value, R.string.speed_ms);

        error = row();
        error.setBackground(shape(RAISED, 0, 16));
        error.setPadding(tablePadding, tablePadding, tablePadding, tablePadding);
        error.setMinimumHeight(dp(compact ? 116 : 148));
        addSpaced(content, error, MATCH, compact ? 18 : 26);
        errorIcon = new ImageView(context);
        int errorIconSize = dp(narrow ? 40 : 52);
        error.addView(errorIcon, new LinearLayout.LayoutParams(errorIconSize, errorIconSize));
        LinearLayout errorText = column();
        LinearLayout.LayoutParams errorTextSize = new LinearLayout.LayoutParams(0, WRAP, 1f);
        errorTextSize.setMarginStart(dp(compact ? 12 : 20));
        error.addView(errorText, errorTextSize);
        errorTitle = label(compact ? 20 : 26, LIGHT, true);
        errorTitle.setId(R.id.speed_error_title);
        errorTitle.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        errorText.addView(errorTitle, new LinearLayout.LayoutParams(MATCH, WRAP));
        errorCopy = label(compact ? 14 : 17, MUTED, false);
        errorCopy.setId(R.id.speed_error_copy);
        addSpaced(errorText, errorCopy, MATCH, 8);

        LinearLayout footer = new LinearLayout(context);
        footer.setOrientation(narrow && width < 360 ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        addSpaced(content, footer, MATCH, 16);
        cancel = button(footer, R.id.speed_cancel, R.string.speed_cancel, R.drawable.speed_screen_close, 0, onCancel);
        retry = button(footer, R.id.speed_retry, R.string.speed_retry, R.drawable.speed_screen_retry, 0, onRetry);
        back = button(footer, R.id.speed_back, R.string.speed_back, R.drawable.speed_screen_back, 8, onBack);
        running();
    }

    void connection(SpeedConnection current) {
        network.setText(current.label());
        int icon = switch (current.transport()) {
            case WIFI -> R.drawable.speed_screen_wifi;
            case CELLULAR -> R.drawable.speed_screen_cell;
            case NONE -> R.drawable.speed_screen_offline;
            default -> NONE;
        };
        networkIcon.setVisibility(icon == NONE ? GONE : VISIBLE);
        if (icon != NONE) {
            networkIcon.setImageResource(icon);
            networkIcon.setImageTintList(ColorStateList.valueOf(SKY));
        }
    }

    void running() {
        readings.setVisibility(VISIBLE);
        error.setVisibility(GONE);
        cancel.setVisibility(VISIBLE);
        retry.setVisibility(GONE);
        back.setVisibility(GONE);
        metrics(null, null, null);
        stage(0);
    }

    void stage(int current) {
        int text = switch (current) {
            case 1 -> R.string.speed_ping_running;
            case 2 -> R.string.speed_download_running;
            case 3 -> R.string.speed_upload_running;
            case 4 -> R.string.speed_result;
            default -> R.string.speed_connecting;
        };
        if (!getResources().getText(text).equals(stage.getText())) stage.setText(text);
        for (int i = 0; i < segments.length; i++) {
            segments[i].setBackground(shape(i < current ? TEAL : i == current ? SKY : SEGMENT, 0, 4));
        }
        recolor(download, current == 2);
        recolor(upload, current == 3);
        recolor(ping, current == 1);
    }

    void metrics(Double down, Double up, Double roundTripMs) {
        renderMetric(download, down == null ? null : speedFormat.format(down / 1_000_000));
        renderMetric(upload, up == null ? null : speedFormat.format(up / 1_000_000));
        renderMetric(ping, roundTripMs == null ? null : pingFormat.format(roundTripMs));
    }

    void result() {
        stage(4);
        footer(false);
    }

    void error(int title, int copy, boolean ready) {
        readings.setVisibility(GONE);
        error.setVisibility(VISIBLE);
        errorTitle.setText(title);
        errorCopy.setText(copy);
        showIcon(errorIcon, ready ? R.drawable.speed_screen_ready : R.drawable.speed_screen_offline,
                ready ? TEAL : 0xfff2d3a8, ready ? 0xff25463f : 0xff493c33, narrow ? 40 : 52, 28);
        footer(true);
    }

    private void footer(boolean failed) {
        boolean wasFocused = cancel.hasFocus();
        cancel.setVisibility(GONE);
        retry.setVisibility(VISIBLE);
        back.setVisibility(VISIBLE);
        retry.setText(failed ? R.string.speed_retry : R.string.speed_repeat);
        buttonColors(retry, failed);
        buttonColors(back, !failed);
        if (wasFocused) (failed ? retry : back).requestFocus();
    }

    private void renderMetric(Metric metric, String result) {
        metric.value.setText(result == null ? getResources().getText(R.string.speed_empty) : result);
        metric.value.setTextColor(result == null ? EMPTY : LIGHT);
    }

    private void recolor(Metric metric, boolean active) {
        int color = active ? TEAL : MUTED;
        metric.label.setTextColor(color);
        Drawable[] icons = metric.label.getCompoundDrawablesRelative();
        if (icons[0] != null) icons[0].setTint(color);
    }

    private record Metric(TextView label, TextView value) { }

    private Metric metric(LinearLayout table, int name, int icon, int valueId, int unit) {
        LinearLayout cell = new LinearLayout(getContext());
        cell.setOrientation(narrow ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        cell.setGravity(narrow ? Gravity.CENTER_VERTICAL : Gravity.START);
        cell.setPadding(narrow ? 0 : dp(compact ? 10 : 20), narrow ? dp(8) : 0,
                narrow ? 0 : dp(compact ? 10 : 20), narrow ? dp(8) : 0);
        table.addView(cell, narrow ? new LinearLayout.LayoutParams(MATCH, WRAP)
                : new LinearLayout.LayoutParams(0, WRAP, 1f));

        TextView metricLabel = label(compact ? 14 : 18, MUTED, false);
        metricLabel.setText(name);
        withIcon(metricLabel, icon, 17, MUTED);
        cell.addView(metricLabel, narrow ? new LinearLayout.LayoutParams(0, WRAP, 1f)
                : new LinearLayout.LayoutParams(WRAP, WRAP));
        LinearLayout reading = row();
        reading.setGravity(Gravity.BOTTOM);
        addSpaced(cell, reading, WRAP, narrow ? 0 : 8);
        TextView value = label(narrow ? 28 : compact ? 32 : 48, LIGHT, true);
        value.setId(valueId);
        value.setFontFeatureSettings("tnum");
        value.setLetterSpacing(-0.04f);
        value.setMaxLines(1);
        reading.addView(value, new LinearLayout.LayoutParams(WRAP, WRAP));
        TextView units = label(compact ? 12 : 15, MUTED, false);
        units.setText(unit);
        units.setPadding(0, 0, 0, dp(3));
        spaceLeft(reading, units, 6);
        return new Metric(metricLabel, value);
    }

    private void divider(LinearLayout table) {
        View line = new View(getContext());
        line.setBackgroundColor(DIVIDER);
        table.addView(line, new LinearLayout.LayoutParams(narrow ? MATCH : dp(1), narrow ? dp(1) : MATCH));
    }

    private Button button(LinearLayout parent, int id, int text, int icon, int margin, Runnable click) {
        Button button = new Button(getContext());
        button.setId(id);
        button.setTag(icon);
        button.setText(text);
        button.setTextSize(compact ? 14 : 16);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(compact ? 10 : 15), dp(6), dp(compact ? 10 : 15), dp(6));
        button.setOnClickListener(view -> click.run());
        buttonColors(button, false);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(WRAP, WRAP);
        if (parent.getOrientation() == LinearLayout.VERTICAL) size.topMargin = dp(margin);
        else size.setMarginStart(dp(margin));
        parent.addView(button, size);
        return button;
    }

    private void buttonColors(Button button, boolean primary) {
        int tint = primary ? INK : LIGHT;
        button.setTextColor(tint);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x36172b36 : 0x55bddaf4),
                shape(primary ? SKY : 0xff203549, primary ? 0 : 0xff60758a, 12), null));
        withIcon(button, (int) button.getTag(), 17, tint);
    }

    private void withIcon(TextView text, int icon, int size, int tint) {
        Drawable picture = getContext().getDrawable(icon).mutate();
        picture.setTint(tint);
        picture.setBounds(0, 0, dp(size), dp(size));
        text.setCompoundDrawablesRelative(picture, null, null, null);
        text.setCompoundDrawablePadding(dp(6));
    }

    private void showIcon(ImageView view, int icon, int tint, int background, int size, int radius) {
        view.setImageResource(icon);
        view.setImageTintList(ColorStateList.valueOf(tint));
        view.setBackground(shape(background, 0, radius));
        int padding = dp(size >= 48 ? 13 : 8);
        view.setPadding(padding, padding, padding, padding);
        view.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    private GradientDrawable shape(int color, int border, int radius) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(color);
        result.setCornerRadius(dp(radius));
        if (border != 0) result.setStroke(dp(1), border);
        return result;
    }

    private TextView label(int sp, int color, boolean emphasis) {
        TextView text = new TextView(getContext());
        text.setTextColor(color);
        text.setTextSize(sp);
        text.setTypeface(Typeface.create(emphasis ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        text.setIncludeFontPadding(false);
        return text;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private void spaceLeft(LinearLayout group, View child, int margin) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(WRAP, WRAP);
        size.setMarginStart(dp(margin));
        group.addView(child, size);
    }

    private void addSpaced(LinearLayout group, View child, int width, int margin) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(width, WRAP);
        size.topMargin = dp(margin);
        group.addView(child, size);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
