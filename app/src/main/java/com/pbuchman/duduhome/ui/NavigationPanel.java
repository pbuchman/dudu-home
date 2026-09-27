package com.pbuchman.duduhome.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.Window;
import android.view.WindowManager;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import com.pbuchman.duduhome.R;
import com.pbuchman.duduhome.automation.HomeActions;
import com.pbuchman.duduhome.navigation.*;
import java.io.InputStream;

/** Owns only manual destination UI. Text and file data never become diagnostics. */
final class NavigationPanel {
    private static final int PICK_DOCUMENT = 201;
    private final Activity activity;
    private final MapsLauncher maps;
    private final android.content.res.Resources polishResources;
    private final java.util.function.BooleanSupplier allowed;
    private boolean picking, reading, launching;
    private Dialog selector;
    private int selectedSlot = -1, restoreSlot = -1;
    NavigationPanel(Activity activity, java.util.function.BooleanSupplier allowed, Bundle saved) {
        this(activity, allowed, saved, new MapsLauncher(activity));
    }
    NavigationPanel(Activity activity, java.util.function.BooleanSupplier allowed, Bundle saved, MapsLauncher maps) {
        this.activity = activity; this.allowed = allowed; this.maps = maps;
        // The current UI copy is Polish even when the head unit uses another system language.
        android.content.res.Configuration language = new android.content.res.Configuration(activity.getResources().getConfiguration());
        language.setLocale(java.util.Locale.forLanguageTag("pl"));
        polishResources = activity.createConfigurationContext(language).getResources();
        picking = saved != null && saved.getBoolean("navigation_picker");
        restoreSlot = saved == null ? -1 : saved.getInt("navigation_selector", -1);
    }
    boolean busy() { return picking || reading || selector != null; }
    void saveState(Bundle state) {
        state.putBoolean("navigation_picker", picking);
        state.putInt("navigation_selector", selectedSlot);
    }
    void destroy() { if (selector != null) selector.dismiss(); }
    void resumed() { launching = false; }
    void render() {
        NavigationConfig config;
        try { config = new NavigationStore(activity).load(); }
        catch (Exception ignored) { config = NavigationConfig.empty(); toast(R.string.navigation_invalid); }
        LinearLayout row = activity.findViewById(R.id.navigation_tiles);
        row.removeAllViews();
        boolean narrow = activity.getResources().getConfiguration().screenWidthDp < 600;
        row.setOrientation(narrow ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        for (int slot = 0; slot < NavigationConfig.SLOT_COUNT; slot++) {
            NavigationConfig.Slot group = config.slot(slot);
            boolean empty = group == null;
            boolean multiple = !empty && group.destinations().size() > 1;
            final int slotIndex = slot;
            LinearLayout tile = new LinearLayout(activity);
            tile.setId(new int[]{R.id.navigation_slot_1, R.id.navigation_slot_2, R.id.navigation_slot_3}[slot]);
            tile.setOrientation(LinearLayout.HORIZONTAL); tile.setGravity(Gravity.CENTER_VERTICAL);
            tile.setPadding(dp(16), dp(12), dp(16), dp(12));
            tile.setMinimumHeight(dp(132));
            tile.setBackgroundResource(empty ? R.drawable.navigation_empty
                    : new int[]{R.drawable.tile_gate, R.drawable.tile_cleaning, R.drawable.tile_mop}[slot]);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(narrow ? -1 : 0, -2, narrow ? 0 : 1);
            if (slot > 0) { if (narrow) size.topMargin = dp(12); else size.setMarginStart(dp(16)); }
            row.addView(tile, size);
            ImageView icon = new ImageView(activity);
            icon.setImageResource(empty ? R.drawable.ic_navigation_empty : switch (group.icon()) {
                case "home" -> R.drawable.art_navigation_home;
                case "squash" -> R.drawable.art_navigation_squash;
                default -> R.drawable.art_navigation_pin;
            });
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            tile.addView(icon, new LinearLayout.LayoutParams(dp(88), dp(94)));
            LinearLayout words = new LinearLayout(activity); words.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams wordsSize = new LinearLayout.LayoutParams(0, -2, 1); wordsSize.setMarginStart(dp(10));
            tile.addView(words, wordsSize);
            String title = empty ? activity.getString(R.string.navigation_slot, slot + 1) : group.label();
            TextView label = new TextView(activity); label.setText(title); label.setTextSize(22);
            label.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            label.setTextColor(activity.getColor(empty ? R.color.text_secondary : R.color.tile_ink));
            label.setMaxLines(2); label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); words.addView(label);
            TextView subtitle = new TextView(activity);
            String hint = empty ? activity.getString(R.string.navigation_empty)
                    : multiple ? polishResources.getQuantityString(R.plurals.navigation_places,
                            group.destinations().size(), group.destinations().size())
                    : activity.getString(R.string.navigation_go);
            subtitle.setText(hint); subtitle.setTextSize(16);
            subtitle.setTextColor(activity.getColor(empty ? R.color.text_secondary : R.color.tile_ink));
            subtitle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams subSize = new LinearLayout.LayoutParams(-1, -2); subSize.topMargin = dp(6);
            words.addView(subtitle, subSize);
            if (!empty) {
                ImageView arrow = new ImageView(activity); arrow.setImageResource(multiple ? R.drawable.ic_navigation_chevron : R.drawable.ic_navigation_arrow);
                arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                LinearLayout.LayoutParams arrowSize = new LinearLayout.LayoutParams(dp(28), dp(28)); arrowSize.setMarginStart(dp(8));
                tile.addView(arrow, arrowSize);
            }
            tile.setContentDescription(title + ", " + hint);
            tile.setClickable(true); tile.setFocusable(true);
            tile.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            tile.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName(Button.class.getName());
                }
            });
            tile.setOnClickListener(v -> {
                if (busy() || launching || !allowed.getAsBoolean()) return;
                if (empty) {
                    new AlertDialog.Builder(activity).setTitle(R.string.navigation_empty)
                            .setMessage(R.string.navigation_empty_help).setPositiveButton("OK", null).show();
                    return;
                }
                if (multiple) showSelector(slotIndex, group);
                else launch(group.destinations().get(0));
            });
        }
        int restore = restoreSlot;
        restoreSlot = -1;
        if (restore >= 0 && restore < NavigationConfig.SLOT_COUNT && !busy() && allowed.getAsBoolean()) {
            NavigationConfig.Slot group = config.slot(restore);
            if (group != null && group.destinations().size() > 1) showSelector(restore, group);
        }
    }
    private boolean launch(NavigationConfig.Destination destination) {
        if (launching || !allowed.getAsBoolean()) return false;
        MapsLauncher.Result result = maps.launch(destination);
        switch (result) {
            case REQUESTED -> launching = true;
            case MISSING -> toast(R.string.navigation_maps_missing);
            case STORAGE_FAILED -> toast(R.string.navigation_storage_failed);
            case FAILED -> toast(R.string.navigation_launch_failed);
        }
        return result == MapsLauncher.Result.REQUESTED;
    }
    private void showSelector(int slot, NavigationConfig.Slot group) {
        if (busy() || launching || !allowed.getAsBoolean()) return;
        Dialog dialog = new Dialog(activity) {
            @Override public void dismiss() {
                super.dismiss();
                // Clear synchronously: a queued old dismiss callback must not clear a new selector.
                if (selector == this) selectionDismissed(slot);
            }
        };
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(28), dp(28), dp(28), dp(24));
        content.setBackground(shape(0xff1a303b, 28));
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView art = new ImageView(activity);
        art.setImageResource(switch (group.icon()) {
            case "home" -> R.drawable.art_navigation_home;
            case "squash" -> R.drawable.art_navigation_squash;
            default -> R.drawable.art_navigation_pin;
        });
        art.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(art, new LinearLayout.LayoutParams(dp(68), dp(68)));
        LinearLayout heading = new LinearLayout(activity); heading.setOrientation(LinearLayout.VERTICAL);
        TextView title = words(group.label(), 32, R.color.text_primary);
        if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        heading.addView(title);
        heading.addView(words(activity.getString(R.string.navigation_choose), 20, R.color.text_secondary));
        LinearLayout.LayoutParams headingSize = new LinearLayout.LayoutParams(0, -2, 1);
        headingSize.setMarginStart(dp(16)); header.addView(heading, headingSize);
        ImageButton close = new ImageButton(activity);
        close.setImageResource(R.drawable.ic_navigation_close);
        close.setContentDescription(activity.getString(R.string.navigation_close));
        close.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44ffffff), shape(0xff263f4b, 16), null));
        close.setOnClickListener(v -> dialog.dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(56), dp(56)));
        content.addView(header);
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        LinearLayout list = new LinearLayout(activity); list.setOrientation(LinearLayout.VERTICAL);
        for (NavigationConfig.Destination d : group.destinations()) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(18), dp(18), dp(18), dp(18)); row.setMinimumHeight(dp(124));
            row.setBackgroundResource(R.drawable.tile_cleaning);
            ImageView pin = new ImageView(activity); pin.setImageResource(R.drawable.art_navigation_pin);
            pin.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(pin, new LinearLayout.LayoutParams(dp(64), dp(74)));
            LinearLayout labels = new LinearLayout(activity); labels.setOrientation(LinearLayout.VERTICAL);
            labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            labels.addView(words(d.label(), 26, R.color.tile_ink));
            if (!d.address().isEmpty()) {
                TextView address = words(d.address(), 18, R.color.tile_ink);
                address.setPadding(0, dp(8), 0, 0); labels.addView(address);
            }
            LinearLayout.LayoutParams labelsSize = new LinearLayout.LayoutParams(0, -2, 1);
            labelsSize.setMarginStart(dp(16)); row.addView(labels, labelsSize);
            TextView go = words(activity.getString(R.string.navigation_drive), 20, R.color.tile_ink);
            go.setPadding(dp(12), 0, dp(10), 0);
            go.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); row.addView(go);
            ImageView arrow = new ImageView(activity); arrow.setImageResource(R.drawable.ic_navigation_arrow);
            arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(arrow, new LinearLayout.LayoutParams(dp(24), dp(24)));
            row.setContentDescription(d.label() + (d.address().isEmpty() ? "" : ", " + d.address())
                    + ", " + activity.getString(R.string.navigation_go));
            row.setClickable(true); row.setFocusable(true);
            row.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName(Button.class.getName());
                }
            });
            row.setOnClickListener(v -> {
                if (!allowed.getAsBoolean()) { dialog.dismiss(); return; }
                if (launch(d)) dialog.dismiss();
            });
            LinearLayout.LayoutParams rowSize = new LinearLayout.LayoutParams(-1, -2);
            if (list.getChildCount() > 0) rowSize.topMargin = dp(14);
            list.addView(row, rowSize);
        }
        scroll.addView(list);
        // Measure wrapped labels at the actual dialog width before bounding the scroll area.
        int availableWidth = activity.getWindow().getDecorView().getWidth();
        int availableHeight = activity.getWindow().getDecorView().getHeight();
        if (availableWidth == 0) availableWidth = activity.getResources().getDisplayMetrics().widthPixels;
        if (availableHeight == 0) availableHeight = activity.getResources().getDisplayMetrics().heightPixels;
        int width = Math.min(dp(840), availableWidth - dp(32));
        header.measure(View.MeasureSpec.makeMeasureSpec(width - dp(56), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        list.measure(View.MeasureSpec.makeMeasureSpec(width - dp(56), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        TextView footer = words(activity.getString(R.string.navigation_choice_help), 16, R.color.text_secondary);
        footer.measure(View.MeasureSpec.makeMeasureSpec(width - dp(56), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int listHeight = Math.max(dp(64), availableHeight - header.getMeasuredHeight() - footer.getMeasuredHeight() - dp(132));
        LinearLayout.LayoutParams scrollSize = new LinearLayout.LayoutParams(-1, Math.min(listHeight, list.getMeasuredHeight()));
        scrollSize.topMargin = dp(24); content.addView(scroll, scrollSize);
        LinearLayout.LayoutParams footerSize = new LinearLayout.LayoutParams(-1, -2); footerSize.topMargin = dp(18);
        content.addView(footer, footerSize);
        dialog.setContentView(content); dialog.setCanceledOnTouchOutside(true);
        selector = dialog; selectedSlot = slot;
        View menu = activity.findViewById(R.id.menu_content);
        menu.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes(); attributes.dimAmount = .72f;
            window.setAttributes(attributes);
        }
        HomeActions.schedulingChanged();
    }
    private void selectionDismissed(int slot) {
        selector = null; selectedSlot = -1;
        activity.findViewById(R.id.menu_content).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        if (!activity.isDestroyed() && !activity.isFinishing()) {
            View tile = activity.findViewById(new int[]{R.id.navigation_slot_1, R.id.navigation_slot_2, R.id.navigation_slot_3}[slot]);
            if (tile != null) tile.requestFocus();
        }
        HomeActions.schedulingChanged();
    }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable result = new GradientDrawable(); result.setColor(color); result.setCornerRadius(dp(radius)); return result;
    }
    private TextView words(String value, int size, int color) {
        TextView view = new TextView(activity); view.setText(value); view.setTextSize(size);
        view.setTextColor(activity.getColor(color)); return view;
    }
    void pick() {
        if (busy() || !allowed.getAsBoolean()) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,
                        new String[]{"application/json", "text/json", "text/plain", "application/octet-stream"});
        picking = true; HomeActions.schedulingChanged();
        try { activity.startActivityForResult(intent, PICK_DOCUMENT); }
        catch (RuntimeException ignored) { picking = false; toast(R.string.navigation_picker_missing); }
    }
    boolean result(int request, int result, Intent data) {
        if (request != PICK_DOCUMENT) return false;
        picking = false;
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) {
            HomeActions.schedulingChanged(); return true;
        }
        if (!allowed.getAsBoolean()) { HomeActions.schedulingChanged(); return true; }
        reading = true; toast(R.string.navigation_loading); HomeActions.schedulingChanged();
        android.net.Uri uri = data.getData();
        // Read on a worker. No persistent URI grant, permission to broad storage, or URI logging.
        new Thread(() -> {
            NavigationConfig parsed = null;
            try (InputStream input = activity.getContentResolver().openInputStream(uri)) {
                parsed = NavigationStore.readDocument(input);
            } catch (Exception ignored) { /* Only the fixed user-facing error is exposed. */ }
            NavigationConfig document = parsed;
            activity.runOnUiThread(() -> {
                reading = false;
                if (activity.isDestroyed() || activity.isFinishing()) return;
                try {
                    if (document == null || !allowed.getAsBoolean()) throw new java.io.IOException();
                    new NavigationStore(activity).save(document);
                    render(); toast(R.string.navigation_imported);
                } catch (Exception ignored) { toast(R.string.navigation_import_failed); }
                HomeActions.schedulingChanged();
            });
        }, "navigation-document").start();
        return true;
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private void toast(int text) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show(); }
}
